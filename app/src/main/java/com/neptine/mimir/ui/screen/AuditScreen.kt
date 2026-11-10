package com.neptine.mimir.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neptine.mimir.data.db.dao.AuditEventRow
import com.neptine.mimir.ui.component.EmptyState
import com.neptine.mimir.ui.component.MimirListRow
import com.neptine.mimir.ui.component.MimirPageHeader
import com.neptine.mimir.ui.component.MonoLabel
import com.neptine.mimir.ui.component.Pager
import com.neptine.mimir.ui.component.RiskChip
import com.neptine.mimir.ui.component.SectionBlock
import com.neptine.mimir.ui.component.StatusChip
import com.neptine.mimir.ui.component.TextActionButton
import com.neptine.mimir.ui.i18n.t
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.viewmodel.AuditEventsViewModel
import com.neptine.mimir.ui.viewmodel.AuditFilter
import com.neptine.mimir.ui.viewmodel.formatTime
import com.neptine.mimir.ui.viewmodel.parseRiskLevel

/**
 * 功能：审计页——按"高危操作"列事件，而不是先选任务
 * 参数：onOpenEvent 打开某条事件的证据；onOpenTaskReplay 进入"按任务回放"次入口
 * 返回：无
 *
 * 两种看法的分工：
 * - 这里回答"最近哪些操作需要人确认/被阻断、它们属于哪条任务"；
 * - 完整的一条任务时间轴属于次入口，排障时才进去。
 *
 * 事件只显示摘要（目标、任务、步序、风险、处置），证据在详情里，
 * 避免列表本身又被字段淹没。
 */
@Composable
fun AuditScreen(
    onOpenEvent: (Long) -> Unit,
    onOpenTaskReplay: () -> Unit,
) {
    val viewModel: AuditEventsViewModel = hiltViewModel()
    val pageState by viewModel.pageState.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val colors = LocalMimirColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MimirSpacing.page),
    ) {
        MimirPageHeader(
            title = "审计".t(),
            trailing = {
                TextActionButton(text = "按任务回放".t(), onClick = onOpenTaskReplay)
            },
        )

        AuditFilterRow(selected = selectedFilter, onSelect = viewModel::setFilter)

        val current = pageState
        when {
            current == null -> Unit
            current.total == 0 -> if (selectedFilter == AuditFilter.ALL) {
                EmptyState(
                    title = "还没有高危操作".t(),
                    hint = "需要人确认、被阻断或失败的操作会出现在这里；每一条都能追溯到任务与证据。".t(),
                )
            } else {
                EmptyState(
                    title = "该筛选条件下没有事件".t(),
                    hint = "换一个筛选条件，或查看全部。".t(),
                    action = {
                        TextActionButton(
                            text = "查看全部".t(),
                            onClick = { viewModel.setFilter(AuditFilter.ALL) },
                        )
                    },
                )
            }
            else -> {
                SectionBlock(
                    title = "高危操作".t(),
                    modifier = Modifier.padding(top = MimirSpacing.md),
                    trailing = { MonoLabel(text = "${current.total} EVENTS", color = colors.muted) },
                ) {
                    current.rows.forEach { event ->
                        AuditEventListRow(event = event, onOpen = onOpenEvent)
                    }
                }
                Pager(
                    page = current.page,
                    pageCount = current.pageCount,
                    onPrev = viewModel::goPrev,
                    onNext = viewModel::goNext,
                )
            }
        }

        Spacer(modifier = Modifier.height(MimirSpacing.huge))
    }
}

/**
 * 功能：渲染筛选条（全部 / 仅阻断 / 仅确认 / 仅失败）
 * 参数：selected 当前口径；onSelect 选择回调
 * 返回：无
 *
 * 先筛后列：口径变化时由查询重新发流，不在界面层过滤列表——
 * 否则"筛选后为空"与"本来就没有"在界面上分不清。
 */
@Composable
private fun AuditFilterRow(
    selected: AuditFilter,
    onSelect: (AuditFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MimirSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
    ) {
        AuditFilter.entries.forEach { filter ->
            TextActionButton(
                text = filter.label().t(),
                onClick = { onSelect(filter) },
                emphasis = filter == selected,
            )
        }
    }
}

/** 功能：筛选口径的中文标签；参数：无；返回：展示用短标签。 */
private fun AuditFilter.label(): String = when (this) {
    AuditFilter.ALL -> "全部"
    AuditFilter.BLOCKED -> "仅阻断"
    AuditFilter.CONFIRM -> "仅确认"
    AuditFilter.FAILED -> "仅失败"
}

/**
 * 功能：渲染一行审计事件
 * 参数：event 事件投影；onOpen 打开详情回调
 * 返回：无
 *
 * 标题取"目标文本 → 子目标 → 动作类型"：用户记得的是"点了去结算"，
 * 不是 "TAP"。任务原话放在第二行，保证每条事件都能追回来源。
 */
@Composable
private fun AuditEventListRow(
    event: AuditEventRow,
    onOpen: (Long) -> Unit,
) {
    val colors = LocalMimirColors.current
    val risk = parseRiskLevel(event.riskLevel)
    val title = event.targetText?.takeIf { it.isNotBlank() }
        ?: event.subGoal.takeIf { it.isNotBlank() }
        ?: event.actionKind

    MimirListRow(
        title = title,
        leading = {
            if (risk != null) {
                RiskChip(level = risk)
            } else {
                StatusChip(text = event.riskLevel, fg = colors.slate)
            }
        },
        note = event.taskTitle ?: event.taskId,
        meta = "第 %1\$d 步 · %2\$s".t(event.stepIndex, formatTime(event.timestamp)),
        trailing = {
            StatusChip(text = event.decision, fg = decisionColor(event.decision))
        },
        onClick = { onOpen(event.id) },
    )
}

/**
 * 功能：把处置结论映射为强调色
 * 参数：decision 处置结论
 * 返回：chip 使用的颜色
 *
 * 颜色只做辅助，文字本身保留原始枚举，避免"颜色好看但读不出结论"。
 */
@Composable
private fun decisionColor(decision: String): Color {
    val colors = LocalMimirColors.current
    return when (decision) {
        "ALLOW" -> colors.deepGreen
        "CONFIRM" -> colors.coral
        "BLOCK", "DENY" -> colors.error
        "RETRY" -> colors.slate
        else -> colors.slate
    }
}
