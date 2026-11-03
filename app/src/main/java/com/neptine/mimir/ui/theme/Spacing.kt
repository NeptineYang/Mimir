package com.neptine.mimir.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity

/**
 * 间距刻度：8dp 基准栅格 + Cohere 稿子里的一次性对齐值。
 *
 * 用 object 常量而不是 CompositionLocal：间距没有主题维度，编译期常量即可；
 * 走 CompositionLocal 会强制所有使用点进入 @Composable 作用域，纯属浪费。
 */
object MimirSpacing {
    val xxs = 2.dp
    val xs = 6.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 40.dp

    /** 区块之间的呼吸留白。 */
    val section = 56.dp

    /** 页面左右边距。 */
    val page = 20.dp
}

/** 描边工具。 */
object MimirStroke {

    /** 标准细线 1dp；需要真 1 物理像素时用 [hairlineWidth]。 */
    val hairline = 1.dp
}

/**
 * 功能：返回 1 个物理像素对应的 dp 值
 * 参数：无（读取当前屏幕密度）
 * 返回：等价于 1 物理像素的 Dp
 *
 * 直接用 1.dp 在 xxhdpi 上是 3 个物理像素，线会明显变粗，Cohere 那种"几乎看不见的分隔线"就没了。
 */
@Composable
@ReadOnlyComposable
fun hairlineWidth(): Dp = with(LocalDensity.current) { 1f.toDp() }
