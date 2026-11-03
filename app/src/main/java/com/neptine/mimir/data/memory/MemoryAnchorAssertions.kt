package com.neptine.mimir.data.memory

import com.neptine.mimir.core.contract.Anchor
import com.neptine.mimir.core.contract.AnchorLevel
import com.neptine.mimir.core.contract.UiElement

/**
 * 配方锚点断言（设计文档 §2.5.6 第 3 步）。
 *
 * 检索与立即重验共用同一份判据：两处若各写一份匹配规则，就会出现"检索放行、
 * 重验判死"的分裂结论。只断言 A1–A3：A4 依赖相对几何、A5 是绝对坐标，
 * 都要求比"当前一帧"更多的信息。
 */
object MemoryAnchorAssertions {

    /**
     * 断言结果。
     *
     * @property passed 是否全部命中
     * @property detail 给审计看的说明
     */
    data class Check(val passed: Boolean, val detail: String)

    /**
     * 功能：断言一组前置锚点
     * 参数：anchors 锚点；elements 当前帧元素
     * 返回：是否全部命中与说明
     *
     * 一条可断言锚点都没有时按通过处理，但说明里必须写清楚——
     * 不能让审计误以为核对过。
     */
    fun assert(anchors: List<Anchor>, elements: List<UiElement>): Check {
        val checkable = anchors.filter {
            it.level == AnchorLevel.A1 || it.level == AnchorLevel.A2 || it.level == AnchorLevel.A3
        }
        if (checkable.isEmpty()) {
            return Check(true, "前置锚点无可断言项（${anchors.size} 条为 A4/A5）")
        }
        val matched = checkable.count { anchor -> elements.any { matches(anchor, it) } }
        return Check(matched == checkable.size, "前置锚点 $matched/${checkable.size} 命中")
    }

    /**
     * 功能：判断单个元素是否命中锚点
     * 参数：anchor 锚点；element 当前帧元素
     * 返回：命中为 true
     */
    private fun matches(anchor: Anchor, element: UiElement): Boolean {
        val label = normalize(element.text.ifEmpty { element.contentDescription })
        return when (anchor.level) {
            AnchorLevel.A1 ->
                !anchor.resourceId.isNullOrEmpty() && anchor.resourceId == element.resourceId
            AnchorLevel.A2 -> {
                val expected = normalize(anchor.text ?: anchor.contentDescription ?: return false)
                expected.isNotEmpty() && label == expected
            }
            AnchorLevel.A3 -> {
                val expected = normalize(anchor.text ?: anchor.contentDescription ?: return false)
                expected.isNotEmpty() && label.contains(expected) &&
                    (anchor.className.isNullOrEmpty() || anchor.className == element.className)
            }
            AnchorLevel.A4, AnchorLevel.A5 -> false
        }
    }

    /**
     * 功能：规范化用于比较的文本
     * 参数：text 原始文本
     * 返回：去掉全部空白并转小写
     */
    private fun normalize(text: String): String = text.filterNot { it.isWhitespace() }.lowercase()
}
