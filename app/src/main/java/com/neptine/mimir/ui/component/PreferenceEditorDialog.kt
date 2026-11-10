package com.neptine.mimir.ui.component

import com.neptine.mimir.ui.i18n.t
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.neptine.mimir.core.contract.PreferenceCategory
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.viewmodel.PreferenceDraft

/**
 * 功能：偏好编辑弹窗
 * 参数：draft 草稿；error 错误提示；onChange 字段变更；onSave 保存；onCancel 取消；
 *       onDelete 删除（仅已有偏好；新建时传 null）
 * 返回：无
 *
 * 记忆页与历史页共用：前者维护长期偏好，后者把一次成功任务预填成"任务后确认"。
 * 类别用循环切换而不是下拉框：只有五类，点一下比展开菜单快。
 */
@Composable
fun PreferenceEditorDialog(
    draft: PreferenceDraft,
    error: String?,
    onChange: (PreferenceDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val colors = LocalMimirColors.current
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = if (draft.id == null) "新建偏好".t() else "编辑偏好".t()) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "类别".t(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.slate,
                        modifier = Modifier.padding(end = MimirSpacing.md),
                    )
                    TextActionButton(
                        text = draft.category.label(),
                        onClick = { onChange(draft.copy(category = draft.category.next())) },
                    )
                    Text(
                        text = "点一下切换".t(),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.slate,
                    )
                }
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { onChange(draft.copy(title = it)) },
                    label = { Text("标题".t()) },
                    placeholder = { Text("看视频优先用 B 站".t()) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.content,
                    onValueChange = { onChange(draft.copy(content = it)) },
                    label = { Text("偏好内容（注入 Planner 的自然语言）".t()) },
                    placeholder = { Text("看视频时优先打开 B 站，不要用抖音".t()) },
                )
                OutlinedTextField(
                    value = draft.value,
                    onValueChange = { onChange(draft.copy(value = it)) },
                    label = { Text("结构化值（留空时取内容）".t()) },
                    placeholder = { Text("bilibili") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.subject,
                    onValueChange = { onChange(draft.copy(subject = it)) },
                    label = { Text("主体 / 场景（地点、联系人、任务域，可留空）".t()) },
                    placeholder = { Text("公司 / 妈妈 / VIDEO".t()) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.scope,
                    onValueChange = { onChange(draft.copy(scope = it)) },
                    label = { Text("作用域包名（留空表示全局）".t()) },
                    placeholder = { Text("com.sankuai.meituan") },
                    singleLine = true,
                )
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                }
            }
        },
        confirmButton = { TextActionButton(text = "保存".t(), onClick = onSave) },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                onDelete?.let {
                    TextActionButton(text = "删除".t(), color = colors.error, onClick = it)
                }
                TextActionButton(text = "取消".t(), onClick = onCancel)
            }
        },
    )
}

/**
 * 功能：偏好类别的中文标签
 * 参数：无（扩展函数）
 * 返回：界面与 prompt 共用的短标签
 */
@Composable
fun PreferenceCategory.label(): String = when (this) {
    PreferenceCategory.PLATFORM -> "平台".t()
    PreferenceCategory.MERCHANT -> "常点".t()
    PreferenceCategory.TONE -> "语气".t()
    PreferenceCategory.ORDER_STYLE -> "点餐方式".t()
    PreferenceCategory.GENERAL -> "经验".t()
}

/**
 * 功能：在五类偏好之间循环
 * 参数：无（扩展函数）
 * 返回：下一个类别
 */
fun PreferenceCategory.next(): PreferenceCategory {
    val all = PreferenceCategory.entries
    return all[(all.indexOf(this) + 1) % all.size]
}
