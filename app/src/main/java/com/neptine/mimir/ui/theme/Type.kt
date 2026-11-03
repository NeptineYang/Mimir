package com.neptine.mimir.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.neptine.mimir.R

/**
 * 是否为正文与标题启用打包字体。
 *
 * 打包的 Inter 只有拉丁字形，中文必然回落到系统字体。若真机上发现"Inter 的英文 + 系统中文字"
 * 对比过大（中文明显偏粗偏大），把这里置 false：正文与标题退回系统字体，只保留
 * SpaceGrotesk（大标题）与 SpaceMono（技术标签）两族，是最小割裂的退路。
 */
private const val UseBundledText = true

/**
 * 显示族：Space Grotesk。只用于纯拉丁与数字（品牌名、编号、NO SIGNAL 一类），
 * 含中文的标题一律走 InterText，否则中英字面差异会很明显。
 */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
)

/** 正文族：Inter。缺字时由系统 fallback 补中文。 */
val InterText: FontFamily = if (UseBundledText) {
    FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
    )
} else {
    FontFamily.Default
}

/** 技术标签族：Space Mono。只承载全大写 ASCII，中文标签不使用它。 */
val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
)

/**
 * 功能：提供 15 个 Material3 排版槽位
 * 参数：无
 * 返回：无
 *
 * 桌面设计稿的 96/72/60px 在手机上无法直接使用，按"首页一个超大标题、其余收敛"压到 44/36/30sp，
 * 字距按 -0.02em 的比例同步缩放。500 是全局最重字重，不打包 600/700——
 * 层次靠字号与留白，而不是靠加粗。
 */
val MimirTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 44.sp,
        lineHeight = 44.sp,
        letterSpacing = (-1.0).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.8).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = InterText,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.28).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = InterText,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.24).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = InterText,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = InterText,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = InterText,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = InterText,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = InterText,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterText,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = InterText,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = InterText,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = SpaceMono,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.28.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = SpaceMono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.22.sp,
    ),
)

/** 排版槽位之外的专用样式；只放无法用槽位表达的场景。 */
object MimirType {

    /**
     * 数字样式：等宽数字 + Medium 字重。
     * 数值（步数、token、时间戳）单独成 Text，避免与中文混排后宽度随字数跳动，
     * 也避免整串混排退化成"英文 Inter、中文系统字体"的两套字面。
     */
    val Numeric = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    )

    /** 品牌标识：大写、宽字距、极小字号，用于页眉左侧与页脚。 */
    val Brand = TextStyle(
        fontFamily = SpaceMono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 1.6.sp,
    )
}
