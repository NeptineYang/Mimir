package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.AgentAction
import com.neptine.mimir.core.contract.CredentialRecord
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.PlanCursor
import com.neptine.mimir.core.contract.PlanStep
import com.neptine.mimir.core.contract.TaskPlan
import com.neptine.mimir.core.contract.TaskSpec
import com.neptine.mimir.data.config.ModelConfigRepository
import com.neptine.mimir.data.db.entity.AuditEntity
import com.neptine.mimir.data.remote.ChatMessage
import com.neptine.mimir.data.remote.LlmClient
import com.neptine.mimir.data.remote.encodeImageDataUrl
import javax.inject.Inject

/**
 * 单步决策器（设计文档 §2.4）。
 *
 * 用 AutoGLM 这类针对手机 GUI 逐步决策微调过的模型：动作语法、中文 App 常识、
 * 外卖购物场景规则都是开箱的，换通用旗舰自写 prompt 至少多花两天调 badcase。
 *
 * 它只产出动作，不判断动作是否该执行、是否已生效——那是 Guard 与后置条件的事。
 */
class Executor @Inject constructor(
    private val llm: LlmClient,
    private val promptBuilder: PromptBuilder,
    private val models: ModelConfigRepository,
) {

    /**
     * 功能：基于当前观察与上下文产出下一步动作
     * 参数：request 单步请求（TaskSpec 全局约束 + 当前 PlanStep + 近 3 步历史 + 元素表 + 截图 + 命中配方）
     * 返回：动作、模型原始输出与尝试次数
     * 抛出：ModelUnavailableException 未配置后端；ActionParseException 两次输出都不可解析
     *
     * 解析失败按设计文档 §1.3 S4b 用同一份 prompt 重试一次；仍失败就交给上层转人工或规则兜底，
     * 不做第三次——连续两次读不懂同一张界面，多半是模型能力问题，不是运气问题。
     */
    suspend fun decide(request: ExecutorRequest): DecideResult {
        val model = models.resolve(ModelConfigRepository.ModelRole.EXECUTOR)
            ?: throw ModelUnavailableException("未配置可用的模型后端（base_url / 模型名 / API Key 缺一不可）")

        val payload = promptBuilder.build(request)
        val messages = buildMessages(payload)

        var lastError: Exception? = null
        var lastRaw = ""
        repeat(MAX_ATTEMPTS) { attemptIndex ->
            val raw = llm.chat(model.baseUrl, model.apiKey, model.model, messages)
                .choices.firstOrNull()?.message?.content
                .orEmpty()
            lastRaw = raw
            runCatching { ActionProtocol.parse(raw, request.snapshot) }
                .onSuccess { return DecideResult(it, raw, attemptIndex + 1) }
                .onFailure { lastError = it as? Exception ?: Exception(it) }
        }
        // 把原始输出带进错误信息：解析失败时审计里没有任何动作行，
        // 不留下模型原话就只能靠复现猜（真机 DeepSeek 的 point 解析失败就是这样）。
        throw ActionParseException(
            "连续 $MAX_ATTEMPTS 次输出无法解析：${lastError?.message}；模型原始输出：${lastRaw.take(RAW_HINT_LIMIT)}",
        )
    }

    /**
     * 功能：把渲染后的 prompt 转成消息列表
     * 参数：payload 渲染结果
     * 返回：系统消息 + 用户消息（附截图时带图片块）
     */
    private fun buildMessages(payload: PromptPayload): List<ChatMessage> {
        val images = payload.image?.let { listOf(encodeImageDataUrl(it.bytes)) }.orEmpty()
        return listOf(
            ChatMessage(role = "system", content = payload.systemPrompt),
            ChatMessage(role = "user", content = payload.userText, imageDataUrls = images),
        )
    }

    private companion object {
        /** 解析失败时附在错误信息里的模型原始输出长度上限。 */
        const val RAW_HINT_LIMIT = 200

        /** 同 prompt 的最大尝试次数（首次 + 1 次重试）。 */
        const val MAX_ATTEMPTS = 2
    }
}

/**
 * 单步请求。
 *
 * @property spec 任务不可变约束
 * @property plan 当前任务计划
 * @property cursor 计划游标，指向本次要执行的步骤
 * @property recentSteps 近 3 步审计记录，倒序排列
 * @property snapshot 当前一致观察
 * @property memories 命中的配方 top-2，仅作参考
 * @property credentials 当前应用可用的凭据元数据；只含 id/账号别名，不含密码
 */
data class ExecutorRequest(
    val spec: TaskSpec,
    val plan: TaskPlan,
    val cursor: PlanCursor,
    val recentSteps: List<AuditEntity>,
    val snapshot: ObservationSnapshot,
    val memories: List<MemoryHit>,
    val credentials: List<CredentialRecord> = emptyList(),
) {

    /**
     * 功能：取当前计划步骤
     * 参数：无
     * 返回：游标指向的步骤
     * 抛出：游标越界时抛 IllegalArgumentException——调用方应在越界前就转入验证
     */
    val step: PlanStep
        get() = requireNotNull(plan.stepAt(cursor)) { "计划游标越界，不应再请求决策" }
}

/**
 * 决策结果。
 *
 * @property action 解析出的动作
 * @property rawOutput 模型原始输出，写入审计供回放
 * @property attempts 实际尝试次数
 */
data class DecideResult(
    val action: AgentAction,
    val rawOutput: String,
    val attempts: Int,
)

/** 模型后端不可用（未配置或缺密钥）。 */
class ModelUnavailableException(message: String) : IllegalStateException(message)
