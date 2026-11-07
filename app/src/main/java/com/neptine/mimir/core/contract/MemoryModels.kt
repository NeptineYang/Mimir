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
 * 配方树的一个动作节点（第四层）。
 *
 * @property id 节点标识；用户删改与排序都按它定位
 * @property actionKind 动作类型
 * @property title 用户可读的动作名，默认取锚点文本
 * @property anchor 执行前只校验这一级锚点
 * @property parameterSlots 参数槽位占位名到取值的映射
 */
data class ProcedureAction(
    val id: String,
    val actionKind: ActionKind,
    val title: String,
    val anchor: Anchor?,
    val parameterSlots: Map<String, String> = emptyMap(),
)

/**
 * 配方树的一个页面节点（第三层）。
 *
 * App 与任务是配方的字段（第一、二层），页面与动作才是树的节点；
 * UI 把四层一起展示，但存储上只需这两级——层级再深就变成"通用图"，检索与编辑都会失控。
 *
 * @property id 节点标识
 * @property title 页面名，默认取 Activity 简名，用户可改
 * @property pagePath 页面标识（Activity 名）；同一 Activity 内的多段用序号区分
 * @property signature 页面静态文本集合；用于判断"这一页有没有变"
 * @property actions 该页面上的动作，按执行顺序
 */
data class ProcedurePage(
    val id: String,
    val title: String,
    val pagePath: String?,
    val signature: List<String> = emptyList(),
    val actions: List<ProcedureAction> = emptyList(),
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
 * @property pages 配方树：页面 → 动作；App 与任务是本对象的字段，构成四层
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
    val pages: List<ProcedurePage>,
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
) {

    /**
     * 扁平步骤视图：由 [pages] 派生，供检索、参数填充与旧导出格式使用。
     *
     * 存储真值只有树；steps 是只读投影，不允许单独修改——否则同一份配方会出现两个版本。
     */
    val steps: List<ProcedureStep>
        get() = pages.flatMap { page ->
            page.actions.map { action ->
                ProcedureStep(
                    actionKind = action.actionKind,
                    anchor = action.anchor,
                    pagePath = page.pagePath,
                    parameterSlots = action.parameterSlots,
                )
            }
        }
}

/**
 * 偏好类别（设计文档 §5.6）。
 *
 * 固定枚举而不是自由文本：类别决定注入时的分组与排序，也让"这条偏好该不该影响这次任务"
 * 有可判断的依据；自由文本最后一定会退化成一堆同义标签。
 */
enum class PreferenceCategory {
    /** 平台选择：看视频用 B 站而不是抖音。 */
    PLATFORM,

    /** 地点与常点店：公司附近常点哪家、常点什么。 */
    MERCHANT,

    /** 语气与口头禅：发消息时的称呼、语气、签名。 */
    TONE,

    /** 点餐方式：拼好饭、先领券、默认少辣。 */
    ORDER_STYLE,

    /** 其它经验。 */
    GENERAL,
}

/**
 * 偏好记忆。
 *
 * @property id 唯一标识
 * @property scope 作用域包名；为空表示全局
 * @property category 类别；决定注入分组
 * @property subject 主体/场景：地点、联系人、任务域（VIDEO / FOOD / CHAT）；为空表示通用
 * @property title 人读标题
 * @property content 注入 Planner 的自然语言描述
 * @property value 结构化值（平台名、店名、口味、语气关键词）；用于后续匹配与去重
 * @property confidence 置信度：手写 1.0，任务后确认 0.9，用户可调
 * @property source 来源：USER_WRITTEN / USER_CONFIRMED
 * @property status 状态：ACTIVE / DISABLED
 * @property hitCount 被注入次数
 * @property lastUsedAt 上次注入时间（毫秒）
 * @property createdAt 创建时间戳（毫秒）
 */
data class Preference(
    val id: String,
    val scope: String?,
    val category: PreferenceCategory,
    val subject: String?,
    val title: String,
    val content: String,
    val value: String,
    val confidence: Double,
    val source: MemorySource,
    val status: MemoryStatus,
    val hitCount: Int,
    val lastUsedAt: Long,
    val createdAt: Long,
)

/**
 * 记忆来源。
 *
 * USER_WRITTEN 豁免版本系数与时间衰减，但仍走主路径断言——
 * 人的判断不该被机器衰减，但人的判断也会过期。
 *
 * USER_TAUGHT 表示"用户在一次真实任务后确认"：比手写低一档置信度（0.9），
 * 因为它带着那次任务的上下文，未必是长期偏好。
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
