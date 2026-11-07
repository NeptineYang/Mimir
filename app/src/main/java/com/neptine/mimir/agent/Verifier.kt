package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.Bounds
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.TaskSpec
import javax.inject.Inject

/**
 * 结果验证器（设计文档 §4.4.1）。
 *
 * 对所有模型后端执行同一套字段级断言，不接受 Executor 的 finish 自述。
 * "独立"指的是独立读取路径与独立证据快照，不声称同 App UI 等于服务端 API。
 *
 * 三态口径（v0.13）：
 * - `DONE`：初验与独立复验都通过；复验页还必须满足"每个必核字段恰好出现一次"。
 * - `COMPLETED_UNVERIFIED`：初验通过，但独立复验不可达（没有注册的复验路线或复验中途失败）。
 * - `MISMATCH`：初验或复验任一字段不符；也包含"完成契约没有可核对文本"这种情况——
 *   连初验都做不了，就不能说任务已完成，交给人工判断。
 */
class Verifier @Inject constructor() {

    /**
     * 功能：核对一路证据的必核字段
     * 参数：spec 任务约束；snapshot 证据快照；label 这一路的名称（初验 / 复验）；
     *       assertSingle 是否要求每个字段恰好出现一次
     * 返回：核对结论；passed=false 时 reason 写明为什么不能算通过
     */
    fun check(
        spec: TaskSpec,
        snapshot: ObservationSnapshot,
        label: String,
        assertSingle: Boolean = false,
    ): FieldCheck {
        val evidence = "$label：${snapshot.packageName}/${snapshot.activityName}" +
            "（${snapshot.elements.size} 个元素，观察 ${snapshot.observationId.take(8)}）"
        val fields = spec.completionContract.requiredFields
        if (fields.isEmpty()) {
            return FieldCheck(false, evidence, "完成契约没有必核字段，无法做字段级核对")
        }

        val pageText = snapshot.elements.joinToString(" ") {
            it.text.ifEmpty { it.contentDescription }
        }
        if (isUnverifiableContract(fields, pageText)) {
            return FieldCheck(
                false,
                evidence,
                "完成契约只给了抽象字段名（${fields.joinToString("、")}），页面上没有可核对的文本",
            )
        }

        val haystack = normalize(pageText)
        val missing = missingFields(spec, haystack)
        if (missing.isNotEmpty()) {
            return FieldCheck(false, evidence, "$label 未通过：${missing.joinToString("；")}")
        }

        if (assertSingle) {
            val duplicated = fields.filter { field ->
                countLeafMatches(snapshot, expectedOf(spec, field)) > 1
            }
            if (duplicated.isNotEmpty()) {
                return FieldCheck(
                    false,
                    evidence,
                    "「$label」中 ${duplicated.joinToString("、")} 出现多次，不满足“结果恰好出现一次”",
                )
            }
        }
        return FieldCheck(true, evidence, null)
    }

