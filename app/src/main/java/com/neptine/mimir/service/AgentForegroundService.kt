package com.neptine.mimir.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.neptine.mimir.R
import com.neptine.mimir.agent.MemoryReverifier
import com.neptine.mimir.agent.Orchestrator
import com.neptine.mimir.data.db.dao.TaskDao
import com.neptine.mimir.device.ActiveDisplayRegistry
import com.neptine.mimir.service.schedule.ScheduledTaskRunner
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Agent 执行期保活服务（设计文档 §1.2 / §4.1）。
 *
 * 用 specialUse 类型：任务执行是长时前台工作，但没有更贴切的既有类型。
 * manifest 里必须同时声明 FOREGROUND_SERVICE_SPECIAL_USE 权限与
 * PROPERTY_SPECIAL_USE_FGS_SUBTYPE 属性，否则 targetSdk 34+ 起会拒绝启动。
 *
 * 职责只有一个：按创建顺序取出 CREATED 任务，交给 Orchestrator 逐条跑完。
 * 同一时刻只跑一个任务、只持有一个活动副屏。
 */
@AndroidEntryPoint
class AgentForegroundService : Service() {

    @Inject
    lateinit var orchestrator: Orchestrator

    @Inject
    lateinit var taskDao: TaskDao

    @Inject
    lateinit var activeDisplay: ActiveDisplayRegistry

    @Inject
    lateinit var memoryReverifier: MemoryReverifier

    @Inject
    lateinit var scheduledTaskRunner: ScheduledTaskRunner

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 防止重复 startCommand 触发两轮队列循环。 */
    private val queueLock = Mutex()

    /**
     * 功能：创建通知渠道
     * 参数：无
     * 返回：无
     */
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    /**
     * 功能：启动前台通知并处理本次启动请求
     * 参数：intent 启动意图；flags 启动标志；startId 启动标识
     * 返回：粘性重启模式
     *
     * 三种入口共用一条队列锁：普通启动只消费 CREATED 队列，
     * 「继续一次」与「放弃」来自交接通知，处理完当前任务后继续消费队列。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        val action = intent?.action
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        if (action == ACTION_RUN_SCHEDULED) {
            // 定时任务不占队列锁：它要等整轮任务跑完，占锁会把后续「继续」与手动任务一起堵住。
            // 它自己通过 TaskLauncher 创建任务，队列仍按普通路径消费。
            val scheduledId = intent?.getStringExtra(EXTRA_SCHEDULED_ID)
            scope.launch {
                scheduledId?.let { runCatching { scheduledTaskRunner.run(it) } }
                stopSelfResult(startId)
            }
            return START_STICKY
        }
        if (action == ACTION_REVERIFY_MEMORY) {
            // 重验不排任务队列：队列锁会被整轮任务占住，排在后面要等十几分钟才有结果。
            // 它自带"副屏被占用就拒绝"的判断，与队列走同一条单屏约束。
            val procedureId = intent?.getStringExtra(EXTRA_PROCEDURE_ID)
            scope.launch {
                procedureId?.let { runCatching { memoryReverifier.verify(it) } }
                stopSelfResult(startId)
            }
            return START_STICKY
        }
        scope.launch { process(action, taskId, startId) }
        return START_STICKY
    }

    /**
     * 功能：服务不提供绑定
     * 参数：intent 绑定意图
     * 返回：恒为 null
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 功能：服务销毁时取消运行中的任务
     * 参数：无
     * 返回：无
     */
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 功能：处理一次启动请求，随后消费队列
     * 参数：action 请求动作；taskId 目标任务标识；startId 本次启动标识
     * 返回：无
     *
     * Orchestrator 内部已把失败写进任务行，这里只保证队列继续往前走；
     * 交接动作失败时任务仍停在 NEED_HUMAN，界面上还是有如实状态，不静默吞成"成功"。
     *
     * 用 stopSelf(startId) 而不是 stopSelf()：用户连续点两次通知时，先完成的那次
     * 不能把还在排队等锁的那次连同服务一起停掉。
     */
    private suspend fun process(action: String?, taskId: String?, startId: Int) = queueLock.withLock {
        android.util.Log.d("MimirHandoff", "服务处理动作 action=$action task=$taskId")
        when (action) {
            // 「继续」会在副屏上跑完整轮主循环，必须放进子协程：
            // Orchestrator 会把它登记为当前运行任务，任务页的强制打断据此取消它；
            // 直接摊在队列协程里，取消会把队列锁与 stopSelf 一起带走。
            ACTION_CONTINUE -> taskId?.let { id ->
                runChild {
                    // 这里必须记日志：之前 continueTask 抛异常会被静默吞掉，
                    // 表现为"点了继续、授权也消耗了，但任务一步没动"，很难查。
                    runCatching { orchestrator.continueTask(id) }
                        .onFailure { error ->
                            android.util.Log.e("MimirAgent", "继续任务失败 task=$id", error)
                        }
                }
            }
            ACTION_ABORT -> taskId?.let { runCatching { orchestrator.abort(it) } }
            ACTION_HUMAN_VERIFY -> taskId?.let { runCatching { orchestrator.markVerifiedByHuman(it) } }
            ACTION_OPEN_MAIN -> taskId?.let { runCatching { orchestrator.handOverToMain(it) } }
            ACTION_OPEN_RESULT -> taskId?.let { runCatching { orchestrator.openResultOnMain(it) } }
        }
        drainQueueLocked()
        stopSelf(startId)
    }

