package com.neptine.mimir.observation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.neptine.mimir.core.contract.ImageFrame

/**
 * 画面指纹（设计文档 §1.3 第 2 条"树哈希 + 像素差轮询"）。
 *
 * 整图逐像素比较在 540×1200 上太贵，也没必要：把画面降采样成 GRID×GRID 的灰度格，
 * 比较两帧的格平均差即可判定"画面还在动"。
 */
internal object FrameFingerprint {

    /** 采样网格边长（每边 GRID 个采样点）。 */
    const val GRID = 16

    /**
     * 功能：把一帧图像降采样成灰度指纹
     * 参数：frame 待采样的图像帧
     * 返回：长度为 GRID×GRID 的灰度数组；解码失败返回 null
     */
    fun of(frame: ImageFrame): IntArray? {
        val bitmap = BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size) ?: return null
        return try {
            val samples = IntArray(GRID * GRID)
            for (row in 0 until GRID) {
                for (column in 0 until GRID) {
                    val x = (column + 0.5f) * bitmap.width / GRID
                    val y = (row + 0.5f) * bitmap.height / GRID
                    val pixel = bitmap.getPixel(
                        x.toInt().coerceIn(0, bitmap.width - 1),
                        y.toInt().coerceIn(0, bitmap.height - 1),
                    )
                    // 亮度近似：0.299R + 0.587G + 0.114B，位移实现避免浮点
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    samples[row * GRID + column] = (r * 77 + g * 150 + b * 29) shr 8
                }
            }
            samples
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 功能：计算一帧的平均亮度
     * 参数：frame 待判定的图像帧
     * 返回：0–255 的平均灰度；解码失败返回 -1
     *
     * 探针用它区分"副屏真的渲染了内容"与"创建成功但全黑"。
     */
    fun averageLuma(frame: ImageFrame): Double {
        val samples = of(frame) ?: return -1.0
        return samples.average()
    }

    /**
     * 功能：计算两帧指纹的平均灰度差
     * 参数：a 前一帧指纹；b 当前帧指纹
     * 返回：0–255 的平均绝对差；长度不一致时返回 255（视为完全不同）
     */
    fun meanDiff(a: IntArray, b: IntArray): Double {
        if (a.size != b.size || a.isEmpty()) return 255.0
        var total = 0
        for (index in a.indices) {
            total += kotlin.math.abs(a[index] - b[index])
        }
        return total.toDouble() / a.size
    }
}