    /**
     * 功能：把初验与复验合成三态结论
     * 参数：spec 任务约束；primary 初验快照；secondary 独立复验快照；
     *       secondaryUnreachable 复验不可达的具体原因
     * 返回：验证结论与两路证据
     *
     * 复验不可达不判失败：任务可能真的做完了，只是没有安全的只读入口。
     * 初验不通过则判 MISMATCH，由上层转人工，而不是继续复验或假装完成。
     */
    fun verify(
        spec: TaskSpec,
        primary: ObservationSnapshot,
        secondary: ObservationSnapshot?,
        secondaryUnreachable: String? = null,
        adopted: EvidenceOutcome? = null,
        readback: ObservationSnapshot? = null,
        viewTask: Boolean = false,
        readbackPackage: String? = null,
    ): VerificationResult {
        // 查看类任务的完成物就是"目标页面本身"：字段文案是计划期盲猜的，
        // 猜"cats"这种抽象词必然初验失败。核对应换成"目标 App 仍停在副屏前台"。
        val first = if (viewTask) checkForeground(spec, primary, "初验") else check(spec, primary, "初验")
        if (!first.passed) {
            // 两段式契约：初验的字段是计划期盲猜的（还没见过目标页），"字段不符"不等于"没做成"。
            // 若复验页可达且 reviewer 从该页原文里摘到了能证明完成边界的片段，
            // 就以这些片段为准——它们是被本地逐字校验过的页面原文，不是模型的自由判断。
            if (secondary != null && adopted != null && adopted.achieved) {
                val adoptedCheck = checkFragments(secondary, adopted.fragments, "复验")
                if (adoptedCheck.passed) {
                    return VerificationResult(
                        outcome = VerificationOutcome.DONE,
                        evidence = listOf(first.evidence, adoptedCheck.evidence),
                        note = "证据采纳：初验字段（${spec.completionContract.requiredFields.joinToString("、")}）" +
                            "与页面文案不一致，改用复验页原文：${adopted.note}",
                    )
                }
            }
            return VerificationResult(
                outcome = VerificationOutcome.MISMATCH,
                evidence = listOf(first.evidence),
                unreachableReason = first.reason,
            )
        }

        if (secondary == null) {
            // 没有注册路线的 App：退一步做"同页重读"——隔一段时间重新观察同一页，
            // 用一份**新的**快照再核对一遍。它挡不住缓存页，但能挡住单帧幻读与模型自述，
            // 比"什么都不验"强，也比"永远到不了成功终态"更有用。
            //
            // 查看类任务例外：视频/直播页一直在动，永远等不到"一致观察"，用整帧快照
            // 会把成功判成未复验（真机 B 站实测）。这类任务的重读只回答"目标 App 还在不在前台"，
            // 前台包名就足够，而且不需要一帧安静的页面。
            val again = when {
                viewTask && readbackPackage != null ->
                    checkForegroundPackage(spec, readbackPackage, "重读")
                readback != null && readback.observationId != primary.observationId ->
                    if (viewTask) checkForeground(spec, readback, "重读") else check(spec, readback, "重读")
                else -> null
            }
            if (again?.passed == true) {
                return VerificationResult(
                    outcome = VerificationOutcome.DONE_READBACK,
                    evidence = listOf(first.evidence, again.evidence),
                    note = "没有注册复验路线，改用同页二次读取复核",
                )
            }
            val reason = buildString {
                append("已完成；该应用没有独立的复验入口，未做二次核对")
                secondaryUnreachable?.takeIf { it.isNotBlank() }?.let { append("。技术原因：$it") }
            }
            return VerificationResult(
                outcome = VerificationOutcome.COMPLETED_UNVERIFIED,
                evidence = listOf(first.evidence),
                unreachableReason = reason,
            )
        }

        val second = check(
            spec = spec,
            snapshot = secondary,
            label = "复验",
            assertSingle = spec.completionContract.requireSingleResult,
        )
        if (!second.passed) {
            if (adopted != null && adopted.achieved) {
                val adoptedCheck = checkFragments(secondary, adopted.fragments, "复验")
                if (adoptedCheck.passed) {
                    return VerificationResult(
                        outcome = VerificationOutcome.DONE,
                        evidence = listOf(first.evidence, adoptedCheck.evidence),
                        note = "证据采纳：复验字段不符（${second.reason}），改用复验页原文：${adopted.note}",
                    )
                }
            }
            return VerificationResult(
                outcome = VerificationOutcome.MISMATCH,
                evidence = listOf(first.evidence, second.evidence),
                unreachableReason = second.reason,
            )
        }
        return VerificationResult(
            outcome = VerificationOutcome.DONE,
            evidence = listOf(first.evidence, second.evidence),
        )
    }

