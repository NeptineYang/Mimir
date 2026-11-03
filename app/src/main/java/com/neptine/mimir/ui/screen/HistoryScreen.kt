package com.neptine.mimir.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.ui.component.EmptyState
import com.neptine.mimir.ui.component.MimirListRow
import com.neptine.mimir.ui.component.MimirPageHeader
import com.neptine.mimir.ui.component.SectionBlock
import com.neptine.mimir.ui.component.StatusChip
import com.neptine.mimir.ui.component.TaskStateChip
import com.neptine.mimir.ui.component.TextActionButton
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirRadius
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.viewmodel.HistoryViewModel
import com.neptine.mimir.ui.viewmodel.TaskRow
import com.neptine.mimir.ui.viewmodel.formatTime

/**
 * 功能：历史页——展示已结束的任务，支持打开审计回放与删除记录
 * 参数：onCreateTask 跳转任务页；onOpenAudit 打开某任务的审计回放
 * 返回：无
 *
 * 只列终态：正在执行或等待人处理的任务归任务页（01）。
 * 删除会同时清掉该任务的审计、意图、授权与截图，因此必须二次确认。
 */
@Composable
fun HistoryScreen(
    onCreateTask: () -> Unit,
    onOpenAudit: (String) -> Unit,
) {
    val viewModel: HistoryViewModel = hiltViewModel()
    val rows by viewModel.tasks.collectAsStateWithLifecycle()
    val colors = LocalMimirColors.current
    var pendingRemoval by remember { mutableStateOf<TaskRow?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MimirSpacing.page),
    ) {
        MimirPageHeader(
            eyebrow = "MIMIR / 02",
            title = "历史",
            subtitle = "已结束的任务；可打开审计回放，或删除不再需要的记录",
        )

        val current = rows
        if (current != null && current.isEmpty()) {
            EmptyState(
                title = "还没有历史记录",
                hint = "任务结束、失败或被放弃后会出现在这里。",
                action = { TextActionButton(text = "去创建", onClick = onCreateTask) },
            )
        }
        if (current != null && current.isNotEmpty()) {
            SectionBlock(title = "已结束", modifier = Modifier.padding(top = MimirSpacing.xl)) {
                current.forEach { row ->
                    HistoryRow(
                        row = row,
                        onOpenAudit = onOpenAudit,
                        onRemove = { pendingRemoval = it },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(MimirSpacing.huge))
    }

    pendingRemoval?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            containerColor = colors.canvas,
            shape = RoundedCornerShape(MimirRadius.lg),
            title = {
                Text(
                    text = "删除这条记录？",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.ink,
                )
            },
            text = {
                Text(
                    text = "「${row.inputText}」的任务记录、审计步骤与截图都会被删除，且不可恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.slate,
                )
            },
            confirmButton = {
                TextActionButton(
                    text = "删除",
                    color = colors.error,
                    onClick = {
                        viewModel.removeTask(row.taskId)
                        pendingRemoval = null
                    },
                )
            },
            dismissButton = {
                TextActionButton(text = "取消", onClick = { pendingRemoval = null })
            },
        )
    }
}

/**
 * 功能：渲染历史中的一条任务
 * 参数：row 任务展示模型；onOpenAudit 打开审计；onRemove 请求删除
 * 返回：无
 */
@Composable
private fun HistoryRow(
    row: TaskRow,
    onOpenAudit: (String) -> Unit,
    onRemove: (TaskRow) -> Unit,
) {
    val colors = LocalMimirColors.current
    val state = row.state
    MimirListRow(
        title = row.inputText,
        note = row.suspendReason,
        noteColor = if (state == TaskState.FAILED || state == TaskState.COMPLETED_UNVERIFIED) {
            colors.coral
        } else {
            colors.muted
        },
        meta = "步 ${row.stepCount} / ${row.maxSteps} · 模型 ${row.budgetModelCalls} · " +
            formatTime(row.finishedAt ?: row.createdAt),
        trailing = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            ) {
                if (state != null) {
                    TaskStateChip(state = state)
                } else {
                    StatusChip(text = row.rawState, fg = colors.slate)
                }
                TextActionButton(
                    text = "删除",
                    color = colors.error,
                    onClick = { onRemove(row) },
                )
            }
        },
        onClick = { onOpenAudit(row.taskId) },
    )
}
