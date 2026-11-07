package com.neptine.mimir.service.schedule

import com.neptine.mimir.core.contract.ScheduleRepeat
import com.neptine.mimir.core.contract.ScheduledTask
import com.neptine.mimir.data.db.entity.ScheduledTaskEntity

/**
 * 定时任务实体与契约的映射。
 *
 * 解析星期与重复类型时都做兜底：数据库里出现不认识的值时按"一次性、无指定星期"处理，
 * 不让一条坏记录把整张列表的调度打断。
 *
 * @return 定时任务契约
 */
fun ScheduledTaskEntity.toContract(): ScheduledTask = ScheduledTask(
    id = id,
    title = title,
    inputText = inputText,
    hour = hour,
    minute = minute,
    repeat = runCatching { ScheduleRepeat.valueOf(repeatKind) }
        .getOrDefault(ScheduleRepeat.ONCE),
    days = parseDays(repeatDays),
    enabled = enabled,
    allowL2a = allowL2a,
    triggerAt = triggerAt,
    lastRunAt = lastRunAt,
    lastTaskId = lastTaskId,
    lastState = lastState,
    createdAt = createdAt,
)

/**
 * 功能：把契约写回实体
 * 参数：无（扩展函数）
 * 返回：数据库行
 */
fun ScheduledTask.toEntity(): ScheduledTaskEntity = ScheduledTaskEntity(
    id = id,
    title = title,
    inputText = inputText,
    hour = hour,
    minute = minute,
    repeatKind = repeat.name,
    repeatDays = days.sorted().joinToString(","),
    enabled = enabled,
    allowL2a = allowL2a,
    triggerAt = triggerAt,
    lastRunAt = lastRunAt,
    lastTaskId = lastTaskId,
    lastState = lastState,
    createdAt = createdAt,
)

/**
 * 功能：解析星期集合
 * 参数：text 逗号分隔的 1–7
 * 返回：合法星期的集合
 */
fun parseDays(text: String): Set<Int> =
    text.split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { day -> day in 1..7 } }.toSet()
