package com.neptine.mimir.ui.viewmodel

import androidx.compose.ui.graphics.ImageBitmap
import com.neptine.mimir.agent.TaskSpecCodec
import com.neptine.mimir.core.contract.RiskLevel
import com.neptine.mimir.core.contract.TaskBudget
import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.data.db.entity.AuditEntity
import com.neptine.mimir.data.db.entity.TaskEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 任务在列表里的展示模型；Composable 只吃它，不直接吃 Room 实体。 */
data class TaskRow(
    /** 任务标识。 */
    val taskId: String,
    /** 原始自然语言输入。 */
    val inputText: String,
    /** 解析出的任务状态；库里存的是字符串，解析失败为 null。 */
    val state: TaskState?,
    /** 库里的原始状态字符串，解析失败时用于如实回显。 */
    val rawState: String,
    /** 目标应用包名；未解析时为空串。 */
    val packageName: String,
    /** 当前子目标；规划前为空。 */
    val currentSubGoal: String?,
    /** 挂起原因；仅 NEED_HUMAN 有值。 */
    val suspendReason: String?,
    /** 已执行步数。 */
    val stepCount: Int,
    /** 步数预算上限；用于展示进度分母。 */
    val maxSteps: Int,
    /** 已消耗模型调用次数。 */
    val budgetModelCalls: Int,
    /** 模型调用预算上限。 */
    val maxModelCalls: Int,
    /** 创建时间戳（毫秒）。 */
    val createdAt: Long,
    /** 结束时间戳（毫秒）；未结束为空。 */
    val finishedAt: Long?,
    /** 最近一步的动作后画面；用于任务卡缩略图。 */
    val thumb: ImageBitmap? = null,
)

/** 审计步骤的展示模型；只保留回放页真正渲染的字段。 */
data class AuditRow(
    /** 行主键。 */
    val id: Long,
    /** 步序。 */
    val stepIndex: Int,
    /** 时间戳（毫秒）。 */
    val timestamp: Long,
    /** 当时子目标。 */
    val subGoal: String,
    /** 动作类型。 */
    val actionKind: String,
    /** 动作参数摘要。 */
    val paramsDigest: String,
    /** 模型原始输出。 */
    val modelOutput: String,
    /** 注入的系统返回值；仅代表系统接收。 */
    val systemReturnValue: Boolean?,
    /** 目标文本（树文本或 OCR 文本）。 */
    val targetText: String?,
    /** 目标指纹。 */
    val targetFingerprint: String?,
    /** 幂等分类。 */
    val idempotency: String,
    /** 写入意图状态。 */
    val intentStatus: String?,
    /** 动作结果三态。 */
    val outcome: String?,
    /** 一次性授权标识。 */
    val grantId: String?,
    /** 风险等级；解析失败为 null。 */
    val riskLevel: RiskLevel?,
    /** 处置结论。 */
    val decision: String,
    /** 复核理由。 */
    val reviewReason: String?,
    /** 验证证据。 */
    val verificationEvidence: String?,
    /** 本步图像 token 估算。 */
    val imageTokens: Int,
    /** 前帧路径。 */
    val screenshotBefore: String?,
    /** 后帧路径。 */
    val screenshotAfter: String?,
    /** 副屏代次。 */
    val displayGeneration: Long,
    /** 本步合计耗时（毫秒）；0 表示没有计时。 */
    val totalMs: Long = 0,
    /** 观察段耗时（毫秒）。 */
    val observeMs: Long = 0,
    /** 决策段耗时（毫秒）。 */
    val decideMs: Long = 0,
    /** 注入与判定段耗时（毫秒）。 */
    val actMs: Long = 0,
)

/** 时间格式：月-日 时:分；跨天时也不会太长，审计列表能一屏放下。 */
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 功能：把毫秒时间戳格式化为 MM-dd HH:mm
 * 参数：millis 时间戳（毫秒）
 * 返回：本地时区下的短时间串
 */
fun formatTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)

