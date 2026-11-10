package com.neptine.mimir.ui.screen

import com.neptine.mimir.ui.i18n.t
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neptine.mimir.core.contract.ScheduleRepeat
import com.neptine.mimir.ui.component.EmptyState
import com.neptine.mimir.ui.component.MimirListRow
import com.neptine.mimir.ui.component.MimirPageHeader
import com.neptine.mimir.ui.component.SectionBlock
import com.neptine.mimir.ui.component.TextActionButton
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirRadius
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.viewmodel.ScheduledTaskDraft
import com.neptine.mimir.ui.viewmodel.ScheduledTaskRow
import com.neptine.mimir.ui.viewmodel.ScheduledTaskUiState
import com.neptine.mimir.ui.viewmodel.ScheduledTaskViewModel

/**
 * 功能：定时任务页——设定时间让 Agent 自动执行
 * 参数：onBack 返回回调
 * 返回：无
 *
 * 全屏路由而不是第五个底栏项：定时任务是低频维护动作，从设置页进入。
 * 到点执行会点亮屏幕但保持锁屏；任务内容不在锁屏上显示。
 */
@Composable
fun ScheduledTaskScreen() {
    val viewModel: ScheduledTaskViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalMimirColors.current
    val context = LocalContext.current
    val exactAlarmAllowed = remember {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() != false
    }
    var pendingDelete by remember { mutableStateOf<ScheduledTaskRow?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MimirSpacing.page),
    ) {
        MimirPageHeader(
            title = "定时任务".t(),
        )

        Notice(state = state, onDismiss = viewModel::dismissNotice)

        if (!exactAlarmAllowed) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = MimirSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "精确闹钟未开启：任务可能晚几分钟触发。".t(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.coral,
                    modifier = Modifier.weight(1f),
                )
                TextActionButton(
                    text = "去开启".t(),
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    },
                )
            }
        }

        SectionBlock(
            title = "已设定".t(),
            trailing = {
                TextActionButton(
                    text = "新增".t(),
                    emphasis = true,
                    onClick = viewModel::startCreate,
                )
            },
        ) {
            val tasks = state.tasks
            if (tasks != null && tasks.isEmpty()) {
                EmptyState(
                    title = "还没有定时任务".t(),
                    hint = "例如每天早上 8:30 打开 B 站看关注更新。".t() +
                        "定时任务与手动任务走同一条执行链路，结果照常进历史与审计。".t(),
                )
            }
            tasks.orEmpty().forEach { row ->
                ScheduledTaskListRow(
                    row = row,
                    onEdit = { viewModel.startEdit(row.id) },
                    onToggle = { viewModel.toggle(row.id) },
                    onDelete = { pendingDelete = row },
                )
            }
        }

        Spacer(modifier = Modifier.height(MimirSpacing.huge))
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = colors.canvas,
            shape = RoundedCornerShape(MimirRadius.lg),
            title = {
                Text(
                    text = "删除这条定时任务？".t(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.ink,
                )
            },
            text = {
                Text(
                    text = "「${row.title}」将不再自动执行；已经产生的历史任务不受影响。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.slate,
                )
            },
            confirmButton = {
                TextActionButton(
                    text = "删除".t(),
                    color = colors.error,
                    onClick = {
                        viewModel.delete(row.id)
                        pendingDelete = null
                    },
                )
            },
            dismissButton = {
                TextActionButton(text = "取消".t(), onClick = { pendingDelete = null })
            },
        )
    }

    state.draft?.let { draft ->
        ScheduledTaskEditor(
            draft = draft,
            error = state.error,
            onChange = viewModel::updateDraft,
            onSave = viewModel::saveDraft,
            onCancel = viewModel::cancelDraft,
            onDelete = draft.id?.let { id ->
                { viewModel.delete(id); viewModel.cancelDraft() }
            },
        )
    }
}

/**
 * 功能：渲染一条定时任务
 * 参数：row 列表项；onEdit 编辑；onToggle 启停；onDelete 删除
 * 返回：无
 */
@Composable
private fun ScheduledTaskListRow(
    row: ScheduledTaskRow,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalMimirColors.current
    val status = when {
        !row.enabled -> "已停用".t()
        row.lastState == null -> "尚未执行".t()
        else -> "上次 ${row.lastState}"
    }
    MimirListRow(
        title = row.title,
        meta = "${row.timeText} · ${row.repeatText} · $status" +
            if (row.allowL2a) " · 预设授权 L2A".t() else "",
        note = row.inputText,
        onClick = onEdit,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                TextActionButton(
                    text = if (row.enabled) "停用".t() else "启用".t(),
                    onClick = onToggle,
                )
                TextActionButton(
                    text = "删除".t(),
                    color = colors.error,
                    onClick = onDelete,
                )
            }
        },
    )
}

