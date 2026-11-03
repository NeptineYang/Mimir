package com.neptine.mimir.observation

import com.neptine.mimir.core.contract.CaptureSource
import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.ImageFrame

/**
 * 截图采集端口（设计文档 §2.2 / §2.3.1）。
 *
 * 三级兜底按顺序尝试，取第一条能返回非黑帧的路径：
 * PixelCopy（主路径，300ms 超时）→ 无障碍 takeScreenshot（限频约 300ms/次）→ screencap。
 * 实际走的是哪条要记录在 CaptureSource 里，审计才能解释"这一帧为什么不清晰"。
 */
interface ScreenshotProvider {

    /**
     * 功能：抓取一帧副屏画面并缩放为送模型规格
     * 参数：handle 建屏句柄
     * 返回：图像帧与取帧来源
     */
    suspend fun capture(handle: DisplayHandle): CapturedFrame
}

/**
 * 取帧结果。
 *
 * @property image 图像帧
 * @property source 实际生效的取帧路径
 */
data class CapturedFrame(
    val image: ImageFrame,
    val source: CaptureSource,
)
