package com.neptine.mimir.service.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.neptine.mimir.MainActivity
import com.neptine.mimir.core.contract.ScheduledTask
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 定时任务的闹钟调度（设计文档 §5.6）。
 *
 * 用 `setExactAndAllowWhileIdle`：到点必须真的跑，`setRepeating` 在 Doze 下会漂移。
 * 精确闹钟权限被用户关掉时退化到 `setAndAllowWhileIdle`，只延迟、不掉任务。
 *
 * 不用 WorkManager：它的最小周期 15 分钟、且不保证准点，做"每天 08:30"会漂出半小时。
 */
@Singleton
class ScheduledTaskScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 功能：登记一条定时任务的闹钟
     * 参数：task 定时任务
     * 返回：无
     *
     * 同一个 id 重复登记会覆盖旧闹钟（PendingIntent 用 id.hashCode 作 requestCode）。
     */
    fun schedule(task: ScheduledTask) {
        if (!task.enabled) {
            cancel(task.id)
            return
        }
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(task.id)
        // 用 setAlarmClock 而不是 setExactAndAllowWhileIdle：
        // 1. Android 12+ 只对 alarm clock 广播豁免"后台启动前台服务"的限制；
        //    普通 alarm 广播启动 FGS 会抛 ForegroundServiceStartNotAllowedException，
        //    真机上表现为"闹钟到了、Receiver 只跑几十毫秒就结束、任务没建"。
        // 2. HyperOS 会把普通精确闹钟对齐到维护窗口（日志 not align this alarm），
        //    alarm clock 不参与对齐。
        val show = PendingIntent.getActivity(
            context,
            SHOW_REQUEST_OFFSET + task.id.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            alarm.setAlarmClock(AlarmManager.AlarmClockInfo(task.triggerAt, show), pending)
        }.onFailure {
            // 精确闹钟权限缺失时退化为窗口闹钟：会晚几分钟，但不能丢任务。
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.triggerAt, pending)
        }
    }

    /**
     * 功能：取消一条定时任务的闹钟
     * 参数：id 定时任务标识
     * 返回：无
     */
    fun cancel(id: String) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(pendingIntent(id))
    }

    /**
     * 功能：重排一批定时任务
     * 参数：tasks 定时任务列表
     * 返回：无
     *
     * 应用启动与开机后调用：AlarmManager 的登记在多数情况下能跨进程存活，
     * 但应用更新、ROM 清理都可能丢掉它，重排是幂等的，成本只有一次系统调用。
     */
    fun reschedule(tasks: List<ScheduledTask>) {
        tasks.forEach { schedule(it) }
    }

    /**
     * 功能：构造指向接收器的 PendingIntent
     * 参数：id 定时任务标识
     * 返回：可用于登记/取消闹钟的 PendingIntent
     */
    private fun pendingIntent(id: String): PendingIntent {
        val intent = Intent(context, ScheduledTaskReceiver::class.java)
            .setAction(ScheduledTaskReceiver.ACTION_RUN)
            .putExtra(ScheduledTaskReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** 展示用 PendingIntent 的 requestCode 偏移，避免与触发用的撞号。 */
private const val SHOW_REQUEST_OFFSET = 0x5A0000
