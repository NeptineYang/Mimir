package com.neptine.mimir.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing

/**
 * 功能：渲染编辑式页面标题（技术标签 + 主标题 + 副标题 + 右侧操作）
 * 参数：eyebrow 顶部等宽标签；title 主标题；subtitle 一句话说明；modifier 修饰符；trailing 右侧操作
 * 返回：无
 *
 * 主标题走 headlineLarge 而不是 display*：标题含中文，display 族的 Space Grotesk 没有中文字形，
 * 混排后字面会明显不一致。
 */
@Composable
fun MimirPageHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = MimirSpacing.sm, bottom = MimirSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoLabel(text = eyebrow, color = LocalMimirColors.current.slate)
            if (trailing != null) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = LocalMimirColors.current.ink,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMimirColors.current.slate,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MimirHairline(modifier = Modifier.padding(top = MimirSpacing.md))
    }
}
