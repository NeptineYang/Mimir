package com.neptine.mimir.ui.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.neptine.mimir.service.MimirAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/** 单项权限的可用状态。 */
enum class PermissionStatus {
    /** 已授予。 */
    GRANTED,

    /** 服务可用但未授权，或功能被系统关闭。 */
    DENIED,

    /** 前置条件不成立（例如 Shizuku 服务没有运行），此时谈不上授权。 */
    UNAVAILABLE,
}

/** 三项权限的读取结果。 */
data class PermissionSnapshot(
    val shizuku: PermissionStatus,
    val accessibility: PermissionStatus,
    val notifications: PermissionStatus,
)

/**
 * 功能：读取系统权限状态并提供跳转入口
 * 参数：无
 * 返回：无
 *
 * 只读系统状态与发 Intent，不碰 device/ 包——权限是 UI 层能独立回答的问题，
 * 不需要等设备层实现。
 */
@Singleton
class PermissionReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 功能：读取三项权限的当前状态
     * 参数：无
     * 返回：三项状态快照
     */
    fun snapshot(): PermissionSnapshot = PermissionSnapshot(
        shizuku = readShizuku(),
        accessibility = readAccessibility(),
        notifications = readNotifications(),
    )

    /**
     * 功能：发起 Shizuku 授权请求
     * 参数：requestCode 请求码，结果通过 Shizuku 的监听器回调
     * 返回：请求是否成功发出；服务未运行时返回 false
     */
    fun requestShizukuPermission(requestCode: Int): Boolean = runCatching {
        if (!Shizuku.pingBinder()) {
            false
        } else {
            Shizuku.requestPermission(requestCode)
            true
        }
    }.getOrDefault(false)

    /**
     * 功能：打开系统的无障碍服务设置页
     * 参数：无
     * 返回：无
     */
    fun openAccessibilitySettings() {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * 功能：打开本应用的通知设置页
     * 参数：无
     * 返回：无
     */
    fun openAppNotificationSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * 功能：读取本应用版本名
     * 参数：无
     * 返回：版本名；读取失败返回 "—"
     *
     * AGP 9 默认关闭 BuildConfig，版本号只能从 PackageManager 取。
     */
    fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
    }.getOrDefault("—")

    /**
     * 功能：读取 Shizuku 授权状态
     * 参数：无
     * 返回：服务未运行 → UNAVAILABLE；已授权 → GRANTED；其余 → DENIED
     */
    private fun readShizuku(): PermissionStatus = runCatching {
        when {
            !Shizuku.pingBinder() -> PermissionStatus.UNAVAILABLE
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> PermissionStatus.GRANTED
            else -> PermissionStatus.DENIED
        }
    }.getOrDefault(PermissionStatus.UNAVAILABLE)

    /**
     * 功能：读取无障碍服务是否已启用
     * 参数：无
     * 返回：已启用 → GRANTED；否则 DENIED
     *
     * 按 ComponentName 归一化比对，不用字符串包含：包名前缀相同或存在多个同类服务时，
     * 包含判断会把别人的服务误认成自己。
     */
    private fun readAccessibility(): PermissionStatus {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val self = ComponentName(context, MimirAccessibilityService::class.java)
        val matched = enabled
            .split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == self }
        return if (matched) PermissionStatus.GRANTED else PermissionStatus.DENIED
    }

    /**
     * 功能：读取通知权限状态
     * 参数：无
     * 返回：已允许 → GRANTED；否则 DENIED
     *
     * API 33 起 POST_NOTIFICATIONS 是运行时权限；更早版本只看渠道总开关。
     */
    private fun readNotifications(): PermissionStatus {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val enabled = manager?.areNotificationsEnabled() ?: false
        val permissionGranted = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return if (enabled && permissionGranted) PermissionStatus.GRANTED else PermissionStatus.DENIED
    }
}
