package com.neptine.mimir.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 配方记忆（设计文档 §2.5.2）。
 *
 * 配方不存坐标（A5 兜底除外），只存锚点：锚点回答"如何在当前界面重新找到它"。
 * stable_key 与 app_version_code 联合唯一，App 升级后允许生成新候选而不是覆盖旧记录。
 */
@Entity(
    tableName = "mem_procedure",
    indices = [
        Index(value = ["package_name", "task_type", "status"]),
        Index(value = ["stable_key", "app_version_code"], unique = true),
    ],
)
data class ProcedureEntity(
    /** 配方唯一标识。 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** 归属 App，检索主键之一。 */
    @ColumnInfo(name = "package_name")
    val packageName: String,

    /** 任务类型，如 ORDER_FOOD。 */
    @ColumnInfo(name = "task_type")
    val taskType: String,

    /** 包名 + 任务类型 + 页面路径 + 前后置条件的规范化摘要。 */
    @ColumnInfo(name = "stable_key")
    val stableKey: String,

    /** 人的分类标签（娱乐/财务/社交），不参与检索。 */
    @ColumnInfo(name = "category")
    val category: String,

    /** 配方名，如"领取外卖红包"。 */
    @ColumnInfo(name = "title")
    val title: String,

    /** 入口前置条件：页面签名 + 锚点列表（JSON）。 */
    @ColumnInfo(name = "precondition_json")
    val preconditionJson: String,

    /** 步骤数组：动作 + 当前步锚点 + 页面路径 + 参数槽位（JSON）。 */
    @ColumnInfo(name = "steps_json")
    val stepsJson: String,

    /** 后置条件：文本出现 / 元素状态变化 / 独立结果字段（JSON）。 */
    @ColumnInfo(name = "postcondition_json")
    val postconditionJson: String,

    /** 规范化静态文本集合与页面路径（JSON）。 */
    @ColumnInfo(name = "page_signature_json")
    val pageSignatureJson: String,

    /** 来源：AUTO / USER_WRITTEN / USER_TAUGHT。 */
    @ColumnInfo(name = "source")
    val source: String,

    /** 状态：CANDIDATE / ACTIVE / STALE / DISABLED。 */
    @ColumnInfo(name = "status")
    val status: String,

    /** 成功次数，只统计不同 task_id 的独立验证结果。 */
    @ColumnInfo(name = "success_count")
    val successCount: Int,

    /** 失败次数。 */
    @ColumnInfo(name = "fail_count")
    val failCount: Int,

    /** 派生置信度，公式见 §2.5.4。 */
    @ColumnInfo(name = "confidence")
    val confidence: Double,

    /** 写入时的 App 版本号。 */
    @ColumnInfo(name = "app_version_code")
    val appVersionCode: Int,

    /** 仅 A5 绝对坐标锚点需要的有效期（毫秒）；其他锚点为空。 */
    @ColumnInfo(name = "valid_until")
    val validUntil: Long?,

    /** 上次后置条件达成时间（毫秒）。 */
    @ColumnInfo(name = "last_verified_at")
    val lastVerifiedAt: Long,

    /** 创建时间戳（毫秒）。 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** 最近一次计入成功次数的任务标识；只用于去重，不参与检索。 */
    @ColumnInfo(name = "last_task_id")
    val lastTaskId: String? = null,
)

/**
 * 偏好记忆（设计文档 §2.5.1 / §5.6）。
 *
 * 消费者是 Planner 而非 Executor：它改变"做什么、按什么顺序"，
 * 因此不进逐步 prompt，只在开局注入。
 *
 * v8 起从纯文本升级为结构化偏好：类别 + 主体 + 值 + 置信度 + 状态。
 * 旧数据迁移时 `category=GENERAL`、`value=content`，不丢任何一条。
 */
