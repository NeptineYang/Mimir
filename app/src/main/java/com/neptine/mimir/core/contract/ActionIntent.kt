package com.neptine.mimir.core.contract

/**
 * 写入意图日志（设计文档 §1.3.1）。
 *
 * 生命周期与判定结果分开表达：status 记录"动作走到哪一步"，
 * outcome 记录"结果是否已确认"。进程若死在 INJECTING，
 * 留下的是 status=INJECTING 且 outcome=null 的记录——这就是"未决写入"，只能只读核对，禁止重放。
 */
data class ActionIntent(
    /** 动作唯一标识。 */
    val actionId: String,

    /** 归属任务。 */
    val taskId: String,

    /** 任务内步序，与审计记录对齐。 */
    val stepIndex: Int,

    /** 决策依据的观察编号。 */
    val observationId: String,

    /** 注入前复验得到的实际 displayId。 */
    val displayId: Int,

    /** 副屏代次，与 displayId 一起复验。 */
    val displayGeneration: Long,

    /** 动作类型。 */
    val actionKind: ActionKind,

    /** 目标身份；复验时与当前观察比较。 */
    val targetFingerprint: TargetFingerprint?,

    /** 风险分级。 */
    val riskLevel: RiskLevel,

    /** 消费的授权标识；无需授权时为空。 */
    val grantId: String?,

    /** 参数摘要，用于确认授权与动作仍匹配。 */
    val paramsDigest: String,

    /** 是否幂等；非幂等动作即使判定 NO_EFFECT 也不得自动重试。 */
    val idempotent: Boolean,

    /** 生命周期状态。 */
    val status: IntentStatus,

    /** 判定结果；未判定时为空。 */
    val outcome: ActionOutcome?,

    /** 注入返回值；true 只代表系统接受事件，不代表已生效。 */
    val systemReturnValue: Boolean?,

    /** 后置条件证据摘要。 */
    val evidence: String?,

    /** 重试溯源：本次是对哪个动作的重试。 */
    val retryOf: String?,

    /** 同一动作的尝试次数。 */
    val attemptCount: Int,

    /** 创建时间戳（毫秒）。 */
    val createdAt: Long,

    /** 最后更新时间戳（毫秒）。 */
    val updatedAt: Long,
)

/** 写入意图的生命周期状态。 */
enum class IntentStatus {
    /** 已落盘，尚未注入。 */
    PREPARED,

    /** 正在注入；此状态下进程死亡即产生未决写入。 */
    INJECTING,

    /** 已得到判定结果。 */
    SETTLED,
}

/**
 * 动作结果的三种取值（设计文档 §1.3 第 3 条）。
 *
 * 关键约束：无法判定时必须落在 OUTCOME_UNKNOWN，不允许把"界面安静"或"树哈希变化"当作成功。
 */
enum class ActionOutcome {
    /** 后置条件满足。 */
    EFFECT_CONFIRMED,

    /** 有明确证据表明未生效；只有幂等动作才允许据此重试。 */
    NO_EFFECT,

    /** 无法判定；进入核对流程。 */
    OUTCOME_UNKNOWN,
}
