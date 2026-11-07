package com.neptine.mimir.data.memory

import com.neptine.mimir.agent.MemoryHit
import com.neptine.mimir.agent.MemoryRetriever
import com.neptine.mimir.core.contract.MemorySource
import com.neptine.mimir.core.contract.MemoryStatus
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.Preference
import com.neptine.mimir.core.contract.Procedure
import com.neptine.mimir.core.contract.ProcedurePage
import com.neptine.mimir.data.db.dao.MemoryDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * 记忆检索实现（设计文档 §2.5.6）。
 *
 * 五步固定顺序，和文档一一对应：
 * 1. 取当前包名、任务类型与页面文本；
 * 2. 过滤 `ACTIVE`、任务类型匹配、A5 兜底锚点未过期；
 * 3. 算页面签名相似度并断言入口前置条件，不匹配的**不进入候选**；
 * 4. 有效置信度按"存储值 × 版本系数 × 时间衰减"计算；
 * 5. 取 top-2。
 *
 * 不做的事：检索失败**不**顺手把配方标成 STALE。置 STALE 需要有"复核一次仍失败"的证据，
 * 而检索这一层拿不到第二次观察；把它交给执行期（配方被用上但后置条件没达成）更诚实。
 */
@Singleton
class RoomMemoryRetriever @Inject constructor(
    private val dao: MemoryDao,
    private val versions: AppVersionReader,
) : MemoryRetriever {

    /**
     * 功能：检索当前页面可用的配方
     * 参数：taskType 任务类型；snapshot 当前一致观察；parameters 本次任务参数
     * 返回：命中的配方（最多 2 条，按有效置信度降序）
     *
     * 参数槽位在这里填回：断言与注入 prompt 用的都是"这一次的收件人/商品名"，
     * 而不是写入配方时那一次的具体取值。
     */
    override suspend fun retrieve(
        taskType: String,
        snapshot: ObservationSnapshot,
        parameters: Map<String, String>,
    ): List<MemoryHit> {
        if (taskType.isEmpty()) return emptyList()
        val candidates = runCatching { dao.findActiveCandidates(snapshot.packageName, taskType) }
            .getOrDefault(emptyList())
        if (candidates.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val currentTexts = pageTexts(snapshot, parameters)
        val versionCode = versions.versionCodeOf(snapshot.packageName)

        return candidates.mapNotNull { entity ->
            val stored = ProcedureCodec.fromEntity(entity, loadTree(entity.id))
            if (!isUsable(stored, now)) return@mapNotNull null
            val procedure = MemoryParameters.resolved(stored, parameters)
            val similarity = signatureSimilarity(stored.pageSignature, currentTexts)
            if (stored.pageSignature.isNotEmpty() && similarity < MIN_SIGNATURE_SIMILARITY) {
                return@mapNotNull null
            }
            val anchors = MemoryAnchorAssertions.assert(procedure.precondition, snapshot.elements)
            if (!anchors.passed) return@mapNotNull null
            MemoryHit(
                procedure = procedure,
                confidence = effectiveConfidence(stored, versionCode, now),
                assertion = anchors.detail + signatureNote(stored, similarity),
            )
        }
            .sortedByDescending { it.confidence }
            .take(MAX_HITS)
    }

    /**
     * 功能：读取适用于某 App 的偏好
     * 参数：packageName 目标包名
     * 返回：全局与 App 级偏好；按置信度降序，最多 8 条
     *
     * 命中即累加计数：偏好是否真的被用上，目前只能靠"注入过"近似；
     * 写失败不影响本次规划，记忆读路径不能因为记账把任务拖死。
     */
    override suspend fun retrievePreferences(packageName: String): List<Preference> {
        val rows = runCatching { dao.findPreferences(packageName) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return emptyList()
        val hits = rows.take(MAX_PREFERENCES).map { ProcedureCodec.fromEntity(it) }
        runCatching {
            dao.bumpPreferenceHits(hits.map { it.id }, System.currentTimeMillis())
        }
        return hits
    }

    /**
     * 功能：读取一条配方的树
     * 参数：procedureId 配方标识
     * 返回：按页面顺序排列的树；节点缺失时为空列表
     *
     * 每条候选查两次节点表：候选最多 2 条（MAX_HITS），这里不做批量优化；
     * 批量取法留给编辑页，那里才是"一次要看几十条配方"的场景。
     */
    private suspend fun loadTree(procedureId: String): List<ProcedurePage> =
        ProcedureTree.assemble(
            pages = dao.findPages(procedureId),
            actions = dao.findActions(procedureId),
            anchorOf = ProcedureCodec::decodeAnchorOrNull,
            slotsOf = ProcedureCodec::decodeSlots,
        )

    /**
     * 功能：判断配方是否还有效
     * 参数：procedure 配方；now 当前时间戳
     * 返回：可用为 true
     *
     * 只有 A5 绝对坐标锚点需要 TTL：其它级别的锚点靠"用之前重新找一遍"自证，
     * 过期与否由断言结果说话，不靠时间判死。
     */
    private fun isUsable(procedure: Procedure, now: Long): Boolean {
        if (procedure.status != MemoryStatus.ACTIVE) return false
        val ttl = procedure.validUntil ?: return true
        return now <= ttl
    }

    /**
     * 功能：算有效置信度
     * 参数：procedure 配方；currentVersion 目标 App 当前版本号；now 当前时间戳
     * 返回：置信度，落在 [0.2, 1.0]
     *
     * 存储值承载 laplace 部分（成功/失败次数），读取时再叠加版本系数与时间衰减：
     * 这样"用户手写固定 1.0"和"用户教导初始 0.9"这两个种子值不会被公式抹平。
     */
    private fun effectiveConfidence(procedure: Procedure, currentVersion: Int, now: Long): Double {
        if (procedure.source == MemorySource.USER_WRITTEN) return 1.0
        val versionFactor = when {
            currentVersion <= 0 -> 1.0
            procedure.appVersionCode <= 0 -> 1.0
            currentVersion == procedure.appVersionCode -> 1.0
            else -> VERSION_MISMATCH_FACTOR
        }
        val baseline = procedure.lastVerifiedAt.takeIf { it > 0 } ?: procedure.createdAt
        val ageDays = (now - baseline).coerceAtLeast(0L) / DAY_MS.toDouble()
        val decay = 0.5.pow(ageDays / HALF_LIFE_DAYS)
        return (procedure.confidence * versionFactor * decay).coerceIn(MIN_CONFIDENCE, 1.0)
    }

    /**
     * 功能：算页面签名相似度（Jaccard）
     * 参数：expected 配方记录的静态文本集合；actual 当前页面文本集合
     * 返回：交集 ÷ 并集，取值 0–1；任一侧为空时为 0
     */
    private fun signatureSimilarity(expected: List<String>, actual: List<String>): Double {
        val left = expected.map(::normalize).filter { it.isNotEmpty() }.toSet()
        val right = actual.map(::normalize).filter { it.isNotEmpty() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.intersect(right).size.toDouble()
        val union = left.union(right).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    /**
     * 功能：生成页面签名的断言说明
     * 参数：procedure 配方；similarity 刚算出的相似度
     * 返回：一句话；配方没记录签名时说明"只按锚点断言"
     */
    private fun signatureNote(procedure: Procedure, similarity: Double): String =
        if (procedure.pageSignature.isEmpty()) {
            "；无页面签名，只按锚点断言"
        } else {
            "；签名相似度 ${(similarity * 100).toInt()}%"
        }

    /**
     * 功能：取当前页面的可见文本集合
     * 参数：snapshot 一致观察；parameters 本次任务参数
     * 返回：去掉动态参数值并去重后的文本列表（文本优先，退回无障碍描述）
     *
     * 与写入配方时的签名用同一套掩码："收件人：张三"和"收件人：李四"要算同一段结构，
     * 否则换个参数就相似度暴跌，配方永远命不中。
     */
    private fun pageTexts(snapshot: ObservationSnapshot, parameters: Map<String, String>): List<String> =
        snapshot.elements
            .map { it.text.ifEmpty { it.contentDescription } }
            .filter { it.isNotEmpty() }
            .map { MemoryParameters.mask(it, parameters, SIGNATURE_MIN_VALUE_LENGTH).text }
            .filter { it.isNotEmpty() && !it.matches(PURE_PLACEHOLDER) }
            .distinct()
            .take(TEXT_LIMIT)

    /**
     * 功能：规范化用于比较的文本
     * 参数：text 原始文本
     * 返回：去掉全部空白并转小写
     */
    private fun normalize(text: String): String = text.filterNot { it.isWhitespace() }.lowercase()

    private companion object {
        /** 最多注入几条配方；§2.5.6 定的是 top-2。 */
        const val MAX_HITS = 2

        /** 最多注入几条偏好：偏好是"经验"，太多会把规划 prompt 淹掉。 */
        const val MAX_PREFERENCES = 8

        /** 页面签名相似度阈值；低于它说明页面结构已经变了（§2.5.4）。 */
        const val MIN_SIGNATURE_SIMILARITY = 0.60

        /** App 版本不一致时的降权系数（§2.5.4）。 */
        const val VERSION_MISMATCH_FACTOR = 0.3

        /** 置信度下限（§2.5.4）。 */
        const val MIN_CONFIDENCE = 0.2

        /** 时间衰减半衰期（天）。 */
        const val HALF_LIFE_DAYS = 30.0

        /** 一天对应的毫秒数。 */
        const val DAY_MS = 86_400_000L

        /** 页面签名最多取多少条文本，与审计页的签名上限一致。 */
        const val TEXT_LIMIT = 30

        /** 页面文本掩码的最短取值长度，与 MemoryParameters.signature 保持一致。 */
        const val SIGNATURE_MIN_VALUE_LENGTH = 2

        /**
         * 整条文本就是一个占位符：说明它本身就是动态值，不计入签名。
         * 收尾 `}` 转义是 Android ICU 正则的要求，不能省。
         */
        val PURE_PLACEHOLDER = Regex("""\{[^{}]+\}""")
    }
}
