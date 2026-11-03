package com.neptine.mimir.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.neptine.mimir.data.db.entity.AuditEntity
import kotlinx.coroutines.flow.Flow

/** 每步审计的读写口（设计文档 §4.4）。 */
@Dao
interface AuditDao {

    /**
     * 功能：追加一条审计记录
     * 参数：entry 审计实体
     * 返回：新记录的自增主键
     */
    @Insert
    suspend fun insert(entry: AuditEntity): Long

    /**
     * 功能：取某任务最近若干步
     * 参数：taskId 任务标识；limit 步数上限
     * 返回：按步序倒序的审计记录
     *
     * 用途是构造 prompt 的"近 3 步历史"，因此不查全表。
     */
    @Query("SELECT * FROM audit WHERE task_id = :taskId ORDER BY step_idx DESC LIMIT :limit")
    suspend fun recentSteps(taskId: String, limit: Int): List<AuditEntity>

    /**
     * 功能：取某任务最近一步
     * 参数：taskId 任务标识
     * 返回：步序最大的一条记录；没有记录时为 null
     *
     * 用途是"继续一次"：用户确认的是刚被闸门拦下的那一步，不是某个历史动作。
     */
    @Query("SELECT * FROM audit WHERE task_id = :taskId ORDER BY step_idx DESC, id DESC LIMIT 1")
    suspend fun lastStep(taskId: String): AuditEntity?

    /**
     * 功能：观察某任务的完整审计时间轴
     * 参数：taskId 任务标识
     * 返回：按步序升序、同一步内按写入顺序的记录流，供逐步回放使用
     *
     * 同一步可能有多条记录（初始观察、动作结果、记忆写入、验证结论），
     * 只按 step_idx 排序会让同一页里的顺序随机跳，回放上一步/下一步就失去意义。
     */
    @Query("SELECT * FROM audit WHERE task_id = :taskId ORDER BY step_idx ASC, id ASC")
    fun observeByTask(taskId: String): Flow<List<AuditEntity>>

    /**
     * 功能：取某任务的全部动作记录，按写入顺序
     * 参数：taskId 任务标识
     * 返回：action_kind 非空的审计行（即真正执行过的动作）
     *
     * 按 id 而不是 step_idx 排序：系统事件（记忆注入、重规划）与动作可能共用同一个步序，
     * 只有 id 能保证真实先后。轨迹重建要的就是真实先后。
     */
    @Query("SELECT * FROM audit WHERE task_id = :taskId AND action_kind <> '' ORDER BY id ASC")
    suspend fun actionsOf(taskId: String): List<AuditEntity>

    /**
     * 功能：删除某任务的全部审计记录
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 只用于用户主动删除历史任务：审计是回放的唯一来源，随任务一起移除。
     */
    @Query("DELETE FROM audit WHERE task_id = :taskId")
    suspend fun deleteByTask(taskId: String)
}
