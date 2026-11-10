package com.neptine.mimir.ui.screen

import com.neptine.mimir.ui.i18n.t
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neptine.mimir.core.contract.ActionKind
import com.neptine.mimir.core.contract.Anchor
import com.neptine.mimir.core.contract.AnchorLevel
import com.neptine.mimir.core.contract.PreferenceCategory
import com.neptine.mimir.core.contract.ProcedureAction
import com.neptine.mimir.core.contract.ProcedurePage
import com.neptine.mimir.ui.component.EmptyState
import com.neptine.mimir.ui.component.MimirHairline
import com.neptine.mimir.ui.component.MimirListRow
import com.neptine.mimir.ui.component.MimirPageHeader
import com.neptine.mimir.ui.component.PreferenceEditorDialog
import com.neptine.mimir.ui.component.SectionBlock
import com.neptine.mimir.ui.component.TextActionButton
import com.neptine.mimir.ui.component.label
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.viewmodel.MemoryUiState
import com.neptine.mimir.ui.viewmodel.MemoryViewModel
import com.neptine.mimir.ui.viewmodel.PreferenceRow
import com.neptine.mimir.ui.viewmodel.PreferenceDraft
import com.neptine.mimir.ui.viewmodel.ProcedureDraft
import com.neptine.mimir.ui.viewmodel.ProcedureRow
import java.util.UUID

/**
 * 功能：记忆页——配方的白盒浏览与编辑、策略查看、JSON 导入导出
 * 参数：onBack 返回回调
 * 返回：无
 *
 * 放在独立全屏路由而不是第五个底栏项：底栏四项是"使用"的入口，
 * 记忆编辑是低频维护动作，只在需要时从设置页进来。
 */
