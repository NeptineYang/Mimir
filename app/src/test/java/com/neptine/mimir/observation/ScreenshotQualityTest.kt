package com.neptine.mimir.observation

import com.neptine.mimir.core.contract.Bounds
import com.neptine.mimir.core.contract.UiElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 截图清晰度按树标签数量选择的边界（v0.3.19）。
 *
 * 阈值不能随手改：标签不足时元素表给不出足够语义，必须保留全尺寸；
 * 纯容器再多也不算标签，避免相机/图标页被误降到紧凑档。
 */
class ScreenshotQualityTest {

    /** 标签不足时必须用全尺寸。 */
    @Test
    fun sparseLabelsUseFullSize() {
        val elements = List(11) { index -> element("标签$index") } + List(30) { element("") }
        assertEquals(ScreenshotQuality.FULL, ScreenshotQuality.forTree(elements))
        assertEquals(ScreenshotQuality.FULL, ScreenshotQuality.forTree(emptyList()))
    }

    /** 达到标签阈值后用紧凑档。 */
    @Test
    fun richLabelsUseCompactSize() {
        val elements = List(12) { index -> element("标签$index") }
        assertEquals(ScreenshotQuality.COMPACT, ScreenshotQuality.forTree(elements))
    }

    /** 只有无障碍描述也算标签。 */
    @Test
    fun contentDescriptionCountsAsLabel() {
        val elements = List(12) { index ->
            element("", contentDescription = "描述$index")
        }
        assertEquals(ScreenshotQuality.COMPACT, ScreenshotQuality.forTree(elements))
    }

    /** 两档尺寸保持 9:20 比例，坐标映射按图像尺寸反算，比例不能歪。 */
    @Test
    fun sizesKeepAspectRatio() {
        ScreenshotQuality.entries.forEach { quality ->
            assertEquals(9.0 / 20.0, quality.width.toDouble() / quality.height, 0.001)
        }
    }

    /**
     * 功能：构造一个用于标签计数的元素
     * 参数：text 文本；contentDescription 无障碍描述
     * 返回：树模式元素
     */
    private fun element(text: String, contentDescription: String = ""): UiElement = UiElement(
        index = 1,
        className = "android.widget.TextView",
        text = text,
        contentDescription = contentDescription,
        bounds = Bounds(0, 0, 10, 10),
        clickable = false,
        editable = false,
        resourceId = "test:id",
    )
}
