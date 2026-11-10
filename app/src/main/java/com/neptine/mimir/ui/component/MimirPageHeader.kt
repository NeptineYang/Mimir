package com.neptine.mimir.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.neptine.mimir.ui.LocalOpenDrawer
import com.neptine.mimir.ui.MimirMarkButton
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing

/**
 * 功能：渲染模块页眉（居中标题 + 操作区）
 * 参数：title 主标题；modifier 修饰符；trailing 标题下方的操作区
 * 返回：无
 *
 * 只保留标题：副标题属于"解释信息"，模块顶部只需要回答"这是哪一页"；
 * 说明应当写进内容区或空态里，而不是长期占着页眉。
 *
 * 标题在「M 标记右侧的剩余宽度」里居中，而不是整屏居中——M 占了左上角，
 * 若按整屏居中会在视觉上压到 M 上。
 *
 * M 标记画在页眉内部而不是应用浮层上：浮层会随滚动一直悬在内容上方挡住列表。
 * 颗粒质感不在页眉里画，它属于应用根层，从屏幕物理边缘开始（见 MimirApp）。
 */
@Composable
fun MimirPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LocalMimirColors.current
    val openDrawer = LocalOpenDrawer.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = MimirSpacing.xs),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            MimirMarkButton(onClick = openDrawer)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = colors.ink,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (trailing != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MimirSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = trailing,
            )
        }
        Spacer(modifier = Modifier.height(MimirSpacing.lg))
    }
}
