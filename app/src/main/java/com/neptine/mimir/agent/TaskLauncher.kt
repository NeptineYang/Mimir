package com.neptine.mimir.agent

import android.content.Context
import com.neptine.mimir.core.contract.CompletionContract
import com.neptine.mimir.core.contract.TaskBudget
import com.neptine.mimir.core.contract.TaskConstraints
import com.neptine.mimir.core.contract.TaskSpec
import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.data.db.dao.TaskDao
import com.neptine.mimir.data.db.entity.TaskEntity
import com.neptine.mimir.service.AgentForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 任务创建入口（设计文档 §1.3 首步）。
 *
 * 手动任务与定时任务共用这一条路径：两者的差别只在 [TaskSpec.autoApproveL2a]，
 * 创建、落库、拉起前台服务的行为必须一致，否则定时任务会出现手动任务没有的边界。
 */
@Singleton
class TaskLauncher @Inject constructor(
    private val taskDao: TaskDao,
    @ApplicationContext private val context: Context,
) {

    /**
     * 功能：创建并排队一条任务
     * 参数：inputText 用户原始输入；autoApproveL2a 是否预设批准 L2A（仅定时任务为 true）
     * 返回：新任务标识
     *
     * 任务类型、目标包与参数都留空，由 Planner 在准备阶段解析后回填：
     * 参数不完整时不能凭空生成一次性授权（§1.3.1）。
     */
    suspend fun launch(inputText: String, autoApproveL2a: Boolean = false): String {
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val spec = buildSpec(taskId, inputText, autoApproveL2a)
        taskDao.upsert(
            TaskEntity(
                taskId = taskId,
                taskType = spec.taskType,
                packageName = spec.packageName,
                inputText = inputText,
                specJson = TaskSpecCodec.encode(spec),
                state = TaskState.CREATED.name,
                currentSubGoal = null,
                stepCount = 0,
                displayGeneration = 0,
                lastObservationId = null,
                pendingIntentId = null,
                suspendReason = null,
                budgetStepsUsed = 0,
                budgetModelCalls = 0,
                createdAt = now,
                updatedAt = now,
                finishedAt = null,
            ),
        )
        AgentForegroundService.start(context)
        return taskId
    }

    /**
     * 功能：构造参数未解析的 TaskSpec 骨架
     * 参数：taskId 任务标识；inputText 用户原始输入；autoApproveL2a 预设授权开关
     * 返回：TaskSpec 实例
     */
    private fun buildSpec(
        taskId: String,
        inputText: String,
        autoApproveL2a: Boolean,
    ): TaskSpec = TaskSpec(
        taskId = taskId,
        taskType = "",
        packageName = "",
        inputText = inputText,
        parameters = emptyMap(),
        constraints = TaskConstraints(),
        riskGrants = emptyList(),
        completionContract = CompletionContract(
            successBoundary = "",
            requiredFields = emptyList(),
        ),
        budget = TaskBudget(),
        autoApproveL2a = autoApproveL2a,
    )
}
