package com.neptine.mimir.service.schedule

import com.neptine.mimir.core.contract.ScheduleRepeat
import java.time.Instant
import java.time.ZoneId

/**
 * 定时任务的时间计算（设计文档 §5.6）。
 *
 * 只做纯计算，不碰闹钟与数据库：时间推进规则必须能被单独核对，
 * 混进调度器后一旦算错只会表现成"到点没跑"，很难定位。
 */
object ScheduledTime {

    /**
     * 功能：计算下一次触发时间
     * 参数：now 当前时间戳（毫秒）；hour 小时；minute 分钟；
     *       repeat 重复方式；days 星期集合（1=周一 … 7=周日）
     * 返回：下一次触发的绝对时间（毫秒）
     *
     * ONCE 与 DAILY 的差别只在触发后是否停用：两者都取"今天该时刻，过期则顺延一天"。
     * WEEKLY 从今天开始逐日找，最多找 8 天（今天剩余 + 一整周），不会死循环。
     */
    fun next(
        now: Long,
        hour: Int,
        minute: Int,
        repeat: ScheduleRepeat,
        days: Set<Int>,
    ): Long {
        val zone = ZoneId.systemDefault()
        val base = Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime()
        var candidate = base.toLocalDate().atTime(hour, minute)
        when (repeat) {
            ScheduleRepeat.ONCE, ScheduleRepeat.DAILY -> {
                if (!candidate.isAfter(base)) candidate = candidate.plusDays(1)
            }
            ScheduleRepeat.WEEKLY -> {
                val allowed = days.ifEmpty { setOf(base.dayOfWeek.value) }
                var guard = 0
                while ((!candidate.isAfter(base) || candidate.dayOfWeek.value !in allowed) && guard < 8) {
                    candidate = candidate.plusDays(1)
                    guard++
                }
            }
        }
        return candidate.atZone(zone).toInstant().toEpochMilli()
    }
}
