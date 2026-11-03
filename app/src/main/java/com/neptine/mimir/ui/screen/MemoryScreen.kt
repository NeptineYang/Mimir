package com.neptine.mimir.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import com.neptine.mimir.ui.viewmodel.MemoryUiState
import com.neptine.mimir.ui.viewmodel.MemoryViewModel
import com.neptine.mimir.ui.viewmodel.PreferenceRow
import com.neptine.mimir.ui.viewmodel.ProcedureDraft
import com.neptine.mimir.ui.viewmodel.ProcedureRow

/**
 * 功能：记忆页——配方的白盒浏览与编辑、策略查看、JSON 导入导出
 * 参数：onBack 返回回调
 * 返回：无
 *
 * 放在独立全屏路由而不是第五个底栏项：底栏四项是"使用"的入口，
 * 记忆编辑是低频维护动作，只在需要时从设置页进来。
 */
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val viewModel: MemoryViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalMimirColors.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportTo) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importFrom) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MimirSpacing.xl),
    ) {
        MimirPageHeader(
            eyebrow = "05 MEMORY",
            title = "记忆",
            subtitle = "配方只在使用前自证有效；策略只影响规划，不进逐步决策",
            trailing = { TextActionButton(text = "返回", onClick = onBack) },
        )

        Notice(state, onDismiss = viewModel::dismissNotice)

        SectionBlock(
            title = "配方",
            trailing = { TextActionButton(text = "新建", onClick = viewModel::startCreate) },
        ) {
            if (state.procedures.isEmpty()) {
                EmptyState(
                    title = "还没有配方",
                    hint = "配方记录「入口在哪、步骤怎么走」，使用时先核对锚点；" +
                        "没有它 agent 只能靠探索。空的记忆表不会影响任务执行。",
                )
            } else {
                state.procedures.forEach { row ->
                    ProcedureListRow(
                        row = row,
                        verifying = row.id == state.verifyingId,
                        onEdit = { viewModel.startEdit(row.id) },
                        onToggle = { viewModel.toggleStatus(row.id) },
                        onReverify = { viewModel.reverify(row.id) },
                    )
                }
            }
        }

        SectionBlock(title = "策略") {
            if (state.preferences.isEmpty()) {
                EmptyState(
                    title = "还没有策略",
                    hint = "策略写的是「先做什么、别做什么」，例如「点外卖先领券」。" +
                        "它只在开局规划时注入 Planner。",
                )
            } else {
                state.preferences.forEach { row -> PreferenceListRow(row) }
            }
        }

        SectionBlock(title = "导入导出") {
            Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                TextActionButton(
                    text = "导出 JSON",
                    onClick = { exportLauncher.launch("mimir-memory.json") },
                )
                TextActionButton(
                    text = "导入 JSON",
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                )
            }
            Text(
                text = "导出内容只含配方与锚点，不含凭据——凭据库不属于记忆体系。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.slate,
                modifier = Modifier.padding(top = MimirSpacing.sm, bottom = MimirSpacing.xl),
            )
        }
    }

    state.draft?.let { draft ->
        ProcedureEditor(
            draft = draft,
            error = state.error,
            onChange = viewModel::updateDraft,
            onSave = viewModel::saveDraft,
            onCancel = viewModel::cancelDraft,
            onToggle = draft.id?.let { id -> { viewModel.toggleStatus(id); viewModel.cancelDraft() } },
            onDelete = draft.id?.let { id -> { viewModel.deleteProcedure(id); viewModel.cancelDraft() } },
        )
    }
}

/**
 * 功能：渲染一条提示条
 * 参数：state 页面状态；onDismiss 关闭回调
 * 返回：无
 */
@Composable
private fun Notice(state: MemoryUiState, onDismiss: () -> Unit) {
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
        TextActionButton(text = "关闭", onClick = onDismiss)
    }
}

/**
 * 功能：渲染一条配方
 * 参数：row 配方行；verifying 是否正在重验；onEdit 编辑回调；onToggle 启停回调；
 *       onReverify 立即重验回调
 * 返回：无
 *
 * 重验按钮在核对期间禁用：一次重验要建一块副屏，允许连点只会让第二次排队等第一次。
 * STALE 配方不给"启用"按钮：它只能靠重验通过恢复，不能靠点一下洗白。
 */
