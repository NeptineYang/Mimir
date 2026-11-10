package com.neptine.mimir.ui.screen

import com.neptine.mimir.ui.i18n.t
import com.neptine.mimir.ui.i18n.tStatic
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neptine.mimir.core.contract.PreferenceCategory
import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.ui.component.AuditStepRow
import com.neptine.mimir.ui.component.EmptyState
import com.neptine.mimir.ui.component.ExpandableSection
import com.neptine.mimir.ui.component.KeyValueRow
import com.neptine.mimir.ui.component.MimirHairline
import com.neptine.mimir.ui.component.MimirIcons
import com.neptine.mimir.ui.component.MimirPageHeader
import com.neptine.mimir.ui.component.Pager
import com.neptine.mimir.ui.component.MonoLabel
import com.neptine.mimir.ui.component.PreferenceEditorDialog
import com.neptine.mimir.ui.component.SectionBlock
import com.neptine.mimir.ui.component.StatusChip
import com.neptine.mimir.ui.component.TaskStateChip
import com.neptine.mimir.ui.component.TextActionButton
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirRadius
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.viewmodel.AuditRow
import com.neptine.mimir.ui.viewmodel.HistoryDetail
import com.neptine.mimir.ui.viewmodel.HistoryFilter
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
 * 功能：历史页——已结束任务，点开就地展开任务摘要与步骤摘要
 * 参数：onCreateTask 跳转任务页；onOpenAudit 打开该任务的完整审计回放
 * 返回：无
 *
 * 不再为每条任务跳一个独立页面：跳页会把"对比几条记录"这件事打断成
 * "进入-返回-再进入"。手风琴让同一条时间线上的信息原地铺开，
 * 折叠后仍然只剩一行，一屏能扫过的条数没有变少。
 *
 * 只列终态：正在执行或等待人处理的任务归任务页。
 * 删除会同时清掉该任务的审计、意图、授权与截图，因此必须二次确认。
 */
