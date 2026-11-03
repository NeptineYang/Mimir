package com.neptine.mimir.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.neptine.mimir.data.db.entity.PreferenceEntity
import com.neptine.mimir.data.db.entity.ProcedureEntity
import kotlinx.coroutines.flow.Flow

/**
 * 按 App 聚合的配方统计（投影，不是表）。
 *
 * @property packageName 归属包名
 * @property total 配方总数
 * @property active 其中 ACTIVE 的数量
 * @property lastCreatedAt 最近一条配方的创建时间
 */
data class PackageMemoryStats(
    val packageName: String,
    val total: Int,
    val active: Int,
    val lastCreatedAt: Long,
)

/** 记忆表（配方 + 策略）的读写口（设计文档 §2.5）。 */
@Dao
interface MemoryDao {

    /**
     * 功能：写入或覆盖一条配方
     * 参数：procedure 配方实体
     * 返回：无
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProcedure(procedure: ProcedureEntity)

    /**
     * 功能：检索某 App 某任务类型的可用配方
     * 参数：packageName 目标包名；taskType 任务类型
     * 返回：status 为 ACTIVE 的配方列表
     *
     * 检索走平表索引，不走向量库：任务描述已含目标 App，让模型先猜分类再多推理两次是净损失（§2.5.6）。
     */
    @Query(
        "SELECT * FROM mem_procedure WHERE package_name = :packageName AND task_type = :taskType " +
            "AND status = 'ACTIVE' ORDER BY confidence DESC",
    )
    suspend fun findActiveCandidates(packageName: String, taskType: String): List<ProcedureEntity>

    /**
     * 功能：写入或覆盖一条策略
     * 参数：preference 策略实体
     * 返回：无
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreference(preference: PreferenceEntity)

    /**
     * 功能：读取适用策略（全局 + 指定 App）
     * 参数：packageName 目标包名
     * 返回：scope 为空或等于该包名的策略列表
     */
    @Query("SELECT * FROM mem_preference WHERE scope IS NULL OR scope = :packageName")
    suspend fun findPreferences(packageName: String): List<PreferenceEntity>

    /**
     * 功能：观察全部配方，供白盒编辑页展示
     * 参数：无
     * 返回：按更新时间倒序的配方数据流
     *
     * 表里没有 updated_at 列，按 created_at 倒序即可：白盒编辑改的是内容而不是"进度"。
     * 二级排序键用 id：REPLACE 写入会换掉 SQLite 的 rowid，只按时间排会让改过的那条跳到列表最前。
     */
    @Query("SELECT * FROM mem_procedure ORDER BY created_at DESC, id ASC")
    fun observeProcedures(): Flow<List<ProcedureEntity>>

    /**
     * 功能：读取一条配方
     * 参数：id 配方标识
     * 返回：配方；不存在时为 null
     */
    @Query("SELECT * FROM mem_procedure WHERE id = :id")
    suspend fun findProcedure(id: String): ProcedureEntity?

    /**
     * 功能：删除一条配方
     * 参数：id 配方标识
     * 返回：无
     */
    @Query("DELETE FROM mem_procedure WHERE id = :id")
    suspend fun deleteProcedure(id: String)

    /**
     * 功能：观察全部策略
     * 参数：无
     * 返回：按创建时间倒序的策略数据流
     */
    @Query("SELECT * FROM mem_preference ORDER BY created_at DESC, id ASC")
    fun observePreferences(): Flow<List<PreferenceEntity>>

    /**
     * 功能：删除一条策略
     * 参数：id 策略标识
     * 返回：无
     */
    @Query("DELETE FROM mem_preference WHERE id = :id")
    suspend fun deletePreference(id: String)

    /**
     * 功能：按稳定键与写入时的 App 版本取配方
     * 参数：stableKey 稳定键；appVersionCode 写入时版本号
     * 返回：配方；不存在时为 null
     *
     * 与唯一索引 (stable_key, app_version_code) 对齐：App 升级后允许生成新候选，不覆盖旧记录。
     */
    @Query(
        "SELECT * FROM mem_procedure WHERE stable_key = :stableKey " +
            "AND app_version_code = :appVersionCode LIMIT 1",
    )
    suspend fun findByStableKey(stableKey: String, appVersionCode: Int): ProcedureEntity?

    /**
     * 功能：给一条配方记一次失败
     * 参数：id 配方标识
     * 返回：无
     *
     * 只加计数；是否降级由 MemoryWriter 按"连续 2 次后置条件未达成"的阈值决定。
     */
    @Query("UPDATE mem_procedure SET fail_count = fail_count + 1 WHERE id = :id")
    suspend fun bumpFailCount(id: String)

    /**
     * 功能：把配方移入待复核区并同步置信度
     * 参数：id 配方标识；status 新状态；confidence 按计数重算后的置信度
     * 返回：无
     */
    @Query("UPDATE mem_procedure SET status = :status, confidence = :confidence WHERE id = :id")
    suspend fun updateStatusAndConfidence(id: String, status: String, confidence: Double)

    /**
     * 功能：按归属 App 聚合配方数量
     * 参数：无
     * 返回：配方总数、ACTIVE 数与最近创建时间
     */
    @Query(
        "SELECT package_name AS packageName, COUNT(*) AS total, " +
            "SUM(CASE WHEN status = 'ACTIVE' THEN 1 ELSE 0 END) AS active, " +
            "MAX(created_at) AS lastCreatedAt FROM mem_procedure GROUP BY package_name",
    )
    suspend fun packageStats(): List<PackageMemoryStats>
}
