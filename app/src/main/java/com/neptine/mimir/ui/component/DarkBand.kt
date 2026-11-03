package com.neptine.mimir.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirRadius
import com.neptine.mimir.ui.theme.MimirSpacing

/**
 * 功能：渲染一块深色区块（Cohere 的深绿/近黑产品带）
 * 参数：modifier 修饰符；container 底色；spacing 内容间距；content 内容
 * 返回：无
 *
 * 深色在本项目只以"区块"出现，不是整套深色主题——这是 Cohere 的做法，
 * 也让"白画布 + 一块深色"的对比成为页面的视觉重心。
 */
@Composable
fun DarkBand(
    modifier: Modifier = Modifier,
    container: Color = LocalMimirColors.current.deepGreen,
    spacing: androidx.compose.ui.unit.Dp = MimirSpacing.md,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MimirRadius.lg))
            .background(container)
            .padding(MimirSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}
