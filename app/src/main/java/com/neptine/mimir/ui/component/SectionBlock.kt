package com.neptine.mimir.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing

/**
 * 功能：渲染一个页面分节（顶部分隔细线 + 节标题 + 内容）
 * 参数：title 节标题；modifier 修饰符；trailing 标题右侧操作；content 节内容
 * 返回：无
 *
 * 刻意不包卡片：Cohere 的分节靠一条线与留白完成，卡片会把页面切成一格一格的方块，
 * 反而丢掉编辑式的连续感。
 */
@Composable
fun SectionBlock(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MimirHairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MimirSpacing.md, bottom = MimirSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = LocalMimirColors.current.ink,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                trailing()
            }
        }
        content()
    }
}
