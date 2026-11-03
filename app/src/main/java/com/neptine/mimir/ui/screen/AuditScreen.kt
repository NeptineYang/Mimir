package com.neptine.mimir.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neptine.mimir.ui.component.EmptyState
import com.neptine.mimir.ui.component.KeyValueRow
import com.neptine.mimir.ui.component.MimirListRow
import com.neptine.mimir.ui.component.MimirPageHeader
import com.neptine.mimir.ui.component.MonoLabel
import com.neptine.mimir.ui.component.RiskChip
import com.neptine.mimir.ui.component.SectionBlock
import com.neptine.mimir.ui.component.StatusChip
import com.neptine.mimir.ui.component.TaskStateChip
import com.neptine.mimir.ui.component.TextActionButton
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirRadius
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.viewmodel.AuditRow
import com.neptine.mimir.ui.viewmodel.AuditUiState
import com.neptine.mimir.ui.viewmodel.AuditViewModel
import com.neptine.mimir.ui.viewmodel.TaskRow
import com.neptine.mimir.ui.viewmodel.formatTime
import java.util.Locale

/**
 * 功能：审计页——选任务，或逐步回放某次任务的执行记录
 * 参数：onBack 返回上一页；onSelectTask 选中某个任务进行回放
 * 返回：无
 *
 * 枚举字段（riskLevel / decision / outcome）一律原样显示，不翻译成更友好的措辞：
 * 这一页的价值是"可核对"，改写会破坏证据链。
 */
@Composable
fun AuditScreen(
    onBack: () -> Unit,
    onSelectTask: (String) -> Unit,
) {
    val viewModel: AuditViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalMimirColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MimirSpacing.page),
    ) {
        MimirPageHeader(
            eyebrow = "MIMIR / 03",
            title = "审计",
            subtitle = "每一步的输入、决策与结果",
            trailing = {
                if (state is AuditUiState.Detail) {
                    TextActionButton(text = "返回", onClick = onBack)
                }
            },
        )

        when (val current = state) {
            null -> Unit
            is AuditUiState.Selecting -> SelectingContent(
                tasks = current.tasks,
                onSelectTask = onSelectTask,
            )
            is AuditUiState.Detail -> DetailContent(
                detail = current,
                onSelectStep = viewModel::selectStep,
            )
        }

        Spacer(modifier = Modifier.height(MimirSpacing.huge))
    }
}

/**
 * 功能：渲染"选任务"态
 * 参数：tasks 全部任务；onSelectTask 选中回调
 * 返回：无
 */
