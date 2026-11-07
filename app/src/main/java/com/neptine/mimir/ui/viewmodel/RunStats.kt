package com.neptine.mimir.ui.viewmodel

import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.data.db.dao.AuditStatsRow
import com.neptine.mimir.data.db.entity.TaskEntity
import java.util.Locale

/**
 * 历史页的运行统计（设计文档 §5.4）。
 *
 * 口径写死在这里，面板只负责展示：
 * - 严格成功率的分母排除 ABORTED（用户主动放弃不算 agent 失败），分子只认 DONE；
 * - 首次成功率再排除有过 RESUMED 的任务（中途被人工续过就不算一次跑通）；
 * - 步耗时只统计有计时的动作步（total_ms > 0），v6 之前的旧记录不参与均值；
 * - 记忆致错率与省步数需要人工归因 / A-B 回放，这里不给假数，面板如实标注。
 *
 * @property finishedTasks 参与成功率统计的任务数（已排除 ABORTED）
 * @property doneTasks 严格成功（DONE）的任务数
 * @property firstTryDoneTasks 没有人工续跑的 DONE 任务数
 * @property firstTryTasks 没有人工续跑的任务数
 * @property abortedTasks 用户主动放弃的任务数
 * @property stateCounts 终态分布，按 TaskState 定义顺序排序
 * @property avgSteps 平均执行步数
 * @property timedSteps 有计时数据的动作步数
 * @property avgTotalMs 平均单步总耗时
 * @property avgObserveMs 平均观察耗时
 * @property avgDecideMs 平均模型决策耗时
 * @property avgActMs 平均注入与判定耗时
 * @property actionSteps 有动作的审计行数（无效步占比的分母）
 * @property noEffectSteps 判定为 NO_EFFECT 的步数
 * @property unknownSteps 判定为 OUTCOME_UNKNOWN 的步数
 * @property totalSteps 全部执行步数（记忆命中率的分母）
 * @property memoryHitSteps 注入过配方的步数
 * @property timeouts 单步决策超时的次数
 * @property failureCategories 失败/受阻原因分类，按次数降序取前五
 */
data class RunStats(
    val finishedTasks: Int,
    val doneTasks: Int,
    val firstTryDoneTasks: Int,
    val firstTryTasks: Int,
    val abortedTasks: Int,
    val stateCounts: List<Pair<String, Int>>,
    val avgSteps: Double,
    val timedSteps: Int,
    val avgTotalMs: Long,
    val avgObserveMs: Long,
    val avgDecideMs: Long,
    val avgActMs: Long,
    val actionSteps: Int,
    val noEffectSteps: Int,
    val unknownSteps: Int,
    val totalSteps: Int,
    val memoryHitSteps: Int,
    val timeouts: Int,
    val failureCategories: List<Pair<String, Int>>,
) {

    /** 严格成功率：DONE / 非放弃任务；没有样本时为 null。 */
    val strictSuccessRate: Double? get() = ratio(doneTasks, finishedTasks)

    /** 首次成功率：没有被人工续过的 DONE / 没有被人工续过的任务。 */
    val firstTryRate: Double? get() = ratio(firstTryDoneTasks, firstTryTasks)

    /** 无效步占比：NO_EFFECT 的动作步 / 全部动作步。 */
    val noEffectRate: Double? get() = ratio(noEffectSteps, actionSteps)

    /** 记忆命中率：注入过配方的步数 / 全部执行步数。 */
    val memoryHitRate: Double? get() = ratio(memoryHitSteps, totalSteps)
}

/**
 * 功能：把任务与审计投影聚合成历史页概览
 * 参数：tasks 已结束任务（TaskEntity）；audits 全部审计投影（AuditStatsRow）
 * 返回：统计结果；没有可统计任务时各项为零
 */
