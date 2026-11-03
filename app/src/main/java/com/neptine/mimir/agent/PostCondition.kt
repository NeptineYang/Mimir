package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.ActionKind
import com.neptine.mimir.core.contract.AgentAction
import com.neptine.mimir.core.contract.ObservationMode
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.UiElement
import com.neptine.mimir.observation.ElementDiff
import com.neptine.mimir.observation.TreeFrame
import javax.inject.Inject

/**
 * 动作后置条件判定（设计文档 §1.3 第 3 条 / §4.4）。
 *
 * 注入返回值与界面安静都只说明"可以读下一帧"，不说明动作生效；生效必须由动作特定的
 * 后置条件判定。判定不出时一律给 OUTCOME_UNKNOWN，不拿"页面变了"冒充成功。
 *
 * 两级证据：
 * 1. 一致观察（树 A → 截图 → 树 B）可用时，用它判结构/文本/前台变化；
 * 2. 动画页面（列表扫描、加载骨架）常常长时间无法形成一致观察，此时退到**单帧树**，
 *    只判"结构是否变化"。这类结论在审计里标记为弱证据；单帧看不到变化时给未知而不是未生效，
 *    因为页面可能仍在动。
 */
class PostCondition @Inject constructor() {

    /**
     * 功能：判定一个动作的效果
     * 参数：before 决策依据的观察；action 动作；after 安静后重新采集的一致观察；
     *       afterTree 一致观察不可用时的单帧树
     * 返回：CONFIRMED / NO_EFFECT / OUTCOME_UNKNOWN
     */
    fun assess(
        before: ObservationSnapshot,
        action: AgentAction.Do,
        after: ObservationSnapshot?,
        afterTree: TreeFrame? = null,
    ): EffectOutcome {
        if (after != null) return assessSnapshot(before, action, after)
        val tree = afterTree ?: return EffectOutcome.OUTCOME_UNKNOWN
        return assessTree(before, action, tree)
    }

    /**
     * 功能：用一致观察判定
     * 参数：before 动作前观察；action 动作；after 动作后观察
     * 返回：三态结论
     */
    private fun assessSnapshot(
        before: ObservationSnapshot,
        action: AgentAction.Do,
        after: ObservationSnapshot,
    ): EffectOutcome {
        // 纯视觉观察的元素表为空，任何"新增/消失"都只是降级噪声，不能当作动作证据
        if (before.mode == ObservationMode.VISION_ONLY || after.mode == ObservationMode.VISION_ONLY) {
            // OCR 补出了元素表时，点击可以照常按"元素是否变化"判定——
            // 这与树模式用的是同一套证据，只是元素来源不同。
            val ocrComparable = before.elements.isNotEmpty() && after.elements.isNotEmpty()
            return when (action.kind) {
                ActionKind.WAIT -> EffectOutcome.CONFIRMED
                ActionKind.LAUNCH -> launched(action, after.packageName)
                ActionKind.BACK, ActionKind.HOME -> navigated(before, after.activityName, after.packageName)
                ActionKind.TAP -> if (ocrComparable) {
                    tapEffect(before, action, after.elements, after.activityName, after.packageName)
                } else {
                    EffectOutcome.OUTCOME_UNKNOWN
                }
                // 视觉模式下刚键入的文字要靠 OCR 才能读到；漏掉这一支会让每次输入
                // 都被判成"结果未知"，任务在"打完字"这一步必挂人工。
                ActionKind.TEXT -> if (ocrComparable) {
                    textLanded(action, after.elements.map { it.text.ifEmpty { it.contentDescription } })
                } else {
                    EffectOutcome.OUTCOME_UNKNOWN
                }
                else -> EffectOutcome.OUTCOME_UNKNOWN
            }
        }
        return when (action.kind) {
            ActionKind.WAIT -> EffectOutcome.CONFIRMED
            ActionKind.LAUNCH -> launched(action, after.packageName)
            ActionKind.TEXT -> textLanded(action, after.elements.map { it.text.ifEmpty { it.contentDescription } })
            ActionKind.BACK, ActionKind.HOME -> navigated(before, after.activityName, after.packageName)
            ActionKind.CREDENTIAL -> credentialEffect(before.elements, action, after.elements)
            ActionKind.TAP -> tapEffect(before, action, after.elements, after.activityName, after.packageName)
            // 滑动到列表尽头时界面本来就不变，"没变化"不是"没生效"，因此只能给未知
            ActionKind.SWIPE -> if (ElementDiff.between(before.elements, after.elements).structuralChange) {
                EffectOutcome.CONFIRMED
            } else {
                EffectOutcome.OUTCOME_UNKNOWN
            }
        }
    }

