package com.neptine.mimir.device

import com.neptine.mimir.core.contract.ProbeResult

/**
 * 启动期能力探针（设计文档 §2.2.2）。
 *
 * 目的不是"检测失败就崩"，而是把机型差异变成可展示的降级项：
 * 用户在配置页能看到这台机器哪一项不支持，而不是遇到一个静默失败的任务。
 */
interface CapabilityProbe {

    /**
     * 功能：依次执行 P1–P5 五项探针
     * 参数：无
     * 返回：每项探针的结果列表，顺序与 ProbeId 声明一致
     */
    suspend fun runAll(): List<ProbeResult>
}