@Composable
private fun SelectingContent(
    tasks: List<TaskRow>,
    onSelectTask: (String) -> Unit,
) {
    if (tasks.isEmpty()) {
        EmptyState(
            title = "还没有可审计的任务",
            hint = "每次执行都会把每一步写进审计表。当前审计表为空，因为还没有任何任务进入执行。",
        )
        return
    }
    SectionBlock(title = "选择任务", modifier = Modifier.padding(top = MimirSpacing.md)) {
        tasks.forEach { task ->
            MimirListRow(
                title = task.inputText,
                meta = "${task.taskId.take(8)} · 步 ${task.stepCount} · ${formatTime(task.createdAt)}",
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

    SectionBlock(title = "任务", modifier = Modifier.padding(top = MimirSpacing.md)) {
        if (task == null) {
            Text(
                text = "该任务记录已不存在，只保留审计步骤。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.slate,
            )
        } else {
            KeyValueRow(key = "任务标识", value = task.taskId, mono = true)
            KeyValueRow(key = "目标应用", value = task.packageName.ifBlank { "—（尚未解析）" })
            KeyValueRow(key = "已完成步数", value = "${task.stepCount}")
            KeyValueRow(key = "模型调用", value = "${task.budgetModelCalls}")
        }
    }

    SectionBlock(title = "步骤", modifier = Modifier.padding(top = MimirSpacing.xxl)) {
        if (detail.steps.isEmpty()) {
            EmptyState(
                title = "该任务还没有审计记录",
                hint = "审计按步写入。没有记录通常意味着任务尚未进入执行阶段。",
            )
        } else {
            detail.steps.forEach { step ->
                StepRow(
                    step = step,
                    selected = step.id == detail.selected?.id,
                    onClick = {
                        onSelectStep(if (step.id == detail.selected?.id) null else step.id)
                    },
                )
            }
        }
    }

    detail.selected?.let { step ->
        StepDetail(step = step)
    }
}

/**
 * 功能：渲染时间轴上的一步
 * 参数：step 审计步骤；selected 是否选中；onClick 点击回调
 * 返回：无
 */
@Composable
private fun StepRow(
    step: AuditRow,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalMimirColors.current
    MimirListRow(
        title = step.actionKind,
        leading = {
            MonoLabel(
                text = String.format(Locale.ROOT, "STEP %02d", step.stepIndex),
                color = if (selected) colors.ink else colors.muted,
            )
        },
        meta = "${formatTime(step.timestamp)} · displayGen ${step.displayGeneration}",
        trailing = {
            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.End,
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
            ) {
                step.riskLevel?.let { RiskChip(level = it) }
                Text(
                    text = step.decision,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.slate,
                )
            }
        },
        onClick = onClick,
    )
}

/**
 * 功能：渲染选中步骤的完整证据
 * 参数：step 选中的审计步骤
 * 返回：无
 */
@Composable
private fun StepDetail(step: AuditRow) {
    val colors = LocalMimirColors.current
    SectionBlock(
        title = "第 ${step.stepIndex} 步证据",
        modifier = Modifier.padding(top = MimirSpacing.xxl),
    ) {
        KeyValueRow(key = "当前步骤", value = step.subGoal)
        KeyValueRow(key = "动作类型", value = step.actionKind, mono = true)
        KeyValueRow(key = "参数摘要", value = step.paramsDigest, mono = true)
        KeyValueRow(
            key = "系统返回",
            value = when (step.systemReturnValue) {
                true -> "true（仅代表系统接收）"
                false -> "false（注入失败，可重试与否取决于幂等性）"
                null -> "—"
            },
        )
        KeyValueRow(key = "意图状态", value = step.intentStatus ?: "—", mono = true)
        KeyValueRow(key = "动作结果", value = step.outcome ?: "—", mono = true)
        KeyValueRow(key = "幂等分类", value = step.idempotency, mono = true)
        KeyValueRow(key = "风险等级", value = step.riskLevel?.name ?: "—", mono = true)
        KeyValueRow(key = "处置结论", value = step.decision, mono = true)
        KeyValueRow(key = "复核理由", value = step.reviewReason ?: "—")
        KeyValueRow(key = "目标文本", value = step.targetText ?: "—")
        KeyValueRow(key = "目标指纹", value = step.targetFingerprint ?: "—", mono = true)
        KeyValueRow(key = "授权标识", value = step.grantId ?: "—", mono = true)
        KeyValueRow(key = "图像 token", value = "${step.imageTokens}", mono = true)
        KeyValueRow(key = "验证证据", value = step.verificationEvidence ?: "—")
        KeyValueRow(key = "前帧路径", value = step.screenshotBefore ?: "—", mono = true)
        KeyValueRow(key = "后帧路径", value = step.screenshotAfter ?: "—", mono = true)

        Text(
            text = "模型输出",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.slate,
            modifier = Modifier.padding(top = MimirSpacing.md),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MimirSpacing.sm)
                .clip(RoundedCornerShape(MimirRadius.xs))
                .background(colors.softStone)
                .horizontalScroll(rememberScrollState())
                .padding(MimirSpacing.md),
        ) {
            Text(
                text = step.modelOutput,
                style = MaterialTheme.typography.labelMedium,
                color = colors.ink,
                softWrap = false,
                maxLines = 1,
            )
        }
        Text(
            text = "截图预览需要图像加载组件，本轮不引入，因此只显示路径。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.padding(top = MimirSpacing.sm),
        )
    }
}
