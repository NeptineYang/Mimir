// 仅存在于 debug 构建的调试入口：用 adb 广播触发一次完整的配对链路。
//
// 存在的原因：HyperOS 在 Settings 失去前台的瞬间就销毁无线调试的配对对话框，
// 配对端口随之关闭，因此"在系统里读码 → 切回应用输码"这条路在真机上走不通。
// 广播接收器不会把应用切到前台，可以在系统对话框仍存活时把链路跑完。
//
// release 构建不含本文件（src/debug 源集）与对应 manifest，不影响正式包。
package com.neptine.mimir.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.neptine.mimir.adblink.AdbController
import com.neptine.mimir.adblink.AdbMdns
import com.neptine.mimir.adblink.PreferenceAdbKeyStore
import com.neptine.mimir.device.CapabilityProbe
import com.neptine.mimir.device.selfhost.ShellLauncher
import com.neptine.mimir.service.PrivilegeNotifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/** 调试用配对触发器：`adb shell am broadcast -a com.neptine.mimir.DEBUG_PAIR --es code 123456`。 */
class DebugPairReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // action=prompt 只发配对通知，用于人工验证通知栏输入入口。
        if (intent.getStringExtra("action") == "prompt") {
            PrivilegeNotifier(context).prompt()
            Log.i(TAG, "prompt notification posted")
            return
        }

        // action=serve 直接拉起壳进程（走 ShellLauncher 的完整两段式流程）。
        if (intent.getStringExtra("action") == "serve") {
            val pending = goAsync()
            thread(name = "debug-serve") {
                try {
                    val launcher = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        DebugEntryPoint::class.java,
                    ).shellLauncher()
                    val result = runBlocking { launcher.start() }
                    Log.i(TAG, "serve ok=${result.isSuccess} err=${result.exceptionOrNull()?.message}")
                } finally {
                    pending.finish()
                }
            }
            return
        }

        // action=task 直接经 TaskLauncher 发一条任务，绕过 UI 输入法/发送按钮（验证恢复链路用）。
        if (intent.getStringExtra("action") == "task") {
            val text = intent.getStringExtra("text").orEmpty()
            if (text.isEmpty()) {
                Log.e(TAG, "task: 缺少 text")
                return
            }
            val pending = goAsync()
            thread(name = "debug-task") {
                try {
                    val launcher = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        DebugEntryPoint::class.java,
                    ).taskLauncher()
                    val id = runBlocking { launcher.launch(text) }
                    Log.i(TAG, "task launched id=$id text=${text.take(40)}")
                } catch (t: Throwable) {
                    Log.e(TAG, "task 失败：${t.javaClass.simpleName} ${t.message}")
                } finally {
                    pending.finish()
                }
            }
            return
        }

        // action=selfheal 强制验证"旧实例接走 intent → 清残留重试"：
        // 先在主屏拉起目标，再建一块虚拟屏连续 launch 两次——第一次清屏外那份，第二次清目标屏那份。
        if (intent.getStringExtra("action") == "selfheal") {
            val pkg = intent.getStringExtra("pkg").orEmpty()
            if (pkg.isEmpty()) {
                Log.e(TAG, "selfheal: 缺少 pkg")
                return
            }
            val pending = goAsync()
            thread(name = "debug-selfheal") {
                try {
                    val session = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        DebugEntryPoint::class.java,
                    ).routingShellSession()
                    val proxy = runBlocking { session.require() }
                    val main = proxy.launchApp(pkg, 0)
                    val display = proxy.createDisplay("debug-selfheal", 720, 1600, 320)
                    Log.i(TAG, "selfheal main=$main display=$display pkg=$pkg")
                    val first = proxy.launchApp(pkg, display)
                    Log.i(TAG, "selfheal first(display)=$first")
                    val second = proxy.launchApp(pkg, display)
                    Log.i(TAG, "selfheal second(display)=$second")
                    proxy.destroyDisplay(display)
                    Log.i(TAG, "selfheal display destroyed=$display")
                } catch (t: Throwable) {
                    Log.e(TAG, "selfheal 失败：${t.javaClass.simpleName} ${t.message}")
                } finally {
                    pending.finish()
                }
            }
            return
        }

        // action=launch 直接调壳进程 launchApp（可重复），用于验证"旧实例接走 intent → 清残留重试"。
        if (intent.getStringExtra("action") == "launch") {
            val pkg = intent.getStringExtra("pkg").orEmpty()
            val display = intent.getIntExtra("display", -1)
            if (pkg.isEmpty() || display < 0) {
                Log.e(TAG, "launch: 缺少 pkg/display")
                return
            }
            val pending = goAsync()
            thread(name = "debug-launch") {
                try {
                    val session = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        DebugEntryPoint::class.java,
                    ).routingShellSession()
                    val proxy = runBlocking { session.require() }
                    val ok = proxy.launchApp(pkg, display)
                    Log.i(TAG, "launch pkg=$pkg display=$display ok=$ok")
                } catch (t: Throwable) {
                    Log.e(TAG, "launch 失败：${t.javaClass.simpleName} ${t.message}")
                } finally {
                    pending.finish()
                }
            }
            return
        }

        // action=probe 跑一遍设备能力探针，验证设备层能否经自建通道工作。
        if (intent.getStringExtra("action") == "probe") {
            val pending = goAsync()
            thread(name = "debug-probe") {
                try {
                    val probe = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        DebugEntryPoint::class.java,
                    ).capabilityProbe()
                    val results = runBlocking { probe.runAll() }
                    results.forEach { Log.i(TAG, "probe ${it.probeId} pass=${it.passed} ${it.detail}") }
                } finally {
                    pending.finish()
                }
            }
            return
        }

        // action=exec 直接执行一条 shell 命令并打印输出，用于验证 app_process 自举等链路。
        if (intent.getStringExtra("action") == "exec") {
            val command = intent.getStringExtra("cmd").orEmpty()
            val pending = goAsync()
            thread(name = "debug-exec") {
                try {
                    val port = controller(context).discoverPort(AdbMdns.TLS_CONNECT, 8_000)
                    if (port == null) {
                        Log.e(TAG, "exec: no connect port")
                        return@thread
                    }
                    val result = controller(context).shell(port, command, 30_000)
                    Log.i(TAG, "exec out=<<<${result.getOrNull()}>>> err=${result.exceptionOrNull()}")
                } finally {
                    pending.finish()
                }
            }
            return
        }

        val code = intent.getStringExtra("code").orEmpty()
        val pending = goAsync()
        // 广播回调在主线程，链路全是阻塞 socket，必须挪走。
        thread(name = "debug-pair") {
            try {
                runPairing(context.applicationContext, code)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * 功能：跑一遍"发现配对端口 → 配对 → 发现连接端口 → 执行 id"
     * 参数：context 应用上下文；code 系统对话框里的 6 位配对码，**留空表示只测连接**（模拟已配对后重连）
     * 返回：无；结果写进 logcat（tag: MimirDebugPair）
     */
    private fun runPairing(context: Context, code: String) {
        val adb = controller(context)
        Log.i(TAG, "start code='$code' hasKeyBefore=${adb.hasKey()}")

        if (code.isNotEmpty()) {
            val pairingPort = adb.discoverPort(AdbMdns.TLS_PAIRING, DISCOVERY_MS)
            if (pairingPort == null) {
                Log.e(TAG, "fail: no pairing port")
                return
            }
            Log.i(TAG, "pairing port=$pairingPort")

            val pairResult = adb.pair(pairingPort, code, SOCKET_TIMEOUT_MS)
            Log.i(TAG, "pair ok=${pairResult.isSuccess} err=${pairResult.exceptionOrNull()}")
            if (pairResult.isFailure) return
        }

        val connectPort = adb.discoverPort(AdbMdns.TLS_CONNECT, DISCOVERY_MS)
        if (connectPort == null) {
            Log.e(TAG, "fail: no connect port")
            return
        }
        Log.i(TAG, "connect port=$connectPort")

        val shellResult = adb.shell(connectPort, "id", SOCKET_TIMEOUT_MS)
        Log.i(TAG, "shell out=${shellResult.getOrNull()?.trim()} err=${shellResult.exceptionOrNull()}")
    }

    /**
     * 功能：取 adb 通道
     * 参数：context 上下文
     * 返回：AdbController 实例
     */
    private fun controller(context: Context): AdbController = AdbController(
        context.applicationContext,
        PreferenceAdbKeyStore(
            context.applicationContext.getSharedPreferences(AdbController.PREFS_NAME, Context.MODE_PRIVATE),
        ),
    )

    /** 调试入口的 Hilt 取用点：接收器不是 Hilt 组件，只能走 EntryPoint。 */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DebugEntryPoint {
        fun shellLauncher(): ShellLauncher

        fun capabilityProbe(): CapabilityProbe

        fun routingShellSession(): com.neptine.mimir.device.selfhost.RoutingShellSession

        fun taskLauncher(): com.neptine.mimir.agent.TaskLauncher
    }

    private companion object {
        const val TAG = "MimirDebugPair"

        /** mDNS 发现等待上限（毫秒）。 */
        const val DISCOVERY_MS = 10_000L

        /** socket 读写超时（毫秒）。 */
        const val SOCKET_TIMEOUT_MS = 10_000
    }
}
