package com.neptine.mimir.core.contract

/**
 * 任务计划（设计文档 §1.3.2）。
 *
 * TaskSpec 回答"允许做什么"，TaskPlan 回答"按什么任务和步骤做"。它只在开局与受限重规划时
 * 由 Planner 生成；执行期 Orchestrator 按游标推进，模型不能增删子任务、不能跳步、
 * 也不能宣布整个计划完成。
 *
 * @property planId 计划标识，任务内唯一
 * @property version 计划版本；每次重规划递增，旧版本的动作立即失效
 * @property subtasks 子任务清单，按执行顺序
 * @property assumptions 规划依赖的前提；前提失效时允许请求受限重规划
 */
data class TaskPlan(
    val planId: String,
    val version: Int,
    val subtasks: List<PlanSubtask>,
    val assumptions: List<String> = emptyList(),
) {

    /** 计划内的步骤总数，用于 prompt 里的"第 n/N 步"。 */
    val stepCount: Int
        get() = subtasks.sumOf { it.steps.size }

    /**
     * 功能：取游标指向的步骤
     * 参数：cursor 计划游标
     * 返回：步骤；游标越界（计划已走完）时为 null
     */
    fun stepAt(cursor: PlanCursor): PlanStep? {
        val subtask = subtasks.getOrNull(cursor.subtaskIndex) ?: return null
        return subtask.steps.getOrNull(cursor.stepIndex)
    }

    /**
     * 功能：判断游标是否已越过最后一步
     * 参数：cursor 计划游标
     * 返回：全部步骤已走完为 true
     */
    fun isExhausted(cursor: PlanCursor): Boolean = stepAt(cursor) == null

    /**
     * 功能：把游标推进到下一步
     * 参数：cursor 当前游标
     * 返回：下一个游标；已在最后一步时返回越界游标（subtaskIndex 等于子任务数）
     */
    fun advance(cursor: PlanCursor): PlanCursor {
        val subtask = subtasks.getOrNull(cursor.subtaskIndex)
            ?: return PlanCursor(version, subtasks.size, 0)
        if (cursor.stepIndex + 1 < subtask.steps.size) {
            return PlanCursor(version, cursor.subtaskIndex, cursor.stepIndex + 1)
        }
        return PlanCursor(version, cursor.subtaskIndex + 1, 0)
    }

    /**
     * 功能：取游标的可读标签，写入任务行的"当前步骤"与审计
     * 参数：cursor 计划游标
     * 返回：形如 `s1-2 打开我的设备`；游标越界时为"计划已完成"
     */
    fun labelOf(cursor: PlanCursor): String {
        val step = stepAt(cursor) ?: return "计划已完成"
        return "${step.stepId} ${step.intent}"
    }

    /**
     * 功能：取游标在计划中的位置描述，写入 prompt 让模型知道进度
     * 参数：cursor 计划游标
     * 返回：形如 `子任务 1/3 · 步骤 2/4`；游标越界时为空串
     */
    fun positionOf(cursor: PlanCursor): String {
        val subtask = subtasks.getOrNull(cursor.subtaskIndex) ?: return ""
        if (subtask.steps.getOrNull(cursor.stepIndex) == null) return ""
        return "子任务 ${cursor.subtaskIndex + 1}/${subtasks.size} · " +
            "步骤 ${cursor.stepIndex + 1}/${subtask.steps.size}"
    }
}

/**
 * 子任务（设计文档 §1.3.2）。
 *
 * @property subtaskId 子任务标识，形如 `s1`
 * @property targetPackage 目标包名，必须属于 TaskSpec.allowedPackages
 * @property objective 子任务目标
 * @property dependencies 依赖的子任务标识；执行期不强制，仅供规划自检
 * @property steps 有序步骤
 */
data class PlanSubtask(
    val subtaskId: String,
    val targetPackage: String,
    val objective: String,
    val dependencies: List<String> = emptyList(),
    val steps: List<PlanStep>,
)

/**
 * 计划步骤（设计文档 §1.3.2）。
 *
 * 一步可能包含多个原子 GUI 动作，全部计入 TaskSpec 预算。
 *
 * @property stepId 步骤标识，形如 `s1-2`
 * @property intent 这一步要做什么，一句话
 * @property parameters 用到的 TaskSpec 参数键名，不是参数值
 * @property precondition 进入这一步前页面上应满足的条件
 * @property postcondition 这一步完成后页面上才新出现的文本片段；为空表示规划器给不出可核对证据
 */
data class PlanStep(
    val stepId: String,
    val intent: String,
    val parameters: List<String> = emptyList(),
    val precondition: String = "",
    val postcondition: List<String> = emptyList(),
)

/**
 * 计划游标（设计文档 §1.3.2）。
 *
 * 只有当前步骤的后置条件成立才推进；游标与 plan_version 一起落库，进程重启后据此恢复
 * "执行到哪"，而不是靠模型记住第几步。
 *
 * @property planVersion 所属计划版本
 * @property subtaskIndex 子任务下标，从 0 起
 * @property stepIndex 子任务内的步骤下标，从 0 起
 */
data class PlanCursor(
    val planVersion: Int,
    val subtaskIndex: Int,
    val stepIndex: Int,
) {
    companion object {

        /**
         * 功能：生成计划的起始游标
         * 参数：plan 计划
         * 返回：指向第一个子任务第一步的游标
         */
        fun startOf(plan: TaskPlan): PlanCursor = PlanCursor(plan.version, 0, 0)
    }
}
