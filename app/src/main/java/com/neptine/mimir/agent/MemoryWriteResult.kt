package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.MemoryStatus

/**
 * 一次任务结束后记忆写入的结论。
 *
 * 区分"写入"与"跳过"是刻意的：当前主路径到不了 `DONE`，如果只返回"没写入"，
 * 从任务上看不出是"轨迹没价值"还是"闸门没开"。两者都进审计，各有明确原因。
 */
sealed interface MemoryWriteResult {

    /**
     * 已写入配方。
     *
     * @property procedureId 配方标识
     * @property title 配方标题
     * @property status 写入后的状态
     * @property successCount 累计成功次数
     * @property detail 一句话说明（首次候选 / 转正 / 仍未达条件）
     */
    data class Written(
        val procedureId: String,
        val title: String,
        val status: MemoryStatus,
        val successCount: Int,
        val detail: String,
    ) : MemoryWriteResult

    /**
     * 轨迹可重建但没有写入。
     *
     * @property title 本可生成的配方标题
     * @property stepCount 重建出的步骤数
     * @property stableKey 重建出的稳定键，用于核对"是同一路径"
     * @property reason 为什么没写
     */
    data class Skipped(
        val title: String,
        val stepCount: Int,
        val stableKey: String,
        val reason: String,
    ) : MemoryWriteResult
}
