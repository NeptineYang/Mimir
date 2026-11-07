package com.neptine.mimir.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.neptine.mimir.data.db.entity.ScheduledTaskEntity
import kotlinx.coroutines.flow.Flow

/** 定时任务的读写口（设计文档 §5.6）。 */
@Dao
interface ScheduledTaskDao {

    /**
     * 功能：写入或覆盖一条定时任务
     * 参数：task 定时任务行
     * 返回：无
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ScheduledTaskEntity)

    /**
     * 功能：观察全部定时任务
     * 参数：无
     * 返回：按触发时间排序的数据流
     */
    @Query("SELECT * FROM scheduled_task ORDER BY hour ASC, minute ASC, created_at DESC")
    fun observeAll(): Flow<List<ScheduledTaskEntity>>

    /**
     * 功能：读取一条定时任务
     * 参数：id 定时任务标识
     * 返回：定时任务；不存在时为 null
     */
    @Query("SELECT * FROM scheduled_task WHERE id = :id")
    suspend fun find(id: String): ScheduledTaskEntity?

    /**
     * 功能：读取所有启用的定时任务
     * 参数：无
     * 返回：启用中的定时任务列表
     *
     * 用于应用启动与开机后重排闹钟：AlarmManager 的登记在进程重启后仍存在，
     * 但 ROM 清理、应用更新都可能丢掉它，重排是幂等的。
     */
    @Query("SELECT * FROM scheduled_task WHERE enabled = 1")
    suspend fun findEnabled(): List<ScheduledTaskEntity>

    /**
     * 功能：删除一条定时任务
     * 参数：id 定时任务标识
     * 返回：无
     */
    @Query("DELETE FROM scheduled_task WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * 功能：更新下一次触发时间与启用状态
     * 参数：id 标识；triggerAt 下次触发；enabled 是否仍启用
     * 返回：无
     */
    @Query("UPDATE scheduled_task SET trigger_at = :triggerAt, enabled = :enabled WHERE id = :id")
    suspend fun updateTrigger(id: String, triggerAt: Long, enabled: Boolean)

    /**
     * 功能：记录一次触发
     * 参数：id 标识；at 触发时间；taskId 产生的任务标识；state 当前状态
     * 返回：无
     */
    @Query(
        "UPDATE scheduled_task SET last_run_at = :at, last_task_id = :taskId, last_state = :state " +
            "WHERE id = :id",
    )
    suspend fun markRun(id: String, at: Long, taskId: String?, state: String)

    /**
     * 功能：更新上次执行的终态
     * 参数：id 标识；state 终态名
     * 返回：无
     */
    @Query("UPDATE scheduled_task SET last_state = :state WHERE id = :id")
    suspend fun updateLastState(id: String, state: String)
}