    /**
     * 功能：在独立子协程里执行一次任务动作并等待结束
     * 参数：block 任务动作
     * 返回：无
     *
     * 子协程失败或被打断都不向队列抛：队列要继续走，真实状态已经由 Orchestrator 落库。
     */
    private suspend fun runChild(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }.join()
    }

    /**
     * 功能：依次执行队列里的任务，直到没有待执行任务
     * 参数：无
     * 返回：无
     *
     * 单次只取一条：状态迁移由 Orchestrator 落库，取下一条时数据库里的状态已经变了。
     * 调用方必须已持有 queueLock。
     *
     * 有任务挂起并**保留副屏**时停止取队：同一时刻只允许一块活动副屏，
     * 此时新起任务会建第二块屏，既违反单屏约束也会让接管页预览取错画面。
     *
     * 每条任务在独立子协程里跑：Orchestrator 把该子协程登记为"当前运行任务"，
     * 任务页的强制打断据此直接取消它，不需要再经过前台服务（服务可能已被系统停掉）。
     */
    private suspend fun drainQueueLocked() {
        while (true) {
            if (activeDisplay.isOccupied()) {
                // 只有在"确实还有任务在排队"时才牺牲结果页；队列为空就让结果页留到超时，
                // 用户仍有机会点通知看结果。转人工任务的屏不能收，释放函数会拒绝并返回 false。
                val waiting = runCatching { taskDao.findNextCreated() }.getOrNull() ?: break
                if (!runCatching { orchestrator.releaseRetainedResults() }.getOrDefault(false)) break
                android.util.Log.d("MimirAgent", "队列等待任务 ${waiting.taskId}，已释放保留结果页")
            }
            val next = runCatching { taskDao.findNextCreated() }.getOrNull() ?: break
            android.util.Log.d("MimirAgent", "队列取任务 id=${next.taskId} 当前占用=${activeDisplay.isOccupied()}")
            runChild { orchestrator.run(next.taskId) }
        }
        android.util.Log.d(
            "MimirAgent",
            "队列排空结束 占用=${activeDisplay.isOccupied()} 剩余待跑=${runCatching { taskDao.findNextCreated() }.getOrNull()?.taskId}",
        )
    }

    /**
     * 功能：建立保活通知渠道
     * 参数：无
     * 返回：无
     */
    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.agent_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    /**
     * 功能：构造保活通知
     * 参数：无
     * 返回：通知对象
     */
    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.agent_running))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

    companion object {

        /**
         * 功能：标注"用户已核对"某个未复验的任务
         * 参数：context 上下文；taskId 任务标识
         * 返回：无
         */
        fun markVerifiedByHuman(context: Context, taskId: String) =
            launch(context, ACTION_HUMAN_VERIFY, taskId)

        /**
         * 功能：把出问题的 App 开到主屏，交给用户处理
         * 参数：context 上下文；taskId 任务标识
         * 返回：无
         */
        fun openOnMain(context: Context, taskId: String) =
            launch(context, ACTION_OPEN_MAIN, taskId)

        /**
         * 功能：打开任务结果页
         * 参数：context 任意上下文；taskId 任务标识
         * 返回：无
         *
         * 由结果通知点击触发：把完成时搬到主屏后台的同一系统任务带到前台，
         * 用户看到的就是 Agent 停留的那一页，而不是从 App 首页重新找。
         */
        fun openResult(context: Context, taskId: String) =
            launch(context, ACTION_OPEN_RESULT, taskId)
        /** 保活通知渠道标识。 */
        private const val CHANNEL_ID = "mimir_agent"

        /** 保活通知标识。 */
        private const val NOTIFICATION_ID = 1001

        /** 普通启动：只消费 CREATED 队列。 */
        private const val ACTION_RUN = "com.neptine.mimir.action.RUN_QUEUE"

        /** 交接「继续一次」或「继续」：L2A 签发授权后恢复，其它挂起直接恢复。 */
        private const val ACTION_CONTINUE = "com.neptine.mimir.action.CONTINUE_TASK"

        /** 交接「放弃」：把任务置为 ABORTED。 */
        private const val ACTION_ABORT = "com.neptine.mimir.action.ABORT_TASK"

        /** 人工核对未复验的结果（结果通知上的「我已核对」）。 */
        private const val ACTION_HUMAN_VERIFY = "com.neptine.mimir.action.HUMAN_VERIFY_TASK"

        /** 在主屏打开出问题的 App（交接通知上的「在主屏打开」）。 */
        private const val ACTION_OPEN_MAIN = "com.neptine.mimir.action.OPEN_ON_MAIN"

        /** 打开任务结果页（结果通知点击）：把完成时搬到主屏后台的系统任务带到前台。 */
        private const val ACTION_OPEN_RESULT = "com.neptine.mimir.action.OPEN_RESULT"

        /** 立即重验一条配方：在私有副屏上只读核对，不动配方内容。 */
        private const val ACTION_REVERIFY_MEMORY = "com.neptine.mimir.action.REVERIFY_MEMORY"

        /** 附加的任务标识。 */
        private const val EXTRA_TASK_ID = "task_id"

        /** 附加的配方标识。 */
        private const val EXTRA_PROCEDURE_ID = "procedure_id"

        /** 定时任务标识。 */
        private const val EXTRA_SCHEDULED_ID = "scheduledTaskId"

        /** 定时任务触发动作。 */
        private const val ACTION_RUN_SCHEDULED = "com.neptine.mimir.action.RUN_SCHEDULED_TASK"

        /**
         * 功能：启动执行服务
         * 参数：context 任意上下文
         * 返回：无
         *
         * 必须用 startForegroundService：任务执行可能超过 5 秒，普通 startService 会触发
         * ANR 级别的限制。调用点必须是用户可见的前台场景（例如点"发送"）。
         */
        fun start(context: Context) = launch(context, ACTION_RUN, null)

        /**
         * 功能：请求「继续一次」或「继续」并恢复任务
         * 参数：context 任意上下文；taskId 任务标识
         * 返回：无
         */
        fun continueTask(context: Context, taskId: String) = launch(context, ACTION_CONTINUE, taskId)

        /**
         * 功能：请求放弃任务
         * 参数：context 任意上下文；taskId 任务标识
         * 返回：无
         */
        fun abort(context: Context, taskId: String) = launch(context, ACTION_ABORT, taskId)

        /**
         * 功能：请求立即重验一条配方
         * 参数：context 任意上下文；procedureId 配方标识
         * 返回：无
         *
         * 必须在用户可见的前台场景调用（记忆页按钮），与任务发送同一条启动约束。
         */
        fun reverifyMemory(context: Context, procedureId: String) =
            launch(context, ACTION_REVERIFY_MEMORY, null, procedureId)

        /**
         * 功能：执行一条到点的定时任务
         * 参数：context 任意上下文；scheduledId 定时任务标识
         * 返回：无
         *
         * 由闹钟接收器调用；从 alarm 启动前台服务是后台启动限制的豁免场景。
         */
        fun runScheduled(context: Context, scheduledId: String) =
            launch(context, ACTION_RUN_SCHEDULED, null, scheduledId = scheduledId)

        /**
         * 功能：投递一次服务启动
         * 参数：context 任意上下文；action 请求动作；taskId 目标任务标识；
         *       procedureId 目标配方标识（仅重验使用）
         * 返回：无
         *
         * 系统拒绝后台启动前台服务时这里会失败，但任务行仍停在 NEED_HUMAN，
         * 界面上仍能看到真实状态，不把失败伪装成已处理。
         */
        private fun launch(
            context: Context,
            action: String,
            taskId: String?,
            procedureId: String? = null,
            scheduledId: String? = null,
        ) {
            val intent = Intent(context, AgentForegroundService::class.java)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_PROCEDURE_ID, procedureId)
                .putExtra(EXTRA_SCHEDULED_ID, scheduledId)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    android.util.Log.e("MimirSchedule", "启动前台服务失败 action=$action", it)
                }
        }
    }
}
