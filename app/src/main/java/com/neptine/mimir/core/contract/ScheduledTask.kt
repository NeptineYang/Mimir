package com.neptine.mimir.core.contract

import java.util.Locale

/**
 * 定时任务的重复方式（设计文档 §5.6）。
 */
enum class ScheduleRepeat {
    /** 只跑一次；触发后自动停用。 */
    ONCE,

    /** 每天同一时刻。 */
    DAILY,

    /** 每周指定几天。 */
    WEEKLY,
}

/**
 * 定时任务契约。
 *
 * 只描述"什么时候跑什么"；每次触发都会新建一条普通任务，执行结果通过 [lastTaskId] 指回去。
 * 定时表不复制任务状态机，避免"定时任务的状态"和"任务本身的状态"出现两个真值。
 *
 * @property id 定时任务标识
 * @property title 人读标题
 * @property inputText 到点提交给 Agent 的任务输入
 * @property hour 触发小时（0–23）
 * @property minute 触发分钟（0–59）
 * @property repeat 重复方式
 * @property days WEEKLY 时生效：1=周一 … 7=周日
 * @property enabled 是否启用
 * @property allowL2a 是否预设批准 L2A 动作；L2B 永远不放行
 * @property triggerAt 下一次触发的绝对时间（毫秒）
 * @property lastRunAt 上次触发时间（毫秒）
 * @property lastTaskId 上次执行产生的任务标识
 * @property lastState 上次执行的任务终态；执行中为 RUNNING
 * @property createdAt 创建时间戳（毫秒）
 */
data class ScheduledTask(
    val id: String,
    val title: String,
    val inputText: String,
    val hour: Int,
    val minute: Int,
    val repeat: ScheduleRepeat,
    val days: Set<Int>,
    val enabled: Boolean,
    val allowL2a: Boolean,
    val triggerAt: Long,
    val lastRunAt: Long,
    val lastTaskId: String?,
    val lastState: String?,
    val createdAt: Long,
) {

    /** 时间文本，如 `08:30`。 */
    val timeText: String get() = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

    /** 重复方式文本。 */
    val repeatText: String
        get() = when (repeat) {
            ScheduleRepeat.ONCE -> "一次"
            ScheduleRepeat.DAILY -> "每天"
            ScheduleRepeat.WEEKLY -> {
                if (days.isEmpty()) "每周" else "每周" + days.sorted().joinToString("、") { dayName(it) }
            }
        }

    private companion object {
        /** 功能：把 1–7 的星期序号转成中文 */
        fun dayName(day: Int): String = when (day) {
            1 -> "一"
            2 -> "二"
            3 -> "三"
            4 -> "四"
            5 -> "五"
            6 -> "六"
            else -> "日"
        }
    }
}
