package com.neptine.mimir.core.contract

/**
 * 记忆模型（设计文档 §2.5）。
 *
 * 两类记忆两个消费者：策略改变"做什么、按什么顺序"，只注入 Planner；
 * 配方改变"点哪里"，只注入 Executor。混在一起会让两边上下文互相稀释。
 *
 * 共同前提：记忆是提示，不是指令。每条被使用的记忆都必须先在当前界面上自证有效。
 */

/**
 * 锚点：回答"如何在当前界面重新找到它"，而不是"它在哪里"。
 *
 * @property level 稳定性级别 A1–A5
 * @property resourceId A1：viewIdResourceName
 * @property text A2/A3：规范化文本
 * @property contentDescription A2：无障碍描述
 * @property className 控件类名
 * @property relativeTo A4：被引用的另一个锚点指纹
 * @property offsetDpX A4：相对横向偏移（dp）
 * @property offsetDpY A4：相对纵向偏移（dp）
 * @property validUntil A5 绝对坐标的有效期（毫秒）；其他级别为空
 */
data class Anchor(
    val level: AnchorLevel,
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val relativeTo: String? = null,
    val offsetDpX: Int? = null,
    val offsetDpY: Int? = null,
    val validUntil: Long? = null,
)

/**
 * 配方中的一步。
 *
 * @property actionKind 动作类型
 * @property anchor 当前步锚点；执行前只校验这一级锚点
 * @property pagePath 该步所在页面路径
 * @property parameterSlots 参数槽位占位名到取值的映射
 */
data class ProcedureStep(
    val actionKind: ActionKind,
    val anchor: Anchor?,
    val pagePath: String?,
    val parameterSlots: Map<String, String> = emptyMap(),
)

/**
 * 配方记忆。
 *
 * @property id 唯一标识
 * @property packageName 归属 App
 * @property taskType 任务类型
 * @property stableKey 包名 + 任务类型 + 页面路径 + 前后置条件的规范化摘要
 * @property category 人的分类标签，不参与检索
 * @property title 配方名
 * @property precondition 入口前置条件锚点
 * @property steps 步骤数组
 * @property postcondition 后置条件描述
 * @property pageSignature 规范化静态文本集合，用于结构相似度比较
 * @property source 来源
 * @property status 状态
 * @property successCount 成功次数（只统计不同 task_id 的独立验证）
 * @property failCount 失败次数
 * @property confidence 派生置信度，公式见 §2.5.4
 * @property appVersionCode 写入时的 App 版本
 * @property validUntil 有效期（毫秒）；仅 A5 兜底锚点需要
 * @property lastVerifiedAt 上次后置条件达成时间（毫秒）
 * @property createdAt 创建时间戳（毫秒）
 */
data class Procedure(
    val id: String,
    val packageName: String,
    val taskType: String,
    val stableKey: String,
    val category: String,
    val title: String,
    val precondition: List<Anchor>,
    val steps: List<ProcedureStep>,
    val postcondition: List<String>,
    val pageSignature: List<String>,
    val source: MemorySource,
    val status: MemoryStatus,
    val successCount: Int,
    val failCount: Int,
    val confidence: Double,
    val appVersionCode: Int,
    val validUntil: Long?,
    val lastVerifiedAt: Long,
    val createdAt: Long,
    /**
     * 最近一次计入成功次数的任务标识。
     *
     * 只用于"不同 task_id 才计数"的去重（§2.5.5），不参与检索、不进 prompt、不导出。
     */
    val lastTaskId: String? = null,
)

/**
 * 策略记忆。
 *
 * @property id 唯一标识
 * @property scope 作用域包名；为空表示全局
 * @property title 标题
 * @property content 正文
 * @property source 来源
 * @property createdAt 创建时间戳（毫秒）
 */
data class Preference(
    val id: String,
    val scope: String?,
    val title: String,
    val content: String,
    val source: MemorySource,
    val createdAt: Long,
)

/**
 * 记忆来源。
 *
 * USER_WRITTEN 豁免版本系数与时间衰减，但仍走主路径断言——
 * 人的判断不该被机器衰减，但人的判断也会过期。
 */
enum class MemorySource {
    AUTO,
    USER_WRITTEN,
    USER_TAUGHT,
}

/** 记忆状态；CANDIDATE 不参与检索。 */
enum class MemoryStatus {
    CANDIDATE,
    ACTIVE,
    STALE,
    DISABLED,
}
