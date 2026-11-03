package com.neptine.mimir.observation

import com.neptine.mimir.core.contract.Bounds
import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.ImageFrame
import com.neptine.mimir.core.contract.ObservationMode
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.UiElement
import java.util.UUID

/**
 * 一致观察装配（设计文档 §1.3.1）。
 *
 * 采集顺序固定为"树 A → 截图 → 树 B"。仅当两次树哈希、包名与 Activity 全部一致时才生成
 * observationId；否则整组丢弃重采——目的是不让模型同时看到两个页面状态。
 *
 * displayId 与 display_generation 直接取自建屏句柄，且树采集入口已按 displayId 白名单过滤，
 * 因此这里不需要（也无法）再"从结果反推"实际屏号。
 */
class ConsistentObservationAssembler(
    private val trees: TreeProvider,
    private val screenshots: ScreenshotProvider,
    private val numberer: ElementNumberer,
    private val ocr: OcrProvider,
) : ObservationAssembler {

    /**
     * 功能：装配一帧一致观察
     * 参数：handle 建屏句柄
     * 返回：一致快照；不一致时抛 ObservationUnstableException 由上层重采
     *
     * 配对用布局哈希而不是含文本的结构哈希：开屏广告倒计时、轮播页码这类文本每帧都变，
     * 但元素位置没动，用结构哈希会让这类页面永远形不成一致观察，任务被直接判死。
     */
    override suspend fun snapshot(handle: DisplayHandle): ObservationSnapshot {
        val before = trees.capture(handle)
        val captured = screenshots.capture(handle)
        val after = trees.capture(handle)

        if (before.layoutHash != after.layoutHash) {
            throw ObservationUnstableException("截图前后布局不一致，整组作废")
        }
        if (before.packageName != after.packageName || before.activityName != after.activityName) {
            throw ObservationUnstableException("截图前后前台应用发生变化，整组作废")
        }

        val numbered = numberer.number(before.elements)
        // 树"能读到但一个元素都没有"是封锁型 App 的典型形态（微信就是：窗口在、children=0）。
        // 这种情况不能当成"页面是空的"，否则模型只能干等；改用 OCR 补一份元素表，
        // 同时把通道降级成 VISION_ONLY —— 元素是识别出来的字，写入仍然不可用。
        val blockedTree = numbered.isEmpty()
        val ocrBranch = if (blockedTree) ocrElementsOf(captured.image, handle) else emptyList()

        return ObservationSnapshot(
            observationId = UUID.randomUUID().toString(),
            displayId = handle.displayId,
            displayGeneration = handle.generation,
            packageName = before.packageName,
            activityName = before.activityName,
            treeHashBefore = before.treeHash,
            treeHashAfter = after.treeHash,
            elements = if (blockedTree) ocrBranch else numbered,
            overflowCount = (before.elements.size - numbered.size).coerceAtLeast(0),
            ocrElements = ocrBranch,
            image = captured.image,
            captureSource = captured.source,
            nativeWidth = handle.spec.width,
            nativeHeight = handle.spec.height,
            coordinateMapVersion = COORDINATE_MAP_VERSION,
            capturedAt = System.currentTimeMillis(),
            mode = if (blockedTree && ocrBranch.isNotEmpty()) ObservationMode.VISION_ONLY else ObservationMode.TREE,
            overlay = TransientOverlayClassifier.classify(if (blockedTree) ocrBranch else numbered),
        )
    }

    /**
     * 功能：装配一帧纯视觉降级观察
     * 参数：handle 建屏句柄；packageName 当前副屏前台包名
     * 返回：只带截图、元素表为空的快照
     *
     * 树读不出来时仍要给出"这一帧画面上是什么"，任务才能靠等待重试而不是直接判死；
     * 但元素编号为空，任何元素级动作都无法构造，因此纯视觉观察不允许写入。
     */
    override suspend fun visionSnapshot(
        handle: DisplayHandle,
        packageName: String,
    ): ObservationSnapshot {
        val captured = screenshots.capture(handle)
        val now = System.currentTimeMillis()
        val hash = "vision:$now"
        // 树被 App 封锁时用 OCR 补元素表：没有它，模型只能看着截图说"等一下"，
        // 微信这类 App 会一直 WAIT 到预算耗尽。
        val elements = ocrElementsOf(captured.image, handle)
        return ObservationSnapshot(
            observationId = UUID.randomUUID().toString(),
            displayId = handle.displayId,
            displayGeneration = handle.generation,
            packageName = packageName,
            activityName = "",
            treeHashBefore = hash,
            treeHashAfter = hash,
            elements = elements,
            overflowCount = 0,
            ocrElements = elements,
            image = captured.image,
            captureSource = captured.source,
            nativeWidth = handle.spec.width,
            nativeHeight = handle.spec.height,
            coordinateMapVersion = COORDINATE_MAP_VERSION,
            capturedAt = now,
            mode = ObservationMode.VISION_ONLY,
        )
    }

    /**
     * 功能：把 OCR 文本行映射成元素
     * 参数：image 送模型的截图；handle 建屏句柄（用于把图像坐标折算回原生坐标）
     * 返回：按上→下排序、编号后的元素；识别不到文本时为空
     *
     * 坐标必须折算回**原生像素**：动作注入用的是副屏坐标，而截图可能已按 540×1200 缩小过。
     * 不折算会把点击打到页面左上角那一小片。
     */
    private suspend fun ocrElementsOf(image: ImageFrame, handle: DisplayHandle): List<UiElement> {
        if (image.bytes.isEmpty() || image.width <= 0 || image.height <= 0) return emptyList()
        val lines = runCatching { ocr.recognize(image) }.getOrDefault(emptyList())
        if (lines.isEmpty()) return emptyList()
        val scaleX = handle.spec.width.toFloat() / image.width
        val scaleY = handle.spec.height.toFloat() / image.height
        return lines
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .take(ElementNumberer.MAX_ELEMENTS)
            .mapIndexed { position, line ->
                UiElement(
                    index = position + 1,
                    className = OCR_CLASS_NAME,
                    text = line.text,
                    contentDescription = "",
                    bounds = Bounds(
                        left = (line.bounds.left * scaleX).toInt(),
                        top = (line.bounds.top * scaleY).toInt(),
                        right = (line.bounds.right * scaleX).toInt(),
                        bottom = (line.bounds.bottom * scaleY).toInt(),
                    ),
                    // OCR 只知道"这里有字"，不知道能不能点：交给动作后的元素变化去证伪。
                    clickable = false,
                    editable = false,
                )
            }
    }

    private companion object {
        /** OCR 元素的类名标记，审计与 prompt 用它区分"识别出来的字"和"真实控件"。 */
        const val OCR_CLASS_NAME = "OcrText"

        /** 坐标映射规则版本：模型图像尺寸 → 原生副屏尺寸的换算前提（§2.3.1）。 */
        const val COORDINATE_MAP_VERSION = "native-540x1200-v1"
    }
}
