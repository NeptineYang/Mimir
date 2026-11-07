package com.neptine.mimir.data.remote

/**
 * 单次调用的可调参数。
 *
 * @property reasoningEffort 思考强度：low / high / max。智谱 GLM-5.3 系列强制思考且默认 max，
 *           不指定就等于每次点按都做深度推理（实测单步 ttfb 9–207 秒）。后端不认这个字段时
 *           客户端会自动去掉重试，不会把整个后端打死。
 * @property maxTokens 输出上限；不指定时后端按模型上限（128K）放开，长尾会拖到超时。
 * @property forceJson 是否带 response_format；后端拒绝时自动退回普通请求。
 */
data class ChatOptions(
    val reasoningEffort: String? = null,
    val maxTokens: Int? = null,
    val forceJson: Boolean = true,
)

/**
 * 模型后端访问口（设计文档 §2.4）。
 *
 * 云端 API、局域网 vLLM、公网反代三种档位共用同一套 OpenAI 兼容接口，
 * 差异只在 base_url 与鉴权，因此接口不出现"厂商"概念。
 */
interface LlmClient {

    /**
     * 功能：发送一次 chat/completions 请求并解析回复
     * 参数：baseUrl 后端地址；apiKey 密钥（由加密存储取出后传入，不落库）；model 模型名；
     *       messages 消息列表；options 思考强度与输出上限等可调参数
     * 返回：解析后的响应体（含 usage，调用方据此记账）
     */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        options: ChatOptions = ChatOptions(),
    ): ChatResponse
}
