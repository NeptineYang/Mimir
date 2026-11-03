package com.neptine.mimir.agent

import android.util.Log
import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.DisplaySpec
import com.neptine.mimir.core.contract.MemorySource
import com.neptine.mimir.core.contract.MemoryStatus
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.Procedure
import com.neptine.mimir.core.contract.UiElement
import com.neptine.mimir.data.db.dao.MemoryDao
import com.neptine.mimir.data.db.dao.TaskDao
import com.neptine.mimir.data.db.entity.ProcedureEntity
import com.neptine.mimir.data.memory.MemoryAnchorAssertions
import com.neptine.mimir.data.memory.MemoryParameters
import com.neptine.mimir.data.memory.ProcedureCodec
import com.neptine.mimir.device.ActiveDisplayRegistry
import com.neptine.mimir.device.DeviceTransport
import com.neptine.mimir.observation.ObservationAssembler
import com.neptine.mimir.observation.TreeProvider
import com.neptine.mimir.service.MimirAccessibilityService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 立即重验的三种结论。 */
enum class ReverifyVerdict {
    /** 锚点与签名都通过。 */
    PASSED,

    /** 连续两次核对仍未通过，配方已转 STALE。 */
    FAILED,

    /** 条件不具备或路线失败，没有拿到可判定的证据；不动配方状态。 */
    UNAVAILABLE,
}

/**
 * 一次立即重验的结果。
 *
 * @property procedureId 配方标识
 * @property verdict 结论
 * @property detail 给界面与日志看的说明
 */
data class ReverifyResult(
    val procedureId: String,
    val verdict: ReverifyVerdict,
    val detail: String,
)

/**
 * 立即重验的进度与终局，供记忆页观察。
 *
 * @property procedureId 正在/刚刚核对过的配方
 * @property running 是否仍在核对
 * @property verdict 终局结论；运行中为空
 * @property detail 终局说明
 */
data class ReverifyStatus(
    val procedureId: String? = null,
    val running: Boolean = false,
    val verdict: ReverifyVerdict? = null,
    val detail: String? = null,
)

/**
 * 配方的立即重验（设计文档 §2.5.7「立即重验」）。
 *
 * 用一块私有虚拟屏拉起配方归属的 App，读出当前页面后断言入口锚点与页面签名：
 * 两条证据都过才算通过，更新 `last_verified_at` 与置信度；STALE 配方通过后恢复 ACTIVE。
 * 失败要连续两次核对都没过才置 STALE——一次页面抖动不该杀掉一条好记忆（§2.5.4）。
 *
 * 只做只读核对：不移到配方记录的目标页、不点击、不注入，配方内容一个字都不改。
 * 拿不到页面（无障碍离线、副屏被任务占用、App 拉不起来）一律判 UNAVAILABLE，
 * 不动配方状态——基础设施问题不能算到配方头上。
 */
