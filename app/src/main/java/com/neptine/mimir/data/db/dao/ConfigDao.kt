package com.neptine.mimir.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.neptine.mimir.data.db.entity.CapabilityEntity
import com.neptine.mimir.data.db.entity.ModelConfigEntity
import kotlinx.coroutines.flow.Flow

/** 配置与设备能力的读写口（设计文档 §2.2.2 / §2.4）。 */
@Dao
interface ConfigDao {

    /**
     * 功能：读取模型后端配置
     * 参数：无
     * 返回：配置实体；从未配置过时为 null
     */
    @Query("SELECT * FROM model_config WHERE id = 'default'")
    suspend fun findModelConfig(): ModelConfigEntity?

    /**
     * 功能：写入或覆盖模型后端配置
     * 参数：config 配置实体
     * 返回：无
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModelConfig(config: ModelConfigEntity)

    /**
     * 功能：观察全部探针结果
     * 参数：无
     * 返回：探针结果流，按编号排序
     */
    @Query("SELECT * FROM device_capability ORDER BY probe_id ASC")
    fun observeCapabilities(): Flow<List<CapabilityEntity>>

    /**
     * 功能：写入或覆盖单条探针结果
     * 参数：capability 探针结果实体
     * 返回：无
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCapability(capability: CapabilityEntity)

    /**
     * 功能：读取单条探针结果
     * 参数：probeId 探针编号（如 P5_ZERO_INTERFERENCE_TEXT）
     * 返回：探针结果；从未检测过时为 null
     *
     * Guard 用它做 P5 硬门判定：读实测结果而不是写死常量，人工验证通过后无需改代码。
     */
    @Query("SELECT * FROM device_capability WHERE probe_id = :probeId")
    suspend fun findCapability(probeId: String): CapabilityEntity?
}
