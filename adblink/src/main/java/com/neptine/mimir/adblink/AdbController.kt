package com.neptine.mimir.adblink

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用内 adb 通道的唯一对外入口。
 *
 * 只做四件事：发现端口、配对、连接并执行一条 shell 命令。所有方法都是**阻塞调用**，
 * 调用方必须在 IO 线程使用（本模块不引协程，保持无 Android 依赖之外的最小面）。
 *
 * 连接一律走 127.0.0.1：adbd 的 TLS 端口监听在所有网卡上，走 loopback 可以避开
 * Wi-Fi 地址变化与局域网权限策略。
 */
class AdbController(context: Context, private val keyStore: AdbKeyStore) {

    private val appContext = context.applicationContext

    /** adb 客户端密钥：首次使用时生成，之后从 [keyStore] 解密复用。 */
    private val key: AdbKey by lazy { AdbKey(keyStore, KEY_NAME) }

    /**
     * 功能：判断本机是否已有可用的 adb 密钥（即曾经配对成功）
     * 参数：无
     * 返回：有密钥为 true
     */
    fun hasKey(): Boolean = keyStore.get() != null

    /**
     * 功能：删除本机的 adb 密钥，使下次必须重新配对
     * 参数：无
     * 返回：无
     */
    fun clearKey() = keyStore.clear()

    /**
     * 功能：用 mDNS 发现 adbd 的端口
     * 参数：serviceType 服务类型（[AdbMdns.TLS_PAIRING] 或 [AdbMdns.TLS_CONNECT]）；timeoutMs 等待上限
     * 返回：端口号；超时为 null
     */
    fun discoverPort(serviceType: String, timeoutMs: Long): Int? {
        val latch = CountDownLatch(1)
        val found = AtomicInteger(-1)
        val mdns = AdbMdns(appContext, serviceType) { port ->
            if (port > 0 && found.compareAndSet(-1, port)) latch.countDown()
        }
        mdns.start()
        return try {
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) found.get() else null
        } finally {
            mdns.stop()
        }
    }

    /**
     * 功能：用配对码完成无线调试配对
     * 参数：port 配对端口；pairingCode 系统对话框显示的 6 位码；timeoutMs 读超时
     * 返回：成功为 Unit；失败携带 [AdbInvalidPairingCodeException]（码错/过期）等具体异常
     *
     * 配对成功后 adbd 会把本机公钥写进自己的信任表，之后连接不再需要配对码——
     * 这是"重启后只需打开无线调试、不必重新配对"的前提。
     */
    fun pair(port: Int, pairingCode: String, timeoutMs: Int): Result<Unit> = runCatching {
        AdbPairingClient(HOST, port, pairingCode, key, timeoutMs).use { client ->
            if (!client.start()) error("配对握手未完成")
        }
    }

    /**
     * 功能：连接 adbd 并执行一条 shell 命令
     * 参数：port 连接端口；command 命令；timeoutMs 读超时
     * 返回：命令的标准输出（含 stderr，adbd 会合并）；失败携带具体异常
     */
    fun shell(port: Int, command: String, timeoutMs: Int): Result<String> = runCatching {
        val output = StringBuilder()
        AdbClient(HOST, port, key, timeoutMs).use { client ->
            client.connect()
            client.shellCommand(command) { bytes -> output.append(String(bytes)) }
        }
        output.toString()
    }

    companion object {
        /**
         * 存放 adb 密钥的 prefs 文件名。
         *
         * 对外暴露是为了让装配层（Hilt）与调试入口用同一份存储——密钥只有一份，
         * 分叉出第二份会导致"看起来配对过、实际连不上"。
         */
        const val PREFS_NAME = "mimir_adb"

        /** adbd 监听在所有网卡上，走 loopback 最稳。 */
        private const val HOST = "127.0.0.1"

        /** 写进 adb 公钥尾部的标识，出现在设备的"已配对设备"列表里。 */
        private const val KEY_NAME = "mimir@android"
    }
}
