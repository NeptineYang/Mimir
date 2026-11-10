package com.neptine.mimir.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.neptine.mimir.agent.Orchestrator
import com.neptine.mimir.core.contract.MemorySource
import com.neptine.mimir.core.contract.MemoryStatus
import com.neptine.mimir.core.contract.Preference
import com.neptine.mimir.data.db.MimirDatabase
import com.neptine.mimir.data.memory.ProcedureCodec
import com.neptine.mimir.observation.AuditScreenshotStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 历史页的时间筛选口径。
 */
enum class HistoryFilter {
    TODAY,
    WEEK,
    MONTH,
    ALL,
    ;

    /**
     * 功能：计算筛选起点
     * 参数：now 当前时间戳（便于测试注入）
     * 返回：起始毫秒；ALL 返回 0
     *
     * 周按 ISO 口径从周一开始；"今天/本周/本月"都以本地时区的零点为界。
     */
    fun sinceMillis(now: Long = System.currentTimeMillis()): Long {
        if (this == ALL) return 0L
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = when (this) {
            WEEK -> today.with(DayOfWeek.MONDAY)
            MONTH -> today.withDayOfMonth(1)
            else -> today
        }
        return start.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

/**
 * 历史任务列表的一页。
 *
 * @property rows 当前页任务
 * @property page 当前页（从 0 开始，已按页数夹紧）
 * @property pageCount 总页数；0 表示没有任务
 * @property total 当前筛选下的总条数
 */
data class HistoryPage(
    val rows: List<TaskRow>,
    val page: Int,
    val pageCount: Int,
    val total: Int,
)

/**
 * 历史页里展开的那一条任务的细节。
 *
 * @property taskId 当前展开的任务；同一时刻只允许展开一条
 * @property steps 该任务的步骤摘要（不含证据字段）
 */
data class HistoryDetail(
    val taskId: String,
    val steps: List<AuditRow>,
)

/**
 * 历史页状态持有者。
 *
 * 只观察终态任务：正在进行或等待人处理的任务归任务页（01），这里存的是"跑完/失败/被放弃"的记录。
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val database: MimirDatabase,
    private val screenshots: AuditScreenshotStore,
    private val orchestrator: Orchestrator,
) : ViewModel() {

    private val filter = MutableStateFlow(HistoryFilter.ALL)
    private val page = MutableStateFlow(0)

    /** 当前时间筛选口径，供界面高亮。 */
    val selectedFilter: StateFlow<HistoryFilter> = filter.asStateFlow()

    /**
     * 筛选后的全部任务；null 表示首帧尚未到达。
     *
     * 筛选放在 SQL（observeFinishedSince），不在 UI 层过滤整表；
     * 分页在拿到列表后切片——任务量级不大，且需要总数算页数。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val filtered: StateFlow<List<TaskRow>?> = filter
        .flatMapLatest { current ->
            database.taskDao().observeFinishedSince(current.sinceMillis())
                .map { list -> list.map { it.toRow() } }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前页；null 表示首帧尚未到达。 */
    val pageState: StateFlow<HistoryPage?> = combine(filtered, page) { list, index ->
        if (list == null) {
            null
        } else {
            val pageCount = if (list.isEmpty()) 0 else (list.size + PAGE_SIZE - 1) / PAGE_SIZE
            val safePage = index.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            HistoryPage(
                rows = list.drop(safePage * PAGE_SIZE).take(PAGE_SIZE),
                page = safePage,
                pageCount = pageCount,
                total = list.size,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 功能：切换时间筛选
     * 参数：value 目标口径
     * 返回：无
     */
    fun setFilter(value: HistoryFilter) {
        if (filter.value == value) return
        filter.value = value
        page.value = 0
    }

    /** 功能：上一页；参数：无；返回：无。 */
    fun goPrev() {
        page.value = ((pageState.value?.page ?: page.value) - 1).coerceAtLeast(0)
    }

    /** 功能：下一页；参数：无；返回：无。 */
    fun goNext() {
        val current = pageState.value ?: return
        if (current.page < current.pageCount - 1) {
            page.value = current.page + 1
        }
    }

    /**
     * 运行统计；null 表示首帧尚未到达。
     *
     * 与任务列表分开收集：统计要读整张审计表，和列表绑在同一个 Flow 里会让
     * 每次审计追加都重新映射一遍任务行（包括解析 spec_json）。
     */
    val stats: StateFlow<RunStats?> = combine(
        database.taskDao().observeFinished(),
        database.auditDao().observeStatsRows(),
    ) { tasks, audits -> computeRunStats(tasks, audits) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 功能：重新打开一条历史任务的结果页
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 结果页还在副屏上时整条任务搬到主屏并置顶；已经释放（超过保留期或进程重启）
     * 就退回按包名打开那个 App——页面状态回不去，但至少不用用户自己去找图标。
     */
    fun openResult(taskId: String) {
        viewModelScope.launch { runCatching { orchestrator.openResultOnMain(taskId) } }
    }

    /** 当前展开的任务标识；null 表示全部收起。 */
    private val expandedTaskId = MutableStateFlow<String?>(null)

    /**
     * 展开项的任务摘要与步骤摘要。
     *
     * 用 flatMapLatest 按需订阅审计表：收起时退化成 flowOf(null)，
     * 不订阅任何任务，也就不会为看不见的行持续查库。
     * 这里只取步骤行做摘要，证据字段属于审计事件详情，不在历史里展开。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val detail: StateFlow<HistoryDetail?> = expandedTaskId
        .flatMapLatest { taskId ->
            if (taskId == null) {
                flowOf<HistoryDetail?>(null)
            } else {
                database.auditDao().observeByTask(taskId).map { entities ->
                    HistoryDetail(taskId = taskId, steps = entities.map { it.toRow() })
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 功能：展开或收起某条历史任务
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 手风琴语义：再点同一条即收起；点另一条时上一条自动收起，
     * 避免多条同时展开把页面拉成几屏长的证据流。
     */
    fun toggleExpanded(taskId: String) {
        expandedTaskId.value = if (expandedTaskId.value == taskId) null else taskId
    }

    /**
     * 功能：把一条历史任务的上下文保存成偏好
     * 参数：draft 偏好草稿；onFailure 保存失败回调；onSaved 保存成功回调
     * 返回：无
     *
     * 来源记 USER_TAUGHT、置信度 0.9：这是"任务后确认"，带着那一次任务的上下文，
     * 未必是长期偏好；手写偏好的 1.0 不该被它拉平。
     */
    fun savePreference(
        draft: PreferenceDraft,
        onFailure: (String) -> Unit,
        onSaved: () -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                val preference = Preference(
                    id = UUID.randomUUID().toString(),
                    scope = draft.scope.trim().ifEmpty { null },
                    category = draft.category,
                    subject = draft.subject.trim().ifEmpty { null },
                    title = draft.title.trim(),
                    content = draft.content.trim(),
                    value = draft.value.trim().ifEmpty { draft.content.trim() },
                    confidence = TAUGHT_PREFERENCE_CONFIDENCE,
                    source = MemorySource.USER_TAUGHT,
                    status = MemoryStatus.ACTIVE,
                    hitCount = 0,
                    lastUsedAt = 0L,
                    createdAt = System.currentTimeMillis(),
                )
                database.memoryDao().upsertPreference(ProcedureCodec.toEntity(preference))
            }.onSuccess { onSaved() }
                .onFailure { onFailure(it.message ?: "保存失败") }
        }
    }

    /**
     * 功能：删除一条历史任务
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 数据库四张表在同一个事务里删：任务行、审计、写入意图、一次性授权。
     * 只删任务行会留下无法再被引用的孤儿记录；截图在事务之后单独清理，
     * 文件系统不参与 SQLite 事务，删失败也不应回滚数据库。
     */
    fun removeTask(taskId: String) {
        viewModelScope.launch {
            database.withTransaction {
                database.taskDao().deleteById(taskId)
                database.auditDao().deleteByTask(taskId)
                database.actionIntentDao().deleteByTask(taskId)
                database.riskGrantDao().deleteByTask(taskId)
            }
            screenshots.deleteTask(taskId)
        }
    }

    private companion object {
        /** 任务后确认的偏好置信度：低于手写 1.0，高于未验证的自动推断。 */
        const val TAUGHT_PREFERENCE_CONFIDENCE = 0.9

        /** 每页任务数。 */
        const val PAGE_SIZE = 20
    }
}