/**
 * 功能：把已用时长格式化成一句话
 * 参数：millis 时长（毫秒）
 * 返回：秒 / 分秒 / 时分 三档短句
 */
fun formatElapsed(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds} 秒"
        seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分"
    }
}

/**
 * 功能：安全解析任务状态
 * 参数：raw 库里的状态字符串
 * 返回：对应的枚举值；无法识别时为 null
 *
 * 不抛异常也不兜底成某个状态：无法识别的值必须如实暴露，否则会把数据损坏伪装成正常。
 */
fun parseTaskState(raw: String): TaskState? =
    TaskState.entries.firstOrNull { it.name == raw }

/**
 * 功能：安全解析风险等级
 * 参数：raw 库里的等级字符串
 * 返回：对应的枚举值；无法识别时为 null
 */
fun parseRiskLevel(raw: String): RiskLevel? =
    RiskLevel.entries.firstOrNull { it.name == raw }

/**
 * 功能：把任务实体转成展示模型
 * 参数：thumb 最近一步的画面缩略图，由调用方解码后传入
 * 返回：TaskRow
 */
fun TaskEntity.toRow(thumb: ImageBitmap? = null): TaskRow {
    val budget = budgetOf(this)
    return TaskRow(
        taskId = taskId,
        inputText = inputText,
        state = parseTaskState(state),
        rawState = state,
        packageName = packageName,
        currentSubGoal = currentSubGoal,
        suspendReason = suspendReason,
        stepCount = stepCount,
        maxSteps = budget.maxSteps,
        budgetModelCalls = budgetModelCalls,
        maxModelCalls = budget.maxModelCalls,
        createdAt = createdAt,
        finishedAt = finishedAt,
        thumb = thumb,
    )
}

/**
 * 功能：取任务行里的预算
 * 参数：task 任务实体
 * 返回：TaskSpec 中的预算；内容为空或解析失败时为默认预算
 *
 * 进度条的分母必须来自任务自己的 spec：预算在创建时就固定了，
 * 拿界面上的常量会让"步 12 / 40"在被改写过的预算下显示成错误的比例。
 */
private fun budgetOf(task: TaskEntity): TaskBudget =
    runCatching { TaskSpecCodec.decode(task.specJson).budget }.getOrDefault(TaskBudget())

/**
 * 功能：判断这条历史任务能否"再打开结果"
 * 参数：无（扩展函数）
 * 返回：可能还留有结果页时为 true
 *
 * 只对三种"做到过"的终态开放：DONE / DONE_READBACK / COMPLETED_UNVERIFIED。
 * 失败与放弃没有可交付的页面，给入口只会打开一个与任务无关的 App 首页。
 */
fun TaskRow.canReopenResult(): Boolean =
    packageName.isNotEmpty() && state in setOf(
        TaskState.DONE,
        TaskState.DONE_READBACK,
        TaskState.COMPLETED_UNVERIFIED,
    )

/**
 * 功能：把审计实体转成展示模型
 * 参数：无（扩展函数）
 * 返回：AuditRow
 */
fun AuditEntity.toRow(): AuditRow = AuditRow(
    id = id,
    stepIndex = stepIndex,
    timestamp = timestamp,
    subGoal = subGoal,
    actionKind = actionKind,
    paramsDigest = paramsDigest,
    modelOutput = modelOutput,
    systemReturnValue = systemReturnValue,
    targetText = targetText,
    targetFingerprint = targetFingerprint,
    idempotency = idempotency,
    intentStatus = intentStatus,
    outcome = outcome,
    grantId = grantId,
    riskLevel = parseRiskLevel(riskLevel),
    decision = decision,
    reviewReason = reviewReason,
    verificationEvidence = verificationEvidence,
    imageTokens = imageTokens,
    screenshotBefore = screenshotBefore,
    screenshotAfter = screenshotAfter,
    displayGeneration = displayGeneration,
    totalMs = totalMs,
    observeMs = observeMs,
    decideMs = decideMs,
    actMs = actMs,
)
