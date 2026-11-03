package com.neptine.mimir.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neptine.mimir.agent.Orchestrator
import com.neptine.mimir.agent.TaskSpecCodec
import com.neptine.mimir.core.contract.CompletionContract
import com.neptine.mimir.core.contract.TaskBudget
import com.neptine.mimir.core.contract.TaskConstraints
import com.neptine.mimir.core.contract.TaskSpec
import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.data.config.ModelConfigRepository
import com.neptine.mimir.data.db.dao.TaskDao
import com.neptine.mimir.data.db.entity.TaskEntity
import com.neptine.mimir.service.AgentForegroundService
import com.neptine.mimir.ui.permission.PermissionReader
import com.neptine.mimir.ui.permission.PermissionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 任务页输入框的字数上限。 */
private const val MAX_INPUT_LENGTH = 240

/**
 * 引擎状态：能不能开工，以及卡在哪一项。
 *
 * @property online 三项前置条件都满足为 true
 * @property detail 给用户看的一句话说明
 */
data class EngineStatus(
    val online: Boolean,
    val detail: String,
)

/**
 * 任务页状态持有者。
 */
@HiltViewModel
class TaskViewModel @Inject constructor(
    private val taskDao: TaskDao,
    private val permissions: PermissionReader,
    private val models: ModelConfigRepository,
    private val orchestrator: Orchestrator,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _input = MutableStateFlow("")

    /** 输入框文本。 */
    val input: StateFlow<String> = _input.asStateFlow()

    private val _status = MutableStateFlow(EngineStatus(online = false, detail = "正在检查运行条件"))

    /** 引擎状态；进入页面时刷新一次，避免设置页改完回来还显示旧结论。 */
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    /**
     * 未结束的任务。
     *
     * 只查非终态：历史记录在历史页，首页只回答"现在有哪些任务还在跑或等人"。
     * 排序按"正在推进 > 等人处理 > 排队中"：有任务在跑时，它必须出现在最上面。
     * 首帧为 null，避免页面先闪一下"没有进行中任务"。
     */
    val activeTasks: StateFlow<List<TaskRow>?> = taskDao.observeActive()
        .map { tasks ->
            tasks.map { it.toRow() }
                .sortedWith(compareBy({ statePriority(it.state) }, { it.createdAt }))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 功能：刷新引擎状态
     * 参数：无
     * 返回：无
     *
     * 顺序按"最可能先挡住任务"排：Shizuku → 无障碍 → 模型后端。
     * P5 未通过不算引擎离线——文本输入被 Guard 拦下是执行中的预期行为，不是环境没搭好。
     */
    fun refreshStatus() {
        viewModelScope.launch {
            // Shizuku 的 pingBinder 与权限读取都是 binder 调用，放主线程会卡 UI（§5.3）
            val status = withContext(Dispatchers.IO) {
                val permission = permissions.snapshot()
                when {
                    permission.shizuku != PermissionStatus.GRANTED ->
                        EngineStatus(false, "Shizuku 未授权，去设置页处理")
                    permission.accessibility != PermissionStatus.GRANTED ->
                        EngineStatus(false, "无障碍服务未开启，去设置页处理")
                    else -> {
                        val model = models.resolve(ModelConfigRepository.ModelRole.EXECUTOR)
                        if (model == null) {
                            EngineStatus(false, "未配置模型后端，去设置页填写")
                        } else {
                            EngineStatus(true, "已就绪：${model.model}")
                        }
                    }
                }
            }
            _status.value = status
        }
    }

    /**
     * 功能：更新输入框文本
     * 参数：value 新文本
     * 返回：无
     */
    fun onInputChange(value: String) {
        _input.value = value.take(MAX_INPUT_LENGTH)
    }

    /**
     * 功能：把当前输入作为一次任务请求发出
     * 参数：无
     * 返回：无
     *
     * 用户不再填表单、也不再确认 TaskSpec：任务类型、目标包与参数都留空，
     * 由 Planner 在准备阶段解析后回填（设计文档 §1.3 首步）。
     * 入队后立刻启动前台服务消费队列；解析失败会以 FAILED + 原因落在任务行上。
     */
    fun send() {
        val text = _input.value.trim()
        if (text.isEmpty()) {
            return
        }
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val spec = buildSpec(taskId = taskId, inputText = text)
        viewModelScope.launch {
            taskDao.upsert(
                TaskEntity(
                    taskId = taskId,
                    taskType = spec.taskType,
                    packageName = spec.packageName,
                    inputText = text,
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
            _input.value = ""
            AgentForegroundService.start(context)
        }
    }

    /**
     * 功能：强制打断一个未结束的任务
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 直接调用 Orchestrator 而不是发 Intent 给前台服务：服务可能已经被系统停掉
     * （例如进程重启后遗留的 PLANNING 行），此时 startForegroundService 会被拒绝，
     * 界面看起来像"按钮没反应"。Orchestrator 是单实例，能直接取消正在跑的协程。
     *
     * 打断后若还有排队的 CREATED 任务，再拉起前台服务继续消费队列；
     * 没有排队任务就不启动服务，避免为了一个空队列弹通知。
     */
    fun forceStop(taskId: String) {
        viewModelScope.launch {
            runCatching { orchestrator.forceAbort(taskId) }
            val next = runCatching { taskDao.findNextCreated() }.getOrNull()
            if (next != null) {
                AgentForegroundService.start(context)
            }
        }
    }

    /**
     * 功能：构造参数未解析的 TaskSpec 骨架
     * 参数：taskId 任务标识；inputText 用户原始输入
     * 返回：TaskSpec 实例
     *
     * 参数与授权都留空是刻意的：设计文档要求"参数不完整必须等参数确定后请求当次确认"，
     * 不能在解析前就凭空生成一次性授权。
     */
    private fun buildSpec(taskId: String, inputText: String): TaskSpec = TaskSpec(
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
    )

}

/**
 * 功能：给进行中任务排优先级
 * 参数：state 任务状态
 * 返回：数值越小越靠前
 *
 * 正在推进的任务排最前，其次是等人处理，最后才是排队中：
 * 首页的作用是回答"现在在发生什么"，不是按创建时间罗列。
 */
private fun statePriority(state: TaskState?): Int = when (state) {
    TaskState.PLANNING,
    TaskState.EXECUTING,
    TaskState.RECONCILING,
    TaskState.VERIFYING,
    -> 0
    TaskState.NEED_HUMAN -> 1
    TaskState.CREATED -> 2
    else -> 3
}
