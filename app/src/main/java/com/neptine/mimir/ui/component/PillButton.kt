package com.neptine.mimir.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirRadius
import com.neptine.mimir.ui.theme.MimirSpacing

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
    Column(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) colors.onDark else colors.muted,
            modifier = Modifier
                .background(
                    color = if (enabled) colors.primary else colors.ink.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(MimirRadius.pill),
                )
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = MimirSpacing.xxl, vertical = 14.dp),
        )
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
 * 功能：渲染次级操作（下划线文字链接）
 * 参数：text 文案；onClick 点击回调；modifier 修饰符；enabled 是否可用；
 *       emphasis 是否用编辑型蓝；color 指定文字色，非空时优先于 emphasis
 * 返回：无
 *
 * color 主要用于破坏性动作（例如"移除"）——编辑型蓝是链接语义，
 * 用在删除上会让人误以为无害。
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
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            !enabled -> colors.muted
            color != null -> color
            emphasis -> colors.actionBlue
            else -> colors.ink
        },
        textDecoration = if (enabled) TextDecoration.Underline else TextDecoration.None,
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
    )
}
