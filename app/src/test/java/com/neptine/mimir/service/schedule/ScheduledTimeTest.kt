package com.neptine.mimir.service.schedule

import com.neptine.mimir.core.contract.ScheduleRepeat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 定时任务的时间推进规则（设计文档 §5.6）。
 *
 * 跨天、跨周都只算一次纯函数结果：真机上"到点没跑"很难复现，
 * 把边界锁在单测里比等到闹钟不响再排障便宜得多。
 */
class ScheduledTimeTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun useFixedZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    /** DAILY 当天尚未到点：不应顺延到明天。 */
    @Test
    fun dailyKeepsTodayWhenTimeIsAhead() {
        val now = millisOf("2026-11-16T07:00")
        val next = ScheduledTime.next(now, 8, 30, ScheduleRepeat.DAILY, emptySet())
        assertEquals(millisOf("2026-11-16T08:30"), next)
    }

    /** DAILY 当天已过点：顺延到明天同一时刻。 */
    @Test
    fun dailyMovesToTomorrowWhenTimePassed() {
        val now = millisOf("2026-11-16T09:00")
        val next = ScheduledTime.next(now, 8, 30, ScheduleRepeat.DAILY, emptySet())
        assertEquals(millisOf("2026-11-17T08:30"), next)
    }

    /** ONCE 跨午夜：23:59 建 00:01 的任务必须落到次日。 */
    @Test
    fun onceCrossesMidnight() {
        val now = millisOf("2026-11-16T23:59")
        val next = ScheduledTime.next(now, 0, 1, ScheduleRepeat.ONCE, emptySet())
        assertEquals(millisOf("2026-11-17T00:01"), next)
    }

    /** WEEKLY：今天不在允许星期里，跳到下一个允许的星期。 */
    @Test
    fun weeklyWaitsForNextAllowedDay() {
        val now = millisOf("2026-11-16T10:00")
        val tomorrow = LocalDateTime.parse("2026-11-17T10:00").dayOfWeek.value
        val next = ScheduledTime.next(now, 9, 0, ScheduleRepeat.WEEKLY, setOf(tomorrow))
        assertEquals(millisOf("2026-11-17T09:00"), next)
    }

    /** WEEKLY：今天就在允许星期里且时刻未到，保持今天。 */
    @Test
    fun weeklyKeepsTodayWhenAllowedAndAhead() {
        val now = millisOf("2026-11-16T10:00")
        val today = LocalDateTime.parse("2026-11-16T10:00").dayOfWeek.value
        val next = ScheduledTime.next(now, 11, 0, ScheduleRepeat.WEEKLY, setOf(today))
        assertEquals(millisOf("2026-11-16T11:00"), next)
    }

    /** WEEKLY 没选星期时按"今天星期几"回落；已过点则顺延整整一周。 */
    @Test
    fun weeklyEmptyDaysFallsBackToSameWeekdayNextWeek() {
        val now = millisOf("2026-11-16T10:00")
        val next = ScheduledTime.next(now, 9, 0, ScheduleRepeat.WEEKLY, emptySet())
        assertEquals(millisOf("2026-11-23T09:00"), next)
    }

    /**
     * 功能：把本地时间文本转成毫秒时间戳
     * 参数：text ISO 本地时间（yyyy-MM-ddTHH:mm）
     * 返回：固定时区下的时间戳
     */
    private fun millisOf(text: String): Long =
        LocalDateTime.parse(text).atZone(ZoneId.of(ZONE)).toInstant().toEpochMilli()

    private companion object {
        const val ZONE = "Asia/Shanghai"
    }
}