package com.neptine.mimir.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neptine.mimir.core.contract.ScheduleRepeat
import com.neptine.mimir.core.contract.ScheduledTask
import com.neptine.mimir.data.db.dao.ScheduledTaskDao
import com.neptine.mimir.service.schedule.ScheduledTaskScheduler
import com.neptine.mimir.service.schedule.ScheduledTime
import com.neptine.mimir.service.schedule.toContract
import com.neptine.mimir.service.schedule.toEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 定时任务列表项。 */
data class ScheduledTaskRow(
    val id: String,
    val title: String,
    val inputText: String,
    val timeText: String,
    val repeatText: String,
    val enabled: Boolean,
    val allowL2a: Boolean,
    val lastState: String?,
)

/**
 * 定时任务编辑草稿。
 *
 * 时间用两个数字文本而不是 TimePicker：输入框少一层系统弹窗，
 * 也便于在模拟器/无 GMS 设备上验证；非法值在保存时统一校验。
 */
data class ScheduledTaskDraft(
    val id: String?,
    val title: String,
    val inputText: String,
    val hourText: String,
    val minuteText: String,
    val repeat: ScheduleRepeat,
    val days: Set<Int>,
    val allowL2a: Boolean,
)

/** 定时任务页状态。 */
data class ScheduledTaskUiState(
    val tasks: List<ScheduledTaskRow>?,
    val draft: ScheduledTaskDraft?,
    val message: String?,
    val error: String?,
)

/**
 * 定时任务页状态持有者（设计文档 §5.6）。
 *
 * 只负责"什么时候跑什么"的增删改与闹钟登记；到点执行由 ScheduledTaskRunner 完成，
 * 列表里的上次结果来自任务表的终态回写。
 */
@HiltViewModel
class ScheduledTaskViewModel @Inject constructor(
    private val dao: ScheduledTaskDao,
    private val scheduler: ScheduledTaskScheduler,
) : ViewModel() {

    private val draftFlow = MutableStateFlow<ScheduledTaskDraft?>(null)
    private val messageFlow = MutableStateFlow<String?>(null)
    private val errorFlow = MutableStateFlow<String?>(null)

    /** 页面状态。 */
    val state: StateFlow<ScheduledTaskUiState> = combine(
        dao.observeAll().map { list -> list.map { it.toContract().toRow() } },
        draftFlow,
        messageFlow,
        errorFlow,
    ) { tasks, draft, message, error ->
        ScheduledTaskUiState(tasks = tasks, draft = draft, message = message, error = error)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ScheduledTaskUiState(null, null, null, null),
        )

    /**
     * 功能：打开新建表单
     * 参数：无
     * 返回：无
     */
    fun startCreate() {
        errorFlow.value = null
        draftFlow.value = ScheduledTaskDraft(
            id = null,
            title = "",
            inputText = "",
            hourText = "08",
            minuteText = "30",
            repeat = ScheduleRepeat.DAILY,
            days = emptySet(),
            allowL2a = false,
        )
    }

    /**
     * 功能：打开编辑表单
     * 参数：id 定时任务标识
     * 返回：无
     */
    fun startEdit(id: String) {
        viewModelScope.launch {
            val entity = dao.find(id) ?: return@launch
            val task = entity.toContract()
            errorFlow.value = null
            draftFlow.value = ScheduledTaskDraft(
                id = task.id,
                title = task.title,
                inputText = task.inputText,
                hourText = "%02d".format(task.hour),
                minuteText = "%02d".format(task.minute),
                repeat = task.repeat,
                days = task.days,
                allowL2a = task.allowL2a,
            )
        }
    }

    /**
     * 功能：更新草稿
     * 参数：draft 新草稿
     * 返回：无
     */
    fun updateDraft(draft: ScheduledTaskDraft) {
        draftFlow.value = draft
    }

    /**
     * 功能：关闭表单
     * 参数：无
     * 返回：无
     */
    fun cancelDraft() {
        draftFlow.value = null
        errorFlow.value = null
    }

    /**
     * 功能：保存定时任务
     * 参数：无
     * 返回：无
     *
     * 保存时算下一次触发并立刻登记闹钟：先落库再登记，登记失败也不会丢配置，
     * 下次冷启动会重排。
     */
    fun saveDraft() {
        val draft = draftFlow.value ?: return
        if (draft.title.isBlank() || draft.inputText.isBlank()) {
            errorFlow.value = "标题与任务内容不能为空"
            return
        }
        val hour = draft.hourText.trim().toIntOrNull()
        val minute = draft.minuteText.trim().toIntOrNull()
        if (hour == null || hour !in 0..23 || minute == null || minute !in 0..59) {
            errorFlow.value = "时间格式不对：小时 0–23，分钟 0–59"
            return
        }
        if (draft.repeat == ScheduleRepeat.WEEKLY && draft.days.isEmpty()) {
            errorFlow.value = "每周任务至少要选一天"
            return
        }
        viewModelScope.launch {
            val existing = draft.id?.let { dao.find(it) }
            val now = System.currentTimeMillis()
            val task = ScheduledTask(
                id = draft.id ?: UUID.randomUUID().toString(),
                title = draft.title.trim(),
                inputText = draft.inputText.trim(),
                hour = hour,
                minute = minute,
                repeat = draft.repeat,
                days = draft.days,
                enabled = existing?.enabled ?: true,
                allowL2a = draft.allowL2a,
                triggerAt = ScheduledTime.next(now, hour, minute, draft.repeat, draft.days),
                lastRunAt = existing?.lastRunAt ?: 0,
                lastTaskId = existing?.lastTaskId,
                lastState = existing?.lastState,
                createdAt = existing?.createdAt ?: now,
            )
            runCatching {
                dao.upsert(task.toEntity())
                scheduler.schedule(task)
            }.onSuccess {
                draftFlow.value = null
                messageFlow.value = "已保存，下次触发 ${task.timeText}"
            }.onFailure { errorFlow.value = it.message ?: "保存失败" }
        }
    }

    /**
     * 功能：切换启停
     * 参数：id 定时任务标识
     * 返回：无
     *
     * 重新启用时重算下一次触发：停用期间错过的时间不该立刻补跑。
     */
    fun toggle(id: String) {
        viewModelScope.launch {
            val entity = dao.find(id) ?: return@launch
            val task = entity.toContract()
            val enabled = !task.enabled
            val next = if (enabled) {
                task.copy(
                    enabled = true,
                    triggerAt = ScheduledTime.next(
                        System.currentTimeMillis(), task.hour, task.minute, task.repeat, task.days,
                    ),
                )
            } else {
                task.copy(enabled = false)
            }
            dao.upsert(next.toEntity())
            if (enabled) scheduler.schedule(next) else scheduler.cancel(id)
        }
    }

    /**
     * 功能：删除定时任务
     * 参数：id 定时任务标识
     * 返回：无
     */
    fun delete(id: String) {
        viewModelScope.launch {
            dao.delete(id)
            scheduler.cancel(id)
            messageFlow.value = "已删除"
        }
    }

    /**
     * 功能：清掉提示条
     * 参数：无
     * 返回：无
     */
    fun dismissNotice() {
        messageFlow.value = null
        errorFlow.value = null
    }

    /**
     * 功能：把契约转成列表行
     * 参数：无（扩展函数）
     * 返回：列表项
     */
    private fun ScheduledTask.toRow(): ScheduledTaskRow = ScheduledTaskRow(
        id = id,
        title = title,
        inputText = inputText,
        timeText = timeText,
        repeatText = repeatText,
        enabled = enabled,
        allowL2a = allowL2a,
        lastState = lastState,
    )
}
