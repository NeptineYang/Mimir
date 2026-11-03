package com.neptine.mimir.ui.screen

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.ui.component.MimirComposer
import com.neptine.mimir.ui.component.MimirHairline
import com.neptine.mimir.ui.component.MimirPageHeader
import com.neptine.mimir.ui.component.MonoLabel
import com.neptine.mimir.ui.component.SectionBlock
import com.neptine.mimir.ui.component.StatusChip
import com.neptine.mimir.ui.component.TaskStateChip
import com.neptine.mimir.ui.component.TextActionButton
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirRadius
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.viewmodel.TaskRow
import com.neptine.mimir.ui.viewmodel.TaskViewModel
import com.neptine.mimir.ui.viewmodel.formatTime
import com.neptine.mimir.ui.voice.VoiceStatus
import com.neptine.mimir.ui.voice.rememberVoiceInputController

/**
 * 功能：任务页——展示进行中任务与进度，并用一句话创建新任务
 * 参数：onOpenTakeover 打开某任务的接管页
 * 返回：无
 *
 * 页面只保留三块：页眉、引擎状态、进行中任务；输入区在最下方。
 * 终态记录在历史页（02），这里只回答"现在有哪些任务还在跑或等人"。
 */
@Composable
fun TaskScreen(onOpenTakeover: (String) -> Unit) {
    val viewModel: TaskViewModel = hiltViewModel()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val engine by viewModel.status.collectAsStateWithLifecycle()
    val activeTasks by viewModel.activeTasks.collectAsStateWithLifecycle()
    val colors = LocalMimirColors.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var pendingStop by remember { mutableStateOf<TaskRow?>(null) }

    // 从设置页回来时重新读一遍权限与模型配置，避免顶部状态停在旧结论
    LaunchedEffect(Unit) { viewModel.refreshStatus() }

    // 按下麦克风时的已有文本；识别结果接在它后面，避免覆盖用户已经打好的内容。
    var draftBeforeVoice by remember { mutableStateOf("") }
    // pointerInput 的块只创建一次，直接读 input 会拿到首次组合的旧值，因此用 State 保鲜。
    val latestInput = rememberUpdatedState(input)

    val voice = rememberVoiceInputController { text ->
        viewModel.onInputChange(draftBeforeVoice + text)
    }

    // 授权结果只决定下一次按下能否收音；这里不自动开始录音，避免用户没按住就开麦。
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MimirSpacing.page),
    ) {
        MimirPageHeader(
            eyebrow = "MIMIR / 01",
            title = "任务",
            subtitle = "用一句话描述，剩下的交给 Mimir",
        )

        // 等宽标记只放 ASCII，中文说明单独一段：Space Mono 没有中文字形，
        // 混排并加字距会让这段字面明显散开，而且单行标签还会被省略号截断。
        Row(
            modifier = Modifier.padding(top = MimirSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        ) {
            MonoLabel(
                text = if (engine.online) "ONLINE" else "OFFLINE",
                color = if (engine.online) colors.deepGreen else colors.muted,
            )
            Text(
                text = engine.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        }

        Spacer(modifier = Modifier.height(MimirSpacing.xxl))

        MimirComposer(
            value = input,
            onValueChange = viewModel::onInputChange,
            placeholder = "例如：在美团点一份黄焖鸡米饭，少辣",
            trailing = {
                VoiceButton(
                    listening = voice.status == VoiceStatus.LISTENING,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                if (voice.hasPermission()) {
                                    draftBeforeVoice = latestInput.value
                                    voice.start()
                                    tryAwaitRelease()
                                    voice.stop()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                        )
                    },
                )
                Spacer(modifier = Modifier.size(MimirSpacing.sm))
                SendButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        if (voice.status == VoiceStatus.LISTENING) {
                            voice.stop()
                        }
                        // 发送后收起键盘并释放焦点：下面的"进行中"列表要立刻可见，
                        // 否则用户刚发的任务进度会被输入法挡住（真机实测）。
                        keyboard?.hide()
                        focusManager.clearFocus()
                        viewModel.send()
                    },
                )
            },
        )

        VoiceHint(status = voice.status, message = voice.message)

        // 进行中任务放在输入区之后：输入是新任务的主路径，必须始终可见；
        // 进度列表紧贴其下，通常第一屏仍能看到正在跑的那一条。
        val tasks = activeTasks
        if (tasks != null && tasks.isNotEmpty()) {
            SectionBlock(
                title = "进行中",
                modifier = Modifier.padding(top = MimirSpacing.xl),
                trailing = {
                    MonoLabel(text = "${tasks.size} ACTIVE", color = colors.muted)
                },
            ) {
                tasks.forEach { row ->
                    ActiveTaskRow(
                        row = row,
                        onTakeover = onOpenTakeover,
                        onForceStop = { pendingStop = it },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(MimirSpacing.huge))
    }

    pendingStop?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingStop = null },
            containerColor = colors.canvas,
            shape = RoundedCornerShape(MimirRadius.lg),
            title = {
                Text(
                    text = if (row.state == TaskState.CREATED) "取消这条任务？" else "强制打断这条任务？",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.ink,
                )
            },
            text = {
                Text(
                    text = if (row.state == TaskState.CREATED) {
                        "「${row.inputText}」还在排队，尚未开始执行。取消后它会以「已放弃」进入历史页。"
                    } else {
                        "「${row.inputText}」会立即停止，副屏会被销毁。已经提交给外部应用的操作不会回滚。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.slate,
                )
            },
            confirmButton = {
                TextActionButton(
                    text = if (row.state == TaskState.CREATED) "取消任务" else "强制打断",
                    color = colors.error,
                    onClick = {
                        viewModel.forceStop(row.taskId)
                        pendingStop = null
                    },
                )
            },
            dismissButton = {
                TextActionButton(text = "返回", onClick = { pendingStop = null })
            },
        )
    }
}

