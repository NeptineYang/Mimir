package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.RiskLevel
import com.neptine.mimir.data.db.dao.AuditDao
import com.neptine.mimir.data.db.entity.AuditEntity
import com.neptine.mimir.data.db.entity.TaskEntity
import com.neptine.mimir.data.remote.ChatUsage
import javax.inject.Inject

/**
 * 审计记录器（设计文档 §4.4）。
 *
 * 每步一条记录，既供"近 3 步历史"构造 prompt，也供 UI 逐步回放。
 * 记的是"当时看到了什么、模型说了什么、系统返回什么、结果判定为什么"，
 * 而不是一句"成功/失败"——回放的价值全在细节。
 */
class AuditRecorder @Inject constructor(private val auditDao: AuditDao) {

    /**
     * 功能：追加一条审计记录
     * 参数：entry 审计实体
     * 返回：新记录的自增主键
     */
    suspend fun record(entry: AuditEntity): Long = auditDao.insert(entry)

    /**
     * 功能：写一条没有观察上下文的系统审计
     * 参数：task 任务；outcome 结果；result 说明；decision 处置字段；usage 本次模型调用用量
     * 返回：新记录的自增主键
     *
     * 用于恢复与核对这类"不发生在某一帧上"的事件；观察字段留空而不是编造一个 observationId。
     * 默认处置是 NEED_HUMAN（恢复与核对都会让人接手）；记忆写入这类"事情发生了但没拦任何东西"
     * 的事件传 ALLOW，别让审计里出现莫名其妙的"需要人工"。
     * 规划器调用不落在某一帧上，用量也走这里记，否则成本统计会漏掉解析与规划两次调用。
     */
    suspend fun recordSystemEvent(
        task: TaskEntity,
        outcome: String,
        result: String,
        decision: String = "NEED_HUMAN",
        usage: ChatUsage? = null,
    ): Long =
        record(
            AuditEntity(
                taskId = task.taskId,
                stepIndex = task.budgetStepsUsed + 1,
                timestamp = System.currentTimeMillis(),
                subGoal = task.currentSubGoal.orEmpty(),
                observationId = task.lastObservationId.orEmpty(),
                observationHash = "",
                displayGeneration = task.displayGeneration,
                actionId = null,
                actionKind = "",
                paramsDigest = "",
                modelOutput = "",
                systemReturnValue = null,
                targetFingerprint = null,
                targetText = null,
                idempotency = "NONE",
                intentStatus = null,
                outcome = outcome,
                grantId = null,
                riskLevel = RiskLevel.L0.name,
                decision = decision,
                reviewReason = result,
                result = result,
                verificationEvidence = null,
                memoryUsed = null,
                memoryAssertion = null,
                imageTokens = 0,
                promptTokens = usage?.promptTokens ?: 0,
                completionTokens = usage?.completionTokens ?: 0,
                screenshotBefore = null,
                screenshotAfter = null,
            ),
        )
}