    /**
     * 功能：核对"采纳的证据片段"是否真的出现在页面上
     * 参数：snapshot 复验快照；fragments 已由 reviewer 摘抄、本地待复核的片段；label 这一路的名称
     * 返回：核对结论
     *
     * 这一步是证据采纳的闸门：reviewer 只负责"挑"，能不能算数由这里逐字判定。
     */
    /**
     * 功能：查看类任务的核对——目标 App 仍停留在副屏前台
     * 参数：spec 任务约束；snapshot 观察；label 阶段名
     * 返回：核对结果
     *
     * 这类任务的交付物是页面本身（DeepSeek 的回答页、B 站的视频页），
     * 字段级核对只会因为"计划期猜的词太抽象"而误判；包名核对既诚实又可自动判定。
     */
    private fun checkForeground(spec: TaskSpec, snapshot: ObservationSnapshot, label: String): FieldCheck {
        val evidence = "$label：${snapshot.packageName}/${snapshot.activityName}" +
            "（${snapshot.elements.size} 个元素，观察 ${snapshot.observationId.take(8)}）"
        return foregroundCheck(spec, snapshot.packageName, label, evidence)
    }

    /**
     * 功能：只按前台包名核对"目标 App 还在不在前台"
     * 参数：spec 任务约束；packageName 当前前台包名；label 这一路的名称
     * 返回：字段级核对结果
     *
     * 查看类任务的重读用它：页面在播视频时拿不到一致观察，但前台包名随时可读。
     */
    private fun checkForegroundPackage(spec: TaskSpec, packageName: String, label: String): FieldCheck =
        foregroundCheck(spec, packageName, label, "$label：前台 $packageName")

    /**
     * 功能：前台包名与任务目标的比对
     * 参数：spec 任务约束；packageName 当前前台包名；label 名称；evidence 证据串
     * 返回：字段级核对结果
     */
    private fun foregroundCheck(
        spec: TaskSpec,
        packageName: String,
        label: String,
        evidence: String,
    ): FieldCheck {
        val hit = packageName == spec.packageName
        return if (hit) {
            FieldCheck(true, evidence, null)
        } else {
            FieldCheck(
                false,
                evidence,
                "$label 未通过：目标 App 已不在副屏前台（当前 $packageName），结果页无法交付",
            )
        }
    }

    private fun checkFragments(
        snapshot: ObservationSnapshot,
        fragments: List<String>,
        label: String,
    ): FieldCheck {
        val evidence = "$label：${snapshot.packageName}/${snapshot.activityName}" +
            "（${snapshot.elements.size} 个元素，观察 ${snapshot.observationId.take(8)}）"
        if (fragments.isEmpty()) return FieldCheck(false, evidence, "没有可采纳的证据片段")
        val haystack = normalize(
            snapshot.elements.joinToString(" ") { it.text.ifEmpty { it.contentDescription } },
        )
        val missing = fragments.filter { !haystack.contains(normalize(it)) }
        return if (missing.isEmpty()) {
            FieldCheck(true, evidence, null)
        } else {
            FieldCheck(false, evidence, "$label 采纳的证据不在页面上：${missing.joinToString("；")}")
        }
    }

    /**
     * 功能：核对完成契约里的必填字段
     * 参数：spec 任务约束；haystack 已归一化的页面文本
     * 返回：缺失项说明列表；空列表表示全部命中
     *
     * 比对前统一去掉空白并忽略大小写：模型给的字段名与页面文案常差在空格与全半角上，
     * 逐字比对会把这类差异判成"未完成"。有参数值时先按参数值断言；参数值缺失时退回字段名本身。
     */
    private fun missingFields(spec: TaskSpec, haystack: String): List<String> =
        spec.completionContract.requiredFields.mapNotNull { field ->
            val expected = expectedOf(spec, field)
            if (haystack.contains(expected)) null else "$field（期望包含「$expected」）"
        }

    /**
     * 功能：取某个必核字段在页面上期望出现的文本
     * 参数：spec 任务约束；field 字段名
     * 返回：归一化后的期望文本；参数值缺失时用字段名
     */
    private fun expectedOf(spec: TaskSpec, field: String): String =
        normalize(spec.parameters[field]?.takeIf { it.isNotBlank() } ?: field)

