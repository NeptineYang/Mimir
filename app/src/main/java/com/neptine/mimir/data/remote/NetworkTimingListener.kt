package com.neptine.mimir.data.remote

import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response

/**
 * 请求分段计时（诊断用）。
 *
 * 目的只有一个：回答"模型调用慢在哪一层"。非流式的视觉请求把 DNS、连接、TLS、
 * 上传、等待首字节、下载、以及服务端生成时间全部串在一次调用里，只看一个总耗时
 * 无法区分"网络差"和"模型生成慢"，而这两者的处置完全不同。
 *
 * 输出示例（logcat -s MimirNet）：
 * ```
 * #3 dns=42ms connect=88ms tls=131ms send=9ms ttfb=28410ms read=312ms total=28992ms
 * ```
 * `ttfb`（requestHeadersEnd → responseHeadersStart）近似等于服务端排队 + 生成时间，
 * 它是判断"是不是模型慢"的关键指标；`dns/connect/tls` 高才是网络问题。
 */
class NetworkTimingListener : EventListener() {

    private val callStart = ThreadLocal<Long>()
    private var dnsStart = 0L
    private var dnsEnd = 0L
    private var connectStart = 0L
    private var connectEnd = 0L
    private var tlsStart = 0L
    private var tlsEnd = 0L
    private var sendStart = 0L
    private var sendEnd = 0L
    private var responseStart = 0L
    private var requestBytes = 0L

    override fun callStart(call: Call) {
        callStart.set(System.currentTimeMillis())
        dnsStart = 0; dnsEnd = 0; connectStart = 0; connectEnd = 0
        tlsStart = 0; tlsEnd = 0; sendStart = 0; sendEnd = 0; responseStart = 0
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsStart = System.currentTimeMillis()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
        dnsEnd = System.currentTimeMillis()
    }

    override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
        connectStart = System.currentTimeMillis()
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: java.net.InetSocketAddress,
        proxy: java.net.Proxy,
        protocol: okhttp3.Protocol?,
    ) {
        connectEnd = System.currentTimeMillis()
    }

    override fun secureConnectStart(call: Call) {
        tlsStart = System.currentTimeMillis()
    }

    override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
        tlsEnd = System.currentTimeMillis()
    }

    override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
        sendEnd = System.currentTimeMillis()
        requestBytes = request.body?.contentLength() ?: -1L
    }

    override fun responseHeadersStart(call: Call) {
        responseStart = System.currentTimeMillis()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        val start = callStart.get() ?: return
        val total = System.currentTimeMillis() - start
        Log.d(
            TAG,
            "#${id.incrementAndGet()} dns=${gap(dnsStart, dnsEnd)}ms connect=${gap(connectStart, connectEnd)}ms " +
                "tls=${gap(tlsStart, tlsEnd)}ms send=${gap(sendStart, sendEnd)}ms " +
                "ttfb=${gap(sendEnd, responseStart)}ms total≈${total}ms " +
                "请求体=${requestBytes}B 响应=${response.header("content-length") ?: "?"}B",
        )
    }

    override fun callFailed(call: Call, ioe: IOException) {
        val start = callStart.get() ?: return
        Log.d(TAG, "#${id.incrementAndGet()} 失败 total≈${System.currentTimeMillis() - start}ms ${ioe.javaClass.simpleName}: ${ioe.message}")
    }

    /** 两段时间戳之差；任一端缺失（如连接复用不会有 connect 阶段）返回 -1。 */
    private fun gap(from: Long, to: Long): Long = if (from == 0L || to == 0L) -1 else to - from

    private companion object {
        const val TAG = "MimirNet"
        val id = AtomicLong(0)
    }
}