fun computeRunStats(tasks: List<TaskEntity>, audits: List<AuditStatsRow>): RunStats {
    val stateOf = tasks.associate { it.taskId to parseState(it.state) }
    val resumed = audits.asSequence()
        .filter { it.outcome == OUTCOME_RESUMED }
        .map { it.taskId }
        .toSet()
    val counted = tasks.filter { stateOf[it.taskId] != null && stateOf[it.taskId] != TaskState.ABORTED }
    val firstTry = counted.filter { it.taskId !in resumed }
    val timed = audits.filter { it.totalMs > 0 }
    // 分母只认"真正执行并做出判定的动作"：PACKAGE_RECOVERED 这类系统自愈行也带 actionKind，
    // 但它没有走过后置条件判定，算进去会把无效步占比的分母撑大。
    val actionAudits = audits.filter { it.actionKind.isNotEmpty() && it.outcome in EFFECT_OUTCOMES }

    return RunStats(
        finishedTasks = counted.size,
        doneTasks = counted.count { stateOf[it.taskId] == TaskState.DONE },
        firstTryDoneTasks = firstTry.count { stateOf[it.taskId] == TaskState.DONE },
        firstTryTasks = firstTry.size,
        abortedTasks = tasks.count { stateOf[it.taskId] == TaskState.ABORTED },
        stateCounts = counted.groupingBy { stateOf[it.taskId]!!.name }
            .eachCount()
            .toList()
            .sortedBy { (state, _) -> parseState(state)?.ordinal ?: Int.MAX_VALUE },
        avgSteps = if (counted.isEmpty()) 0.0 else counted.sumOf { it.stepCount }.toDouble() / counted.size,
        timedSteps = timed.size,
        avgTotalMs = average(timed.map { it.totalMs }),
        avgObserveMs = average(timed.map { it.observeMs }),
        avgDecideMs = average(timed.map { it.decideMs }),
        avgActMs = average(timed.map { it.actMs }),
        actionSteps = actionAudits.size,
        noEffectSteps = audits.count { it.outcome == OUTCOME_NO_EFFECT },
        unknownSteps = audits.count { it.outcome == OUTCOME_UNKNOWN },
        // 分母不能用 task.stepCount：查看类任务只看不点，stepCount 不增，但每轮决策前
        // 都会检索记忆并写一条 MEMORY_INJECTED，用 stepCount 会算出 >100% 的命中率。
        // 改用"审计里出现过的步"（按任务 + 步序去重），与分子同源，必然同量级。
        totalSteps = audits.map { it.taskId to it.stepIndex }.distinct().size,
        // 同一步可能注入多次（软拒绝后重决策会再写一条 MEMORY_INJECTED），
        // 按"任务 + 步序"去重才是"有多少步用上了记忆"，否则会算出 >100% 的命中率。
        memoryHitSteps = audits.filter { !it.memoryUsed.isNullOrBlank() }
            .distinctBy { it.taskId to it.stepIndex }
            .size,
        timeouts = audits.count { it.outcome == OUTCOME_STEP_TIMEOUT },
        failureCategories = audits.mapNotNull { failureCategory(it.outcome) }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { (_, count) -> count }
            .take(5),
    )
}

/**
 * 功能：把比例格式化成百分比
 * 参数：rate 比例；null 表示没有样本
 * 返回：一位小数的百分比；无样本时为"—"
 */
fun formatRate(rate: Double?): String =
    if (rate == null) "—" else String.format(Locale.ROOT, "%.1f%%", rate * 100)

/**
 * 功能：把毫秒格式化成秒
 * 参数：millis 毫秒
 * 返回：一位小数的秒数
 */
fun formatSeconds(millis: Long): String =
    String.format(Locale.ROOT, "%.1f s", millis / 1000.0)

/**
 * 功能：解析任务状态字符串
 * 参数：raw 库里的状态字符串
 * 返回：解析出的状态；未知值或空串为 null
 */
private fun parseState(raw: String): TaskState? =
    runCatching { TaskState.valueOf(raw) }.getOrNull()

/**
 * 功能：把审计结论归到失败大类
 * 参数：outcome 审计结论
 * 返回：失败大类名；正常完成或与失败无关的结论为 null
 */
private fun failureCategory(outcome: String?): String? = when (outcome) {
    OUTCOME_STEP_TIMEOUT -> "模型超时"
    "REPLAN_REQUEST", "STUCK", "STEP_DONE_WEAK_REJECTED", "DUPLICATE_TEXT",
    "INVALID_TARGET", "BLIND_TAP" -> "模型与计划"
    "BLOCKED", "NEED_CONFIRM", "HUMAN_VERIFICATION", "PAYMENT_HANDOVER", "OVERLAY_LOOP" -> "安全与人机"
    "VISION_ONLY", "STALE", "DISPLAY_MISMATCH", "MAIN_SCREEN_CONFLICT", OUTCOME_UNKNOWN -> "页面与观测"
    OUTCOME_NO_EFFECT -> "动作无效"
    else -> null
}

/**
 * 功能：计算一列毫秒值的算术平均
 * 参数：values 毫秒值列表
 * 返回：平均值；空列表为 0
 */
private fun average(values: List<Long>): Long =
    if (values.isEmpty()) 0 else values.sum() / values.size

/**
 * 功能：计算比例并避免除零
 * 参数：numerator 分子；denominator 分母
 * 返回：比例；分母为 0 时为 null
 */
private fun ratio(numerator: Int, denominator: Int): Double? =
    if (denominator == 0) null else numerator.toDouble() / denominator

/** 用户点过"继续"的审计结论；统计首次成功率时用来排除。 */
private const val OUTCOME_RESUMED = "RESUMED"

/** 单步决策超时的审计结论。 */
private const val OUTCOME_STEP_TIMEOUT = "STEP_TIMEOUT"

/** 后置条件判定为页面无变化。 */
private const val OUTCOME_NO_EFFECT = "NO_EFFECT"

/** 后置条件无法判定。 */
private const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"

/** 动作真正执行并做出判定的三种结论；无效步占比只在这三类里统计。 */
private val EFFECT_OUTCOMES = setOf("CONFIRMED", OUTCOME_NO_EFFECT, OUTCOME_UNKNOWN)