    /**
     * 功能：统计页面上真正"独立出现"的匹配元素个数
     * 参数：snapshot 证据快照；expected 已归一化的期望文本
     * 返回：叶子匹配元素个数
     *
     * 树里可点击容器会聚合子控件文本，同一个标签会同时出现在容器和子 TextView 上。
     * 直接数文本出现次数会把一次正常展示算成两次，所以只数"不是其它匹配元素祖先"的叶子；
     * 两条独立结果各带同一个值（例如两个订单都含同一商品）仍然会被数成 2，断言才有意义。
     */
    private fun countLeafMatches(snapshot: ObservationSnapshot, expected: String): Int {
        if (expected.isEmpty()) return 0
        val matches = snapshot.elements.filter { element ->
            normalize(element.text.ifEmpty { element.contentDescription }).contains(expected)
        }
        return matches.count { candidate ->
            matches.none { other -> containsBounds(candidate.bounds, other.bounds) }
        }
    }

    /**
     * 功能：判断一个矩形是否严格包含另一个矩形
     * 参数：outer 外层矩形；inner 内层矩形
     * 返回：外层比内层大且完全包住内层时为 true
     */
    private fun containsBounds(outer: Bounds, inner: Bounds): Boolean {
        val bigger = outer.width > inner.width || outer.height > inner.height
        return bigger &&
            outer.left <= inner.left && outer.top <= inner.top &&
            outer.right >= inner.right && outer.bottom >= inner.bottom
    }

    /**
     * 功能：把文本归一化成可比较的形式
     * 参数：text 原始文本
     * 返回：只保留字母与数字并转小写的文本
     */
    private fun normalize(text: String): String = normalizeForMatch(text)

    /**
     * 功能：判断完成契约是否根本没有可核对的文本
     * 参数：fields 完成契约的必填字段；pageText 初验页面的全部文本
     * 返回：无法用页面文本核对时为 true
     *
     * 两种情形：
     * 1. 字段本身是标识符（如 currentScreen、orderStatus）——模型在描述"要核对什么"，
     *    不是界面上的文字；
     * 2. 字段是英文而页面完全没有拉丁字母（英文任务 + 中文界面）——不是没做完，
     *    是语言对不上，任何比对都不可能成功。
     */
    private fun isUnverifiableContract(fields: List<String>, pageText: String): Boolean {
        if (fields.isEmpty()) return false
        val fieldsAsciiOnly = fields.all { field -> field.none { it.code > ASCII_LIMIT } }
        if (!fieldsAsciiOnly) return false
        val identifierLike = fields.all { field -> field.isNotEmpty() && field.none { it.isWhitespace() } }
        val pageHasLetters = pageText.any { it.code <= ASCII_LIMIT && it.isLetter() }
        return identifierLike || !pageHasLetters
    }

    private companion object {
        /** ASCII 上界；超过它的字符视为中文等可见文本，不属于"抽象字段名"。 */
        const val ASCII_LIMIT = 127
    }
}

/**
 * 一路证据的字段核对结论。
 *
 * @property passed 这一路是否通过
 * @property evidence 证据摘要，进审计
 * @property reason 未通过的原因；通过时为 null
 */
data class FieldCheck(
    val passed: Boolean,
    val evidence: String,
    val reason: String?,
)

/**
 * 验证结果。
 *
 * @property outcome 三态结论
 * @property evidence 证据摘要
 * @property unreachableReason 未达成或复验不可达的原因
 */
data class VerificationResult(
    val outcome: VerificationOutcome,
    val evidence: List<String>,
    val unreachableReason: String? = null,
    /** 额外说明：证据采纳路径会在这里写清"为什么换了判据"；其它路径为空。 */
    val note: String? = null,
)

/** 验证结论；COMPLETED_UNVERIFIED 不计入严格成功率。 */
enum class VerificationOutcome {
    /** 两条不同路径各读一次，都通过。 */
    DONE,

    /** 没有注册复验路线时，同一页面隔一段时间再读一次，两次都通过。 */
    DONE_READBACK,

    COMPLETED_UNVERIFIED,
    MISMATCH,
}
