package com.neptine.mimir.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neptine.mimir.core.contract.RiskLevel
import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.ui.component.DarkBand
import com.neptine.mimir.ui.component.EmptyState
import com.neptine.mimir.ui.component.KeyValueRow
import com.neptine.mimir.ui.component.MonoLabel
import com.neptine.mimir.ui.component.SectionBlock
import com.neptine.mimir.ui.component.TaskStateChip
import com.neptine.mimir.ui.component.TextActionButton
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirRadius
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.viewmodel.AuditRow
import com.neptine.mimir.ui.viewmodel.PreviewFrame
import com.neptine.mimir.ui.viewmodel.TakeoverViewModel

/** 副屏原生分辨率的宽高比；画面框按它比例还原，避免预览变形。 */
private const val SECONDARY_DISPLAY_RATIO = 720f / 1600f

/** 判定"这一步可以继续一次"的审计处置值，与 Orchestrator 写入的一致。 */
private const val DECISION_NEED_CONFIRM = "NEED_CONFIRM"

/** 结果未知、必须先只读核对的审计结果值，与 Orchestrator 写入的一致。 */
private val UNRESOLVED_OUTCOMES = setOf("OUTCOME_UNKNOWN", "UNRESOLVED_WRITE")

/** 挂起任务的恢复方式。 */
private enum class ResumeMode {
    /** 不提供继续：仅本人步骤。 */
    NONE,

    /** 用户核对未决写入后继续：不重放动作，Agent 重新观测。 */
    RECONCILE,

    /** 签发一次性授权后继续：L2A 可授权副作用。 */
    CONFIRM_ONCE,

    /** 直接继续：冲突、前台不符、验证未通过等可自行恢复的挂起。 */
    PLAIN,
}

/**
 * 功能：接管页——显示副屏实时画面并处置挂起任务（全屏，不挂底栏）
 * 参数：onBack 返回上一页
 * 返回：无
 *
 * 画面由 DisplayPort 提供（PixelCopy 主路径、无障碍 takeScreenshot 兜底），
 * 只在任务挂起且有活动副屏时轮询。没有画面就明确写"无信号"，
 * 不拿模拟画面冒充 agent 正在跑。
 */
