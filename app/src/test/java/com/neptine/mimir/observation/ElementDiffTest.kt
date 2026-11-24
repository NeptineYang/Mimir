package com.neptine.mimir.observation

import com.neptine.mimir.core.contract.AnchorLevel
import com.neptine.mimir.core.contract.Bounds
import com.neptine.mimir.core.contract.TargetFingerprint
import com.neptine.mimir.core.contract.TargetMode
import com.neptine.mimir.core.contract.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 目标指纹在重复资源 ID 下的消歧（v0.3.18）。
 *
 * 混淆后的资源 ID 会被多个控件复用；注入前复核必须选到决策时的那一个，
 * 否则会把"目标还在原位"误判成"页面已变"。这里锁住三条：同 ID 时优先文案、
 * 再取最近 bounds；没有 bounds 时退回第一个；找不到时返回 null。
 */
class ElementDiffTest {

    /** 同一混淆 ID 下，应选中文案一致且位置最近的那个。 */
    @Test
    fun duplicateObfuscatedIdPrefersLabelAndNearestBounds() {
        val fingerprint = TargetFingerprint(
            mode = TargetMode.TREE,
            anchorLevel = AnchorLevel.A1,
            resourceId = OBFUSCATED_ID,
            text = "搜索",
            className = "android.widget.Button",
            bounds = Bounds(589, 80, 691, 160),
        )
        val elements = listOf(
            element("首页", OBFUSCATED_ID, Bounds(10, 10, 100, 100)),
            element("搜索", OBFUSCATED_ID, Bounds(589, 80, 691, 160)),
            element("我的", OBFUSCATED_ID, Bounds(900, 2100, 1000, 2200)),
        )

        val best = ElementDiff.findBest(fingerprint, elements)

        assertEquals("搜索", best?.text)
        assertEquals(Bounds(589, 80, 691, 160), best?.bounds)
    }

    /** 没有文案时按决策时的 bounds 取最近，而不是第一个。 */
    @Test
    fun duplicateIdWithoutLabelFallsBackToNearestBounds() {
        val fingerprint = TargetFingerprint(
            mode = TargetMode.TREE,
            anchorLevel = AnchorLevel.A1,
            resourceId = OBFUSCATED_ID,
            className = "android.widget.Button",
            bounds = Bounds(500, 500, 600, 600),
        )
        val elements = listOf(
            element("", OBFUSCATED_ID, Bounds(0, 0, 50, 50)),
            element("", OBFUSCATED_ID, Bounds(520, 520, 620, 620)),
        )

        val best = ElementDiff.findBest(fingerprint, elements)

        assertEquals(Bounds(520, 520, 620, 620), best?.bounds)
    }

    /** 找不到同类同标识元素时返回 null，调用方据此判失效。 */
    @Test
    fun noMatchReturnsNull() {
        val fingerprint = TargetFingerprint(
            mode = TargetMode.TREE,
            anchorLevel = AnchorLevel.A1,
            resourceId = OBFUSCATED_ID,
            text = "搜索",
            className = "android.widget.Button",
            bounds = Bounds(0, 0, 10, 10),
        )
        val elements = listOf(element("首页", "other:id", Bounds(0, 0, 10, 10)))

        assertNull(ElementDiff.findBest(fingerprint, elements))
    }

    /**
     * 功能：构造一个用于指纹测试的元素
     * 参数：text 文本；resourceId 资源 ID；bounds 位置
     * 返回：树模式元素
     */
    private fun element(text: String, resourceId: String, bounds: Bounds): UiElement = UiElement(
        index = 1,
        className = "android.widget.Button",
        text = text,
        contentDescription = "",
        bounds = bounds,
        clickable = true,
        editable = false,
        resourceId = resourceId,
    )

    private companion object {
        /** 小红书等混淆包常见的复用资源 ID。 */
        const val OBFUSCATED_ID = "com.xingin.xhs:id/0_resource_name_obfuscated"
    }
}
