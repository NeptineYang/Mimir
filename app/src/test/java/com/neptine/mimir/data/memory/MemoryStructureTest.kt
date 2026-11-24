package com.neptine.mimir.data.memory

import com.neptine.mimir.core.contract.Bounds
import com.neptine.mimir.core.contract.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语言无关结构签名的口径（v0.3.16）。
 *
 * 锁住三件事：同一页换语言后结构签名仍可比；不同页面的结构相似度必须低于阈值；
 * 只有"一侧汉字、另一侧拉丁"才启用跨语言兜底，同语言页面变化不能被放行。
 */
class MemoryStructureTest {

    /** 同一页换语言：文本全变，但结构签名应完全一致且可共享资源 ID。 */
    @Test
    fun samePageAcrossLanguagesKeepsStructure() {
        val chinese = listOf(
            element("搜索设置", "com.android.settings:id/search_action_bar", clickable = true),
            element("网络和互联网", "android:id/title", clickable = true),
            element("移动网络、WLAN、热点", "android:id/summary"),
            element("已连接的设备", "android:id/title", clickable = true),
        )
        val english = listOf(
            element("Search settings", "com.android.settings:id/search_action_bar", clickable = true),
            element("Network & internet", "android:id/title", clickable = true),
            element("Mobile, Wi-Fi, hotspot", "android:id/summary"),
            element("Connected devices", "android:id/title", clickable = true),
        )
        val left = MemoryStructure.signature(chinese)
        val right = MemoryStructure.signature(english)

        assertEquals(1.0, MemoryStructure.similarity(left, right), 0.0001)
        assertEquals(3, MemoryStructure.sharedResourceIdCount(left, right))
        assertTrue(
            MemoryStructure.languageMismatch(
                chinese.map { it.text },
                english.map { it.text },
            ),
        )
    }

    /** 同一 App 的不同页面：资源 ID 与计数不同，相似度必须低于阈值。 */
    @Test
    fun differentPagesFallBelowThreshold() {
        val home = listOf(
            element("搜索设置", "com.android.settings:id/search_action_bar", clickable = true),
            element("网络和互联网", "android:id/title", clickable = true),
            element("已连接的设备", "android:id/title", clickable = true),
            element("应用", "android:id/title", clickable = true),
        )
        val detail = listOf(
            element("WLAN", "android:id/title", clickable = true),
            element("关闭", "android:id/switch_widget", clickable = true),
            element("添加网络", "android:id/button", clickable = true),
        )

        val similarity = MemoryStructure.similarity(
            MemoryStructure.signature(home),
            MemoryStructure.signature(detail),
        )

        assertTrue(similarity < MemoryStructure.MIN_SIMILARITY)
        assertEquals(1, MemoryStructure.sharedResourceIdCount(
            MemoryStructure.signature(home),
            MemoryStructure.signature(detail),
        ))
    }

    /** 没有资源 ID 的页面退回"类名 + 标志"，重复次数仍然参与比较。 */
    @Test
    fun elementsWithoutResourceIdUseClassTokens() {
        val one = listOf(
            element("A", resourceId = null),
            element("B", resourceId = null, clickable = true),
        )
        val two = listOf(
            element("C", resourceId = null),
            element("D", resourceId = null, clickable = true),
        )
        val signature = MemoryStructure.signature(one)

        assertEquals(2, signature.size)
        assertTrue(signature.all { it.startsWith("class:") })
        assertEquals(1.0, MemoryStructure.similarity(signature, MemoryStructure.signature(two)), 0.0001)
        assertEquals(0, MemoryStructure.sharedResourceIdCount(signature, signature))
    }

    /** 同语言（即使混有品牌名）不能判成跨语言。 */
    @Test
    fun sameLanguageIsNotMismatch() {
        assertFalse(
            MemoryStructure.languageMismatch(
                listOf("搜索设置", "Google 服务和偏好设置"),
                listOf("网络和互联网", "Google"),
            ),
        )
        assertFalse(
            MemoryStructure.languageMismatch(
                listOf("Search settings", "Google services"),
                listOf("Network & internet", "Google"),
            ),
        )
    }

    /** 任一侧没有文本时不启用跨语言兜底。 */
    @Test
    fun emptyTextsAreNotMismatch() {
        assertFalse(MemoryStructure.languageMismatch(emptyList(), listOf("Search settings")))
        assertFalse(MemoryStructure.languageMismatch(listOf("搜索设置"), emptyList()))
    }

    /** 空签名没有可比证据，相似度为 0。 */
    @Test
    fun emptySignatureHasZeroSimilarity() {
        assertEquals(0.0, MemoryStructure.similarity(emptyList(), listOf("id:x")), 0.0001)
        assertEquals(0.0, MemoryStructure.similarity(listOf("id:x"), emptyList()), 0.0001)
    }

    /**
     * 功能：构造一个用于签名测试的元素
     * 参数：text 文本；resourceId 资源 ID；clickable 是否可点击
     * 返回：编号固定的 UiElement
     */
    private fun element(
        text: String,
        resourceId: String?,
        clickable: Boolean = false,
    ): UiElement = UiElement(
        index = 1,
        className = "android.widget.TextView",
        text = text,
        contentDescription = "",
        bounds = Bounds(0, 0, 10, 10),
        clickable = clickable,
        editable = false,
        resourceId = resourceId,
    )
}