/**
 * 功能：定时任务编辑弹窗
 * 参数：draft 草稿；error 错误；onChange 字段变更；onSave 保存；onCancel 取消；onDelete 删除
 * 返回：无
 *
 * 重复方式与星期都用点按切换：只有三种重复方式、七个星期，展开下拉反而多一层。
 */
@Composable
private fun ScheduledTaskEditor(
    draft: ScheduledTaskDraft,
    error: String?,
    onChange: (ScheduledTaskDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = LocalMimirColors.current
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = if (draft.id == null) "新建定时任务".t() else "编辑定时任务".t()) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            ) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { onChange(draft.copy(title = it)) },
                    label = { Text("标题".t()) },
                    placeholder = { Text("早间看关注更新".t()) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.inputText,
                    onValueChange = { onChange(draft.copy(inputText = it)) },
                    label = { Text("任务内容（交给 Agent 的原话）".t()) },
                    placeholder = { Text("打开 B 站看关注更新".t()) },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = draft.hourText,
                        onValueChange = { onChange(draft.copy(hourText = it)) },
                        label = { Text("时".t()) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = draft.minuteText,
                        onValueChange = { onChange(draft.copy(minuteText = it)) },
                        label = { Text("分".t()) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "重复".t(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.slate,
                        modifier = Modifier.padding(end = MimirSpacing.md),
                    )
                    TextActionButton(
                        text = draft.repeat.label(),
                        onClick = { onChange(draft.copy(repeat = draft.repeat.next())) },
                    )
                    Text(
                        text = "点一下切换".t(),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.slate,
                    )
                }
                if (draft.repeat == ScheduleRepeat.WEEKLY) {
                    // 七个圆角按钮在一行里放不下（每个约 40dp，弹窗可用宽度只有 ~270dp），
                    // 用 FlowRow 允许折行，免得最后一两天被裁掉。
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
                        verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
                        // 一行 7 个会挤成 6+1 的孤儿行；固定 4 个一行，排成 4+3 更整齐
                        maxItemsInEachRow = 4,
                    ) {
                        (1..7).forEach { day ->
                            TextActionButton(
                                text = dayName(day),
                                color = if (day in draft.days) colors.ink else colors.muted,
                                onClick = {
                                    val next = if (day in draft.days) {
                                        draft.days - day
                                    } else {
                                        draft.days + day
                                    }
                                    onChange(draft.copy(days = next))
                                },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "需要确认的操作".t(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.slate,
                        modifier = Modifier.padding(end = MimirSpacing.md),
                    )
                    TextActionButton(
                        text = if (draft.allowL2a) "自动执行".t() else "到时挂起".t(),
                        onClick = { onChange(draft.copy(allowL2a = !draft.allowL2a)) },
                    )
                }
                Text(
                    text = if (draft.allowL2a) {
                        "到点后发送消息、加购等 L2A 操作会自动批准；支付、验证码、人脸仍会阻断。".t()
                    } else {
                        "到点后遇到需要确认的操作会挂起并通知你，任务不会继续。".t()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (draft.allowL2a) colors.coral else colors.slate,
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
 * 功能：渲染提示条
 * 参数：state 页面状态；onDismiss 关闭
 * 返回：无
 */
@Composable
private fun Notice(state: ScheduledTaskUiState, onDismiss: () -> Unit) {
    val colors = LocalMimirColors.current
    val text = state.error ?: state.message ?: return
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = MimirSpacing.md)) {
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
 * 功能：重复方式的中文标签
 * 参数：无（扩展函数）
 * 返回：短标签
 */
@Composable
private fun ScheduleRepeat.label(): String = when (this) {
    ScheduleRepeat.ONCE -> "一次".t()
    ScheduleRepeat.DAILY -> "每天".t()
    ScheduleRepeat.WEEKLY -> "每周".t()
}

/**
 * 功能：在三种重复方式之间循环
 * 参数：无（扩展函数）
 * 返回：下一个重复方式
 */
private fun ScheduleRepeat.next(): ScheduleRepeat = when (this) {
    ScheduleRepeat.ONCE -> ScheduleRepeat.DAILY
    ScheduleRepeat.DAILY -> ScheduleRepeat.WEEKLY
    ScheduleRepeat.WEEKLY -> ScheduleRepeat.ONCE
}

/**
 * 功能：星期序号转中文
 * 参数：day 1–7
 * 返回：单字星期名
 */
@Composable
private fun dayName(day: Int): String = when (day) {
    1 -> "一".t()
    2 -> "二".t()
    3 -> "三".t()
    4 -> "四".t()
    5 -> "五".t()
    6 -> "六".t()
    else -> "日".t()
}
