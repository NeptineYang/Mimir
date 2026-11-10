package com.neptine.mimir.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.neptine.mimir.ui.i18n.t
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing

/**
 * 功能：渲染列表分页条（上一页 / 第 x / y 页 / 下一页）
 * 参数：page 当前页（从 0 开始）；pageCount 总页数；onPrev 上一页；onNext 下一页；modifier 修饰符
 * 返回：无
 *
 * 只有一页时不占版面：分页条本身也是信息，不该在不需要的时候出现。
 * 页码从 1 开始展示，内部仍用 0 基，避免调用方各自 +1/-1。
 */
@Composable
fun Pager(
    page: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = MimirSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(
            MimirSpacing.md,
            Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextActionButton(
            text = "上一页".t(),
            onClick = onPrev,
            enabled = page > 0,
        )
        MonoLabel(
            text = "第 %1\$d / %2\$d 页".t(page + 1, pageCount),
            color = LocalMimirColors.current.muted,
        )
        TextActionButton(
            text = "下一页".t(),
            onClick = onNext,
            enabled = page < pageCount - 1,
        )
    }
}
