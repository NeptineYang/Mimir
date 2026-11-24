package com.neptine.mimir.data.memory

import com.neptine.mimir.core.contract.UiElement

/**
 * 页面结构签名（v0.3.16，跨语言记忆匹配）。
 *
 * 页面文本签名是界面原文：目标 App 换语言后，中文配方与英文页面的交集为 0，
 * 检索会直接判成"页面变了"。结构签名只取语言无关的证据——资源 ID，
 * 或没有资源 ID 时的"控件类名 + 可点击/可编辑"——让同一页在换语言后仍能自证。
 *
 * 注意：不能用 `ObservationSnapshot.activityName` 做跨语言判据，它实际取的是
 * 窗口标题（`AccessibilityTreeProvider`），会随语言变化。
 */
object MemoryStructure {

    /** token 前缀：资源 ID。 */
    private const val ID_PREFIX = "id:"

    /** 单页最多保留多少 token，防止超长列表把签名撑爆。 */
    private const val TOKEN_LIMIT = 120

    /** 结构签名相似度阈值；低于它视为不同页面。 */
    const val MIN_SIMILARITY = 0.7

    /** 跨语言匹配至少要有几个相同的资源 ID，避免只靠控件类名撞页。 */
    const val MIN_SHARED_RESOURCE_IDS = 2

    /**
     * 功能：从一帧元素生成结构签名
     * 参数：elements 已编号的元素列表
     * 返回：排序后的 token 列表；保留重复次数，用于比较页面元素密度
     *
     * 有资源 ID 的元素只记 ID（语言无关且区分度最高）；没有 ID 的元素退回
     * "类名 + 可点击/可编辑"，否则 WebView / 自绘控件会让整页结构签名变空。
     */
    fun signature(elements: List<UiElement>): List<String> = elements
        .map { element ->
            val id = element.resourceId?.trim()?.lowercase().orEmpty()
            if (id.isNotEmpty()) {
                ID_PREFIX + id
            } else {
                "class:${element.className.lowercase()}|c:${if (element.clickable) 1 else 0}" +
                    "|e:${if (element.editable) 1 else 0}"
            }
        }
        .sorted()
        .take(TOKEN_LIMIT)

    /**
     * 功能：算两个结构签名的相似度（多重集 Jaccard）
     * 参数：expected 配方记录的结构签名；actual 当前页面的结构签名
     * 返回：0–1；任一侧为空时为 0
     *
     * 用计数而不是去重：同一页换语言后元素数量不变，计数能区分
     * "10 行设置项"和"3 行设置项"；只比集合会把两页判成一样。
     */
    fun similarity(expected: List<String>, actual: List<String>): Double {
        if (expected.isEmpty() || actual.isEmpty()) return 0.0
        val left = expected.groupingBy { it }.eachCount()
        val right = actual.groupingBy { it }.eachCount()
        val keys = left.keys + right.keys
        var overlap = 0
        var union = 0
        keys.forEach { key ->
            val a = left[key] ?: 0
            val b = right[key] ?: 0
            overlap += minOf(a, b)
            union += maxOf(a, b)
        }
        return if (union == 0) 0.0 else overlap.toDouble() / union
    }

    /**
     * 功能：数两侧共享的资源 ID 个数（去重）
     * 参数：expected 配方记录的结构签名；actual 当前页面的结构签名
     * 返回：共享的资源 ID 数量
     */
    fun sharedResourceIdCount(expected: List<String>, actual: List<String>): Int {
        val left = expected.filter { it.startsWith(ID_PREFIX) }.toSet()
        val right = actual.filter { it.startsWith(ID_PREFIX) }.toSet()
        return left.intersect(right).size
    }

    /**
     * 功能：判断两侧文本是否处于不同书写系统
     * 参数：expected 配方记录的页面文本；actual 当前页面的页面文本
     * 返回：一侧有汉字而另一侧没有、且另一侧有拉丁字母时为 true
     *
     * 只用来决定"要不要启用结构签名兜底"，不参与置信度；两侧同一语言时
     * 仍走原来的文本签名判定，避免结构相似但内容已变的页面被误放行。
     */
    fun languageMismatch(expected: List<String>, actual: List<String>): Boolean {
        val left = expected.joinToString(" ")
        val right = actual.joinToString(" ")
        if (left.isBlank() || right.isBlank()) return false
        val leftCjk = left.any(::isCjk)
        val rightCjk = right.any(::isCjk)
        val leftLatin = left.any(::isLatin)
        val rightLatin = right.any(::isLatin)
        return (leftCjk && !rightCjk && rightLatin) || (rightCjk && !leftCjk && leftLatin)
    }

    /**
     * 功能：判断字符是否为汉字
     * 参数：ch 字符
     * 返回：落在常用汉字或扩展 A 区为 true
     */
    private fun isCjk(ch: Char): Boolean =
        ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF

    /**
     * 功能：判断字符是否为拉丁字母
     * 参数：ch 字符
     * 返回：ASCII 字母为 true
     */
    private fun isLatin(ch: Char): Boolean = ch in 'a'..'z' || ch in 'A'..'Z'
}
