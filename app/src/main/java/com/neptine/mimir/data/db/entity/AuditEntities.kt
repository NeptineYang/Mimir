package com.neptine.mimir.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 每步审计记录（设计文档 §4.4）。
 *
 * 这是唯一的每步日志表：既供"近 3 步历史"构造 prompt，也供 UI 逐步回放。
 * 与 action_intent 的分工：本表 append-only 记录"这一步发生过什么"，
 * action_intent 记录"某个写入动作当前处于哪个阶段"（可多次更新）。
 */
@Entity(
    tableName = "audit",
    indices = [Index(value = ["task_id", "step_idx"]), Index(value = ["ts"])],
)
data class AuditEntity(
    /** 自增主键。 */
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** 归属任务。 */
    @ColumnInfo(name = "task_id")
    val taskId: String,

    /** 任务内步序，从 1 开始。 */
    @ColumnInfo(name = "step_idx")
    val stepIndex: Int,

    /** 记录时间戳（毫秒）。 */
    @ColumnInfo(name = "ts")
    val timestamp: Long,

    /** 本步所属子目标。 */
    @ColumnInfo(name = "sub_goal")
    val subGoal: String,

    /** 一致观察编号。 */
    @ColumnInfo(name = "observation_id")
    val observationId: String,

    /** 观察的结构哈希（两次树哈希一致后的取值）。 */
    @ColumnInfo(name = "obs_hash")
    val observationHash: String,

    /** 副屏代次。 */
    @ColumnInfo(name = "display_generation")
    val displayGeneration: Long,

    /** 动作标识；本步无动作时为空。 */
    @ColumnInfo(name = "action_id")
    val actionId: String?,

    /** 动作类型与参数摘要。 */
    @ColumnInfo(name = "action_kind")
    val actionKind: String,

    /** 参数摘要。 */
    @ColumnInfo(name = "params_digest")
    val paramsDigest: String,

    /** 模型原始输出，回放时逐字展示。 */
    @ColumnInfo(name = "model_output")
    val modelOutput: String,

    /** 注入返回值，仅代表系统接收。 */
    @ColumnInfo(name = "system_return_value")
    val systemReturnValue: Boolean?,

    /** 目标指纹序列化结果。 */
    @ColumnInfo(name = "target_fingerprint")
    val targetFingerprint: String?,

    /** 目标文本（树文本或 OCR 文本），供检索与复核。 */
    @ColumnInfo(name = "target_text")
    val targetText: String?,

    /** 幂等分类。 */
    @ColumnInfo(name = "idempotency")
    val idempotency: String,

    /** 动作生命周期状态。 */
    @ColumnInfo(name = "intent_status")
    val intentStatus: String?,

    /** 动作判定结果：EFFECT_CONFIRMED / NO_EFFECT / OUTCOME_UNKNOWN。 */
    @ColumnInfo(name = "outcome")
    val outcome: String?,

    /** 消费的授权标识。 */
    @ColumnInfo(name = "grant_id")
    val grantId: String?,

    /** 风险分级。 */
    @ColumnInfo(name = "risk_level")
    val riskLevel: String,

    /** 处置：ALLOW / DENY / NEED_HUMAN 等。 */
    @ColumnInfo(name = "decision")
    val decision: String,

    /** Reviewer 或 Guard 的复核理由。 */
    @ColumnInfo(name = "review_reason")
    val reviewReason: String?,

    /** 本步结果摘要。 */
    @ColumnInfo(name = "result")
    val result: String?,

    /** 双路验证证据标识与不可达原因。 */
    @ColumnInfo(name = "verification_evidence")
    val verificationEvidence: String?,

    /** 本步注入的配方 ID 列表（top-2）。 */
    @ColumnInfo(name = "memory_used")
    val memoryUsed: String?,

    /** 锚点断言结果：命中 / 未命中。 */
    @ColumnInfo(name = "memory_assertion")
    val memoryAssertion: String?,

    /** 本步实际发送的图像 token 估算。 */
    @ColumnInfo(name = "image_tokens")
    val imageTokens: Int,

    /** 本步模型调用的输入 token（后端返回的实测值；不返回时为 0）。 */
    @ColumnInfo(name = "prompt_tokens", defaultValue = "0")
    val promptTokens: Int = 0,

    /** 本步模型调用的输出 token（含思考；后端不返回时为 0）。 */
    @ColumnInfo(name = "completion_tokens", defaultValue = "0")
    val completionTokens: Int = 0,

    /** 一致观察耗时（毫秒）；没有计时的系统事件行为 0。 */
    @ColumnInfo(name = "observe_ms", defaultValue = "0")
    val observeMs: Long = 0,

    /** 模型决策耗时（毫秒，含解析重试）；没有计时的行为 0。 */
    @ColumnInfo(name = "decide_ms", defaultValue = "0")
    val decideMs: Long = 0,

    /** 注入、等待稳定与后置条件判定的耗时（毫秒）；没有计时的行为 0。 */
    @ColumnInfo(name = "act_ms", defaultValue = "0")
    val actMs: Long = 0,

    /** 本步合计耗时（毫秒）；等于前三段之和，便于聚合时直接 AVG。 */
    @ColumnInfo(name = "total_ms", defaultValue = "0")
    val totalMs: Long = 0,

    /** 动作前截图路径（应用私有目录）。 */
    @ColumnInfo(name = "screenshot_before")
    val screenshotBefore: String?,

    /** 动作后截图路径（应用私有目录）。 */
    @ColumnInfo(name = "screenshot_after")
    val screenshotAfter: String?,
)