/**
 * 功能：渲染一条进行中任务
 * 参数：row 任务展示模型；onTakeover 打开接管页；onForceStop 请求强制打断
 * 返回：无
 *
 * 进行中任务卡要回答三个问题：跑到哪一步、卡在哪、还能不能立刻停下。
 * 进度分母取自任务自己的预算（spec），不是界面上的常量。
 */
@Composable
private fun ActiveTaskRow(
    row: TaskRow,
    onTakeover: (String) -> Unit,
    onForceStop: (TaskRow) -> Unit,
) {
    val colors = LocalMimirColors.current
    val state = row.state
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MimirSpacing.md),
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
                Text(
                    text = progressLine(row),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.slate,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.size(MimirSpacing.sm))
            if (state != null) {
                TaskStateChip(state = state)
            } else {
                StatusChip(text = row.rawState, fg = colors.slate)
            }
        }

        // 挂起原因只对 NEED_HUMAN 有值；它是"为什么停"的唯一解释，必须留在卡片里
        row.suspendReason?.takeIf { state == TaskState.NEED_HUMAN }?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.coral,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = MimirSpacing.xs),
            )
        }

        StepProgress(
            stepCount = row.stepCount,
            maxSteps = row.maxSteps,
            modifier = Modifier.padding(top = MimirSpacing.sm),
        )
        Text(
            text = "步 ${row.stepCount} / ${row.maxSteps} · 模型 ${row.budgetModelCalls} / ${row.maxModelCalls} · " +
                formatTime(row.createdAt),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.padding(top = MimirSpacing.xs),
        )

        Row(
            modifier = Modifier.padding(top = MimirSpacing.sm, bottom = MimirSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // EXECUTING 也允许进接管页：查看档只读，可以实时看 agent 在副屏上干活。
            // 演示与排障都需要这个入口，不该只有挂起时才能看到执行画面。
            if (state == TaskState.NEED_HUMAN || state == TaskState.EXECUTING) {
                TextActionButton(text = "接管", onClick = { onTakeover(row.taskId) })
            }
            TextActionButton(
                text = if (state == TaskState.CREATED) "取消" else "强制打断",
                color = colors.error,
                onClick = { onForceStop(row) },
            )
        }
        MimirHairline()
    }
}

