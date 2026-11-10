package com.neptine.mimir.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 线性图标集，路径取自 Lucide（ISC 许可）的 24×24 描边图形。
 *
 * 不引入图标依赖：本项目只需要六个符号，而 Lucide 的 composer 扩展库会带来
 * 上千个矢量与一套额外的版本约束。这里用 PathParser 直接吃 Lucide 的原始 path 串，
 * 升级某个图标时照抄官网 path 即可，不涉及 API 差异。
 *
 * 描边宽度 1.8 而不是 2：在手机上 2px 会显得偏重，和 1 物理像素的细线语言不协调。
 */
object MimirIcons {

    /** 任务：清单加两个勾。 */
    val Task: ImageVector = strokeIcon(
        "Task",
        "m3 17 2 2 4-4",
        "m3 7 2 2 4-4",
        "M13 6h8",
        "M13 12h8",
        "M13 18h8",
    )

    /** 历史：带回拨箭头的时钟。 */
    val History: ImageVector = strokeIcon(
        "History",
        "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
        "M3 3v5h5",
        "M12 7v5l4 2",
    )

    /** 审计：盾牌加勾，代表"证据可核对"。 */
    val Audit: ImageVector = strokeIcon(
        "Audit",
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1" +
            "c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        "m9 12 2 2 4-4",
    )

    /** 设置：两条带滑块的横轨。 */
    val Settings: ImageVector = strokeIcon(
        "Settings",
        "M3 8h3.5",
        "M11.5 8H21",
        "M3 16h12.5",
        "M18.5 16H21",
        "M9 6.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 1 0 0-3",
        "M16 14.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 1 0 0-3",
    )

    /** 记忆：带书签的书，代表配方与偏好。 */
    val Memory: ImageVector = strokeIcon(
        "Memory",
        "M10 2v8l3-3 3 3V2",
        "M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H19a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H6.5a1 1 0 0 1 0-5H20",
    )

    /** 凭据：钥匙。 */
    val Key: ImageVector = strokeIcon(
        "Key",
        "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1" +
            "a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z",
        "M16.5 7.5h.01",
    )

    /** 定时：时钟。 */
    val Clock: ImageVector = strokeIcon(
        "Clock",
        "M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20",
        "M12 6v6l4 2",
    )

    /** 折叠箭头。 */
    val ChevronDown: ImageVector = strokeIcon("ChevronDown", "m6 9 6 6 6-6")

    /** 返回箭头。 */
    val ArrowLeft: ImageVector = strokeIcon("ArrowLeft", "M19 12H5", "m12 19-7-7 7-7")

    /** 关闭。 */
    val Close: ImageVector = strokeIcon("Close", "M18 6 6 18", "m6 6 12 12")
}

/**
 * 功能：按若干条 SVG path 串构造一个纯描边图标
 * 参数：name 图标名；commands SVG path 串（Lucide 原样）
 * 返回：可直接交给 Icon 使用的 ImageVector
 *
 * 描边用纯黑占位，实际颜色由 Icon(tint=...) 的 ColorFilter 统一改色，
 * 因此不需要为每个使用场景复制一份图标。
 */
private fun strokeIcon(name: String, vararg commands: String): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    commands.forEach { command ->
        builder.addPath(
            pathData = PathParser().parsePathString(command).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}
