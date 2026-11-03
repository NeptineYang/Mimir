package com.neptine.mimir.core.contract

/**
 * 任务状态机（设计文档 §1.4）。
 *
 * 十个状态与文档状态图一一对应；转移合法性由 [allowedTransitions] 表达，
 * 避免各调用点各写一份 if 判断而后逐渐分叉。
 */
enum class TaskState {
    CREATED,
    PLANNING,
    EXECUTING,
    RECONCILING,
    NEED_HUMAN,
    VERIFYING,
    DONE,

    /**
     * 完成，且用"同一页面二次读取"做了复核（没有为这个 App 注册专用复验路线）。
     *
     * 与 [DONE] 的区别只在证据强度：DONE 是两条不同路径各读一次，
     * DONE_READBACK 是同一条路径隔一段时间再读一次。它挡得住"单帧幻读"和模型自述，
     * 挡不住"这页本身是缓存的成功页"——所以单独一个状态，不和 DONE 混为一谈。
     */
    DONE_READBACK,

    COMPLETED_UNVERIFIED,

    /** 未复验的结果由用户在结果通知上点「我已核对」确认；只影响终态与统计，不写记忆。 */
    VERIFIED_BY_HUMAN,
    FAILED,
    ABORTED,
    ;

    /** 是否为终态；终态退出前需统一释放 IME、副屏、取帧 surface 与前台服务。 */
    val isTerminal: Boolean
        get() = this == DONE || this == DONE_READBACK || this == COMPLETED_UNVERIFIED ||
            this == VERIFIED_BY_HUMAN || this == FAILED || this == ABORTED
}

/**
 * 功能：列出某状态允许迁移到的目标状态
 * 参数：state 当前状态
 * 返回：允许的目标状态集合；终态返回空集
 *
 * 注意 EXECUTING → EXECUTING 是合法的（单步循环），因此结果里保留自身。
 */
fun allowedTransitions(state: TaskState): Set<TaskState> = when (state) {
    // ABORTED 面向"用户强制打断"：排队的任务还没开始跑，也必须能立刻结束，而不是只能删库。
    TaskState.CREATED -> setOf(TaskState.PLANNING, TaskState.ABORTED)
    // PLANNING 也要能转人工：拉起前要查"用户是否正在主屏用目标 App"，命中就必须暂停等用户离开，
    // 而那一刻计划还没生成（计划要靠拉起后的首帧）。只给 FAILED 会把可恢复的暂停变成任务死亡。
    // ABORTED 是用户强制打断：任何还没结束的任务都必须在界面上可以被立刻停下。
    TaskState.PLANNING -> setOf(
        TaskState.EXECUTING,
        TaskState.NEED_HUMAN,
        TaskState.FAILED,
        TaskState.ABORTED,
    )
    TaskState.EXECUTING -> setOf(
        TaskState.EXECUTING,
        TaskState.NEED_HUMAN,
        TaskState.RECONCILING,
        TaskState.VERIFYING,
        TaskState.FAILED,
        TaskState.ABORTED,
    )
    TaskState.RECONCILING -> setOf(
        TaskState.VERIFYING,
        TaskState.EXECUTING,
        TaskState.NEED_HUMAN,
        TaskState.ABORTED,
    )
    TaskState.NEED_HUMAN -> setOf(TaskState.EXECUTING, TaskState.ABORTED)
    TaskState.VERIFYING -> setOf(
        TaskState.DONE,
        TaskState.DONE_READBACK,
        TaskState.COMPLETED_UNVERIFIED,
        TaskState.EXECUTING,
        TaskState.NEED_HUMAN,
        TaskState.FAILED,
        TaskState.ABORTED,
    )
    // 未复验的结果允许人工核对一次：这不是"重新跑任务"，只是把终态从
    // "机器没验成"改成"人看过了"，因此保留这条单向边，且不再向外迁移。
    TaskState.COMPLETED_UNVERIFIED -> setOf(TaskState.VERIFIED_BY_HUMAN)
    TaskState.DONE,
    TaskState.DONE_READBACK,
    TaskState.VERIFIED_BY_HUMAN,
    TaskState.FAILED,
    TaskState.ABORTED,
    -> emptySet()
}
