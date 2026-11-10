package com.neptine.mimir.ui.screen

import com.neptine.mimir.ui.i18n.t
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neptine.mimir.ui.component.AuditStepDetail
import com.neptine.mimir.ui.component.AuditStepRow
import com.neptine.mimir.ui.component.EmptyState
import com.neptine.mimir.ui.component.KeyValueRow
import com.neptine.mimir.ui.component.MimirListRow
import com.neptine.mimir.ui.component.MimirPageHeader
import com.neptine.mimir.ui.component.MonoLabel
import com.neptine.mimir.ui.component.Pager
import com.neptine.mimir.ui.component.SectionBlock
import com.neptine.mimir.ui.component.StatusChip
import com.neptine.mimir.ui.component.TaskStateChip
import com.neptine.mimir.ui.component.TextActionButton
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.viewmodel.AuditSelectPage
import com.neptine.mimir.ui.viewmodel.AuditUiState
import com.neptine.mimir.ui.viewmodel.AuditViewModel
import com.neptine.mimir.ui.viewmodel.TaskRow
import com.neptine.mimir.ui.viewmodel.formatSeconds
import com.neptine.mimir.ui.viewmodel.formatTime

/**
 * 功能：按任务回放——选任务，或逐步查看某次任务的完整时间轴
 * 参数：onBack 返回上一页；onSelectTask 选中某个任务进行回放
 * 返回：无
 *
 * 枚举字段（riskLevel / decision / outcome）一律原样显示，不翻译成更友好的措辞：
 * 这一页的价值是"可核对"，改写会破坏证据链。
 */
@Composable
fun AuditTaskScreen(
    onBack: () -> Unit,
    onSelectTask: (String) -> Unit,
) {
    val viewModel: AuditViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectPage by viewModel.selectPage.collectAsStateWithLifecycle()
    val colors = LocalMimirColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MimirSpacing.page),
    ) {
        MimirPageHeader(
            title = "按任务回放".t(),
            trailing = {
                // 选任务态也要能回到高危事件流；此前只在详情态显示返回，
                // 从"按任务回放"进来后会被困在这一页。
                TextActionButton(text = "← 返回".t(), onClick = onBack)
            },
        )

        when (val current = state) {
            null -> Unit
            is AuditUiState.Selecting -> selectPage?.let { page ->
                SelectingContent(
                    page = page,
                    onSelectTask = onSelectTask,
                    onPrev = viewModel::goPrevSelectPage,
                    onNext = viewModel::goNextSelectPage,
                )
            }
            is AuditUiState.Detail -> DetailContent(
                detail = current,
                onSelectStep = viewModel::selectStep,
            )
        }

        Spacer(modifier = Modifier.height(MimirSpacing.huge))
    }
}

/**
 * 功能：渲染"选任务"态（分页）
 * 参数：page 当前页；onSelectTask 选中回调；onPrev/onNext 翻页
 * 返回：无
 */
@Composable
private fun SelectingContent(
    page: AuditSelectPage,
    onSelectTask: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    if (page.total == 0) {
        EmptyState(
            title = "还没有可审计的任务".t(),
            hint = "每次执行都会把每一步写进审计表。当前审计表为空，因为还没有任何任务进入执行。".t(),
        )
        return
    }
    SectionBlock(
        title = "选择任务".t(),
        modifier = Modifier.padding(top = MimirSpacing.md),
        trailing = { MonoLabel(text = "${page.total} TASKS", color = LocalMimirColors.current.muted) },
    ) {
        page.rows.forEach { task ->
            MimirListRow(
                title = task.inputText,
                meta = "%1\$s · 步 %2\$d · %3\$s".t(
                    task.taskId.take(8),
                    task.stepCount,
                    formatTime(task.createdAt),
                ),
                trailing = {
                    val state = task.state
                    if (state != null) {
                        TaskStateChip(state = state)
                    } else {
                        StatusChip(text = task.rawState, fg = LocalMimirColors.current.slate)
                    }
                },
                onClick = { onSelectTask(task.taskId) },
            )
        }
    }
    Pager(
        page = page.page,
        pageCount = page.pageCount,
        onPrev = onPrev,
        onNext = onNext,
    )
}

/**
 * 功能：渲染"回放"态
 * 参数：detail 回放数据；onSelectStep 选中某一步
 * 返回：无
 */
@Composable
private fun DetailContent(
    detail: AuditUiState.Detail,
    onSelectStep: (Long?) -> Unit,
) {
    val colors = LocalMimirColors.current
    val task = detail.task

    SectionBlock(title = "任务".t(), modifier = Modifier.padding(top = MimirSpacing.md)) {
        if (task == null) {
            Text(
                text = "该任务记录已不存在，只保留审计步骤。".t(),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.slate,
            )
        } else {
            KeyValueRow(key = "任务标识".t(), value = task.taskId, mono = true)
            KeyValueRow(key = "目标应用".t(), value = task.packageName.ifBlank { "—（尚未解析）".t() })
            KeyValueRow(key = "已完成步数".t(), value = "${task.stepCount}")
            KeyValueRow(key = "模型调用".t(), value = "${task.budgetModelCalls}")
            val timed = detail.steps.filter { it.totalMs > 0 }
            if (timed.isEmpty()) {
                KeyValueRow(key = "步耗时".t(), value = "未留存（v6 之前的记录）".t())
            } else {
                KeyValueRow(
                    key = "平均步耗时".t(),
                    value = "${formatSeconds(timed.sumOf { it.totalMs } / timed.size)} · ${timed.size} 步有计时",
                )
                KeyValueRow(
                    key = "耗时构成".t(),
                    value = "观察 ${formatSeconds(timed.sumOf { it.observeMs } / timed.size)}\n" +
                        "模型 ${formatSeconds(timed.sumOf { it.decideMs } / timed.size)}\n" +
                        "注入判定 ${formatSeconds(timed.sumOf { it.actMs } / timed.size)}",
                )
            }
        }
    }

    SectionBlock(title = "步骤".t(), modifier = Modifier.padding(top = MimirSpacing.xxl)) {
        if (detail.steps.isEmpty()) {
            EmptyState(
                title = "该任务还没有审计记录".t(),
                hint = "审计按步写入。没有记录通常意味着任务尚未进入执行阶段。".t(),
            )
        } else {
            detail.steps.forEach { step ->
                AuditStepRow(
                    step = step,
                    selected = step.id == detail.selected?.id,
                    onClick = {
                        onSelectStep(if (step.id == detail.selected?.id) null else step.id)
                    },
                )
                if (step.id == detail.selected?.id) {
                    AuditStepDetail(step = step)
                }
            }
        }
    }
}