@Composable
fun MemoryScreen() {
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
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MimirSpacing.xl),
    ) {
        MimirPageHeader(
            title = "记忆".t(),
        )

        Notice(state, onDismiss = viewModel::dismissNotice)

        SectionBlock(
            title = "配方".t(),
            trailing = { TextActionButton(text = "新增".t(), emphasis = true, onClick = viewModel::startCreate) },
        ) {
            if (state.procedures.isEmpty()) {
                EmptyState(
                    title = "还没有配方".t(),
                    hint = "配方记录「入口在哪、步骤怎么走」，使用时先核对锚点；".t() +
                        "没有它 agent 只能靠探索。空的记忆表不会影响任务执行。".t(),
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

        SectionBlock(
            title = "偏好与策略".t(),
            trailing = {
                TextActionButton(
                    text = "新增".t(),
                    emphasis = true,
                    onClick = viewModel::startCreatePreference,
                )
            },
        ) {
            if (state.preferences.isEmpty()) {
                EmptyState(
                    title = "还没有偏好".t(),
                    hint = "偏好写的是「用户更喜欢怎么做」：常用平台、常点店、点餐方式、发消息的语气。".t() +
                        "它只在开局规划时注入 Planner，不会直接指挥每一步。".t(),
                )
            } else {
                state.preferences.forEach { row ->
                    PreferenceListRow(
                        row = row,
                        onEdit = { viewModel.startEditPreference(row.id) },
                        onToggle = { viewModel.togglePreference(row.id) },
                    )
                }
            }
        }

        SectionBlock(title = "导入导出".t()) {
            Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                TextActionButton(
                    text = "导出 JSON".t(),
                    onClick = { exportLauncher.launch("mimir-memory.json") },
                )
                TextActionButton(
                    text = "导入 JSON".t(),
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                )
            }
            Text(
                text = "导出内容只含配方与锚点，不含凭据——凭据库不属于记忆体系。".t(),
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

    state.preferenceDraft?.let { draft ->
        PreferenceEditorDialog(
            draft = draft,
            error = state.error,
            onChange = viewModel::updatePreferenceDraft,
            onSave = viewModel::savePreferenceDraft,
            onCancel = viewModel::cancelPreferenceDraft,
            onDelete = draft.id?.let { id ->
                { viewModel.deletePreference(id); viewModel.cancelPreferenceDraft() }
            },
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
        TextActionButton(text = "关闭".t(), onClick = onDismiss)
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
        note = "${row.status} · ${row.source} · ${row.pageCount} 页 / ${row.actionCount} 动作 · " +
            "置信度 ${String.format(java.util.Locale.US, "%.2f", row.confidence)} · " +
            if (attempts == 0) "尚未验证".t() else "成功 ${row.successCount}/$attempts",
        onClick = onEdit,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                TextActionButton(
                    text = if (verifying) "核对中…".t() else "重验".t(),
                    enabled = !verifying,
                    emphasis = true,
                    onClick = onReverify,
                )
                TextActionButton(
                    text = when (row.status) {
                        "ACTIVE" -> "停用".t()
                        "STALE" -> "待重验".t()
                        else -> "启用".t()
                    },
                    enabled = row.status != "STALE",
                    onClick = onToggle,
                )
            }
        },
    )
}

/**
 * 功能：渲染一条偏好
 * 参数：row 偏好行；onEdit 打开编辑；onToggle 切换启停
 * 返回：无
 */
@Composable
private fun PreferenceListRow(
    row: PreferenceRow,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
) {
    val colors = LocalMimirColors.current
    MimirListRow(
        title = row.title,
        meta = "${row.category.label()} · ${row.scope} · ${row.source} · " +
            "置信度 ${String.format(java.util.Locale.US, "%.2f", row.confidence)} · ${row.status}",
        note = row.content,
        onClick = onEdit,
        trailing = {
            TextActionButton(
                text = if (row.status == "ACTIVE") "停用".t() else "启用".t(),
                color = if (row.status == "ACTIVE") colors.ink else colors.slate,
                onClick = onToggle,
            )
        },
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
        title = { Text(text = if (draft.id == null) "新建配方".t() else "编辑配方".t()) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            ) {
                if (onToggle != null && onDelete != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                        TextActionButton(text = "停用/启用".t(), onClick = onToggle)
                        TextActionButton(text = "删除".t(), onClick = onDelete, color = colors.error)
                    }
                }
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { onChange(draft.copy(title = it)) },
                    label = { Text("标题".t()) },
                    placeholder = { Text("领取外卖红包".t()) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.packageName,
                    onValueChange = { onChange(draft.copy(packageName = it)) },
                    label = { Text("包名".t()) },
                    placeholder = { Text("com.sankuai.meituan") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.taskType,
                    onValueChange = { onChange(draft.copy(taskType = it)) },
                    label = { Text("任务类型（留空按 GENERAL）".t()) },
                    placeholder = { Text("ORDER_FOOD") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.anchors,
                    onValueChange = { onChange(draft.copy(anchors = it)) },
                    label = { Text("入口前置锚点（每行一条，A2 文本）".t()) },
                    placeholder = { Text("我的\n红包卡券".t()) },
                )
                Text(
                    text = "配方树：App → 任务 → 页面 → 动作".t(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.slate,
                )
                draft.pages.forEachIndexed { pageIndex, page ->
                    ProcedurePageEditor(
                        index = pageIndex,
                        page = page,
                        onChange = { updated -> onChange(draft.replacePage(updated)) },
                        onDelete = {
                            onChange(draft.copy(pages = draft.pages.filterNot { it.id == page.id }))
                        },
                    )
                }
                TextActionButton(
                    text = "+ 页面".t(),
                    onClick = { onChange(draft.copy(pages = draft.pages + newPageDraft())) },
                )
                OutlinedTextField(
                    value = draft.postcondition,
                    onValueChange = { onChange(draft.copy(postcondition = it)) },
                    label = { Text("后置条件（每行一条文本）".t()) },
                )
                OutlinedTextField(
                    value = draft.signature,
                    onValueChange = { onChange(draft.copy(signature = it)) },
                    label = { Text("页面签名（每行一条静态文本，可留空）".t()) },
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
        confirmButton = { TextActionButton(text = "保存".t(), onClick = onSave, emphasis = true) },
        dismissButton = { TextActionButton(text = "取消".t(), onClick = onCancel) },
    )
}

/**
 * 功能：渲染一个页面节点及其动作
 * 参数：index 页面序号；page 页面；onChange 页面内容变更；onDelete 删除页面
 * 返回：无
 *
 * 页面名可直接改；动作类型在 TAP / TEXT / BACK / HOME 之间循环——
 * 这四种覆盖绝大多数界面操作，也不给 WAIT 这类"编辑了也没有执行意义"的选项。
 */
@Composable
private fun ProcedurePageEditor(
    index: Int,
    page: ProcedurePage,
    onChange: (ProcedurePage) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalMimirColors.current
    Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "P${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.slate,
                modifier = Modifier.padding(end = MimirSpacing.sm),
            )
            OutlinedTextField(
                value = page.title,
                onValueChange = { onChange(page.copy(title = it)) },
                label = { Text("页面名".t()) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextActionButton(text = "删页".t(), onClick = onDelete, color = colors.error)
        }
        page.actions.forEachIndexed { actionIndex, action ->
            Column(modifier = Modifier.padding(start = MimirSpacing.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${index + 1}.${actionIndex + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.slate,
                        modifier = Modifier.padding(end = MimirSpacing.sm),
                    )
                    TextActionButton(
                        text = action.actionKind.name,
                        onClick = {
                            onChange(
                                page.replaceAction(
                                    action.id,
                                    action.copy(actionKind = action.actionKind.next()),
                                ),
                            )
                        },
                    )
                    OutlinedTextField(
                        value = action.title,
                        onValueChange = { text ->
                            onChange(page.replaceAction(action.id, action.copy(title = text)))
                        },
                        label = { Text("动作名".t()) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextActionButton(
                        text = "删".t(),
                        color = colors.error,
                        onClick = {
                            onChange(page.copy(actions = page.actions.filterNot { it.id == action.id }))
                        },
                    )
                }
                OutlinedTextField(
                    value = action.anchor?.text ?: action.anchor?.resourceId.orEmpty(),
                    onValueChange = { text ->
                        onChange(
                            page.replaceAction(
                                action.id,
                                action.copy(anchor = mergeAnchor(action.anchor, text)),
                            ),
                        )
                    },
                    label = { Text("锚点文本（A2；留空表示没有可断言目标）".t()) },
                    singleLine = true,
                )
            }
        }
        TextActionButton(
            text = "+ 动作".t(),
            onClick = { onChange(page.copy(actions = page.actions + newActionDraft())) },
        )
        MimirHairline()
    }
}

/**
 * 功能：替换草稿里的一个页面
 * 参数：page 新页面内容
 * 返回：替换后的草稿
 */
private fun ProcedureDraft.replacePage(page: ProcedurePage): ProcedureDraft =
    copy(pages = pages.map { if (it.id == page.id) page else it })

/**
 * 功能：替换页面里的一个动作
 * 参数：actionId 动作标识；action 新动作内容
 * 返回：替换后的页面
 */
private fun ProcedurePage.replaceAction(actionId: String, action: ProcedureAction): ProcedurePage =
    copy(actions = actions.map { if (it.id == actionId) action else it })

/**
 * 功能：新建一个空页面节点
 * 参数：无
 * 返回：带随机标识的页面
 */
private fun newPageDraft(): ProcedurePage = ProcedurePage(
    id = UUID.randomUUID().toString(),
    title = "新页面",
    pagePath = null,
)

/**
 * 功能：新建一个空动作节点
 * 参数：无
 * 返回：带随机标识的 TAP 动作
 */
private fun newActionDraft(): ProcedureAction = ProcedureAction(
    id = UUID.randomUUID().toString(),
    actionKind = ActionKind.TAP,
    title = "新动作",
    anchor = null,
)

/**
 * 功能：按编辑框文本更新锚点
 * 参数：anchor 原锚点；text 新文本
 * 返回：新锚点；文本为空时为 null
 *
 * 只改文本，保留原锚点的 resourceId / className：用户改的是文案，
 * 不该顺手把更稳的资源 ID 锚点降级成纯文本。
 */
private fun mergeAnchor(anchor: Anchor?, text: String): Anchor? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return (anchor ?: Anchor(level = AnchorLevel.A2)).copy(text = trimmed)
}

/**
 * 功能：在四个常用动作之间循环
 * 参数：无（扩展函数）
 * 返回：下一个动作类型
 */
private fun ActionKind.next(): ActionKind = when (this) {
    ActionKind.TAP -> ActionKind.TEXT
    ActionKind.TEXT -> ActionKind.BACK
    ActionKind.BACK -> ActionKind.HOME
    else -> ActionKind.TAP
}
