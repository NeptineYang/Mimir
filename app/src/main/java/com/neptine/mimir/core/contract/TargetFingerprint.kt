package com.neptine.mimir.core.contract

/**
 * 目标身份（设计文档 §1.3.1）。
 *
 * 执行前复验比较的是"目标是否还在原位"，而不是"坐标是否相同"，
 * 因此指纹必须同时能表达树模式与纯视觉模式。
 */
data class TargetFingerprint(
    /** 指纹来源模式。 */
    val mode: TargetMode,

    /** 锚点稳定性级别；纯视觉模式通常为 A5。 */
    val anchorLevel: AnchorLevel,

    /** A1：viewIdResourceName；多数 release 包被混淆或置空。 */
    val resourceId: String? = null,

    /** A2/A3：规范化文本。 */
    val text: String? = null,

    /** A2：无障碍描述，与 text 一起参与消歧。 */
    val contentDescription: String? = null,

    /** 控件类名。 */
    val className: String? = null,

    /** 祖先路径，用于同文本元素消歧。 */
    val ancestorPath: String? = null,

    /** 树模式下的原生像素范围。 */
    val bounds: Bounds? = null,

    /** 纯视觉模式的 OCR 文本。 */
    val ocrText: String? = null,

    /** 纯视觉模式的归一化范围（0–1000）。 */
    val normalizedBox: NormalizedBox? = null,

    /** 纯视觉模式的局部图像指纹，用于目标区域复验。 */
    val localImageHash: String? = null,
)

/** 指纹来源：树或纯视觉。 */
enum class TargetMode {
    TREE,
    VISION,
}

/**
 * 锚点稳定性分级（设计文档 §2.5.3）。
 *
 * 级别越高越稳；A5 绝对坐标仅作兜底，必须带有效期，连续 2 次失败即禁用。
 */
enum class AnchorLevel {
    A1,
    A2,
    A3,
    A4,
    A5,
}
