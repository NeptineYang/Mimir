package com.neptine.mimir.ui.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 界面语言。
 *
 * 只影响 UI 文案，不影响模型 prompt、审计字段与日志：那些是证据与协议的一部分，
 * 翻译它们会让排障时对不上数据库。
 */
enum class UiLanguage(val tag: String) {
    ZH("zh"),
    EN("en"),
}

/**
 * 当前界面语言。用 Compose 全局状态而不是 CompositionLocal：
 * 页面文案、非 Composable 的辅助函数（进度文案、状态标签）都要读同一个值，
 * CompositionLocal 只能覆盖可组合作用域，反而要多写一套静态入口。
 */
object UiLanguageState {
    var current by mutableStateOf(UiLanguage.ZH)
}

/**
 * 功能：翻译一段界面文案（可组合作用域）
 * 参数：无（接收者即中文原文，同时作为词条键）；返回：当前语言下的文案
 *
 * 以中文原文作键而不是另起一套 key：
 * 词条与代码里的文案一一对应，漏翻时自动回落到中文，不会出现空白或 "missing_key"。
 * 代价是改中文措辞时英文会短暂回落，需要在 [EnglishText] 里同步替换。
 */
@Composable
fun String.t(): String {
    val language = UiLanguageState.current
    return if (language == UiLanguage.EN) EnglishText[this] ?: this else this
}

/**
 * 功能：翻译带占位符的文案（可组合作用域）
 * 参数：args 填充参数（模板为 %1$s / %2$d 形式）
 * 返回：当前语言下的成品文案
 */
@Composable
fun String.t(vararg args: Any): String {
    val language = UiLanguageState.current
    val template = if (language == UiLanguage.EN) EnglishText[this] ?: this else this
    return String.format(template, *args)
}

/**
 * 功能：翻译文案（非可组合作用域，例如 ViewModel 构造状态时）
 * 参数：无（接收者即中文原文）
 * 返回：当前语言下的文案
 *
 * 读的是同一个全局状态，但不建立快照订阅；语言切换后需要调用方主动刷新一次状态。
 */
fun String.tStatic(): String =
    if (UiLanguageState.current == UiLanguage.EN) EnglishText[this] ?: this else this

/** 功能：翻译带占位符的文案（非可组合作用域）；参数与返回同 [t]。 */
fun String.tStatic(vararg args: Any): String {
    val template = if (UiLanguageState.current == UiLanguage.EN) EnglishText[this] ?: this else this
    return String.format(template, *args)
}

/** 语言偏好的持久化；与引导标记分开存，互不影响。 */
object LanguagePrefs {

    private const val PREFS = "mimir_ui"
    private const val KEY_LANGUAGE = "language"

    /**
     * 功能：读取已保存的语言
     * 参数：context 任意上下文
     * 返回：语言枚举；没有记录或值非法时为中文
     */
    fun read(context: Context): UiLanguage {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
        return UiLanguage.entries.firstOrNull { it.tag == raw } ?: UiLanguage.ZH
    }

    /**
     * 功能：保存语言选择
     * 参数：context 任意上下文；language 目标语言
     * 返回：无
     */
    fun write(context: Context, language: UiLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language.tag)
            .apply()
    }
}
