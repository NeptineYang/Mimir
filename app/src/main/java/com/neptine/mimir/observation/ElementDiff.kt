package com.neptine.mimir.observation

import com.neptine.mimir.core.contract.TargetFingerprint
import com.neptine.mimir.core.contract.UiElement

/**
 * 元素级 diff（设计文档 §2.2 观测层 / §1.3 第 3 条）。
 *
 * 两帧之间"页面到底变没变"不能只看整树哈希：时钟、倒计时、轮播页码每帧都在变文本，
 * 整树哈希会把它们当成页面变化，从而把一次没生效的点击误判为 CONFIRMED。
 * 这里按"元素身份"配对，把变化拆成新增、消失、位移与文案变化四类：
 * - 身份优先用 viewId；没有 viewId 时退到"类名 + 位置 + 可操作性"，**刻意不含文本**，
 *   这样文案变化才会落在 labelChanged 而不是被当成旧元素消失 + 新元素出现；
 * - 目标元素是否消失 / 文案是否变化单独标出，供后置条件判断"点的是不是同一个东西"。
 *
 * @property added 只在后一帧出现的元素
 * @property removed 只在前一帧出现的元素
 * @property moved 同一身份但位置偏移超过容差的元素
 * @property labelChanged 同一身份但文案变化的元素
 * @property targetGone 动作目标在前一帧存在、后一帧消失
 * @property targetChanged 动作目标的文案发生变化
 * @property unchanged 身份、位置与文案都没变的元素数
 */
data class ElementDiff(
    val added: List<UiElement>,
    val removed: List<UiElement>,
    val moved: List<UiElement>,
    val labelChanged: List<UiElement>,
    val targetGone: Boolean,
    val targetChanged: Boolean,
    val unchanged: Int,
) {
    /** 结构性变化：新增、消失或位移。文案变化不计入，避免把倒计时当成页面切换。 */
    val structuralChange: Boolean
        get() = added.isNotEmpty() || removed.isNotEmpty() || moved.isNotEmpty()

    /** 任意变化，含目标文案变化。 */
    val anyChange: Boolean
        get() = structuralChange || labelChanged.isNotEmpty() || targetGone || targetChanged

    /**
     * 功能：生成一行紧凑摘要
     * 参数：无
     * 返回：`+a/-r/~m/l` 形式，供审计证据使用
     */
    fun summary(): String = "+${added.size}/-${removed.size}/~${moved.size}/l${labelChanged.size}"

    companion object {
        /** 位置偏移容差：阴影、字重与状态栏高度变化会让同一元素偏移几个像素。 */
        const val MOVE_TOLERANCE_PX = 24

        /**
         * 功能：比较两帧元素表
         * 参数：before 动作前元素表；after 动作后元素表；target 动作目标指纹（可空）
         * 返回：元素级差异
         */
        fun between(
            before: List<UiElement>,
            after: List<UiElement>,
            target: TargetFingerprint? = null,
        ): ElementDiff {
            val usedBefore = BooleanArray(before.size)
            val added = mutableListOf<UiElement>()
            val moved = mutableListOf<UiElement>()
            val labelChanged = mutableListOf<UiElement>()
            var unchanged = 0
            var targetChanged = false

            for (element in after) {
                val beforeIndex = before.indices.firstOrNull { index ->
                    !usedBefore[index] && identityOf(before[index]) == identityOf(element)
                }
                if (beforeIndex == null) {
                    added += element
                    continue
                }
                usedBefore[beforeIndex] = true
                val previous = before[beforeIndex]
                if (distanceOf(previous, element) > MOVE_TOLERANCE_PX) {
                    moved += element
                } else if (labelOf(previous) != labelOf(element)) {
                    labelChanged += element
                    if (target != null && matches(target, previous)) targetChanged = true
                } else {
                    unchanged++
                }
            }

            val removed = before.indices.filter { !usedBefore[it] }.map { before[it] }
            val targetGone = target != null && before.any { matches(target, it) } && after.none { matches(target, it) }

            return ElementDiff(
                added = added,
                removed = removed,
                moved = moved,
                labelChanged = labelChanged,
                targetGone = targetGone,
                targetChanged = targetChanged,
                unchanged = unchanged,
            )
        }

        /**
         * 功能：计算元素身份
         * 参数：element 元素
         * 返回：有 viewId 用 viewId；否则用类名 + 位置 + 可操作性（不含文本）
         */
        private fun identityOf(element: UiElement): String {
            val resourceId = element.resourceId
            if (!resourceId.isNullOrEmpty()) return "id:$resourceId"
            val bounds = element.bounds
            return "box:${element.className}|${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}|" +
                "${element.clickable}|${element.editable}"
        }

        /**
         * 功能：取元素文案
         * 参数：element 元素
         * 返回：文本；文本为空时退到无障碍描述
         */
        private fun labelOf(element: UiElement): String =
            element.text.ifEmpty { element.contentDescription }

        /**
         * 功能：计算两个元素中心点的距离
         * 参数：before 前一帧元素；after 后一帧元素
         * 返回：像素距离
         */
        private fun distanceOf(before: UiElement, after: UiElement): Double {
            val dx = before.bounds.center.x - after.bounds.center.x
            val dy = before.bounds.center.y - after.bounds.center.y
            return kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
        }

        /**
         * 功能：判断元素是否就是指纹指向的目标
         * 参数：fingerprint 目标指纹；element 元素
         * 返回：同类同标识为 true
         */
        fun matches(fingerprint: TargetFingerprint, element: UiElement): Boolean {
            if (fingerprint.className != null && fingerprint.className != element.className) return false
            if (!fingerprint.resourceId.isNullOrEmpty()) return fingerprint.resourceId == element.resourceId
            val label = fingerprint.text ?: fingerprint.contentDescription
            if (!label.isNullOrEmpty()) {
                return element.text == label || element.contentDescription == label
            }
            // 无文本的输入框（密码框常见）只能按位置兜底；没有 bounds 就无法定位
            val before = fingerprint.bounds ?: return false
            val dx = before.center.x - element.bounds.center.x
            val dy = before.center.y - element.bounds.center.y
            return dx * dx + dy * dy <= MOVE_TOLERANCE_PX * MOVE_TOLERANCE_PX
        }
    }
}
