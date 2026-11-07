package com.neptine.mimir.data.config

import com.neptine.mimir.data.remote.ChatMessage
import com.neptine.mimir.data.remote.ChatOptions
import com.neptine.mimir.data.remote.LlmClient
import javax.inject.Inject

/**
 * 模型后端连通性自检（设计文档 §2.4 / §13 真机联调清单第 4 条）。
 *
 * 存在的理由：接入真实后端时，配置错（地址少了 /v1、密钥无效、模型名不存在、局域网不可达）
 * 与"模型能力差"在任务失败信息里长得一模一样。这里用一条最小请求把两类问题分开，
 * 让用户在设置页就能分清"连不上"和"答得不好"。
 *
 * 它只证明"能通、能回一句话"，不证明动作协议遵循度——后者只能靠真实任务跑出来。
 */
class ModelHealthChecker @Inject constructor(
    private val llm: LlmClient,
    private val models: ModelConfigRepository,
) {

    /**
     * 功能：对指定角色的后端发一次最小请求
     * 参数：role 要测试的角色，默认 Executor（任务执行实际使用的那个）
     * 返回：自检结果
     */
    suspend fun check(role: ModelConfigRepository.ModelRole = ModelConfigRepository.ModelRole.EXECUTOR): HealthResult {
        val model = models.resolve(role)
            ?: return HealthResult.Failure("未配置可用的模型后端：base_url、模型名与 API Key 缺一不可")

        val startedAt = System.currentTimeMillis()
        return runCatching {
            val response = llm.chat(
                baseUrl = model.baseUrl,
                apiKey = model.apiKey,
                model = model.model,
                messages = listOf(
                    ChatMessage(role = "user", content = PING_PROMPT),
                ),
                // 自检只要一句回复：低思考 + 小上限，避免"测个连通性等两分钟"
                options = ChatOptions(reasoningEffort = "low", maxTokens = 512),
            )
            val reply = response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
            val elapsedMs = System.currentTimeMillis() - startedAt
            if (reply.isEmpty()) {
                HealthResult.Failure("后端返回 200 但回复为空（模型名可能不被支持）")
            } else {
                HealthResult.Success(
                    model = model.model,
                    latencyMs = elapsedMs,
                    reply = reply.take(REPLY_LIMIT),
                )
            }
        }.getOrElse { error ->
            HealthResult.Failure(error.message ?: error::class.java.simpleName)
        }
    }

    private companion object {
        /** 最小请求：不求有用，只求能回。 */
        const val PING_PROMPT = "只回复两个字符：OK"

        /** 回显上限，避免把整段异常文本塞进界面。 */
        const val REPLY_LIMIT = 40
    }
}

/** 自检结果。 */
sealed interface HealthResult {

    /**
     * 连通成功。
     *
     * @property model 实际使用的模型名
     * @property latencyMs 往返耗时（毫秒）
     * @property reply 模型的回复正文（截断后）
     */
    data class Success(val model: String, val latencyMs: Long, val reply: String) : HealthResult

    /**
     * 连通失败。
     *
     * @property reason 失败原因（网络、鉴权、模型名等）
     */
    data class Failure(val reason: String) : HealthResult
}
