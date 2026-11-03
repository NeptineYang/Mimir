package com.neptine.mimir.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.neptine.mimir.ui.MimirTab
import com.neptine.mimir.ui.theme.LocalMimirColors

/**
 * 功能：渲染底部导航栏
 * 参数：selected 当前选中的一级页；onSelect 选择回调；modifier 修饰符
 * 返回：无
 *
 * 不用 Material3 的 NavigationBar：它固定 80dp 高、内含 32dp 的圆形激活指示器、
 * 且默认取 surfaceContainer 上色，三条都与"扁平、细线、激活下划线"冲突，
 * 覆盖成本高于自绘。这里的高度是 56dp，导航栏 inset 由自己承担。
 */
@Composable
fun MimirTabBar(
    selected: MimirTab,
    onSelect: (MimirTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LocalMimirColors.current.canvas)
            .navigationBarsPadding(),
    ) {
        MimirHairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MimirTab.entries.forEach { tab ->
                TabItem(
                    tab = tab,
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 功能：渲染单个导航项（编号 + 大写标签 + 激活下划线）
 * 参数：tab 导航项；selected 是否选中；onClick 点击回调；modifier 修饰符
 * 返回：无
 */
@Composable
private fun TabItem(
    tab: MimirTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimirColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val contentColor: Color = if (selected) colors.ink else colors.muted

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (pressed) colors.ink.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = tab.description },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "${tab.code} ${tab.label}",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
            )
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height(2.dp)
                    .background(if (selected) colors.ink else Color.Transparent),
            )
        }
    }
}
