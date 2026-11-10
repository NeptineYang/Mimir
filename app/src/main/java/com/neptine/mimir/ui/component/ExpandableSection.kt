package com.neptine.mimir.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing

/**
 * 功能：渲染可折叠的二级分节（标题 + 一行摘要 + 折叠箭头，点开才展开内容）
 * 参数：title 节标题；modifier 修饰符；summary 收起时显示的一行摘要；
 *       initiallyExpanded 初始是否展开；content 展开后的内容
 * 返回：无
 *
 * 展开状态放在组件内部：调用方（设置页的各个分节）只关心"内容是什么"，
 * 不需要把六七组展开标记提升到页面层再传回来。
 *
 * 摘要行是关键：收起后仍然能读到"2 / 4 已就绪"这类结论，
 * 不能因为折叠就把状态一起藏起来，否则用户必须逐个点开才能确认配置。
 */
@Composable
fun ExpandableSection(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalMimirColors.current
    // 用 rememberSaveable 而不是 remember：设置页切成别的 tab 再回来时，
    // 用户刚展开的那一段不应该自己合上。
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "sectionArrow",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        MimirHairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = MimirSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.xxs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                )
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
            }
            Icon(
                imageVector = MimirIcons.ChevronDown,
                contentDescription = if (expanded) "收起 $title" else "展开 $title",
                tint = colors.slate,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(arrowRotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(220), expandFrom = Alignment.Top) +
                fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = tween(200), shrinkTowards = Alignment.Top) +
                fadeOut(animationSpec = tween(120)),
        ) {
            Column(
                modifier = Modifier.padding(bottom = MimirSpacing.xl),
                content = content,
            )
        }
    }
}
