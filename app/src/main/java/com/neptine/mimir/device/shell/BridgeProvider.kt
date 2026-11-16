package com.neptine.mimir.device.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.neptine.mimir.device.selfhost.ShellBridge
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 壳进程把 binder 交付给 app 的入口。
 *
 * 为什么需要一个 exported provider：壳进程是 app_process 拉起的裸进程，
 * 没有正常应用的 Context，唯一能把 IBinder 递进 app 进程的通道就是
 * `ContentProvider.call()`（Shizuku 用的是同一招）。
 *
 * 安全边界分两层：
 * 1. manifest 上 `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`，
 *    只有 system/shell/root 能调用；
 * 2. 这里再校验调用方 uid 与一次性令牌——即使权限在某个 ROM 上失效，令牌也不会漏。
 */
class BridgeProvider : ContentProvider() {

    /**
     * 功能：处理壳进程的交付请求
     * 参数：method 方法名；arg 未使用；extras 交付载荷
     * 返回：接受时返回 accepted=true；拒绝时返回原因
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != ShellProtocol.METHOD_ATTACH || extras == null) return null

        val callerUid = Binder.getCallingUid()
        if (callerUid != Process.ROOT_UID && callerUid != Process.SYSTEM_UID && callerUid != Process.SHELL_UID) {
            Log.w(TAG, "拒绝 attach：uid=$callerUid")
            return reject("uid=$callerUid")
        }

        val bridge = bridge() ?: return reject("no-bridge")
        val raw = extras.getBinder(ShellProtocol.KEY_BINDER) ?: return reject("binder")

        val token = extras.getString(ShellProtocol.KEY_TOKEN).orEmpty()
        if (!tokenEquals(token, bridge.currentToken())) {
            // 旧令牌 = 上一次启动残留的壳进程：让它自己退出，别和新壳抢屏。
            runCatching { IMimirShell.Stub.asInterface(raw).shutdown() }
            Log.w(TAG, "拒绝 attach：令牌不匹配")
            return reject("token")
        }

        if (extras.getInt(ShellProtocol.KEY_PROTOCOL) != ShellProtocol.PROTOCOL) {
            runCatching { IMimirShell.Stub.asInterface(raw).shutdown() }
            return reject("proto")
        }

        if (extras.getString(ShellProtocol.KEY_APK) != bridge.apkPath()) {
            // 装包后残留的旧进程：dex 已过期，留它没有意义。
            runCatching { IMimirShell.Stub.asInterface(raw).shutdown() }
            return reject("apk")
        }

        if (!bridge.onAttached(raw, extras.getInt(ShellProtocol.KEY_PID))) {
            runCatching { IMimirShell.Stub.asInterface(raw).shutdown() }
            return reject("duplicate")
        }
        return Bundle().apply { putBoolean(ShellProtocol.KEY_ACCEPTED, true) }
    }

    /**
     * 功能：比较令牌（定长，逐字节比较，避免提前返回泄露长度信息）
     * 参数：actual 壳进程送来的令牌；expected app 侧保存的令牌
     * 返回：完全一致为 true
     */
    private fun tokenEquals(actual: String, expected: String): Boolean {
        if (expected.isEmpty() || actual.length != expected.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (actual[i].code xor expected[i].code)
        return diff == 0
    }

    /**
     * 功能：构造拒绝回执
     * 参数：reason 原因
     * 返回：回执 Bundle
     */
    private fun reject(reason: String): Bundle = Bundle().apply {
        putBoolean(ShellProtocol.KEY_ACCEPTED, false)
        putString(ShellProtocol.KEY_REJECT, reason)
    }

    /**
     * 功能：从 Hilt 图里取壳进程 binder 持有者
     * 参数：无
     * 返回：ShellBridge；取不到时为 null
     *
     * ContentProvider 不被 Hilt 支持注入，只能走 EntryPoint。
     */
    private fun bridge(): ShellBridge? = runCatching {
        val appContext = context?.applicationContext ?: return null
        EntryPointAccessors.fromApplication(appContext, BridgeEntryPoint::class.java).shellBridge()
    }.getOrNull()

    override fun onCreate(): Boolean = true

    /**
     * 功能：Hilt 入口点
     * 参数：无
     * 返回：ShellBridge
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BridgeEntryPoint {
        fun shellBridge(): ShellBridge
    }

    // 本 provider 只承载 call()，其余方法按框架要求留空实现。
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val TAG = "BridgeProvider"
    }
}
