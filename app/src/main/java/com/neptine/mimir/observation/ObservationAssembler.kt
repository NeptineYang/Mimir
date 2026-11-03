package com.neptine.mimir.observation

import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.ObservationSnapshot

/**
 * 一致观察装配口（设计文档 §1.3.1）。
 *
 * 采集顺序固定为"树 A → 截图 → 树 B"；两次树哈希、包名、Activity、实际 displayId 与
 * display_generation 全部一致才生成 observationId，否则整组丢弃重采——
 * 目的是不让模型同时看到两个页面状态。
 */
interface ObservationAssembler {

    /**
     * 功能：装配一帧一致观察
     * 参数：handle 建屏句柄
     * 返回：一致快照；不一致时抛 ObservationUnstableException 由上层重采
     */
    suspend fun snapshot(handle: DisplayHandle): ObservationSnapshot

    /**
     * 功能：装配一帧纯视觉降级观察（树不可用时）
     * 参数：handle 建屏句柄；packageName 当前副屏前台包名
     * 返回：只带截图、元素表为空的快照；截图也取不到时抛异常
     *
     * 纯视觉观察没有元素编号，不能执行任何元素动作；调用方只应据此等待重试，
     * 直到控件树恢复或转人工（§2.2 观测降级）。
     */
    suspend fun visionSnapshot(handle: DisplayHandle, packageName: String): ObservationSnapshot
}

/** 观察期间界面发生变化，需整组重采。 */
class ObservationUnstableException(message: String) : IllegalStateException(message)
