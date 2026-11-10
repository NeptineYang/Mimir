package com.neptine.mimir.ui.screen

import com.neptine.mimir.ui.i18n.t
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neptine.mimir.ui.component.EmptyState
import com.neptine.mimir.ui.component.MimirListRow
import com.neptine.mimir.ui.component.MimirPageHeader
import com.neptine.mimir.ui.component.SectionBlock
import com.neptine.mimir.ui.component.TextActionButton
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.viewmodel.CredentialRow
import com.neptine.mimir.ui.viewmodel.CredentialUiState
import com.neptine.mimir.ui.viewmodel.CredentialViewModel
import java.time.Instant
import java.time.ZoneId

/**
 * 功能：凭据页——保存、查看与删除普通登录密码
 * 参数：onBack 返回回调
 * 返回：无
 *
 * 密码只写进 Keystore 加密存储，列表与提示都不回显正文；凭据页也不参与记忆导出。
 */
@Composable
fun CredentialScreen() {
    val viewModel: CredentialViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalMimirColors.current
    var adding by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MimirSpacing.xl),
    ) {
        MimirPageHeader(
            title = "凭据".t(),
        )

        Notice(state, onDismiss = viewModel::clearNotice)

        SectionBlock(
            title = "登录密码".t(),
            trailing = { TextActionButton(text = "新增".t(), emphasis = true, onClick = { adding = true }) },
        ) {
            if (state.rows.isEmpty()) {
                EmptyState(
                    title = "还没有凭据".t(),
                    hint = "保存后，登录页上模型只能请求「使用哪条凭据」；密码由本地解密填充，".t() +
                        "不进入 prompt、审计或配方导出。".t(),
                )
            } else {
                state.rows.forEach { row ->
                    CredentialListRow(row = row, onDelete = { viewModel.delete(row.id) })
                }
            }
        }

        Text(
            text = "支付密码、短信验证码、银行卡、人脸/指纹与账号安全设置仍为 L2B，".t() +
                "只能由本人操作，任何凭据都不会用于这些步骤。".t(),
            style = MaterialTheme.typography.bodySmall,
            color = colors.slate,
            modifier = Modifier.padding(top = MimirSpacing.sm, bottom = MimirSpacing.section),
        )
    }

    if (adding) {
        CredentialEditor(
            onSave = { packageName, alias, secret ->
                viewModel.save(packageName, alias, secret)
                adding = false
            },
            onCancel = { adding = false },
        )
    }
}

/**
 * 功能：渲染一条提示
 * 参数：state 页面状态；onDismiss 关闭回调
 * 返回：无
 */
@Composable
private fun Notice(state: CredentialUiState, onDismiss: () -> Unit) {
    val colors = LocalMimirColors.current
    val text = state.error ?: state.message ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MimirSpacing.md),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.error != null) colors.error else colors.ink,
            modifier = Modifier.weight(1f),
        )
        TextActionButton(text = "关闭".t(), onClick = onDismiss)
    }
}

/**
 * 功能：渲染一条凭据
 * 参数：row 凭据行；onDelete 删除回调
 * 返回：无
 */
@Composable
private fun CredentialListRow(row: CredentialRow, onDelete: () -> Unit) {
    MimirListRow(
        title = row.accountAlias,
        meta = row.packageName,
        note = "普通登录密码 · 密码已加密存储 · 创建于 ${formatDate(row.createdAt)}",
        trailing = { TextActionButton(text = "删除".t(), onClick = onDelete) },
    )
}

/**
 * 功能：新增凭据对话框
 * 参数：onSave 保存回调（包名 / 账号别名 / 密码）；onCancel 取消回调
 * 返回：无
 */
@Composable
private fun CredentialEditor(
    onSave: (String, String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var packageName by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("保存登录密码".t()) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            ) {
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text("应用包名".t()) },
                    placeholder = { Text("com.example.app") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("账号别名".t()) },
                    placeholder = { Text("例如 user@example.com".t()) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("密码".t()) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Text(
                    text = "密码写入 Keystore 加密存储，不进入记忆、导出与模型上下文。".t(),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMimirColors.current.slate,
                )
            }
        },
        confirmButton = {
            TextActionButton(text = "保存".t(), emphasis = true, onClick = { onSave(packageName, alias, secret) })
        },
        dismissButton = { TextActionButton(text = "取消".t(), onClick = onCancel) },
    )
}

/**
 * 功能：把时间戳格式化成日期
 * 参数：millis 时间戳（毫秒）
 * 返回：`2026-11-02` 形式；无效时间为 `-`
 */
private fun formatDate(millis: Long): String =
    if (millis <= 0) {
        "-"
    } else {
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }
