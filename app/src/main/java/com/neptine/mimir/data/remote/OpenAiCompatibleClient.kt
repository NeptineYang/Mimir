package com.neptine.mimir.data.remote

import android.util.Log
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * OpenAI 兼容客户端（设计文档 §2.1 / §2.4）。
 *
 * 不引入 langchain4j 这类重型抽象：本项目只需要一次 chat/completions 调用，
 * 自写请求构造比适配第三方传输层更短。
 *
 * 用非流式而不是 SSE：动作必须等整段 JSON 到齐才能解析，流式只改善首字延迟、
 * 不减少总等待，却要多写一套增量解析状态机。设计文档 §2.1 提到 SSE，
 * 这里如实记为偏差，等真正需要"边想边显示"时再补。
 *
 * 非流式的代价是**整个生成过程都算在读超时里**：OkHttp 默认 10 秒读超时会把
 * 正常的视觉请求判成超时（真机实测），因此超时必须在 NetworkModule 里显式配。
 */
class OpenAiCompatibleClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) : LlmClient {

    /**
     * 功能：发送一次 chat/completions 请求并解析回复
     * 参数：baseUrl 后端地址；apiKey 密钥；model 模型名；messages 消息列表
     * 返回：解析后的响应体
     * 抛出：IllegalStateException 网络失败或后端返回非 2xx；消息里写明是哪一类
     *
     * 传输层失败重试：普通超时/断连重试一次；DNS 解析失败与连接失败重试多次。
     * 真机上蜂窝网与 Wi-Fi 切换、DNS 抖动会让 UnknownHostException 连续出现十几秒，
     * 只重试一次仍然会把正在推进的任务判死（淘宝实测在 13 步时因 DNS 瞬断失败）。
     * 只重试 IOException，不重试 4xx/5xx——那是配置或模型侧问题，重发只会重复计费。
     */
    override suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        options: ChatOptions,
    ): ChatResponse {
        var lastError: IOException? = null
        var attempt = 0
        while (true) {
            try {
                return execute(baseUrl, apiKey, model, messages, options)
            } catch (e: IOException) {
                lastError = e
                val connectivity = e is UnknownHostException || e is ConnectException
                val maxAttempts = if (connectivity) CONNECTIVITY_ATTEMPTS else TRANSPORT_ATTEMPTS
                if (attempt >= maxAttempts - 1) break
                val gap = if (connectivity) {
                    CONNECTIVITY_RETRY_GAPS_MS.getOrElse(attempt) { CONNECTIVITY_RETRY_GAPS_MS.last() }
                } else {
                    RETRY_GAP_MS
                }
                attempt++
                delay(gap)
            }
        }
        throw IllegalStateException(networkFailureMessage(lastError), lastError)
    }

    /**
     * 功能：发一次请求并把响应体解析成对象，必要时退化请求参数
     * 参数：baseUrl 后端地址；apiKey 密钥；model 模型名；messages 消息列表；options 可调参数
     * 返回：解析后的响应体
     * 抛出：IOException 传输层失败；IllegalStateException 后端返回非 2xx
     *
     * 退化顺序按"损失从小到大"：先去掉思考强度与输出上限（老模型不认这两个字段），
     * 再去掉 response_format（个别后端直接 400）。两次退化都不改变消息内容，
     * 因此重复计费只发生在"后端确实拒绝"的情况下，而 400 不计费。
     */
    private suspend fun execute(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        options: ChatOptions,
    ): ChatResponse {
        var current = options
        var lastError: IllegalStateException? = null
        repeat(MAX_DEGRADE_STEPS) {
            try {
                return executeOnce(baseUrl, apiKey, model, messages, current)
            } catch (e: IllegalStateException) {
                val next = degrade(e.message.orEmpty(), current) ?: throw e
                lastError = e
                current = next
            }
        }
        throw lastError ?: IllegalStateException("模型接口失败")
    }

    /**
     * 功能：按后端的 400 提示退掉一个不被接受的请求参数
     * 参数：message 错误消息；options 当前参数
     * 返回：退化后的参数；无法再退化时为 null（由调用方抛出原错误）
     */
    private fun degrade(message: String, options: ChatOptions): ChatOptions? {
        if (!message.contains("返回 400")) return null
        val extrasRejected = options.reasoningEffort != null || options.maxTokens != null
        if (extrasRejected && (message.contains("reasoning_effort") || message.contains("max_tokens"))) {
            return options.copy(reasoningEffort = null, maxTokens = null)
        }
        if (options.forceJson && message.contains("response_format")) {
            return options.copy(forceJson = false)
        }
        return null
    }

    /**
     * 功能：发一次请求并把响应体解析成对象
     * 参数：baseUrl/apiKey/model/messages 请求内容；options 可调参数
     * 返回：解析后的响应体
     * 抛出：IOException 传输层失败；IllegalStateException 后端返回非 2xx
     */
    private suspend fun executeOnce(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        options: ChatOptions,
    ): ChatResponse = suspendCancellableCoroutine { cont ->
        val request = buildRequest(baseUrl, apiKey, model, messages, options)
        val call = http.newCall(request)
        // 取消必须是真取消：单步超时与"强制打断"都靠协程取消触发，阻塞式 execute()
        // 不会响应取消，一个 200 秒的长尾请求会把任务协程一起拖到 callTimeout。
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {

                /**
                 * 功能：传输层失败时把异常交回调用方
                 * 参数：call 本次调用；e 失败原因
                 * 返回：无
                 */
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                /**
                 * 功能：收到响应后解析并交回调用方
                 * 参数：call 本次调用；response HTTP 响应
                 * 返回：无
                 */
                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val parsed = runCatching { parseResponse(resp, messages, model, options) }
                        if (!cont.isActive) return
                        parsed.fold(
                            onSuccess = { cont.resume(it) },
                            onFailure = { cont.resumeWithException(it) },
                        )
                    }
                }
            },
        )
    }

    /**
     * 功能：把一次 HTTP 响应解析成聊天响应
     * 参数：response HTTP 响应；messages 原请求消息（用于判断是否带图）；model 模型名；
     *       options 可调参数（只用于日志）
     * 返回：解析后的响应体
     * 抛出：IllegalStateException 非 2xx；反序列化异常响应体不是合法 JSON
     */
    private fun parseResponse(
        response: Response,
        messages: List<ChatMessage>,
        model: String,
        options: ChatOptions,
    ): ChatResponse {
        val text = response.body.string()
        if (!response.isSuccessful) {
            throw IllegalStateException(
                httpFailureMessage(response.code, text, hasImage = messages.any { it.imageDataUrls.isNotEmpty() }),
            )
        }
        val parsed = json.decodeFromString(ChatResponse.serializer(), text)
        parsed.usage?.let { usage ->
            Log.d(
                TAG,
                "用量 in=${usage.promptTokens} out=${usage.completionTokens} total=${usage.totalTokens} " +
                    "缓存=${usage.cachedTokens} model=$model 思考=${options.reasoningEffort ?: "默认"}",
            )
        }
        return parsed
    }

    /**
     * 功能：把非 2xx 响应翻成带处置建议的话
     * 参数：code HTTP 状态码；body 响应体；hasImage 本次请求是否带图片
     * 返回：失败原因
     *
     * 带图的请求被 400 拒绝时，最常见的原因是模型不支持图片输入或后端不认 webp 的 data URL，
     * 而这条错误和"密钥错/地址错"长得一样，不点出来会白排查很久。
     */
    private fun httpFailureMessage(code: Int, body: String, hasImage: Boolean): String {
        val hint = if (code == 400 && hasImage) {
            "。带图请求被拒，常见原因是该模型不支持图片输入，或后端不认 image/webp 的 data URL"
        } else {
            ""
        }
        return "模型接口返回 $code：${body.take(ERROR_BODY_LIMIT)}$hint"
    }

    /**
     * 功能：把传输层异常翻成一句能指导处置的话
     * 参数：error 最后一次传输层异常
     * 返回：失败原因；不含密钥与请求体
     *
     * "timeout" 三个字无法区分是网络不通、模型太慢还是地址写错，所以在消息里分开写。
     */
    private fun networkFailureMessage(error: IOException?): String {
        val detail = error?.let { "${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
        return when (error) {
            is SocketTimeoutException -> "模型接口超时（$detail）。若反复出现，说明该模型在视觉请求上单次耗时超过超时上限"
            is UnknownHostException, is ConnectException -> "模型后端连不上（$detail）。检查 base_url、网络与是否需要代理"
            else -> "模型接口网络失败（$detail）"
        }
    }

    /**
     * 功能：构造 HTTP 请求
     * 参数：baseUrl 后端地址；apiKey 密钥；model 模型名；messages 消息列表；options 可调参数
     * 返回：待发送的 OkHttp 请求
     */
    private fun buildRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        options: ChatOptions,
    ): Request {
        val payload = buildJsonObject {
            put("model", model)
            put("temperature", 0)
            // 思考强度：GLM-5.3 系列不能关思考，只能降档。不传等于默认 max，
            // 单步点按也要跑完整推理链（真机实测 9–207 秒）。
            options.reasoningEffort?.let { put("reasoning_effort", it) }
            // 输出上限：动作 JSON 只有几十字节，不设上限时后端放到 128K，长尾直接把请求拖到超时。
            options.maxTokens?.let { put("max_tokens", it) }
            // 强制 JSON：autoglm-phone 这类模型默认会先输出自然语言推理，单靠提示词约束不住。
            // 后端不认这个字段时由 execute 退化重试。
            if (options.forceJson) {
                putJsonObject("response_format") { put("type", "json_object") }
            }
            putJsonArray("messages") {
                messages.forEach { add(encodeMessage(it)) }
            }
        }

        return Request.Builder()
            .url(chatCompletionsUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", CONTENT_TYPE)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /**
     * 功能：拼出 chat/completions 的完整地址
     * 参数：baseUrl 用户填写的后端地址（可带或不带尾部斜杠）
     * 返回：完整 URL
     */
    private fun chatCompletionsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "模型后端地址为空" }
        return "$trimmed/chat/completions"
    }

    /**
     * 功能：把一条消息编码为 JSON
     * 参数：message 消息
     * 返回：消息 JSON 对象；无图片时 content 是字符串，有图片时是内容块数组
     */
    private fun encodeMessage(message: ChatMessage) = buildJsonObject {
        put("role", message.role)
        if (message.imageDataUrls.isEmpty()) {
            put("content", message.content)
        } else {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", message.content)
                }
                message.imageDataUrls.forEach { url ->
                    addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", url) }
                    }
                }
            }
        }
    }

    private companion object {
        /** 诊断日志标签，与 NetworkTimingListener 共用，便于把耗时与用量对照起来看。 */
        const val TAG = "MimirNet"

        /** 请求参数最多退化几次：思考强度/输出上限 → response_format → 无路可退。 */
        const val MAX_DEGRADE_STEPS = 3

        /** 请求媒体类型。 */
        const val CONTENT_TYPE = "application/json"

        /** OkHttp 的媒体类型常量。 */
        val JSON_MEDIA_TYPE = CONTENT_TYPE.toMediaType()

        /** 失败时最多回显多少字响应体，避免把整页 HTML 塞进异常信息。 */
        const val ERROR_BODY_LIMIT = 300

        /** 传输层最大尝试次数（首次 + 1 次重试）；超时等长耗时失败重试太多只会让用户多等。 */
        const val TRANSPORT_ATTEMPTS = 2

        /** DNS / 连接失败的最大尝试次数：这类失败通常秒级返回，值得多试几次熬过网络切换。 */
        const val CONNECTIVITY_ATTEMPTS = 5

        /** DNS / 连接失败的重试间隔（毫秒）：逐步拉长，覆盖切换网络时的十几秒空窗。 */
        val CONNECTIVITY_RETRY_GAPS_MS = longArrayOf(2_000L, 4_000L, 6_000L, 10_000L)

        /** 重试前的间隔（毫秒）。 */
        const val RETRY_GAP_MS = 1200L
    }
}

/**
 * 功能：把图片字节编码成 OpenAI 兼容的 data URL
 * 参数：bytes 编码后的图片；mimeType 图片类型，如 image/webp
 * 返回：`data:<mime>;base64,<payload>` 形式的字符串
 */
fun encodeImageDataUrl(bytes: ByteArray, mimeType: String = "image/webp"): String =
    "data:$mimeType;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
