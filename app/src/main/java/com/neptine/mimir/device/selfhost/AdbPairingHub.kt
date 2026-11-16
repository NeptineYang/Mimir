package com.neptine.mimir.device.selfhost

import android.content.Context
import android.util.Log
import com.neptine.mimir.adblink.AdbController
import com.neptine.mimir.adblink.AdbInvalidPairingCodeException
import com.neptine.mimir.adblink.AdbMdns
import com.neptine.mimir.adblink.PreferenceAdbKeyStore
import com.neptine.mimir.ui.i18n.tStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无线调试配对的状态与执行器（进程级单例）。
 *
 * 为什么不是 ViewModel 的私有状态：配对码在通知栏里输入，执行者是广播接收器，
 * 界面可能不在前台；把状态放在单例里，界面回到前台时能直接看到最新结果。
 */
object AdbPairingHub {

    /** 配对端口发现的等待上限（毫秒）：系统对话框只开一小会儿，等太久没意义。 */
    private const val PAIRING_DISCOVERY_MS = 10_000L

    /** 连接端口发现的等待上限（毫秒）：无线调试常驻广播，正常几百毫秒就能命中。 */
    private const val CONNECT_DISCOVERY_MS = 8_000L

    /** adb socket 的读写超时（毫秒）。 */
    private const val ADB_TIMEOUT_MS = 10_000

    private const val TAG = "AdbPairingHub"

    /**
     * 配对状态。
     *
     * @property paired 本机是否已持有 adb 密钥（等价于"曾经配对成功"）
     * @property busy 是否正在跑配对链路
     * @property result 最近一次的结果文案，null 表示还没跑过
     */
    data class State(
        val paired: Boolean = false,
        val busy: Boolean = false,
        val result: String? = null,
    )

    private val _state = MutableStateFlow(State())

    /** 供界面订阅的状态流。 */
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var controller: AdbController? = null

    /**
     * 功能：取 adb 通道（进程内复用同一份密钥存储；装配层也用它，保证只有一个实例）
     * 参数：context 应用上下文
     * 返回：AdbController 实例
     */
    fun controller(context: Context): AdbController =
        controller ?: synchronized(this) {
            controller ?: AdbController(
                context.applicationContext,
                PreferenceAdbKeyStore(
                    context.applicationContext.getSharedPreferences(AdbController.PREFS_NAME, Context.MODE_PRIVATE),
                ),
            ).also { controller = it }
        }

    /**
     * 功能：从本地存储重读"是否已配对"
     * 参数：context 应用上下文
     * 返回：无；结果写进 [state]
     */
    fun refresh(context: Context) {
        _state.value = _state.value.copy(paired = controller(context).hasKey())
    }

    /**
     * 功能：删除已保存的密钥，使下次必须重新配对
     * 参数：context 应用上下文
     * 返回：无
     */
    fun clearKey(context: Context) {
        controller(context).clearKey()
        _state.value = State(paired = false, result = "已清除配对密钥".tStatic())
    }

    /**
     * 功能：记录"没有通知权限、无法弹出配对输入框"
     * 参数：无
     * 返回：无
     */
    fun reportNotificationBlocked() {
        _state.value = _state.value.copy(
            busy = false,
            result = "通知权限未开启，配对码没有地方输入；请先允许 Mimir 发送通知。".tStatic(),
        )
    }

    /**
     * 功能：跑完整条配对链路（阻塞，调用方负责放到 IO 线程）
     * 参数：context 应用上下文；code 系统对话框里的 6 位配对码
     * 返回：null 表示成功；否则是给界面/通知看的失败原因
     */
    fun pair(context: Context, code: String): String? {
        val adb = controller(context)
        _state.value = _state.value.copy(busy = true, result = null)
        var outcome: String? = null
        try {
            outcome = runPairing(adb, code)
        } catch (error: Exception) {
            // 任何未归类异常也要复位 busy，否则按钮会永远停在"配对中…"。
            outcome = "配对失败：%1\$s".tStatic(error.message ?: error.javaClass.simpleName)
            Log.w(TAG, "配对异常：${error.javaClass.simpleName} ${error.message}")
        } finally {
            _state.value = State(paired = adb.hasKey(), busy = false, result = outcome)
        }
        Log.i(TAG, "pair finished: ${outcome ?: "ok"}")
        return outcome
    }

    /**
     * 功能：按"发现配对端口 → 配对 → 发现连接端口 → 执行 id"顺序验证链路
     * 参数：adb 通道；code 6 位配对码
     * 返回：null 表示成功；否则是失败原因
     */
    private fun runPairing(adb: AdbController, code: String): String? {
        val pairingPort = adb.discoverPort(AdbMdns.TLS_PAIRING, PAIRING_DISCOVERY_MS)
            ?: return "没发现配对端口：请确认系统「使用配对码配对设备」对话框还开着，并保持 Wi-Fi 连接。".tStatic()

        val pairResult = adb.pair(pairingPort, code, ADB_TIMEOUT_MS)
        pairResult.exceptionOrNull()?.let { error ->
            val reason = when (error) {
                is AdbInvalidPairingCodeException -> "配对码错误或已过期".tStatic()
                else -> error.message ?: error.javaClass.simpleName
            }
            return "配对失败：%1\$s".tStatic(reason)
        }

        val connectPort = adb.discoverPort(AdbMdns.TLS_CONNECT, CONNECT_DISCOVERY_MS)
            ?: return "配对成功，但没发现连接端口：请重新打开一次无线调试。".tStatic()

        val shellResult = adb.shell(connectPort, "id", ADB_TIMEOUT_MS)
        val output = shellResult.getOrNull()?.trim()
        if (output.isNullOrEmpty()) {
            val reason = shellResult.exceptionOrNull()?.message ?: "无输出"
            return "已配对，但 shell 不可用：%1\$s".tStatic(reason)
        }
        return null
    }
}
