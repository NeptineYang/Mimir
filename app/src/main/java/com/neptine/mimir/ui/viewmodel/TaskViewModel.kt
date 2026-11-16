package com.neptine.mimir.ui.viewmodel

import android.content.Context
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import com.neptine.mimir.ui.i18n.tStatic
import androidx.lifecycle.viewModelScope
import com.neptine.mimir.agent.Orchestrator
import com.neptine.mimir.agent.TaskLauncher
import com.neptine.mimir.core.ProcessWindow
import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.data.config.ModelConfigRepository
import com.neptine.mimir.data.db.dao.AuditDao
import com.neptine.mimir.data.db.dao.TaskDao
import com.neptine.mimir.observation.AuditScreenshotStore
import com.neptine.mimir.service.AgentForegroundService
import com.neptine.mimir.device.selfhost.PrivilegeMonitor
import com.neptine.mimir.ui.permission.PermissionReader
import com.neptine.mimir.ui.permission.PermissionStatus
import com.neptine.mimir.ui.permission.privilegeStatusText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 任务页输入框的字数上限。
 *
 * 界面上不显示计数器（上限只用来截断超长粘贴/语音回填），
 * 500 字覆盖"多步任务 + 约束条件"的完整描述，不再逼用户压缩句子。
 */
private const val MAX_INPUT_LENGTH = 500

/** 拉起特权服务后等它接入的时间（毫秒）。 */
private const val PRIVILEGE_REFRESH_DELAY_MS = 3_000L

/** 任务卡缩略图的最大宽度（像素）；只用于几十 dp 的展示位。 */
private const val THUMB_MAX_WIDTH = 220

/**
 * 引擎不能开工的原因，决定点击时该跳到哪一步。
 */
enum class EngineFix {
    /** 没有待处理项。 */
    NONE,

    /** 特权服务未就绪（缺配对 / 缺环境 / 进程没起来）。 */
    PRIVILEGE,

    /** 无障碍服务未开启。 */
    ACCESSIBILITY,

    /** 模型后端未配置。 */
    MODEL,
}

/**
 * 引擎状态：能不能开工，以及卡在哪一项。
 *
 * @property online 三项前置条件都满足为 true
 * @property detail 给用户看的一句话说明
 * @property fix 点击这一行时该做的修复动作
 */
data class EngineStatus(
    val online: Boolean,
    val detail: String,
    val fix: EngineFix = EngineFix.NONE,
)

/**
 * 任务页状态持有者。
 */
@HiltViewModel
class TaskViewModel @Inject constructor(
    private val taskDao: TaskDao,
    private val auditDao: AuditDao,
    private val auditShots: AuditScreenshotStore,
    private val permissions: PermissionReader,
    private val privilege: PrivilegeMonitor,
    private val models: ModelConfigRepository,
    private val orchestrator: Orchestrator,
    private val launcher: TaskLauncher,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _input = MutableStateFlow("")

    init {
        // 特权状态是会自己变的（壳进程后起/被回收），一次性读取会让界面停在旧结论上；
        // 这里订阅状态流，变了就重算一次门禁文案。
        viewModelScope.launch {
            privilege.status.collect { refreshStatus() }
        }
    }

    /** 输入框文本。 */
    val input: StateFlow<String> = _input.asStateFlow()

    private val _status = MutableStateFlow(EngineStatus(online = false, detail = "正在检查运行条件".tStatic()))

    /** 引擎状态；进入页面时刷新一次，避免设置页改完回来还显示旧结论。 */
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    /**
     * 未结束的任务。
     *
     * 只查非终态：历史记录在历史页，首页只回答"现在有哪些任务还在跑或等人"。
     * 排序按"正在推进 > 等人处理 > 排队中"：有任务在跑时，它必须出现在最上面。
     * 首帧为 null，避免页面先闪一下"没有进行中任务"。
     *
     * 每条任务带上最近一步的动作后截图：文字进度说不清"它现在停在哪一页"，
     * 一张缩略图能。缩略图按 220px 宽解码，不加载原图。
     */
    val activeTasks: StateFlow<List<TaskRow>?> = taskDao.observeActive()
        .map { tasks ->
            tasks.map { task ->
                val path = runCatching { auditDao.latestScreenshot(task.taskId) }.getOrNull()
                val thumb = auditShots.loadThumb(path, THUMB_MAX_WIDTH)?.asImageBitmap()
                task.toRow(thumb = thumb)
            }
                .sortedWith(compareBy({ statePriority(it.state) }, { it.createdAt }))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 本次运行里已经结束的任务。
     *
     * 时间窗由 [ProcessWindow] 在进程启动时固定：进程死亡后窗口重置，列表自然清空，
     * 不新增表、也不需要手动清理。只留终态——未结束的任务在上面的"进行中"区。
     * 总结由 TaskSummaryWriter 异步写入，Room 的变更会把这个 Flow 推回界面。
     */
    val sessionTasks: StateFlow<List<TaskRow>?> =
        taskDao.observeCreatedSince(ProcessWindow.startedAt)
            .map { tasks ->
                tasks.filter { parseTaskState(it.state)?.isTerminal == true }
                    .map { it.toRow() }
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
                val privilegeHint = privilegeStatusText(privilege.status.value)
                when {
                    permission.privilege != PermissionStatus.GRANTED ->
                        EngineStatus(
                            false,
                            "特权服务未就绪：%1\$s，点这里启动".tStatic(privilegeHint),
                            EngineFix.PRIVILEGE,
                        )
                    permission.accessibility != PermissionStatus.GRANTED ->
                        EngineStatus(false, "无障碍服务未开启，点这里去开启".tStatic(), EngineFix.ACCESSIBILITY)
                    else -> {
                        val model = models.resolve(ModelConfigRepository.ModelRole.EXECUTOR)
                        if (model == null) {
                            EngineStatus(false, "未配置模型后端，点这里去填写".tStatic(), EngineFix.MODEL)
                        } else {
                            EngineStatus(true, "已就绪：%1\$s".tStatic(model.model))
                        }
                    }
                }
            }
            _status.value = status
        }
    }

    /**
     * 功能：按当前缺项发起修复
     * 参数：无
     * 返回：无
     *
     * 权限类缺项就地处理；模型未配置需要界面跳设置页，由调用方判断 [EngineFix.MODEL]。
     * Shizuku 授权是系统弹窗，授予后等一小会儿再读一次状态，用户不必手动退出重进。
     */
    fun fixEngine() {
        when (_status.value.fix) {
            EngineFix.PRIVILEGE -> {
                privilege.ensureStarted()
                viewModelScope.launch {
                    delay(PRIVILEGE_REFRESH_DELAY_MS)
                    refreshStatus()
                }
            }
            EngineFix.ACCESSIBILITY -> permissions.openAccessibilitySettings()
            EngineFix.MODEL, EngineFix.NONE -> Unit
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
        viewModelScope.launch {
            runCatching { launcher.launch(text) }
                .onSuccess { _input.value = "" }
                .onFailure { /* 落库失败时保留输入，用户可直接重试 */ }
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
     * 参数：无
     * 返回：无
     *
     * 创建逻辑已收敛到 [TaskLauncher]：手动任务与定时任务必须走同一入口，
     * 否则两者的预算、授权与落库字段会慢慢分叉。
     */
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
