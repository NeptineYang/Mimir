package com.neptine.mimir.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.neptine.mimir.device.selfhost.AdbPairingHub
import kotlin.concurrent.thread

/**
 * 通知栏配对码的接收者。
 *
 * 存在的理由：HyperOS 在设置失去前台时立即销毁系统配对对话框，所以配对码必须在
 * 通知栏里输入——本接收者不回前台、不切 Activity，系统的配对对话框与端口都不受影响。
 */
class PairingReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PRIVILEGE_REPLY_KEY)
            ?.toString()
            ?.trim()
            .orEmpty()

        val notifier = PrivilegeNotifier(context)
        if (code.length != CODE_LENGTH) {
            notifier.prompt()
            Log.w(TAG, "ignore invalid code length=${code.length}")
            return
        }

        val pending = goAsync()
        // 广播回调在主线程，配对链路全是阻塞 socket，必须挪走。
        thread(name = "privilege-pair") {
            try {
                notifier.progress()
                val error = AdbPairingHub.pair(context.applicationContext, code)
                notifier.finish(error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** 回复动作标识。 */
        const val ACTION_REPLY = "com.neptine.mimir.PRIVILEGE_PAIR_REPLY"

        /** 配对码固定 6 位。 */
        private const val CODE_LENGTH = 6

        private const val TAG = "PairingReplyReceiver"
    }
}
