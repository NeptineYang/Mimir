package com.neptine.mimir.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.neptine.mimir.core.contract.RiskLevel
import com.neptine.mimir.core.contract.TaskState

/**
 * 功能：承载 Cohere 设计系统的全部语义色
 * 参数：无（由 LightMimirColors 提供实例）
 * 返回：无
 *
 * 为什么不直接塞进 Material3 的 ColorScheme：coral 与 action-blue 在 M3 里没有语义对位，
 * 一旦挂到 error / tertiary 上，就会被 ripple、focus ring 与动态取色自动消费。
 * 这两类颜色在本项目只做风险与分类标记，禁止当大面积底色，所以留在自定义域里物理隔离。
 */
@Immutable
data class MimirColors(
    /** 默认画布（暖白纸色）。 */
    val canvas: Color,
    /** 浮在画布上的浅色面：输入框、弹窗、卡片。 */
    val surface: Color,
    /** 正文色。 */
    val ink: Color,
    /** 主 CTA 填充与品牌锚点。 */
    val primary: Color,
    /** 品牌纯黑，用于最高对比度的行与图标底色。 */
    val black: Color,
    /** 深绿产品带：任务页状态带、接管页之外的深色区块。 */
    val deepGreen: Color,
    /** 深海军蓝：安全/风控语义，界面不铺满。 */
    val darkNavy: Color,
    /** 暖中性面：列表分组底、代码块底。 */
    val softStone: Color,
    /** 浅绿洗底：已完成/已通过的柔和背景。 */
    val paleGreen: Color,
    /** 浅蓝洗底：编辑型链接区块。 */
    val paleBlue: Color,
    /** 标准分隔细线。 */
    val hairline: Color,
    /** 次级边线，用于输入框与弱分隔。 */
    val borderLight: Color,
    /** 最浅的卡片包边。 */
    val cardBorder: Color,
    /** 元信息灰。 */
    val muted: Color,
    /** 三级文字灰。 */
    val slate: Color,
    /** 次级正文灰。 */
    val bodyMuted: Color,
    /** 编辑型链接与次级强调。 */
    val actionBlue: Color,
    /** 键盘焦点与选中环。 */
    val focusBlue: Color,
    /** 风险标记与分类标签，禁止当底色。 */
    val coral: Color,
    /** 浅珊瑚，仅用于描边与极浅底。 */
    val coralSoft: Color,
    /** 表单聚焦描边。 */
    val formFocus: Color,
    /** 校验失败与 L2B 硬阻断。 */
    val error: Color,
    /** 深色带上的文字。 */
    val onDark: Color,
)

/**
 * 浅色主题令牌实例；本项目 v1 只有浅色主题。
 *
 * 画布取暖白纸色而不是纯白：Cohere 的页面底色是"印刷纸"，
 * 细线与颗粒质感都建立在这层暖调上；纯白底会让 1 物理像素的分隔线显得发灰。
 */
val LightMimirColors = MimirColors(
    canvas = Color(0xFFFAF9F5),
    surface = Color(0xFFFFFDF8),
    ink = Color(0xFF1B1A17),
    primary = Color(0xFF17171C),
    black = Color(0xFF000000),
    deepGreen = Color(0xFF003C33),
    darkNavy = Color(0xFF071829),
    softStone = Color(0xFFF0EDE4),
    paleGreen = Color(0xFFEDF6EC),
    paleBlue = Color(0xFFF1F4FA),
    hairline = Color(0xFFE2DED3),
    borderLight = Color(0xFFE6E2D8),
    cardBorder = Color(0xFFEFEBE1),
    muted = Color(0xFF8E8A7E),
    slate = Color(0xFF6C685C),
    bodyMuted = Color(0xFF55524A),
    actionBlue = Color(0xFF1863DC),
    focusBlue = Color(0xFF4C6EE6),
    coral = Color(0xFFFF7759),
    coralSoft = Color(0xFFFFAD9B),
    formFocus = Color(0xFF9B60AA),
    error = Color(0xFFB30000),
    onDark = Color(0xFFFFFFFF),
)

/** 主题令牌入口；组件通过 LocalMimirColors.current 读取。 */
val LocalMimirColors = staticCompositionLocalOf { LightMimirColors }

/**
 * 功能：把任务状态映射为一个强调色
 * 参数：state 任务状态；colors 主题令牌，默认浅色实例
 * 返回：该状态使用的强调色；chip 的底色与描边由调用方按同一色降透明度派生
 *
 * 不用单独维护一套底色表：Cohere 的标记本身就是"同一色的细描边 + 极浅底"，
 * 从强调色派生可以保证任意新增状态都不会破坏一致性。
 */
fun statusColorOf(state: TaskState, colors: MimirColors = LightMimirColors): Color = when (state) {
    TaskState.CREATED -> colors.slate
    TaskState.PLANNING -> colors.slate
    TaskState.EXECUTING -> colors.deepGreen
    TaskState.RECONCILING -> colors.darkNavy
    TaskState.NEED_HUMAN -> colors.coral
    TaskState.VERIFYING -> colors.focusBlue
    TaskState.DONE -> colors.deepGreen
    // 重读复核弱于双路复验：用信息蓝而不是成功绿，避免和 DONE 混看
    TaskState.DONE_READBACK -> colors.focusBlue
    TaskState.VERIFIED_BY_HUMAN -> colors.deepGreen
    TaskState.COMPLETED_UNVERIFIED -> colors.coral
    TaskState.FAILED -> colors.error
    TaskState.ABORTED -> colors.muted
}

/**
 * 功能：把风险等级映射为强调色（设计文档 §4.2）
 * 参数：level 风险等级；colors 主题令牌，默认浅色实例
 * 返回：该等级使用的强调色
 */
fun riskColorOf(level: RiskLevel, colors: MimirColors = LightMimirColors): Color = when (level) {
    RiskLevel.L0 -> colors.slate
    RiskLevel.L1 -> colors.bodyMuted
    RiskLevel.L2A -> colors.coral
    RiskLevel.L2B -> colors.error
}
