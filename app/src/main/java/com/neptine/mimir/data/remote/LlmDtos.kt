package com.neptine.mimir.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI 兼容 chat/completions 的传输结构（设计文档 §2.1 / §2.4）。
 *
 * 请求侧手工构造 JSON（ChatMessage 含多模态内容块，用序列化注解反而更绕）；
 * 响应侧用注解，字段少且稳定。
 */

/**
 * 一条对话消息。
 *
 * @property role 角色：system / user / assistant
 * @property content 文本内容
 * @property imageDataUrls 附带的图片，元素是 `data:image/webp;base64,...` 形式；
 *           非空时 content 会编码成 [文本块, 图片块…] 的数组（§2.3.1 送模型截图）
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val imageDataUrls: List<String> = emptyList(),
)

/**
 * 响应体（非流式）。
 *
 * @property id 响应标识
 * @property choices 候选回复
 * @property usage token 用量；部分后端不返回
 */
@Serializable
data class ChatResponse(
    val id: String = "",
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null,
)

/**
 * 单个候选回复。
 *
 * @property index 候选序号
 * @property message 回复消息
 * @property finishReason 结束原因
 */
@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatReply? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

/**
 * 回复正文；后端只返回纯文本，不建模多模态。
 *
 * @property content 文本内容
 */
@Serializable
data class ChatReply(
    val content: String = "",
)

/**
 * token 用量；审计里记录的 image_tokens 最终以此处实测值为准（§2.3.1）。
 *
 * @property promptTokens 输入 token
 * @property completionTokens 输出 token
 * @property totalTokens 合计 token
 */
@Serializable
data class ChatUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerialName("completion_tokens")
    val completionTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0,
)
