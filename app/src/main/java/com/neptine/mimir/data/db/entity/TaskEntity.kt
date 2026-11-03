package com.neptine.mimir.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 任务与 checkpoint（设计文档 §1.4）。
 *
 * 一行 = 一个任务；恢复所需字段（当前状态、步数、display 代次、未决写入、挂起原因）全部在此，
 * 进程被杀后据此重建，但**元素编号与 displayId 不复用**，恢复时必须重新观测。
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    /** 任务唯一标识。 */
    @PrimaryKey
    @ColumnInfo(name = "task_id")
    val taskId: String,

    /** 任务类型，如 ORDER_FOOD / SEND_MESSAGE；记忆检索与配方 stable_key 的组成部分。 */
    @ColumnInfo(name = "task_type")
    val taskType: String,

    /** 目标应用包名。 */
    @ColumnInfo(name = "package_name")
    val packageName: String,

    /** 用户原始输入的自然语言任务。 */
    @ColumnInfo(name = "input_text")
    val inputText: String,

    /** 已确认的 TaskSpec 序列化结果；任务期间不可被覆盖。 */
    @ColumnInfo(name = "spec_json")
    val specJson: String,

    /** 状态机取值，见 TaskState。 */
    @ColumnInfo(name = "state")
    val state: String,

    /** 当前计划步骤的可读标签，形如 `s1-2 打开我的设备`；规划完成前为空。 */
    @ColumnInfo(name = "current_sub_goal")
    val currentSubGoal: String?,

    /** 任务计划的序列化结果；规划完成前为空串。 */
    @ColumnInfo(name = "plan_json", defaultValue = "''")
    val planJson: String = "",

    /** 计划版本；每次受限重规划递增，旧版本的动作立即失效。 */
    @ColumnInfo(name = "plan_version", defaultValue = "0")
    val planVersion: Int = 0,

    /** 计划游标——子任务下标。 */
    @ColumnInfo(name = "plan_subtask_index", defaultValue = "0")
    val planSubtaskIndex: Int = 0,

    /** 计划游标——子任务内步骤下标。 */
    @ColumnInfo(name = "plan_step_index", defaultValue = "0")
    val planStepIndex: Int = 0,

    /**
     * 执行期会话计数（重规划次数、步骤驳回次数、注入过的配方、越界自愈次数等）。
     *
     * 这些计数只影响"还能不能继续走"，不影响任务本身的目标；但进程被杀后若不恢复，
     * 续跑就会把已经用掉的余量重新算满，等于绕过 MAX_REPLANS / MAX_STEP_REJECTIONS 的护栏。
     */
    @ColumnInfo(name = "session_json", defaultValue = "''")
    val sessionJson: String = "",

    /** 已执行步数，用于预算判定。 */
    @ColumnInfo(name = "step_count")
    val stepCount: Int,

    /** 副屏代次；虚拟屏重建后递增，旧观察与旧授权随之失效。 */
    @ColumnInfo(name = "display_generation")
    val displayGeneration: Long,

    /** 最近一次一致观察的编号。 */
    @ColumnInfo(name = "last_observation_id")
    val lastObservationId: String?,

    /** 未决写入意图的 action_id；非空表示存在未核对的外部副作用。 */
    @ColumnInfo(name = "pending_intent_id")
    val pendingIntentId: String?,

    /** NEED_HUMAN 的挂起原因。 */
    @ColumnInfo(name = "suspend_reason")
    val suspendReason: String?,

    /** 已消耗步数预算。 */
    @ColumnInfo(name = "budget_steps_used")
    val budgetStepsUsed: Int,

    /** 已消耗模型调用次数预算。 */
    @ColumnInfo(name = "budget_model_calls")
    val budgetModelCalls: Int,

    /** 创建时间戳（毫秒）。 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** 最后更新时间戳（毫秒）。 */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    /** 终态时间戳（毫秒）；未结束为空。 */
    @ColumnInfo(name = "finished_at")
    val finishedAt: Long?,
)
