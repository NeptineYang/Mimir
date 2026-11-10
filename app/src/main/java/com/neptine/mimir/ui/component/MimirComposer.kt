package com.neptine.mimir.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirRadius
import com.neptine.mimir.ui.theme.MimirSpacing

/** 输入框最多显示的行数；超出后在框内滚动，避免把底部操作行顶出屏幕。 */
private const val COMPOSER_MAX_LINES = 5

/**
 * 功能：渲染一个"输入区 + 底部操作行"的输入组件（对话式 composer）
 * 参数：value 当前文本与光标位置；onValueChange 文本变化回调；placeholder 占位文案；
 *       modifier 修饰符；maxLength 最大字数；trailing 底部右侧的操作（语音、发送）
 * 返回：无
 *
 * 底部行左侧是字数计数，右侧由调用方决定放什么操作。
 * 边框只有一层：输入框自身不再描边，避免出现"框套框"。
 * 用 TextFieldValue 而不是 String：语音回填是外部写入，String 重载会保留旧光标位置，
 * 新识别出的字会从句子中间插进去。
 */
@Composable
fun MimirComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    maxLength: Int = 240,
    trailing: @Composable RowScope.() -> Unit,
) {
    val colors = LocalMimirColors.current
    val shape = RoundedCornerShape(MimirRadius.md)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(color = colors.surface, shape = shape)
            .border(width = 1.dp, color = colors.borderLight, shape = shape)
            .padding(MimirSpacing.lg),
    ) {
        BasicTextField(
            value = value,
            onValueChange = { if (it.text.length <= maxLength) onValueChange(it) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.ink),
            minLines = 3,
            // 上限 5 行：长句在框内滚动，底部的语音/发送按钮位置不动
            maxLines = COMPOSER_MAX_LINES,
            decorationBox = { innerTextField ->
                Box {
                    if (value.text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.muted,
                        )
                    }
                    innerTextField()
                }
            },
        )
        Spacer(modifier = Modifier.height(MimirSpacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoLabel(text = "${value.text.length} / $maxLength", color = colors.muted)
            Spacer(modifier = Modifier.weight(1f))
            trailing()
        }
    }
}