@Composable
private fun ProcedureListRow(
    row: ProcedureRow,
    verifying: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onReverify: () -> Unit,
) {
    val attempts = row.successCount + row.failCount
    MimirListRow(
        title = row.title,
        meta = "${row.packageName} · ${row.taskType}",
        note = "${row.status} · ${row.source} · 置信度 ${String.format(java.util.Locale.US, "%.2f", row.confidence)} · " +
            if (attempts == 0) "尚未验证" else "成功 ${row.successCount}/$attempts",
        onClick = onEdit,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                TextActionButton(
                    text = if (verifying) "核对中…" else "重验",
                    enabled = !verifying,
                    emphasis = true,
                    onClick = onReverify,
                )
                TextActionButton(
                    text = when (row.status) {
                        "ACTIVE" -> "停用"
                        "STALE" -> "待重验"
                        else -> "启用"
                    },
                    enabled = row.status != "STALE",
                    onClick = onToggle,
                )
            }
        },
    )
}

/**
 * 功能：渲染一条策略
 * 参数：row 策略行
 * 返回：无
 */
@Composable
private fun PreferenceListRow(row: PreferenceRow) {
    MimirListRow(
        title = row.title,
        meta = "${row.scope} · ${row.source}",
        note = row.content,
    )
}

/**
 * 功能：配方编辑弹窗
 * 参数：draft 草稿；error 错误提示；onChange 字段变更；onSave 保存；onCancel 取消；
 *       onToggle 启停（仅已有配方）；onDelete 删除（仅已有配方）
 * 返回：无
 *
 * 步骤用"每行一个动作"的纯文本：`TAP 领取`、`TEXT 搜索框`、`BACK`。
 * 只有 TAP / TEXT 需要文本，编辑时写成 A2 锚点；A1 的 resourceId 只有从真实控件树里才拿得到，
 * 手写页不做这种猜测。
 */
@Composable
private fun ProcedureEditor(
    draft: ProcedureDraft,
    error: String?,
    onChange: (ProcedureDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onToggle: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val colors = LocalMimirColors.current
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = if (draft.id == null) "新建配方" else "编辑配方") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            ) {
                if (onToggle != null && onDelete != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                        TextActionButton(text = "停用/启用", onClick = onToggle)
                        TextActionButton(text = "删除", onClick = onDelete, color = colors.error)
                    }
                }
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { onChange(draft.copy(title = it)) },
                    label = { Text("标题") },
                    placeholder = { Text("领取外卖红包") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.packageName,
                    onValueChange = { onChange(draft.copy(packageName = it)) },
                    label = { Text("包名") },
                    placeholder = { Text("com.sankuai.meituan") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.taskType,
                    onValueChange = { onChange(draft.copy(taskType = it)) },
                    label = { Text("任务类型（留空按 GENERAL）") },
                    placeholder = { Text("ORDER_FOOD") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.anchors,
                    onValueChange = { onChange(draft.copy(anchors = it)) },
                    label = { Text("入口前置锚点（每行一条，A2 文本）") },
                    placeholder = { Text("我的\n红包卡券") },
                )
                OutlinedTextField(
                    value = draft.steps,
                    onValueChange = { onChange(draft.copy(steps = it)) },
                    label = { Text("步骤（每行一个动作）") },
                    placeholder = { Text("TAP 我的\nTAP 红包卡券") },
                )
                OutlinedTextField(
                    value = draft.postcondition,
                    onValueChange = { onChange(draft.copy(postcondition = it)) },
                    label = { Text("后置条件（每行一条文本）") },
                )
                OutlinedTextField(
                    value = draft.signature,
                    onValueChange = { onChange(draft.copy(signature = it)) },
                    label = { Text("页面签名（每行一条静态文本，可留空）") },
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
        confirmButton = { TextActionButton(text = "保存", onClick = onSave) },
        dismissButton = { TextActionButton(text = "取消", onClick = onCancel) },
    )
}
