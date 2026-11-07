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
import com.neptine.mimir.data.db.dao.ScheduledTaskDao
import com.neptine.mimir.data.db.dao.TaskDao
import com.neptine.mimir.data.db.entity.ActionIntentEntity
import com.neptine.mimir.data.db.entity.AuditEntity
import com.neptine.mimir.data.db.entity.CapabilityEntity
import com.neptine.mimir.data.db.entity.ModelConfigEntity
import com.neptine.mimir.data.db.entity.PreferenceEntity
import com.neptine.mimir.data.db.entity.ProcedureActionEntity
import com.neptine.mimir.data.db.entity.ProcedureEntity
import com.neptine.mimir.data.db.entity.ProcedurePageEntity
import com.neptine.mimir.data.db.entity.RiskGrantEntity
import com.neptine.mimir.data.db.entity.ScheduledTaskEntity
import com.neptine.mimir.data.db.entity.TaskEntity
import com.neptine.mimir.data.memory.ProcedureCodec
import com.neptine.mimir.data.memory.ProcedureTree

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
 * v5：审计表增加 prompt/completion token，按任务核算模型成本。
 * v6：审计表增加四段耗时，支撑 §5.4 的步耗时分解与 QoS 面板。
 * v7：配方改成四层树（App → 任务 → 页面 → 动作），新增页面与动作两张节点表；
 *     旧 `mem_procedure.steps_json` 保留为派生快照，不再作为真值。
 * v8：偏好从纯文本升级为结构化（类别/主体/值/置信度/状态/命中计数）。
 * v9：新增定时任务表；到点执行走同一条任务创建链路，不复制状态机。
 */
