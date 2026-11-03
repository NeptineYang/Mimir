package com.neptine.mimir.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.hairlineWidth

/**
 * 功能：渲染一条 1 物理像素的横向细线
 * 参数：modifier 修饰符；color 线色
 * 返回：无
 */
@Composable
fun MimirHairline(
    modifier: Modifier = Modifier,
    color: Color = LocalMimirColors.current.hairline,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(hairlineWidth())
            .background(color),
    )
}
