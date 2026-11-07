package com.neptine.mimir.ui.voice

import android.content.Context
import android.content.Intent

/** 通用任务词：已装 App 名只覆盖「名词」，动作类高频词在这里补一小份。 */
private val TASK_WORDS = listOf("外卖", "购物车", "下单", "结算", "搜索", "播放", "发送", "导航", "评价")

/** 只用纯中文标签：模型建模单元是 cjkchar，含字母或符号的词喂进去不会生效。 */
private val CHINESE_ONLY = Regex("^[\\u4e00-\\u9fa5]{2,6}$")

/**
 * 功能：用已装 App 名生成语音识别热词表
 * 参数：context 应用上下文；limit 词条上限（sherpa-onnx 建议不超过 100）
 * 返回：每行一个词的字符串；没有可用词时返回空串
 *
 * 热词只会提升「本来犹豫」的整词命中率，给太多反而误伤，因此默认 60 条。
 * 只有 transducer + modified_beam_search 支持热词，换模型或改解码方法后会静默失效。
 * 接口侧会把 "/" 也当分隔符（online-recognizer-transducer-impl.h），中文标签里不会出现，按行传即可。
 */
fun buildVoiceHotwords(context: Context, limit: Int = 60): String {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val appNames = pm.queryIntentActivities(launcher, 0)
        .asSequence()
        .mapNotNull { it.loadLabel(pm)?.toString()?.trim() }
        .filter { CHINESE_ONLY.matches(it) }
        .distinct()
        .take((limit - TASK_WORDS.size).coerceAtLeast(0))
        .toList()
    return (appNames + TASK_WORDS).distinct().take(limit).joinToString("\n")
}
