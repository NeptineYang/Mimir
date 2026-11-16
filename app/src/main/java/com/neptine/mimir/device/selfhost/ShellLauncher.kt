package com.neptine.mimir.device.selfhost

import android.content.Context
import android.os.Process
import android.util.Log
import com.neptine.mimir.adblink.AdbController
import com.neptine.mimir.adblink.AdbMdns
import com.neptine.mimir.device.shell.ShellProtocol
import com.neptine.mimir.shell.Bootstrap
import com.neptine.mimir.shell.ShellArgs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 通过应用内 adb 通道拉起壳进程。
 *
 * 命令形态（已在 HyperOS 实测）：
 * `CLASSPATH=<base.apk> app_process /system/bin --nice-name=<进程名> <Bootstrap> --mode=…`
 * ——`/system/bin` 在前、`--nice-name` 在后，这是 `app_process` 的解析顺序决定的，
 * 换位置会把它当成主类名。
 *
 * 两段式启动：先同步跑 selftest 确认 "dex 能加载、uid 是 shell"，再异步拉起常驻进程。
 * 这样失败时能区分"根本跑不起来"与"起来了但没交付上"。
 */
@Singleton
class ShellLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adb: AdbController,
    private val bridge: ShellBridge,
) {

    /**
     * 功能：启动特权服务
     * 参数：无
     * 返回：成功为 Unit；失败带可见原因
     */
    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        // 已有可用 binder 时直接返回：再拉一个会把正在服务的那个当"兄弟"清掉。
        if (bridge.isReady()) return@withContext Result.success(Unit)

        val port = adb.discoverPort(AdbMdns.TLS_CONNECT, DISCOVERY_MS)
            ?: return@withContext Result.failure(
                IllegalStateException("没发现无线调试端口：请确认无线调试已打开且连着 Wi-Fi"),
            )

        val apk = bridge.apkPath()

        // ① 同步自检：把"命令能不能跑"与"跑起来后能不能交付"分开。
        val selfTest = adb.shell(port, selfTestCommand(apk), EXEC_TIMEOUT_MS)
        val selfTestOut = selfTest.getOrNull().orEmpty()
        if (!selfTestOut.contains(SELFTEST_MARK)) {
            val reason = selfTest.exceptionOrNull()?.message ?: selfTestOut.take(200)
            return@withContext Result.failure(IllegalStateException("壳进程自检失败：$reason"))
        }
        Log.i(TAG, "selftest ok: ${selfTestOut.trim()}")

        // ② 异步拉起常驻进程：setsid 脱离 adbd 会话，否则流一关就被 SIGHUP 带走。
        val token = bridge.newToken()
        val command = serveCommand(apk, token)
        // 只记前 80 字符：完整命令里有一次性令牌，不能进日志。
        Log.i(TAG, "serve cmd: ${command.take(80)}…")
        val serve = adb.shell(port, command, EXEC_TIMEOUT_MS)
        if (serve.isFailure) {
            return@withContext Result.failure(
                IllegalStateException("壳进程启动失败：${serve.exceptionOrNull()?.message}"),
            )
        }

        // ③ 等 binder 交付：壳进程要建 Context、实例化服务，再走一次 provider 往返。
        repeat(AWAIT_ATTEMPTS) {
            if (bridge.isReady()) return@withContext Result.success(Unit)
            delay(AWAIT_GAP_MS)
        }
        Result.failure(IllegalStateException("壳进程已启动，但没交付上 binder：请把 Mimir 的省电策略设为无限制"))
    }

    /**
     * 功能：拼接自检命令
     * 参数：apk 当前安装包路径
     * 返回：可直接下发的命令行
     */
    private fun selfTestCommand(apk: String): String =
        "CLASSPATH=$apk ${baseCommand(apk)} --mode=${ShellArgs.MODE_SELFTEST} " +
            "--proto=${ShellProtocol.PROTOCOL} --apk=$apk"

    /**
     * 功能：拼接常驻启动命令（后台执行）
     * 参数：apk 当前安装包路径；token 一次性握手令牌
     * 返回：可直接下发的命令行
     */
    private fun serveCommand(apk: String, token: String): String {
        val command = buildString {
            // CLASSPATH 必须在 setsid 之前：`CLASSPATH=x setsid …` 才是"带环境变量启动"，
            // 反过来 `setsid CLASSPATH=x …` 会让 setsid 把 `CLASSPATH=x` 当成要执行的命令。
            append("CLASSPATH=$apk setsid ${baseCommand(apk)}")
            append(" --mode=${ShellArgs.MODE_SERVE} --proto=${ShellProtocol.PROTOCOL} --apk=$apk")
            append(" --token=$token --pkg=${context.packageName}")
            append(" --app-uid=${Process.myUid()} --started=${System.currentTimeMillis()}")
        }
        // 两个细节都不能省：
        // 1) 输出落到 /data/local/tmp：正常为空，出问题时是唯一能看到"为什么没起来"的地方；
        // 2) 末尾 sleep 1：adb 会话一关，adbd 会连同进程组一起回收，setsid 还没来得及
        //    完成分离就被杀掉（实测表现为"进程时有时无"）。等一拍让分离落地。
        return "$command < /dev/null > $LOG_PATH 2>&1 & sleep 1"
    }

    /**
     * 功能：拼接命令前缀（CLASSPATH + app_process + 主类）
     * 参数：apk 当前安装包路径
     * 返回：命令前缀（不含 CLASSPATH，由调用方决定放在 setsid 前还是后）
     */
    private fun baseCommand(apk: String): String =
        "app_process /system/bin --nice-name=${ShellProtocol.PROCESS_NAME} ${Bootstrap::class.java.name}"

    private companion object {
        const val TAG = "ShellLauncher"

        /** 连接端口发现上限（毫秒）。 */
        const val DISCOVERY_MS = 8_000L

        /** 单条 adb 命令的超时（毫秒）。 */
        const val EXEC_TIMEOUT_MS = 20_000

        /** 等待 binder 的次数与间隔：共约 20 秒。 */
        const val AWAIT_ATTEMPTS = 40
        const val AWAIT_GAP_MS = 500L

        /** 自检成功的标记，与 [Bootstrap.selfTest] 的输出前缀一致。 */
        const val SELFTEST_MARK = "MIMIR_SHELL_OK"

        /** 壳进程的启动日志；shell uid 可写，app 读不到，只能 adb 取。 */
        const val LOG_PATH = "/data/local/tmp/mimir_shell.log"
    }
}
