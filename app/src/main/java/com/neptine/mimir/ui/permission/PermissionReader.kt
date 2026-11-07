package com.neptine.mimir.ui.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.neptine.mimir.service.MimirAccessibilityService
import com.neptine.mimir.service.RESULT_CHANNEL_ID
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
    /**
     * 结果通知渠道的"悬浮通知"是否可用。
     *
     * MIUI/HyperOS 把"屏幕上方弹出通知"做成了**渠道级**开关，与渠道重要性分开存放。
     * 渠道重要性设成 HIGH 只是初值，ROM 侧开关关着就照样不弹横幅，
     * 所以这是"完成通知真的会弹出来"的独立前提，必须单独报给用户。
     */
    val resultBanner: PermissionStatus,
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
        resultBanner = readResultBanner(),
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
     * 功能：打开 Shizuku 应用（服务未运行时唯一的恢复途径）
     * 参数：无
     * 返回：无
     *
     * Shizuku 服务不能由本应用启动：它需要用户在手机会话里走无线调试授权。
     */
    fun openShizukuApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
     * 功能：读取结果通知渠道的"悬浮通知"是否可用
     * 参数：无
     * 返回：渠道重要性仍为 HIGH → GRANTED；被下调 → DENIED；渠道尚未创建 → UNAVAILABLE
     *
     * ROM 把"悬浮通知"存成渠道的实际重要性，而不是另设一个标记：
     * 打开开关 = 渠道保持 HIGH，关闭 = 渠道被下调成 LOW。
     * 所以看渠道当前重要性就能判断横幅会不会弹。
     *
     * 渠道还没建出来时不报 DENIED：用户只是还没触发过一次结果通知，
     * 这时该告诉他去哪儿开，而不是说他关掉了。
     */
    private fun readResultBanner(): PermissionStatus {
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return PermissionStatus.DENIED
        val channel = manager.getNotificationChannel(RESULT_CHANNEL_ID) ?: return PermissionStatus.UNAVAILABLE
        return if (channel.importance >= android.app.NotificationManager.IMPORTANCE_HIGH) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }
    }

    /**
     * 功能：打开结果通知渠道的设置页
     * 参数：无
     * 返回：无
     *
     * 跳到渠道级页面而不是应用级页面：ROM 把"悬浮通知"做成渠道级开关，
     * 应用级页面上没有这一项，用户在那里找不到要开的地方。
     * 部分 ROM 不认这个 action，失败时退回应用级通知设置页。
     */
    fun openResultChannelSettings() {
        val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, RESULT_CHANNEL_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (channelIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(channelIntent)
        } else {
            openAppNotificationSettings()
        }
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

    private companion object {
        /** Shizuku 的包名；只用于服务未运行时把用户送到它的界面。 */
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
