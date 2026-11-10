package com.neptine.mimir.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirRadius
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.theme.hairlineWidth

/**
 * 功能：渲染主操作按钮（近黑胶囊）
 * 参数：text 按钮文案；onClick 点击回调；modifier 修饰符；enabled 是否可用；
 *       disabledHint 不可用时的原因说明，紧贴按钮下方显示
 * 返回：无
 *
 * 禁用态不隐藏原因：本项目的多数按钮暂时不可用，是因为对应能力仍是 TODO。
 * 把原因写在旁边，比让按钮静默灰着更有用。
 */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledHint: String? = null,
) {
    val colors = LocalMimirColors.current
    val shape = RoundedCornerShape(MimirRadius.pill)
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .clip(shape)
                .background(if (enabled) colors.primary else colors.ink.copy(alpha = 0.08f))
                .clickable(enabled = enabled, onClick = onClick)
                .heightIn(min = 44.dp)
                .padding(horizontal = MimirSpacing.xxl, vertical = MimirSpacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) colors.onDark else colors.muted,
            )
        }
        if (!enabled && disabledHint != null) {
            Text(
                text = disabledHint,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(top = MimirSpacing.sm),
            )
        }
    }
}

/**
 * 功能：渲染次级操作按钮（描边胶囊）
 * 参数：text 文案；onClick 点击回调；modifier 修饰符；enabled 是否可用；
 *       emphasis 是否用主色填充；color 指定强调色，非空时优先于 emphasis
 * 返回：无
 *
 * 替代原来的"下划线文字链接"：下划线在长列表与深色带上都太弱，
 * 用户读不出"这里可以点"。现在统一为 1 物理像素描边的胶囊，
 * 与主按钮同一套圆角与高度，视觉重量靠"填充 vs 描边"区分，而不是靠有没有下划线。
 *
 * color 主要用于破坏性动作：描边与文字同色，不填充，避免删除按钮比主操作还抢眼。
 */
@Composable
fun TextActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasis: Boolean = false,
    color: Color? = null,
) {
    val colors = LocalMimirColors.current
    val shape = RoundedCornerShape(MimirRadius.pill)
    val accent = color ?: colors.ink
    val contentColor = when {
        !enabled -> colors.muted
        emphasis && color == null -> colors.onDark
        else -> accent
    }
    val container = when {
        !enabled -> Color.Transparent
        emphasis && color == null -> colors.primary
        else -> Color.Transparent
    }
    val outline = when {
        !enabled -> colors.borderLight
        emphasis && color == null -> Color.Transparent
        else -> accent.copy(alpha = 0.30f)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(container)
            .border(width = hairlineWidth(), color = outline, shape = shape)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
        )
    }
}
