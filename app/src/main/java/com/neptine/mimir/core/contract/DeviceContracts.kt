package com.neptine.mimir.core.contract

/**
 * 设备层数据结构（设计文档 §2.2）。
 */

/**
 * 建屏参数。
 *
 * 默认 720×1600 @ 293dpi：与 1080×2400 @ 440dpi 在 dp 尺寸上近似等效，
 * 但单帧体积从 9.9MB 降到 4.4MB，取帧与跨进程传输耗时约减半（§2.2.1）。
 *
 * @property width 宽（像素）
 * @property height 高（像素）
 * @property dpi 密度
 */
data class DisplaySpec(
    val width: Int = 720,
    val height: Int = 1600,
    val dpi: Int = 293,
)

/**
 * 建屏句柄。
 *
 * displayId 与 generation 都不可跨进程恢复复用；虚拟屏重建后 generation 递增，
 * 旧的观察、授权与元素编号全部失效。
 *
 * @property displayId 系统分配的显示标识
 * @property generation 本进程内的副屏代次
 * @property spec 建屏参数
 */
data class DisplayHandle(
    val displayId: Int,
    val generation: Long,
    val spec: DisplaySpec,
)

/**
 * 指定屏上的前台应用；用于持续校验实际 displayId。
 *
 * @property packageName 包名
 * @property activityName Activity 全名
 * @property displayId 实际所在显示
 */
data class ForegroundApp(
    val packageName: String,
    val activityName: String,
    val displayId: Int,
)

/**
 * 启动期能力探针编号（设计文档 §2.2.2）。
 *
 * 探针的作用是把"这台机器不支持"从运行时崩溃变成启动时降级，并在配置页逐项展示。
 */
enum class ProbeId {
    /** P1 虚拟屏：建屏后 displayId 有效且能抓到非全黑帧。 */
    P1_VIRTUAL_DISPLAY,

    /** P2 定向注入：返回值为真、目标 displayId 正确、主屏无任何响应。 */
    P2_TARGETED_INJECT,

    /** P3 定向取帧：三级兜底逐条试，取第一条 300ms 内返回非黑帧的路径。 */
    P3_TARGETED_CAPTURE,

    /** P4 无障碍可见性：能否枚举到该 display 的窗口，主屏窗口是否被过滤。 */
    P4_ACCESSIBILITY_VISIBILITY,

    /** P5 零干扰文本输入：主屏键盘、候选、焦点、剪贴板均无变化；一票否决项。 */
    P5_ZERO_INTERFERENCE_TEXT,
}

/**
 * 单项探针结果。
 *
 * @property probeId 探针编号
 * @property passed 是否通过
 * @property detail 失败原因或降级说明
 */
data class ProbeResult(
    val probeId: ProbeId,
    val passed: Boolean,
    val detail: String,
)