@Composable
fun TakeoverScreen(onBack: () -> Unit) {
    val viewModel: TakeoverViewModel = hiltViewModel()
    val task by viewModel.task.collectAsStateWithLifecycle()
    val lastStep by viewModel.lastStep.collectAsStateWithLifecycle()
    val previewFrame by viewModel.preview.collectAsStateWithLifecycle()
    val takeover by viewModel.takeover.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val replayIndex by viewModel.replayIndex.collectAsStateWithLifecycle()
    val replayFrame by viewModel.replayFrame.collectAsStateWithLifecycle()
    val colors = LocalMimirColors.current

    // 页面可见期间才轮询取帧；离开页面立即停，避免持续占用 binder 与解码资源
    DisposableEffect(viewModel.taskId) {
        viewModel.startPreview()
        onDispose {
            viewModel.exitTakeover()
            viewModel.stopPreview()
        }
    }

    // 任务恢复或被放弃时收回触控权并退出回放，避免停在上一个任务的旧画面
    LaunchedEffect(task?.state) {
        if (task?.state != TaskState.NEED_HUMAN) {
            viewModel.exitReplay()
            viewModel.exitTakeover()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MimirSpacing.page),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MimirSpacing.sm, bottom = MimirSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextActionButton(
                text = "← 返回",
                onClick = {
                    viewModel.exitTakeover()
                    onBack()
                },
            )
            Spacer(modifier = Modifier.weight(1f))
            MonoLabel(
                text = viewModel.taskId?.take(8) ?: "NO TASK",
                color = colors.slate,
            )
        }

        // 处置按钮固定在预览**上方**：处置档下预览区的触控会转发给副屏，按钮若混在
        // 滚动区里，位置会随预览高度浮动，自动化与真人都可能点进预览区把事件发到副屏
        // （真机实测：盲点旧坐标实际点到了 DeepSeek）。放在最上面则位置恒定、永远可见。
        task?.takeIf { it.state == TaskState.NEED_HUMAN }?.let {
            SectionBlock(title = "处置") {
                PendingActions(
                    mode = resumeModeOf(lastStep),
                    onContinue = viewModel::continueTask,
                    onContinueAfterCheck = viewModel::continueAfterManualCheck,
                    onAbort = viewModel::abort,
                )
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            DarkBand(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .aspectRatio(SECONDARY_DISPLAY_RATIO),
                container = colors.ink,
            ) {
                val live = previewFrame
                // 回放只看历史截图：该步没有留存就明确显示"未留存"，不能拿当前实时画面冒充"当时的样子"
                val shown = if (replayIndex != null) replayFrame else live?.bitmap
                Box(modifier = Modifier.fillMaxSize()) {
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = if (replayIndex != null) "回放画面" else "副屏实时画面",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(
                                    takeover,
                                    task?.state,
                                    replayIndex,
                                    shown.width,
                                    shown.height,
                                ) {
                                    // 只有接管档才接收触控：查看档与回放都必须保持只读
                                    if (!takeover || task?.state != TaskState.NEED_HUMAN) {
                                        return@pointerInput
                                    }
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val path = mutableListOf<Pair<Float, Float>>()
                                        down.position.toFraction(
                                            size, shown.width, shown.height,
                                        )?.let { path += it }

                                        var moved = false
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes
                                                .firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break
                                            if (change.positionChanged()) {
                                                if (!moved &&
                                                    (change.position - down.position)
                                                        .getDistance() > viewConfiguration.touchSlop
                                                ) {
                                                    moved = true
                                                }
                                                if (moved) {
                                                    change.position.toFraction(
                                                        size, shown.width, shown.height,
                                                    )?.let { path += it }
                                                }
                                            }
                                            change.consume()
                                        }

                                        if (moved && path.size >= 2) {
                                            viewModel.forwardSwipe(path)
                                        } else {
                                            down.position.toFraction(
                                                size, shown.width, shown.height,
                                            )?.let { viewModel.forwardTap(it.first, it.second) }
                                        }
                                    }
                                },
                        )
                        val badge = when {
                            takeover -> "接管中 · 触控已转发" to colors.coral
                            replayIndex != null -> "回放 · 第 ${steps.getOrNull(replayIndex!!)?.stepIndex ?: 0} 步" to colors.actionBlue
                            paused -> "已暂停 · 保留最后一帧" to colors.slate
                            else -> null to colors.slate
                        }
                        if (badge.first != null) {
                            Text(
                                text = badge.first.orEmpty(),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onDark,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(MimirSpacing.sm)
                                    .background(
                                        color = badge.second.copy(alpha = 0.85f),
                                        shape = RoundedCornerShape(MimirRadius.pill),
                                    )
                                    .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.xs),
                            )
                        }
                    } else if (replayIndex != null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                            ) {
                                MonoLabel(text = "NO FRAME", color = colors.onDark.copy(alpha = 0.4f))
                                Text(
                                    text = "该步未留存画面",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = colors.onDark,
                                )
                                Text(
                                    text = "截图在动作执行后写入；这条记录早于截图留存，或该步没有可用的前后帧。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onDark.copy(alpha = 0.62f),
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                            ) {
                                MonoLabel(text = "NO SIGNAL", color = colors.onDark.copy(alpha = 0.4f))
                                Text(
                                    text = "暂无副屏画面",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = colors.onDark,
                                )
                                Text(
                                    text = "任务挂起期间副屏会保留并在这里显示；任务未挂起或副屏已销毁时没有画面。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onDark.copy(alpha = 0.62f),
                                )
                            }
                        }
                    }
                }
            }
        }

        val replayAt = replayIndex
        val replayStep = replayAt?.let { steps.getOrNull(it) }
        val mode = when {
            replayAt != null -> "REPLAY"
            takeover -> "TAKEOVER"
            paused -> "PAUSED"
            previewFrame != null -> "LIVE"
            else -> "STANDBY"
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MimirSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoLabel(
                text = mode,
                color = if (mode == "LIVE" || mode == "REPLAY") colors.actionBlue else colors.muted,
            )
            Text(
                text = when {
                    replayStep != null ->
                        "第 ${replayStep.stepIndex} 步 · ${replayStep.actionKind.ifEmpty { "无动作" }} · " +
                            (replayStep.outcome ?: replayStep.decision)
                    takeover -> "接管中 · 画面来自副屏，触控已转发"
                    paused -> "画面已暂停 · 保留最后一帧，不继续取帧"
                    else -> previewLine(previewFrame, task?.state)
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }

        if (!takeover) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MimirSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (replayAt == null) {
                    TextActionButton(
                        text = if (paused) "继续画面" else "暂停画面",
                        onClick = viewModel::togglePause,
                        enabled = previewFrame != null,
                    )
                    TextActionButton(
                        text = "单步回放",
                        onClick = viewModel::startReplay,
                        enabled = steps.isNotEmpty(),
                    )
                } else {
                    TextActionButton(
                        text = "上一步",
                        onClick = viewModel::replayPrevious,
                        enabled = replayAt > 0,
                    )
                    TextActionButton(
                        text = "下一步",
                        onClick = viewModel::replayNext,
                        enabled = replayAt < steps.lastIndex,
                    )
                    TextActionButton(
                        text = "退出回放",
                        onClick = viewModel::exitReplay,
                    )
                }
            }
            Text(
                text = if (replayAt == null) {
                    "查看档只读：暂停只冻结画面，不改变任务状态；回放按审计时间轴逐步查看当时的画面与结果。"
                } else {
                    "回放只读：不注入动作、不改变任务；需要自己操作时先退出回放再进入接管档。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(top = MimirSpacing.xs),
            )
        }

        replayStep?.let { step ->
            SectionBlock(title = "单步回放", modifier = Modifier.padding(top = MimirSpacing.xl)) {
                KeyValueRow(key = "步序", value = "第 ${step.stepIndex} 步")
                KeyValueRow(key = "动作", value = actionLine(step.actionKind, step.targetText))
                KeyValueRow(key = "结果", value = step.outcome ?: "—", mono = true)
                KeyValueRow(key = "处置", value = step.decision, mono = true)
                KeyValueRow(key = "说明", value = step.reviewReason ?: "—")
                KeyValueRow(
                    key = "画面",
                    value = when {
                        replayFrame != null -> "已留存"
                        step.screenshotAfter != null || step.screenshotBefore != null -> "读取失败"
                        else -> "该步未留存截图"
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(MimirSpacing.xxl))

        val current = task
        if (current == null) {
            EmptyState(
                title = "没有可接管的任务",
                hint = "接管页在任务进入 NEED_HUMAN 时使用，需要一个任务标识。当前没有挂起的任务。",
                action = { TextActionButton(text = "去队列看看", onClick = onBack) },
            )
        } else {
            SectionBlock(title = "任务") {
                KeyValueRow(key = "原始输入", value = current.inputText)
                KeyValueRow(key = "当前步骤", value = current.currentSubGoal ?: "—")
                KeyValueRow(key = "步数", value = "${current.stepCount} / 40")
                KeyValueRow(key = "挂起原因", value = current.suspendReason ?: "—")
                Row(
                    modifier = Modifier.padding(top = MimirSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    current.state?.let { TaskStateChip(state = it) }
                }

                val step = lastStep
                if (step != null) {
                    KeyValueRow(key = "最近动作", value = actionLine(step.actionKind, step.targetText))
                    KeyValueRow(key = "风险等级", value = step.riskLevel?.name ?: "—")
                    KeyValueRow(key = "处置", value = step.decision)
                }
            }
        }

        SectionBlock(title = "档位", modifier = Modifier.padding(top = MimirSpacing.xxl)) {
            val canEnterTakeover = previewFrame != null && task?.state == TaskState.NEED_HUMAN
            Row(
                modifier = Modifier.padding(top = MimirSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextActionButton(
                    text = if (takeover) "接管档" else "查看档",
                    onClick = {},
                    enabled = false,
                )
                TextActionButton(
                    text = if (takeover) "交还 agent" else "接管操作",
                    onClick = if (takeover) viewModel::handBack else viewModel::enterTakeover,
                    enabled = takeover || canEnterTakeover,
                )
            }
            Text(
                text = when {
                    takeover ->
                        "触控已转发到副屏。操作完成后点「交还 agent」，Agent 会重新观测再继续。"
                    !canEnterTakeover ->
                        "查看档为只读预览；当前没有可用的副屏画面，无法进入接管档。"
                    else ->
                        "查看档只读预览，不取得副屏触控权；需要自己操作 App 时进入接管档。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.slate,
                modifier = Modifier.padding(top = MimirSpacing.sm),
            )
        }

        Spacer(modifier = Modifier.height(MimirSpacing.huge))
    }
}

/**
 * 功能：把画面触点换算成归一化坐标
 * 参数：size 控件尺寸；bitmapWidth/bitmapHeight 画面像素尺寸
 * 返回：归一化坐标；触点落在留白上时为 null
 *
 * 预览用 ContentScale.Fit，留白不属于副屏；落在留白上的触点直接丢弃，
 * 否则会把"点黑边"误映射成点击屏幕边缘。
 */
private fun Offset.toFraction(
    size: IntSize,
    bitmapWidth: Int,
    bitmapHeight: Int,
): Pair<Float, Float>? {
    if (size.width <= 0 || size.height <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return null
    val scale = minOf(size.width.toFloat() / bitmapWidth, size.height.toFloat() / bitmapHeight)
    val drawnWidth = bitmapWidth * scale
    val drawnHeight = bitmapHeight * scale
    val left = (size.width - drawnWidth) / 2f
    val top = (size.height - drawnHeight) / 2f
    val x = (this.x - left) / drawnWidth
    val y = (this.y - top) / drawnHeight
    if (x !in 0f..1f || y !in 0f..1f) return null
    return x to y
}

/**
 * 功能：拼出画面状态行
 * 参数：frame 当前预览帧，可为空；state 任务状态，可为空
 * 返回：一句说明
 *
 * 实时帧率不承诺 15–30fps：PixelCopy 单帧约 150–250ms，实际受取帧耗时限制。
 */
private fun previewLine(frame: PreviewFrame?, state: TaskState?): String = when {
    frame != null -> "实时画面 · ${frame.fps} fps · ${frame.source.name}"
    state == TaskState.NEED_HUMAN -> "副屏已保留，等待第一帧…"
    state == null -> "没有可预览的任务"
    else -> "任务不在挂起状态，预览暂停"
}

/**
 * 功能：按最近一步判定挂起任务的恢复方式
 * 参数：step 最近一步审计，可为空
 * 返回：恢复方式
 *
 * L2B 不提供继续；未决写入提供「我已核对，继续」；L2A 待确认签发一次性授权；其余挂起直接恢复。
 * 没有审计时无从判断"停在哪一步"，只提供放弃。
 */
private fun resumeModeOf(step: AuditRow?): ResumeMode = when {
    step == null -> ResumeMode.NONE
    step.riskLevel == RiskLevel.L2B -> ResumeMode.NONE
    step.outcome in UNRESOLVED_OUTCOMES -> ResumeMode.RECONCILE
    step.riskLevel == RiskLevel.L2A && step.decision == DECISION_NEED_CONFIRM -> ResumeMode.CONFIRM_ONCE
    else -> ResumeMode.PLAIN
}

/**
 * 功能：渲染挂起任务的处置按钮
 * 参数：mode 恢复方式；onContinue / onContinueAfterCheck / onAbort 回调
 * 返回：无
 */
@Composable
private fun PendingActions(
    mode: ResumeMode,
    onContinue: () -> Unit,
    onContinueAfterCheck: () -> Unit,
    onAbort: () -> Unit,
) {
    val colors = LocalMimirColors.current
    Row(
        modifier = Modifier.padding(top = MimirSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (mode) {
            ResumeMode.CONFIRM_ONCE -> TextActionButton(text = "继续一次", onClick = onContinue)
            ResumeMode.PLAIN -> TextActionButton(text = "继续", onClick = onContinue)
            ResumeMode.RECONCILE -> TextActionButton(text = "我已核对，继续", onClick = onContinueAfterCheck)
            ResumeMode.NONE -> Unit
        }
        TextActionButton(text = "放弃", onClick = onAbort, color = colors.error)
    }
    Text(
        text = when (mode) {
            ResumeMode.CONFIRM_ONCE ->
                "「继续一次」只授权刚被拦下的这一步；页面或参数变化后会重新请求确认。"
            ResumeMode.PLAIN ->
                "「继续」不会签发任何授权；Agent 重新观测后再决策。"
            ResumeMode.RECONCILE ->
                "「我已核对，继续」表示你已确认刚才的动作是否需要重放。Agent 不会重放它，只会重新观测当前页面后继续。"
            ResumeMode.NONE ->
                "这类挂起不能由 Agent 继续：仅本人步骤，或存在未决写入需要先只读核对。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = colors.muted,
        modifier = Modifier.padding(top = MimirSpacing.sm),
    )
}

/**
 * 功能：拼出"动作类型 + 目标文本"一行
 * 参数：kind 动作类型；target 目标文本，可为空
 * 返回：展示用的一行文字
 */
private fun actionLine(kind: String, target: String?): String =
    listOfNotNull(kind.takeIf { it.isNotEmpty() }, target).joinToString(" ").ifEmpty { "—" }