/**
 * 功能：渲染步数进度条
 * 参数：stepCount 已执行步数；maxSteps 预算上限；modifier 修饰符
 * 返回：无
 *
 * 用 2dp 细线而不是 Material 进度条：与页面的细线语言一致，
 * 而且不需要额外的圆角与动画参数。
 */
@Composable
private fun StepProgress(
    stepCount: Int,
    maxSteps: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimirColors.current
    val fraction = if (maxSteps <= 0) 0f else (stepCount.toFloat() / maxSteps).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(colors.borderLight),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(2.dp)
                .background(colors.primary),
        )
    }
}

/**
 * 功能：把任务状态翻成一句进度说明
 * 参数：row 任务展示模型
 * 返回：面向用户的一句话
 */
private fun progressLine(row: TaskRow): String = when (row.state) {
    TaskState.CREATED -> "排队中，等待执行"
    TaskState.PLANNING -> "正在解析任务并生成计划"
    TaskState.EXECUTING -> row.currentSubGoal?.let { "正在：$it" } ?: "执行中"
    TaskState.NEED_HUMAN -> "等待你处理"
    TaskState.RECONCILING -> "正在核对未决写入"
    TaskState.VERIFYING -> "正在核验结果"
    else -> row.rawState
}

/**
 * 功能：渲染麦克风按钮（按住说话）
 * 参数：listening 是否正在收音；modifier 修饰符（含手势处理）
 * 返回：无
 *
 * 收音时反色填充，给出"正在听"的即时反馈；图标用 Canvas 画细线话筒，
 * 不引入任何图标库。
 */
@Composable
private fun VoiceButton(
    listening: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimirColors.current
    Canvas(
        modifier = modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (listening) colors.ink else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (listening) colors.ink else colors.borderLight,
                shape = CircleShape,
            )
            .padding(10.dp),
    ) {
        val tint = if (listening) colors.onDark else colors.ink
        val stroke = 1.3.dp.toPx()
        val w = size.width
        val h = size.height

        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.34f, h * 0.06f),
            size = Size(w * 0.32f, h * 0.46f),
            cornerRadius = CornerRadius(w * 0.16f, w * 0.16f),
            style = Stroke(width = stroke),
        )
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.14f, h * 0.28f),
            size = Size(w * 0.72f, h * 0.40f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.5f, h * 0.68f),
            end = Offset(w * 0.5f, h * 0.86f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.30f, h * 0.92f),
            end = Offset(w * 0.70f, h * 0.92f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 功能：渲染发送按钮
 * 参数：enabled 是否有内容可发；onClick 点击回调
 * 返回：无
 */
@Composable
private fun SendButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalMimirColors.current
    Text(
        text = "发送",
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) colors.onDark else colors.muted,
        modifier = Modifier
            .clip(RoundedCornerShape(MimirRadius.pill))
            .background(if (enabled) colors.primary else colors.ink.copy(alpha = 0.10f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

/**
 * 功能：渲染语音输入的状态提示
 * 参数：status 当前语音状态；message 错误或权限提示
 * 返回：无
 *
 * 只在"正在听"或"出问题"时出现，平时不占版面。
 */
@Composable
private fun VoiceHint(
    status: VoiceStatus,
    message: String?,
) {
    val colors = LocalMimirColors.current
    val text = when {
        status == VoiceStatus.LISTENING -> "LISTENING · 松开结束"
        message != null -> message
        else -> null
    } ?: return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MimirSpacing.sm),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoLabel(
            text = text,
            color = if (status == VoiceStatus.LISTENING) colors.slate else colors.coral,
            uppercase = status == VoiceStatus.LISTENING,
        )
    }
}
