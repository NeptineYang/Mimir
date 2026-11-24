package com.neptine.mimir.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.neptine.mimir.ui.theme.LocalMimirColors
import kotlin.math.pow
import kotlin.random.Random

/** 颗粒随机种子：固定值保证同一块区域每次重绘纹理一致，不会在滚动时"闪"。 */
private const val GRAIN_SEED = 20_261_109

/** 颗粒贴边方向。 */
enum class GrainEdge {
    /** 从屏幕（容器）顶边向内渐隐。 */
    Top,

    /** 从屏幕（容器）底边向内渐隐。 */
    Bottom,
}

/**
 * 功能：渲染"贴边颗粒、向内渐隐"的纸面质感层
 * 参数：modifier 修饰符（高度由调用方给定）；color 颗粒颜色；intensity 颗粒密度倍率；
 *       opacity 整体不透明度；edge 贴哪条边
 * 返回：无
 *
 * 图案由三层叠成：一层极淡的墨色洗底、一层细颗粒、一层少量粗颗粒。
 * 粒径用物理像素而不是 dp：这是"印刷颗粒"，换算成 dp 后在 3x 屏上会被放大成色斑。
 *
 * 随机点按"离边距离"的平方分布撒，再乘一个 (1 - d/h)^1.8 的透明度衰减：
 * 两者相乘使得贴边一两行是最密的黑点，中段只剩稀疏颗粒，内侧完全消失，
 * 不会出现一条生硬的"渐变边界"。上下两条边共用同一套生成逻辑，只是把 y 镜像。
 *
 * v0.3.17 起改为一次性烘焙成位图：原来在 `onDrawBehind` 里每帧画上千个圆点，
 * 真机滚动时 draw 阶段明显变慢；现在纹理只在尺寸变化时生成一次，每帧只画一张图。
 */
@Composable
fun GrainFade(
    modifier: Modifier = Modifier,
    color: Color = LocalMimirColors.current.ink,
    intensity: Float = 1f,
    opacity: Float = 1f,
    edge: GrainEdge = GrainEdge.Top,
) {
    Canvas(
        modifier = modifier.drawWithCache {
            val width = size.width.toInt()
            val height = size.height.toInt()
            if (width <= 0 || height <= 0) {
                return@drawWithCache onDrawBehind { }
            }
            val grain = buildGrainBitmap(width, height, color, intensity, opacity, edge)
            onDrawBehind {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawBitmap(grain, 0f, 0f, null)
                }
            }
        },
        // 绘制全部在 drawWithCache 里完成；这里只提供 Canvas 需要的绘制入口
        onDraw = {},
    )
}

/**
 * 功能：把颗粒纹理烘焙成一张位图
 * 参数：width/height 位图尺寸（像素）；color 颗粒颜色；intensity 密度倍率；
 *       opacity 整体不透明度；edge 贴边方向
 * 返回：带透明通道的位图
 *
 * 只在 GrainFade 的 drawWithCache 里调用：尺寸不变就不会重建；
 * 每帧绘制从"上千次 drawCircle"变成"一次 drawBitmap"。
 */
private fun buildGrainBitmap(
    width: Int,
    height: Int,
    color: Color,
    intensity: Float,
    opacity: Float,
    edge: GrainEdge,
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val w = width.toFloat()
    val h = height.toFloat()
    val random = Random(GRAIN_SEED)
    val count = ((w * h) / (420f / intensity)).toInt().coerceIn(240, 2_600)

    // 先撒 14 个微团簇，再让大部分颗粒落在簇心附近；纯均匀随机会像"灰尘"，
    // 成簇的墨点才像印刷颗粒。
    val clusters = 14
    val clusterX = FloatArray(clusters) { random.nextFloat() * w }
    val clusterD = FloatArray(clusters) {
        val t = random.nextFloat()
        h * t * t * 0.55f
    }
    val dots = FloatArray(count * 4)
    for (i in 0 until count) {
        val t = random.nextFloat()
        val x: Float
        val d: Float
        if (random.nextFloat() < 0.62f) {
            val c = random.nextInt(clusters)
            x = clusterX[c] + (random.nextFloat() - 0.5f) * w * 0.16f
            d = clusterD[c] + (random.nextFloat() - 0.5f) * h * 0.14f
        } else {
            x = random.nextFloat() * w
            d = h * t * t * 0.85f
        }
        dots[i * 4] = x.coerceIn(0f, w)
        dots[i * 4 + 1] = d.coerceIn(0f, h)
        dots[i * 4 + 2] = 0.35f + random.nextFloat() * random.nextFloat() * 2.6f
        dots[i * 4 + 3] = 0.22f + random.nextFloat() * 0.78f
    }
    // 少量粗颗粒：只有第一眼能看见的几颗，用来打破细颗粒的均匀感
    val flecks = (count / 18).coerceAtLeast(5)
    val bigDots = FloatArray(flecks * 3)
    for (i in 0 until flecks) {
        val c = random.nextInt(clusters)
        bigDots[i * 3] = (clusterX[c] + (random.nextFloat() - 0.5f) * w * 0.1f)
            .coerceIn(0f, w)
        bigDots[i * 3 + 1] = (clusterD[c] + (random.nextFloat() - 0.5f) * h * 0.08f)
            .coerceIn(0f, h)
        bigDots[i * 3 + 2] = 2.4f + random.nextFloat() * 2.2f
    }

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // 洗底：与原来的 verticalGradient 三点等距一致；底边方向按 edge 翻转。
    val wash = intArrayOf(
        color.copy(alpha = 0.06f * opacity).toArgb(),
        color.copy(alpha = 0.014f * opacity).toArgb(),
        Color.Transparent.toArgb(),
    )
    paint.shader = LinearGradient(
        0f,
        0f,
        0f,
        h,
        if (edge == GrainEdge.Top) wash else wash.reversedArray(),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, w, h, paint)
    paint.shader = null

    // 统一按"离边距离 d"计算透明度，再按方向映射回 y
    fun yOf(distance: Float): Float =
        if (edge == GrainEdge.Top) distance else h - distance

    for (i in 0 until count) {
        val d = dots[i * 4 + 1]
        val fade = (1f - d / h).coerceIn(0f, 1f).pow(1.8f)
        val alpha = dots[i * 4 + 3] * fade * 0.72f * opacity
        if (alpha > 0.012f) {
            paint.color = color.copy(alpha = alpha).toArgb()
            canvas.drawCircle(dots[i * 4], yOf(d), dots[i * 4 + 2], paint)
        }
    }
    for (i in 0 until flecks) {
        val d = bigDots[i * 3 + 1]
        val fade = (1f - d / h).coerceIn(0f, 1f).pow(1.9f)
        val alpha = fade * 0.52f * opacity
        if (alpha > 0.02f) {
            paint.color = color.copy(alpha = alpha).toArgb()
            canvas.drawCircle(bigDots[i * 3], yOf(d), bigDots[i * 3 + 2], paint)
        }
    }
    return bitmap
}
