package com.neptine.mimir.observation

import com.neptine.mimir.core.contract.UiElement

/**
 * 元素编号器（设计文档 §2.3）。
 *
 * 把界面元素编号成 #1..#N 再交给模型，模型只回答"操作 #7"，本地再映射回 bounds：
 * 这一步把"坐标漂移"从模型能力问题降级为本地查表问题。
 */
class ElementNumberer {

    /**
     * 功能：按优先级排序、截断并编号元素
     * 参数：raw 已按保留规则筛选过的候选元素（未编号）
     * 返回：编号后的元素列表，最多 MAX_ELEMENTS 个
     *
     * 优先级：可操作节点 > 有语义文本节点 > 其它。同一优先级内按屏上位置（上→下、左→右）排序，
     * 让编号在相邻两帧之间保持稳定；不能只按面积排——那会优先留下大容器、丢掉小按钮（§2.3）。
     */
    fun number(raw: List<UiElement>): List<UiElement> =
        raw.sortedWith(
            compareBy<UiElement> { priorityOf(it) }
                .thenBy { it.bounds.top }
                .thenBy { it.bounds.left },
        )
            .take(MAX_ELEMENTS)
            .mapIndexed { position, element -> element.copy(index = position + 1) }

    /**
     * 功能：给元素定优先级
     * 参数：element 候选元素
     * 返回：0 可操作 / 1 有语义文本 / 2 其它
     */
    private fun priorityOf(element: UiElement): Int = when {
        element.clickable || element.editable -> 0
        element.text.isNotEmpty() || element.contentDescription.isNotEmpty() -> 1
        else -> 2
    }

    companion object {
        /** 单帧编号上限；超出的元素只报"还有 N 个未列出"。 */
        const val MAX_ELEMENTS: Int = 60
    }
}
