package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.TaskSpec
import com.neptine.mimir.data.config.ModelConfigRepository
import com.neptine.mimir.data.remote.ChatMessage
import com.neptine.mimir.data.remote.LlmClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 完成契约的证据采纳（设计文档 §4.4.1 的实现补充，"两段式契约"）。
 *
 * 背景：`requiredFields` 是 Planner 在**还没见过目标页**时写下的字符串。它必然可能猜错——
 * 用户说「关于手机」，小米把这页叫「我的设备」；用户用英文提问，界面上全是中文。
 * 按字面断言，一次成功的任务会因为"标签猜错"被判不符。
 *
 * 这里把契约拆成两段：计划期只给"完成边界"（自然语言）与可选字段；到验证阶段，
 * **在独立复验页上**让 reviewer 只读地摘抄能证明边界的原文片段，再由本地逐字核对
 * "这些片段确实出现在复验快照里"。
 *
 * 关键约束：模型只说"选哪几句"，不说"算不算完成"。
 * - 摘抄必须逐字来自页面文本，本地 `contains` 校验，改写/翻译/编造一律丢弃；
 * - 一句有效证据都摘不出来，就按未达成处理，回落到原来的 MISMATCH 路径。
 */
@Singleton
class EvidenceReviewer @Inject constructor(
    private val llm: LlmClient,
    private val models: ModelConfigRepository,
) {

    /**
     * 功能：在给定快照上摘抄能证明完成边界的原文证据
     * 参数：spec 任务约束；snapshot 独立复验快照
     * 返回：是否达成、可用证据片段（已本地校验）、一句话说明
     */
    suspend fun extract(spec: TaskSpec, snapshot: ObservationSnapshot): EvidenceOutcome {
        val model = models.resolve(ModelConfigRepository.ModelRole.REVIEWER)
            ?: return EvidenceOutcome(false, emptyList(), "未配置 reviewer 模型，无法采纳证据")
        val texts = snapshot.elements
            .map { it.text.ifEmpty { it.contentDescription } }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_TEXTS)
        if (texts.isEmpty()) return EvidenceOutcome(false, emptyList(), "复验页没有可摘抄的文本")

        val raw = runCatching { call(model, SYSTEM_PROMPT, userPrompt(spec, snapshot, texts)) }
            .getOrElse { return EvidenceOutcome(false, emptyList(), "证据核对请求失败：${it.message ?: "未知错误"}") }
        val payload = LlmJson.extract(raw)
            ?: return EvidenceOutcome(false, emptyList(), "证据核对输出不可读")

        val achieved = payload["achieved"]?.jsonPrimitive?.booleanOrNull ?: false
        val quoted = payload["evidence"]?.jsonArray.orEmpty()
            .mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            .map(String::trim)
            .filter(String::isNotEmpty)
        val haystack = texts.joinToString("\n")
        val accepted = quoted.filter { haystack.contains(it) }.distinct().take(MAX_EVIDENCE)
        val invented = quoted.size - accepted.size

        val note = buildString {
            append(payload.string("reason").ifEmpty { "（reviewer 未给理由）" })
            if (invented > 0) append("；丢弃 $invented 条非原文片段")
        }
        return if (achieved && accepted.isNotEmpty()) {
            EvidenceOutcome(true, accepted, note)
        } else {
            EvidenceOutcome(false, accepted, note)
        }
    }

    /**
     * 功能：拼证据核对请求
     * 参数：spec 任务约束；snapshot 复验快照；texts 页面文本（已去重截断）
     * 返回：user 消息
     */
    private fun userPrompt(spec: TaskSpec, snapshot: ObservationSnapshot, texts: List<String>): String =
        buildString {
            appendLine("任务：${spec.inputText}")
            val boundary = spec.completionContract.successBoundary
            appendLine("完成边界：${boundary.ifEmpty { "（计划期没有写明，只能按任务本身判断）" }}")
            val fields = spec.completionContract.requiredFields
            if (fields.isNotEmpty()) {
                appendLine("计划期猜的必核字段（可能不准，仅作参考）：${fields.joinToString("、")}")
            }
            appendLine("页面标题：${snapshot.activityName}（包名 ${snapshot.packageName}）")
            appendLine("页面可见文本（只能从这里逐字摘抄）：")
            texts.forEach { appendLine("- $it") }
        }

    /**
     * 功能：发一次模型请求
     * 参数：model 已解析的模型；system 系统提示；user 用户消息
     * 返回：模型原始输出
     */
    private suspend fun call(
        model: com.neptine.mimir.data.config.ResolvedModel,
        system: String,
        user: String,
    ): String {
        val response = llm.chat(
            baseUrl = model.baseUrl,
            apiKey = model.apiKey,
            model = model.model,
            messages = listOf(
                ChatMessage(role = "system", content = system),
                ChatMessage(role = "user", content = user),
            ),
        )
        return response.choices.firstOrNull()?.message?.content.orEmpty()
    }

    /** 取字符串字段；缺失时为空串。 */
    private fun kotlinx.serialization.json.JsonObject.string(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull.orEmpty() }.getOrDefault("")

    private companion object {
        /** 一页最多看多少条文本；太多会稀释注意力，也浪费 token。 */
        const val MAX_TEXTS = 40

        /** 最多采纳几条证据。 */
        const val MAX_EVIDENCE = 3

        /** 证据核对的系统提示。 */
        val SYSTEM_PROMPT = """
你是只读证据核对员。你只做一件事：从给定页面文本里，找出能证明"任务已按要求完成"的原文片段。

输出 JSON，不要解释：
{"achieved":true|false,
 "evidence":["<逐字摘抄的页面原文片段>"],
 "reason":"<一句话说明为什么达成/未达成>"}

规则：
1. evidence 只能**逐字复制**页面文本里的片段，不得改写、翻译、概括或补充页面上没有的字。
   本地会用字符串包含关系逐条复核，编造的片段会被丢弃。
2. 判断依据是"完成边界"这一句，不是用户原话的字面翻译。
   用户说「关于手机」而页面写「我的设备 / OS版本 / Android 版本」时，这些页面原文就是达成证据。
3. 页面不足以证明完成（例如还停在上一级页面、结果为空、只有入口没有结果）时，
   achieved=false 且 evidence 为空数组。
4. 最多 3 条证据，优先选能唯一标识结果的那几条（标题、关键值、状态词）。
        """.trimIndent()
    }
}

/**
 * 一次证据采纳的结果。
 *
 * @property achieved 证据是否足以证明完成
 * @property fragments 已通过本地"原文包含"校验的片段
 * @property note 给审计看的一句话（含被丢弃的编造片段数）
 */
data class EvidenceOutcome(
    val achieved: Boolean,
    val fragments: List<String>,
    val note: String,
)
