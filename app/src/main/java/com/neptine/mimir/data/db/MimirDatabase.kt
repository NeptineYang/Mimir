package com.neptine.mimir.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.neptine.mimir.data.db.dao.ActionIntentDao
import com.neptine.mimir.data.db.dao.AuditDao
import com.neptine.mimir.data.db.dao.ConfigDao
import com.neptine.mimir.data.db.dao.MemoryDao
import com.neptine.mimir.data.db.dao.RiskGrantDao
import com.neptine.mimir.data.db.dao.TaskDao
import com.neptine.mimir.data.db.entity.ActionIntentEntity
import com.neptine.mimir.data.db.entity.AuditEntity
import com.neptine.mimir.data.db.entity.CapabilityEntity
import com.neptine.mimir.data.db.entity.ModelConfigEntity
import com.neptine.mimir.data.db.entity.PreferenceEntity
import com.neptine.mimir.data.db.entity.ProcedureEntity
import com.neptine.mimir.data.db.entity.RiskGrantEntity
import com.neptine.mimir.data.db.entity.TaskEntity

/**
 * 本地库（设计文档 §2.1 / §5.2）。
 *
 * 八张表：任务、写入意图、一次性授权、审计、配方、策略、模型配置、设备能力。
 * 不设独立的步进表：审计表已是每步记录的超集，两表并存会出现两个真值源。
 * 不用向量库：记忆检索按 package_name 平表索引 + 页面签名匹配即可（§2.5.6）。
 * exportSchema = false：骨架期表结构仍在变，签订稿后开启 schema 导出与迁移测试。
 *
 * v2：任务表增加计划与游标四列、写入意图表增加计划版本与步骤标识，支撑 §1.3.2 的计划推进。
 * v3：配方表增加 last_task_id，支撑 §2.5.5"只统计不同 task_id"的成功次数去重。
 * v4：任务表增加 session_json，续跑时恢复重规划/步骤驳回等执行期计数（§1.4 checkpoint）。
 */
@Database(
    entities = [
        TaskEntity::class,
        ActionIntentEntity::class,
        RiskGrantEntity::class,
        AuditEntity::class,
        ProcedureEntity::class,
        PreferenceEntity::class,
        ModelConfigEntity::class,
        CapabilityEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class MimirDatabase : RoomDatabase() {

    /**
     * 功能：取得任务表访问口
     * 参数：无
     * 返回：TaskDao 实例
     */
    abstract fun taskDao(): TaskDao

    /**
     * 功能：取得写入意图表访问口
     * 参数：无
     * 返回：ActionIntentDao 实例
     */
    abstract fun actionIntentDao(): ActionIntentDao

    /**
     * 功能：取得一次性授权表访问口
     * 参数：无
     * 返回：RiskGrantDao 实例
     */
    abstract fun riskGrantDao(): RiskGrantDao

    /**
     * 功能：取得审计表访问口
     * 参数：无
     * 返回：AuditDao 实例
     */
    abstract fun auditDao(): AuditDao

    /**
     * 功能：取得记忆表（配方 + 策略）访问口
     * 参数：无
     * 返回：MemoryDao 实例
     */
    abstract fun memoryDao(): MemoryDao

    /**
     * 功能：取得配置与设备能力表访问口
     * 参数：无
     * 返回：ConfigDao 实例
     */
    abstract fun configDao(): ConfigDao

    companion object {

        /**
         * 1 → 2 迁移：补计划与游标字段。
         *
         * 全部用 ADD COLUMN + DEFAULT 完成：旧行保留，任务列表与审计不丢。
         * 列默认值必须与实体上的 `@ColumnInfo(defaultValue = ...)` 一致，
         * 否则 Room 的迁移后校验会报 "Migration didn't properly handle"。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {

            /**
             * 功能：执行迁移
             * 参数：db 目标版本的数据库连接
             * 返回：无
             */
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN plan_json TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN plan_version INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN plan_subtask_index INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN plan_step_index INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE action_intent ADD COLUMN plan_version INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE action_intent ADD COLUMN plan_step_id TEXT")
            }
        }

        /**
         * 2 → 3 迁移：配方表补 last_task_id。
         *
         * 可空列，不需要 DEFAULT；旧行读出 null，按"还没被任何任务计入过"处理。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {

            /**
             * 功能：执行迁移
             * 参数：db 目标版本的数据库连接
             * 返回：无
             */
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mem_procedure ADD COLUMN last_task_id TEXT")
            }
        }

        /**
         * 3 → 4 迁移：任务表补执行期会话计数。
         *
         * 旧行读出空串，按"没有用掉任何重规划/自愈余量"处理；正在跑的任务续跑时
         * 会从默认计数重新开始，这与升级前进程被杀的行为一致。
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {

            /**
             * 功能：执行迁移
             * 参数：db 目标版本的数据库连接
             * 返回：无
             */
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN session_json TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
