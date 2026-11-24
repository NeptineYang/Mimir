package com.neptine.mimir.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.neptine.mimir.ui.AppEntry
import com.neptine.mimir.ui.appLabelOf
import com.neptine.mimir.ui.i18n.t
import com.neptine.mimir.ui.launchableApps
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing

/**
 * 功能：应用选择字段
 * 参数：packageName 当前绑定的应用 ID；onPick 选择回调；title 字段标题；
 *       allowEmpty 是否允许清空（例如偏好作用域）；emptyLabel 未绑定时的展示文本；
 *       modifier 布局修饰
 * 返回：无
 *
 * 界面只显示软件名，包名只在数据层流转；应用已卸载时回落显示包名，
 * 让"绑定为什么失效"有据可查。选择器单独弹一层，避免把几百个应用铺进编辑表单。
 */
@Composable
fun AppField(
    packageName: String,
    onPick: (String) -> Unit,
    title: String = "应用",
    allowEmpty: Boolean = false,
    emptyLabel: String = "未选择应用",
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = LocalMimirColors.current
    var picking by remember { mutableStateOf(false) }
    val fallback = emptyLabel.t()
    // 按包名缓存：编辑其它字段时每次重组都查 PackageManager 会把输入变成主线程 IO。
    val label = remember(packageName, fallback) {
        if (packageName.isBlank()) fallback else appLabelOf(context, packageName)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.t(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.slate,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
            )
        }
        if (allowEmpty && packageName.isNotBlank()) {
            TextActionButton(text = "清除".t(), onClick = { onPick("") })
        }
        TextActionButton(
            text = "选择".t(),
            emphasis = true,
            onClick = { picking = true },
        )
    }

    if (picking) {
        AppPickerDialog(
            selected = packageName,
            onPick = {
                onPick(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * 功能：应用选择弹窗
 * 参数：selected 当前选中的应用 ID；onPick 选中回调（回传包名）；onDismiss 取消回调
 * 返回：无
 *
 * 列表只展示软件名；搜索同时匹配显示名，用户不需要知道包名。
 */
@Composable
fun AppPickerDialog(
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalMimirColors.current
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<AppEntry>?>(initialValue = null) {
        value = launchableApps(context)
    }
    val filtered = remember(apps, query) {
        val list = apps.orEmpty()
        if (query.isBlank()) {
            list
        } else {
            list.filter { it.label.contains(query, ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "选择应用".t()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索应用".t()) },
                    singleLine = true,
                )
                when {
                    apps == null -> Text(
                        text = "读取应用列表…".t(),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.slate,
                    )
                    filtered.isEmpty() -> Text(
                        text = "没有匹配的应用".t(),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.slate,
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(filtered, key = { it.packageName }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(entry.packageName) }
                                    .padding(vertical = MimirSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = entry.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.ink,
                                    modifier = Modifier.weight(1f),
                                )
                                if (entry.packageName == selected) {
                                    Text(
                                        text = "当前".t(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.slate,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextActionButton(text = "取消".t(), onClick = onDismiss) },
    )
}
