package com.neptine.mimir.data.remote

/**
 * 模型后端访问口（设计文档 §2.4）。
 *
 * 云端 API、局域网 vLLM、公网反代三种档位共用同一套 OpenAI 兼容接口，
 * 差异只在 base_url 与鉴权，因此接口不出现"厂商"概念。
 */
interface LlmClient {

    /**
     * 功能：发送一次 chat/completions 请求并解析回复
     * 参数：baseUrl 后端地址；apiKey 密钥（由加密存储取出后传入，不落库）；model 模型名；messages 消息列表
     * 返回：解析后的响应体
     */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
    ): ChatResponse
}
