package com.neptine.mimir.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.neptine.mimir.data.db.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * 按 App 聚合的任务统计（投影，不是表）。
 *
 * @property packageName 目标包名
 * @property total 任务总数
 * @property done 已成功（DONE 或 COMPLETED_UNVERIFIED）的任务数
 * @property lastUsedAt 最近一次任务更新时间
 */
data class PackageTaskStats(
    val packageName: String,
    val total: Int,
    val done: Int,
    val lastUsedAt: Long,
)

/** 任务表的读写口（设计文档 §1.4）。 */
@Dao
interface TaskDao {

    /**
     * 功能：写入或覆盖一条任务记录
     * 参数：task 任务实体
     * 返回：无
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    /**
     * 功能：按标识读取任务
     * 参数：taskId 任务标识
     * 返回：任务实体；不存在时为 null
     */
    @Query("SELECT * FROM tasks WHERE task_id = :taskId")
    suspend fun findById(taskId: String): TaskEntity?

    /**
     * 功能：按创建时间倒序观察全部任务
     * 参数：无
     * 返回：任务列表的数据流，表变更时自动重发
     */
    @Query("SELECT * FROM tasks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    /**
     * 功能：观察单个任务
     * 参数：taskId 任务标识
     * 返回：任务实体的数据流；不存在时发 null
     */
    @Query("SELECT * FROM tasks WHERE task_id = :taskId")
    fun observeById(taskId: String): Flow<TaskEntity?>

    /**
     * 功能：观察全部未进入终态的任务
     * 参数：无
     * 返回：按创建时间升序的任务流（最早入队的在最前）
     *
     * 任务页只展示"还没结束"的任务：终态记录移到历史页，避免首页被历史噪音占满。
     */
    @Query(
        "SELECT * FROM tasks WHERE state IN " +
            "('CREATED','PLANNING','EXECUTING','RECONCILING','NEED_HUMAN','VERIFYING') " +
            "ORDER BY created_at ASC",
    )
    fun observeActive(): Flow<List<TaskEntity>>

    /**
     * 功能：观察已进入终态的任务
     * 参数：无
     * 返回：按结束时间倒序的任务流
     *
     * 老数据可能没有 finished_at（升级前结束的任务），用 updated_at 兜底排序，
     * 否则这些记录会全部沉到列表最底下。
     */
    @Query(
        "SELECT * FROM tasks WHERE state IN " +
            "('DONE','DONE_READBACK','COMPLETED_UNVERIFIED','VERIFIED_BY_HUMAN','FAILED','ABORTED') " +
            "ORDER BY COALESCE(finished_at, updated_at) DESC",
    )
    fun observeFinished(): Flow<List<TaskEntity>>

    /**
     * 功能：取出所有未进入终态的任务，用于进程重启后的恢复
     * 参数：无
     * 返回：按创建时间升序的任务列表
     */
    @Query(
        "SELECT * FROM tasks WHERE state NOT IN ('DONE','DONE_READBACK','COMPLETED_UNVERIFIED','VERIFIED_BY_HUMAN','FAILED','ABORTED') " +
            "ORDER BY created_at ASC",
    )
    suspend fun findUnfinished(): List<TaskEntity>

    /**
     * 功能：取出最早入队的一条待执行任务
     * 参数：无
     * 返回：状态为 CREATED 的最早任务；队列为空时为 null
     *
     * 同一时刻只允许一个任务执行（设计文档 §1.2 任务并发边界），因此服务每次只取一条。
     */
    @Query("SELECT * FROM tasks WHERE state = 'CREATED' ORDER BY created_at ASC LIMIT 1")
    suspend fun findNextCreated(): TaskEntity?

    /**
     * 功能：删除一条任务记录
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 两类用途：清理尚未进入执行的 CREATED 任务；用户在历史页删除一条已结束记录。
     * 状态机里 CREATED 只有 → PLANNING 一条出边（设计文档 §1.4），没有 CREATED → ABORTED，
     * 因此"放弃"落地为删除而不是状态转移。历史删除还要同时清掉审计、意图、授权与截图，
     * 由调用方统一处理。
     */
    @Query("DELETE FROM tasks WHERE task_id = :taskId")
    suspend fun deleteById(taskId: String)

    /**
     * 功能：按目标 App 聚合任务用量
     * 参数：无
     * 返回：任务数与成功数，按最近使用倒序
     *
     * 只统计已解析出包名的任务：未解析的任务还没有"归属 App"，进矩阵只会多一行空包名。
     */
    @Query(
        "SELECT package_name AS packageName, COUNT(*) AS total, " +
            "SUM(CASE WHEN state IN ('DONE','DONE_READBACK','COMPLETED_UNVERIFIED','VERIFIED_BY_HUMAN') THEN 1 ELSE 0 END) AS done, " +
            "MAX(updated_at) AS lastUsedAt FROM tasks WHERE package_name <> '' " +
            "GROUP BY package_name ORDER BY lastUsedAt DESC",
    )
    suspend fun packageStats(): List<PackageTaskStats>
}
