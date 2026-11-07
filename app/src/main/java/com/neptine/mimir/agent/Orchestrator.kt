package com.neptine.mimir.agent

import android.os.SystemClock
import android.util.Log
import com.neptine.mimir.core.contract.ActionKind
import com.neptine.mimir.core.contract.AgentAction
import com.neptine.mimir.core.contract.Bounds
import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.DisplaySpec
import com.neptine.mimir.core.contract.ObservationMode
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.OverlayKind
import com.neptine.mimir.core.contract.OverlayPolicy
import com.neptine.mimir.core.contract.PlanCursor
import com.neptine.mimir.core.contract.PlanStep
import com.neptine.mimir.core.contract.PlanSubtask
import com.neptine.mimir.core.contract.RiskLevel
import com.neptine.mimir.core.contract.TargetFingerprint
import com.neptine.mimir.core.contract.TaskPlan
import com.neptine.mimir.core.contract.TaskSpec
import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.core.contract.allowedTransitions
import com.neptine.mimir.data.db.dao.ActionIntentDao
import com.neptine.mimir.data.db.dao.AuditDao
import com.neptine.mimir.data.db.dao.TaskDao
import com.neptine.mimir.data.db.entity.ActionIntentEntity
import com.neptine.mimir.data.db.entity.AuditEntity
import com.neptine.mimir.data.db.entity.TaskEntity
import com.neptine.mimir.data.memory.MemoryWriter
import com.neptine.mimir.device.ActiveDisplayRegistry
import com.neptine.mimir.device.DeviceTransport
import com.neptine.mimir.observation.AuditScreenshotStore
import com.neptine.mimir.observation.ElementDiff
import com.neptine.mimir.observation.ObservationAssembler
import com.neptine.mimir.observation.ScreenStability
import com.neptine.mimir.observation.TreeFrame
import com.neptine.mimir.observation.TreeProvider
import com.neptine.mimir.service.HandoffNotifier
import com.neptine.mimir.service.MimirAccessibilityService
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 任务编排器（设计文档 §1.3 主循环 / §1.4 状态机）。
 *
 * 单任务单实例：UI 可创建多个任务并排队，但同一时刻只运行一个任务、只持有一个活动副屏。
 *
 * 一期的循环是"观测 → 决策 → 目标复验 → 闸门 → 授权 → 落盘 → 注入 → 安静 → 后置条件 → 审计 → 推进"，
 * 每条路径都落库：状态迁移进 tasks，每步进 audit，写入动作先落 action_intent，
 * 被拦下的动作发交接通知，结果未知的写入先进 RECONCILING 再交人。
 *
 * @Singleton 是必须的：强制打断由 UI 直接调用本类取消运行中的协程，
 * 如果 UI 与服务各拿一个实例，两边看到的"当前运行任务"就不是同一个。
 */
