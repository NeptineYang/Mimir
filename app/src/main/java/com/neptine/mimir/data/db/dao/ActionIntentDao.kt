package com.neptine.mimir.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.neptine.mimir.data.db.entity.ActionIntentEntity

/** 写入意图账本的读写口（设计文档 §1.3.1）。 */
@Dao
interface ActionIntentDao {

    /**
     * 功能：写入或覆盖一条写入意图
     * 参数：intent 意图实体
     * 返回：无
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(intent: ActionIntentEntity)

    /**
     * 功能：按标识读取写入意图
     * 参数：actionId 动作标识
     * 返回：意图实体；不存在时为 null
     */
    @Query("SELECT * FROM action_intent WHERE action_id = :actionId")
    suspend fun findById(actionId: String): ActionIntentEntity?

    /**
     * 功能：删除一条写入意图
     * 参数：actionId 动作标识
     * 返回：无
     *
     * 只用于"尚未注入就已作废"的意图（例如授权在注入前失效）。已注入过的意图不得删除：
     * 它们可能成为未决写入，是恢复时唯一的核对依据。
     */
    @Query("DELETE FROM action_intent WHERE action_id = :actionId")
    suspend fun deleteById(actionId: String)

    /**
     * 功能：取出某任务下所有未判定结果的写入意图
     * 参数：taskId 任务标识
     * 返回：status 为 PREPARED 或 INJECTING 的意图列表
     *
     * 这些记录是 RECONCILING 的唯一依据：只读核对，禁止自动重放。
     */
    @Query(
        "SELECT * FROM action_intent WHERE task_id = :taskId AND status IN ('PREPARED','INJECTING') " +
            "ORDER BY step_idx ASC",
    )
    suspend fun findUnresolvedByTask(taskId: String): List<ActionIntentEntity>

    /**
     * 功能：把某任务下所有未决写入一次性判成"结果未知"
     * 参数：taskId 任务标识；evidence 证据摘要；updatedAt 更新时间戳
     * 返回：受影响的行数
     *
     * 用一条 UPDATE 而不是逐条读出再写回：恢复扫描与运行期核对都可能在进程刚起来时执行，
     * 逐条写会留下"改了一半"的中间态。判成 UNKNOWN 而不是 FAILED 是刻意的——
     * 它只表示"没核对过"，不表示"没执行"。
     */
    @Query(
        "UPDATE action_intent SET status = 'SETTLED', outcome = 'OUTCOME_UNKNOWN', " +
            "evidence = :evidence, updated_at = :updatedAt " +
            "WHERE task_id = :taskId AND status IN ('PREPARED','INJECTING')",
    )
    suspend fun settleUnresolved(taskId: String, evidence: String, updatedAt: Long): Int

    /**
     * 功能：删除某任务的全部写入意图
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 只用于用户主动删除历史任务：任务记录被移除后，这些意图不再有核对对象。
     */
    @Query("DELETE FROM action_intent WHERE task_id = :taskId")
    suspend fun deleteByTask(taskId: String)
}
