package com.neptine.mimir.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.neptine.mimir.data.db.entity.AuditEntity
import kotlinx.coroutines.flow.Flow

/**
 * 统计面板需要的审计投影（设计文档 §5.4）。
 *
 * 只取聚合用得到的列：审计行的模型原始输出与截图路径很大，统计不需要它们。
 *
 * @property taskId 归属任务
 * @property stepIndex 任务内步序；与 taskId 一起构成"这一步"的唯一键
 * @property outcome 本步结论
 * @property memoryUsed 本步注入的配方；空表示未命中
 * @property totalMs 本步合计耗时；0 表示没有计时（v6 之前的记录或系统事件）
 * @property observeMs 观察段耗时
 * @property decideMs 决策段耗时
 * @property actMs 注入与判定段耗时
 * @property actionKind 动作类型；空表示这一步没有落到设备上的动作
 */
data class AuditStatsRow(
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "step_idx") val stepIndex: Int,
    @ColumnInfo(name = "outcome") val outcome: String?,
    @ColumnInfo(name = "memory_used") val memoryUsed: String?,
    @ColumnInfo(name = "total_ms") val totalMs: Long,
    @ColumnInfo(name = "observe_ms") val observeMs: Long,
    @ColumnInfo(name = "decide_ms") val decideMs: Long,
    @ColumnInfo(name = "act_ms") val actMs: Long,
    @ColumnInfo(name = "action_kind") val actionKind: String,
)

/**
 * 审计事件投影：一行 = 一次需要人关心的高危/非放行动作，带任务标题。
 *
 * @property id 审计行主键，事件详情按它取
 * @property taskId 归属任务
 * @property taskTitle 任务原话；任务被删后为 null
 * @property stepIndex 任务内步序
 * @property timestamp 发生时间
 * @property actionKind 动作类型
 * @property riskLevel 风险等级（L0/L1/L2A/L2B）
 * @property decision 处置结论（ALLOW/CONFIRM/RETRY/BLOCK…）
 * @property outcome 动作结果
 * @property targetText 目标文本；没有时为 null
 * @property subGoal 当步子目标，作为标题兜底
 */
data class AuditEventRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "task_title") val taskTitle: String?,
    @ColumnInfo(name = "step_idx") val stepIndex: Int,
    @ColumnInfo(name = "ts") val timestamp: Long,
    @ColumnInfo(name = "action_kind") val actionKind: String,
    @ColumnInfo(name = "risk_level") val riskLevel: String,
    @ColumnInfo(name = "decision") val decision: String,
    @ColumnInfo(name = "outcome") val outcome: String?,
    @ColumnInfo(name = "target_text") val targetText: String?,
    @ColumnInfo(name = "sub_goal") val subGoal: String,
)

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
     * 功能：观察高危操作事件流，并按筛选口径收窄
     * 参数：mode 0 全部 / 1 仅阻断 / 2 仅确认 / 3 仅失败；limit 每页条数；offset 跳过多少条
     * 返回：风险等级为 L2A/L2B，或处置结论不是 ALLOW 的审计行，按时间倒序
     *
     * 这是审计页的主查询：它回答"哪些操作需要人关心、属于哪条任务"，
     * 不要求用户先选任务。SQL 里过滤而不是在 UI 层筛，避免把整张审计表读进内存；
     * 筛选也用同一个查询的 mode 参数表达，避免为四种口径写四份投影。
     */
    @Query(
        """
        SELECT a.id AS id, a.task_id AS task_id, t.input_text AS task_title,
               a.step_idx AS step_idx, a.ts AS ts, a.action_kind AS action_kind,
               a.risk_level AS risk_level, a.decision AS decision, a.outcome AS outcome,
               a.target_text AS target_text, a.sub_goal AS sub_goal
        FROM audit a LEFT JOIN tasks t ON t.task_id = a.task_id
        WHERE (a.risk_level IN ('L2A', 'L2B') OR a.decision <> 'ALLOW')
          AND (:mode = 0
               OR (:mode = 1 AND (a.risk_level = 'L2B' OR a.decision IN ('BLOCK', 'DENY')))
               OR (:mode = 2 AND a.decision = 'CONFIRM')
               OR (:mode = 3 AND a.outcome IS NOT NULL AND a.outcome <> 'SUCCESS'))
        ORDER BY a.ts DESC, a.id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observeRiskEvents(mode: Int, limit: Int, offset: Int): Flow<List<AuditEventRow>>

    /**
     * 功能：统计筛选后的高危事件总数
     * 参数：mode 与 [observeRiskEvents] 相同的筛选口径
     * 返回：总条数流
     *
     * 分页条需要总页数；不先 COUNT 就得靠"多取一条探测下一页"，
     * 会让页码显示在最后一页之前一直是未知。
     */
    @Query(
        """
        SELECT COUNT(*) FROM audit a
        WHERE (a.risk_level IN ('L2A', 'L2B') OR a.decision <> 'ALLOW')
          AND (:mode = 0
               OR (:mode = 1 AND (a.risk_level = 'L2B' OR a.decision IN ('BLOCK', 'DENY')))
               OR (:mode = 2 AND a.decision = 'CONFIRM')
               OR (:mode = 3 AND a.outcome IS NOT NULL AND a.outcome <> 'SUCCESS'))
        """,
    )
    fun countRiskEvents(mode: Int): Flow<Int>

    /**
     * 功能：按主键观察单条审计
     * 参数：id 审计行主键
     * 返回：该行；被删除后发 null
     *
     * 事件详情只吃这一条，不重查整条时间轴。
     */
    @Query("SELECT * FROM audit WHERE id = :id")
    fun observeById(id: Long): Flow<AuditEntity?>

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
     * 功能：取某任务最近一张动作后截图
     * 参数：taskId 任务标识
     * 返回：截图绝对路径；没有留存画面时为 null
     *
     * 任务卡用它显示"agent 现在停在哪一页"，比一句进度文字直观。
     */
    @Query(
        "SELECT screenshot_after FROM audit WHERE task_id = :taskId " +
            "AND screenshot_after IS NOT NULL AND screenshot_after <> '' " +
            "ORDER BY id DESC LIMIT 1",
    )
    suspend fun latestScreenshot(taskId: String): String?

    /**
     * 功能：取统计面板需要的全部审计投影
     * 参数：无
     * 返回：按写入顺序的全部审计行（只含统计用到的列）
     *
     * 不做 SQL 聚合而由调用方在内存里归类：失败分类的口径经常要随答辩口径调整，
     * 写在 Kotlin 里比每次改 SQL 更容易核对；当前任务量（百级）也不值得上聚合表。
     */
    @Query(
        "SELECT task_id, step_idx, outcome, memory_used, total_ms, observe_ms, decide_ms, act_ms, action_kind " +
            "FROM audit ORDER BY id ASC",
    )
    fun observeStatsRows(): Flow<List<AuditStatsRow>>

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
