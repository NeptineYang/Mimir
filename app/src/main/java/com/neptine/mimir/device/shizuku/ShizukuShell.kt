package com.neptine.mimir.device.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.neptine.mimir.device.shell.IMimirShell
import com.neptine.mimir.device.shell.MimirShellService
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shizuku UserService 连接（设计文档 §2.2 / §3.4）。
 *
 * 建屏、取帧、注入、拉起 App 全部跑在 shell uid 的 [MimirShellService] 里；
 * 本类只负责把那条 binder 拿到并缓存，断连后清空以便下次重绑。
 */
class ShizukuShell(private val context: Context) {

    /**
     * 单次绑定的等待上限。
     *
     * 首次绑定要跨进程拉起 app_process 并加载本 APK 的 dex；HyperOS/MIUI 上冷启动
     * 实测可能超过 10s（后台限制越严越慢），10s 会把"慢"误判成"不可用"。
     */
    private val bindTimeoutMs = 30_000L

    private val mutex = Mutex()

    @Volatile
    private var cached: IMimirShell? = null

    /**
     * 功能：判断 Shizuku binder 与授权是否就绪
     * 参数：无
     * 返回：两者都就绪为 true
     */
    fun isReady(): Boolean {
        if (!Shizuku.pingBinder()) return false
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 功能：取得 shell 服务代理，必要时建立绑定
     * 参数：无
     * 返回：可用的服务代理；Shizuku 未就绪或绑定超时时抛异常
     */
    suspend fun require(): IMimirShell {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: bind().also { cached = it }
        }
    }

    /**
     * 功能：绑定 UserService 并等待回调
     * 参数：无
     * 返回：服务代理
     * 抛出：IllegalStateException 多次超时，消息里写明常见原因与处置
     *
     * 超时自动重试一次：真机上第一次拉起 app_process 常常被系统拖慢或被后台策略卡住，
     * 立刻重试往往就成功了；仍然失败才按"设备层不可用"上报。
     */
    private suspend fun bind(): IMimirShell {
        var lastTimeout: TimeoutCancellationException? = null
        repeat(BIND_ATTEMPTS) { attempt ->
            try {
                return bindOnce()
            } catch (e: TimeoutCancellationException) {
                lastTimeout = e
                if (attempt < BIND_ATTEMPTS - 1) {
                    delay(RETRY_GAP_MS)
                }
            }
        }
        throw IllegalStateException(
            "Shizuku UserService 未能在 ${bindTimeoutMs / 1000} 秒内启动。" +
                "常见原因：系统拦截了 Mimir 的后台子进程（省电策略 / 自启动）、" +
                "或 Shizuku 服务已失效。" +
                "处理：把 Mimir 的省电策略设为「无限制」并允许自启动，" +
                "或在 Shizuku 里停止服务后重新启动，然后再点「重新检测」。",
            lastTimeout,
        )
    }

    /**
     * 功能：执行一次绑定
     * 参数：无
     * 返回：服务代理
     */
    private suspend fun bindOnce(): IMimirShell = withTimeout(bindTimeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, MimirShellService::class.java.name),
            )
                .processNameSuffix("shell")
                .daemon(false)
                // 版本号变了 Shizuku 才会杀掉旧进程、用当前 APK 重新拉起。
                // 每次随 AIDL 或服务实现变更而 +1，否则升级后可能复用到上一个版本的进程。
                .version(USER_SERVICE_VERSION)

            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    // 绑定回调可能重复到达（服务重启、Shizuku 重连、daemon=false 重新拉起）。
                    // 协程已经拿到结果时再 resume 会抛 "Already resumed"，异常发生在主线程
                    // Looper 里，直接把整个进程带崩——表现为用户看到"应用没有响应"。
                    if (!continuation.isActive) return
                    val service = binder?.let { IMimirShell.Stub.asInterface(it) }
                    if (service == null) {
                        continuation.resumeWithException(IllegalStateException("UserService binder 为空"))
                    } else {
                        continuation.resume(service)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    cached = null
                }
            }

            try {
                Shizuku.bindUserService(args, connection)
            } catch (e: Throwable) {
                // 绑定调用本身失败与回调失败可能同时发生，同样只在协程还活着时收尾。
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    }

    private companion object {
        /** 绑定尝试次数：首次 + 1 次重试。 */
        const val BIND_ATTEMPTS = 2

        /** 两次绑定之间的间隔（毫秒）。 */
        const val RETRY_GAP_MS = 1_000L

        /**
         * UserService 版本；服务实现变更时递增，用于让 Shizuku 重建进程。
         *
         * 24：回收遗留副屏前先结束同包的旧 shell 进程（装包后会残留）；
         * 23：新增遗留副屏回收（reapDisplays）；22：置顶成功返回码补上
         * START_TASK_TO_FRONT(2)；21：置顶增加 am start + REORDER_TO_FRONT 兜底；
         * 20：剪贴板走 IClipboard 显式 callingPackage。
         */
        const val USER_SERVICE_VERSION = 24
    }
}
