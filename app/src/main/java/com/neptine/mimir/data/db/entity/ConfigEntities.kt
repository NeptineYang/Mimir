package com.neptine.mimir.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 模型后端配置（设计文档 §2.4）。
 *
 * 刻意只存 api_key_alias，不存密钥本体：data class 的 toString 会把字段打进日志，
 * 密钥交由 EncryptedSharedPreferences 保管，客户端调用时再取出（§4.6）。
 */
@Entity(tableName = "model_config")
data class ModelConfigEntity(
    /** 固定为 "default"，一期只支持一套后端配置。 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** OpenAI 兼容接口的 base_url。 */
    @ColumnInfo(name = "base_url")
    val baseUrl: String,

    /** 密钥在加密存储中的别名。 */
    @ColumnInfo(name = "api_key_alias")
    val apiKeyAlias: String,

    /** Planner 模型名。 */
    @ColumnInfo(name = "planner_model")
    val plannerModel: String,

    /** Executor 模型名。 */
    @ColumnInfo(name = "executor_model")
    val executorModel: String,

    /** Reviewer 模型名。 */
    @ColumnInfo(name = "reviewer_model")
    val reviewerModel: String,

    /** 最后更新时间戳（毫秒）。 */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * 设备能力探针结果（设计文档 §2.2.2）。
 *
 * 落库后在配置页逐项展示，让用户看到"这台机器哪一项不支持"，
 * 而不是遇到一个静默失败的任务。
 */
@Entity(tableName = "device_capability")
data class CapabilityEntity(
    /** 探针编号：P1 虚拟屏 / P2 定向注入 / P3 定向取帧 / P4 无障碍可见性 / P5 零干扰文本输入。 */
    @PrimaryKey
    @ColumnInfo(name = "probe_id")
    val probeId: String,

    /** 是否通过。 */
    @ColumnInfo(name = "passed")
    val passed: Boolean,

    /** 失败原因或降级说明。 */
    @ColumnInfo(name = "detail")
    val detail: String,

    /** 检测时的设备型号，便于区分多台机器的结论。 */
    @ColumnInfo(name = "device_model")
    val deviceModel: String,

    /** 检测时间戳（毫秒）。 */
    @ColumnInfo(name = "checked_at")
    val checkedAt: Long,
)
