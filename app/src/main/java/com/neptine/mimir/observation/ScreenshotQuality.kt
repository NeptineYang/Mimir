package com.neptine.mimir.observation

import com.neptine.mimir.core.contract.UiElement

/**
 * 送模型截图的清晰度档位（v0.3.19 截图策略）。
 *
 * 视觉 token 按分辨率估算（28×28 像素一个 token）：540×1200 约 798，
 * 360×800 约 336。元素表能提供足够语义时用紧凑档，只有树稀疏或纯视觉时
 * 才回到全尺寸，避免每一步都为"看得清"多付一倍图像 token。
 */
enum class ScreenshotQuality(val width: Int, val height: Int) {
    /** 紧凑档：文本元素足够时使用。 */
    COMPACT(360, 800),

    /** 全尺寸档：树稀疏、图标为主或纯视觉时使用。 */
    FULL(540, 1200),
    ;

    companion object {

        /**
         * 标签元素达到这个数量，认为文本语义足够，使用紧凑截图。
         *
         * 只数带文本或无障碍描述的元素，不数纯容器：设置页/信息流有很多标题，
         * 而相机、图标工具页虽然元素不少，却几乎没有可读文本，后者必须保留全尺寸。
         */
        const val RICH_LABELED_ELEMENTS = 12

        /**
         * 功能：按当前树的标签元素数量选择截图档位
         * 参数：elements 当前帧树元素
         * 返回：带文本/描述的元素足够多时为 COMPACT，否则 FULL
         */
        fun forTree(elements: List<UiElement>): ScreenshotQuality {
            val labeled = elements.count { element ->
                element.text.isNotBlank() || element.contentDescription.isNotBlank()
            }
            return if (labeled >= RICH_LABELED_ELEMENTS) COMPACT else FULL
        }
    }
}

