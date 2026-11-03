package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.Preference
import com.neptine.mimir.core.contract.Procedure

/**
 * 记忆检索口（设计文档 §2.5.6）。
 *
 * 检索算法不依赖向量库：按包名、任务类型与页面签名过滤平表，只断言当前阶段所需锚点，
 * 按置信度取 top-2。本地检索不调用模型。
 */
interface MemoryRetriever {

    /**
     * 功能：检索当前页面可用的配方
     * 参数：taskType 任务类型；snapshot 当前一致观察（包名、元素与文本都从它取）；
     *       parameters 本次任务参数，用于把配方里的 `{字段}` 占位符填成当前取值
     * 返回：命中的配方（最多 2 条，按置信度降序）；无命中返回空列表
     *
     * 传整份观察而不是只传页面签名：锚点 A1 要靠 resourceId 断言，只给文本集合就无法核对。
     */
    suspend fun retrieve(
        taskType: String,
        snapshot: ObservationSnapshot,
        parameters: Map<String, String> = emptyMap(),
    ): List<MemoryHit>

    /**
     * 功能：读取适用于某 App 的策略
     * 参数：packageName 目标包名
     * 返回：全局策略 + 该 App 的策略；没有时为空列表
     *
     * 策略只注入 Planner，不进逐步 prompt（§2.5.6）：它改的是"做什么、按什么顺序"。
     */
    suspend fun retrievePreferences(packageName: String): List<Preference>
}

/**
 * 一条命中结果。
 *
 * @property procedure 命中的配方
 * @property confidence 读取时算出的有效置信度（已叠加版本系数与时间衰减）
 * @property assertion 本地断言结论，写进审计证明"用之前核对过"
 */
data class MemoryHit(
    val procedure: Procedure,
    val confidence: Double,
    val assertion: String,
)
