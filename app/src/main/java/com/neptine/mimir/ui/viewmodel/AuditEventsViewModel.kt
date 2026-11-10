package com.neptine.mimir.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neptine.mimir.data.db.dao.AuditDao
import com.neptine.mimir.data.db.dao.AuditEventRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * 审计事件流的筛选口径。
 *
 * @property mode 传给 SQL 的整型模式；0 表示不过滤
 */
enum class AuditFilter(val mode: Int) {
    /** 全部需要人关心的事件。 */
    ALL(0),

    /** 仅被阻断（L2B 或 BLOCK/DENY）。 */
    BLOCKED(1),

    /** 仅需要人确认（CONFIRM）。 */
    CONFIRM(2),

    /** 仅动作结果非 SUCCESS 的事件。 */
    FAILED(3),
}

/**
 * 审计事件列表的一页。
 *
 * @property rows 当前页事件
 * @property page 当前页（从 0 开始，已按页数夹紧）
 * @property pageCount 总页数；0 表示没有事件
 * @property total 筛选后的总条数
 */
data class AuditPage(
    val rows: List<AuditEventRow>,
    val page: Int,
    val pageCount: Int,
    val total: Int,
)

/**
 * 审计事件流的状态持有者。
 *
 * 只观察"需要人关心"的行：L2A/L2B 或非 ALLOW 的处置。全量时间轴属于"按任务回放"，
 * 由 [AuditViewModel] 负责，两者不再共用同一个入口。
 *
 * 分页在 SQL 层做（LIMIT/OFFSET），不把整表读进内存；总数单独 COUNT，
 * 否则分页条无法知道一共几页。
 */
@HiltViewModel
class AuditEventsViewModel @Inject constructor(
    private val auditDao: AuditDao,
) : ViewModel() {

    private val filter = MutableStateFlow(AuditFilter.ALL)
    private val page = MutableStateFlow(0)

    /** 当前筛选口径，供界面高亮。 */
    val selectedFilter: StateFlow<AuditFilter> = filter.asStateFlow()

    /** 当前页；null 表示首帧尚未到达。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pageState: StateFlow<AuditPage?> = combine(filter, page) { current, index ->
        current to index
    }
        .flatMapLatest { (current, index) ->
            combine(
                auditDao.countRiskEvents(current.mode),
                auditDao.observeRiskEvents(current.mode, PAGE_SIZE, index * PAGE_SIZE),
            ) { total, rows ->
                val pageCount = if (total == 0) 0 else (total + PAGE_SIZE - 1) / PAGE_SIZE
                val safePage = index.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                AuditPage(
                    rows = rows,
                    page = safePage,
                    pageCount = pageCount,
                    total = total,
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 功能：切换筛选口径
     * 参数：value 目标口径
     * 返回：无
     *
     * 换口径必须回到第一页：否则新口径只有一页时，界面会停在旧页码上。
     */
    fun setFilter(value: AuditFilter) {
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

    private companion object {
        /** 每页条数：一屏能扫完，又不至于翻页过频。 */
        const val PAGE_SIZE = 20
    }
}
