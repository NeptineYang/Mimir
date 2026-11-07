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
import com.neptine.mimir.core.contract.PreferenceCategory
import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.ui.component.EmptyState
import com.neptine.mimir.ui.component.KeyValueRow
import com.neptine.mimir.ui.component.MimirListRow
import com.neptine.mimir.ui.component.MimirPageHeader
import com.neptine.mimir.ui.component.PreferenceEditorDialog
import com.neptine.mimir.ui.component.SectionBlock
import com.neptine.mimir.ui.component.StatusChip
import com.neptine.mimir.ui.component.TaskStateChip
import com.neptine.mimir.ui.component.TextActionButton
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirRadius
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.viewmodel.HistoryViewModel
import com.neptine.mimir.ui.viewmodel.PreferenceDraft
import com.neptine.mimir.ui.viewmodel.RunStats
import com.neptine.mimir.ui.viewmodel.TaskRow
import com.neptine.mimir.ui.viewmodel.canReopenResult
import com.neptine.mimir.ui.viewmodel.formatRate
import com.neptine.mimir.ui.viewmodel.formatSeconds
import com.neptine.mimir.ui.viewmodel.formatTime
import java.util.Locale

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
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val colors = LocalMimirColors.current
    var pendingRemoval by remember { mutableStateOf<TaskRow?>(null) }
    var pendingPreference by remember { mutableStateOf<PreferenceDraft?>(null) }
    var preferenceError by remember { mutableStateOf<String?>(null) }

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
        stats?.let { statistics ->
            if (statistics.finishedTasks + statistics.abortedTasks > 0) {
                RunStatsBlock(stats = statistics, modifier = Modifier.padding(top = MimirSpacing.xl))
            }
        }
        if (current != null && current.isNotEmpty()) {
            SectionBlock(title = "已结束", modifier = Modifier.padding(top = MimirSpacing.xl)) {
                current.forEach { row ->
                    HistoryRow(
                        row = row,
                        onOpenAudit = onOpenAudit,
                        onOpenResult = { viewModel.openResult(it) },
                        onRemember = {
                            preferenceError = null
                            pendingPreference = preferenceDraftOf(row)
                        },
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

    pendingPreference?.let { draft ->
        PreferenceEditorDialog(
            draft = draft,
            error = preferenceError,
            onChange = { pendingPreference = it },
            onSave = {
                viewModel.savePreference(
                    draft = draft,
                    onFailure = { preferenceError = it },
                    onSaved = { pendingPreference = null },
                )
            },
            onCancel = { pendingPreference = null },
        )
    }
}

/**
 * 功能：把一条历史任务预填成偏好草稿
 * 参数：row 任务行
 * 返回：偏好草稿；标题取任务原话前 24 字，作用域取任务包名
 *
 * 类别默认"经验"：用户点开后再按实际情况切成平台/常点/语气/点餐方式。
 * 预填内容只是起点，保存前必须由用户确认——这是"任务后确认"而不是自动学习。
 */
private fun preferenceDraftOf(row: TaskRow): PreferenceDraft = PreferenceDraft(
    id = null,
    category = PreferenceCategory.GENERAL,
    subject = "",
    title = row.inputText.take(24),
    content = row.inputText,
    value = "",
    scope = row.packageName,
)

/**
 * 功能：渲染运行统计区块
 * 参数：stats 聚合结果；modifier 修饰符
 * 返回：无
 *
 * 只展示能自动算的指标。记忆致错率与省步数需要人工回放归因，面板如实写"未自动统计"，
 * 不拿模型自评冒充归因（设计文档 §5.4）。
 */
@Composable
private fun RunStatsBlock(stats: RunStats, modifier: Modifier = Modifier) {
    SectionBlock(title = "运行统计", modifier = modifier) {
        KeyValueRow(
            key = "严格成功率",
            value = "${stats.doneTasks}/${stats.finishedTasks} · ${formatRate(stats.strictSuccessRate)}",
        )
        KeyValueRow(
            key = "首次成功率",
            value = "${stats.firstTryDoneTasks}/${stats.firstTryTasks} · ${formatRate(stats.firstTryRate)}",
        )
        KeyValueRow(
            key = "平均步耗时",
            value = if (stats.timedSteps == 0) {
                "未记录（v6 之前的任务）"
            } else {
                "${formatSeconds(stats.avgTotalMs)} / 步 · ${stats.timedSteps} 步有计时\n" +
                    "观察 ${formatSeconds(stats.avgObserveMs)} · 模型 ${formatSeconds(stats.avgDecideMs)} · " +
                    "注入判定 ${formatSeconds(stats.avgActMs)}"
            },
        )
        KeyValueRow(
            key = "平均步数",
            value = String.format(Locale.ROOT, "%.1f 步", stats.avgSteps),
        )
        KeyValueRow(
            key = "无效步占比",
            value = "${stats.noEffectSteps}/${stats.actionSteps} · ${formatRate(stats.noEffectRate)}" +
                if (stats.unknownSteps > 0) "（未知 ${stats.unknownSteps} 步单列）" else "",
        )
        KeyValueRow(
            key = "记忆命中",
            value = "${stats.memoryHitSteps}/${stats.totalSteps} · ${formatRate(stats.memoryHitRate)}",
        )
        if (stats.memoryHitSteps > 0) {
            KeyValueRow(key = "记忆致错率", value = "需按审计回放人工归因，未自动统计")
        }
        KeyValueRow(
            key = "终态分布",
            value = stats.stateCounts.joinToString(" · ") { "${stateLabel(it.first)} ${it.second}" } +
                if (stats.abortedTasks > 0) " · 已放弃 ${stats.abortedTasks}" else "",
        )
        KeyValueRow(
            key = "失败分类",
            value = if (stats.failureCategories.isEmpty()) {
                "暂无"
            } else {
                stats.failureCategories.joinToString(" · ") { "${it.first} ${it.second}" }
            },
        )
    }
}

/**
 * 功能：把任务状态枚举名翻成面板上的中文
 * 参数：state 任务状态名
 * 返回：中文标签；未知状态原样返回
 */
private fun stateLabel(state: String): String = when (state) {
    "DONE" -> "完成"
    "DONE_READBACK" -> "读回复验"
    "VERIFIED_BY_HUMAN" -> "人工核对"
    "COMPLETED_UNVERIFIED" -> "未复验"
    "FAILED" -> "失败"
    "ABORTED" -> "已放弃"
    else -> state
}

/**
 * 功能：渲染历史中的一条任务
 * 参数：row 任务展示模型；onOpenAudit 打开审计；onOpenResult 重新打开结果页；
 *       onRemove 请求删除
 * 返回：无
 */
@Composable
private fun HistoryRow(
    row: TaskRow,
    onOpenAudit: (String) -> Unit,
    onOpenResult: (String) -> Unit,
    onRemember: () -> Unit,
    onRemove: (TaskRow) -> Unit,
) {
    val colors = LocalMimirColors.current
    val state = row.state
    MimirListRow(
        title = row.inputText,
        note = row.suspendReason,
        // 只有真正失败才用告警色：COMPLETED_UNVERIFIED 是"做完了但没二次核对"，
        // 用红色会把一次成功的任务读成失败。
        noteColor = if (state == TaskState.FAILED) colors.coral else colors.muted,
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
                // 结果页只在保留期内可从通知进入；错过通知后这里就是唯一的回看入口。
                if (row.canReopenResult()) {
                    TextActionButton(
                        text = "查看结果",
                        onClick = { onOpenResult(row.taskId) },
                    )
                }
                TextActionButton(
                    text = "记偏好",
                    onClick = onRemember,
                )
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
