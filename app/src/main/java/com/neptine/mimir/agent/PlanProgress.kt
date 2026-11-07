package com.neptine.mimir.agent

import android.icu.text.Transliterator
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.PlanStep

/**
 * 计划步骤的后置条件断言（设计文档 §1.3.2）。
 *
 * 模型可以声明"这一步做完了"，但只有当前界面上真的出现了步骤的后置条件文本才允许推进游标。
 * 断言完全在本地完成，不额外调用模型：一次翻页就可能让上一帧的证据失效，用同一份快照复核
 * 等于让模型自证。
 */
object PlanProgress {

    /**
     * 功能：找出当前界面里已经出现的后置条件片段
     * 参数：step 计划步骤；snapshot 当前观察
     * 返回：命中的片段；没有命中或步骤没有后置条件时为空
     *
     * 比对前会去掉空白并忽略大小写：同一段文案在树上可能被拆成多个节点、带上换行或全角空格。
     */
    fun matchedFragments(step: PlanStep, snapshot: ObservationSnapshot): List<String> {
        if (step.postcondition.isEmpty()) return emptyList()
        val labels = snapshot.elements
            .flatMap { listOf(it.text, it.contentDescription) }
            .map(::normalize)
            .filter { it.isNotEmpty() }
        if (labels.isEmpty()) return emptyList()

        return step.postcondition.filter { fragment ->
            val needle = normalize(fragment)
            needle.isNotEmpty() && labels.any { it.contains(needle) }
        }
    }

    /**
     * 功能：规范化用于比对的文本
     * 参数：text 原始文本
     * 返回：只保留字母与数字并转小写
     */
    private fun normalize(text: String): String = normalizeForMatch(text)
}

/**
 * 功能：归一化用于文案比对
 * 参数：text 原始文本
 * 返回：简体化、只保留字母与数字并转小写
 *
 * 只保留字母数字可以同时消掉空白、全半角标点与问号/斜杠差异：
 * 「what is the capital of France?」与页面上的「what is the capital of france/」
 * 必须能对上，否则问答类任务会被标点差异判成未完成（真机实测 DeepSeek 就是这样）。
 *
 * 再做一次繁→简：微信这类 App 的标题会返回「文件传輸助手」（繁体「輸」），
 * 与任务契约里的「文件传输助手」逐字比对必然失败，真机上直接把发消息任务判成人工。
 * ICU 的转换是逐字的，不会改变语义，也不会把英文弄坏。
 */
internal fun normalizeForMatch(text: String): String {
    val simplified = threadTranslator.get()?.transliterate(text) ?: text
    return simplified.filter { it.isLetterOrDigit() }.lowercase()
}

/** 繁→简转换器；初次使用时构建，构建失败则退化为原文比对。 */
private val threadTranslator: ThreadLocal<Transliterator?> =
    object : ThreadLocal<Transliterator?>() {
        override fun initialValue(): Transliterator? =
            runCatching { Transliterator.getInstance("Traditional-Simplified") }.getOrNull()
    }
