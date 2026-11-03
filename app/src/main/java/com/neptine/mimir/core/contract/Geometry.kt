package com.neptine.mimir.core.contract

/**
 * 几何基础类型（设计文档 §2.3）。
 *
 * 坐标有两套语义，不可混用：原生像素（Bounds，用于注入）与归一化 0–1000（NormalizedBox，
 * 用于纯视觉模式下的模型输出）。两者之间的映射版本号记录在 ObservationSnapshot 中。
 */

/**
 * 原生像素坐标点。
 *
 * @property x 横坐标（像素）
 * @property y 纵坐标（像素）
 */
data class Point(val x: Int, val y: Int)

/**
 * 原生像素矩形；边界含左上不含右下。
 *
 * @property left 左边界
 * @property top 上边界
 * @property right 右边界
 * @property bottom 下边界
 */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    /** 宽度（像素）。 */
    val width: Int get() = right - left

    /** 高度（像素）。 */
    val height: Int get() = bottom - top

    /** 中心点；点击定位以此为准，再由执行层加抖动。 */
    val center: Point get() = Point((left + right) / 2, (top + bottom) / 2)
}

/**
 * 归一化矩形，取值 0–1000。
 *
 * @property left 左边界（0–1000）
 * @property top 上边界（0–1000）
 * @property right 右边界（0–1000）
 * @property bottom 下边界（0–1000）
 */
data class NormalizedBox(val left: Int, val top: Int, val right: Int, val bottom: Int)
