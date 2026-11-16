package com.neptine.mimir.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.neptine.mimir.MainActivity
import com.neptine.mimir.ui.i18n.tStatic

/** 特权服务通知渠道：配对码在这个渠道里输入。 */
const val PRIVILEGE_CHANNEL_ID = "mimir_privilege_v1"

/** 通知里回复配对码的 RemoteInput 键。 */
const val PRIVILEGE_REPLY_KEY = "privilege_pairing_code"

/**
 * 特权服务的配对通知。
 *
 * 为什么用通知而不是应用内对话框：HyperOS 在设置失去前台的瞬间就销毁
 * 「使用配对码配对设备」对话框，配对端口随之关闭；下拉通知栏不会。
 * 所以配对码必须在通知栏里输入——全程系统设置保持前台。
 */
class PrivilegeNotifier(private val context: Context) {

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /**
     * 功能：发出一条"请输入配对码"的通知（带直接回复输入框）
     * 参数：无
     * 返回：无
     */
    fun prompt() {
        ensureChannel()
        manager.notify(NOTIFICATION_ID, build(title = "输入配对码".tStatic(), text = PROMPT_TEXT.tStatic()))
    }

    /**
     * 功能：把通知更新为"正在配对"
     * 参数：无
     * 返回：无
     */
    fun progress() {
        manager.notify(NOTIFICATION_ID, build(title = "正在配对…".tStatic(), text = PROGRESS_TEXT.tStatic()))
    }

    /**
     * 功能：把通知更新为最终结果
     * 参数：error 失败原因；null 表示成功
     * 返回：无
     */
    fun finish(error: String?) {
        val title = if (error == null) "配对成功".tStatic() else "配对失败".tStatic()
        val text = error ?: "以后打开 Mimir 会自动连接，不需要再输配对码。".tStatic()
        manager.notify(NOTIFICATION_ID, build(title = title, text = text))
    }

    /**
     * 功能：取消配对通知
     * 参数：无
     * 返回：无
     */
    fun cancel() = manager.cancel(NOTIFICATION_ID)

    /**
     * 功能：组装通知（含回复动作与点击打开应用）
     * 参数：title 标题；text 正文
     * 返回：通知对象
     */
    private fun build(title: String, text: String): android.app.Notification {
        val replyIntent = Intent(context, PairingReplyReceiver::class.java)
            .setAction(PairingReplyReceiver.ACTION_REPLY)

        // Android 12 起 RemoteInput 必须挂在可变 PendingIntent 上，否则系统直接丢弃输入。
        val replyPending = PendingIntent.getBroadcast(
            context,
            0,
            replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val remoteInput = RemoteInput.Builder(PRIVILEGE_REPLY_KEY)
            .setLabel("配对码".tStatic())
            .setAllowFreeFormInput(true)
            .build()

        // MIUI/HyperOS 只在"语义为回复"的动作上渲染输入框，缺了 setSemanticAction
        // 通知能发出去但展开后看不到任何输入入口。
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.stat_sys_download_done,
            "输入配对码".tStatic(),
            replyPending,
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .build()

        val contentIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, PRIVILEGE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            // MIUI 只在"非常驻 + 消息类"的通知上渲染内联回复框，两者缺一都不会出现输入入口。
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // MIUI 的内联回复只对"聊天样式"的通知开放：普通通知带 RemoteInput 也不渲染输入框。
            .setStyle(
                MessagingStyle(Person.Builder().setName("Mimir").build())
                    // 消息样式的通知标题取自会话标题，不设的话展开后标题会变成应用名。
                    .setConversationTitle(title)
                    .addMessage(text, System.currentTimeMillis(), Person.Builder().setName("Mimir").build()),
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(replyAction)
            .build()
    }

    /**
     * 功能：建立通知渠道（已存在则不动）
     * 参数：无
     * 返回：无
     *
     * 渠道重要性创建后归 ROM 管，代码只能给初值；HIGH 是为了让输入框能直接展开。
     */
    private fun ensureChannel() {
        val channel = NotificationChannel(
            PRIVILEGE_CHANNEL_ID,
            "特权服务".tStatic(),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "配对无线调试时在这里输入 6 位配对码。".tStatic()
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val NOTIFICATION_ID = 4_002

        const val PROMPT_TEXT = "在系统「使用配对码配对设备」里读出 6 位数字，在下面回复即可。不用切回 Mimir。"
        const val PROGRESS_TEXT = "正在发现配对端口并握手，请稍候。"
    }
}