@Singleton
class MemoryReverifier @Inject constructor(
    private val memoryDao: MemoryDao,
    private val taskDao: TaskDao,
    private val transport: DeviceTransport,
    private val trees: TreeProvider,
    private val assembler: ObservationAssembler,
    private val activeDisplay: ActiveDisplayRegistry,
) {

    /** 同一时刻只允许一次重验：两次核对各自建屏会互相踩帧。 */
    private val mutex = Mutex()

    private val _status = MutableStateFlow(ReverifyStatus())

    /** 进度与终局；记忆页据此显示"核对中"与结果提示。 */
    val status: StateFlow<ReverifyStatus> = _status.asStateFlow()

    /**
     * 功能：清空已消费的终局状态
     * 参数：无
     * 返回：无
     *
     * 由界面在展示完结果后调用：不清掉的话，重新进入记忆页会把上一次的结论再弹一遍。
     */
    fun acknowledge() {
        if (_status.value.running) return
        _status.value = ReverifyStatus()
    }

    /**
     * 功能：核对一条配方
     * 参数：procedureId 配方标识
     * 返回：核对结论
     */
    suspend fun verify(procedureId: String): ReverifyResult = mutex.withLock {
        _status.value = ReverifyStatus(procedureId = procedureId, running = true)
        val result = runCatching { doVerify(procedureId) }
            .getOrElse {
                Log.e(TAG, "核对异常", it)
                ReverifyResult(
                    procedureId,
                    ReverifyVerdict.UNAVAILABLE,
                    "核对异常：${it.message ?: it::class.java.simpleName}",
                )
            }
        _status.value = ReverifyStatus(
            procedureId = procedureId,
            running = false,
            verdict = result.verdict,
            detail = result.detail,
        )
        result
    }

    /**
     * 功能：执行一次核对
     * 参数：procedureId 配方标识
     * 返回：核对结论
     */
    private suspend fun doVerify(procedureId: String): ReverifyResult {
        val entity = memoryDao.findProcedure(procedureId)
            ?: return unavailable(procedureId, "配方不存在或已被删除")
        if (activeDisplay.isOccupied()) {
            return unavailable(procedureId, "有任务正在使用副屏，稍后再试")
        }
        if (MimirAccessibilityService.instance == null) {
            return unavailable(procedureId, "无障碍服务未连接，无法读取页面")
        }

        val procedure = ProcedureCodec.fromEntity(entity)
        val values = slotsOf(procedure) + taskParametersOf(procedure)

        var handle: DisplayHandle? = null
        try {
            handle = transport.displays.create(DisplaySpec())
            transport.activities.launch(procedure.packageName, handle)
            delay(LAUNCH_SETTLE_MS)
            val foreground = runCatching { transport.activities.foregroundOn(handle) }.getOrNull()
            if (foreground == null || !PackageScope.isWithin(procedure.packageName, foreground.packageName)) {
                val actual = foreground?.packageName ?: "无应用窗口"
                return unavailable(procedureId, "拉起 ${procedure.packageName} 后副屏前台是 $actual")
            }

            val first = observePage(handle, procedure.packageName)
                ?: return unavailable(procedureId, "拉起后取不到页面元素")
            val firstCheck = check(procedure, values, first.elements)
            if (firstCheck.passed == true) {
                val detail = "核对通过：${firstCheck.detail}（${first.source}）"
                markVerified(entity, procedure)
                Log.d(TAG, "配方「${procedure.title}」$detail")
                return ReverifyResult(procedureId, ReverifyVerdict.PASSED, detail)
            }
            if (firstCheck.passed == null) {
                // 没有可判定的证据（多数是旧配方还没记录页面签名）：不拿"没看到"当"变了"
                return unavailable(procedureId, firstCheck.detail)
            }

            // 明确不匹配时再取一次：结构相似度可能被加载态、弹窗或动画临时拉低
            delay(RECHECK_DELAY_MS)
            val second = observePage(handle, procedure.packageName)
                ?: return unavailable(procedureId, "首次未匹配（${firstCheck.detail}），复核时取不到页面")
            val secondCheck = check(procedure, values, second.elements)
            if (secondCheck.passed == true) {
                val detail = "首次未匹配（${firstCheck.detail}），复核一次通过：${secondCheck.detail}（${second.source}）"
                markVerified(entity, procedure)
                Log.d(TAG, "配方「${procedure.title}」$detail")
                return ReverifyResult(procedureId, ReverifyVerdict.PASSED, detail)
            }
            if (secondCheck.passed == null) {
                return unavailable(procedureId, "首次未匹配，复核无法判定：${secondCheck.detail}")
            }

            val detail = "连续 2 次核对未通过：${firstCheck.detail}；复核：${secondCheck.detail}"
            markStale(entity)
            Log.d(TAG, "配方「${procedure.title}」$detail，已转 STALE")
            return ReverifyResult(procedureId, ReverifyVerdict.FAILED, detail)
        } finally {
            handle?.let { runCatching { transport.displays.destroy(it) } }
        }
    }

    /**
     * 功能：按页面签名与入口锚点判断当前页是否仍符合配方
     * 参数：procedure 配方；values 占位符的填充取值；elements 当前帧元素
     * 返回：通过 / 明确不匹配 / 无法判定（passed 为 null）
     *
     * 重验只拉起 App，不重放配方里的滑动与点击，因此停在的是入口页：
     * - 有页面签名：签名描述的就是入口页，相似度低于阈值即"结构变了"，可判不匹配；
     * - 没有页面签名：只能看入口锚点是否恰好在这一页。锚点不在不等于页面变了，
     *   它可能本来就在更深的页面（例如关于页），此时必须说"无法判定"，不能置 STALE。
     */
    private fun check(procedure: Procedure, values: Map<String, String>, elements: List<UiElement>): PageCheck {
        val resolved = MemoryParameters.resolved(procedure, values)
        val anchors = MemoryAnchorAssertions.assert(resolved.precondition, elements)

        if (procedure.pageSignature.isNotEmpty()) {
            val actual = signatureTexts(elements, values)
            val similarity = similarityOf(procedure.pageSignature, actual)
            val note = "签名相似度 ${(similarity * 100).toInt()}%"
            return if (similarity >= MIN_SIGNATURE_SIMILARITY) {
                PageCheck(true, "${anchors.detail}；$note")
            } else {
                PageCheck(false, "${anchors.detail}；$note 低于 ${(MIN_SIGNATURE_SIMILARITY * 100).toInt()}%")
            }
        }

        if (anchors.passed) {
            return PageCheck(true, "${anchors.detail}；无页面签名，只按锚点断言")
        }
        return PageCheck(
            null,
            "${anchors.detail}；无页面签名，入口锚点不在当前页（可能位于更深页面），无法判定",
        )
    }

    /**
     * 功能：取当前页面的签名文本集合
     * 参数：elements 当前帧元素；values 动态参数值
     * 返回：掩码并去重后的文本集合
     *
     * 与写入配方时的签名用同一套掩码，否则换一次收件人就会把相似度拉到阈值以下。
     */
    private fun signatureTexts(elements: List<UiElement>, values: Map<String, String>): List<String> =
        elements
            .map { it.text.ifEmpty { it.contentDescription } }
            .filter { it.isNotEmpty() }
            .map { MemoryParameters.mask(it, values, SIGNATURE_MIN_VALUE_LENGTH).text }
            .filter { it.isNotEmpty() && !it.matches(PURE_PLACEHOLDER) }
            .distinct()
            .take(SIGNATURE_LIMIT)

    /**
     * 功能：算两个文本集合的 Jaccard 相似度
     * 参数：expected 配方签名；actual 当前页面签名
     * 返回：交集 ÷ 并集；任一侧为空时为 0
     */
    private fun similarityOf(expected: List<String>, actual: List<String>): Double {
        val left = expected.map(::normalize).filter { it.isNotEmpty() }.toSet()
        val right = actual.map(::normalize).filter { it.isNotEmpty() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val union = left.union(right).size.toDouble()
        return if (union == 0.0) 0.0 else left.intersect(right).size.toDouble() / union
    }

    /**
     * 功能：取配方各步记录过的参数槽位取值
     * 参数：procedure 配方
     * 返回：字段名 → 写入时取值
     */
    private fun slotsOf(procedure: Procedure): Map<String, String> =
        procedure.steps.fold(emptyMap()) { acc, step -> acc + step.parameterSlots }

    /**
     * 功能：取配方最近一次成功任务的参数
     * 参数：procedure 配方
     * 返回：TaskSpec.parameters；任务不存在或解析不到时为空
     *
     * `last_task_id` 只用于成功计数去重，但它是"这条配方最近一次真实跑过什么参数"
     * 的唯一线索；重验时用它把占位符填成页面上的实际值。
     */
    private suspend fun taskParametersOf(procedure: Procedure): Map<String, String> {
        val taskId = procedure.lastTaskId ?: return emptyMap()
        val task = runCatching { taskDao.findById(taskId) }.getOrNull() ?: return emptyMap()
        return runCatching { TaskSpecCodec.decode(task.specJson).parameters }.getOrDefault(emptyMap())
    }

    /**
     * 功能：取一份页面证据
     * 参数：handle 副屏句柄；expectedPackage 期望包名
     * 返回：页面元素与来源；多轮都读不到为 null
     *
     * 优先用一致观察；动画页面长期形不成一致观察时退回单帧树，
     * 并在说明里注明证据是单帧的，不假装两份观察一样强。
     */
    private suspend fun observePage(handle: DisplayHandle, expectedPackage: String): PageEvidence? {
        repeat(SNAPSHOT_ATTEMPTS) { attempt ->
            runCatching { assembler.snapshot(handle) }.onSuccess { snapshot ->
                if (PackageScope.isWithin(expectedPackage, snapshot.packageName)) {
                    return PageEvidence(snapshot.packageName, snapshot.activityName, snapshot.elements, "一致观察")
                }
            }
            if (attempt < SNAPSHOT_ATTEMPTS - 1) delay(SNAPSHOT_RETRY_MS)
        }
        repeat(FRAME_ATTEMPTS) { attempt ->
            runCatching { trees.capture(handle) }.onSuccess { frame ->
                if (PackageScope.isWithin(expectedPackage, frame.packageName)) {
                    return PageEvidence(frame.packageName, frame.activityName, frame.elements, "单帧树")
                }
            }
            if (attempt < FRAME_ATTEMPTS - 1) delay(FRAME_RETRY_MS)
        }
        return null
    }

    /**
     * 功能：重验通过后更新验证时间与置信度
     * 参数：entity 原配方行；procedure 配方
     * 返回：无
     *
     * 只改这三个派生字段；锚点、步骤、签名、版本一个都不动。
     * STALE 配方通过后恢复 ACTIVE：重新核对正是它唯一允许的恢复方式。
     */
    private suspend fun markVerified(entity: ProcedureEntity, procedure: Procedure) {
        val confidence = if (procedure.source == MemorySource.USER_WRITTEN) {
            1.0
        } else {
            laplace(procedure.successCount, procedure.failCount)
        }
        val status = if (procedure.status == MemoryStatus.STALE) MemoryStatus.ACTIVE else procedure.status
        memoryDao.upsertProcedure(
            entity.copy(
                status = status.name,
                confidence = confidence,
                lastVerifiedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * 功能：连续核对未通过时把配方转 STALE
     * 参数：entity 原配方行
     * 返回：无
     */
    private suspend fun markStale(entity: ProcedureEntity) {
        if (entity.status != MemoryStatus.ACTIVE.name) return
        memoryDao.upsertProcedure(
            entity.copy(
                status = MemoryStatus.STALE.name,
                lastVerifiedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * 功能：构造"无法核对"的结论
     * 参数：procedureId 配方标识；detail 原因
     * 返回：UNAVAILABLE 结果
     */
    private fun unavailable(procedureId: String, detail: String): ReverifyResult {
        Log.d(TAG, "配方 $procedureId 无法核对：$detail")
        return ReverifyResult(procedureId, ReverifyVerdict.UNAVAILABLE, detail)
    }

    /**
     * 功能：把文本归一化成可比较的形式
     * 参数：text 原始文本
     * 返回：去掉全部空白并转小写
     */
    private fun normalize(text: String): String = text.filterNot { it.isWhitespace() }.lowercase()

    /**
     * 功能：算 laplace 平滑后的成功率，与 MemoryWriter 同一口径
     * 参数：success 成功次数；fail 失败次数
     * 返回：取值 (0,1) 的置信度
     */
    private fun laplace(success: Int, fail: Int): Double =
        (success + 1.0) / (success + fail + 2.0)

    /** 一次核对用的页面证据。 */
    private data class PageEvidence(
        val packageName: String,
        val activityName: String,
        val elements: List<UiElement>,
        val source: String,
    )

    /**
     * 单页核对结论。
     *
     * @property passed true 通过；false 明确不匹配；null 证据不足，无法判定
     * @property detail 说明
     */
    private data class PageCheck(val passed: Boolean?, val detail: String)

    private companion object {
        /** 日志标签，与 Agent 主链路一致。 */
        const val TAG = "MimirAgent"

        /** 拉起目标 App 后的粗等时间（毫秒）。 */
        const val LAUNCH_SETTLE_MS = 3000L

        /** 首次未通过后复核的间隔（毫秒）。 */
        const val RECHECK_DELAY_MS = 900L

        /** 页面签名相似度阈值，与检索侧一致（§2.5.4）。 */
        const val MIN_SIGNATURE_SIMILARITY = 0.60

        /** 签名掩码的最短取值长度，与 MemoryParameters.signature 一致。 */
        const val SIGNATURE_MIN_VALUE_LENGTH = 2

        /** 页面签名最多比较多少条文本。 */
        const val SIGNATURE_LIMIT = 30

        /**
         * 整条文本就是一个占位符：本身是动态值，不参与结构比对。
         * 收尾 `}` 转义是 Android ICU 正则的要求，不能省。
         */
        val PURE_PLACEHOLDER = Regex("""\{[^{}]+\}""")

        /** 一致观察的重采次数与间隔。 */
        const val SNAPSHOT_ATTEMPTS = 3
        const val SNAPSHOT_RETRY_MS = 500L

        /** 单帧树的重采次数与间隔。 */
        const val FRAME_ATTEMPTS = 3
        const val FRAME_RETRY_MS = 400L
    }
}
