package com.neptine.mimir.service.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机后重排定时任务（设计文档 §5.6）。
 *
 * AlarmManager 的登记在重启后通常会被系统清空；不重排就等于用户的定时任务悄悄消失。
 * 接收器本身只做转交：Hilt 的依赖通过 EntryPoint 取，避免给 BroadcastReceiver 加
 * `@AndroidEntryPoint` 后还要在 manifest 里维护额外声明。
 */
class BootReceiver : BroadcastReceiver() {

    /**
     * 功能：开机完成后重排所有启用的定时任务
     * 参数：context 上下文；intent 开机广播
     * 返回：无
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val entry = EntryPointAccessors.fromApplication(context, BootEntryPoint::class.java)
                val tasks = entry.scheduledTaskDao().findEnabled().map { it.toContract() }
                entry.scheduler().reschedule(tasks)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * 开机接收器需要的依赖入口。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BootEntryPoint {

    /** 定时任务表。 */
    fun scheduledTaskDao(): com.neptine.mimir.data.db.dao.ScheduledTaskDao

    /** 闹钟调度器。 */
    fun scheduler(): ScheduledTaskScheduler
}
