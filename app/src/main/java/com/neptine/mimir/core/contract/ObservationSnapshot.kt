package com.neptine.mimir.core.contract

/**
 * 一致观察快照（设计文档 §1.3.1）。
 *
 * 采集顺序为"树 A → 截图 → 树 B"；仅当两次树哈希、包名、Activity、实际 displayId 与
 * display_generation 全部一致时才生成 observationId，否则整组丢弃重采。
 * 两段哈希都保留，审计才能证明"这一次观察确实一致"，而不是只能看到一个结果值。
 */
data class ObservationSnapshot(
    /** 观察编号；动作必须携带它，执行前重新观测比对。 */
    val observationId: String,

    /** 实际 displayId，必须与该副屏句柄一致。 */
    val displayId: Int,

    /** 副屏代次；虚拟屏重建后旧观察与旧授权自动失效。 */
    val displayGeneration: Long,

    /** 前台包名。 */
    val packageName: String,

    /** 前台 Activity。 */
    val activityName: String,

    /** 第一次树采集的结构哈希。 */
    val treeHashBefore: String,

    /** 第二次树采集的结构哈希；与前者不一致即整组作废。 */
    val treeHashAfter: String,

    /** 已编号的候选元素（#1..#N）。 */
    val elements: List<UiElement>,

    /** 超出编号上限、未列出的可见元素数量。 */
    val overflowCount: Int,

    /** 纯视觉模式下由 OCR 生成的元素表；树可用时为空。 */
    val ocrElements: List<UiElement>,

    /** 送模型的截图；取帧失败时为 null。 */
    val image: ImageFrame?,

    /** 本帧来自哪条取帧路径，决定可信度与降级判断。 */
    val captureSource: CaptureSource,

    /** 副屏原生宽度（像素），坐标逆映射的依据。 */
    val nativeWidth: Int,

    /** 副屏原生高度（像素），坐标逆映射的依据。 */
    val nativeHeight: Int,

    /** 坐标映射版本：图像尺寸与原生尺寸的换算规则版本。 */
    val coordinateMapVersion: String,

    /** 采集完成时间戳（毫秒）。 */
    val capturedAt: Long,

    /** 本次观察的通道：控件树或纯视觉降级。 */
    val mode: ObservationMode = ObservationMode.TREE,

    /** 一次性提示/广告分类；未命中为 null。只对当前观察有效，不写入长期记忆。 */
    val overlay: OverlayClassification? = null,
) {
    /**
     * 功能：判断两次树采集是否一致
     * 参数：无
     * 返回：两次树哈希相同为 true
     */
    fun isConsistent(): Boolean = treeHashBefore == treeHashAfter
}

/**
 * 可交互元素；树模式与 OCR 模式共用同一结构。
 *
 * @property index 编号，从 1 开始；仅对当前观察有效，不跨帧复用
 * @property className 控件类名
 * @property text 文本
 * @property contentDescription 无障碍描述
 * @property bounds 原生像素范围
 * @property clickable 是否可点击
 * @property editable 是否可编辑
 * @property password 是否为密码输入框；凭据填充必须命中它（§2.5.8）
 * @property resourceId 树模式的 viewIdResourceName（锚点 A1）；OCR 模式为空
 * @property ancestorPath 祖先路径，用于同文本消歧
 */
data class UiElement(
    val index: Int,
    val className: String,
    val text: String,
    val contentDescription: String,
    val bounds: Bounds,
    val clickable: Boolean,
    val editable: Boolean,
    val resourceId: String? = null,
    val ancestorPath: String? = null,
    val password: Boolean = false,
)

/**
 * 一帧图像；用普通类而非 data class，避免 ByteArray 参与 equals/hashCode。
 *
 * @property width 图像宽度（像素）
 * @property height 图像高度（像素）
 * @property format 编码格式
 * @property bytes 编码后的字节
 */
class ImageFrame(
    val width: Int,
    val height: Int,
    val format: ImageFormat,
    val bytes: ByteArray,
)

/** 图像编码格式；编码只影响字节数，不改变送给模型的视觉 token 数（§2.3.1）。 */
enum class ImageFormat {
    WEBP,
    PNG,
}

/**
 * 观察通道（设计文档 §2.2 观测降级）。
 *
 * TREE 是主路径；VISION_ONLY 表示控件树这次读不出来、只有截图可用。
 * 纯视觉观察没有元素编号，因此只允许本地等待重试或可逆导航，禁止任何元素级写入。
 */
enum class ObservationMode {
    /** 控件树 + 截图。 */
    TREE,

    /** 仅截图；元素表为空。 */
    VISION_ONLY,
}

/** 取帧来源（设计文档 §2.2 三级兜底）。 */
enum class CaptureSource {
    /** 主路径：Shizuku UserService 内 PixelCopy，带 300ms 超时。 */
    PIXELCOPY,

    /** 兜底一：无障碍 takeScreenshot，系统限频约 300ms/次。 */
    ACCESSIBILITY,

    /** 兜底二：screencap 命令 + PNG 解码，最慢但最通用。 */
    SCREENCAP,
}
