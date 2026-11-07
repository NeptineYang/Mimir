package com.neptine.mimir.service.schedule

import android.content.Context
import android.os.PowerManager
import com.neptine.mimir.agent.TaskLauncher
import com.neptine.mimir.core.contract.ScheduleRepeat
import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.data.db.dao.ScheduledTaskDao
import com.neptine.mimir.data.db.dao.TaskDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 定时任务的执行器（设计文档 §5.6）。
 *
 * 到点后的三件事：重排下一次、点亮屏幕、创建任务并等它到终态。
 *
 * 为什么必须点亮屏幕：实测息屏（Asleep）后虚拟屏窗口根节点全为 null，观察直接失败；
 * 亮屏但保持锁屏（不解锁）时任务可以完整跑通。所以这里只唤醒屏幕，不启动 Activity、
 * 不解锁、不显示任务内容。
 */
@Singleton
class ScheduledTaskRunner @Inject constructor(
    private val scheduledDao: ScheduledTaskDao,
    private val taskDao: TaskDao,
    private val launcher: TaskLauncher,
    private val scheduler: ScheduledTaskScheduler,
    @ApplicationContext private val context: Context,
) {

    /**
     * 功能：执行一条到点的定时任务
     * 参数：id 定时任务标识
     * 返回：无
     *
     * 先重排下一次再执行：执行过程中进程被杀，周期任务也不会丢掉后续触发。
     * 一次性任务在这里就置 `enabled=0`，避免闹钟重入时再跑一遍。
     */
    suspend fun run(id: String) {
        val entity = scheduledDao.find(id) ?: return
        if (!entity.enabled) return
        val repeat = runCatching { ScheduleRepeat.valueOf(entity.repeatKind) }
            .getOrDefault(ScheduleRepeat.ONCE)
        val days = parseDays(entity.repeatDays)
        val now = System.currentTimeMillis()

        if (repeat == ScheduleRepeat.ONCE) {
            scheduledDao.updateTrigger(id, entity.triggerAt, enabled = false)
        } else {
            val next = ScheduledTime.next(now, entity.hour, entity.minute, repeat, days)
            scheduledDao.updateTrigger(id, next, enabled = true)
            scheduler.schedule(entity.toContract().copy(triggerAt = next, enabled = true))
        }

        val lock = acquireWakeLock()
        try {
            val taskId = launcher.launch(entity.inputText, entity.allowL2a)
            scheduledDao.markRun(id, now, taskId, STATE_RUNNING)
            val finished = withTimeoutOrNull(MAX_WAIT_MS) {
                taskDao.observeById(taskId).first { row ->
                    row != null && runCatching { TaskState.valueOf(row.state).isTerminal }
                        .getOrDefault(false)
                }
            }
            scheduledDao.updateLastState(id, finished?.state ?: STATE_UNKNOWN)
        } finally {
            releaseWakeLock(lock)
        }
    }

    /**
     * 功能：点亮屏幕并保持到任务结束
     * 参数：无
     * 返回：持有的唤醒锁；系统不支持时为 null
     *
     * 用 SCREEN_BRIGHT_WAKE_LOCK 而不是 FULL_WAKE_LOCK：只需要屏幕亮着让副屏可读，
     * 不需要键盘灯。带超时上限，避免异常路径把屏幕永久点亮。
     */
    private fun acquireWakeLock(): PowerManager.WakeLock? = runCatching {
        val power = context.getSystemService(PowerManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            WAKE_LOCK_TAG,
        ).apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }.getOrNull()

    /**
     * 功能：释放唤醒锁
     * 参数：lock 唤醒锁
     * 返回：无
     */
    private fun releaseWakeLock(lock: PowerManager.WakeLock?) {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }

    private companion object {
        /** 执行中的状态文本，与任务终态区分。 */
        const val STATE_RUNNING = "RUNNING"

        /** 超时未拿到终态时的状态文本。 */
        const val STATE_UNKNOWN = "OUTCOME_UNKNOWN"

        /** 唤醒锁标签；`Mimir:` 前缀便于在 dumpsys 里辨认。 */
        const val WAKE_LOCK_TAG = "Mimir:scheduled"

        /** 唤醒锁最长持有时间：任务预算 30 分钟，这里给 10 分钟上限，异常时不至于一直亮屏。 */
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        /** 等待任务终态的上限，与唤醒锁上限一致。 */
        const val MAX_WAIT_MS = 10 * 60 * 1000L
    }
}
