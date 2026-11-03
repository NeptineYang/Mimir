package com.neptine.mimir.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neptine.mimir.core.contract.RiskLevel
import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.ui.theme.MimirRadius
import com.neptine.mimir.ui.theme.hairlineWidth
import com.neptine.mimir.ui.theme.riskColorOf
import com.neptine.mimir.ui.theme.statusColorOf

/**
 * 功能：渲染一个小号标记（细描边 + 极浅底 + 等宽大写文字）
 * 参数：text 标记文本；fg 强调色；modifier 修饰符；bg 底色；border 描边色
 * 返回：无
 *
 * 底色与描边默认从强调色降透明度派生，因此不需要第二套调色板，
 * 也不会出现"新状态找不到对应底色"的问题。
 */
@Composable
fun StatusChip(
    text: String,
    fg: Color,
    modifier: Modifier = Modifier,
    bg: Color = fg.copy(alpha = 0.08f),
    border: Color = fg.copy(alpha = 0.24f),
) {
    val shape = RoundedCornerShape(MimirRadius.xs)
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(color = bg, shape = shape)
            .border(width = hairlineWidth(), color = border, shape = shape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** 功能：渲染任务状态标记；参数：state 任务状态；modifier 修饰符；返回：无。 */
@Composable
fun TaskStateChip(state: TaskState, modifier: Modifier = Modifier) {
    StatusChip(text = state.name, fg = statusColorOf(state), modifier = modifier)
}

/** 功能：渲染风险等级标记；参数：level 风险等级；modifier 修饰符；返回：无。 */
@Composable
fun RiskChip(level: RiskLevel, modifier: Modifier = Modifier) {
    StatusChip(text = level.name, fg = riskColorOf(level), modifier = modifier)
}
