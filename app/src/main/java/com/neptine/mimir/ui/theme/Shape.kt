package com.neptine.mimir.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角刻度（Cohere 设计系统）。
 * 22dp 是签名值——媒体卡与深色带都用它；控件与小组件用 8dp，不用更小的圆角包大卡片。
 */
object MimirRadius {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 22.dp
    val xl = 30.dp
    val pill = 32.dp
    val full = 9999.dp
}

/** Material3 圆角槽位；只覆盖用得到的五档。 */
val MimirShapes = Shapes(
    extraSmall = RoundedCornerShape(MimirRadius.xs),
    small = RoundedCornerShape(MimirRadius.sm),
    medium = RoundedCornerShape(MimirRadius.md),
    large = RoundedCornerShape(MimirRadius.lg),
    extraLarge = RoundedCornerShape(MimirRadius.xl),
)
