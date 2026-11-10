package com.neptine.mimir.ui.viewmodel

import com.neptine.mimir.ui.i18n.tStatic
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neptine.mimir.agent.Orchestrator
import com.neptine.mimir.agent.TextInput
import com.neptine.mimir.core.contract.ActionKind
import com.neptine.mimir.core.contract.AgentAction
import com.neptine.mimir.core.contract.CaptureSource
import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.Point
import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.data.db.dao.AuditDao
import com.neptine.mimir.data.db.dao.TaskDao
import com.neptine.mimir.device.ActiveDisplayRegistry
import com.neptine.mimir.device.DeviceTransport
import com.neptine.mimir.observation.AuditScreenshotStore
import com.neptine.mimir.observation.ScreenshotProvider
import com.neptine.mimir.service.AgentForegroundService
import com.neptine.mimir.ui.ARG_TASK_ID
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 副屏预览的一帧。
 *
 * @property bitmap 解码后的画面（540×1200，送模型规格）
 * @property fps 最近一秒内成功取到的帧数，用于如实展示实际帧率
 * @property source 本帧来自哪条取帧路径
 */
data class PreviewFrame(
    val bitmap: Bitmap,
    val fps: Int,
    val source: CaptureSource,
)

/**
 * 接管页本地输入框的写入结果。
 *
 * @property message 给用户看的一句话
 * @property ok 是否写入成功
 */
data class LocalWriteResult(
    val message: String,
    val ok: Boolean,
)

/**
 * 接管页状态持有者。
 *
 * 实时画面不追求固定 15–30fps：PixelCopy 单帧实测 137–210ms，物理上限约 5–7fps。
 * 这里按"取完一帧尽快取下一帧"轮询，并在 UI 里显示实测 fps，不伪造帧率。
 */
