package com.neptine.mimir.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neptine.mimir.data.db.dao.AuditDao
import com.neptine.mimir.data.db.dao.TaskDao
import com.neptine.mimir.ui.ARG_EVENT_ID
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 单条审计事件的详情状态。
 *
 * @property taskId 归属任务
 * @property task 任务摘要；任务被删除时为 null
 * @property step 该事件的完整审计行（含证据字段）
 */
data class AuditEventDetail(
    val taskId: String,
    val task: TaskRow?,
    val step: AuditRow,
)

/** 事件详情页三态：加载中 / 已被删除 / 有内容。 */
sealed interface AuditEventUiState {

    /** 首帧未到。 */
    data object Loading : AuditEventUiState

    /** 事件不存在（随任务被删除）。 */
    data object Missing : AuditEventUiState

    /** 正常详情。 */
    data class Loaded(val detail: AuditEventDetail) : AuditEventUiState
}

/**
 * 事件详情持有者：只读这一条审计，再补一次任务标题。
 */
@HiltViewModel
class AuditEventViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    auditDao: AuditDao,
    taskDao: TaskDao,
) : ViewModel() {

    private val eventId: Long = savedStateHandle.get<Long>(ARG_EVENT_ID) ?: -1L

    /** 详情三态；首帧先给 Loading，避免先闪一下"事件已不存在"。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<AuditEventUiState> = auditDao.observeById(eventId)
        .flatMapLatest { entity ->
            if (entity == null) {
                flowOf(AuditEventUiState.Missing)
            } else {
                taskDao.observeById(entity.taskId).map { task ->
                    AuditEventUiState.Loaded(
                        AuditEventDetail(
                            taskId = entity.taskId,
                            task = task?.toRow(),
                            step = entity.toRow(),
                        ),
                    )
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AuditEventUiState.Loading,
        )
}
