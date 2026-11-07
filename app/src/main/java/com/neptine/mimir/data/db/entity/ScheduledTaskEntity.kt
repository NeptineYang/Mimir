package com.neptine.mimir.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 定时任务（设计文档 §5.6）。
 *
 * 只存"什么时候跑什么"，不存执行状态：每次触发都会新建一条普通任务记录，
 * 执行结果通过 [lastTaskId] 指回去，定时表本身不复制一套状态机。
 *
 * @property id 定时任务标识
 * @property title 人读标题，默认取任务原话
 * @property inputText 到点时提交给 Agent 的任务输入
 * @property hour 触发小时（0–23）
 * @property minute 触发分钟（0–59）
 * @property repeatKind ONCE / DAILY / WEEKLY
 * @property repeatDays WEEKLY 时生效：1=周一 … 7=周日，逗号分隔
 * @property enabled 是否启用；一次性任务触发后自动置 false
 * @property allowL2a 是否预设批准 L2A 动作；L2B 永远不会被预设授权放行
 * @property triggerAt 下一次触发的绝对时间（毫秒）；由调度器维护
 * @property lastRunAt 上次触发时间（毫秒）
 * @property lastTaskId 上次执行产生的任务标识
 * @property lastState 上次执行的任务终态；执行中为 RUNNING
 * @property createdAt 创建时间戳（毫秒）
 */
@Entity(tableName = "scheduled_task")
data class ScheduledTaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "input_text")
    val inputText: String,

    @ColumnInfo(name = "hour")
    val hour: Int,

    @ColumnInfo(name = "minute")
    val minute: Int,

    @ColumnInfo(name = "repeat_kind")
    val repeatKind: String,

    @ColumnInfo(name = "repeat_days", defaultValue = "''")
    val repeatDays: String = "",

    @ColumnInfo(name = "enabled")
    val enabled: Boolean,

    @ColumnInfo(name = "allow_l2a")
    val allowL2a: Boolean = false,

    @ColumnInfo(name = "trigger_at")
    val triggerAt: Long,

    @ColumnInfo(name = "last_run_at", defaultValue = "0")
    val lastRunAt: Long = 0,

    @ColumnInfo(name = "last_task_id")
    val lastTaskId: String? = null,

    @ColumnInfo(name = "last_state")
    val lastState: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
