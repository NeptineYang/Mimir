package com.neptine.mimir

import android.app.Application
import android.util.Log
import com.neptine.mimir.agent.TaskRecovery
import com.neptine.mimir.data.db.dao.ScheduledTaskDao
import com.neptine.mimir.device.DeviceTransport
import com.neptine.mimir.service.schedule.ScheduledTaskScheduler
import com.neptine.mimir.service.schedule.toContract
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 选择 Hilt 的理由见设计文档 §2.1：设备层（shell uid 进程）与 Agent 层生命周期差异大，
 * 手写单例会失控，而 Koin 的运行期报错在设备层问题上代价太高。
 */
@HiltAndroidApp
class MimirApplication : Application() {

    @Inject
    lateinit var recovery: TaskRecovery

    @Inject
    lateinit var transport: DeviceTransport

    @Inject
    lateinit var scheduledTasks: ScheduledTaskDao

    @Inject
    lateinit var scheduledScheduler: ScheduledTaskScheduler

    /** 冷启动清理用的作用域；Application 与进程同寿命。 */
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 功能：进程启动时做一次中断任务恢复，并清理遗留的输入法与副屏
     * 参数：无
     * 返回：无
     *
     * 必须在 super.onCreate() 之后：Hilt 的属性注入在父类 onCreate 里完成。
     * 恢复本身是异步的，不阻塞冷启动。
     * 输入法清理只读 secure 设置判断，不是自己接管时不会拉起 Shizuku UserService。
     */
    override fun onCreate() {
        super.onCreate()
        recovery.launch()
        startupScope.launch { runCatching { transport.ime.restoreIfLeftOver() } }
        startupScope.launch { reapLeftoverDisplays() }
        startupScope.launch { rescheduleScheduledTasks() }
    }

    /**
     * 功能：回收上一进程遗留的副屏
     * 参数：无
     * 返回：无
     *
     * 结果页的保留计时器活在进程里，重装或进程被杀后计时器随之消失，
     * 副屏会一直留在系统中占用虚拟屏资源（真机实测 49 分钟仍未释放）。
     * 新进程启动时登记表必为空，因此这里可以不带保留项地全量回收。
     */
    /**
     * 功能：重排所有启用的定时任务
     * 参数：无
     * 返回：无
     *
     * 应用更新、ROM 清理或用户手动停用都可能让 AlarmManager 里的登记与现实不一致；
     * 重排是幂等的，冷启动时做一次成本只有几十次系统调用。
     */
    private suspend fun rescheduleScheduledTasks() {
        runCatching {
            val tasks = scheduledTasks.findEnabled().map { it.toContract() }
            scheduledScheduler.reschedule(tasks)
        }.onFailure { Log.w(TAG, "重排定时任务失败：${it.message}") }
    }

    private suspend fun reapLeftoverDisplays() {
        runCatching { transport.displays.reapOrphans(keepDisplayId = -1) }
            .onSuccess { count ->
                if (count > 0) Log.i(TAG, "冷启动回收遗留副屏 $count 块")
            }
            .onFailure { Log.w(TAG, "回收遗留副屏失败：${it.message}") }
    }

    private companion object {
        const val TAG = "MimirDisplay"
    }
}
