package com.neptine.mimir.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing

/**
 * 功能：渲染一条"标题 + 元信息"的列表行，行尾带细线
 * 参数：title 行标题；modifier 修饰符；meta 等宽元信息；note 补充说明（如挂起原因）；
 *       noteColor 补充说明的颜色；leading 行首插槽；trailing 行尾插槽；
 *       onClick 整行点击回调，为空表示不可点
 * 返回：无
 *
 * 用细线分隔而不是卡片：Cohere 的列表页（研究、博客）就是这种"罗列 + 规则线"的形态，
 * 一屏能容纳更多条目，也避免每段都变成卡片。
 */
@Composable
fun MimirListRow(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    note: String? = null,
    noteColor: Color = LocalMimirColors.current.bodyMuted,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = MimirSpacing.lg),
            verticalAlignment = Alignment.Top,
        ) {
            if (leading != null) {
                Box(modifier = Modifier.padding(end = MimirSpacing.md)) { leading() }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalMimirColors.current.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = noteColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (meta != null) {
                    MonoLabel(text = meta, color = LocalMimirColors.current.muted, uppercase = false)
                }
            }
            if (trailing != null) {
                Box(modifier = Modifier.padding(start = MimirSpacing.md)) { trailing() }
            }
        }
        MimirHairline()
    }
}