@Singleton
class Orchestrator @Inject constructor(
    private val transport: DeviceTransport,
    private val activeDisplay: ActiveDisplayRegistry,
    private val assembler: ObservationAssembler,
    private val trees: TreeProvider,
    private val auditShots: AuditScreenshotStore,
    private val stability: ScreenStability,
    private val planner: Planner,
    private val executor: Executor,
    private val guard: Guard,
    private val verifier: Verifier,
    private val audit: AuditRecorder,
    private val applier: ActionApplier,
    private val memory: MemoryRetriever,
    private val credentials: CredentialBroker,
    private val memoryWriter: MemoryWriter,
    private val resultReverifier: ResultReverifier,
    private val evidenceReviewer: EvidenceReviewer,
    private val grants: GrantBroker,
    private val conditions: PostCondition,
    private val notifier: HandoffNotifier,
    private val resultViews: ResultViewRegistry,
    private val taskDao: TaskDao,
    private val auditDao: AuditDao,
    private val intentDao: ActionIntentDao,
) {

    /**
     * 功能：跑完一个任务的全生命周期
     * 参数：taskId 任务标识
     * 返回：无；终态与每步记录都写进数据库
     */
    suspend fun run(taskId: String) {
        val initial = taskDao.findById(taskId) ?: return
        if (TaskState.valueOf(initial.state).isTerminal) return
        // 队列诊断：出现过"任务停在 CREATED 几分钟不动"，这里不留日志就无法区分
        // "没取到"和"取到了但立刻退出"。
        Log.d(AGENT_TAG, "run 开始 task=$taskId state=${initial.state}")
        notifier.clear(taskId)

        // 登记协程句柄：强制打断要取消这一条循环，而不是只改数据库状态等它自己退出
        val self = coroutineContext[Job]
        runningJob = self
        runningTaskId = taskId

        var handle: DisplayHandle? = null
        try {
            // 建屏必须在 try 内、且立刻赋值：prepare 里任何一步失败都要能销毁这块屏，
            // 否则每失败一次就泄漏一个虚拟屏，积累到一定程度新屏的窗口根节点会读不出来。
            handle = transport.displays.create(DisplaySpec())
            activeDisplay.bind(handle, taskId)
            prepare(initial, handle)?.let { drive(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(taskDao.findById(taskId) ?: initial, e.message ?: e::class.java.simpleName)
        } finally {
            // 强制打断会取消这个协程；清理必须在 NonCancellable 里跑，
            // 否则第一次挂起（写库或跨进程销毁）就会再抛取消，副屏与输入法都会漏掉。
            withContext(NonCancellable) {
                if (runningJob == self) {
                    runningJob = null
                    runningTaskId = null
                }
                handle?.let { retainOrRelease(taskId, it) }
            }
        }
    }

    /**
     * 功能：按 checkpoint 恢复任务
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 恢复的关键不是"记住第几步"，而是重新观测、重新校准元素编号并核对未决写入：
     * 存在未决写入时先转 RECONCILING，再由人工核对，禁止自动重放（§1.4）。
     */
    suspend fun resume(taskId: String) {
        // 续跑也必须登记协程句柄：强制打断可能在任务恢复后的任意一步发生
        val self = coroutineContext[Job]
        runningJob = self
        runningTaskId = taskId
        try {
            val task = taskDao.findById(taskId) ?: return
            when (TaskState.valueOf(task.state)) {
                TaskState.CREATED, TaskState.PLANNING -> run(taskId)
                TaskState.EXECUTING, TaskState.NEED_HUMAN -> {
                    val unresolved = intentDao.findUnresolvedByTask(taskId)
                    if (unresolved.isNotEmpty()) {
                        reconcile(task, unresolved)
                        return
                    }
                    notifier.clear(taskId)
                    continueFrom(task)
                }
                else -> Unit
            }
        } finally {
            if (runningJob == self) {
                runningJob = null
                runningTaskId = null
            }
        }
    }

    /**
     * 功能：用户点「继续一次」或「继续」后恢复执行
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 只有最近一步是被 L2A 拦下的可确认动作时才签发一次性授权：授权绑定动作类型、
     * 参数摘要、目标包名与完成边界。其它挂起（共享状态冲突、前台不符、验证未通过等）
     * 只做恢复、不签发授权，恢复后仍要重新观测再决策；存在未决写入时 resume 会先转
     * RECONCILING，不会自动重放。
     */
    suspend fun continueTask(taskId: String) {
        val task = taskDao.findById(taskId) ?: return
        if (TaskState.valueOf(task.state) != TaskState.NEED_HUMAN) return

        val blocked = auditDao.lastStep(taskId)
        if (blocked != null && blocked.riskLevel == RiskLevel.L2A.name &&
            blocked.decision == DECISION_NEED_CONFIRM
        ) {
            val spec = TaskSpecCodec.decode(task.specJson)
            grants.issue(
                taskId = taskId,
                actionKind = blocked.actionKind,
                paramsDigest = blocked.paramsDigest,
                targetPackage = spec.packageName,
                scopeText = spec.completionContract.successBoundary.ifEmpty { DEFAULT_GRANT_SCOPE },
            )
        }
        // 首次成功率要能区分"一次跑通"与"中途被人续过"：这条事件是唯一判据，
        // 不新增任务列（续跑是过程事实，属于审计，不属于任务属性）。
        audit.recordSystemEvent(
            task = task,
            outcome = "RESUMED",
            result = "用户从挂起状态继续任务",
            decision = "ALLOW",
        )
        resume(taskId)
    }

    /**
     * 功能：用户核对未决写入后继续任务
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 只允许从 NEED_HUMAN 进入：用户必须在接管页确认过"刚才的动作到底有没有执行"。
     * 这里只把未决写入结案，不替用户重放动作；恢复后 Agent 重新观测页面再决策。
     */
    suspend fun continueAfterManualCheck(taskId: String) {
        val task = taskDao.findById(taskId) ?: return
        val current = runCatching { TaskState.valueOf(task.state) }.getOrNull() ?: return
        if (current != TaskState.NEED_HUMAN) return
        val settled = intentDao.settleUnresolved(
            taskId,
            "用户在接管页确认已核对未决写入；不重放动作",
            System.currentTimeMillis(),
        )
        audit.recordSystemEvent(
            task = task,
            outcome = "HUMAN_RECONCILED",
            result = "用户确认已核对 $settled 条未决写入，恢复执行但不重放",
            decision = "ALLOW",
        )
        audit.recordSystemEvent(
            task = task,
            outcome = "RESUMED",
            result = "用户核对未决写入后继续任务",
            decision = "ALLOW",
        )
        resume(taskId)
    }

    /**
     * 功能：用户放弃任务
     * 参数：taskId 任务标识
     * 返回：无
     *
     * CREATED 的"放弃"曾经是删除；现在统一走 ABORTED：强制打断要能在界面上解释
     * "为什么这条任务没了"，历史页也据此留下记录，再由用户决定是否删除。
     */
    suspend fun abort(taskId: String) = markAborted(taskId, releaseDisplay = true)

    /**
     * 功能：把任务置为 ABORTED
     * 参数：taskId 任务标识；releaseDisplay 是否顺带销毁本任务持有的副屏
     * 返回：无
     *
     * 只有确实持有副屏的任务才需要销毁画面：排队中的 CREATED 从未建屏，
     * 不能因为打断它就把另一个正在跑/挂起任务的活动副屏拆掉。
     * 运行中的任务由 [forceAbort] 交给它自己的 finally 释放，避免和新任务的输入法切换互相踩。
     */
    private suspend fun markAborted(taskId: String, releaseDisplay: Boolean) {
        val task = taskDao.findById(taskId) ?: return
        val current = TaskState.valueOf(task.state)
        if (TaskState.ABORTED !in allowedTransitions(current)) return
        val now = System.currentTimeMillis()
        taskDao.upsert(
            task.copy(
                state = TaskState.ABORTED.name,
                suspendReason = "用户放弃",
                updatedAt = now,
                finishedAt = now,
            ),
        )
        if (releaseDisplay && activeDisplay.isOwnedBy(taskId)) {
            activeDisplay.take()?.let { destroyDisplay(it) }
        }
        notifier.notifyResult(taskId, TaskState.ABORTED, null, task.budgetStepsUsed)
    }

    /**
     * 功能：强制打断一个未结束的任务
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 由任务页直接调用，不经过前台服务：服务可能已经停止（例如进程重启后遗留的
     * PLANNING 行），此时再 startForegroundService 反而可能被系统拒绝。
     *
     * 顺序是"先取消循环 → 立刻落 ABORTED → 再等清理"：
     * 取消让循环不再开始新步骤；落库让界面立即响应，不必等一次模型调用结束；
     * 最后等它退出，确认状态没有被并发写入覆盖，并释放仍登记的副屏。
     */
    suspend fun forceAbort(taskId: String) {
        val job = runningJob
        val isRunning = runningTaskId == taskId && job != null
        Log.d(AGENT_TAG, "强制打断 task=$taskId running=$isRunning")
        if (!isRunning) {
            markAborted(taskId, releaseDisplay = true)
            Log.d(AGENT_TAG, "强制打断落库 task=$taskId state=${taskDao.findById(taskId)?.state}")
            return
        }

        job?.cancel()
        markAborted(taskId, releaseDisplay = false)
        Log.d(AGENT_TAG, "强制打断已落库 task=$taskId，等待循环清理")
        job?.join()

        // 模型调用等阻塞点不会立刻响应协程取消，期间可能有已发出的写库落地覆盖 ABORTED
        val current = runCatching { taskDao.findById(taskId)?.state }.getOrNull()
        val terminal = current != null && runCatching { TaskState.valueOf(current) }.getOrNull()?.isTerminal == true
        if (!terminal) {
            markAborted(taskId, releaseDisplay = true)
        } else if (activeDisplay.isOwnedBy(taskId)) {
            activeDisplay.take()?.let { destroyDisplay(it) }
        }
        Log.d(AGENT_TAG, "强制打断收尾 task=$taskId state=${taskDao.findById(taskId)?.state}")
    }

    /**
     * 功能：打开某任务的结果页
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 结果通知点击时调用：结果页还留在副屏时，把整个根任务搬到主屏并置顶；
     * 副屏已释放（超时或进程重启）时，退回按包名打开 App 首页。
     */
    suspend fun openResultOnMain(taskId: String) {
        val task = taskDao.findById(taskId)
        val spec = task?.let { runCatching { TaskSpecCodec.decode(it.specJson) }.getOrNull() }
        val handle = activeDisplay.current()?.takeIf { activeDisplay.isOwnedBy(taskId) }
        Log.d(
            AGENT_TAG,
            "打开结果页 task=$taskId 副屏=${handle?.displayId} 包名=${spec?.packageName} 保留中=${resultViews.isPending(taskId)}",
        )

        // 结果页还留在副屏：用户主动点了通知，此时允许把任务搬到主屏并置顶
        if (handle != null && spec != null && spec.packageName.isNotEmpty()) {
            val moved = moveRetainedTaskToMain(taskId, spec, handle)
            resultViews.clear(taskId)
            if (moved) return
            Log.d(AGENT_TAG, "结果页搬迁失败，退回按包名打开 task=$taskId")
        }

        // 副屏已释放 / 搬迁失败 / 进程重启：按包名打开 App 首页兜底
        if (spec != null && spec.packageName.isNotEmpty()) {
            val launched = runCatching { transport.activities.launchOnMain(spec.packageName) }.isSuccess
            Log.d(AGENT_TAG, "按包名打开结果 App task=$taskId pkg=${spec.packageName} 成功=$launched")
        }
        resultViews.clear(taskId)
    }

    /**
     * 功能：准备阶段——解析 TaskSpec、拉起目标 App、生成子目标
     * 参数：task 待执行任务；handle 已创建的副屏句柄
     * 返回：执行会话；主屏正在使用目标 App 时返回 null（任务已转 NEED_HUMAN）
     *
     * 建屏不在这里做：句柄由调用方持有并负责销毁，避免 prepare 中途失败时泄漏虚拟屏。
     */
    private suspend fun prepare(task: TaskEntity, handle: DisplayHandle): Session? {
        var current = transition(task, TaskState.PLANNING)

        val spec = resolveSpec(current)
        current = persistSpec(current, spec)

        // 拉起之前就要查：这一步会把目标 App 的 task 拉起来，如果用户此刻正在主屏用它，
        // 购物车/草稿/登录态会被两边同时改。等到主循环第一步再查已经晚了（§4.6 第 2 条）。
        if (blockOnMainScreenConflict(current, spec)) return null

        transport.activities.launch(spec.packageName, handle)
        // 目标 App 首帧通常要 1–3s；这里只做粗等，后续每步都有安静判定
        delay(LAUNCH_SETTLE_MS)
        launchFailure(handle, spec)?.let { reason ->
            audit.recordSystemEvent(current, outcome = "LAUNCH_FAILED", result = reason)
            throw IllegalStateException(reason)
        }

        val firstFrame = observeOrRecover(handle, spec, current)
        val plan = buildPlan(spec, firstFrame, current)
        val cursor = PlanCursor.startOf(plan)
        current = persistPlan(current, plan, cursor)

        // 计划就绪 → EXECUTING（§1.4：PLANNING → EXECUTING 的条件是 TaskPlan 就绪）
        current = transition(current, TaskState.EXECUTING)
        return Session(
            task = current,
            spec = spec,
            handle = handle,
            plan = plan,
            cursor = cursor,
            entrySnapshot = firstFrame,
            // 入口页本身就是遮挡时也要记住：页面签名与后续轨迹都不能把弹窗当稳定路径
            overlaySeen = firstFrame.overlay != null,
        )
    }

    /**
     * 功能：拉起前的主屏冲突检查
     * 参数：task 当前任务；spec 任务约束
     * 返回：命中冲突并已转人工时为 true；无冲突为 false
     */
    private suspend fun blockOnMainScreenConflict(task: TaskEntity, spec: TaskSpec): Boolean {
        val reason = mainScreenConflict(spec) ?: return false
        audit.recordSystemEvent(task, outcome = "MAIN_SCREEN_CONFLICT", result = reason)
        notifier.notifyResumable(task.taskId, reason)
        suspendForHuman(task, reason)
        return true
    }

    /**
     * 功能：从 EXECUTING / NEED_HUMAN 续跑——复用或重建副屏，再进主循环
     * 参数：task 当前任务
     * 返回：无
     *
     * 挂起时副屏被保留下来（接管页要靠它显示实时画面），继续时优先复用同一块屏；
     * 只有没有保留的屏（例如进程中断后恢复）才新建并重新拉起目标 App。
     * 无论复用还是重建，元素编号与旧观察一律不复用（§4.3 第 3 条）。
     */
    private suspend fun continueFrom(task: TaskEntity) {
        var current = task
        if (TaskState.valueOf(current.state) == TaskState.NEED_HUMAN) {
            // 人工处理期间不计入任务时长：把挂起时长补回 createdAt，否则用户完成验证/确认
            // 花的时间会让任务一恢复就撞上 maxDurationMs（真机实测交接 30 分钟直接超时）。
            val now = System.currentTimeMillis()
            val pausedMs = (now - current.updatedAt).coerceAtLeast(0)
            current = current.copy(createdAt = current.createdAt + pausedMs)
            current = transition(current, TaskState.EXECUTING)
        }
        // 已经继续执行，旧的挂起原因不能再挂在任务行上，否则列表里会一直显示"为什么停下"
        if (current.suspendReason != null) {
            current = current.copy(suspendReason = null, updatedAt = System.currentTimeMillis())
            taskDao.upsert(current)
        }

        var handle: DisplayHandle? = null
        try {
            val spec = resolveSpec(current)
            current = persistSpec(current, spec)

            // 续跑同样可能重新拉起目标 App，拉起前必须查一次主屏冲突
            if (blockOnMainScreenConflict(current, spec)) return

            val reused = activeDisplay.take()
            val opened = reused ?: transport.displays.create(DisplaySpec())
            handle = opened
            activeDisplay.bind(opened, current.taskId)
            if (reused == null) {
                transport.activities.launch(spec.packageName, opened)
                delay(LAUNCH_SETTLE_MS)
                launchFailure(opened, spec)?.let { throw IllegalStateException(it) }
            } else {
                // 复用的屏通常还停在挂起时的页面；若目标 App 的窗口已经不在，必须重新拉起，
                // 否则接下来的观察会以"屏上没有可见窗口"直接失败。
                // 索引退化时树读不出来，但 AMS 里任务还在：直接复用它，避免再拉一个实例。
                delay(REUSE_SETTLE_MS)
                val frame = runCatching { trees.capture(opened) }.getOrNull()
                val indexLost = frame == null && taskExistsOn(opened, spec.packageName)
                if (!indexLost &&
                    (frame == null || !PackageScope.isWithin(spec.packageName, frame.packageName))
                ) {
                    transport.activities.launch(spec.packageName, opened)
                    delay(LAUNCH_SETTLE_MS)
                    launchFailure(opened, spec)?.let { throw IllegalStateException(it) }
                }
            }

            val firstFrame = observeOrRecover(opened, spec, current)
            // 续跑优先复用已落库的计划与游标：挂起可能只是等用户确认，重新规划会把进度推翻
            val restored = restorePlan(current)
            val plan = restored ?: buildPlan(spec, firstFrame, current)
            val cursor = restored?.let {
                TaskPlanCodec.cursorOf(it, current.planVersion, current.planSubtaskIndex, current.planStepIndex)
            } ?: PlanCursor.startOf(plan)
            current = persistPlan(current, plan, cursor)
            val session = Session(
                task = current,
                spec = spec,
                handle = opened,
                plan = plan,
                cursor = cursor,
                entrySnapshot = firstFrame,
                overlaySeen = firstFrame.overlay != null,
            )
            // 续跑恢复已用掉的护栏余量：重规划次数、步骤驳回、注入配方与越界自愈
            session.restoreState(SessionStateCodec.decode(current.sessionJson))
            drive(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(taskDao.findById(current.taskId) ?: current, e.message ?: e::class.java.simpleName)
        } finally {
            withContext(NonCancellable) {
                handle?.let { retainOrRelease(current.taskId, it) }
            }
        }
    }

    /**
     * 功能：核对未决写入并转人工
     * 参数：task 当前任务；unresolved 未判定结果的意图
     * 返回：无
     *
     * 一期没有按 App 定制的只读结果页（订单详情 / 会话记录），因此无法完成"证明已执行一次"；
     * 但也不能什么都不看就把人叫过来。这里新起一块私有副屏只读观察一帧，记录未决动作的
     * 目标是否仍在当前页面：这只是"当时页面长什么样"的证据，不是执行证明，也更不自动重放。
     */
    private suspend fun reconcile(task: TaskEntity, unresolved: List<ActionIntentEntity>) {
        var current = task
        if (TaskState.valueOf(current.state) == TaskState.EXECUTING) {
            current = transition(current, TaskState.RECONCILING)
        }
        val evidence = readonlyEvidence(current, unresolved)
        intentDao.settleUnresolved(current.taskId, "进程中断后只读复核：$evidence", System.currentTimeMillis())
        audit.recordSystemEvent(
            task = current,
            outcome = "UNRESOLVED_WRITE",
            result = "存在 ${unresolved.size} 条未决写入；$evidence",
        )
        val reason = "存在未决写入 ${unresolved.size} 条，需只读核对后由人工处理"
        if (TaskState.NEED_HUMAN in allowedTransitions(TaskState.valueOf(current.state))) {
            suspendForHuman(current, reason)
        } else {
            // 已经在 NEED_HUMAN：只更新原因，不重复迁移，更不能因此把任务判失败
            taskDao.upsert(current.copy(suspendReason = reason, updatedAt = System.currentTimeMillis()))
        }
        notifier.notifySelfOnly(current.taskId, "有 ${unresolved.size} 条写入结果未知，需要你核对后决定")
    }

    /**
     * 功能：为未决写入取一帧只读证据
     * 参数：task 任务；unresolved 未决意图
     * 返回：一句话证据；条件不具备时写明原因
     *
     * 只拉起目标 App 并读一帧，不点击、不滑动、不输入。证据只回答"当时页面上还有没有
     * 这个目标"，不回答"动作执行了几次"：界面状态既可能是执行后的结果，也可能是没执行，
     * 把前者说成已执行、后者说成未执行都会误导人工判断。
     */
    private suspend fun readonlyEvidence(task: TaskEntity, unresolved: List<ActionIntentEntity>): String {
        if (activeDisplay.isOccupied()) return "副屏被其它任务占用，未做只读复核"
        if (MimirAccessibilityService.instance == null) return "无障碍未连接，未做只读复核"
        val spec = runCatching { TaskSpecCodec.decode(task.specJson) }.getOrNull()
        if (spec == null || spec.packageName.isEmpty()) return "目标包未解析，未做只读复核"

        var handle: DisplayHandle? = null
        return try {
            handle = transport.displays.create(DisplaySpec())
            transport.activities.launch(spec.packageName, handle)
            delay(LAUNCH_SETTLE_MS)
            val snapshot = observeOrRecover(handle, spec, task)
            if (snapshot.mode == ObservationMode.VISION_ONLY) {
                return "只读复核不可用：控件树不可读，仅有截图，无法断言目标元素是否仍在页面上"
            }
            val findings = unresolved.mapNotNull { intent ->
                val fingerprint = intent.targetFingerprintJson?.let(TargetFingerprintCodec::decode)
                    ?: return@mapNotNull null
                val present = snapshot.elements.any { ElementDiff.matches(fingerprint, it) }
                val label = fingerprint.text ?: fingerprint.contentDescription ?: fingerprint.resourceId ?: "-"
                "目标「$label」${if (present) "仍在当前页" else "已不在当前页"}"
            }
            val body = findings.ifEmpty { listOf("未决动作没有可核对的目标指纹") }.joinToString("；")
            "只读复核：${snapshot.packageName}/${snapshot.activityName}，$body（界面状态不是执行证明）"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            "只读复核不可用：${e.message ?: e::class.java.simpleName}"
        } finally {
            handle?.let { destroyDisplay(it) }
        }
    }

    /**
     * 功能：取任务的 TaskSpec；未解析过就调 Planner 回填
     * 参数：task 当前任务
     * 返回：可用的 TaskSpec
     *
     * 任务页只发一句话，taskType 与 packageName 初始为空；这一步就是设计文档 §1.3 首步说的
     * "由 Planner 回填"。已解析过的任务不重复调用模型。
     */
    private suspend fun resolveSpec(task: TaskEntity): TaskSpec {
        val stored = TaskSpecCodec.decode(task.specJson)
        if (stored.packageName.isNotEmpty() && stored.taskType.isNotEmpty()) return stored

        val packages = runCatching { transport.packages.launchablePackages() }.getOrDefault(emptyList())
        val planned = planner.resolveSpec(
            inputText = task.inputText,
            taskId = task.taskId,
            launchablePackages = packages,
            autoApproveL2a = stored.autoApproveL2a,
        )
        planned.usage?.let { usage ->
            audit.recordSystemEvent(
                task, outcome = "MODEL_USAGE", result = "任务解析", decision = "ALLOW", usage = usage,
            )
        }
        val resolved = planned.value
        check(resolved.packageName.isNotEmpty()) {
            "无法确定目标应用：请把要用的 App 名字写进任务（例如「打开设置查看关于手机」）"
        }
        return resolved
    }

    /**
     * 功能：生成执行期任务计划
     * 参数：spec 任务约束；firstFrame 开局首帧
     * 返回：任务计划；规划失败时退化为单子任务单步骤计划
     *
     * 退化而不是失败：规划器输出不可解析时，至少还能按"整句话当一步"继续执行，
     * 由动作后置条件与 Verifier 兜底，比直接判死更接近真实可用性。
     */
    private suspend fun buildPlan(
        spec: TaskSpec,
        firstFrame: ObservationSnapshot,
        task: TaskEntity,
    ): TaskPlan {
        // 策略只在这里注入（§2.5.6）：它改变"做什么、按什么顺序"，不属于逐步 prompt
        val preferences = runCatching { memory.retrievePreferences(spec.packageName) }
            .getOrDefault(emptyList())
        val planned = runCatching { planner.planTask(spec, preferences, firstFrame) }.getOrNull()
        planned?.usage?.let { usage ->
            audit.recordSystemEvent(
                task, outcome = "MODEL_USAGE", result = "计划生成", decision = "ALLOW", usage = usage,
            )
        }
        return planned?.value ?: fallbackPlan(spec)
    }

    /**
     * 功能：构造单步退化计划
     * 参数：spec 任务约束
     * 返回：只有一个子任务、一个步骤的计划
     *
     * 后置条件取完成契约里的必核字段——那是 TaskSpec 里唯一确定的"完成时页面上该有的文本"。
     */
    private fun fallbackPlan(spec: TaskSpec): TaskPlan = TaskPlan(
        planId = "${spec.taskId}:1",
        version = 1,
        subtasks = listOf(
            PlanSubtask(
                subtaskId = "s1",
                targetPackage = spec.packageName,
                objective = spec.inputText,
                steps = listOf(
                    PlanStep(
                        stepId = "s1-1",
                        intent = spec.inputText,
                        postcondition = spec.completionContract.requiredFields.take(MAX_POSTCONDITIONS),
                    ),
                ),
            ),
        ),
    )

    /**
     * 功能：执行主循环，直到完成、挂起或失败
     * 参数：session 执行会话
     * 返回：循环结果
     */
    private suspend fun executeLoop(session: Session): LoopResult {
        var task = session.task
        var stepIndex = task.budgetStepsUsed
        var escapeRecoveries = 0

        while (true) {
            // 计时从"这一轮开始"算起：预算检查、主屏冲突检查都算任务开销，
            // 但不进 observe 段——否则 AVG(observe_ms) 会把前置检查算成观察成本。
            val roundStartedAt = SystemClock.elapsedRealtime()
            budgetProblem(task, session.spec, stepIndex)?.let { return failTerminal(session, it) }

            // 用户在主屏打开同一个 App：购物车、草稿、订单等共享状态会被两边同时改动（§4.6 第 2 条）。
            // 命中即暂停，等用户离开主屏上的目标 App 再继续，而不是继续注入互相覆盖。
            mainScreenConflict(session.spec)?.let { reason ->
                if (escapeRecoveries < MAX_ESCAPE_RECOVERIES && recoverToDisplay(session.handle, session.spec)) {
                    // App 用 deep link 启动内部页面时会把整个任务 reparent 到主屏；恢复后继续跑，
                    // 用户屏幕才算真的没被占。恢复本身不改步数，用独立计数防止来回拉扯。
                    escapeRecoveries++
                    audit.recordSystemEvent(
                        task,
                        outcome = "MAIN_SCREEN_RECOVERED",
                        result = "目标应用跑到主屏，已在副屏重新拉起并清掉主屏实例（第 $escapeRecoveries 次）：$reason",
                    )
                    continue
                }
                transport.activities.removeTasksOutside(session.spec.packageName, session.handle)
                audit.recordSystemEvent(task, outcome = "MAIN_SCREEN_CONFLICT", result = reason)
                notifier.notifyResumable(task.taskId, reason)
                return suspendForHuman(task, reason)
            }

            val snapshot = observeOrRecover(session.handle, session.spec, task)
            val observedAt = SystemClock.elapsedRealtime()
            displayProblem(session, snapshot)?.let { reason ->
                // 副屏被重建或观察来自别的屏：编号与坐标全部失效，不能继续注入
                audit.recordSystemEvent(task, outcome = "DISPLAY_MISMATCH", result = reason)
                notifier.notifyResumable(task.taskId, reason)
                return suspendForHuman(task, reason)
            }
            // 控件树读不出来时只拿到截图：没有元素编号，任何写入都无法构造。
            // 这类失败通常发生在页面切换/动画期间，等一次再重采比直接判死更接近真实情况；
            // 连续多次仍不可读才交人（§2.2 观测降级）。
            // 纯视觉但 OCR 补出了元素表：可以继续决策（只读动作）。只有"连一行字都识别不到"
            // 才走下面的等待重采分支——否则微信这类封锁控件树的 App 会一路 WAIT 到预算耗尽。
            if (snapshot.mode == ObservationMode.VISION_ONLY && snapshot.elements.isEmpty()) {
                session.visionWaits++
                val reason = "控件树不可读，仅有截图（纯视觉降级，第 ${session.visionWaits} 次），等待后重采"
                recordAudit(
                    task.taskId, stepIndex + 1, snapshot,
                    action = null, decision = null, verdict = null,
                    outcome = "VISION_ONLY", result = reason,
                )
                if (session.visionWaits >= MAX_VISION_WAITS) {
                    val blocked = "连续 $MAX_VISION_WAITS 次只能取到截图、控件树始终不可读，已转人工"
                    notifier.notifyResumable(task.taskId, blocked)
                    return suspendForHuman(task, blocked)
                }
                delay(VISION_WAIT_MS)
                continue
            }
            session.visionWaits = 0

            // 页面级人机验证：美团/滴滴会在首页直接盖一层滑块/点选验证。模型看不到
            // "该拖哪里"，只会反复滑动，把风控越点越严；命中即整页交人，不再让它试。
            guard.pageVerification(snapshot)?.let { keyword ->
                val reason = "当前页面出现「$keyword」人机验证，需要你本人在手机上完成后任务再继续"
                recordAudit(
                    task.taskId, stepIndex + 1, snapshot,
                    action = null, decision = null, verdict = null,
                    outcome = "HUMAN_VERIFICATION", result = reason,
                    decisionOverride = "NEED_HUMAN",
                )
                notifier.notifyResumable(task.taskId, reason)
                return suspendForHuman(task, reason)
            }

            // 计划游标越过最后一步：全部步骤的后置条件都已达成，直接进入验证，
            // 不再要求模型额外声明 finish（§1.4：EXECUTING → VERIFYING 的条件是计划达成）
            val step = session.plan.stepAt(session.cursor)
            if (step == null) {
                recordAudit(
                    task.taskId, stepIndex + 1, snapshot,
                    action = null, decision = null, verdict = null,
                    outcome = "PLAN_DONE", result = "计划全部 ${session.plan.stepCount} 步已达成",
                )
                return LoopResult.Finished(snapshot, "计划全部步骤已达成")
            }

            // 换步骤时记下入口页面的文本：后面用它判断本步的后置条件
            // 是"页面上真有的字"还是"计划期猜的文案"。
            val entryKey = "${session.cursor.subtaskIndex}:${session.cursor.stepIndex}"
            if (session.stepEntryKey != entryKey) {
                session.stepEntryKey = entryKey
                session.stepEntryText = pageTextOf(snapshot)
            }

            // 一次性遮挡只在本任务内记录一次分类，绝不进入记忆（§1.6.1）。
            // 放在计划走完之后：任务已经达成时不该被一个弹窗拦成挂起，直接去验证。
            snapshot.overlay?.let { overlay ->
                if (!session.overlaySeen) {
                    session.overlaySeen = true
                    session.task = task
                    task = persistSession(session)
                    audit.recordSystemEvent(
                        task = task,
                        outcome = overlay.kind.name,
                        result = "${overlay.summary()}（观察 ${snapshot.observationId}）",
                        decision = if (overlay.kind == OverlayKind.BLOCKING_OVERLAY) "NEED_HUMAN" else "ALLOW",
                    )
                }
                if (overlay.kind == OverlayKind.BLOCKING_OVERLAY) {
                    val reason = "当前遮挡需本人决定：${overlay.summary()}"
                    notifier.notifyResumable(task.taskId, reason)
                    return suspendForHuman(task, reason)
                }
            }

            val stepLabel = session.plan.labelOf(session.cursor)
            // 每步先读记忆再决策（§2.5.6）：命中才写一条 MEMORY_INJECTED，未命中不留噪音
            val memories = memory.retrieve(session.spec.taskType, snapshot, session.spec.parameters)
            session.stepMemoryIds = memories.map { it.procedure.id }
            // 注入过哪些配方要立刻落库：进程若在决策中途被杀，续跑才能把这些配方记成失败
            task = persistSession(session)
            if (memories.isNotEmpty()) {
                recordAudit(
                    task.taskId, stepIndex + 1, snapshot,
                    action = null, decision = null, verdict = null,
                    outcome = "MEMORY_INJECTED",
                    result = memories.joinToString("；") { it.procedure.title },
                    memoryUsed = memories.joinToString("；") { "${it.procedure.id}|${it.procedure.title}" },
                    memoryAssertion = memories.joinToString("；") { it.assertion },
                )
            }
            // 单步超时只包住"决策"这一段：注入一旦开始就不能取消，取消会留下未知副作用。
            // 取消点是 LlmClient 的异步调用——阻塞式 execute() 无法被 withTimeout 打断，
            // 这也是本轮把客户端改成 enqueue 的原因。
            val decided = withTimeoutOrNull(session.spec.budget.maxStepDurationMs) {
                executor.decide(
                    ExecutorRequest(
                        spec = session.spec,
                        plan = session.plan,
                        cursor = session.cursor,
                        recentSteps = auditDao.recentSteps(task.taskId, HISTORY_LIMIT),
                        snapshot = snapshot,
                        memories = memories,
                        credentials = credentials.availableFor(snapshot.packageName),
                    ),
                )
            }
            val decidedAt = SystemClock.elapsedRealtime()
            if (decided == null) {
                // 请求可能已经到达后端，按一次调用计费：否则"超时"会成为绕开调用预算的口子
                task = task.copy(budgetModelCalls = task.budgetModelCalls + 1)
                session.task = task
                session.stepTimeouts++
                val reason = "单步决策超过 ${session.spec.budget.maxStepDurationMs / 1000} 秒，" +
                    "已取消本次模型调用（连续第 ${session.stepTimeouts} 次）"
                recordAudit(
                    task.taskId, stepIndex + 1, snapshot,
                    action = null, decision = null, verdict = null,
                    outcome = "STEP_TIMEOUT", result = reason,
                    decisionOverride = if (session.stepTimeouts >= MAX_STEP_TIMEOUTS) "NEED_HUMAN" else "ALLOW",
                    timing = StepTiming(
                        observeMs = observedAt - roundStartedAt,
                        decideMs = decidedAt - observedAt,
                        actMs = 0,
                    ),
                )
                if (session.stepTimeouts >= MAX_STEP_TIMEOUTS) {
                    notifier.notifyResumable(task.taskId, reason)
                    return suspendForHuman(task, reason)
                }
                continue
            }
            val decision = decided
            session.stepTimeouts = 0
            // 按实际尝试次数计费：解析失败重试也算一次模型调用
            task = task.copy(budgetModelCalls = task.budgetModelCalls + decision.attempts)
            // 立刻同步给会话：finish 分支会直接走验证，若不在这里同步，模型调用计数就丢了
            session.task = task

            when (val action = decision.action) {
                is AgentAction.Finish -> {
                    recordAudit(
                        task.taskId, stepIndex + 1, snapshot,
                        action = null, decision = decision, verdict = null,
                        outcome = "FINISH", result = action.summary,
                    )
                    return LoopResult.Finished(snapshot, action.summary)
                }

                is AgentAction.StepDone -> {
                    // 模型只能声明；是否推进游标由后置条件在当前界面上复核（§1.3.2）
                    val outcome = handleStepDone(session, task, stepIndex + 1, snapshot, step, action, decision)
                    stepIndex = outcome.stepIndex
                    task = outcome.task
                    session.task = task
                    if (outcome.kind == StepKind.SUSPENDED) {
                        return LoopResult.Suspended(outcome.reason.orEmpty())
                    }
                }

                is AgentAction.Replan -> {
                    recordAudit(
                        task.taskId, stepIndex + 1, snapshot,
                        action = null, decision = decision, verdict = null,
                        outcome = "REPLAN_REQUEST", result = action.reason,
                    )
                    val outcome = replanOrSuspend(session, task, stepIndex + 1, snapshot, action.reason)
                    stepIndex = outcome.stepIndex
                    task = outcome.task
                    session.task = task
                    if (outcome.kind == StepKind.SUSPENDED) {
                        return LoopResult.Suspended(outcome.reason.orEmpty())
                    }
                }

                is AgentAction.Do -> {
                    // 同一步反复无效：继续烧预算只会把失败拖到"任务时长超限"，尽早交人
                    stuckReason(task)?.let { reason ->
                        recordAudit(
                            task.taskId, stepIndex + 1, snapshot, action, decision, verdict = null,
                            outcome = "STUCK", result = reason, decisionOverride = "NEED_HUMAN",
                        )
                        notifier.notifyResumable(task.taskId, reason)
                        return suspendForHuman(task, reason)
                    }
                    val outcome = handleAction(
                        session, task, stepIndex, snapshot, stepLabel,
                        coerceInputTarget(task, session, stepIndex, snapshot, action, decision),
                        decision,
                        observeMs = observedAt - roundStartedAt,
                        decideMs = decidedAt - observedAt,
                    )
                    stepIndex = outcome.stepIndex
                    task = outcome.task
                    session.task = task
                    when (outcome.kind) {
                        StepKind.CONTINUE -> {
                            // 动作与完成声明合并（"边做边声明"）：这一下既是有效动作、又声称完成了本步。
                            // 声明照样走 handleStepDone 的新帧复核，复核不过仍会驳回——省的是那次
                            // 纯声明的视觉请求，不是这道核对。动作被拦下/作废时声明不成立，直接忽略。
                            //
                            // 页面零变化（NO_EFFECT）时也不能算完成：真机上出现过「TAP 点菜 → NO_EFFECT
                            // → STEP_DONE 进入商家页面」，页面根本没变，等于把没做成记成做成了。
                            if (action.stepDone && outcome.actionExecuted && outcome.effect == EffectOutcome.NO_EFFECT) {
                                recordAudit(
                                    task.taskId, stepIndex + 1, snapshot, action, decision, verdict = null,
                                    outcome = "STEP_DONE_WEAK_REJECTED",
                                    result = "这条动作没有改变页面（NO_EFFECT），不能据此认定步骤完成；" +
                                        "请确认页面是否真的变了，或换一个动作",
                                )
                            } else if (action.stepDone && outcome.actionExecuted) {
                                val done = handleStepDone(
                                    session = session,
                                    task = task,
                                    stepIndex = stepIndex,
                                    snapshot = snapshot,
                                    step = step,
                                    action = AgentAction.StepDone(
                                        observationId = snapshot.observationId,
                                        summary = action.stepSummary.orEmpty(),
                                    ),
                                    // 同一次模型调用已经在那条动作审计上记过用量，
                                    // 这条合并出来的 STEP_DONE 不能再记一次，否则成本被算两遍。
                                    decision = decision.copy(usage = null),
                                )
                                stepIndex = done.stepIndex
                                task = done.task
                                session.task = task
                                if (done.kind == StepKind.SUSPENDED) {
                                    return LoopResult.Suspended(done.reason.orEmpty())
                                }
                            }
                            // 连续把 input 打到非输入控件上：多半是计划步骤选错了入口
                            // （美团首页把「搜索框」和分类图标画在一起，模型会反复猜错）。
                            // 到上限先重规划，没有余量再交人，不要让它一直换着图标试。
                            if (session.inputTargetRejections >= MAX_INPUT_TARGET_REJECTIONS) {
                                val replanned = replanOrSuspend(
                                    session = session,
                                    task = task,
                                    stepIndex = stepIndex,
                                    snapshot = snapshot,
                                    reason = "连续 ${session.inputTargetRejections} 次输入目标不是输入框，计划步骤可能选错了入口",
                                )
                                stepIndex = replanned.stepIndex
                                task = replanned.task
                                session.task = task
                                if (replanned.kind == StepKind.SUSPENDED) {
                                    return LoopResult.Suspended(replanned.reason.orEmpty())
                                }
                            }
                            // 纯视觉下连续猜坐标：OCR 给不出图标语义，再猜只会继续烧调用
                            if (session.blindTapRejections >= MAX_BLIND_TAP_REJECTIONS) {
                                val replanned = replanOrSuspend(
                                    session = session,
                                    task = task,
                                    stepIndex = stepIndex,
                                    snapshot = snapshot,
                                    reason = "连续 ${session.blindTapRejections} 次坐标盲点被拦下，纯视觉页面缺少可点文字入口",
                                )
                                stepIndex = replanned.stepIndex
                                task = replanned.task
                                session.task = task
                                if (replanned.kind == StepKind.SUSPENDED) {
                                    return LoopResult.Suspended(replanned.reason.orEmpty())
                                }
                            }
                            // 同一步连续多个动作仍未达成：计划前提大概率失效。先受限重规划，
                            // 没有重规划余量再交人，不要一路烧到步数预算耗尽。
                            if (session.actionsOnStep >= STEP_ACTION_LIMIT) {
                                val replanned = replanOrSuspend(
                                    session = session,
                                    task = task,
                                    stepIndex = stepIndex,
                                    snapshot = snapshot,
                                    reason = "步骤 ${step.stepId} 执行 ${session.actionsOnStep} 个动作仍未达成",
                                )
                                stepIndex = replanned.stepIndex
                                task = replanned.task
                                session.task = task
                                if (replanned.kind == StepKind.SUSPENDED) {
                                    return LoopResult.Suspended(replanned.reason.orEmpty())
                                }
                            }
                        }

                        StepKind.SUSPENDED -> return LoopResult.Suspended(outcome.reason.orEmpty())

                        // 待支付页交接：不代付，当前页面直接转验证并按结果页交付
                        StepKind.FINISHED -> return LoopResult.Finished(snapshot, outcome.reason.orEmpty())
                    }
                }
            }
        }
    }

    /**
     * 功能：推进游标，并顺带吞掉"当前帧已经满足判据"的后续步骤
     * 参数：session 会话；evidence 复核用的新帧
     * 返回：推进后的游标与顺带推进的步数
     *
     * 只在后续步骤**自己写了后置条件且全部命中**时才顺带推进：没有判据的步骤必须由模型
     * 明确声明，否则等于替模型猜它做没做完。顺带推进同样落库（游标持久化），审计里会写
     * 明"并顺带满足后续 N 步的判据"，不把它伪装成一次普通声明。
     */
    private fun advanceGreedily(session: Session, evidence: ObservationSnapshot): Pair<PlanCursor, Int> {
        var cursor = session.plan.advance(session.cursor)
        var extra = 0
        while (extra < MAX_GREEDY_ADVANCE) {
            val next = session.plan.stepAt(cursor) ?: break
            if (next.postcondition.isEmpty()) break
            if (PlanProgress.matchedFragments(next, evidence).isEmpty()) break
            cursor = session.plan.advance(cursor)
            extra++
        }
        return cursor to extra
    }

    /**
     * 功能：处理模型的步骤完成声明，复核后推进计划游标
     * 参数：session 会话；task 任务；stepIndex 计入本次声明后的步数；snapshot 决策时的观察；
     *       step 被声明的步骤；action 声明；decision 决策结果
     * 返回：本步之后的步数、任务与处置
     *
     * 复核用**新取的一帧**而不是模型看到的同一帧：一次翻页就可能让旧证据失效，
     * 用同一份快照复核等于让模型自证。规划器给不出后置条件时只能按声明推进，
     * 这种情况在审计里明确标成弱证据，不假装核对过。
     */
    private suspend fun handleStepDone(
        session: Session,
        task: TaskEntity,
        stepIndex: Int,
        snapshot: ObservationSnapshot,
        step: PlanStep,
        action: AgentAction.StepDone,
        decision: DecideResult,
    ): StepResult {
        val evidence = runCatching { observe(session.handle) }.getOrNull() ?: snapshot
        val matched = PlanProgress.matchedFragments(step, evidence)

        if (step.postcondition.isEmpty() || matched.isNotEmpty()) {
            val (advanced, extra) = advanceGreedily(session, evidence)
            val assertion = when {
                step.postcondition.isEmpty() -> "规划器未给后置条件，按模型声明推进（弱证据）"
                extra > 0 -> "页面出现后置条件：${matched.joinToString("；")}；并顺带满足后续 $extra 步的判据"
                else -> "页面出现后置条件：${matched.joinToString("；")}"
            }
            recordAudit(
                task.taskId, stepIndex, snapshot,
                action = null, decision = decision, verdict = null,
                outcome = "STEP_DONE", result = action.summary, evidence = assertion,
            )
            session.cursor = advanced
            session.actionsOnStep = 0
            session.inputTargetRejections = 0
            session.blindTapRejections = 0
            session.stepRejections = 0
            session.stepMemoryIds = emptyList()
            val persisted = persistStepAdvance(task, stepIndex, evidence, session.plan, advanced)
            session.task = persisted
            return StepResult(stepIndex, persistSession(session))
        }

        // 判据里的文案一个都不在本步入口页面上 → 这是计划期在没见过的页面上猜的标签。
        // 驳回它只会让模型反复重试到重规划上限，最后仍要人处理；改为按声明推进，
        // 并在审计里写明"弱证据"，把核对压力交给最终的独立复验。
        if (!groundedInEntryPage(step.postcondition, session)) {
            val advanced = session.plan.advance(session.cursor)
            recordAudit(
                task.taskId, stepIndex, snapshot,
                action = null, decision = decision, verdict = null,
                outcome = "STEP_DONE_WEAK",
                result = action.summary,
                evidence = "后置条件「${step.postcondition.joinToString("；")}」不在本步入口页面上，" +
                    "本地无法核对，按模型声明推进（弱证据）",
            )
            session.cursor = advanced
            session.actionsOnStep = 0
            session.inputTargetRejections = 0
            session.blindTapRejections = 0
            session.stepRejections = 0
            session.stepMemoryIds = emptyList()
            val persisted = persistStepAdvance(task, stepIndex, evidence, session.plan, advanced)
            session.task = persisted
            return StepResult(stepIndex, persistSession(session))
        }

        session.stepRejections++
        // 把"下一步该怎么走出这个死局"直接写进驳回信息：它会进 recentSteps 喂回模型。
        // 真机上就是这样：模型把用户原话「关于手机」当成目标页文案，而小米这页叫「我的设备」，
        // 只告诉它"没出现"它会一直换着法子点，不知道可以改判据。
        val reason = "声称完成 ${step.stepId}，但页面上没有出现后置条件：" +
            step.postcondition.joinToString("；") +
            "。若当前页面已是目标页、只是文案与计划不同，请调用 replan 调整本步判据，不要反复声明完成"
        recordAudit(
            task.taskId, stepIndex, snapshot,
            action = null, decision = decision, verdict = null,
            outcome = "STEP_REJECTED", result = reason, decisionOverride = "DENY",
        )
        val persisted = persistProgress(task, stepIndex, snapshot, applied = false, goal = session.plan.labelOf(session.cursor))
        session.task = persisted
        if (session.stepRejections >= MAX_STEP_REJECTIONS) {
            return replanOrSuspend(
                session = session,
                task = persisted,
                stepIndex = stepIndex,
                snapshot = snapshot,
                reason = "步骤 ${step.stepId} 连续 ${session.stepRejections} 次声称完成但页面没有后置条件",
            )
        }
        return StepResult(stepIndex, persistSession(session))
    }

    /**
     * 功能：受限重规划，或在没有余量时转人工
     * 参数：session 会话；task 任务；stepIndex 当前步数；snapshot 用于规划的观察；reason 触发原因
     * 返回：本步之后的步数、任务与处置
     *
     * 重规划只换执行路径，不换允许做什么：TaskSpec、白名单、预算与已消耗步数全部沿用。
     * 给不出可行计划、或次数用尽时挂起，让用户决定，而不是让模型在死路上继续烧预算。
     */
    private suspend fun replanOrSuspend(
        session: Session,
        task: TaskEntity,
        stepIndex: Int,
        snapshot: ObservationSnapshot,
        reason: String,
    ): StepResult {
        // 走到这里说明当前步骤没走通：注入过的配方记一次失败，达到阈值即转 STALE。
        // 放在最前面而不是重规划成功之后——重规划给不出计划同样说明这一步的配方没帮上忙。
        val staled = memoryWriter.recordStepFailure(session.stepMemoryIds)
        if (staled.isNotEmpty()) {
            // 降级必须留痕：否则"配方为什么突然不参与检索了"只能靠猜
            audit.recordSystemEvent(
                task = task,
                outcome = "MEMORY_STALE",
                result = staled.joinToString("；") { "「${it.title}」失败 ${it.failCount} 次，移入待复核区" },
            )
        }
        session.stepMemoryIds = emptyList()

        if (session.replans >= MAX_REPLANS) {
            val blocked = "计划连续失效且已重规划 ${session.replans} 次：$reason"
            val persisted = persistProgress(task, stepIndex, snapshot, applied = false, goal = task.currentSubGoal.orEmpty())
            session.task = persisted
            session.task = persistSession(session)
            audit.recordSystemEvent(persisted, outcome = "REPLAN_LIMIT", result = blocked)
            notifier.notifyResumable(persisted.taskId, blocked)
            suspendForHuman(persisted, blocked)
            return StepResult(stepIndex, session.task, StepKind.SUSPENDED, blocked)
        }

        val planned = runCatching { planner.replan(session.spec, session.plan, reason, snapshot) }
            .getOrNull()
        planned?.usage?.let { usage ->
            audit.recordSystemEvent(
                task, outcome = "MODEL_USAGE", result = "重规划", decision = "ALLOW", usage = usage,
            )
        }
        val next = planned?.value
        if (next == null) {
            val blocked = "重规划给不出可行计划：$reason"
            val persisted = persistProgress(task, stepIndex, snapshot, applied = false, goal = task.currentSubGoal.orEmpty())
            session.task = persisted
            session.task = persistSession(session)
            audit.recordSystemEvent(persisted, outcome = "REPLAN_GAVE_UP", result = blocked)
            notifier.notifyResumable(persisted.taskId, blocked)
            suspendForHuman(persisted, blocked)
            return StepResult(stepIndex, session.task, StepKind.SUSPENDED, blocked)
        }

        val previousVersion = session.plan.version
        val cursor = PlanCursor.startOf(next)
        session.plan = next
        session.cursor = cursor
        session.replans++
        session.actionsOnStep = 0
        session.inputTargetRejections = 0
        session.blindTapRejections = 0
        session.stepRejections = 0
        recordAudit(
            task.taskId, stepIndex, snapshot,
            action = null, decision = null, verdict = null,
            outcome = "REPLAN", result = "v$previousVersion → v${next.version}：$reason",
        )
        val persisted = persistStepAdvance(task, stepIndex, snapshot, next, cursor)
        session.task = persisted
        return StepResult(stepIndex, persistSession(session))
    }

    /**
     * 功能：处理一个具体动作（复验 → 闸门 → 授权 → 注入 → 后置条件）
     * 参数：session 会话；task 任务；stepIndex 当前步数；snapshot 观察；stepLabel 当前计划步骤标签；
     *       action 动作；decision 决策结果
     * 返回：本步之后的步数、任务与处置
     *
     * 抽成一个函数而不是继续摊在主循环里：主循环只剩"取帧 → 决策 → 分发"，
     * 每一步的闸门顺序（复验在授权之前、落盘在注入之前）才能一眼看清。
     */
    private suspend fun handleAction(
        session: Session,
        task: TaskEntity,
        stepIndex: Int,
        snapshot: ObservationSnapshot,
        stepLabel: String,
        action: AgentAction.Do,
        decision: DecideResult,
        observeMs: Long,
        decideMs: Long,
    ): StepResult {
        val actStartedAt = SystemClock.elapsedRealtime()
        val digest = paramsDigestOf(action, snapshot)
        val transient = snapshot.overlay?.takeIf { it.kind == OverlayKind.TRANSIENT_OVERLAY }
        val overlayDismiss = transient != null && action.kind == ActionKind.TAP &&
            (action.elementId == transient.closeIndex ||
                OverlayPolicy.isDismiss(targetTextOf(action, snapshot)))

        // 同一个遮挡反复出现说明关闭没有真正生效，继续点只会形成循环（§1.6.1 第 5 条）。
        if (overlayDismiss && session.overlayDismissals >= MAX_OVERLAY_DISMISSALS) {
            val reason = "瞬时遮挡已关闭 ${session.overlayDismissals} 次仍反复出现，疑似关闭循环，已转人工"
            recordAudit(
                task.taskId, stepIndex + 1, snapshot, action, decision, verdict = null,
                outcome = "OVERLAY_LOOP", digest = digest, result = reason, decisionOverride = "NEED_HUMAN",
            )
            notifier.notifyResumable(task.taskId, reason)
            return suspendStep(task, stepIndex + 1, snapshot, stepLabel, reason)
        }

        if (action.kind != ActionKind.BACK && action.kind != ActionKind.WAIT &&
            session.spec.packageName.isNotEmpty() &&
            !PackageScope.isWithin(session.spec.packageName, snapshot.packageName)
        ) {
            // 先试一次本地自愈：目标 App 把功能拆到别的包时（设置 → settings.intelligence），
            // 模型这一动作是打给"另一个 App"的，绝不能执行；但直接转人工太粗暴，
            // 先按一次返回，确认回到目标包就继续（§1.6 "Back 一次并重新观测"）。
            tryRecoverPackageEscape(session, task, stepIndex, snapshot, action, decision, digest)
                ?.let { return it }

            // 自愈次数用尽：禁止注入，转人工（§1.2）。
            // BACK 与 WAIT 例外：它们不产生副作用，是回到目标 App 的唯一自愈手段。
            val reason = "前台应用是 ${snapshot.packageName}，不在任务目标 ${session.spec.packageName} 范围内，禁止注入"
            recordAudit(
                task.taskId, stepIndex + 1, snapshot, action, decision, verdict = null,
                outcome = "BLOCKED", digest = digest, result = reason, decisionOverride = "BLOCKED",
            )
            notifier.notifyResumable(task.taskId, reason)
            return suspendStep(task, stepIndex + 1, snapshot, stepLabel, reason)
        }

        // 同一句话重复输入：自定义输入框（DeepSeek 输入框、美团订单备注）读不回正文时，
        // 模型看不到刚写进去的字，会以为没写成功而反复重输（真机 12–20 次，白烧 10+ 次调用）。
        // 这里按"文本 + 目标锚点"认出重复，软拒绝并回显原因，逼它继续下一步。
        if (action.kind == ActionKind.TEXT) {
            val writeKey = textWriteKey(action)
            if (writeKey != null && writeKey == session.lastTextWrite) {
                val reason = "同一输入框已经写入相同文本「${action.text}」，页面读不回正文属正常，" +
                    "不要重复输入；请直接执行下一步"
                recordAudit(
                    task.taskId, stepIndex + 1, snapshot, action, decision, verdict = null,
                    outcome = "DUPLICATE_TEXT", digest = digest, result = reason,
                )
                session.actionsOnStep++
                session.task = persistProgress(task, stepIndex + 1, snapshot, applied = false, stepLabel)
                return StepResult(stepIndex + 1, session.task, actionExecuted = false)
            }
        }

        // input 打到非输入控件上：真机上模型会把搜索框认成旁边的图标或列表项，
        // 注入到不可编辑容器后既写不进去、又会被判成"结果未知"直接转人工。
        // 这里在注入前拦下并作为一次软拒绝继续循环，模型下一次能看到原因并改选目标。
        invalidInputTarget(action, snapshot)?.let { reason ->
            recordAudit(
                task.taskId, stepIndex + 1, snapshot, action, decision, verdict = null,
                outcome = "INVALID_TARGET", digest = digest, result = reason,
            )
            session.inputTargetRejections++
            session.actionsOnStep++
            session.task = persistProgress(task, stepIndex + 1, snapshot, applied = false, stepLabel)
            return StepResult(stepIndex + 1, session.task, actionExecuted = false)
        }
        // 目标校验通过：清空连续拦截计数，避免偶发一次错点把整步提前判死
        if (action.kind == ActionKind.TEXT) session.inputTargetRejections = 0

        // 纯视觉下的坐标盲点：OCR 只能给出文字位置，图标/空白区没有语义。
        // 真机上模型会反复猜「搜索图标在哪」，连点三次都没命中后整任务判 STUCK。
        // 落点不在任何 OCR 文字元素上时先软拒绝，让它改点文字入口或换策略。
        blindCoordinateTap(action, snapshot)?.let { reason ->
            recordAudit(
                task.taskId, stepIndex + 1, snapshot, action, decision, verdict = null,
                outcome = "BLIND_TAP", digest = digest, result = reason,
            )
            session.blindTapRejections++
            session.actionsOnStep++
            session.task = persistProgress(task, stepIndex + 1, snapshot, applied = false, stepLabel)
            return StepResult(stepIndex + 1, session.task, actionExecuted = false)
        }
        // 坐标点击落在 OCR 文字上：清空连续盲点计数
        if (action.kind == ActionKind.TAP) session.blindTapRejections = 0

        if (!targetStillThere(session.handle, action, snapshot)) {
            // 模型思考期间页面变了：旧编号作废，重新观测后再决策（§1.3 S4d）
            recordAudit(
                task.taskId, stepIndex + 1, snapshot, action, decision, verdict = null,
                outcome = "STALE", digest = digest, result = "页面已变，动作作废",
            )
            return StepResult(
                stepIndex + 1,
                persistProgress(task, stepIndex + 1, snapshot, false, stepLabel),
                actionExecuted = false,
            )
        }

        val verdict = guard.assess(session.spec, action, snapshot)

        // 点餐/下单类任务走到「立即支付」这种只剩付款的按钮：不再代点，也不把用户喊回来重跑，
        // 直接把当前页面按结果页交付；地址与备注已在前面填好，本人过来只需要点一下付款。
        paymentHandoverReason(session.spec, action, snapshot)?.let { reason ->
            recordAudit(
                task.taskId, stepIndex + 1, snapshot, action, decision, verdict,
                outcome = "PAYMENT_HANDOVER", digest = digest,
                result = reason, decisionOverride = "HANDOFF",
            )
            session.task = persistProgress(task, stepIndex + 1, snapshot, applied = false, stepLabel)
            return StepResult(stepIndex + 1, session.task, StepKind.FINISHED, reason, actionExecuted = false)
        }

        if (!verdict.allowed && !verdict.requiresConfirmation) {
            // L2B 或语义不明：硬阻断，只能由本人接管；语义不明的挂起允许用户排除页面问题后「继续」
            recordAudit(
                task.taskId, stepIndex + 1, snapshot, action, decision, verdict,
                outcome = "BLOCKED", digest = digest, result = verdict.reason,
            )
            if (verdict.riskLevel == RiskLevel.L2B) {
                notifier.notifySelfOnly(task.taskId, verdict.reason)
            } else {
                notifier.notifyResumable(task.taskId, verdict.reason)
            }
            return suspendStep(task, stepIndex + 1, snapshot, stepLabel, verdict.reason)
        }

        val intent = prepareIntent(
            task = task,
            stepIndex = stepIndex + 1,
            snapshot = snapshot,
            action = action,
            verdict = verdict,
            digest = digest,
            planVersion = session.plan.version,
            planStepId = session.plan.stepAt(session.cursor)?.stepId,
        )
        // 定时任务的预设授权：只对 L2A 生效，且不写 grant 表。
        // 它放行的是"这一类动作"，不是"这一个动作"，所以审计里必须留下 STANDING_GRANT；
        // L2B（支付/验证码/人脸）在 Guard 那一步就已经硬阻断，这里碰不到。
        val standing = canAutoApprove(verdict, session.spec.autoApproveL2a)
        if (standing) {
            audit.recordSystemEvent(
                task = task,
                outcome = "STANDING_GRANT",
                result = "定时任务预设授权自动批准 ${action.kind.name}：${verdict.reason}",
                decision = "ALLOW",
            )
        }
        val grantId = if (verdict.requiresConfirmation && !standing) {
            val granted = consumeGrant(task, action, snapshot, intent)
            if (granted == null) {
                recordAudit(
                    task.taskId, stepIndex + 1, snapshot, action, decision, verdict,
                    outcome = "NEED_CONFIRM", digest = digest,
                    result = verdict.reason, decisionOverride = "NEED_CONFIRM",
                )
                notifier.notifyConfirmation(task.taskId, confirmationSummary(action, snapshot))
                return suspendStep(task, stepIndex + 1, snapshot, stepLabel, verdict.reason)
            }
            granted
        } else {
            null
        }

        intent?.let { intentDao.upsert(it.copy(status = "INJECTING", grantId = grantId)) }

        var applied = runCatching { applier.apply(session.handle, action, snapshot) }.getOrDefault(false)
        val quiet = stability.awaitQuiet(session.handle, QUIET_TIMEOUT_MS)

        val evidenceFrames = postActionFrames(session.handle)
        // 新页面创建期间窗口根节点会长时间不可读；安静轮询里最后一次读到的树是这时最可靠的证据
        val evidenceTree = evidenceFrames.second ?: quiet.lastTree
        var effect = conditions.assess(snapshot, action, evidenceFrames.first, evidenceTree)
        var evidence = evidenceOf(applied, effect, weak = evidenceFrames.first == null) +
            diffEvidence(snapshot, evidenceFrames.first, evidenceTree)
        // 回放用的前/后帧：前帧直接用观察里的截图，后帧用动作后一致观察的截图；
        // 两者都不额外取帧，避免为了留存证据给每个动作再加一次 200ms 的取帧成本。
        var afterImageBytes = evidenceFrames.first?.image?.bytes

        // 幂等只读动作"系统接受但页面没有反应"时允许原地再点一次：目标仍在说明页面没换，
        // 重复点击不产生第二个副作用；只在 L0 + 无写入意图时这样做，写操作一律不许重试。
        if (effect == EffectOutcome.NO_EFFECT && intent == null &&
            verdict.riskLevel == RiskLevel.L0 && action.kind == ActionKind.TAP &&
            targetStillThere(session.handle, action, snapshot)
        ) {
            recordAudit(
                task.taskId, stepIndex + 1, snapshot, action, decision, verdict,
                outcome = "NO_EFFECT_RETRY", digest = digest,
                result = "系统接受但页面无变化，目标仍在，按幂等重试 1 次",
            )
            applied = runCatching { applier.apply(session.handle, action, snapshot) }.getOrDefault(false)
            val retryQuiet = stability.awaitQuiet(session.handle, QUIET_TIMEOUT_MS)
            val retryFrames = postActionFrames(session.handle)
            val retryTree = retryFrames.second ?: retryQuiet.lastTree
            effect = conditions.assess(snapshot, action, retryFrames.first, retryTree)
            evidence = evidenceOf(applied, effect, weak = retryFrames.first == null) +
                diffEvidence(snapshot, retryFrames.first, retryTree) + "；幂等重试 1 次"
            afterImageBytes = retryFrames.first?.image?.bytes
        }

        // 动画型页面（首页轮播、骨架屏）常拿不到一致观察，单帧又看不到变化 → 判未知。
        // 只读点击没有副作用、目标也还在，隔一会儿再看一眼比直接交人更接近事实：
        // 真机上美团首页就是这种情况——第一次判未知，实际页面已经跳走了。
        // 只要求"没有写入意图"：L1 的普通点击同样会被动画页面判成未知（B 站首页实测），
        // 延迟复核不重放任何动作，只是再看一眼，风险与 L0 相同。
        // TEXT 也允许复核：输入框在转场/渲染完成前读不到正文，页面稍后稳定就能确认；
        // 复核同样不重放输入，只是重新观察，不会造成二次写入。
        val recheckable = action.kind == ActionKind.TEXT ||
            (intent == null && action.kind == ActionKind.TAP)
        if (effect == EffectOutcome.OUTCOME_UNKNOWN && recheckable) {
            // 两轮复核：1.2s 后一次，仍未知再等 2.5s 一次。B 站跳视频页这类重转场
            // 1.2s 不够，一次复核不到就挂人工会让演示与真实使用都很难走通。
            for (recheckGap in listOf(UNKNOWN_RECHECK_MS, UNKNOWN_RECHECK_SECOND_MS)) {
                delay(recheckGap)
                val recheckFrames = postActionFrames(session.handle)
                val recheckTree = recheckFrames.second
                if (recheckFrames.first == null && recheckTree == null) continue
                effect = conditions.assess(snapshot, action, recheckFrames.first, recheckTree)
                evidence = evidenceOf(applied, effect, weak = recheckFrames.first == null) +
                    diffEvidence(snapshot, recheckFrames.first, recheckTree) + "；未知后延迟复核"
                afterImageBytes = recheckFrames.first?.image?.bytes ?: afterImageBytes
                recordAudit(
                    task.taskId, stepIndex + 1, snapshot, action, decision, verdict,
                    outcome = "UNKNOWN_RECHECK", digest = digest,
                    result = "等待 ${recheckGap}ms 后复核，结论 ${effect.name}",
                )
                if (effect != EffectOutcome.OUTCOME_UNKNOWN) break
            }
        }

        // 自定义输入框（DeepSeek/微信）常读不回正文：注入已被系统接受、且目标已通过
        // 输入区域校验时，再挂起只会让用户手动核对一段已经写进去的文字。
        // 这里按"已写入"继续，证据里明确标注未回读；模型下一步看到新页面会自行纠偏。
        if (effect == EffectOutcome.OUTCOME_UNKNOWN && action.kind == ActionKind.TEXT && applied) {
            effect = EffectOutcome.CONFIRMED
            evidence += "；系统接受写入，页面未回读（按已写入继续）"
        }

        val beforeShot = auditShots.save(task.taskId, stepIndex + 1, "before", snapshot.image?.bytes)
        val afterShot = auditShots.save(task.taskId, stepIndex + 1, "after", afterImageBytes)

        intent?.let { pending ->
            intentDao.upsert(
                pending.copy(
                    status = "SETTLED",
                    outcome = effect.name,
                    systemReturnValue = applied,
                    evidence = evidence,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        recordAudit(
            task.taskId, stepIndex + 1, snapshot, action, decision, verdict,
            outcome = effect.name, digest = digest, actionId = intent?.actionId,
            grantId = grantId, result = evidence,
            // 基线判定是"无授权不可执行"，但这一步确实凭授权执行了；审计要写 ALLOW 并靠 grantId 区分
            decisionOverride = if (grantId != null || standing) "ALLOW" else null,
            screenshotBefore = beforeShot,
            screenshotAfter = afterShot,
            timing = StepTiming(
                observeMs = observeMs,
                decideMs = decideMs,
                actMs = SystemClock.elapsedRealtime() - actStartedAt,
            ),
        )

        // 记住这次真正落盘的输入，供下一次重复输入拦截；非 TEXT 动作会改变页面焦点，清空记忆
        session.lastTextWrite = if (action.kind == ActionKind.TEXT) {
            textWriteKey(action)?.takeIf { applied }
        } else {
            null
        }

        val advanced = persistProgress(task, stepIndex + 1, snapshot, applied, stepLabel)

        // 写操作结果未知：禁止重放，先进 RECONCILING 再交人（§1.3 R4）
        if (intent != null && effect == EffectOutcome.OUTCOME_UNKNOWN) {
            val reconciling = transition(advanced, TaskState.RECONCILING)
            notifier.notifySelfOnly(task.taskId, "动作结果未知，需要你核对后再决定")
            val reason = "动作 ${intent.actionId.take(8)} 结果未知，禁止自动重放"
            suspendForHuman(reconciling, reason)
            return StepResult(stepIndex + 1, reconciling, StepKind.SUSPENDED, reason)
        }

        // 计"这一步上白走了几个动作"：连续多个动作仍未达成时触发受限重规划，
        // 结果未知的写入不进这个计数——它已经挂起核对，不需要再靠重规划解围
        session.actionsOnStep++
        if (overlayDismiss) session.overlayDismissals++
        session.task = advanced
        return StepResult(stepIndex + 1, persistSession(session), effect = effect)
    }

    /**
     * 功能：前台离开目标包时，本地按返回自愈
     * 参数：session 会话；task 任务；stepIndex 当前步数；snapshot 越界时的观察；
     *       action 模型原本想做的动作；decision 决策结果；digest 参数摘要
     * 返回：处置结果；自愈次数已用尽时为 null（调用方走原来的挂起分支）
     *
     * 为什么由本地注入而不是让模型自己按返回：模型这一动作的目标在另一个 App 上，
     * 本地必须先把它拦下；而"回到目标 App"是纯导航动作、没有副作用，属于 §1.3.2 说的
     * "为寻找目标做一次安全返回"，不改变计划也不越过任何闸门。
     *
     * 允许连按多次：有些页面（设置搜索）第一次返回只收起搜索态、不退页面，
     * 只按一次就判死会把可自愈的情况做成人工。
     */
    private suspend fun tryRecoverPackageEscape(
        session: Session,
        task: TaskEntity,
        stepIndex: Int,
        snapshot: ObservationSnapshot,
        action: AgentAction.Do,
        decision: DecideResult,
        digest: String,
    ): StepResult? {
        if (session.packageEscapes >= MAX_PACKAGE_ESCAPES) return null
        val escaped = snapshot.packageName
        var actual = escaped
        var applied = false

        while (session.packageEscapes < MAX_PACKAGE_ESCAPES &&
            !PackageScope.isWithin(session.spec.packageName, actual)
        ) {
            val back = AgentAction.Do(
                kind = ActionKind.BACK,
                observationId = snapshot.observationId,
                targetFingerprint = null,
            )
            applied = runCatching { applier.apply(session.handle, back) }.getOrDefault(false)
            session.packageEscapes++
            actual = awaitForeground(session.handle, session.spec.packageName) ?: actual
        }
        val restored = PackageScope.isWithin(session.spec.packageName, actual)

        recordAudit(
            task.taskId, stepIndex + 1, snapshot, action, decision, verdict = null,
            outcome = if (restored) "PACKAGE_RECOVERED" else "PACKAGE_ESCAPED",
            digest = digest,
            result = if (restored) {
                "前台离开目标包（$escaped），按返回自愈成功（系统接受=$applied，已按 ${session.packageEscapes} 次），当前 $actual"
            } else {
                "前台离开目标包（$escaped），按返回 ${session.packageEscapes} 次后仍停在 $actual（系统接受=$applied）"
            },
            decisionOverride = if (restored) "ALLOW" else "NEED_HUMAN",
        )
        val advanced = persistProgress(
            task, stepIndex + 1, snapshot, applied = false, goal = session.plan.labelOf(session.cursor),
        )
        session.task = advanced
        val saved = persistSession(session)
        if (restored) return StepResult(stepIndex + 1, saved)

        val reason = "前台离开目标包（$escaped），按返回后仍未回到 ${session.spec.packageName}，已转人工"
        notifier.notifyResumable(task.taskId, reason)
        suspendForHuman(advanced, reason)
        return StepResult(stepIndex + 1, saved, StepKind.SUSPENDED, reason)
    }

    /**
     * 功能：轮询副屏当前前台包名
     * 参数：handle 建屏句柄；target 任务目标包名
     * 返回：最后一次读到的包名；读不到时为 null
     *
     * 用无障碍窗口而不是截图或树：按 displayId 取窗口是唯一权威来源，
     * 页面切换动画期间会短暂读不到，所以要轮询几次而不是看一眼就下结论。
     */
    private suspend fun awaitForeground(handle: DisplayHandle, target: String): String? {
        var last: String? = null
        repeat(PACKAGE_RECOVERY_ATTEMPTS) { attempt ->
            val packageName = runCatching { transport.activities.foregroundOn(handle)?.packageName }
                .getOrNull()
            if (packageName != null) {
                last = packageName
                if (PackageScope.isWithin(target, packageName)) return packageName
            }
            if (attempt < PACKAGE_RECOVERY_ATTEMPTS - 1) delay(PACKAGE_RECOVERY_GAP_MS)
        }
        return last
    }

    /**
     * 功能：判断后置条件是否有"入口页面原文"作依据
     * 参数：postcondition 后置条件片段；session 会话
     * 返回：任一片段在本步入口页面上出现过为 true
     *
     * 一个都没有 → 说明这条判据不是从当前界面读出来的，而是计划期猜的；
     * 空后置条件不走这里（那种情况本来就按弱证据推进）。
     */
    private fun groundedInEntryPage(postcondition: List<String>, session: Session): Boolean {
        if (session.stepEntryText.isEmpty()) return true
        return postcondition.any { session.stepEntryText.contains(normalizeText(it)) }
    }

    /**
     * 功能：把一帧页面上的文本拼成可做包含判断的字符串
     * 参数：snapshot 观察
     * 返回：去空白、小写后的文本
     */
    private fun pageTextOf(snapshot: ObservationSnapshot): String =
        normalizeText(snapshot.elements.joinToString(" ") { it.text.ifEmpty { it.contentDescription } })

    /**
     * 功能：文本归一化（只保留字母数字 + 小写）
     * 参数：text 原始文本
     * 返回：归一化结果
     */
    private fun normalizeText(text: String): String = normalizeForMatch(text)

    /**
     * 功能：把挂起动作落库并返回"本步挂起"
     * 参数：task 任务；stepIndex 挂在动作之后的步数；snapshot 观察；goal 子目标；reason 挂起原因
     * 返回：挂起型单步结果
     *
     * 被拦下的决策也要计入步数：它已经消耗一次模型调用，不计步会让确认-恢复循环不受预算约束。
     */
    private suspend fun suspendStep(
        task: TaskEntity,
        stepIndex: Int,
        snapshot: ObservationSnapshot,
        goal: String,
        reason: String,
    ): StepResult {
        val advanced = persistProgress(task, stepIndex, snapshot, applied = false, goal = goal)
        suspendForHuman(advanced, reason)
        return StepResult(stepIndex, advanced, StepKind.SUSPENDED, reason)
    }

    /**
     * 功能：为 L2A 动作消费一次性授权
     * 参数：task 任务；action 动作；snapshot 观察；intent 已落盘的写入意图
     * 返回：消费成功的授权标识；没有匹配授权或已被消费时为 null
     */
    private suspend fun consumeGrant(
        task: TaskEntity,
        action: AgentAction.Do,
        snapshot: ObservationSnapshot,
        intent: ActionIntentEntity?,
    ): String? {
        if (intent == null) return null
        val grant = grants.findMatch(task.taskId, action, snapshot)
        if (grant == null || !grants.consume(grant.grantId, intent.actionId)) {
            // 未注入即作废：删掉意图，避免它被当成"未决写入"
            intentDao.deleteById(intent.actionId)
            return null
        }
        return grant.grantId
    }

    /**
     * 功能：跑主循环，并在模型声明完成后进入验证
     * 参数：session 执行会话
     * 返回：无
     */
    private suspend fun drive(session: Session) {
        // 执行期间把默认输入法临时换成本应用的无键盘输入法：
        // 目标 App 的输入框获得焦点时，系统不会再在主屏弹出可见键盘
        // （真机实测百度输入法会盖在主屏上，比暂时没有键盘更干扰）。
        // 挂起、结束或转人工时由 suspendForHuman / retainOrRelease / destroyDisplay 还原。
        runCatching { transport.ime.select() }
            .onFailure { Log.d(AGENT_TAG, "接管默认输入法失败：${it.message}") }
        when (val result = executeLoop(session)) {
            is LoopResult.Finished -> verifyPhase(session, result.snapshot, result.summary)
            is LoopResult.Suspended, is LoopResult.Failed -> Unit
        }
    }

    /**
     * 功能：验证阶段——初验、独立复验与三态结论
     * 参数：session 执行会话；snapshot 完成时的观察；summary 模型给出的完成说明
     * 返回：无；状态落库
     */
    private suspend fun verifyPhase(session: Session, snapshot: ObservationSnapshot, summary: String) {
        val verifying = transition(session.task, TaskState.VERIFYING)
        val primary = verifier.check(session.spec, snapshot, "初验")
        // 初验没过也要走复验：契约字段是计划期盲猜的，"文案不符"不等于"没做成"。
        // 复验页可达时，由 reviewer 从该页原文里摘证据，再由本地逐字核对（两段式契约）。
        val reach = runCatching { resultReverifier.reach(session.spec, session.handle, snapshot) }
            .getOrElse { ReachResult(null, "复验路线异常：${it.message ?: it::class.java.simpleName}") }
        val adopted = if (!primary.passed && reach.snapshot != null) {
            runCatching { evidenceReviewer.extract(session.spec, reach.snapshot!!) }
                .getOrElse {
                    EvidenceOutcome(false, emptyList(), "证据核对异常：${it.message ?: it::class.java.simpleName}")
                }
        } else {
            null
        }
        // 没有注册复验路线时退一步：给页面一点时间稳定，再做一次同页重读。
        // 查看类任务只读前台包名——视频/直播页永远在动，等不到"一致观察"（真机 B 站实测），
        // 用整帧快照会把一次成功判成"未复验"。
        val viewTask = isViewTask(session.spec)
        // 查看类任务的初验判据是"前台包名"（在 Verifier 内决定），不是这里的字段级初验；
        // 字段级初验对这类任务本来就会因为计划期盲猜的文案失败，不能拿它当门槛。
        val needsReadback = reach.snapshot == null && (viewTask || primary.passed)
        Log.d(
            AGENT_TAG,
            "验证：viewTask=$viewTask 字段初验=${primary.passed} 复验页=${reach.snapshot != null} " +
                "输入=${session.spec.inputText.take(24)}",
        )
        val readbackPackage = if (needsReadback && viewTask) {
            delay(READBACK_DELAY_MS)
            val foreground = runCatching {
                transport.activities.foregroundOn(session.handle)?.packageName
            }.getOrNull()
            // 无障碍窗口根节点在播放页会短暂读不到（§5.6 第 3 条），此时退回 AMS：
            // 目标包在副屏上仍有任务，就说明页面没有被销毁。
            val packageName = foreground ?: runCatching {
                if (transport.activities.hasTaskOnDisplay(session.spec.packageName, session.handle)) {
                    session.spec.packageName
                } else {
                    null
                }
            }.getOrNull()
            Log.d(AGENT_TAG, "复验重读：前台=${foreground ?: "读不到"} 采用=${packageName ?: "无"}")
            packageName
        } else {
            null
        }
        val readback = if (needsReadback && !viewTask) {
            runCatching {
                delay(READBACK_DELAY_MS)
                observe(session.handle)
            }.getOrNull()
        } else {
            null
        }
        val result = verifier.verify(
            spec = session.spec,
            primary = snapshot,
            secondary = reach.snapshot,
            secondaryUnreachable = reach.note,
            adopted = adopted,
            readback = readback,
            viewTask = viewTask,
            readbackPackage = readbackPackage,
        )

        val target = when (result.outcome) {
            VerificationOutcome.DONE -> TaskState.DONE
            VerificationOutcome.DONE_READBACK -> TaskState.DONE_READBACK
            VerificationOutcome.COMPLETED_UNVERIFIED -> TaskState.COMPLETED_UNVERIFIED
            // 交付页任务的交付物就是页面本身：模型已停在目标页，字段文案对不上
            // （点赞只有图标、问答标点不同、美团购物车写「已加购商品」）不该把人叫回来处理，
            // 直接交付结果页通知，让用户自己看那一页。
            VerificationOutcome.MISMATCH -> if (isDeliveryPageTask(session.spec)) {
                TaskState.COMPLETED_UNVERIFIED
            } else {
                TaskState.NEED_HUMAN
            }
        }
        recordAudit(
            taskId = verifying.taskId,
            stepIndex = verifying.budgetStepsUsed + 1,
            snapshot = snapshot,
            action = null,
            decision = null,
            verdict = null,
            outcome = result.outcome.name,
            result = summary,
            evidence = (result.evidence + listOfNotNull(
                result.note,
                // 复验不可达的技术原因只留在审计里：任务行与通知用给人看的说法。
                reach.note,
                result.unreachableReason,
            )).joinToString("；"),
            decisionOverride = if (target == TaskState.NEED_HUMAN) "NEED_HUMAN" else "ALLOW",
        )
        val terminal = terminalReason(session.spec, target, summary, result)
        val verified = transition(verifying, target, terminal)
        session.task = verified

        // 查看类/购物类任务的交付物都是"agent 停下的那一页"：视频页、对话页、购物车、
        // 待支付页。副屏先留着，等用户点结果通知时再把整个任务搬到主屏。
        val keepResult = target != TaskState.NEED_HUMAN && isDeliveryPageTask(session.spec)
        if (keepResult) {
            resultViews.markPending(verified.taskId, session.spec.packageName)
            scheduleResultRelease(verified.taskId)
        }

        // 任务结束后按结局决定是否把本轮轨迹写成配方（§2.5.5）。
        // 只有 DONE 才写；其它结局写 MEMORY_SKIPPED，闸门要可见，不能静默。
        recordMemoryOutcome(
            verifying,
            session.spec,
            result.outcome,
            session.entrySnapshot,
            overlaySegments = session.overlaySeen,
        )

        if (target == TaskState.NEED_HUMAN) {
            notifier.notifyResumable(verifying.taskId, terminal)
        } else {
            // 走到终态就发一条结果报告：原因、步数与是否经过复验（§4.1 完成报告）
            notifier.notifyResult(
                taskId = verifying.taskId,
                state = target,
                reason = terminal,
                steps = verified.budgetStepsUsed,
                openResult = keepResult,
            )
        }
    }

    /**
     * 功能：把验证结论翻成用户能执行的一句话
     * 参数：spec 任务约束；target 终态；summary 完成说明；result 验证结论
     * 返回：写进任务行与结果通知的说明
     *
     * 交付页任务的 MISMATCH 已按"结果页交付"处理（不转人工），但原始说明是
     * 「初验未通过：订单已提交（期望包含…）」——用户读到的是失败。这里改成
     * "停在哪一页、接下来做什么"；字段不符的细节留在审计证据里。
     */
    private fun terminalReason(
        spec: TaskSpec,
        target: TaskState,
        summary: String,
        result: VerificationResult,
    ): String {
        if (target == TaskState.COMPLETED_UNVERIFIED &&
            isDeliveryPageTask(spec) &&
            result.outcome == VerificationOutcome.MISMATCH
        ) {
            val handover = isOrderingTask(spec) && summary.contains("待支付")
            return if (handover) summary else "已停在目标页面；点击结果通知可直接打开查看"
        }
        return result.unreachableReason ?: summary
    }

    /**
     * 功能：主动释放"保留中的结果页"
     * 参数：无
     * 返回：释放了返回 true
     *
     * 队列因为一条已完成任务占着副屏而停住时调用：宁可让用户稍后从通知重新打开 App，
     * 也不要把后续任务一直堵在 CREATED。
     *
     * 判断依据是"当前副屏归属谁 + 那个任务是不是终态/有保留结果"，而不是单独记一个
     * taskId：用户点过结果通知后，结果页已经被搬到主屏、副屏也拆了，单独记录的 id 会过期，
     * 之后队列明明能释放却一直返回 false（真机上就是这样把后续任务堵住的）。
     */
    suspend fun releaseRetainedResults(): Boolean {
        val handle = activeDisplay.current() ?: return false
        val owner = activeDisplay.owner()
        if (owner == null) {
            Log.d(AGENT_TAG, "释放保留页跳过：副屏没有归属任务")
            return false
        }
        val state = runCatching { taskDao.findById(owner)?.state }.getOrNull()
        val taskState = state?.let { runCatching { TaskState.valueOf(it) }.getOrNull() }
        if (taskState == TaskState.NEED_HUMAN) {
            Log.d(AGENT_TAG, "释放保留页跳过：副屏归人工接管任务 $owner")
            return false
        }
        // 只有终态任务的屏可以收；结果登记已清（例如用户点过通知）也照样收，
        // 避免一块遗留的终态副屏把队列永久堵死。运行中的任务绝不在这里拆屏。
        val terminal = taskState?.isTerminal == true
        if (!terminal && !resultViews.isPending(owner)) {
            Log.d(AGENT_TAG, "释放保留页跳过：副屏归运行中的任务 $owner state=$state")
            return false
        }
        destroyDisplay(handle)
        resultViews.clear(owner)
        Log.d(
            AGENT_TAG,
            "队列需要副屏，主动释放保留的结果页 task=$owner（终态=$terminal state=$state）",
        )
        return true
    }

    /**
     * 功能：安排结果页的保留超时
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 副屏被结果页占住时，队列不能取下一个任务（单屏约束）。用户迟迟不点通知时
     * 必须自动释放，否则一条没被查看的结果会把整条队列永久堵死。
     */
    private fun scheduleResultRelease(taskId: String) {
        resultReleaseScope.launch {
            delay(RESULT_RETENTION_MS)
            if (!resultViews.isPending(taskId)) return@launch
            val handle = activeDisplay.current()
            if (handle != null && activeDisplay.isOwnedBy(taskId)) {
                destroyDisplay(handle)
            }
            resultViews.clear(taskId)
            Log.d(AGENT_TAG, "结果页保留超时，已释放副屏 task=$taskId")
        }
    }

    /**
     * 功能：判断任务是否是"把某个页面交给用户看"
     * 参数：spec 任务约束
     * 返回：是查看类任务为 true
     *
     * 这类任务的完成物就是目标页面本身：Agent 已经停在目标页后，字段文案对不上
     * 不应该转人工处理，而应直接交付结果页通知，让用户点开自己看。
     */
    private fun isViewTask(spec: TaskSpec): Boolean {
        val text = spec.inputText.lowercase()
        return VIEW_TASK_HINTS.any { text.contains(it) }
    }

    /**
     * 功能：判断任务的交付物是不是"某个页面"
     * 参数：spec 任务约束
     * 返回：查看类或购物类为 true
     *
     * 这两类的共同点：用户要的是 agent 停下的那一页，而不是"任务完成"这个结论。
     * 查看类（B 站视频、DeepSeek 对话）停在内容页；购物类（美团外卖、淘宝）停在购物车
     * 或待支付页，剩下的动作必须由用户自己完成（支付是 L2B）。
     *
     * 因此它们共用同一条交付口径：完成契约的文案对不上时按"已完成未复验"交付结果页，
     * 而不是转人工。美团实测：购物车面板写的是「已加购商品」，计划期猜的「加入购物车」
     * 永远对不上，按旧口径会把一次成功的加购判成失败并逼用户重跑。
     *
     * 代价要说清：agent 若没真正加购成功，用户点通知看到的是"未复验"的页面，需要自己判断。
     * 这个代价比"把成功判成失败"小——页面就在眼前，用户一眼能看出来。
     */
    private fun isDeliveryPageTask(spec: TaskSpec): Boolean {
        if (isViewTask(spec)) return true
        val text = spec.inputText.lowercase()
        return DELIVERY_PAGE_HINTS.any { text.contains(it) }
    }

    /**
     * 功能：把任务终局的记忆写入结论落成审计
     * 参数：task 已迁到 VERIFYING 的任务；spec 任务约束；outcome 验证结论；
     *       entrySnapshot 任务入口页观察；overlaySegments 本轮轨迹是否含一次性遮挡
     * 返回：无
     *
     * 轨迹不足以构成配方时什么都不写：噪声审计会让"为什么没长记忆"更难查，而不是更好查。
     * 写成 MEMORY_SKIPPED 而不是静默返回，是因为当前主路径到不了 DONE——
     * 不写出来，下一个人会以为自动学习根本没实现，而不是"实现了但闸门没开"。
     */
    private suspend fun recordMemoryOutcome(
        task: TaskEntity,
        spec: TaskSpec,
        outcome: VerificationOutcome,
        entrySnapshot: ObservationSnapshot,
        overlaySegments: Boolean = false,
    ) {
        val rows = runCatching { auditDao.actionsOf(task.taskId) }.getOrDefault(emptyList())
        val write = runCatching {
            memoryWriter.observeResult(task, spec, outcome, rows, entrySnapshot, overlaySegments)
        }.getOrNull() ?: return
        val (label, text) = when (write) {
            is MemoryWriteResult.Written -> "MEMORY_WRITTEN" to
                "「${write.title}」${write.detail}；累计成功 ${write.successCount} 次，状态 ${write.status.name}"
            is MemoryWriteResult.Skipped -> "MEMORY_SKIPPED" to
                "重建了 ${write.stepCount} 步（key=${write.stableKey.take(STABLE_KEY_HINT)}）但未写入：${write.reason}"
        }
        audit.recordSystemEvent(task = task, outcome = label, result = text, decision = "ALLOW")
    }

    /**
     * 功能：把出问题的 App 开到主屏，交给用户处理后自动交还
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 这是**唯一**允许把目标 App 拉到主屏的入口，且只由用户点通知触发。
     * 交还条件：目标 App 曾在主屏前台出现过，随后离开前台（用户切后台）。
     * 没出现过就一直等（用户可能还没点开）；超过 5 分钟停止监听，任务继续留在人工态。
     */
    suspend fun handOverToMain(taskId: String) {
        val task = taskDao.findById(taskId) ?: return
        if (runCatching { TaskState.valueOf(task.state) }.getOrNull() != TaskState.NEED_HUMAN) return
        val spec = runCatching { TaskSpecCodec.decode(task.specJson) }.getOrNull() ?: return
        if (spec.packageName.isEmpty()) return

        // 优先把副屏上那个任务整体搬到主屏：用户要处理的往往是 agent 停下的页面
        // （购物车、待支付、验证码页）。按包名重新拉起只会回到 App 首页，
        // 购物车与订单状态全丢，用户还得自己再走一遍。
        val handle = activeDisplay.current()?.takeIf { activeDisplay.isOwnedBy(taskId) }
        val moved = handle?.let { moveRetainedTaskToMain(taskId, spec, it) } ?: false
        val opened = moved || runCatching { transport.activities.launchOnMain(spec.packageName) }.isSuccess
        audit.recordSystemEvent(
            task = task,
            outcome = "HANDOFF_MAIN_OPENED",
            result = when {
                moved -> "已把 agent 停下的页面搬到主屏（${spec.packageName}）；处理完把它切到后台，agent 会自动继续"
                opened -> "已在主屏打开 ${spec.packageName}（原页面未能保留，从首页开始）；处理完切到后台即自动继续"
                else -> "在主屏打开 ${spec.packageName} 失败，可改用「我来处理」在接管页处理"
            },
            decision = "NEED_HUMAN",
        )
        if (!opened) return

        taskDao.upsert(
            task.copy(
                suspendReason = "已交给你在主屏处理；把它切到后台后 agent 会自动继续",
                updatedAt = System.currentTimeMillis(),
            ),
        )
        watchMainHandback(taskId, spec.packageName)
    }

    /**
     * 功能：把任务所在的副屏任务搬到主屏并置顶
     * 参数：taskId 任务标识；spec 任务约束；handle 当前副屏句柄
     * 返回：搬成功并置顶为 true
     *
     * 搬完就拆副屏：屏幕上的内容已经在主屏了，留着只会占住单屏约束。
     * 顺序必须是"搬 → 置顶 → 拆屏"，先拆屏会把刚搬过去的任务挤回后台。
     */
    private suspend fun moveRetainedTaskToMain(
        taskId: String,
        spec: TaskSpec,
        handle: DisplayHandle,
    ): Boolean {
        val movedTaskId = runCatching {
            transport.activities.moveTaskToMain(spec.packageName, handle)
        }.getOrNull()
        val brought = movedTaskId != null &&
            runCatching { transport.activities.bringTaskToFront(movedTaskId) }.getOrDefault(false)
        Log.d(AGENT_TAG, "任务搬到主屏 task=$taskId system=$movedTaskId brought=$brought")
        destroyDisplay(handle)
        return brought
    }

    /**
     * 功能：监听"用户把主屏上的目标 App 切到后台"，然后恢复任务
     * 参数：taskId 任务标识；target 目标包名
     * 返回：无
     *
     * 必须"先在主屏见过目标 App、再看到它离开"才算交还：否则用户还没点开时
     * 主屏本来就不是目标 App，会立刻被误判成交还并自动跑起来。
     */
    private fun watchMainHandback(taskId: String, target: String) {
        watchJob?.cancel()
        watchJob = handoffScope.launch {
            var seenForeground = false
            repeat(HANDOFF_WATCH_TICKS) {
                delay(HANDOFF_WATCH_GAP_MS)
                val current = runCatching { taskDao.findById(taskId) }.getOrNull() ?: return@launch
                if (runCatching { TaskState.valueOf(current.state) }.getOrNull() != TaskState.NEED_HUMAN) {
                    return@launch
                }
                val pkg = MimirAccessibilityService.instance?.mainScreenForeground()?.packageName.orEmpty()
                if (PackageScope.isTargetOrChild(target, pkg)) {
                    seenForeground = true
                    return@repeat
                }
                if (seenForeground) {
                    // 目标已离开主屏前台：用户处理完了，交还
                    runCatching { resume(taskId) }
                    return@launch
                }
            }
        }
    }

    /**
     * 功能：把"未复验"的终态标注成用户已核对
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 只改终态与审计，**不写记忆、不改证据**：机器没能核对出来的结果，人看一眼只能说明
     * "我看见结果了"，不能证明"机器做对了"。记忆激活仍只认 DONE 与 DONE_READBACK。
     */
    suspend fun markVerifiedByHuman(taskId: String) {
        val task = taskDao.findById(taskId) ?: return
        val current = runCatching { TaskState.valueOf(task.state) }.getOrNull() ?: return
        if (current != TaskState.COMPLETED_UNVERIFIED) return
        val verified = runCatching { transition(task, TaskState.VERIFIED_BY_HUMAN) }.getOrNull() ?: return
        audit.recordSystemEvent(
            task = verified,
            outcome = "HUMAN_VERIFIED",
            result = "用户在结果通知上确认已核对；不改写证据，不计入记忆激活",
            decision = "ALLOW",
        )
    }

    /**
     * 功能：检查预算，超出则给出失败原因
     * 参数：task 当前任务；spec 任务约束；stepIndex 已执行步数
     * 返回：失败原因；预算充足时为 null
     */
    private fun budgetProblem(task: TaskEntity, spec: TaskSpec, stepIndex: Int): String? = when {
        stepIndex >= spec.budget.maxSteps -> "步数预算耗尽（${spec.budget.maxSteps} 步）"
        task.budgetModelCalls >= spec.budget.maxModelCalls ->
            "模型调用预算耗尽（${spec.budget.maxModelCalls} 次）"
        System.currentTimeMillis() - task.createdAt >= spec.budget.maxDurationMs ->
            "任务时长超限（${spec.budget.maxDurationMs / 1000} 秒）"
        else -> null
    }

    /**
     * 功能：把任务推进到 FAILED 并记录原因
     * 参数：session 执行会话；reason 失败原因
     * 返回：循环结果
     */
    private suspend fun failTerminal(session: Session, reason: String): LoopResult.Failed {
        fail(session.task, reason)
        return LoopResult.Failed(reason)
    }

    /**
     * 功能：把任务置为 FAILED
     * 参数：task 当前任务；reason 失败原因
     * 返回：无
     */
    private suspend fun fail(task: TaskEntity, reason: String) {
        val current = runCatching { TaskState.valueOf(task.state) }.getOrDefault(TaskState.FAILED)
        if (current.isTerminal || TaskState.FAILED !in allowedTransitions(current)) return
        taskDao.upsert(
            task.copy(
                state = TaskState.FAILED.name,
                suspendReason = reason,
                updatedAt = System.currentTimeMillis(),
                finishedAt = System.currentTimeMillis(),
            ),
        )
        notifier.notifyResult(task.taskId, TaskState.FAILED, reason, task.budgetStepsUsed)
    }

    /**
     * 功能：把任务挂起为 NEED_HUMAN
     * 参数：task 当前任务；reason 挂起原因
     * 返回：循环结果
     */
    private suspend fun suspendForHuman(task: TaskEntity, reason: String): LoopResult {
        val current = TaskState.valueOf(task.state)
        if (TaskState.NEED_HUMAN !in allowedTransitions(current)) {
            fail(task, reason)
            return LoopResult.Failed(reason)
        }
        // 转人工后用户要在接管页/主屏自己操作，必须先还回可用键盘
        runCatching { transport.ime.restore() }
        taskDao.upsert(
            task.copy(
                state = TaskState.NEED_HUMAN.name,
                suspendReason = reason,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return LoopResult.Suspended(reason)
    }

    /**
     * 功能：检测主屏是否正打开任务目标 App
     * 参数：spec 任务约束
     * 返回：冲突说明；无冲突或读不到主屏前台应用时为 null
     *
     * 只读主屏前台包名、不读任何内容（§4.6）：同一个 App 共享购物车、草稿与登录态，
     * 两边同时操作会互相覆盖；因此命中即暂停，而不是只在外卖类任务启动前检查一次。
     * 子包也算命中：美团主 App 与美团外卖共用账号与购物车。
     */
    private fun mainScreenConflict(spec: TaskSpec): String? {
        if (spec.packageName.isEmpty()) return null
        val foreground = MimirAccessibilityService.instance?.mainScreenForeground() ?: return null
        // 模拟器的 FallbackHome 属于 com.android.settings 包，但它只是桌面；部分 ROM 的
        // 桌面窗口 title 直接为空。把它当成"用户正在设置里操作"会让每个设置任务在启动前
        // 就被误判成冲突，所以拿不到 Activity 名时不拦（宁可少拦，也不误杀整类任务）。
        val activity = foreground.activityName.orEmpty()
        if (activity.isEmpty() || activity.contains("FallbackHome", ignoreCase = true)) return null
        val packageName = foreground.packageName ?: return null
        if (!PackageScope.isTargetOrChild(spec.packageName, packageName)) return null
        return "主屏正在使用 $packageName，与任务共享状态，任务已暂停"
    }

    /**
     * 功能：把跑到主屏的目标应用恢复到副屏
     * 参数：handle 建屏句柄；spec 任务约束
     * 返回：目标应用重新出现在副屏为 true
     *
     * 副屏上的任务被 reparent 走后，副屏上连 root task 都不剩（真机实测
     * `getAllRootTaskInfosOnDisplay` 返回空），`moveTaskToRootTask` 没有落点可用。
     * 因此恢复 = 在副屏重新拉一个干净实例 + 清掉主屏那份；顺序不能反，
     * 否则会把刚拉起来的实例一起清掉。
     */
    private suspend fun recoverToDisplay(handle: DisplayHandle, spec: TaskSpec): Boolean {
        val launched = runCatching { transport.activities.launch(spec.packageName, handle) }.isSuccess
        transport.activities.removeTasksOutside(spec.packageName, handle)
        if (!launched) return false
        return awaitOnDisplay(handle, spec)
    }

    /**
     * 功能：判断最近几步是否卡在同一个无效动作上
     * 参数：task 当前任务
     * 返回：卡住说明；未卡住为 null
     *
     * 条件是"同一个动作 + 同一个目标 + 每一步都没让界面变化"。只看重复次数会误伤
     * 连点三次「+」这类合法操作——那些每一步的后置条件都是 CONFIRMED。
     */
    private suspend fun stuckReason(task: TaskEntity): String? {
        val recent = auditDao.recentSteps(task.taskId, STUCK_WINDOW)
        if (recent.size < STUCK_WINDOW) return null
        val head = recent.first()
        if (head.actionKind.isEmpty()) return null

        // 坐标点击没有目标文本，不能按"同目标"分组；但只要连续几次都是同一种动作、
        // 且一次都没 CONFIRMED，同样是卡住了（微信里就是反复点空输入框）。
        val sameAction = recent.all { it.actionKind == head.actionKind }
        val sameTarget = if (head.targetText.isNullOrEmpty()) {
            true
        } else {
            recent.all { it.targetText == head.targetText }
        }
        if (!sameAction || !sameTarget) return null
        if (recent.any { it.outcome == EffectOutcome.CONFIRMED.name }) return null

        val target = head.targetText?.takeIf { it.isNotEmpty() } ?: "坐标点击"
        return "连续 $STUCK_WINDOW 次「${head.actionKind} $target」都没有改变界面，疑似卡在同一页"
    }

    /**
     * 功能：校验观察是否来自本任务的副屏
     * 参数：session 执行会话；snapshot 当前观察
     * 返回：问题说明；一致时为 null
     */
    private fun displayProblem(session: Session, snapshot: ObservationSnapshot): String? = when {
        snapshot.displayId != session.handle.displayId ->
            "观察来自 display ${snapshot.displayId}，与任务副屏 ${session.handle.displayId} 不一致"
        snapshot.displayGeneration != session.handle.generation ->
            "副屏已重建（代次 ${snapshot.displayGeneration} ≠ ${session.handle.generation}）"
        else -> null
    }

    /**
     * 功能：纯视觉下拦截落在 OCR 文字之外的坐标点击
     * 参数：action 动作；snapshot 当前观察
     * 返回：问题说明；不是盲点或落点在文字元素内时为 null
     *
     * OCR 只能给出文字位置，图标和空白区没有语义。真机上微信这类控件树封锁的 App，
     * 模型会反复猜"搜索图标在哪"，三次坐标点击都无效后整任务判 STUCK。
     * 落点不在任何 OCR 文字元素上时软拒绝，让模型改点文字入口或换 wait/back/replan。
     */
    private fun blindCoordinateTap(action: AgentAction.Do, snapshot: ObservationSnapshot): String? {
        if (action.kind != ActionKind.TAP) return null
        if (snapshot.mode != ObservationMode.VISION_ONLY) return null
        val normX = action.normX ?: return null
        val normY = action.normY ?: return null
        if (snapshot.elements.isEmpty()) return null
        val px = normX * snapshot.nativeWidth / NORM_SCALE
        val py = normY * snapshot.nativeHeight / NORM_SCALE
        if (snapshot.elements.any { containsPoint(it.bounds, px, py) }) return null

        val searchHints = snapshot.elements
            .filter { it.text.contains("搜索") || it.contentDescription.contains("搜索") }
            .take(2)
            .joinToString("；") { "#${it.index}「${labelOf(it)}」" }
        val hint = if (searchHints.isEmpty()) "" else "；页面上的搜索入口是 $searchHints，请点它的编号"
        return "纯视觉下坐标(x=$normX,y=$normY)没有落在任何可识别文字上，图标/空白区的盲点无效$hint。" +
            "请改用元素编号；如果目标是无文字图标，先找它旁边的文字入口，或输出 back/replan 换路径"
    }

    /**
     * 功能：校验 input 动作是否落在可输入控件上
     * 参数：action 动作；snapshot 当前观察
     * 返回：问题说明；目标可用、无法判断或不是 TEXT 时为 null
     *
     * 只在控件树可读时判断。每个可输入节点连同包住它的最近可点击容器算作一个"输入区域"：
     * 美团实测搜索框的真实 EditText 常被外层 LinearLayout 遮住，只按 editable 节点判定会误杀；
     * 反过来只按 bounds 相交又太宽松——列表图标与搜索栏相差几个像素也会被当成输入框。
     * 纯视觉没有 editable 标记、页面也找不到输入提示时一律放行，交给原有的执行层兜底。
     */
    /**
     * 功能：连续第 2 次把文本打到非输入控件时，若整页只有一个可输入节点就直接改投
     * 参数：task 当前任务；session 会话；stepIndex 当前步；snapshot 当前观察；
     *       action 模型动作；decision 决策结果
     * 返回：实际要执行的动作；不需要改投时原样返回
     *
     * 美团首页的活动浮层会盖住搜索框，模型连续 7 次把输入目标选成浮层，
     * 本地只回显「#3 才是搜索框」，两次重规划仍纠正不过来，白烧 8 步。
     * 第一次仍交给模型自己改（保留它的判断）；第二次只有在"整页唯一可输入节点"时
     * 才替代目标——多输入框页面（登录、表单）行为完全不变。
     */
    private suspend fun coerceInputTarget(
        task: TaskEntity,
        session: Session,
        stepIndex: Int,
        snapshot: ObservationSnapshot,
        action: AgentAction.Do,
        decision: DecideResult,
    ): AgentAction.Do {
        if (action.kind != ActionKind.TEXT) return action
        if (session.inputTargetRejections < 1) return action
        if (inputTargetProblem(action, snapshot) == null) return action
        val editables = snapshot.elements.filter { it.editable }
        if (editables.size != 1) return action

        val target = editables.first()
        val original = snapshot.elements.firstOrNull { it.index == action.elementId }
            ?: elementAtPoint(snapshot, action.normX, action.normY)
        val reason = "输入目标「${original?.let { labelOf(it) } ?: "坐标"}」不在输入框内，" +
            "整页只有一个可输入节点 #${target.index}「${labelOf(target)}」，已自动改投"
        recordAudit(
            task.taskId, stepIndex + 1, snapshot, action, decision, verdict = null,
            outcome = "COERCED_TARGET", result = reason,
        )
        Log.d(AGENT_TAG, "input 目标改投 #${target.index} ${labelOf(target)}（原目标 ${original?.index}）")
        return action.copy(
            elementId = target.index,
            normX = null,
            normY = null,
            targetFingerprint = ActionProtocol.fingerprintOf(target),
        )
    }

    private fun invalidInputTarget(action: AgentAction.Do, snapshot: ObservationSnapshot): String? {
        val reason = inputTargetProblem(action, snapshot)
        if (action.kind == ActionKind.TEXT) {
            Log.d(
                AGENT_TAG,
                "input 目标校验 ${if (reason == null) "放行" else "拒绝：$reason"} " +
                    "可输入节点=${snapshot.elements.count { it.editable }} 模式=${snapshot.mode}",
            )
        }
        return reason
    }

    /** 校验主体；返回值语义与 [invalidInputTarget] 相同。 */
    private fun inputTargetProblem(action: AgentAction.Do, snapshot: ObservationSnapshot): String? {
        if (action.kind != ActionKind.TEXT) return null
        if (snapshot.mode == ObservationMode.VISION_ONLY) return null

        val areas = snapshot.elements.filter { it.editable }.map { editable ->
            wrapperOf(editable, snapshot) ?: editable
        }
        val px = action.normX?.let { it * snapshot.nativeWidth / NORM_SCALE }
        val py = action.normY?.let { it * snapshot.nativeHeight / NORM_SCALE }
        val pointInside = px != null && py != null && areas.any { containsPoint(it.bounds, px, py) }
        if (pointInside) return null

        val target = snapshot.elements.firstOrNull { it.index == action.elementId }
            ?: elementAtPoint(snapshot, action.normX, action.normY)
            ?: return null
        if (target.editable) return null
        val center = target.bounds.center
        if (areas.any { containsPoint(it.bounds, center.x, center.y) }) return null

        // 页面一个可输入节点都没有时，只有存在「请输入…」这类搜索框提示才做校验；
        // 否则可能只是自绘输入框，不能凭控件树里没有 editable 就判成打错目标。
        if (areas.isEmpty()) {
            val hint = snapshot.elements.firstOrNull { isInputHint(it) } ?: return null
            val area = wrapperOf(hint, snapshot) ?: hint
            if (containsPoint(area.bounds, center.x, center.y)) return null
            val hintLabel = hint.text.ifEmpty { hint.contentDescription }
            return "输入目标「${labelOf(target)}」不在搜索框内。搜索框是 " +
                "#${hint.index}「$hintLabel」，请点它所在的输入框或给它的坐标；" +
                "若搜索框被活动浮层挡住，先关闭/返回再输入"
        }

        val label = labelOf(target)
        val hintText = if (action.elementId != null) "改选标有「可输入」的元素" else "给输入框范围内的坐标"
        return "输入目标「$label」不是输入框（无「可输入」标记）。请$hintText，或先等待页面加载完成再输入"
    }

    /** 元素的展示文本，空串时给一个占位说明。 */
    private fun labelOf(element: com.neptine.mimir.core.contract.UiElement): String =
        element.text.ifEmpty { element.contentDescription }.ifEmpty { "(无文本)" }

    /**
     * 功能：生成一次输入动作的去重键
     * 参数：action 输入动作
     * 返回：`正文|目标锚点`；缺正文时为 null
     *
     * 目标锚点优先用元素编号，其次用坐标归到 40px 网格。真机上模型重复输入时
     * 落点会差几个像素，逐像素比较认不出重复，网格化后仍能命中同一个输入框。
     */
    private fun textWriteKey(action: AgentAction.Do): String? {
        val text = action.text.orEmpty()
        if (text.isEmpty()) return null
        val anchor = action.elementId?.let { "e:$it" }
            ?: action.targetFingerprint?.bounds?.let { bounds ->
                "b:${bounds.center.x / TEXT_TARGET_GRID}:${bounds.center.y / TEXT_TARGET_GRID}"
            }
            ?: action.normX?.let { x ->
                action.normY?.let { y -> "n:${x / TEXT_TARGET_GRID}:${y / TEXT_TARGET_GRID}" }
            }
            ?: "none"
        return "$text|$anchor"
    }

    /**
     * 功能：判断这一步是不是"已到待支付页、只剩付款"
     * 参数：spec 任务约束；action 动作；snapshot 当前观察
     * 返回：交接说明；不是该场景时为 null
     *
     * 只在用户明确要下单/点餐、但没要求"代付"的任务里生效：
     * 用「立即支付」「收银台」这类只剩付款的按钮收尾，而不是把它们当成失败挂在 NEED_HUMAN。
     */
    private fun paymentHandoverReason(
        spec: TaskSpec,
        action: AgentAction.Do,
        snapshot: ObservationSnapshot,
    ): String? {
        if (action.kind != ActionKind.TAP) return null
        val target = targetTextOf(action, snapshot)
        val keyword = guard.paymentOnlyKeyword(target) ?: return null
        if (!isOrderingTask(spec)) return null
        return "已到待支付页（命中「$keyword」），地址与备注已按任务准备好；" +
            "按约定不代付，请你本人点一下「立即支付」完成付款"
    }

    /**
     * 功能：判断任务意图是"下单/点餐/加购"而不是"代付"
     * 参数：spec 任务约束
     * 返回：是下单类任务为 true
     *
     * 用户原话里出现支付/付款，或任务类型本身是资金类，就不走交接分支，仍按 L2B 交人。
     */
    private fun isOrderingTask(spec: TaskSpec): Boolean {
        val text = spec.inputText.lowercase()
        val type = spec.taskType.uppercase()
        if (listOf("PAY", "TRANSFER", "RECHARGE", "PAYMENT").any { type.contains(it) }) return false
        // 「停在支付页 / 到待支付页」描述的是终点，不是要求代付：
        // 英文任务 "stop at the payment page" 里含 payment，按原规则会被判成资金任务，
        // 到「立即支付」时直接硬阻断，而不是按待支付页交接（真机实测）。
        val destinationOnly = PAYMENT_DESTINATION_HINTS.any { text.contains(it) }
        if (!destinationOnly &&
            listOf("支付", "付款", "付钱", "结账", "pay", "payment").any { text.contains(it) }
        ) {
            return false
        }
        val orderingType = listOf("ORDER", "SHOPPING", "PURCHASE", "DELIVERY", "CART").any { type.contains(it) }
        val orderingText = listOf("点", "下单", "购买", "买", "外卖", "order", "buy", "cart").any { text.contains(it) }
        return orderingType || orderingText
    }

    /** 包住目标节点的最近可点击容器；没有时返回 null。 */
    private fun wrapperOf(
        inner: com.neptine.mimir.core.contract.UiElement,
        snapshot: ObservationSnapshot,
    ): com.neptine.mimir.core.contract.UiElement? {
        val center = inner.bounds.center
        // 只收高度接近输入框本身的容器：美团首页把搜索栏和分类图标包在同一个可点击大卡片里，
        // 不限制高度会让「团购」图标也被当成输入区域，校验形同虚设。
        val maxHeight = inner.bounds.height + WRAPPER_HEIGHT_MARGIN_PX
        return snapshot.elements
            .filter {
                it.clickable && it.index != inner.index &&
                    containsPoint(it.bounds, center.x, center.y) &&
                    it.bounds.height <= maxHeight
            }
            .minByOrNull { it.bounds.width.toLong() * it.bounds.height.toLong() }
    }

    /** 点是否落在矩形内（含边界）。 */
    private fun containsPoint(bounds: Bounds, x: Int, y: Int): Boolean =
        x in bounds.left..bounds.right && y in bounds.top..bounds.bottom

    /** 文案是否像输入框的占位提示。 */
    private fun isInputHint(element: com.neptine.mimir.core.contract.UiElement): Boolean {
        val text = element.text.ifEmpty { element.contentDescription }
        return text.contains("请输入") || text.contains("搜索框") || text.contains("Search", ignoreCase = true)
    }

    /**
     * 功能：执行前复验——判断决策依据的目标元素是否还在原处
     * 参数：handle 建屏句柄；action 动作
     * 返回：目标仍在原位置为 true
     *
     * 只验目标元素，不比整树哈希：真实 App 的轮播、倒计时、红包动画会让整树哈希每一帧
     * 都不同，按整树比对等于把每个动作都判成"页面已变"，一步白烧一次模型调用。
     * 没有目标元素的动作（滑动 / 返回 / Home / 等待）不依赖元素编号，直接放行。
     */
    private suspend fun targetStillThere(
        handle: DisplayHandle,
        action: AgentAction.Do,
        snapshot: ObservationSnapshot,
    ): Boolean {
        val fingerprint = action.targetFingerprint ?: return true
        // 元素来自 OCR 时不能用控件树复核——微信这类 App 的树本来就是空的，
        // 拿树去找目标只会永远判"目标不见了"，视觉路径一次也走不到注入。
        // 索引退化时也一样：树读不出来就用同一套 OCR 元素复核。
        val elements = if (snapshot.mode == ObservationMode.VISION_ONLY) {
            runCatching { assembler.visionSnapshot(handle, snapshot.packageName) }
                .getOrNull()?.elements ?: return false
        } else {
            runCatching { trees.capture(handle) }.getOrNull()?.elements ?: return false
        }
        val target = elements.firstOrNull { ElementDiff.matches(fingerprint, it) } ?: return false
        // 纯视觉指纹没有原生像素范围，只能要求"还找得到同一个元素"
        val before = fingerprint.bounds ?: return true
        return movedWithinTolerance(before, target.bounds)
    }

    /**
     * 功能：判断两个矩形是否在容差内重合
     * 参数：before 决策时的位置；after 复验时的位置
     * 返回：中心点偏移不超过容差为 true
     *
     * 给容差是必要的：阴影、字重、状态栏高度变化会让同一元素偏移几个像素；
     * 但页面真的换了位置、或列表滚动了，偏移会远超容差。
     */
    private fun movedWithinTolerance(before: Bounds, after: Bounds): Boolean {
        val dx = (before.left + before.right) / 2 - (after.left + after.right) / 2
        val dy = (before.top + before.bottom) / 2 - (after.top + after.bottom) / 2
        return dx * dx + dy * dy <= TARGET_TOLERANCE_PX * TARGET_TOLERANCE_PX
    }

    /**
     * 功能：取一帧一致观察，失败时重采
     * 参数：handle 建屏句柄
     * 返回：一致快照
     *
     * 弹窗切换、窗口重建会短暂拿不到根节点，这类失败必须重采而不是直接把任务判死；
     * 三次都失败才抛最后一个错误。
     */
    private suspend fun observe(handle: DisplayHandle): ObservationSnapshot {
        var lastError: Throwable? = null
        repeat(OBSERVE_ATTEMPTS) { attempt ->
            runCatching { assembler.snapshot(handle) }
                .onSuccess { return it }
                .onFailure { lastError = it }
            if (attempt < OBSERVE_ATTEMPTS - 1) delay(OBSERVE_RETRY_MS)
        }
        throw IllegalStateException("多次重采仍拿不到一致观察：${lastError?.message}")
    }

    /**
     * 功能：校验目标应用是否真的落在副屏上
     * 参数：handle 建屏句柄；spec 任务约束
     * 返回：失败原因；已在副屏（或屏上是系统权限弹窗）时为 null
     *
     * 设计文档 §2.2 要求"启动后必须重新读取实际 displayId，不符即中止"，旧实现只把
     * 启动意图发出去就干等三秒。真机上 launchDisplayId 可能不被 ROM 采纳（或目标 App
     * 已有主屏任务被复用），表现是主屏跳出目标 App、副屏一个应用窗口都没有，
     * 而任务只报"拿不到一致观察"，看不出真正原因。
     */
    private suspend fun launchFailure(handle: DisplayHandle, spec: TaskSpec): String? {
        // 无障碍没连上时，副屏窗口一律读不出来；这时说"应用没出现"会把用户引到错误方向
        if (MimirAccessibilityService.instance == null) {
            return "无障碍服务未连接，无法判断目标应用是否落在副屏上。请重新开启 Mimir 的无障碍服务"
        }

        if (awaitOnDisplay(handle, spec)) return null

        // App 可能在拉起瞬间就把任务 reparent 到主屏（美团实测会这样），先恢复一次再判失败
        if (recoverToDisplay(handle, spec)) {
            Log.d(AGENT_TAG, "目标应用启动后跑到主屏，已在副屏重新拉起 ${handle.displayId}")
            return null
        }

        val mainForeground = MimirAccessibilityService.instance?.mainScreenForeground()
        val mainActivity = mainForeground?.activityName.orEmpty()
        val main = if (mainActivity.isEmpty() || mainActivity.contains("FallbackHome", ignoreCase = true)) {
            ""
        } else {
            mainForeground?.packageName.orEmpty()
        }
        // 拉起失败时不要把目标 App 留在主屏：项目约束是"不主动占用主屏"。
        // 副屏容量耗尽时 `am start --display` 会被系统忽略、App 直接落到主屏，
        // 不清掉就等于 Mimir 把用户主屏占住了（真机上就是这样留下一个设置页）。
        runCatching { transport.activities.removeTasksOutside(spec.packageName, handle) }

        return if (PackageScope.isTargetOrChild(spec.packageName, main)) {
            "目标应用启动到了主屏而不是副屏：ROM 没有采纳 launchDisplayId（或该 App 已有主屏任务被复用）。" +
                "任务在副屏上无法操作它，已中止"
        } else {
            "等待 ${LAUNCH_VERIFY_ATTEMPTS * LAUNCH_VERIFY_GAP_MS / 1000} 秒后，" +
                "副屏 ${handle.displayId} 上仍未出现 ${spec.packageName}"
        }
    }

    /**
     * 功能：轮询等待目标 App 出现在副屏
     * 参数：handle 建屏句柄；spec 任务约束
     * 返回：在超时内出现为 true
     *
     * 两条证据源：优先读无障碍窗口（能拿到包名与页面标题）；无障碍索引偶发退化、
     * 整块屏读不出来时，退到 shell 侧任务列表——只要 AMS 里存在目标包在这块屏上的任务，
     * 就说明拉起已生效，后续观察走纯视觉降级，不再把"索引问题"误判成拉起失败。
     */
    private suspend fun awaitOnDisplay(handle: DisplayHandle, spec: TaskSpec): Boolean {
        repeat(LAUNCH_VERIFY_ATTEMPTS) { attempt ->
            val actual = runCatching { transport.activities.foregroundOn(handle) }
                .getOrNull()?.packageName.orEmpty()
            if (PackageScope.isWithin(spec.packageName, actual)) return true
            // 只有"无障碍根本不收录这块屏"才用 shell 侧任务兜底：索引正常时窗口还没出现
            // 往往是冷启动尚未首帧，继续等窗口比立刻放行进入纯视觉更可靠。
            if (actual.isEmpty() && !accessibilityIndexes(handle.displayId) &&
                taskExistsOn(handle, spec.packageName)
            ) {
                Log.d(
                    AGENT_TAG,
                    "无障碍索引未收录副屏 ${handle.displayId}，但 AMS 上存在 ${spec.packageName} 的任务，" +
                        "按已拉起继续（观察将降级纯视觉）",
                )
                return true
            }
            Log.d(
                AGENT_TAG,
                "等待 ${spec.packageName} 出现在副屏 ${handle.displayId}：" +
                    "实际=${actual.ifEmpty { "无应用窗口" }}",
            )
            if (attempt < LAUNCH_VERIFY_ATTEMPTS - 1) delay(LAUNCH_VERIFY_GAP_MS)
        }
        return false
    }

    /**
     * 功能：查询 AMS 里目标包是否在副屏上存在任务
     * 参数：handle 建屏句柄；packageName 目标包名
     * 返回：存在为 true；shell 不可用或查询失败为 false
     */
    private suspend fun taskExistsOn(handle: DisplayHandle, packageName: String): Boolean =
        runCatching { transport.activities.hasTaskOnDisplay(packageName, handle) }.getOrDefault(false)

    /**
     * 功能：判断无障碍框架当前是否收录了某块显示
     * 参数：displayId 目标显示
     * 返回：收录为 true；无障碍未连接或该屏不在索引里为 false
     */
    private fun accessibilityIndexes(displayId: Int): Boolean {
        val service = MimirAccessibilityService.instance ?: return false
        return displayId in service.visibleDisplayIds()
    }

    /**
     * 功能：取一帧观察；目标应用已不在副屏时重新拉起一次再取
     * 参数：handle 建屏句柄；spec 任务约束；task 当前任务（写审计用）
     * 返回：一致快照
     * 抛出：重新拉起后仍取不到时抛异常，消息写明已尝试过拉起
     *
     * 模型按一次 back 就可能把目标应用退出主界面，副屏变空后旧实现直接判任务失败。
     * 这里先确认"应用是不是真的不在了"：只有确认不在才重新拉起，页面动画导致的
     * 不一致仍按原样失败，避免每次观察抖动都去重启目标应用。
     */
    private suspend fun observeOrRecover(
        handle: DisplayHandle,
        spec: TaskSpec,
        task: TaskEntity,
    ): ObservationSnapshot {
        try {
            return observe(handle)
        } catch (e: Exception) {
            // 无障碍掉线时"屏上没有应用"这个判断本身不成立，直接按原错误上报
            if (MimirAccessibilityService.instance == null) throw e

            val foreground = runCatching { transport.activities.foregroundOn(handle) }.getOrNull()
            // 目标应用还在副屏上，只是控件树这次读不出来（页面切换、窗口重建）：
            // 降级纯视觉先保住观察，主循环会用本地等待重试，而不是把任务直接判死。
            if (foreground != null && PackageScope.isWithin(spec.packageName, foreground.packageName)) {
                return visionFallback(handle, foreground.packageName, e)
            }

            // 无障碍索引退化时 foregroundOn 读不到任何窗口；只要 AMS 上目标包的任务还在
            // 这块屏上，就不能按"应用不见了"重新拉起（那会在同一块屏上再叠一个实例）。
            if (foreground == null && taskExistsOn(handle, spec.packageName)) {
                Log.d(
                    AGENT_TAG,
                    "无障碍索引未收录副屏 ${handle.displayId}，但 AMS 上 ${spec.packageName} 的任务仍在，降级纯视觉观察",
                )
                return visionFallback(handle, spec.packageName, e)
            }

            val reason = "副屏上没有 ${spec.packageName}（当前：${foreground?.packageName ?: "无应用窗口"}），重新拉起"
            audit.recordSystemEvent(task, outcome = "APP_GONE", result = reason)
            Log.d(AGENT_TAG, reason)
            runCatching { transport.activities.launch(spec.packageName, handle) }
            delay(LAUNCH_SETTLE_MS)
            launchFailure(handle, spec)?.let { throw IllegalStateException("重新拉起失败：$it") }

            return try {
                observe(handle)
            } catch (retry: Exception) {
                val reopened = runCatching { transport.activities.foregroundOn(handle) }.getOrNull()
                if (reopened != null && PackageScope.isWithin(spec.packageName, reopened.packageName)) {
                    return visionFallback(handle, reopened.packageName, retry)
                }
                throw IllegalStateException(
                    "已重新拉起 ${spec.packageName}，仍取不到观察：${retry.message}",
                    retry,
                )
            }
        }
    }

    /**
     * 功能：控件树不可用但目标仍在副屏时，降级为纯视觉观察
     * 参数：handle 建屏句柄；packageName 当前副屏前台包名；cause 树采集失败的原因
     * 返回：纯视觉快照
     * 抛出：截图也取不到时抛异常，消息保留原始树错误
     */
    private suspend fun visionFallback(
        handle: DisplayHandle,
        packageName: String,
        cause: Throwable,
    ): ObservationSnapshot {
        Log.d(AGENT_TAG, "控件树不可读（${cause.message}），降级纯视觉：$packageName")
        return runCatching { assembler.visionSnapshot(handle, packageName) }.getOrElse { error ->
            throw IllegalStateException(
                "控件树不可读且截图失败，无法降级纯视觉：${cause.message}；${error.message}",
                error,
            )
        }
    }

    /**
     * 功能：写入动作前先把意图落盘
     * 参数：task 任务；stepIndex 步序；snapshot 观察；action 动作；verdict 闸门判定；
     *       digest 参数摘要；planVersion 动作所属计划版本；planStepId 动作所属计划步骤
     * 返回：已落盘的意图；纯只读动作返回 null
     *
     * 顺序是硬要求：先落盘、再注入（§1.3.1 ActionIntent）。进程死在注入中途时，
     * 这条 PREPARED/INJECTING 记录就是"未决写入"，恢复时只读核对，禁止重放。
     */
    private suspend fun prepareIntent(
        task: TaskEntity,
        stepIndex: Int,
        snapshot: ObservationSnapshot,
        action: AgentAction.Do,
        verdict: GuardDecision,
        digest: String,
        planVersion: Int,
        planStepId: String?,
    ): ActionIntentEntity? {
        if (!verdict.riskLevel.isWrite) return null

        val now = System.currentTimeMillis()
        val intent = ActionIntentEntity(
            actionId = UUID.randomUUID().toString(),
            taskId = task.taskId,
            stepIndex = stepIndex,
            planVersion = planVersion,
            planStepId = planStepId,
            observationId = snapshot.observationId,
            displayId = snapshot.displayId,
            displayGeneration = snapshot.displayGeneration,
            actionKind = action.kind.name,
            targetFingerprintJson = action.targetFingerprint?.let(TargetFingerprintCodec::encode),
            riskLevel = verdict.riskLevel.name,
            grantId = null,
            paramsDigest = digest,
            idempotent = action.kind == ActionKind.TAP,
            status = "PREPARED",
            outcome = null,
            systemReturnValue = null,
            evidence = null,
            retryOf = null,
            attemptCount = 1,
            createdAt = now,
            updatedAt = now,
        )
        intentDao.upsert(intent)
        return intent
    }

    /**
     * 功能：把步数、当前步骤与 checkpoint 落库
     * 参数：task 任务；stepIndex 已执行步数；snapshot 最近观察；applied 上一步是否被系统接受；
     *       goal 当前计划步骤标签
     * 返回：更新后的任务
     */
    private suspend fun persistProgress(
        task: TaskEntity,
        stepIndex: Int,
        snapshot: ObservationSnapshot,
        applied: Boolean,
        goal: String,
    ): TaskEntity {
        val updated = task.copy(
            stepCount = stepIndex,
            budgetStepsUsed = stepIndex,
            currentSubGoal = goal,
            lastObservationId = snapshot.observationId,
            displayGeneration = snapshot.displayGeneration,
            state = if (applied) TaskState.EXECUTING.name else task.state,
            updatedAt = System.currentTimeMillis(),
        )
        taskDao.upsert(updated)
        return updated
    }

    /**
     * 功能：把执行期会话计数写回任务行
     * 参数：session 执行会话
     * 返回：更新后的任务（同时同步到 session.task）
     *
     * 与任务行其它字段分开写：计数变化的时机（注入配方、动作计数、驳回、自愈）
     * 和步数/游标的写入点并不总是一致，单独写一次比让每个调用点都记得带上计数更不容易漏。
     * 写之前必须先把 session.task 同步到最近一次任务行写入的结果，否则会拿旧快照覆盖进度。
     */
    private suspend fun persistSession(session: Session): TaskEntity {
        val updated = session.task.copy(
            sessionJson = SessionStateCodec.encode(session.snapshotState()),
            updatedAt = System.currentTimeMillis(),
        )
        taskDao.upsert(updated)
        session.task = updated
        return updated
    }

    /**
     * 功能：把计划与游标写回任务行
     * 参数：task 任务；plan 当前计划；cursor 当前游标
     * 返回：更新后的任务
     *
     * 计划与游标必须同一次写入：只写计划不写游标会让恢复时按旧下标走进新计划，
     * 只写游标不写计划则版本对不上。
     */
    private suspend fun persistPlan(task: TaskEntity, plan: TaskPlan, cursor: PlanCursor): TaskEntity {
        val updated = task.copy(
            planJson = TaskPlanCodec.encode(plan),
            planVersion = plan.version,
            planSubtaskIndex = cursor.subtaskIndex,
            planStepIndex = cursor.stepIndex,
            currentSubGoal = plan.labelOf(cursor),
            updatedAt = System.currentTimeMillis(),
        )
        taskDao.upsert(updated)
        return updated
    }

    /**
     * 功能：在同一次写入里推进步数、观察与计划游标
     * 参数：task 任务；stepIndex 推进后的步数；snapshot 复核用的观察；plan 当前计划；cursor 新游标
     * 返回：更新后的任务
     *
     * 步骤达成是"一次性事实"：分成两次写库，进程若死在中间就会出现"步数进了、
     * 游标没进"的错位，恢复时会把同一步再走一遍。
     */
    private suspend fun persistStepAdvance(
        task: TaskEntity,
        stepIndex: Int,
        snapshot: ObservationSnapshot,
        plan: TaskPlan,
        cursor: PlanCursor,
    ): TaskEntity {
        val updated = task.copy(
            stepCount = stepIndex,
            budgetStepsUsed = stepIndex,
            planJson = TaskPlanCodec.encode(plan),
            planVersion = plan.version,
            planSubtaskIndex = cursor.subtaskIndex,
            planStepIndex = cursor.stepIndex,
            currentSubGoal = plan.labelOf(cursor),
            lastObservationId = snapshot.observationId,
            displayGeneration = snapshot.displayGeneration,
            state = TaskState.EXECUTING.name,
            updatedAt = System.currentTimeMillis(),
        )
        taskDao.upsert(updated)
        return updated
    }

    /**
     * 功能：从任务行还原已落库的计划
     * 参数：task 任务
     * 返回：计划；从未规划过或内容不可解析时为 null
     */
    private fun restorePlan(task: TaskEntity): TaskPlan? = TaskPlanCodec.decode(task.planJson)

    /**
     * 功能：把 Planner 解析出的 TaskSpec 写回任务行
     * 参数：task 任务；spec 解析结果
     * 返回：更新后的任务
     */
    private suspend fun persistSpec(task: TaskEntity, spec: TaskSpec): TaskEntity {
        val updated = task.copy(
            taskType = spec.taskType,
            packageName = spec.packageName,
            specJson = TaskSpecCodec.encode(spec),
            updatedAt = System.currentTimeMillis(),
        )
        taskDao.upsert(updated)
        return updated
    }

    /**
     * 功能：状态迁移并落库
     * 参数：task 当前任务；target 目标状态；reason 挂起/失败原因
     * 返回：更新后的任务
     */
    private suspend fun transition(task: TaskEntity, target: TaskState, reason: String? = null): TaskEntity {
        val current = TaskState.valueOf(task.state)
        if (target != current && target !in allowedTransitions(current)) {
            throw IllegalStateException("非法状态迁移 ${current} → ${target}")
        }
        val now = System.currentTimeMillis()
        val updated = task.copy(
            state = target.name,
            suspendReason = reason ?: task.suspendReason,
            updatedAt = now,
            finishedAt = if (target.isTerminal) now else task.finishedAt,
        )
        taskDao.upsert(updated)
        return updated
    }

    /**
     * 功能：取动作后的判定证据
     * 参数：handle 建屏句柄
     * 返回：一致观察对单帧树；一致观察可用时单帧树为 null
     *
     * 动画页面（列表扫描、加载骨架）常常长时间形不成一致观察；此时多次重试单帧树，
     * 仍拿不到就两者都为 null，由后置条件判 OUTCOME_UNKNOWN。这里打一行日志，
     * 让"为什么判未知"在 Logcat 里可追溯，而不是只能猜。
     */
    private suspend fun postActionFrames(handle: DisplayHandle): Pair<ObservationSnapshot?, TreeFrame?> {
        runCatching { assembler.snapshot(handle) }
            .onSuccess { return it to null }
            .onFailure { Log.d(AGENT_TAG, "一致观察不可用：${it.message}") }

        repeat(POST_ACTION_ATTEMPTS) { attempt ->
            runCatching { trees.capture(handle) }
                .onSuccess { return null to it }
                .onFailure { Log.d(AGENT_TAG, "单帧树不可用：${it.message}") }
            if (attempt < POST_ACTION_ATTEMPTS - 1) delay(POST_ACTION_RETRY_MS)
        }
        return null to null
    }

    /**
     * 功能：把注入返回值与后置条件拼成一句证据
     * 参数：applied 系统是否接受事件；effect 后置条件结论；weak 是否只有单帧树这类弱证据
     * 返回：证据摘要
     */
    private fun evidenceOf(applied: Boolean, effect: EffectOutcome, weak: Boolean = false): String {
        val suffix = if (weak) "；弱证据=单帧树" else ""
        return "系统接受=${if (applied) "是" else "否"}；后置条件=${effect.name}$suffix"
    }

    /**
     * 功能：把元素级 diff 拼进审计证据
     * 参数：before 动作前观察；after 动作后一致观察；afterTree 动作后单帧树
     * 返回：`；元素变化=+a/-r/~m/l`；没有可用后帧时为空串
     *
     * 只做记录，不参与判定：三态结论仍由 PostCondition 依据同一次观察给出。
     * 审计里留下 diff 数值，页面"看起来变了但目标没动"这类误判才有据可查。
     */
    private fun diffEvidence(
        before: ObservationSnapshot,
        after: ObservationSnapshot?,
        afterTree: TreeFrame?,
    ): String {
        val afterElements = when {
            after?.mode == ObservationMode.VISION_ONLY -> return ""
            after != null -> after.elements
            afterTree != null -> afterTree.elements
            else -> return ""
        }
        val diff = ElementDiff.between(before.elements, afterElements)
        return "；元素变化=${diff.summary()}"
    }

    /**
     * 功能：拼出确认通知里的一句话说明
     * 参数：action 动作；snapshot 观察
     * 返回：说明文本
     */
    private fun confirmationSummary(action: AgentAction.Do, snapshot: ObservationSnapshot): String {
        val target = targetTextOf(action, snapshot)
        if (action.kind == ActionKind.CREDENTIAL) {
            val alias = credentials.find(action.text.orEmpty())?.accountAlias.orEmpty()
            return "使用凭据「$alias」填充「${target.ifEmpty { "密码框" }}」，需要你确认一次"
        }
        return if (target.isEmpty()) {
            "下一步 ${action.kind.name} 需要你确认一次"
        } else {
            "「$target」这一步需要你确认一次"
        }
    }

    /**
     * 功能：写一条审计记录
     * 参数：任务、步序、观察、动作、决策、闸门判定、结果与附加说明
     * 返回：无
     */
    private suspend fun recordAudit(
        taskId: String,
        stepIndex: Int,
        snapshot: ObservationSnapshot,
        action: AgentAction.Do?,
        decision: DecideResult?,
        verdict: GuardDecision?,
        outcome: String,
        digest: String = "",
        actionId: String? = null,
        grantId: String? = null,
        result: String? = null,
        evidence: String? = null,
        decisionOverride: String? = null,
        memoryUsed: String? = null,
        memoryAssertion: String? = null,
        screenshotBefore: String? = null,
        screenshotAfter: String? = null,
        timing: StepTiming? = null,
    ) {
        audit.record(
            AuditEntity(
                taskId = taskId,
                stepIndex = stepIndex,
                timestamp = System.currentTimeMillis(),
                subGoal = snapshot.activityName,
                observationId = snapshot.observationId,
                observationHash = snapshot.treeHashBefore,
                displayGeneration = snapshot.displayGeneration,
                actionId = actionId,
                actionKind = action?.kind?.name.orEmpty(),
                paramsDigest = digest,
                modelOutput = decision?.rawOutput.orEmpty(),
                systemReturnValue = null,
                targetFingerprint = action?.targetFingerprint?.let(TargetFingerprintCodec::encode),
                targetText = action?.let { targetTextOf(it, snapshot) }?.ifEmpty { null },
                idempotency = if (action?.kind == ActionKind.TAP) "IDEMPOTENT" else "NON_IDEMPOTENT",
                intentStatus = null,
                outcome = outcome,
                grantId = grantId,
                riskLevel = verdict?.riskLevel?.name ?: RiskLevel.L0.name,
                decision = decisionOverride ?: if (verdict?.allowed == false) "DENY" else "ALLOW",
                reviewReason = verdict?.reason ?: result,
                result = result ?: outcome,
                verificationEvidence = evidence,
                memoryUsed = memoryUsed,
                memoryAssertion = memoryAssertion,
                imageTokens = imageTokensOf(snapshot),
                // 实测用量（后端返回什么记什么）；只有"这一步真的调了模型"才有值，
                // 系统事件行保持 0，统计时按非零行累加即可。
                promptTokens = decision?.usage?.promptTokens ?: 0,
                completionTokens = decision?.usage?.completionTokens ?: 0,
                observeMs = timing?.observeMs ?: 0,
                decideMs = timing?.decideMs ?: 0,
                actMs = timing?.actMs ?: 0,
                totalMs = timing?.totalMs ?: 0,
                screenshotBefore = screenshotBefore,
                screenshotAfter = screenshotAfter,
            ),
        )
    }

    /**
     * 功能：按图像尺寸估算视觉 token（§2.3.1）
     * 参数：snapshot 观察
     * 返回：估算 token 数；无图时为 0
     *
     * 28×28 像素折一个 token，是成本量级估算；真实值应以各后端的 image processor 为准。
     */
    private fun imageTokensOf(snapshot: ObservationSnapshot): Int {
        val image = snapshot.image ?: return 0
        return (image.width / TOKEN_PATCH) * (image.height / TOKEN_PATCH)
    }

    /**
     * 功能：任务本轮结束后决定保留还是销毁副屏
     * 参数：taskId 任务标识；handle 本轮使用的副屏句柄
     * 返回：无
     *
     * NEED_HUMAN 必须保留画面：接管页要在用户处理期间显示实时画面（§4.3）。
     * 其它状态（终态或异常失败）一律销毁，避免虚拟屏泄漏。
     */
    private suspend fun retainOrRelease(taskId: String, handle: DisplayHandle) {
        val state = runCatching { TaskState.valueOf(taskDao.findById(taskId)?.state.orEmpty()) }.getOrNull()
        if (state == TaskState.NEED_HUMAN || resultViews.isPending(taskId)) {
            // 保留副屏期间用户要看结果或接手操作，键盘不能继续被无键盘输入法占着
            runCatching { transport.ime.restore() }
            Log.d(AGENT_TAG, "任务结束，保留副屏 ${handle.displayId} 供人工处理/查看结果")
            return
        }
        destroyDisplay(handle)
    }

    /**
     * 功能：销毁副屏并释放输入法
     * 参数：handle 建屏句柄
     * 返回：无
     *
     * 清理失败也要吞掉，不能让一次异常掩盖真正的失败原因。
     */
    private suspend fun destroyDisplay(handle: DisplayHandle) {
        activeDisplay.clear(handle)
        runCatching { transport.ime.restore() }
        runCatching { transport.displays.destroy(handle) }
    }

    /**
     * 一次任务执行期间的会话状态；不落库的部分只在这里。
     *
     * @property task 任务行快照
     * @property spec 任务不可变约束
     * @property handle 副屏句柄
     * @property plan 当前任务计划
     * @property cursor 计划游标
     * @property actionsOnStep 当前步骤上已执行的动作数；超过上限触发受限重规划
     * @property replans 已用掉的受限重规划次数；不随计划版本重置
     * @property stepRejections 当前步骤连续被驳回的完成声明次数
     * @property stepMemoryIds 当前步骤注入过的配方；该步被重规划取代时记它们一次失败
     * @property packageEscapes 已用掉的"前台离开目标包"自愈次数；用尽后越界直接转人工
     * @property overlaySeen 本轮轨迹是否出现过一次性遮挡；写入记忆前要看它
     * @property overlayDismissals 已关闭过多少次遮挡；用于识别关闭循环
     * @property visionWaits 连续多少次只拿到纯视觉观察；不落库，续跑时重新计数
     */
    private class Session(
        var task: TaskEntity,
        val spec: TaskSpec,
        val handle: DisplayHandle,
        var plan: TaskPlan,
        var cursor: PlanCursor,
        /** 任务入口页观察；用于生成配方页面签名。 */
        val entrySnapshot: ObservationSnapshot,
        var actionsOnStep: Int = 0,
        var replans: Int = 0,
        var stepRejections: Int = 0,
        var stepMemoryIds: List<String> = emptyList(),
        var packageEscapes: Int = 0,
        var overlaySeen: Boolean = false,
        var overlayDismissals: Int = 0,
        var visionWaits: Int = 0,
        /** 连续多少次单步决策超时；任意一次成功决策后清零。 */
        var stepTimeouts: Int = 0,
        /** 当前步骤连续多少次输入目标被校验拦下；达到上限就重规划。 */
        var inputTargetRejections: Int = 0,
        /** 当前步骤连续多少次纯视觉坐标盲点被拦下；达到上限就重规划。 */
        var blindTapRejections: Int = 0,
        /** 上一次已落盘的输入动作键（文本 + 目标锚点）；用于拦截同一句话的重复输入。 */
        var lastTextWrite: String? = null,
        /** 当前步骤的游标键（子任务:步骤），用于判断"是否换了步骤"。 */
        var stepEntryKey: String = "",
        /** 进入当前步骤时页面上的文本（归一化），用于判断判据是不是猜的。 */
        var stepEntryText: String = "",
    ) {

        /**
         * 功能：把当前计数快照成可落库的形态
         * 参数：无
         * 返回：带计划版本与游标的会话计数
         */
        fun snapshotState(): SessionState = SessionState(
            actionsOnStep = actionsOnStep,
            replans = replans,
            stepRejections = stepRejections,
            stepMemoryIds = stepMemoryIds,
            packageEscapes = packageEscapes,
            planVersion = plan.version,
            subtaskIndex = cursor.subtaskIndex,
            stepIndex = cursor.stepIndex,
            overlaySeen = overlaySeen,
            overlayDismissals = overlayDismissals,
        )

        /**
         * 功能：从任务行恢复计数
         * 参数：stored 已落库的计数
         * 返回：无
         *
         * 步骤级计数只在计划版本与游标一致时沿用：换了步骤或重规划过，就该从零起算，
         * 否则下一步会背上上一步的驳回次数，还没决策就被判失效。
         */
        fun restoreState(stored: SessionState) {
            replans = stored.replans
            packageEscapes = stored.packageEscapes
            // 遮挡是任务级事实：即使续跑换了步骤，也不能因为"换了一步"就允许把遮挡路径写进记忆
            overlaySeen = stored.overlaySeen
            overlayDismissals = stored.overlayDismissals
            val sameStep = stored.planVersion == plan.version &&
                stored.subtaskIndex == cursor.subtaskIndex &&
                stored.stepIndex == cursor.stepIndex
            if (!sameStep) return
            actionsOnStep = stored.actionsOnStep
            stepRejections = stored.stepRejections
            stepMemoryIds = stored.stepMemoryIds
        }
    }

    /** 主循环的三种出口。 */
    private sealed interface LoopResult {
        /** 模型声明完成，带着完成时的观察进入验证。 */
        data class Finished(val snapshot: ObservationSnapshot, val summary: String) : LoopResult

        /** 需要人工介入，任务已置 NEED_HUMAN。 */
        data class Suspended(val reason: String) : LoopResult

        /** 失败，任务已置 FAILED。 */
        data class Failed(val reason: String) : LoopResult
    }

    /** 单步处理的去向。 */
    private enum class StepKind {
        /** 继续下一步。 */
        CONTINUE,

        /** 已挂起，主循环退出。 */
        SUSPENDED,

        /** 就地收尾（待支付页交接），主循环转验证。 */
        FINISHED,
    }

    /**
     * 单步处理结果。
     *
     * @property stepIndex 处理后的已执行步数
     * @property task 处理后的任务行
     * @property kind 继续还是挂起
     * @property reason 挂起原因；继续时为空
     * @property actionExecuted 动作是否真的注入过；被拦下/作废时为 false，
     *       主循环据此忽略动作里携带的 step_done 声明
     * @property effect 注入后的三态判定；只用于判断"页面是否真的变了"
     */
    private data class StepResult(
        val stepIndex: Int,
        val task: TaskEntity,
        val kind: StepKind = StepKind.CONTINUE,
        val reason: String? = null,
        val actionExecuted: Boolean = true,
        val effect: EffectOutcome? = null,
    )

    /** 主屏交还监听用的作用域：与任务执行解耦，任务结束后监听自行退出。 */
    private val handoffScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    /** 结果页保留超时用的作用域：到期自动释放副屏，避免堵住队列。 */
    private val resultReleaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 当前执行中的任务与协程句柄。
     *
     * 只在 [run] 里登记，在 finally 清理阶段清空；[forceAbort] 据此判断
     * "要打断的是一个真正在跑的任务"还是"只改一条遗留记录"。
     */
    @Volatile
    private var runningJob: Job? = null

    /** 当前执行中的任务标识；没有任务在跑时为空。 */
    @Volatile
    private var runningTaskId: String? = null

    private companion object {
        /** Agent 层诊断日志标签。 */
        const val AGENT_TAG = "MimirAgent"

        /** 拉起目标 App 后的粗等时间（毫秒）。 */
        const val LAUNCH_SETTLE_MS = 3000L

        /** 复用挂起时保留下来的副屏时，等待过渡动画结束的短等（毫秒）。 */
        const val REUSE_SETTLE_MS = 600L

        /** 拉起后校验目标应用是否真在副屏上的轮询次数。 */
        // 12×500ms=6s 对微信/淘宝这类冷启动重 App 太短：真机上微信冷启动到首帧
        // 常常超过 6 秒，会被判成"副屏上没出现目标应用"直接失败。先放宽到 12 秒；
        // 2026-11-02 美团/设置冷启动仍会超过 12 秒，再放宽到 24 秒。
        const val LAUNCH_VERIFY_ATTEMPTS = 48

        /** 拉起校验的轮询间隔（毫秒）；12 × 500 = 最多等 6 秒。 */
        const val LAUNCH_VERIFY_GAP_MS = 500L

        /** 执行前复验允许的目标位移（像素）；720 宽度下约 8dp。 */
        const val TARGET_TOLERANCE_PX = 24

        /** 输入框外层容器的额外高度上限（像素）：超过它的可点击大卡片不算输入区域。 */
        const val WRAPPER_HEIGHT_MARGIN_PX = 120

        /** 输入去重时坐标归一的网格边长（像素）：模型重复输入时落点常差几个像素。 */
        const val TEXT_TARGET_GRID = 40

        /** 卡死检测的回看步数：连续这么多步同动作同目标且无界面变化即判卡住。 */
        const val STUCK_WINDOW = 3

        /** 同一任务允许的受限重规划次数；用尽后转人工，不靠重规划续命。 */
        const val MAX_REPLANS = 2

        /** 同一个步骤上连续执行多少个动作仍未达成即判定计划前提失效。 */
        const val STEP_ACTION_LIMIT = 12

        /** 同一步骤连续几次输入目标被校验拦下就重规划；再试只会继续猜错图标。 */
        const val MAX_INPUT_TARGET_REJECTIONS = 3

        /** 同一步骤连续几次纯视觉坐标盲点被拦下就重规划；图标语义 OCR 给不出来。 */
        const val MAX_BLIND_TAP_REJECTIONS = 2

        /** 同一步骤连续几次"声称完成但页面无后置条件"即判定计划前提失效。 */
        const val MAX_STEP_REJECTIONS = 2

        /**
         * 一次步骤复核最多顺带推进几步。
         *
         * 模型常在"已经做完第 2、3 步"之后才补一句 step_done，旧实现要为每一步再发一次
         * 完整视觉请求。同一帧既然已经满足后续步骤的判据，就一次推进到底；
         * 上限 3 步是防止判据文案过宽（例如整页都含「搜索」）时把计划一次性吞掉。
         */
        const val MAX_GREEDY_ADVANCE = 3

        /** 退化计划里最多取几条完成字段做后置条件。 */
        const val MAX_POSTCONDITIONS = 3

        /** 主屏交还监听：轮询次数与间隔（200 × 1.5s ≈ 5 分钟）。 */
        const val HANDOFF_WATCH_TICKS = 200
        const val HANDOFF_WATCH_GAP_MS = 1500L

        /** 判"未知"后延迟复核的等待：只读点击专用。第一轮 1.2s，重转场第二轮 2.5s。 */
        const val UNKNOWN_RECHECK_MS = 1200L
        const val UNKNOWN_RECHECK_SECOND_MS = 2500L

        /** 同页重读前的静置时间：让页面完成渲染与动画，第二份快照才真的"新"。 */
        const val READBACK_DELAY_MS = 1500L

        /** 记忆审计里回显稳定键的长度上限。 */
        const val STABLE_KEY_HINT = 80

        /** 同一任务最多为"前台离开目标包"按几次返回；用尽后越界直接转人工。 */
        const val MAX_PACKAGE_ESCAPES = 2

        /** 每次按返回后等前台包名变化的最多轮询次数与间隔。 */
        const val PACKAGE_RECOVERY_ATTEMPTS = 6
        const val PACKAGE_RECOVERY_GAP_MS = 350L

        /** 同一任务里最多把跑到主屏的 App 搬回副屏几次；超过就清场转人工，避免来回拉扯。 */
        const val MAX_ESCAPE_RECOVERIES = 3

        /** 连续多少次只拿到纯视觉观察就转人工；每次中间等待，给页面切换留时间。 */
        const val MAX_VISION_WAITS = 3

        /**
         * 连续多少次单步决策超时就转人工。
         *
         * 一次超时可能只是后端抖动：取消后重新观察再试是安全的（没有任何注入发生）。
         * 连续两次说明当前后端在这条链路上已经不可用，继续烧预算不如交人。
         */
        const val MAX_STEP_TIMEOUTS = 2

        /** 纯视觉降级时的等待时长（毫秒）。 */
        const val VISION_WAIT_MS = 1000L

        /** 同一任务里最多关闭几次瞬时遮挡；超过即判定关闭循环并转人工。 */
        const val MAX_OVERLAY_DISMISSALS = 3

        /** 单次一致观察的最大尝试次数。 */
        const val OBSERVE_ATTEMPTS = 5

        /** 两次观察尝试之间的间隔（毫秒），留给窗口重建与弹窗过渡。 */
        const val OBSERVE_RETRY_MS = 500L

        /** 动作后单帧树的最大尝试次数：新页面创建的头 1 秒内常常所有窗口根节点都不可读。 */
        const val POST_ACTION_ATTEMPTS = 5

        /** 动作后单帧树的重试间隔（毫秒）。 */
        const val POST_ACTION_RETRY_MS = 400L

        /** 动作后等待界面安静的超时（毫秒）。 */
        const val QUIET_TIMEOUT_MS = 3000L

        /** 构造 prompt 时取的最近步数。 */
        const val HISTORY_LIMIT = 3


        /** 视觉 token 估算的像素块边长。 */
        const val TOKEN_PATCH = 28

        /** TaskSpec 没写完成边界时，授权范围的最小描述。 */
        const val DEFAULT_GRANT_SCOPE = "仅本次动作，一次性"

        /** 审计里表示"可以继续一次"的处置值；与接管页判断保持同一份字面量。 */
        const val DECISION_NEED_CONFIRM = "NEED_CONFIRM"

        /**
         * 结果页在副屏上的保留时长。
         *
         * 它的唯一用途是"用户点结果通知时还能直接看到原页面"；超过这个时间就释放副屏，
         * 用户仍可从通知或桌面打开 App 首页。
         *
         * 曾经从 10 分钟压到 2 分钟，原因是队列被一条已完成任务堵住。现在这个副作用已经
         * 由 [releaseRetainedResults] 兜住——新任务入队时会先释放保留页再建屏，所以
         * 保留时长只影响"点通知还能不能回到原页面"，不再影响队列，取 10 分钟对演示更友好。
         */
        const val RESULT_RETENTION_MS = 10 * 60 * 1000L

        /**
         * 查看类任务的关键词。
         *
         * 命中后即使完成契约的页面文案没核对上，也按"已完成未复验"交付结果页通知，
         * 而不是把人叫回来处理：视频页的「点赞」往往只有图标，问答标点也常与计划不同。
         */
        val VIEW_TASK_HINTS = listOf(
            "打开", "搜索", "查找", "找", "查看", "播放", "问", "看看",
            "open", "search", "find", "show", "view", "play", "ask",
        )

        /**
         * 购物类任务的关键词：命中后同样保留副屏，但不放宽完成契约的核对。
         *
         * 只收"会把用户带到购物车/待支付页"的说法，不收泛化的"买"以外的动词，
         * 避免把普通任务也误判成交付页面（多留一块副屏 10 分钟）。
         */
        val DELIVERY_PAGE_HINTS = listOf(
            "外卖", "购物车", "加购", "下单", "点单", "点一", "买", "结算", "待支付", "去支付",
            "order", "cart", "checkout", "waimai", "takeout",
        )

        /**
         * 表示"停在支付/结算页"的目的地措辞。
         *
         * 这类说法里含「支付/payment」，但描述的是终点而不是要求代付；
         * 判成资金任务会让「立即支付」被硬阻断，拿不到待支付页交接。
         */
        val PAYMENT_DESTINATION_HINTS = listOf(
            "payment page", "payment step", "checkout", "checkout page",
            "待支付页", "支付页", "结算页", "收银台", "提交订单页", "确认订单页",
        )
    }
}