    /**
     * 功能：用单帧树判定（弱证据）
     * 参数：before 动作前观察；action 动作；after 动作后的单帧树
     * 返回：三态结论
     *
     * 单帧无法证明"没变化就是没生效"：页面可能只是还在动，因此未观察到变化一律给未知。
     */
    private fun assessTree(
        before: ObservationSnapshot,
        action: AgentAction.Do,
        after: TreeFrame,
    ): EffectOutcome = when (action.kind) {
        ActionKind.WAIT -> EffectOutcome.CONFIRMED
        ActionKind.LAUNCH -> launched(action, after.packageName)
        ActionKind.TEXT -> textLanded(action, after.elements.map { it.text.ifEmpty { it.contentDescription } })
        ActionKind.BACK, ActionKind.HOME -> navigated(before, after.activityName, after.packageName)
        ActionKind.CREDENTIAL -> credentialEffect(before.elements, action, after.elements)
        ActionKind.TAP -> tapEffect(
            before,
            action,
            after.elements,
            after.activityName,
            after.packageName,
            weak = true,
        )
        ActionKind.SWIPE -> if (ElementDiff.between(before.elements, after.elements).structuralChange) {
            EffectOutcome.CONFIRMED
        } else {
            EffectOutcome.OUTCOME_UNKNOWN
        }
    }

    /**
     * 功能：判定点击是否改变了页面
     * 参数：before 动作前观察；action 动作；afterElements 动作后元素表；
     *       activityName / packageName 动作后的前台；weak 是否只有单帧树这类弱证据
     * 返回：三态结论
     *
     * 不再拿整树哈希定义成功：时钟、倒计时、轮播页码会让哈希每帧都变，把"什么都没发生"
     * 判成 CONFIRMED。改为元素级 diff——只有结构变化、目标消失或目标文案变化才算生效；
     * 弱证据下看不到变化时给未知，因为页面可能只是还在动（§1.3 第 3 条）。
     */
    private fun tapEffect(
        before: ObservationSnapshot,
        action: AgentAction.Do,
        afterElements: List<UiElement>,
        activityName: String,
        packageName: String,
        weak: Boolean = false,
    ): EffectOutcome {
        if (activityName != before.activityName || packageName != before.packageName) {
            return EffectOutcome.CONFIRMED
        }
        val diff = ElementDiff.between(before.elements, afterElements, action.targetFingerprint)
        val changed = diff.structuralChange || diff.targetGone || diff.targetChanged
        return when {
            changed -> EffectOutcome.CONFIRMED
            weak -> EffectOutcome.OUTCOME_UNKNOWN
            else -> EffectOutcome.NO_EFFECT
        }
    }

