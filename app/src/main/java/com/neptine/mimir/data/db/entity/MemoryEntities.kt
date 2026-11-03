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
 * 策略记忆（设计文档 §2.5.1）。
 *
 * 消费者是 Planner 而非 Executor：它改变"做什么、按什么顺序"，
 * 因此不进逐步 prompt，只在开局注入。
 */
@Entity(tableName = "mem_preference")
data class PreferenceEntity(
    /** 策略唯一标识。 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** 作用域：包名表示 App 级，为空表示全局。 */
    @ColumnInfo(name = "scope")
    val scope: String?,

    /** 策略标题。 */
    @ColumnInfo(name = "title")
    val title: String,

    /** 策略正文。 */
    @ColumnInfo(name = "content")
    val content: String,

    /** 来源：USER_WRITTEN / AUTO。 */
    @ColumnInfo(name = "source")
    val source: String,

    /** 创建时间戳（毫秒）。 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
