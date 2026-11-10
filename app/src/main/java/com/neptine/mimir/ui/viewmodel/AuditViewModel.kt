package com.neptine.mimir.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neptine.mimir.data.db.dao.AuditDao
import com.neptine.mimir.data.db.dao.TaskDao
import com.neptine.mimir.ui.ARG_TASK_ID
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 审计页的两种形态：未指定任务时选任务，指定后看时间轴。 */
sealed interface AuditUiState {

    /** 选任务态；任务列表分页在 [AuditViewModel.selectPage]。 */
    data object Selecting : AuditUiState

    /** 回放态。 */
    data class Detail(
        /** 任务摘要；任务已被删除时为 null。 */
        val task: TaskRow?,
        /** 时间轴，按步序升序。 */
        val steps: List<AuditRow>,
        /** 当前选中的一步；未选中为 null。 */
        val selected: AuditRow?,
    ) : AuditUiState
}

/**
 * 选任务列表的一页。
 *
 * @property rows 当前页任务
 * @property page 当前页（从 0 开始，已按页数夹紧）
 * @property pageCount 总页数；0 表示没有任务
 * @property total 总条数
 */
data class AuditSelectPage(
    val rows: List<TaskRow>,
    val page: Int,
    val pageCount: Int,
    val total: Int,
)

/**
 * 审计页状态持有者。
 */
@HiltViewModel
class AuditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val taskDao: TaskDao,
    auditDao: AuditDao,
) : ViewModel() {

    /** 路由带过来的任务标识；为空表示进入选任务态。 */
    val taskId: String? = savedStateHandle.get<String>(ARG_TASK_ID)

    private val selectedStepId = MutableStateFlow<Long?>(null)

    private val selectPageIndex = MutableStateFlow(0)

    private val source: Flow<AuditUiState> = if (taskId == null) {
        flowOf(AuditUiState.Selecting)
    } else {
        combine(
            taskDao.observeById(taskId),
            auditDao.observeByTask(taskId),
            selectedStepId,
        ) { task, steps, selectedId ->
            AuditUiState.Detail(
                task = task?.toRow(),
                steps = steps.map { it.toRow() },
                selected = steps.firstOrNull { it.id == selectedId }?.toRow(),
            )
        }
    }

    /** 页面状态；null 表示首帧尚未到达。 */
    val state: StateFlow<AuditUiState?> = source
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 选任务态的分页列表；详情态为 null。
     *
     * 与事件流一样一页 20 条：排障时任务可能有几十条，一次全列出来既慢又难找。
     */
    val selectPage: StateFlow<AuditSelectPage?> = if (taskId == null) {
        combine(taskDao.observeAll(), selectPageIndex) { tasks, index ->
            val rows = tasks.map { it.toRow() }
            val pageCount = if (rows.isEmpty()) 0 else (rows.size + PAGE_SIZE - 1) / PAGE_SIZE
            val safePage = index.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            AuditSelectPage(
                rows = rows.drop(safePage * PAGE_SIZE).take(PAGE_SIZE),
                page = safePage,
                pageCount = pageCount,
                total = rows.size,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    } else {
        MutableStateFlow<AuditSelectPage?>(null)
    }

    /** 功能：选任务列表上一页；参数：无；返回：无。 */
    fun goPrevSelectPage() {
        selectPageIndex.value = ((selectPage.value?.page ?: 0) - 1).coerceAtLeast(0)
    }

    /** 功能：选任务列表下一页；参数：无；返回：无。 */
    fun goNextSelectPage() {
        val current = selectPage.value ?: return
        if (current.page < current.pageCount - 1) {
            selectPageIndex.value = current.page + 1
        }
    }

    /**
     * 功能：选中或取消选中某一审计步
     * 参数：id 审计行主键；null 表示收起详情
     * 返回：无
     */
    fun selectStep(id: Long?) {
        selectedStepId.value = id
    }

    private companion object {
        /** 选任务列表每页条数。 */
        const val PAGE_SIZE = 20
    }
}
