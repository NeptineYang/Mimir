package com.neptine.mimir.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.neptine.mimir.ui.theme.LocalMimirColors

/**
 * 功能：渲染一行等宽大写技术标签
 * 参数：text 标签文本；modifier 修饰符；color 文字色；uppercase 是否转大写
 * 返回：无
 *
 * 这类标签只承载 ASCII（MIMIR、ENGINE / OFFLINE、STEP 3）。中文不要走这里：
 * Space Mono 没有中文字形，加字距后中文会散架，中文标签请用 titleSmall。
 */
@Composable
fun MonoLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalMimirColors.current.slate,
    uppercase: Boolean = true,
) {
    Text(
        text = if (uppercase) text.uppercase() else text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
