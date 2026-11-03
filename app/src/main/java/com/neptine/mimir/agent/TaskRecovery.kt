package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.core.contract.allowedTransitions
import com.neptine.mimir.data.db.dao.ActionIntentDao
import com.neptine.mimir.data.db.dao.TaskDao
import com.neptine.mimir.data.db.entity.TaskEntity
import com.neptine.mimir.service.HandoffNotifier
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 进程重启后的任务恢复（设计文档 §1.4 / §5.2 二期"全状态持久化 + 重启恢复"）。
 *
 * 进程被杀时数据库里会留下"看起来还在跑"的任务：状态停在 PLANNING / EXECUTING / RECONCILING /
 * VERIFYING。界面上它会永远显示执行中，用户既不能继续也不能放弃，而且没有第二块副屏可用。
 *
 * 恢复只做一件事：把这些任务如实落成 NEED_HUMAN，说清为什么停下、能不能继续。
 * **不做自动续跑**——进程死在哪一步、注入有没有落地都不可知，自动续跑等于把"结果未知的写入"
 * 重放一遍（§1.3.1 明令禁止）。真正的续跑由用户在通知或接管页显式点「继续」触发，
 * 那时任务行上的计划、游标与预算都还在。
 */
@Singleton
class TaskRecovery @Inject constructor(
    private val taskDao: TaskDao,
    private val intentDao: ActionIntentDao,
    private val audit: AuditRecorder,
    private val notifier: HandoffNotifier,
) {

    /** 恢复扫描跑在 IO 线程；Application 不持有生命周期更长的协程作用域，因此由本类持有。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 功能：在后台启动一次恢复扫描
     * 参数：无
     * 返回：无
     *
     * 必须异步：扫描要读数据库、发通知，放在 Application.onCreate 里同步执行会拖慢冷启动。
     */
    fun launch() {
        scope.launch { recoverInterrupted() }
    }

    /**
     * 功能：把中断在执行态的任务落成人工态
     * 参数：无
     * 返回：无
     *
     * 单个任务失败不影响其它任务；恢复是启动路径上的旁路，任何异常都不允许冒泡到进程启动。
     */
    suspend fun recoverInterrupted() {
        val unfinished = runCatching { taskDao.findUnfinished() }.getOrElse { return }
        unfinished.forEach { task -> runCatching { recover(task) } }
    }

    /**
     * 功能：恢复单个任务
     * 参数：task 未完成任务
     * 返回：无
     *
     * CREATED 交给队列、NEED_HUMAN 保留原挂起原因、终态不在查询范围内，这三类都不动。
     */
    private suspend fun recover(task: TaskEntity) {
        val state = runCatching { TaskState.valueOf(task.state) }.getOrNull() ?: return
        if (state !in INTERRUPTED_STATES) return

        val unresolved = runCatching { intentDao.findUnresolvedByTask(task.taskId) }.getOrDefault(emptyList())
        val reason = reasonOf(task, state, unresolved.size)

        // 有未决写入时先落 RECONCILING，和运行期的处置顺序保持一致；
        // PLANNING 没有 → RECONCILING 这条边（规划阶段还没产生写入），直接转人工。
        var current = task
        if (unresolved.isNotEmpty() && TaskState.RECONCILING in allowedTransitions(state)) {
            current = current.copy(state = TaskState.RECONCILING.name, updatedAt = System.currentTimeMillis())
            taskDao.upsert(current)
        }
        if (TaskState.NEED_HUMAN !in allowedTransitions(TaskState.valueOf(current.state))) return
        current = current.copy(
            state = TaskState.NEED_HUMAN.name,
            suspendReason = reason,
            updatedAt = System.currentTimeMillis(),
        )
        taskDao.upsert(current)

        if (unresolved.isNotEmpty()) {
            intentDao.settleUnresolved(task.taskId, "进程中断后未做只读核对", System.currentTimeMillis())
        }
        audit.recordSystemEvent(
            task = current,
            outcome = if (unresolved.isEmpty()) "PROCESS_INTERRUPTED" else "UNRESOLVED_WRITE",
            result = reason,
        )
        notify(current, hasUnresolved = unresolved.isNotEmpty(), reason = reason)
    }

    /**
     * 功能：按中断情形生成挂起原因
     * 参数：task 任务；state 中断时的状态；unresolved 未决写入条数
     * 返回：给用户看的一句话
     */
    private fun reasonOf(task: TaskEntity, state: TaskState, unresolved: Int): String = when {
        unresolved > 0 ->
            "应用进程中断，有 $unresolved 条写入结果未知；不能自动重放，需要你核对后决定"
        state == TaskState.VERIFYING ->
            "应用进程中断于验证阶段；点「继续」会重新观测并核对结果"
        state == TaskState.PLANNING ->
            "应用进程中断于规划阶段；点「继续」会重新规划后执行"
        task.budgetStepsUsed > 0 ->
            "应用进程中断，任务停在第 ${task.budgetStepsUsed} 步；点「继续」会重新观测后接着做"
        else ->
            "应用进程中断于执行开始前（计划已生成）；点「继续」会重新观测后接着做"
    }

    /**
     * 功能：为恢复出来的挂起任务投递通知
     * 参数：task 任务；hasUnresolved 是否存在未决写入；reason 挂起原因
     * 返回：无
     *
     * 有未决写入时只投"需要你核对"的纯文本通知，不给「继续」按钮——那个按钮会让用户在
     * 不知情的情况下放过一次结果未知的写入。没有未决写入才允许一键继续。
     */
    private suspend fun notify(task: TaskEntity, hasUnresolved: Boolean, reason: String) {
        runCatching {
            if (hasUnresolved) {
                notifier.notifySelfOnly(task.taskId, reason)
            } else {
                notifier.notifyResumable(task.taskId, reason)
            }
        }
    }

    private companion object {
        /** 这些状态说明任务"本来在跑"，进程一死就成了无人推进的僵尸。 */
        val INTERRUPTED_STATES = setOf(
            TaskState.PLANNING,
            TaskState.EXECUTING,
            TaskState.RECONCILING,
            TaskState.VERIFYING,
        )
    }
}
