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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 审计页的两种形态：未指定任务时选任务，指定后看时间轴。 */
sealed interface AuditUiState {

    /** 选任务态。 */
    data class Selecting(val tasks: List<TaskRow>) : AuditUiState

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
 * 审计页状态持有者。
 */
@HiltViewModel
class AuditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    taskDao: TaskDao,
    auditDao: AuditDao,
) : ViewModel() {

    /** 路由带过来的任务标识；为空表示进入选任务态。 */
    val taskId: String? = savedStateHandle.get<String>(ARG_TASK_ID)

    private val selectedStepId = MutableStateFlow<Long?>(null)

    private val source: Flow<AuditUiState> = if (taskId == null) {
        taskDao.observeAll().map { tasks -> AuditUiState.Selecting(tasks.map { it.toRow() }) }
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
     * 功能：选中或取消选中某一审计步
     * 参数：id 审计行主键；null 表示收起详情
     * 返回：无
     */
    fun selectStep(id: Long?) {
        selectedStepId.value = id
    }
}
