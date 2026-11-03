package com.neptine.mimir.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 功能：把 Cohere 语义色映射进 Material3 的槽位
 * 参数：无
 * 返回：无
 *
 * 两条刻意为之：
 * 1. surfaceTint 置透明。M3 的 Card/Surface 在 elevation > 0 时会把 primary 叠到表面上，
 *    而本设计"扁平、不用投影、靠 surface 交替分层"，染色会直接抵消这层意图。
 * 2. coral 与 action-blue 不在此处出现——它们留在 MimirColors，只有自定义组件能取到。
 */
internal val MimirLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF17171C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEEECE7),
    onPrimaryContainer = Color(0xFF212121),
    inversePrimary = Color(0xFFEDFCE9),

    secondary = Color(0xFF003C33),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDFCE9),
    onSecondaryContainer = Color(0xFF003C33),

    tertiary = Color(0xFF071829),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF1F5FF),
    onTertiaryContainer = Color(0xFF071829),

    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF212121),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF212121),

    surfaceVariant = Color(0xFFEEECE7),
    onSurfaceVariant = Color(0xFF75758A),
    surfaceTint = Color.Transparent,

    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFF2F2F2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF9F6),
    surfaceContainer = Color(0xFFEEECE7),
    surfaceContainerHigh = Color(0xFFE9E7E1),
    surfaceContainerHighest = Color(0xFFE3E1DA),

    inverseSurface = Color(0xFF17171C),
    inverseOnSurface = Color(0xFFFFFFFF),

    error = Color(0xFFB30000),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDECEA),
    onErrorContainer = Color(0xFFB30000),

    outline = Color(0xFFE5E7EB),
    outlineVariant = Color(0xFFD9D9DD),
    scrim = Color(0xFF000000),
)
