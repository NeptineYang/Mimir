package com.neptine.mimir.core.contract

/**
 * 任务不可变约束（设计文档 §1.3.1）。
 *
 * 任务创建时由自然语言解析并交用户确认；此后不能被子目标或模型输出覆盖。
 * Executor、Reviewer、Guard、Verifier 每一步都可读取它。
 */
data class TaskSpec(
    /** 任务标识。 */
    val taskId: String,

    /** 任务类型，如 ORDER_FOOD / SEND_MESSAGE；记忆检索的组成部分。 */
    val taskType: String,

    /** 目标应用包名。 */
    val packageName: String,

    /**
     * 允许 Planner/Executor 使用的候选包名白名单。
     *
     * 用户指定 App 时为单元素集合；PlanSubtask.targetPackage 必须落在这里面，
     * 模型只能缩小范围、不能扩大（§1.3.1）。
     */
    val allowedPackages: List<String> = emptyList(),

    /** 用户原始输入。 */
    val inputText: String,

    /** 结构化参数：商品、规格、数量、收件人、消息正文等；动态值不进配方稳定键。 */
    val parameters: Map<String, String>,

    /** 全局约束。 */
    val constraints: TaskConstraints,

    /** 一次性授权列表。 */
    val riskGrants: List<RiskGrant>,

    /** 完成契约。 */
    val completionContract: CompletionContract,

    /** 预算。 */
    val budget: TaskBudget,

    /**
     * 定时任务的预设授权：到点无人值守时自动批准 L2A 动作。
     *
     * 只对 L2A 生效，L2B（支付/验证码/人脸）仍由 Guard 硬阻断。
     * 与 `riskGrants` 的一次性授权是两套语义：预设授权不绑定某个具体动作，
     * 执行时仍要按当前页面重新断言目标；开启它的定时任务必须在界面上明确告知用户。
     */
    val autoApproveL2a: Boolean = false,
)

/**
 * 全局约束。
 *
 * @property maxAmountCents 金额上限（分）；为空表示不限制
 * @property forbiddenActions 禁止动作描述，如"不要勾选自动续费"
 * @property notes 其他约束原文，交给 Reviewer 逐条核对
 */
data class TaskConstraints(
    val maxAmountCents: Long? = null,
    val forbiddenActions: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

/**
 * 一次性授权（设计文档 §4.2）。
 *
 * 授权绑定动作类型、参数摘要、目标包名与完成边界，只能使用一次；
 * 首次注入前原子消费，重复点击、页面变化、崩溃后的未知结果都不得复用。
 *
 * @property grantId 授权标识
 * @property actionKind 绑定的动作类型
 * @property paramsDigest 绑定的参数摘要
 * @property targetPackage 绑定的目标包名
 * @property scopeText 完成边界，如"仅创建一笔待支付订单，不含支付"
 * @property source 授权来源：自动生成或当次确认
 * @property issuedAt 签发时间戳（毫秒）
 * @property expiresAt 过期时间戳（毫秒）；为空表示随任务结束失效
 */
data class RiskGrant(
    val grantId: String,
    val actionKind: ActionKind,
    val paramsDigest: String,
    val targetPackage: String,
    val scopeText: String,
    val source: GrantSource,
    val issuedAt: Long,
    val expiresAt: Long? = null,
)

/** 授权来源。 */
enum class GrantSource {
    /** 原始任务已含"下单"且参数足以绑定时自动生成。 */
    AUTO,

    /** 参数不完整，提交前当次确认后生成。 */
    DEFERRED_CONFIRM,
}

/**
 * 完成契约（设计文档 §4.4.1）。
 *
 * @property successBoundary 成功边界描述，如"订单已创建且用户未付款"
 * @property requiredFields 必须逐项核对的字段名，如 商品/数量/规格/收件人
 * @property allowedHandoffPoints 允许的人工交接点描述
 * @property requireSingleResult 是否断言目标结果恰好出现一次；双路复验硬要求
 */
data class CompletionContract(
    val successBoundary: String,
    val requiredFields: List<String>,
    val allowedHandoffPoints: List<String> = emptyList(),
    val requireSingleResult: Boolean = true,
)

/**
 * 预算（设计文档 §1.3.1 / §5.4）。
 *
 * @property maxSteps 最大步数；一期任务控制在 15–25 步，硬上限 40
 * @property maxDurationMs 最大总时长（毫秒）
 * @property maxModelCalls 最大模型调用次数
 * @property maxStepDurationMs 单步最大耗时（毫秒），超时按 OUTCOME_UNKNOWN 处理
 */
data class TaskBudget(
    val maxSteps: Int = 40,
    // 10 分钟在高德/淘宝这类多层页面 + 非流式视觉请求下不够用：真机实测
    // 高德走到选择终点时已用 8 步 / 8 分钟，随后被时长预算判死。
    // 20 分钟给复杂 App 留出完成空间；超时仍然兜底，不是无限等待。
    // 30 分钟：美团外卖这类"首页→外卖→搜索→选店→选品→加购"的链路要 18–25 步，
    // 而 glm-5.3-flash 的 ttfb 实测有 200 秒级长尾（单步就吃掉 3 分钟），
    // 20 分钟在 18 步处被判死。放长只是给"慢模型 + 长链路"留余量，步数与调用数上限不变。
    val maxDurationMs: Long = 30 * 60 * 1000L,
    val maxModelCalls: Int = 60,
    // 120 秒：取消式超时不是"事后判定"，一旦触发就会打断这次模型调用。
    // 20 秒在 glm-5.3-flash 上过严（实测 ttfb 常见 10–35 秒、长尾 200 秒），
    // 会在第 1–2 步就把正常波动的任务判死；120 秒覆盖 P95，长尾仍会被取消。
    val maxStepDurationMs: Long = 120_000L,
)