@Composable
fun HistoryScreen(
    onCreateTask: () -> Unit,
    onOpenAudit: (String) -> Unit,
) {
    val viewModel: HistoryViewModel = hiltViewModel()
    val pageState by viewModel.pageState.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val colors = LocalMimirColors.current
    var pendingRemoval by remember { mutableStateOf<TaskRow?>(null) }
    var pendingPreference by remember { mutableStateOf<PreferenceDraft?>(null) }
    var preferenceError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MimirSpacing.page),
    ) {
        MimirPageHeader(
            title = "历史".t(),
        )

        HistoryFilterRow(selected = selectedFilter, onSelect = viewModel::setFilter)

        val current = pageState
        if (current != null && current.total == 0) {
            if (selectedFilter == HistoryFilter.ALL) {
                EmptyState(
                    title = "还没有历史记录".t(),
                    hint = "任务结束、失败或被放弃后会出现在这里。".t(),
                    action = { TextActionButton(text = "去创建".t(), onClick = onCreateTask) },
                )
            } else {
                EmptyState(
                    title = "该时间范围内没有任务".t(),
                    hint = "换一个时间范围，或查看全部。".t(),
                    action = {
                        TextActionButton(
                            text = "查看全部".t(),
                            onClick = { viewModel.setFilter(HistoryFilter.ALL) },
                        )
                    },
                )
            }
        }
        if (current != null && current.total > 0) {
            SectionBlock(
                title = "已结束".t(),
                modifier = Modifier.padding(top = MimirSpacing.md),
                trailing = { MonoLabel(text = "${current.total} TASKS", color = colors.muted) },
            ) {
                current.rows.forEach { row ->
                    HistoryRow(
                        row = row,
                        detail = detail,
                        onToggle = { viewModel.toggleExpanded(row.taskId) },
                        onOpenAudit = { onOpenAudit(row.taskId) },
                        onOpenResult = { viewModel.openResult(row.taskId) },
                        onRemember = {
                            preferenceError = null
                            pendingPreference = preferenceDraftOf(row)
                        },
                        onRemove = { pendingRemoval = row },
                    )
                }
            }
            Pager(
                page = current.page,
                pageCount = current.pageCount,
                onPrev = viewModel::goPrev,
                onNext = viewModel::goNext,
            )
        }
        // 统计放在列表之后并默认收起：它回答的是"这段时间整体怎么样"，
        // 而进入历史页的第一诉求通常是"看某一条任务"。收起后只留一行摘要，
        // 两个诉求都不互相挡路。
        // 统计是全量口径（读整张审计表），筛选到某个时间范围时先隐藏，
        // 避免"列表 2 条、统计 30 条"看起来像数据不一致。
        stats?.takeIf { selectedFilter == HistoryFilter.ALL }?.let { statistics ->
            if (statistics.finishedTasks + statistics.abortedTasks > 0) {
                ExpandableSection(
                    title = "运行统计".t(),
                    modifier = Modifier.padding(top = MimirSpacing.xl),
                    summary = "严格成功率 %1\$s · %2\$d/%3\$d 完成".t(
                        formatRate(statistics.strictSuccessRate),
                        statistics.doneTasks,
                        statistics.finishedTasks,
                    ),
                ) {
                    RunStatsContent(stats = statistics)
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
                    text = "删除这条记录？".t(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.ink,
                )
            },
            text = {
                Text(
                    text = "「%1\$s」的任务记录、审计步骤与截图都会被删除，且不可恢复。".t(row.inputText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.slate,
                )
            },
            confirmButton = {
                TextActionButton(
                    text = "删除".t(),
                    color = colors.error,
                    onClick = {
                        viewModel.removeTask(row.taskId)
                        pendingRemoval = null
                    },
                )
            },
            dismissButton = {
                TextActionButton(text = "取消".t(), onClick = { pendingRemoval = null })
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
 * 功能：渲染运行统计的指标行
 * 参数：stats 聚合结果
 * 返回：无
 *
 * 只展示能自动算的指标。记忆致错率与省步数需要人工回放归因，面板如实写"未自动统计"，
 * 不拿模型自评冒充归因（设计文档 §5.4）。
 */
@Composable
private fun RunStatsContent(stats: RunStats) {
    KeyValueRow(
        key = "严格成功率".t(),
        value = "${stats.doneTasks}/${stats.finishedTasks} · ${formatRate(stats.strictSuccessRate)}",
    )
    KeyValueRow(
        key = "首次成功率".t(),
        value = "${stats.firstTryDoneTasks}/${stats.firstTryTasks} · ${formatRate(stats.firstTryRate)}",
    )
    KeyValueRow(
        key = "平均步耗时".t(),
        value = if (stats.timedSteps == 0) {
            "未记录（v6 之前的任务）".t()
        } else {
            "${formatSeconds(stats.avgTotalMs)} / 步 · ${stats.timedSteps} 步有计时\n" +
                "观察 ${formatSeconds(stats.avgObserveMs)} · 模型 ${formatSeconds(stats.avgDecideMs)} · " +
                "注入判定 ${formatSeconds(stats.avgActMs)}"
        },
    )
    KeyValueRow(
        key = "平均步数".t(),
        value = String.format(Locale.ROOT, "%.1f 步".t(), stats.avgSteps),
    )
    KeyValueRow(
        key = "无效步占比".t(),
        value = "${stats.noEffectSteps}/${stats.actionSteps} · ${formatRate(stats.noEffectRate)}" +
            if (stats.unknownSteps > 0) "（未知 ${stats.unknownSteps} 步单列）" else "",
    )
    KeyValueRow(
        key = "记忆命中".t(),
        value = "${stats.memoryHitSteps}/${stats.totalSteps} · ${formatRate(stats.memoryHitRate)}",
    )
    if (stats.memoryHitSteps > 0) {
        KeyValueRow(key = "记忆致错率".t(), value = "需按审计回放人工归因，未自动统计".t())
    }
    KeyValueRow(
        key = "终态分布".t(),
        value = stats.stateCounts.joinToString(" · ") { "${stateLabel(it.first)} ${it.second}" } +
            if (stats.abortedTasks > 0) " · 已放弃 %1\$d".t(stats.abortedTasks) else "",
    )
    KeyValueRow(
        key = "失败分类".t(),
        value = if (stats.failureCategories.isEmpty()) {
            "暂无".t()
        } else {
            stats.failureCategories.joinToString(" · ") { "${it.first} ${it.second}" }
        },
    )
}

/**
 * 功能：把任务状态枚举名翻成面板上的中文
 * 参数：state 任务状态名
 * 返回：中文标签；未知状态原样返回
 */
private fun stateLabel(state: String): String = when (state) {
    "DONE" -> "完成".tStatic()
    "DONE_READBACK" -> "读回复验".tStatic()
    "VERIFIED_BY_HUMAN" -> "人工核对".tStatic()
    "COMPLETED_UNVERIFIED" -> "未复验".tStatic()
    "FAILED" -> "失败".tStatic()
    "ABORTED" -> "已放弃".tStatic()
    else -> state
}

/**
 * 功能：为历史步骤生成一句"做了什么"
 * 参数：step 审计步骤
 * 返回：优先模型自述 summary，其次"动词 + 目标文本"，再次复核理由/子目标
 *
 * 模型不一定每步都给 summary（例如纯点击动作），所以保留动词兜底：
 * "点击 6"比单纯的 "6" 或 "TAP" 更容易读。
 */
@Composable
private fun historyTitle(step: AuditRow): String {
    step.summary?.let { return it }
    val target = step.targetText?.takeIf { it.isNotBlank() }
    val verb = actionVerb(step.actionKind)
    if (target != null && verb != null) return "$verb $target"
    return step.reviewReason?.takeIf { it.isNotBlank() }
        ?: step.subGoal.takeIf { it.isNotBlank() }
        ?: step.actionKind
}

/** 功能：动作类型的中文动词；参数：actionKind 动作类型；返回：动词，未知类型为 null。 */
@Composable
private fun actionVerb(actionKind: String): String? = when (actionKind) {
    "TAP" -> "点击".t()
    "SWIPE" -> "滑动".t()
    "TEXT" -> "输入".t()
    "BACK" -> "返回".t()
    "HOME" -> "回主屏".t()
    "LAUNCH" -> "启动".t()
    "WAIT" -> "等待".t()
    "CREDENTIAL" -> "使用凭据".t()
    else -> null
}

/**
 * 功能：渲染时间筛选条（今天 / 本周 / 本月 / 全部）
 * 参数：selected 当前口径；onSelect 选择回调
 * 返回：无
 */
@Composable
private fun HistoryFilterRow(
    selected: HistoryFilter,
    onSelect: (HistoryFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MimirSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
    ) {
        HistoryFilter.entries.forEach { filter ->
            TextActionButton(
                text = filter.label().t(),
                onClick = { onSelect(filter) },
                emphasis = filter == selected,
            )
        }
    }
}

/** 功能：时间筛选的中文标签；参数：无；返回：展示用短标签。 */
private fun HistoryFilter.label(): String = when (this) {
    HistoryFilter.TODAY -> "今天"
    HistoryFilter.WEEK -> "本周"
    HistoryFilter.MONTH -> "本月"
    HistoryFilter.ALL -> "全部"
}

/**
 * 功能：渲染历史中的一条任务（手风琴）
 * 参数：row 任务展示模型；detail 当前展开项；onToggle 展开/收起；onOpenAudit 打开完整审计回放；
 *       onOpenResult 重新打开结果页；onRemember 记偏好；onRemove 请求删除
 * 返回：无
 *
 * 折叠态保留三行：任务原话、状态与结束时间、步数与模型调用。
 * 这三行是"要不要展开"的判断依据；把它们一起藏起来，手风琴就变成了盲盒。
 */
@Composable
private fun HistoryRow(
    row: TaskRow,
    detail: HistoryDetail?,
    onToggle: () -> Unit,
    onOpenAudit: () -> Unit,
    onOpenResult: () -> Unit,
    onRemember: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalMimirColors.current
    val state = row.state
    val expanded = detail?.taskId == row.taskId
    // 只列真正落到设备上的动作：系统事件（解析/计划/step_done）与动作行会重复，
    // 全列出来会让"每一步做了什么"被一堆内部记账淹没。
    val steps = if (expanded) {
        detail?.steps.orEmpty().filter { it.actionKind.isNotBlank() }
    } else {
        emptyList()
    }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "historyArrow",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = MimirSpacing.lg),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
            ) {
                Text(
                    text = row.inputText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                ) {
                    if (state != null) {
                        TaskStateChip(state = state)
                    } else {
                        StatusChip(text = row.rawState, fg = colors.slate)
                    }
                    MonoLabel(
                        text = formatTime(row.finishedAt ?: row.createdAt),
                        color = colors.muted,
                        uppercase = false,
                    )
                }
                Text(
                    text = "步 %1\$d / %2\$d · 模型 %3\$d / %4\$d".t(
                        row.stepCount,
                        row.maxSteps,
                        row.budgetModelCalls,
                        row.maxModelCalls,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
                row.suspendReason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state == TaskState.FAILED) colors.coral else colors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = MimirIcons.ChevronDown,
                contentDescription = if (expanded) "收起任务详情".t() else "展开任务详情".t(),
                tint = if (expanded) colors.ink else colors.muted,
                modifier = Modifier
                    .padding(start = MimirSpacing.sm, top = 2.dp)
                    .size(20.dp)
                    .rotate(arrowRotation),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(220), expandFrom = Alignment.Top) +
                fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = tween(200), shrinkTowards = Alignment.Top) +
                fadeOut(animationSpec = tween(120)),
        ) {
            Column(modifier = Modifier.padding(bottom = MimirSpacing.lg)) {
                KeyValueRow(
                    key = "目标应用".t(),
                    value = row.packageName.ifBlank { "—（尚未解析）".t() },
                )
                KeyValueRow(key = "已完成步数".t(), value = "${row.stepCount} / ${row.maxSteps}")
                KeyValueRow(key = "模型调用".t(), value = "${row.budgetModelCalls} / ${row.maxModelCalls}")
                KeyValueRow(
                    key = "结束时间".t(),
                    value = formatTime(row.finishedAt ?: row.createdAt),
                    mono = true,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MimirSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 结果页只在保留期内可从通知进入；错过通知后这里就是唯一的回看入口。
                    if (row.canReopenResult()) {
                        TextActionButton(text = "查看结果".t(), onClick = onOpenResult)
                    }
                    TextActionButton(text = "审计回放".t(), onClick = onOpenAudit)
                    TextActionButton(text = "记偏好".t(), onClick = onRemember)
                    TextActionButton(text = "删除".t(), color = colors.error, onClick = onRemove)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MimirSpacing.xl, bottom = MimirSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "审计步骤".t(),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    MonoLabel(text = "${steps.size} STEPS", color = colors.muted)
                }

                if (steps.isEmpty()) {
                    Text(
                        text = "该任务没有审计记录。通常意味着它尚未进入执行阶段就被取消。".t(),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        modifier = Modifier.padding(vertical = MimirSpacing.md),
                    )
                } else {
                    // 历史只给摘要：标题写"这一步做了什么"，动作类型退到元信息；
                    // 证据属于审计事件，不在这里二次展开。
                    steps.forEach { step ->
                        AuditStepRow(
                            step = step,
                            title = historyTitle(step),
                            showGeneration = false,
                        )
                    }
                }
            }
        }
        MimirHairline()
    }
}
