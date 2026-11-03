package com.neptine.mimir.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import com.neptine.mimir.MainActivity
import com.neptine.mimir.R
import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.device.ActiveDisplayRegistry
import com.neptine.mimir.observation.ScreenshotProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 人工交接通知（设计文档 §4.3）。
 *
 * 交接不是"停下等用户"，而是"副屏暂停 + 通知栏挂起"：用户可以在任何时刻处理，
 * agent 不占屏、不弹窗、不震动打断。通知渠道用 LOW 重要性，代价是用户可能晚一点看到，
 * 换来的是不打断主屏使用——这正是本项目的主约束。
 *
 * 两档动作集合不同，不能合并：L2A 提供「继续一次 / 放弃 / 我来处理」；
 * L2B 只有「放弃 / 我来处理」，因为支付、密码、验证码只能由本人完成。
 * 共享状态冲突、前台不符这类可自行恢复的挂起提供「继续」，但不签发任何授权。
 */
@Singleton
class HandoffNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screenshots: ScreenshotProvider,
    private val activeDisplay: ActiveDisplayRegistry,
) {

    /**
     * 功能：为可授权副作用（L2A）发起一次确认通知
     * 参数：taskId 任务标识；summary 一句话说明被拦下的动作与原因
     * 返回：无
     *
     * 「继续一次」只对刚被拦下的那一步签发授权；页面或参数变化后授权自动失效。
     */
    suspend fun notifyConfirmation(taskId: String, summary: String) {
        post(taskId, title = "需要确认一次", summary = summary, continueLabel = ACTION_CONTINUE_LABEL)
    }

    /**
     * 功能：为仅本人步骤（L2B）或结果未知的写入发起挂起通知
     * 参数：taskId 任务标识；summary 挂起原因
     * 返回：无
     *
     * 不提供「继续」：这两类情况都不允许 agent 自动续作。
     */
    suspend fun notifySelfOnly(taskId: String, summary: String) {
        post(taskId, title = "需要你本人处理", summary = summary, continueLabel = null)
    }

    /**
     * 功能：为可自行恢复的挂起（共享状态冲突、前台不符等）发起通知
     * 参数：taskId 任务标识；summary 挂起原因
     * 返回：无
     *
     * 提供「继续」但不签发任何授权：用户排除原因后，agent 重新观测再决策。
     */
    suspend fun notifyResumable(taskId: String, summary: String) {
        post(taskId, title = "任务已暂停", summary = summary, continueLabel = ACTION_RESUME_LABEL)
    }

    /**
     * 功能：投递一条任务结果通知
     * 参数：taskId 任务标识；state 任务终态；reason 结束原因（可为空）；steps 已执行步数；
     *       openResult 是否把整条通知点击指向结果页
     * 返回：无
     *
     * 与交接通知分开渠道：交接是"停下来等你处理"，需要动作按钮；结果是"这一轮结束了"，只供回看。
     * 投递前先撤掉同一任务的交接通知，避免通知栏同时留着"需要你处理"和"已完成"两条自相矛盾的提示。
     */
    fun notifyResult(
        taskId: String,
        state: TaskState,
        reason: String?,
        steps: Int,
        openResult: Boolean = false,
    ) {
        clear(taskId)
        ensureResultChannel()

        val body = listOfNotNull(
            reason?.takeIf(String::isNotEmpty),
            "已执行 $steps 步",
            when (state) {
                TaskState.DONE -> "已通过完成契约验证"
                TaskState.DONE_READBACK -> "已通过同页重读复核（没有注册专用复验路线）"
                TaskState.VERIFIED_BY_HUMAN -> "已由你核对确认"
                TaskState.COMPLETED_UNVERIFIED -> "未复验，可手动打开 App 核对"
                else -> "未形成可复验的完成结论"
            },
            // 结果页已经搬到主屏后台，点整条通知就能回到 Agent 停留的那一页
            "点击通知直接打开结果页".takeIf { openResult },
        ).joinToString("；")

        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(resultTitle(state))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(if (openResult) resultIntent(taskId) else mainIntent(taskId))

        if (openResult) {
            notification.addAction(
                android.R.drawable.ic_menu_view,
                ACTION_OPEN_RESULT_LABEL,
                resultIntent(taskId),
            )
        }
        if (state == TaskState.COMPLETED_UNVERIFIED) {
            // 机器没核对成的结果，给人一个"我看过了"的出口：点了才算人工核对，
            // 不改写任何证据，只把终态标注成人工确认（记忆写入不受此影响）。
            notification.addAction(
                android.R.drawable.checkbox_on_background,
                "我已核对",
                actionIntent(taskId, HandoffReceiver.ACTION_HUMAN_VERIFIED, ACTION_VERIFIED_CODE),
            )
        }
        val built = notification.build()

        manager().notify(resultNotificationId(taskId), built)
    }

    /**
     * 功能：撤掉某任务的交接通知
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 用户已经处理（继续或放弃）时调用，避免通知栏留着一条点了也没反应的旧通知。
     */
    fun clear(taskId: String) {
        manager().cancel(notificationId(taskId))
    }

    /**
     * 功能：给终态配一个通知标题
     * 参数：state 任务终态
     * 返回：标题文案
     */
    private fun resultTitle(state: TaskState): String = when (state) {
        TaskState.DONE -> "任务已完成"
        TaskState.DONE_READBACK -> "任务已完成（重读复核）"
        TaskState.VERIFIED_BY_HUMAN -> "任务已完成（人工核对）"
        TaskState.COMPLETED_UNVERIFIED -> "任务完成（未复验）"
        TaskState.FAILED -> "任务失败"
        else -> "任务已放弃"
    }

    /**
     * 功能：投递一条交接通知
     * 参数：taskId 任务标识；title 标题；summary 内容；continueLabel 「继续」按钮文案；
     *       为 null 时不提供继续
     * 返回：无
     */
    private suspend fun post(taskId: String, title: String, summary: String, continueLabel: String?) {
        ensureChannel()
        val thumbnail = handoffThumbnail()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(summary)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(takeoverIntent(taskId, taskId.hashCode()))

        if (thumbnail != null) {
            // 展开后显示副屏大图（§4.3：用通知栏缩略图替代悬浮窗）；取不到图就退回纯文本
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(thumbnail))
                .setLargeIcon(thumbnail)
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(summary))
        }

        if (continueLabel != null) {
            builder.addAction(
                android.R.drawable.ic_menu_send,
                continueLabel,
                actionIntent(taskId, HandoffReceiver.ACTION_CONTINUE, ACTION_CONTINUE_CODE),
            )
        }
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            ACTION_ABORT_LABEL,
            actionIntent(taskId, HandoffReceiver.ACTION_ABORT, ACTION_ABORT_CODE),
        )
        // 「在主屏打开」：把出问题的 App 直接开到主屏，用户处理完切后台即自动交还。
        // 它和「我来处理」是两条路：后者在 Mimir 里镜像副屏，前者让用户动真机。
        builder.addAction(
            android.R.drawable.ic_menu_directions,
            ACTION_OPEN_MAIN_LABEL,
            actionIntent(taskId, HandoffReceiver.ACTION_OPEN_MAIN, ACTION_OPEN_MAIN_CODE),
        )
        builder.addAction(
            android.R.drawable.ic_menu_view,
            ACTION_TAKEOVER_LABEL,
            takeoverIntent(taskId, ACTION_TAKEOVER_CODE),
        )

        manager().notify(notificationId(taskId), builder.build())
    }

    /**
     * 功能：构造通知动作的 PendingIntent
     * 参数：taskId 任务标识；action 动作串；requestCode 请求码
     * 返回：指向 [HandoffReceiver] 的 PendingIntent
     */
    private fun actionIntent(taskId: String, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, HandoffReceiver::class.java)
            .setAction(action)
            .putExtra(HandoffReceiver.EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            context,
            requestCode + stableId(taskId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * 功能：构造打开接管页的 PendingIntent
     * 参数：taskId 任务标识；requestCode 请求码
     * 返回：指向主界面并携带任务标识的 PendingIntent
     */
    private fun takeoverIntent(taskId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAKEOVER_TASK_ID, taskId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode + stableId(taskId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * 功能：构造打开结果页的 PendingIntent
     * 参数：taskId 任务标识
     * 返回：指向 [HandoffReceiver] 的广播 PendingIntent
     *
     * 结果页在目标 App 里，不在 Mimir 里，因此不能直接用 Activity PendingIntent：
     * 交给接收器，由它调用 shell 把已完成任务的系统任务带到前台。
     */
    private fun resultIntent(taskId: String): PendingIntent {
        val intent = Intent(context, HandoffReceiver::class.java)
            .setAction(HandoffReceiver.ACTION_OPEN_RESULT)
            .putExtra(HandoffReceiver.EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            context,
            ACTION_OPEN_RESULT_CODE + stableId(taskId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * 功能：构造打开主界面的 PendingIntent
     * 参数：taskId 任务标识
     * 返回：指向主界面的 PendingIntent
     *
     * 结果通知不带任何动作按钮：任务已经结束，点开只是回到任务列表回看。
     */
    private fun mainIntent(taskId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            ACTION_RESULT_CODE + stableId(taskId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * 功能：抓一帧副屏画面作为交接通知的缩略图
     * 参数：无
     * 返回：缩略图；没有活动副屏或取帧失败时为 null
     *
     * 只从活动副屏取帧（§4.3 用通知栏缩略图替代悬浮窗），取不到就不带图，
     * 不拿主屏截图冒充。解码放 Default 线程：一帧 540×1200 的 PNG 解码不是零成本。
     */
    private suspend fun handoffThumbnail(): Bitmap? {
        val handle = activeDisplay.current() ?: return null
        val frame = runCatching { screenshots.capture(handle) }.getOrNull() ?: return null
        return withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(frame.image.bytes, 0, frame.image.bytes.size)
        }
    }

    /**
     * 功能：建立交接通知渠道
     * 参数：无
     * 返回：无
     */
    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.handoff_channel_name),
            // 交接通知是"agent 卡住了、需要人处理"，必须能打断用户：高优先级才会弹横幅。
            // 早期用 LOW 是怕打扰，但代价是用户根本注意不到任务停在那里。
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.handoff_channel_description)
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 120, 120, 120)
            // 锁屏可见：黑屏/锁屏状态下也要看得到"哪一步需要你"，
            // 否则用户拿起手机只能看到一条"内容已隐藏"。
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager().createNotificationChannel(channel)
    }

    /**
     * 功能：建立任务结果通知渠道
     * 参数：无
     * 返回：无
     *
     * 同样用 LOW：结果只供回看，不需要横幅弹出打断主屏（不打断是项目的主约束）。
     */
    private fun ensureResultChannel() {
        val channel = NotificationChannel(
            CHANNEL_RESULT_ID,
            context.getString(R.string.result_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.result_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager().createNotificationChannel(channel)
    }

    /**
     * 功能：取通知管理器
     * 参数：无
     * 返回：NotificationManager
     */
    private fun manager(): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    /**
     * 功能：按任务算通知标识
     * 参数：taskId 任务标识
     * 返回：稳定的通知标识
     *
     * 同一时刻只有一个活动任务，按任务号错开是为了"上一任务的旧通知"不会覆盖新任务的。
     */
    private fun notificationId(taskId: String): Int = NOTIFICATION_ID_BASE + stableId(taskId)

    /**
     * 功能：按任务算结果通知标识
     * 参数：taskId 任务标识
     * 返回：稳定的通知标识
     *
     * 与交接通知的基数和渠道都不同：两条通知语义相反，不能互相覆盖。
     */
    private fun resultNotificationId(taskId: String): Int =
        NOTIFICATION_ID_RESULT_BASE + stableId(taskId)

    /**
     * 功能：把任务号映射成非负的稳定后缀
     * 参数：taskId 任务标识
     * 返回：0x0FFFFFFF 以内的稳定整数
     *
     * 不能直接用 hashCode：哈希为负时通知 ID 也为负，部分 ROM 会直接丢弃这条通知。
     */
    private fun stableId(taskId: String): Int = taskId.hashCode() and 0x0FFFFFFF

    private companion object {
        /** 「我已核对」动作的请求码。 */
        const val ACTION_VERIFIED_CODE = 3100

        /** 「查看结果」动作的请求码。 */
        const val ACTION_OPEN_RESULT_CODE = 3300

        /** 「查看结果」按钮文案。 */
        const val ACTION_OPEN_RESULT_LABEL = "查看结果"

        /** 「在主屏打开」动作的请求码。 */
        const val ACTION_OPEN_MAIN_CODE = 3200

        /** 「在主屏打开」按钮文案。 */
        const val ACTION_OPEN_MAIN_LABEL = "主屏打开"

        /** 交接通知渠道标识。 */
        // 渠道创建后属性不可修改（IMPORTANCE/VISIBILITY），改属性必须换 id 重建。
        const val CHANNEL_ID = "mimir_handoff_v2"

        /** 结果通知渠道标识。 */
        const val CHANNEL_RESULT_ID = "mimir_result"

        /** 通知标识基数；与前台服务保活通知（1001）错开。 */
        const val NOTIFICATION_ID_BASE = 1100

        /** 结果通知标识基数。 */
        const val NOTIFICATION_ID_RESULT_BASE = 1200

        /** 「继续一次」的请求码。 */
        const val ACTION_CONTINUE_CODE = 1

        /** 「放弃」的请求码。 */
        const val ACTION_ABORT_CODE = 2

        /** 「我来处理」的请求码。 */
        const val ACTION_TAKEOVER_CODE = 3

        /** 结果通知「回到任务列表」的请求码。 */
        const val ACTION_RESULT_CODE = 4

        /** 「继续一次」按钮文案。 */
        const val ACTION_CONTINUE_LABEL = "继续一次"

        /** 可自行恢复的挂起用的「继续」按钮文案。 */
        const val ACTION_RESUME_LABEL = "继续"

        /** 「放弃」按钮文案。 */
        const val ACTION_ABORT_LABEL = "放弃"

        /** 「我来处理」按钮文案。 */
        const val ACTION_TAKEOVER_LABEL = "我来处理"
    }
}
