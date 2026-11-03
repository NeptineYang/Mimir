package com.neptine.mimir.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing

/**
 * 功能：渲染空态（一句结论 + 一句原因 + 可选操作）
 * 参数：title 结论；hint 原因说明；modifier 修饰符；action 可选操作
 * 返回：无
 *
 * 空态必须写清"为什么是空的"。本项目绝大多数列表为空的原因是执行链路还没实现，
 * 这跟"用户还没创建过"是两件事，混为一谈会让人误判系统已经在跑。
 */
@Composable
fun EmptyState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MimirSpacing.xxxl),
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = LocalMimirColors.current.ink,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMimirColors.current.slate,
        )
        if (action != null) {
            Column(modifier = Modifier.padding(top = MimirSpacing.sm)) { action() }
        }
    }
}
