package com.neptine.mimir.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.neptine.mimir.MainActivity

/**
 * 交接通知的动作接收器（设计文档 §4.3）。
 *
 * 只做转发：把「继续一次」「放弃」「我来处理」翻译成前台服务动作或接管页跳转。
 * 真正的授权签发、状态迁移都在 Orchestrator 里，接收器不碰数据库也不碰模型。
 */
class HandoffReceiver : BroadcastReceiver() {

    /**
     * 功能：处理通知按钮点击
     * 参数：context 广播上下文；intent 携带动作与任务标识的意图
     * 返回：无
     */
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        android.util.Log.d("MimirHandoff", "收到通知动作 action=${intent.action} task=$taskId")
        when (intent.action) {
            ACTION_CONTINUE -> AgentForegroundService.continueTask(context, taskId)
            ACTION_ABORT -> AgentForegroundService.abort(context, taskId)
            ACTION_TAKEOVER -> openTakeover(context, taskId)
            ACTION_HUMAN_VERIFIED -> AgentForegroundService.markVerifiedByHuman(context, taskId)
            ACTION_OPEN_MAIN -> AgentForegroundService.openOnMain(context, taskId)
            ACTION_OPEN_RESULT -> AgentForegroundService.openResult(context, taskId)
        }
    }

    /**
     * 功能：打开接管页
     * 参数：context 上下文；taskId 任务标识
     * 返回：无
     *
     * 走主界面携带任务标识，而不是另建一个 Activity：接管页本来就是导航图里的一站，
     * 这样返回栈与底栏显隐规则都不用再复制一份。
     */
    private fun openTakeover(context: Context, taskId: String) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAKEOVER_TASK_ID, taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    companion object {
        /** 「继续一次」：签发一次授权并恢复执行。 */
        const val ACTION_CONTINUE = "com.neptine.mimir.action.HANDOFF_CONTINUE"

        /** 「放弃」：终止任务。 */
        const val ACTION_ABORT = "com.neptine.mimir.action.HANDOFF_ABORT"

        /** 「我来处理」：打开接管页。 */
        const val ACTION_TAKEOVER = "com.neptine.mimir.action.HANDOFF_TAKEOVER"

        /** 用户点「在主屏打开」：把出问题的 App 开到主屏，处理完切后台即自动交还。 */
        const val ACTION_OPEN_MAIN = "com.neptine.mimir.action.HANDOFF_OPEN_MAIN"

        /** 用户在结果通知上点「我已核对」：把未复验的终态标注成人工确认。 */
        const val ACTION_HUMAN_VERIFIED = "com.neptine.mimir.action.HANDOFF_HUMAN_VERIFIED"

        /** 用户点击结果通知：把 Agent 完成时停留的页面直接带到前台。 */
        const val ACTION_OPEN_RESULT = "com.neptine.mimir.action.HANDOFF_OPEN_RESULT"

        /** 附加的任务标识。 */
        const val EXTRA_TASK_ID = "handoff_task_id"
    }
}
