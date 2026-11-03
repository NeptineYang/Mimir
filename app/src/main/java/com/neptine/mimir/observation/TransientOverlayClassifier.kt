package com.neptine.mimir.observation

import com.neptine.mimir.core.contract.OverlayClassification
import com.neptine.mimir.core.contract.OverlayKind
import com.neptine.mimir.core.contract.OverlayPolicy
import com.neptine.mimir.core.contract.UiElement

/**
 * 一次性提示与广告分类（设计文档 §1.6.1）。
 *
 * 分类只看当前帧的文案与结构，不调模型：
 * - 先找"可安全关闭"的入口（跳过 / 关闭 / 以后再说…）；
 * - 再看页面是否短小且带营销线索，或带明显需要本人决定的按钮；
 * - 命中协议、权限、账号或更新线索一律判 BLOCKING，不允许借道广告逻辑自动处理。
 *
 * 刻意要求"小页面"：信息流里的一张广告卡片同样有「关闭」和营销词，
 * 若按整页文案分类，会把整个信息流当成遮挡，从而禁掉页面上的所有正常点击。
 */
object TransientOverlayClassifier {

    /** 遮挡页面的元素数上限；超过就按正常页面处理。 */
    const val MAX_OVERLAY_ELEMENTS = 16

    /**
     * 功能：分类当前帧
     * 参数：elements 已编号的候选元素
     * 返回：分类结果；不是遮挡时为 null
     */
    fun classify(elements: List<UiElement>): OverlayClassification? {
        if (elements.isEmpty() || elements.size > MAX_OVERLAY_ELEMENTS) return null

        val labels = elements.map { labelOf(it) }
        val pageText = labels.joinToString(" ")
        val strongClose = elements.firstOrNull { it.clickable && OverlayPolicy.isStrongDismiss(labelOf(it)) }
        val anyClose = strongClose
            ?: elements.firstOrNull { it.clickable && OverlayPolicy.isDismiss(labelOf(it)) }
        val acceptButton = elements.firstOrNull { it.clickable && OverlayPolicy.isRisky(labelOf(it)) }
        val rejectButton = elements.firstOrNull { it.clickable && labelOf(it) in REJECT_TEXTS }

        val marketing = OverlayPolicy.MARKETING_HINTS.any { pageText.contains(it) }
        val restricted = OverlayPolicy.RESTRICTED_HINTS.any { pageText.contains(it) }
        // 权限/协议弹窗必须同时有"接受"与"拒绝"两个按钮才算：普通设置页只有一个
        // 「允许通知」开关，把它当成权限弹窗会让任务在正常页面上直接挂起。
        val permissionDialog = acceptButton != null && rejectButton != null
        // 更新提示同样是弹窗结构：出现新版本文案且带可点的更新按钮。
        val updateDialog = acceptButton != null && labels.any { it.contains("新版本") || it.contains("版本更新") }
        val candidate = strongClose != null ||
            (marketing && anyClose != null) ||
            permissionDialog ||
            updateDialog
        if (!candidate) return null

        if (restricted) {
            return OverlayClassification(
                kind = OverlayKind.BLOCKING_OVERLAY,
                reason = "遮挡涉及协议/权限/账号/更新，需本人决定",
                closeIndex = anyClose?.index,
                closeLabel = anyClose?.let(::labelOf),
            )
        }
        if (anyClose == null) {
            return OverlayClassification(
                kind = OverlayKind.BLOCKING_OVERLAY,
                reason = "遮挡没有可用的安全关闭入口",
            )
        }
        return OverlayClassification(
            kind = OverlayKind.TRANSIENT_OVERLAY,
            reason = "一次性营销/活动遮挡",
            closeIndex = anyClose.index,
            closeLabel = labelOf(anyClose),
        )
    }

    /**
     * 功能：取元素文案
     * 参数：element 元素
     * 返回：文本；文本为空时退到无障碍描述
     */
    private fun labelOf(element: UiElement): String =
        element.text.ifEmpty { element.contentDescription }

    /** 明确的拒绝类按钮；与接受按钮同时出现才说明是权限/协议弹窗。 */
    private val REJECT_TEXTS = listOf("拒绝", "不允许", "不同意")
}