@Entity(tableName = "mem_preference")
data class PreferenceEntity(
    /** 偏好唯一标识。 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** 作用域：包名表示 App 级，为空表示全局。 */
    @ColumnInfo(name = "scope")
    val scope: String?,

    /** 类别：PLATFORM / MERCHANT / TONE / ORDER_STYLE / GENERAL。 */
    @ColumnInfo(name = "category", defaultValue = "'GENERAL'")
    val category: String = "GENERAL",

    /** 主体/场景：地点、联系人、任务域；为空表示通用。 */
    @ColumnInfo(name = "subject")
    val subject: String? = null,

    /** 人读标题。 */
    @ColumnInfo(name = "title")
    val title: String,

    /** 注入 Planner 的自然语言描述。 */
    @ColumnInfo(name = "content")
    val content: String,

    /** 结构化值（平台名、店名、口味、语气关键词）。 */
    @ColumnInfo(name = "value", defaultValue = "''")
    val value: String = "",

    /** 置信度；手写 1.0，任务后确认 0.9。 */
    @ColumnInfo(name = "confidence", defaultValue = "1.0")
    val confidence: Double = 1.0,

    /** 来源：USER_WRITTEN / USER_TAUGHT / AUTO。 */
    @ColumnInfo(name = "source")
    val source: String,

    /** 状态：ACTIVE / DISABLED。 */
    @ColumnInfo(name = "status", defaultValue = "'ACTIVE'")
    val status: String = "ACTIVE",

    /** 被注入次数。 */
    @ColumnInfo(name = "hit_count", defaultValue = "0")
    val hitCount: Int = 0,

    /** 上次注入时间（毫秒）。 */
    @ColumnInfo(name = "last_used_at", defaultValue = "0")
    val lastUsedAt: Long = 0,

    /** 创建时间戳（毫秒）。 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

/**
 * 配方树的页面节点（设计文档 §5.6，第三层）。
 *
 * 与 [ProcedureEntity] 是 1:N：配方是"App + 任务"，页面是树里可复用的中间层。
 * 不建外键：删除配方时由 DAO 在同一事务里清节点，Room 的级联在当前的批量写入下反而更难控。
 */
@Entity(
    tableName = "mem_procedure_page",
    indices = [Index(value = ["procedure_id"])],
)
data class ProcedurePageEntity(
    /** 页面节点唯一标识。 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** 归属配方。 */
    @ColumnInfo(name = "procedure_id")
    val procedureId: String,

    /** 页面名，用户可改。 */
    @ColumnInfo(name = "title")
    val title: String,

    /** 页面标识（Activity 名）；同一 Activity 内多段时相同。 */
    @ColumnInfo(name = "page_path")
    val pagePath: String?,

    /** 页面静态文本集合（JSON 数组）。 */
    @ColumnInfo(name = "signature_json", defaultValue = "''")
    val signatureJson: String = "",

    /** 树内顺序。 */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    /** 创建时间戳（毫秒）。 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

/**
 * 配方树的动作节点（设计文档 §5.6，第四层）。
 *
 * 只存锚点不存坐标：锚点回答"如何在当前界面重新找到它"（§2.5）。
 */
@Entity(
    tableName = "mem_procedure_action",
    indices = [
        Index(value = ["procedure_id"]),
        Index(value = ["page_id"]),
    ],
)
data class ProcedureActionEntity(
    /** 动作节点唯一标识。 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** 归属配方；冗余一列是为了"按配方整树删除/导出"不必先查页面。 */
    @ColumnInfo(name = "procedure_id")
    val procedureId: String,

    /** 归属页面。 */
    @ColumnInfo(name = "page_id")
    val pageId: String,

    /** 动作类型。 */
    @ColumnInfo(name = "action_kind")
    val actionKind: String,

    /** 用户可读的动作名。 */
    @ColumnInfo(name = "title")
    val title: String,

    /** 锚点（JSON）；没有可断言目标时为空。 */
    @ColumnInfo(name = "anchor_json")
    val anchorJson: String?,

    /** 参数槽位（JSON 对象）。 */
    @ColumnInfo(name = "slots_json", defaultValue = "''")
    val slotsJson: String = "",

    /** 页面内顺序。 */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,
)
