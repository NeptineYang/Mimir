package com.neptine.mimir.agent

/**
 * 一步的四段耗时（设计文档 §5.4 QoS）。
 *
 * 只覆盖"真正走到设备上的一步"：纯系统事件（遮挡、主屏冲突、记忆注入）没有注入段，
 * 不参与步耗时统计，避免把零工作量的行算成极快的一步。
 *
 * @property observeMs 一致观察耗时
 * @property decideMs 模型决策耗时（含解析重试）
 * @property actMs 注入、等待界面稳定与后置条件判定的耗时
 */
data class StepTiming(
    val observeMs: Long,
    val decideMs: Long,
    val actMs: Long,
) {

    /** 本步合计耗时（毫秒）。 */
    val totalMs: Long get() = observeMs + decideMs + actMs
}
