package com.neptine.mimir.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.neptine.mimir.data.db.MimirDatabase
import com.neptine.mimir.observation.AuditScreenshotStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

    /** 历史任务；null 表示首帧尚未到达，避免先闪一下"没有历史记录"。 */
    val tasks: StateFlow<List<TaskRow>?> = database.taskDao().observeFinished()
        .map { list -> list.map { it.toRow() } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
}