@HiltViewModel
class TakeoverViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val taskDao: TaskDao,
    auditDao: AuditDao,
    private val screenshots: ScreenshotProvider,
    private val auditShots: AuditScreenshotStore,
    private val activeDisplay: ActiveDisplayRegistry,
    private val transport: DeviceTransport,
    private val textInput: TextInput,
    private val orchestrator: Orchestrator,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** 路由带过来的任务标识；为空表示没有指定任务。 */
    val taskId: String? = savedStateHandle.get<String>(ARG_TASK_ID)

    /** 被接管的任务摘要；无任务或任务已删除时为 null。 */
    val task: StateFlow<TaskRow?> = if (taskId == null) {
        MutableStateFlow(null)
    } else {
        taskDao.observeById(taskId)
            .map { it?.toRow() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    /** 最近一步审计；接管页用它回答"agent 停在哪一步、为什么停".tStatic()。 */
    val lastStep: StateFlow<AuditRow?> = if (taskId == null) {
        MutableStateFlow(null)
    } else {
        auditDao.observeByTask(taskId)
            .map { it.lastOrNull()?.toRow() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    /** 该任务的完整审计时间轴，按步序升序；查看档单步回放用。 */
    val steps: StateFlow<List<AuditRow>> = if (taskId == null) {
        MutableStateFlow(emptyList())
    } else {
        auditDao.observeByTask(taskId)
            .map { rows -> rows.map { it.toRow() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    private val _preview = MutableStateFlow<PreviewFrame?>(null)

    /** 副屏实时画面；没有活动副屏或任务不在挂起状态时为 null。 */
    val preview: StateFlow<PreviewFrame?> = _preview.asStateFlow()

    private val _takeover = MutableStateFlow(false)

    /** 接管档开关；false 时为查看档（只读预览）。 */
    val takeover: StateFlow<Boolean> = _takeover.asStateFlow()

    private val _localText = MutableStateFlow("")

    /** 接管页本地输入框的正文；只存在于内存，写完即清空。 */
    val localText: StateFlow<String> = _localText.asStateFlow()

    private val _writeResult = MutableStateFlow<LocalWriteResult?>(null)

    /** 最近一次「写入副屏」的结果；进入接管档或重新输入时清空。 */
    val writeResult: StateFlow<LocalWriteResult?> = _writeResult.asStateFlow()

    private val _writing = MutableStateFlow(false)

    /** 是否正在写入；按钮据此置灰，避免连点写出两份。 */
    val writing: StateFlow<Boolean> = _writing.asStateFlow()

    /**
     * 用户在预览里最后点下的位置（归一化）。
     *
     * 写入副屏时用它指定目标：多输入框页面里不能靠"唯一可编辑节点"猜，
     * 必须回到用户刚才点的那个框。
     */
    private var lastTapFraction: Pair<Float, Float>? = null

    private val _paused = MutableStateFlow(false)

    /** 查看档是否暂停实时画面；暂停时保留最后一帧，不继续取帧。 */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _replayIndex = MutableStateFlow<Int?>(null)

    /** 单步回放的当前步在时间轴中的下标；null 表示不在回放。 */
    val replayIndex: StateFlow<Int?> = _replayIndex.asStateFlow()

    private val _replayFrame = MutableStateFlow<Bitmap?>(null)

    /** 回放步留存的历史画面；该步没有截图时为 null。 */
    val replayFrame: StateFlow<Bitmap?> = _replayFrame.asStateFlow()

    /** 回放截图加载任务；切换步骤时取消上一次，避免旧图覆盖新图。 */
    private var replayLoadJob: Job? = null

    /** 手动注入串行锁：保证点击与滑动的注入顺序，避免多个协程同时抢 binder。 */
    private val injectionLock = Mutex()

    /** 预览轮询任务；页面离开时取消。 */
    private var previewJob: Job? = null

    /**
     * 功能：启动预览轮询
     * 参数：无
     * 返回：无
     *
     * 重复调用无副作用；页面进入时调用一次即可。
     */
    fun startPreview() {
        if (previewJob?.isActive == true) return
        previewJob = viewModelScope.launch { previewLoop() }
    }

    /**
     * 功能：停止预览并清空当前帧
     * 参数：无
     * 返回：无
     */
    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        _preview.value = null
    }

    /**
     * 功能：预览轮询主体
     * 参数：无
     * 返回：无
     *
     * 只在"任务处于 NEED_HUMAN 且存在活动副屏"时取帧：agent 恢复执行后立刻停，
     * 避免和主循环的观察抢同一块屏的取帧通道。
     */
    private suspend fun previewLoop() {
        val stamps = ArrayDeque<Long>()
        while (currentCoroutineContext().isActive) {
            val handle = activeDisplay.current()
            // 只要副屏存在就出画面：用户点「接管」想看的是**实时**副屏，
            // 任务执行中、结果页保留中都一样。触控转发等危险能力仍只在 NEED_HUMAN 下开放。
            if (handle == null) {
                if (_preview.value != null) {
                    _preview.value = null
                    stamps.clear()
                }
                delay(IDLE_DELAY_MS)
                continue
            }
            // 暂停或回放时保留最后一帧，不再取帧：查看档的"看清这一帧"比"帧率"更重要
            if (_paused.value || _replayIndex.value != null) {
                delay(PAUSED_DELAY_MS)
                continue
            }

            val captured = runCatching { screenshots.capture(handle) }.getOrNull()
            if (captured == null) {
                delay(FAILURE_DELAY_MS)
                continue
            }
            val bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(captured.image.bytes, 0, captured.image.bytes.size)
            }
            if (bitmap == null) {
                delay(FAILURE_DELAY_MS)
                continue
            }

            val now = SystemClock.elapsedRealtime()
            stamps.addLast(now)
            while (stamps.isNotEmpty() && now - stamps.first() > FPS_WINDOW_MS) {
                stamps.removeFirst()
            }
            _preview.value = PreviewFrame(bitmap, stamps.size, captured.source)
            delay(MIN_FRAME_GAP_MS)
        }
    }

    /**
     * 功能：进入接管档
     * 参数：无
     * 返回：无
     *
     * 只在有实时画面时允许：没有画面就没有坐标映射的依据。
     */
    fun enterTakeover() {
        if (_preview.value == null) return
        // 接管档要的是实时画面与触控：回放和暂停都必须先退出，否则用户点的和看到的不一致
        exitReplay()
        _writeResult.value = null
        _takeover.value = true
    }

    /**
     * 功能：暂停或继续实时画面
     * 参数：无
     * 返回：无
     *
     * 只在查看档可用：接管档暂停画面会让用户点的位置与看到的画面不同步。
     */
    fun togglePause() {
        if (_takeover.value) return
        _paused.value = !_paused.value
    }

    /**
     * 功能：从最近一步开始单步回放
     * 参数：无
     * 返回：无
     *
     * 回放会暂停实时取帧；退出回放后恢复实时画面。回放不改变任务状态，也不注入任何动作。
     */
    fun startReplay() {
        if (_takeover.value) return
        val list = steps.value
        if (list.isEmpty()) return
        _paused.value = true
        showReplay(list.lastIndex)
    }

    /**
     * 功能：回放上一步
     * 参数：无
     * 返回：无
     */
    fun replayPrevious() {
        val index = _replayIndex.value ?: return
        if (index > 0) showReplay(index - 1)
    }

    /**
     * 功能：回放下一步
     * 参数：无
     * 返回：无
     */
    fun replayNext() {
        val index = _replayIndex.value ?: return
        if (index < steps.value.lastIndex) showReplay(index + 1)
    }

    /**
     * 功能：退出回放并恢复实时画面
     * 参数：无
     * 返回：无
     */
    fun exitReplay() {
        replayLoadJob?.cancel()
        replayLoadJob = null
        _replayFrame.value = null
        _replayIndex.value = null
        _paused.value = false
    }

    /**
     * 功能：切到时间轴上的某一步
     * 参数：index 目标下标
     * 返回：无
     *
     * 下标会被夹到当前时间轴范围内：任务仍在挂起时审计行可能继续增长，
     * 用旧下标直接取值会越界。截图优先取后帧（动作之后的样子），没有再退回前帧。
     */
    private fun showReplay(index: Int) {
        val list = steps.value
        if (list.isEmpty()) return
        val safe = index.coerceIn(0, list.lastIndex)
        _replayIndex.value = safe
        val step = list[safe]
        replayLoadJob?.cancel()
        replayLoadJob = viewModelScope.launch {
            _replayFrame.value = null
            _replayFrame.value = auditShots.load(step.screenshotAfter ?: step.screenshotBefore)
        }
    }

    /**
     * 功能：退回收查看档（不恢复任务）
     * 参数：无
     * 返回：无
     *
     * 离开接管页或任务状态变化时调用：触控权收回，任务仍保持挂起。
     */
    fun exitTakeover() {
        _takeover.value = false
    }

    /**
     * 功能：交还 agent
     * 参数：无
     * 返回：无
     *
     * 先收回触控权再走通用「继续」：接管档不签发授权，把控制权还给 agent 后重新观测再决策。
     */
    fun handBack() {
        _takeover.value = false
        taskId?.let { AgentForegroundService.continueTask(context, it) }
    }

    /**
     * 功能：转发一次点击到副屏
     * 参数：xFraction 横向归一化位置（0–1）；yFraction 纵向归一化位置（0–1）
     * 返回：无
     *
     * 只在接管档、任务挂起且有活动副屏时转发；归一化值由 UI 按实际绘制区域换算。
     */
    fun forwardTap(xFraction: Float, yFraction: Float) {
        val handle = injectionHandle() ?: return
        val point = toNative(handle, xFraction, yFraction)
        // 记住这一次的落点：本地输入框要把文本写回"用户刚点的那个框"
        lastTapFraction = xFraction.coerceIn(0f, 1f) to yFraction.coerceIn(0f, 1f)
        viewModelScope.launch {
            injectionLock.withLock {
                runCatching { transport.inputs.tap(handle, point, TAKEOVER_PRESS_MS) }
            }
        }
    }

    /**
     * 功能：更新本地输入框的正文
     * 参数：value 新文本
     * 返回：无
     */
    fun onLocalTextChange(value: String) {
        _localText.value = value.take(LOCAL_INPUT_MAX)
        if (_writeResult.value != null) _writeResult.value = null
    }

    /**
     * 功能：把本地输入框的内容写入副屏
     * 参数：无
     * 返回：无（结果写入 [writeResult]）
     *
     * 输入法不参与：文字在主屏打完，经无障碍 SET_TEXT 直接写给副屏的焦点控件；
     * 写不进去时按 TextInput 的既有顺序退到粘贴与按键。内容不落库、不打日志。
     */
    fun writeLocalText() {
        val text = _localText.value.trim()
        if (text.isEmpty() || _writing.value) return
        val handle = injectionHandle()
        if (handle == null) {
            _writeResult.value = LocalWriteResult("没有可写的副屏：任务已恢复或屏已释放".tStatic(), false)
            return
        }
        val target = lastTapFraction
        if (target == null) {
            _writeResult.value = LocalWriteResult("先在预览里点一下目标输入框，再写入".tStatic(), false)
            return
        }

        viewModelScope.launch {
            _writing.value = true
            val accepted = runCatching {
                val action = AgentAction.Do(
                    kind = ActionKind.TEXT,
                    observationId = "manual-${SystemClock.elapsedRealtime()}",
                    targetFingerprint = null,
                    normX = (target.first * LOCAL_NORM_SCALE).toInt(),
                    normY = (target.second * LOCAL_NORM_SCALE).toInt(),
                    text = text,
                )
                textInput.type(handle, action)
            }.getOrDefault(false)
            _writing.value = false
            _writeResult.value = if (accepted) {
                _localText.value = ""
                LocalWriteResult("已写入副屏，确认无误后自己点「验证 / 提交」".tStatic(), true)
            } else {
                LocalWriteResult("写入被拒绝：先点一下目标输入框再试，或在主屏打开该 App 手动输入".tStatic(), false)
            }
        }
    }

    /**
     * 功能：转发一次滑动到副屏
     * 参数：path 归一化轨迹点（0–1）
     * 返回：无
     *
     * 抽样到最多 16 个点：手指挥动会产出上百个事件，逐个跨进程注入没有意义。
     */
    fun forwardSwipe(path: List<Pair<Float, Float>>) {
        val handle = injectionHandle() ?: return
        if (path.size < 2) return
        val sampled = samplePath(path).map { toNative(handle, it.first, it.second) }
        viewModelScope.launch {
            injectionLock.withLock {
                runCatching { transport.inputs.swipe(handle, sampled) }
            }
        }
    }

    /**
     * 功能：取可用于手动注入的副屏
     * 参数：无
     * 返回：活动副屏句柄；不在接管档、任务未挂起或没有屏时为 null
     */
    private fun injectionHandle(): DisplayHandle? {
        if (!_takeover.value) return null
        if (task.value?.state != TaskState.NEED_HUMAN) return null
        return activeDisplay.current()
    }

    /**
     * 功能：把归一化坐标换算成副屏原生像素
     * 参数：handle 副屏句柄；xFraction/yFraction 归一化坐标
     * 返回：原生像素点
     */
    private fun toNative(handle: DisplayHandle, xFraction: Float, yFraction: Float): Point = Point(
        x = (xFraction.coerceIn(0f, 1f) * (handle.spec.width - 1)).toInt(),
        y = (yFraction.coerceIn(0f, 1f) * (handle.spec.height - 1)).toInt(),
    )

    /**
     * 功能：等间隔抽样一条滑动轨迹
     * 参数：path 原始归一化轨迹
     * 返回：抽样后的轨迹；首尾必保留
     */
    private fun samplePath(path: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (path.size <= MAX_SWIPE_POINTS) return path
        val step = (path.size - 1) / (MAX_SWIPE_POINTS - 1)
        return (0 until MAX_SWIPE_POINTS - 1).map { path[it * step] } + path.last()
    }

    /**
     * 功能：用户点「继续一次」或「继续」
     * 参数：无
     * 返回：无
     *
     * 与通知栏走同一个入口：是否签发一次性授权由 Orchestrator 按最近一步决定，
     * L2A 才签授权，其它挂起直接恢复。
     */
    fun continueTask() {
        taskId?.let { AgentForegroundService.continueTask(context, it) }
    }

    /**
     * 功能：用户核对未决写入后继续任务
     * 参数：无
     * 返回：无
     *
     * 与「继续」分开：这条路会先把未决写入结案，明确不重放刚才那个动作。
     * 直接调用 Orchestrator，避免前台服务被系统停掉时按钮无效。
     */
    fun continueAfterManualCheck() {
        val id = taskId ?: return
        viewModelScope.launch { runCatching { orchestrator.continueAfterManualCheck(id) } }
    }

    /**
     * 功能：用户点「放弃」
     * 参数：无
     * 返回：无
     *
     * 直接调用 Orchestrator：放弃不能依赖"前台服务还活着"，
     * 服务被系统停掉时发 Intent 会被拒绝，按钮看起来毫无反应。
     * 放弃后若还有排队的 CREATED 任务，再拉起服务继续消费。
     */
    fun abort() {
        val id = taskId ?: return
        viewModelScope.launch {
            runCatching { orchestrator.forceAbort(id) }
            if (runCatching { taskDao.findNextCreated() }.getOrNull() != null) {
                AgentForegroundService.start(context)
            }
        }
    }

    /**
     * 功能：页面销毁时停止轮询
     * 参数：无
     * 返回：无
     */
    override fun onCleared() {
        replayLoadJob?.cancel()
        stopPreview()
        super.onCleared()
    }

    private companion object {
        /** 没有可预览画面时的轮询间隔（毫秒）。 */
        const val IDLE_DELAY_MS = 300L

        /** 暂停或回放时的轮询间隔（毫秒）：不取帧，只等状态变化。 */
        const val PAUSED_DELAY_MS = 250L

        /** 取帧失败后的退避（毫秒）。 */
        const val FAILURE_DELAY_MS = 600L

        /** 帧率统计窗口（毫秒）。 */
        const val FPS_WINDOW_MS = 1000L

        /** 两帧之间的最小间隔（毫秒），避免空转占满 CPU。 */
        const val MIN_FRAME_GAP_MS = 50L

        /** 手动点击的按压时长（毫秒）：接管档要跟手，不做拟人化随机。 */
        const val TAKEOVER_PRESS_MS = 80L

        /** 一次滑动最多注入的轨迹点数。 */
        const val MAX_SWIPE_POINTS = 16

        /** 本地输入框的字数上限：验证码、搜索词、消息都用不到更长。 */
        const val LOCAL_INPUT_MAX = 120

        /** 归一化坐标的满量程；与动作协议里的 point 语义一致。 */
        const val LOCAL_NORM_SCALE = 1000
    }
}
