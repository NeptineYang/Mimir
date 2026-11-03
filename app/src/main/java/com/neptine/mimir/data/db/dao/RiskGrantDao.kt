package com.neptine.mimir.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.neptine.mimir.data.db.entity.RiskGrantEntity

/** 一次性授权的读写口（设计文档 §1.3.1 / §4.2）。 */
@Dao
interface RiskGrantDao {

    /**
     * 功能：写入一条授权
     * 参数：grant 授权实体
     * 返回：无
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(grant: RiskGrantEntity)

    /**
     * 功能：按标识读取授权
     * 参数：grantId 授权标识
     * 返回：授权实体；不存在时为 null
     */
    @Query("SELECT * FROM risk_grant WHERE grant_id = :grantId")
    suspend fun findById(grantId: String): RiskGrantEntity?

    /**
     * 功能：原子消费一条授权
     * 参数：grantId 授权标识；consumedAt 消费时间戳（毫秒）；actionId 消费该授权的动作标识
     * 返回：受影响行数；1 表示本次抢到授权，0 表示已被消费或不存在
     *
     * 用单条带条件的 UPDATE 而不是"先查后写"，才能在进程崩溃与重复点击下保持原子性。
     */
    @Query(
        "UPDATE risk_grant SET consumed = 1, consumed_at = :consumedAt, consumed_by_action_id = :actionId " +
            "WHERE grant_id = :grantId AND consumed = 0",
    )
    suspend fun consumeAtomically(grantId: String, consumedAt: Long, actionId: String): Int

    /**
     * 功能：取出某任务下全部授权
     * 参数：taskId 任务标识
     * 返回：按签发时间升序的授权列表
     */
    @Query("SELECT * FROM risk_grant WHERE task_id = :taskId ORDER BY issued_at ASC")
    suspend fun findAllByTask(taskId: String): List<RiskGrantEntity>

    /**
     * 功能：删除某任务的全部授权
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 只用于用户主动删除历史任务：授权随任务失效，留着只会成为无主记录。
     */
    @Query("DELETE FROM risk_grant WHERE task_id = :taskId")
    suspend fun deleteByTask(taskId: String)
}