    /**
     * 功能：判定凭据填充是否落地
     * 参数：beforeElements 动作前元素表；action 凭据动作；afterElements 动作后元素表
     * 返回：CONFIRMED / OUTCOME_UNKNOWN；**永远不给 NO_EFFECT**
     *
     * 密码框通常只显示掩码，无法与明文比对；能确认的只有"目标还在、字段从空变非空或
     * 文本发生变化"。看不到证据时给未知并按未决写入转人工，而不是判"没生效"后重试——
     * §2.5.8 明确禁止凭据填充重试。
     */
    private fun credentialEffect(
        beforeElements: List<UiElement>,
        action: AgentAction.Do,
        afterElements: List<UiElement>,
    ): EffectOutcome {
        val fingerprint = action.targetFingerprint ?: return EffectOutcome.OUTCOME_UNKNOWN
        val beforeTarget = beforeElements.firstOrNull { ElementDiff.matches(fingerprint, it) }
            ?: return EffectOutcome.OUTCOME_UNKNOWN
        val afterTarget = afterElements.firstOrNull { ElementDiff.matches(fingerprint, it) }
            ?: return EffectOutcome.OUTCOME_UNKNOWN
        val beforeLabel = beforeTarget.text.ifEmpty { beforeTarget.contentDescription }
        val afterLabel = afterTarget.text.ifEmpty { afterTarget.contentDescription }
        return if (afterLabel.isNotEmpty() && afterLabel != beforeLabel) {
            EffectOutcome.CONFIRMED
        } else {
            EffectOutcome.OUTCOME_UNKNOWN
        }
    }

    /**
     * 功能：判定拉起应用是否到位
     * 参数：action 动作；packageName 当前前台包名
     * 返回：前台已是目标包名则为已生效
     */
    private fun launched(action: AgentAction.Do, packageName: String): EffectOutcome =
        if (packageName == action.text.orEmpty()) {
            EffectOutcome.CONFIRMED
        } else {
            EffectOutcome.NO_EFFECT
        }

    /**
     * 功能：判定文本是否落到界面上
     * 参数：action 动作；pageTexts 页面上的文本集合
     * 返回：页面文本包含正文则为已生效；否则结果未知
     *
     * 密码框与拨号框会把已输入文本遮蔽，页面上看不到正文并不等于没输入，
     * 因此不敢判 NO_EFFECT——那会让上层误以为可以安全重试一次输入。
     */
    private fun textLanded(action: AgentAction.Do, pageTexts: List<String>): EffectOutcome {
        val expected = action.text.orEmpty()
        if (expected.isEmpty()) return EffectOutcome.NO_EFFECT
        // 两边都归一化再比：输入框回读出来的文本与模型给的正文常在大小写、标点、
        // 空白上不一致（DeepSeek 实测：输入框显示 "what is the capital of france/"，
        // 正文是 "What is the capital of France?"），逐字比对必然判未知、任务卡在输入这一步。
        val needle = normalizeForCompare(expected)
        android.util.Log.d(
            "MimirText",
            "键入校验：needle=$needle 页面片段=${pageTexts.take(4).joinToString("|").take(120)}",
        )
        if (needle.isEmpty()) return EffectOutcome.OUTCOME_UNKNOWN
        return if (pageTexts.any { normalizeForCompare(it).contains(needle) }) {
            EffectOutcome.CONFIRMED
        } else {
            EffectOutcome.OUTCOME_UNKNOWN
        }
    }

    /**
     * 功能：归一化文本用于"键入是否落地"的比较
     * 参数：text 原始文本
     * 返回：只保留字母与数字并转小写
     */
    private fun normalizeForCompare(text: String): String =
        text.filter { it.isLetterOrDigit() }.lowercase()

    /**
     * 功能：判定返回 / Home 是否真的换了页面
     * 参数：before 动作前观察；activityName / packageName 动作后的前台
     * 返回：前台 Activity 或包名变化则为已生效
     *
     * 只比 Activity 与包名，不比树哈希：页面在同一个 Activity 内重绘时，
     * 树哈希会变，但那不代表返回键起了作用。
     */
    private fun navigated(
        before: ObservationSnapshot,
        activityName: String,
        packageName: String,
    ): EffectOutcome =
        if (activityName != before.activityName || packageName != before.packageName) {
            EffectOutcome.CONFIRMED
        } else {
            EffectOutcome.NO_EFFECT
        }
}

/** 动作效果三态（设计文档 §1.3 第 3 条）。 */
enum class EffectOutcome {
    /** 后置条件成立，有证据。 */
    CONFIRMED,

    /** 有证据表明没生效；是否允许重试由幂等性决定。 */
    NO_EFFECT,

    /** 证据不足；写操作按未决处理，禁止重放。 */
    OUTCOME_UNKNOWN,
}
