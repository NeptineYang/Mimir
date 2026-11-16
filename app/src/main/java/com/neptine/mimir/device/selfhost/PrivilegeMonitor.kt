package com.neptine.mimir.device.selfhost

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 特权服务的状态机与自启动。
 *
 * 三件事合成一个状态：环境（Wi-Fi / 无线调试）、凭据（是否配对）、进程（binder 是否就绪）。
 * 界面与任务门禁都只读 [status]，不再各自拼判断，避免"设置页说就绪、任务页说没授权"。
 *
 * 自启动只在"已配对 + 环境就绪"时尝试，且带冷却：壳进程被 ROM 杀掉后
 * 每次回到前台都重拉一次是合理的，但连点或频繁切前台不该反复 fork。
 */
@Singleton
class PrivilegeMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: ShellBridge,
    private val launcher: ShellLauncher,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val starting = MutableStateFlow(false)

    private val _status = MutableStateFlow(PrivilegeStatus.NEED_PAIR)

    /** 供界面与任务门禁订阅的状态。 */
    val status: StateFlow<PrivilegeStatus> = _status.asStateFlow()

    /** 是否曾经就绪过——用来区分"从没起来"与"起来了又断了"。 */
    @Volatile
    private var everReady = false

    /** 上次自动拉起的时间，用于冷却。 */
    @Volatile
    private var lastAttemptAt = 0L

    init {
        scope.launch {
            bridge.ready.collect { ready ->
                if (ready) {
                    everReady = true
                    _status.value = PrivilegeStatus.READY
                } else if (everReady) {
                    _status.value = PrivilegeStatus.SERVICE_DEAD
                    // 进程被系统回收：应用很可能还在前台，不会再有 onStart，
                    // 所以这里主动补一次拉起；稍等一拍避开回收动作本身。
                    scope.launch {
                        delay(RESTART_DELAY_MS)
                        ensureStarted()
                    }
                } else {
                    _status.value = compute()
                }
            }
        }
    }

    /**
     * 功能：按当前环境与凭据重算状态（界面回到前台、用户改过系统设置后调用）
     * 参数：无
     * 返回：无
     */
    fun refresh() {
        // 配对状态由 prefs 决定，和界面是否打开设置页无关：不在这里对齐一次，
        // 用户装完应用直接进任务页时会被误判成"还没配对"。
        AdbPairingHub.refresh(context)
        if (!bridge.isReady() && !starting.value) {
            _status.value = compute()
        }
        // 状态变了顺便尝试自启动：用户刚打开无线调试、或刚配对完成时都能立刻起来。
        ensureStarted()
    }

    /**
     * 功能：尝试拉起壳进程（已就绪、正在拉起、未配对、环境不满足时直接返回）
     * 参数：无
     * 返回：无；结果由 [status] 反映
     */
    fun ensureStarted() {
        if (bridge.isReady() || starting.value) return
        if (!AdbPairingHub.state.value.paired) return
        if (!isWifiConnected() || !isWirelessDebuggingOn()) return

        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < RETRY_COOLDOWN_MS) return
        lastAttemptAt = now

        starting.value = true
        _status.value = PrivilegeStatus.STARTING
        scope.launch {
            try {
                val result = launcher.start()
                if (result.isFailure) {
                    Log.w(TAG, "自动拉起失败：${result.exceptionOrNull()?.message}")
                }
            } finally {
                // 放在 finally：抛异常时若不清 starting，状态会永远停在"正在启动"。
                starting.value = false
                if (!bridge.isReady()) _status.value = compute()
            }
        }
    }

    /**
     * 功能：按环境与凭据算状态
     * 参数：无
     * 返回：当前应展示的状态
     */
    private fun compute(): PrivilegeStatus {
        if (bridge.isReady()) return PrivilegeStatus.READY
        if (!isWifiConnected()) return PrivilegeStatus.NO_WIFI
        if (!isWirelessDebuggingOn()) return PrivilegeStatus.WIRELESS_OFF
        if (!AdbPairingHub.state.value.paired) return PrivilegeStatus.NEED_PAIR
        if (everReady) return PrivilegeStatus.SERVICE_DEAD
        return PrivilegeStatus.PAIRED_IDLE
    }

    /**
     * 功能：判断是否连上了 Wi-Fi 或以太网
     * 参数：无
     * 返回：有可用局域网连接为 true
     *
     * 无线调试必须挂在 WLAN/以太网上，纯蜂窝下系统开关会置灰。
     */
    private fun isWifiConnected(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * 功能：判断系统「无线调试」开关是否打开
     * 参数：无
     * 返回：打开为 true
     */
    private fun isWirelessDebuggingOn(): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, SETTING_ADB_WIFI_ENABLED, 0) == 1
    }.getOrDefault(false)

    private companion object {
        const val TAG = "PrivilegeMonitor"

        /** 系统里控制无线调试的全局开关名。 */
        const val SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled"

        /** 自动拉起的最小间隔：避免频繁切前台时反复 fork 进程。 */
        const val RETRY_COOLDOWN_MS = 10_000L

        /** 进程死亡后重拉的延迟：让系统把回收动作做完再拉，避免抢锁。 */
        const val RESTART_DELAY_MS = 2_000L
    }
}
