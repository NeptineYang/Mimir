package com.neptine.mimir.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing

/**
 * 功能：渲染一行"字段名 — 字段值"
 * 参数：key 字段名；value 字段值；modifier 修饰符；mono 值是否用等宽字体；
 *       valueColor 值颜色
 * 返回：无
 *
 * 值左对齐而不是右对齐：审计与配置里的值经常很长（包名、base_url、模型输出），
 * 右对齐会在换行后产生参差的锯齿。
 */
@Composable
fun KeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    valueColor: Color = LocalMimirColors.current.ink,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MimirSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMimirColors.current.slate,
            modifier = Modifier.weight(0.38f),
        )
        Text(
            text = value,
            style = if (mono) {
                MaterialTheme.typography.labelMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = valueColor,
            modifier = Modifier
                .weight(0.62f)
                .padding(start = MimirSpacing.sm),
        )
    }
}
