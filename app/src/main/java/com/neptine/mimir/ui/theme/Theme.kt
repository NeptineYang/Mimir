package com.neptine.mimir.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * 功能：提供应用统一配色、排版与圆角
 * 参数：content 承载页面内容的可组合函数
 * 返回：无
 *
 * v1 只有浅色主题：Cohere 的深色是"深色带"（深绿/近黑的区块），不是整套 dark theme，
 * 我们照此实现——深色只出现在任务页状态带与接管页画面区。
 */
@Composable
fun MimirTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMimirColors provides LightMimirColors) {
        MaterialTheme(
            colorScheme = MimirLightColorScheme,
            typography = MimirTypography,
            shapes = MimirShapes,
            content = content,
        )
    }
}
