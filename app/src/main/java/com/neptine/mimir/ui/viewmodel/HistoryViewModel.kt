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
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    /** 历史任务；null 表示首帧尚未到达，避免先闪一下"没有历史记录"。 */
    val tasks: StateFlow<List<TaskRow>?> = database.taskDao().observeFinished()
        .map { list -> list.map { it.toRow() } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
    }
}
