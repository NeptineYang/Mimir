package com.neptine.mimir.observation

import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.UiElement

/**
 * 控件树采集端口（设计文档 §2.3）。
 *
 * 唯一能按 displayId 取窗口树的入口是 AccessibilityService.getWindowsOnAllDisplays()（API 30+）；
 * 实现必须在数据入口按 displayId 白名单过滤，主屏文本进入快照或日志即隐私验收失败（§4.6）。
 */
interface TreeProvider {

    /**
     * 功能：采集指定副屏的窗口树
     * 参数：handle 建屏句柄
     * 返回：一帧控件树；树不可用时抛异常，由上层降级为纯视觉
     */
    suspend fun capture(handle: DisplayHandle): TreeFrame
}

/**
 * 一帧控件树。
 *
 * @property packageName 前台包名
 * @property activityName 前台 Activity
 * @property treeHash 结构哈希，含文本；用于判定界面内容是否变化
 * @property layoutHash 布局哈希，只含类名与位置；用于一致观察配对
 * @property elements 已按规则筛选出的候选元素（尚未编号）
 * @property overflowCount 超出上限、未列出的可见元素数量
 */
data class TreeFrame(
    val packageName: String,
    val activityName: String,
    val treeHash: String,
    val layoutHash: String,
    val elements: List<UiElement>,
    val overflowCount: Int,
)