@Database(
    entities = [
        TaskEntity::class,
        ActionIntentEntity::class,
        RiskGrantEntity::class,
        AuditEntity::class,
        ProcedureEntity::class,
        ProcedurePageEntity::class,
        ProcedureActionEntity::class,
        PreferenceEntity::class,
        ModelConfigEntity::class,
        CapabilityEntity::class,
        ScheduledTaskEntity::class,
    ],
    version = 9,
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

    /**
     * 功能：取得定时任务表访问口
     * 参数：无
     * 返回：ScheduledTaskDao 实例
     */
    abstract fun scheduledTaskDao(): ScheduledTaskDao

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
        /**
         * 4 → 5 迁移：审计表补模型 token 用量。
         *
         * 两列都带 DEFAULT 0：旧行读出 0，含义是"没有记录过用量"，不是"没花 token"。
         * 从这一版起每次模型调用都会记实测值，便于按任务核算成本。
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {

            /**
             * 功能：执行迁移
             * 参数：db 目标版本的数据库连接
             * 返回：无
             */
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audit ADD COLUMN prompt_tokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE audit ADD COLUMN completion_tokens INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 5 → 6 迁移：审计表补四段耗时。
         *
         * 四列都带 DEFAULT 0：旧行读出 0，含义是"这一版之前没有计时"，不是"零耗时"。
         * 统计时只用 total_ms > 0 的行，避免把旧记录算成极快的步。
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {

            /**
             * 功能：执行迁移
             * 参数：db 目标版本的数据库连接
             * 返回：无
             */
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audit ADD COLUMN observe_ms INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE audit ADD COLUMN decide_ms INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE audit ADD COLUMN act_ms INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE audit ADD COLUMN total_ms INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 6 → 7 迁移：把配方从扁平步骤升级成页面/动作两层节点树。
         *
         * `mem_procedure.steps_json` 保留：继续作为"树派生出来的扁平快照"写入，供旧导出格式兼容；
         * 读取、编辑与执行只认节点表，避免两个真值源。
         *
         * 分组规则与写入侧共用 [ProcedureTree]：迁移里另写一套 SQL 分组，迟早会出现两种口径。
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {

            /**
             * 功能：执行迁移
             * 参数：db 目标版本的数据库连接
             * 返回：无
             */
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mem_procedure_page` (" +
                        "`id` TEXT NOT NULL, `procedure_id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`page_path` TEXT, `signature_json` TEXT NOT NULL DEFAULT '', " +
                        "`sort_order` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mem_procedure_page_procedure_id` " +
                        "ON `mem_procedure_page` (`procedure_id`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mem_procedure_action` (" +
                        "`id` TEXT NOT NULL, `procedure_id` TEXT NOT NULL, `page_id` TEXT NOT NULL, " +
                        "`action_kind` TEXT NOT NULL, `title` TEXT NOT NULL, `anchor_json` TEXT, " +
                        "`slots_json` TEXT NOT NULL DEFAULT '', `sort_order` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mem_procedure_action_procedure_id` " +
                        "ON `mem_procedure_action` (`procedure_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mem_procedure_action_page_id` " +
                        "ON `mem_procedure_action` (`page_id`)",
                )
                backfillProcedureTree(db)
            }
        }

        /**
         * 功能：把旧配方的 steps_json 回填成页面/动作节点
         * 参数：db 迁移中的数据库连接
         * 返回：无
         *
         * 旧数据没有观察哈希，只能按"连续相同 pagePath"切页；解析失败的配方保持无节点，
         * 编辑页会如实显示"没有步骤"，而不是编造一棵树。
         */
        private fun backfillProcedureTree(db: SupportSQLiteDatabase) {
            val rows = mutableListOf<Triple<String, String, Long>>()
            db.query("SELECT id, steps_json, created_at FROM mem_procedure").use { cursor ->
                while (cursor.moveToNext()) {
                    rows += Triple(cursor.getString(0), cursor.getString(1).orEmpty(), cursor.getLong(2))
                }
            }
            rows.forEach { (procedureId, stepsJson, createdAt) ->
                val pages = ProcedureTree.groupSteps(ProcedureCodec.decodeStepsForMigration(stepsJson))
                pages.forEachIndexed { pageIndex, page ->
                    db.execSQL(
                        "INSERT INTO mem_procedure_page " +
                            "(id, procedure_id, title, page_path, signature_json, sort_order, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        arrayOf<Any?>(
                            page.id, procedureId, page.title, page.pagePath, "[]", pageIndex, createdAt,
                        ),
                    )
                    page.actions.forEachIndexed { actionIndex, action ->
                        db.execSQL(
                            "INSERT INTO mem_procedure_action " +
                                "(id, procedure_id, page_id, action_kind, title, anchor_json, slots_json, sort_order) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            arrayOf<Any?>(
                                action.id, procedureId, page.id, action.actionKind.name, action.title,
                                ProcedureCodec.encodeAnchor(action.anchor),
                                ProcedureCodec.encodeSlots(action.parameterSlots),
                                actionIndex,
                            ),
                        )
                    }
                }
            }
        }

        /**
         * 7 → 8 迁移：偏好升级为结构化。
         *
         * 全部列带默认值：旧行的 `category=GENERAL`、`value=content`、`confidence=1.0`、
         * `status=ACTIVE`，语义等价于"用户手写的一般经验"，不丢任何一条。
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {

            /**
             * 功能：执行迁移
             * 参数：db 目标版本的数据库连接
             * 返回：无
             */
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mem_preference ADD COLUMN category TEXT NOT NULL DEFAULT 'GENERAL'")
                db.execSQL("ALTER TABLE mem_preference ADD COLUMN subject TEXT")
                db.execSQL("ALTER TABLE mem_preference ADD COLUMN value TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mem_preference ADD COLUMN confidence REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE mem_preference ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'")
                db.execSQL("ALTER TABLE mem_preference ADD COLUMN hit_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mem_preference ADD COLUMN last_used_at INTEGER NOT NULL DEFAULT 0")
                // 旧文本策略没有结构化值：正文即值，保证按值去重/匹配至少有一份可用数据
                db.execSQL("UPDATE mem_preference SET value = content WHERE value = ''")
            }
        }

        /**
         * 8 → 9 迁移：新增定时任务表。
         *
         * 新表没有旧数据要搬；一次性任务触发后由调度器置 `enabled=0`，不做物理删除，
         * 保留"上次执行结果"给用户看。
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {

            /**
             * 功能：执行迁移
             * 参数：db 目标版本的数据库连接
             * 返回：无
             */
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scheduled_task` (" +
                        "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `input_text` TEXT NOT NULL, " +
                        "`hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `repeat_kind` TEXT NOT NULL, " +
                        "`repeat_days` TEXT NOT NULL DEFAULT '', `enabled` INTEGER NOT NULL, " +
                        "`allow_l2a` INTEGER NOT NULL, `trigger_at` INTEGER NOT NULL, " +
                        "`last_run_at` INTEGER NOT NULL DEFAULT 0, `last_task_id` TEXT, " +
                        "`last_state` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

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
