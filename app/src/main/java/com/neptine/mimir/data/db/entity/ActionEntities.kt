package com.neptine.mimir.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 写入意图账本（设计文档 §1.3.1）。
 *
 * 所有可能产生外部副作用的动作先落一行、再注入，最后写回结果。
 * status 只描述生命周期，outcome 只描述判定结果，两者不混用：
 * 进程死在 INJECTING 时会留下 status=INJECTING 且 outcome=null 的行，这就是"未决写入"。
 */
@Entity(
    tableName = "action_intent",
    indices = [Index(value = ["task_id", "status"])],
)
data class ActionIntentEntity(
    /** 动作唯一标识。 */
    @PrimaryKey
    @ColumnInfo(name = "action_id")
    val actionId: String,

    /** 归属任务。 */
    @ColumnInfo(name = "task_id")
    val taskId: String,

    /** 任务内步序，与 audit 对齐。 */
    @ColumnInfo(name = "step_idx")
    val stepIndex: Int,

    /** 动作所属的计划版本；与当前计划版本不符即视为旧计划动作。 */
    @ColumnInfo(name = "plan_version", defaultValue = "0")
    val planVersion: Int = 0,

    /** 动作所属的计划步骤标识，形如 `s1-2`；无步骤上下文的系统动作为空。 */
    @ColumnInfo(name = "plan_step_id")
    val planStepId: String? = null,

    /** 决策依据的一致观察编号。 */
    @ColumnInfo(name = "observation_id")
    val observationId: String,

    /** 注入前复验得到的实际 displayId。 */
    @ColumnInfo(name = "display_id")
    val displayId: Int,

    /** 副屏代次，与 displayId 一起复验，防止落到已重建的屏上。 */
    @ColumnInfo(name = "display_generation")
    val displayGeneration: Long,

    /** 动作类型，与 ActionKind 对应。 */
    @ColumnInfo(name = "action_kind")
    val actionKind: String,

    /** 目标指纹序列化结果；纯视觉模式记录 OCR 文本与归一化框。 */
    @ColumnInfo(name = "target_fingerprint_json")
    val targetFingerprintJson: String?,

    /** 风险分级：L0 / L1 / L2A / L2B。 */
    @ColumnInfo(name = "risk_level")
    val riskLevel: String,

    /** 消费的授权标识；无需授权时为空。 */
    @ColumnInfo(name = "grant_id")
    val grantId: String?,

    /** 参数摘要，用于校验授权与动作是否仍匹配。 */
    @ColumnInfo(name = "params_digest")
    val paramsDigest: String,

    /** 是否幂等；非幂等动作在 NO_EFFECT 时也不得自动重试。 */
    @ColumnInfo(name = "idempotent")
    val idempotent: Boolean,

    /** 生命周期：PREPARED / INJECTING / SETTLED。 */
    @ColumnInfo(name = "status")
    val status: String,

    /** 判定结果：CONFIRMED / NO_EFFECT / OUTCOME_UNKNOWN；未判定为空。 */
    @ColumnInfo(name = "outcome")
    val outcome: String?,

    /** 注入返回值，仅代表系统是否接受事件，不代表已生效。 */
    @ColumnInfo(name = "system_return_value")
    val systemReturnValue: Boolean?,

    /** 证据摘要（后置条件、核对结论等）。 */
    @ColumnInfo(name = "evidence")
    val evidence: String?,

    /** 重试溯源：本次是对哪个动作的重试。 */
    @ColumnInfo(name = "retry_of")
    val retryOf: String?,

    /** 同一动作的尝试次数，上限 2 次重试由上层约束。 */
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,

    /** 创建时间戳（毫秒）。 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** 最后更新时间戳（毫秒）。 */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * 一次性授权（设计文档 §1.3.1 / §4.2）。
 *
 * 消费必须原子：`UPDATE risk_grant SET consumed = 1 WHERE grant_id = ? AND consumed = 0`，
 * 以受影响行数判定是否抢到授权，避免重复点击与崩溃后复用。
 */
@Entity(
    tableName = "risk_grant",
    indices = [Index(value = ["task_id", "consumed"])],
)
data class RiskGrantEntity(
    /** 授权唯一标识。 */
    @PrimaryKey
    @ColumnInfo(name = "grant_id")
    val grantId: String,

    /** 归属任务。 */
    @ColumnInfo(name = "task_id")
    val taskId: String,

    /** 绑定的动作类型；动作类型不符即失效。 */
    @ColumnInfo(name = "action_kind")
    val actionKind: String,

    /** 绑定的参数摘要；参数变化即失效。 */
    @ColumnInfo(name = "params_digest")
    val paramsDigest: String,

    /** 绑定的目标包名；换 App 即失效。 */
    @ColumnInfo(name = "target_package")
    val targetPackage: String,

    /** 完成边界说明，例如"仅创建一笔待支付订单，不含支付"。 */
    @ColumnInfo(name = "scope_text")
    val scopeText: String,

    /** 是否已消费。 */
    @ColumnInfo(name = "consumed")
    val consumed: Boolean,

    /** 消费时间戳（毫秒）。 */
    @ColumnInfo(name = "consumed_at")
    val consumedAt: Long?,

    /** 消费该授权的动作标识，用于审计追溯。 */
    @ColumnInfo(name = "consumed_by_action_id")
    val consumedByActionId: String?,

    /** 签发时间戳（毫秒）。 */
    @ColumnInfo(name = "issued_at")
    val issuedAt: Long,

    /** 过期时间戳（毫秒）；为空表示随任务结束失效。 */
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long?,
)
