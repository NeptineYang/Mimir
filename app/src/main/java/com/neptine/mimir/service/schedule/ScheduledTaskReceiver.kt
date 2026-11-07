package com.neptine.mimir.service.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.neptine.mimir.service.AgentForegroundService

/**
 * 定时任务闹钟的接收器（设计文档 §5.6）。
 *
 * 只做一件事：把触发转交给前台服务。真正的执行、唤醒锁、终态回写都在服务里，
 * 因为 BroadcastReceiver 只有十几秒生命周期，跑不完一次 Agent 任务。
 *
 * 从 alarm 启动前台服务是 Android 12+ 后台启动限制的豁免场景之一。
 */
class ScheduledTaskReceiver : BroadcastReceiver() {

    /**
     * 功能：接收闹钟并转交前台服务
     * 参数：context 上下文；intent 闹钟意图
     * 返回：无
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        android.util.Log.d("MimirSchedule", "收到定时任务闹钟 scheduled=$id")
        AgentForegroundService.runScheduled(context, id)
    }

    companion object {
        /** 触发动作。 */
        const val ACTION_RUN = "com.neptine.mimir.action.RUN_SCHEDULED_TASK"

        /** 定时任务标识。 */
        const val EXTRA_ID = "scheduledTaskId"
    }
}
