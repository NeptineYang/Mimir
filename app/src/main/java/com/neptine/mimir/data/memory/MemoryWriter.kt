package com.neptine.mimir.data.memory

import com.neptine.mimir.agent.EffectOutcome
import com.neptine.mimir.agent.MemoryWriteResult
import com.neptine.mimir.agent.TargetFingerprintCodec
import com.neptine.mimir.agent.VerificationOutcome
import com.neptine.mimir.core.contract.ActionKind
import com.neptine.mimir.core.contract.Anchor
import com.neptine.mimir.core.contract.AnchorLevel
import com.neptine.mimir.core.contract.MemorySource
import com.neptine.mimir.core.contract.MemoryStatus
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.Procedure
import com.neptine.mimir.core.contract.ProcedureAction
import com.neptine.mimir.core.contract.ProcedurePage
import com.neptine.mimir.core.contract.ProcedureStep
import com.neptine.mimir.core.contract.TargetFingerprint
import com.neptine.mimir.core.contract.TaskSpec
import com.neptine.mimir.data.db.MimirDatabase
import com.neptine.mimir.data.db.dao.MemoryDao
import com.neptine.mimir.data.db.entity.AuditEntity
import com.neptine.mimir.data.db.entity.ProcedureActionEntity
import com.neptine.mimir.data.db.entity.ProcedurePageEntity
import com.neptine.mimir.data.db.entity.TaskEntity
import androidx.room.withTransaction
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从成功轨迹生成配方（设计文档 §2.5.5）。
 *
 * 规则是"单次成功绝不激活"：第一次只写 `CANDIDATE`（不参与检索），同一个 `stable_key`
 * 在**两个不同 task_id** 中成功、且两次都通过独立结果复验（`DONE`），第二次才转 `ACTIVE`。
 *
 * `DONE` 已对设置 App 的「关于手机」页可达（见 `ResultReverifier`）；其它 App 仍止于
 * `COMPLETED_UNVERIFIED`，此时只写 `MEMORY_SKIPPED` 审计，不把未复验的成功教给下一次。
 */
@Singleton
class MemoryWriter @Inject constructor(
    private val memoryDao: MemoryDao,
    private val versions: AppVersionReader,
    private val database: MimirDatabase,
) {

    /**
     * 功能：任务结束后按结局决定是否把本轮轨迹写成配方
     * 参数：task 任务；spec 任务约束；outcome 验证结论；rows 本轮动作审计（按时间正序）；
     *       entrySnapshot 任务入口页观察，用于生成页面签名；
     *       overlaySegments 本轮轨迹是否包含一次性遮挡
     * 返回：写入或跳过结论；完全没有确认动作时为 null（不留噪音）
     */
    suspend fun observeResult(
        task: TaskEntity,
        spec: TaskSpec,
        outcome: VerificationOutcome,
        rows: List<AuditEntity>,
        entrySnapshot: ObservationSnapshot,
        overlaySegments: Boolean = false,
    ): MemoryWriteResult? {
        val pages = buildPages(rows, spec.parameters)
        if (pages.isEmpty()) return null
        val steps = pages.flatMap { page ->
            page.actions.map { ProcedureStep(it.actionKind, it.anchor, page.pagePath, it.parameterSlots) }
        }
        val activity = pages.firstOrNull()?.pagePath.orEmpty()
        val stableKey = stableKeyOf(spec, activity, pages)
        val title = titleOf(spec)

        // §2.5.8：凭据的使用记录只进审计（哪条凭据、填充结果），不进配方。
        // 轨迹一旦经过凭据填充，页面内容可能包含登录态；整条不生成，避免把账号页教给下一次。
        if (rows.any { it.actionKind == ActionKind.CREDENTIAL.name }) {
            return MemoryWriteResult.Skipped(
                title = title,
                stepCount = steps.size,
                stableKey = stableKey,
                reason = "轨迹使用过凭据，按 §2.5.8 不进入配方",
            )
        }

        // §1.6.1 第 3/4 条：遮挡段的文案与关闭路径不得进入配方，也不能当作稳定页面路径。
        // 当前轨迹没有分段证据，无法证明"遮挡前后的任务页面连续"，因此整条不生成。
        if (overlaySegments) {
            return MemoryWriteResult.Skipped(
                title = title,
                stepCount = steps.size,
                stableKey = stableKey,
                reason = "轨迹包含一次性遮挡段，无法证明遮挡前后页面连续，按 §1.6.1 不生成配方",
            )
        }

        // 轨迹够不上一条路径时也要留一行审计：单动作任务不生成是设计行为，
        // 但"为什么没长记忆"必须查得到，不能表现成自动学习没实现。
        if (steps.size < MIN_STEPS) {
            return MemoryWriteResult.Skipped(
                title = title,
                stepCount = steps.size,
                stableKey = stableKey,
                reason = "轨迹只有 ${steps.size} 个确认动作，不足 $MIN_STEPS 步",
            )
        }
        if (steps.none { it.anchor != null }) {
            return MemoryWriteResult.Skipped(
                title = title,
                stepCount = steps.size,
                stableKey = stableKey,
                reason = "轨迹有 ${steps.size} 步，但没有任何可断言的 A1/A2 锚点",
            )
        }

        val procedure = buildProcedure(task, spec, pages, stableKey, entrySnapshot)
        val now = System.currentTimeMillis()

        if (outcome != VerificationOutcome.DONE && outcome != VerificationOutcome.DONE_READBACK) {
            // §2.5.5：只有通过复核的成功才计入激活次数；COMPLETED_UNVERIFIED 不算。
            // DONE_READBACK 计入：它同样是"两份不同快照都通过"，只是两路来自同一页面。
            return MemoryWriteResult.Skipped(
                title = procedure.title,
                stepCount = procedure.steps.size,
                stableKey = procedure.stableKey,
                reason = "任务以 $outcome 结束，未通过复核，按 §2.5.5 不计入激活",
            )
        }

        val existing = memoryDao.findByStableKey(procedure.stableKey, procedure.appVersionCode)
        if (existing == null) {
            val created = procedure.copy(
                id = UUID.randomUUID().toString(),
                status = MemoryStatus.CANDIDATE,
                successCount = 1,
                failCount = 0,
                confidence = laplace(1, 0),
                lastVerifiedAt = now,
                createdAt = now,
                lastTaskId = task.taskId,
            )
            persist(created)
            return MemoryWriteResult.Written(
                procedureId = created.id,
                title = created.title,
                status = created.status,
                successCount = created.successCount,
                detail = "首次成功，写入候选（不参与检索）",
            )
        }

        if (existing.lastTaskId == task.taskId) {
            return MemoryWriteResult.Skipped(
                title = procedure.title,
                stepCount = procedure.steps.size,
                stableKey = procedure.stableKey,
                reason = "同一任务重复验证，不重复计数",
            )
        }

        val success = existing.successCount + 1
        val promoted = success >= ACTIVATE_AFTER_SUCCESSES && existing.status == MemoryStatus.CANDIDATE.name
        val updated = existing.copy(
            successCount = success,
            status = if (promoted) MemoryStatus.ACTIVE.name else existing.status,
            confidence = laplace(success, existing.failCount),
            lastVerifiedAt = now,
            lastTaskId = task.taskId,
        )
        memoryDao.upsertProcedure(updated)
        return MemoryWriteResult.Written(
            procedureId = updated.id,
            title = updated.title,
            status = runCatching { MemoryStatus.valueOf(updated.status) }.getOrDefault(MemoryStatus.CANDIDATE),
            successCount = success,
            detail = when {
                promoted -> "第 $success 次跨任务成功，转为 ACTIVE"
                existing.status == MemoryStatus.ACTIVE.name -> "第 $success 次跨任务成功，保持 ACTIVE"
                else -> "第 $success 次成功，但仍未达激活条件"
            },
        )
    }

    /**
     * 功能：给"用上了但没帮上忙"的配方记一次失败
     * 参数：ids 本步注入过的配方标识
     * 返回：因此转入 STALE 的配方；没有触发降级时为空列表
     *
     * 统计触发器按 §2.5.4：连续 2 次后置条件未达成就移入待复核区，停止参与检索。
     * 计数仍然照加不误；降级只发生一次，已经在 STALE 的配方不会反复触发。
     */
    suspend fun recordStepFailure(ids: List<String>): List<Procedure> {
        val staled = mutableListOf<Procedure>()
        ids.distinct().forEach { id ->
            runCatching {
                memoryDao.bumpFailCount(id)
                val entity = memoryDao.findProcedure(id) ?: return@runCatching
                val procedure = ProcedureCodec.fromEntity(entity, emptyList())
                if (procedure.status != MemoryStatus.ACTIVE || procedure.failCount < STALE_AFTER_FAILURES) {
                    return@runCatching
                }
                val confidence = laplace(procedure.successCount, procedure.failCount)
                memoryDao.updateStatusAndConfidence(id, MemoryStatus.STALE.name, confidence)
                staled += procedure.copy(status = MemoryStatus.STALE, confidence = confidence)
            }
        }
        return staled
    }

    /**
     * 功能：从审计里提取页面与动作
     * 参数：rows 动作审计；parameters 写入时的任务参数
     * 返回：只含确认生效动作的页面列表（锚点已把动态值换成占位符）
     *
     * 只用"确认生效"的动作：`NO_EFFECT`/`UNKNOWN` 的动作进配方等于把失败路径教给下一次。
     * 参数值在这里就掩掉，而不是等到算稳定键时：锚点、稳定键、页面签名必须用同一套口径，
     * 否则同一路径换个收件人就会被当成另一条配方。
     */
    private fun buildPages(rows: List<AuditEntity>, parameters: Map<String, String>): List<ProcedurePage> {
        val drafts = rows
            .filter { it.outcome == EffectOutcome.CONFIRMED.name }
            .mapNotNull { row ->
                val kind = runCatching { ActionKind.valueOf(row.actionKind) }.getOrNull()
                    ?: return@mapNotNull null
                val anchor = TargetFingerprintCodec.decode(row.targetFingerprint)?.let(::anchorOf)
                val masked = anchor?.let { maskAnchor(it, parameters) }
                PageDraftAction(
                    action = ProcedureAction(
                        id = UUID.randomUUID().toString(),
                        actionKind = kind,
                        title = masked?.first?.text
                            ?: masked?.first?.resourceId
                            ?: kind.name,
                        anchor = masked?.first,
                        parameterSlots = masked?.second.orEmpty(),
                    ),
                    // 审计表的 sub_goal 列实际存的是当时的 Activity 名（recordAudit 传 snapshot.activityName），
                    // 不是模型生成的子目标；列名沿用旧字段，语义以写入侧为准。
                    pagePath = row.subGoal.takeIf { it.isNotEmpty() },
                    observationHash = row.observationHash,
                )
            }
        return ProcedureTree.groupByPageBreaks(drafts)
    }

    /**
     * 功能：把锚点里的动态参数值替换成占位符并收集槽位
     * 参数：anchor 原始锚点；parameters 写入时的任务参数
     * 返回：掩码后的锚点与实际命中的槽位
     */
    private fun maskAnchor(
        anchor: Anchor,
        parameters: Map<String, String>,
    ): Pair<Anchor, Map<String, String>> {
        if (parameters.isEmpty()) return anchor to emptyMap()
        val text = anchor.text?.let { MemoryParameters.mask(it, parameters) }
        val description = anchor.contentDescription?.let { MemoryParameters.mask(it, parameters) }
        val slots = text?.slots.orEmpty() + description?.slots.orEmpty()
        return anchor.copy(
            text = text?.text ?: anchor.text,
            contentDescription = description?.text ?: anchor.contentDescription,
        ) to slots
    }

    /**
     * 功能：把一组步骤组装成配方
     * 参数：task 任务；spec 任务约束；pages 已切好的页面；stableKey 稳定键；
     *       entrySnapshot 入口页观察
     * 返回：配方（状态为候选，尚未落库）
     *
     * 页面签名取入口页的静态文本：配方是"从这一页开始怎么点"，签名就该描述这一页。
     * 动态参数值在生成签名时已被掩掉（MemoryParameters.signature），不参与结构比对。
     */
    private fun buildProcedure(
        task: TaskEntity,
        spec: TaskSpec,
        pages: List<ProcedurePage>,
        stableKey: String,
        entrySnapshot: ObservationSnapshot,
    ): Procedure {
        val pageSignature = MemoryParameters.signature(entrySnapshot, spec.parameters)
        // 入口断言取入口页前几个锚点而不是只取一个：
        // 单锚点在列表页/首页很容易撞到广告位或重复文案，多个锚点一起断言才能证明"确实在这一页"。
        val precondition = pages.firstOrNull()?.actions.orEmpty()
            .mapNotNull { it.anchor }
            .distinctBy { it.resourceId ?: it.text ?: it.contentDescription }
            .take(MAX_PRECONDITION_ANCHORS)
        return Procedure(
            id = UUID.randomUUID().toString(),
            packageName = spec.packageName,
            taskType = spec.taskType,
            stableKey = stableKey,
            category = "",
            title = titleOf(spec),
            precondition = precondition,
            // 只有入口页拿得到真实页面文本；其余页面的签名留空，不拿动作文本冒充静态文本。
            pages = pages.mapIndexed { index, page ->
                if (index == 0) page.copy(signature = pageSignature) else page
            },
            postcondition = spec.completionContract.requiredFields,
            pageSignature = pageSignature,
            source = MemorySource.AUTO,
            status = MemoryStatus.CANDIDATE,
            successCount = 0,
            failCount = 0,
            confidence = laplace(0, 0),
            appVersionCode = versions.versionCodeOf(spec.packageName),
            validUntil = null,
            lastVerifiedAt = 0L,
            createdAt = System.currentTimeMillis(),
            lastTaskId = task.taskId,
        )
    }

    /**
     * 功能：把配方与它的树写进库
     * 参数：procedure 配方
     * 返回：无
     *
     * 主表与两张节点表放在同一事务里：只写主表成功、节点写一半会得到一棵残缺的树，
     * 下次检索命中后按残缺路径执行比没有配方更危险。
     * 旧 `steps_json` 列仍写扁平快照，供旧版本导出格式兼容，不作为读取真值。
     */
    private suspend fun persist(procedure: Procedure) {
        database.withTransaction {
            memoryDao.upsertProcedure(ProcedureCodec.toEntity(procedure))
            memoryDao.deleteActionsOf(procedure.id)
            memoryDao.deletePagesOf(procedure.id)
            if (procedure.pages.isEmpty()) return@withTransaction
            memoryDao.upsertPages(
                procedure.pages.mapIndexed { index, page ->
                    ProcedurePageEntity(
                        id = page.id,
                        procedureId = procedure.id,
                        title = page.title,
                        pagePath = page.pagePath,
                        signatureJson = ProcedureCodec.encodeStringsForTree(page.signature),
                        sortOrder = index,
                        createdAt = procedure.createdAt,
                    )
                },
            )
            memoryDao.upsertActions(
                procedure.pages.flatMap { page ->
                    page.actions.mapIndexed { index, action ->
                        ProcedureActionEntity(
                            id = action.id,
                            procedureId = procedure.id,
                            pageId = page.id,
                            actionKind = action.actionKind.name,
                            title = action.title,
                            anchorJson = ProcedureCodec.encodeAnchor(action.anchor),
                            slotsJson = ProcedureCodec.encodeSlots(action.parameterSlots),
                            sortOrder = index,
                        )
                    }
                },
            )
        }
    }

    /**
     * 功能：把目标指纹降级成配方锚点
     * 参数：fingerprint 动作目标指纹
     * 返回：锚点；只有坐标信息时为 null
     *
     * A5 绝对坐标不进配方：它必须带 TTL 且连续失败即禁用，自动生成时写它等于埋一颗定时炸弹。
     */
    private fun anchorOf(fingerprint: TargetFingerprint): Anchor? {
        val text = stableTextOf(fingerprint)
        if (!fingerprint.resourceId.isNullOrEmpty()) {
            return Anchor(
                level = AnchorLevel.A1,
                resourceId = fingerprint.resourceId,
                text = text,
                className = fingerprint.className,
            )
        }
        if (text.isNullOrEmpty()) return null
        return Anchor(
            level = AnchorLevel.A2,
            text = text,
            contentDescription = fingerprint.contentDescription,
            className = fingerprint.className,
        )
    }

    /**
     * 功能：取锚点的稳定文本
     * 参数：fingerprint 目标指纹
     * 返回：优先用静态文案；文案是动态值时退回无障碍描述；两者都没有时为 null
     *
     * "3 分钟前"、"12.5 元"、"99+" 这类文本下次几乎必然对不上，
     * 拿它当锚点等于写入一条注定失效的记忆。只在描述非空时才替换：
     * 两个都动态时保留原文，总比没有锚点强。
     */
    private fun stableTextOf(fingerprint: TargetFingerprint): String? {
        val raw = fingerprint.text?.takeIf { it.isNotBlank() }
        val description = fingerprint.contentDescription?.takeIf { it.isNotBlank() }
        if (raw == null) return description
        if (description != null && DYNAMIC_TEXT.containsMatchIn(raw)) return description
        return raw
    }

    /**
     * 功能：生成稳定键
     * 参数：spec 任务约束；activity 起始页面；pages 配方树
     * 返回：包名 | 任务类型 | 起始页面 | 页面序列 的规范化摘要
     *
     * 与 §2.5.2 的"包名 + 任务类型 + 页面路径 + 前后置条件"对齐；后置条件在这里恒为
     * 完成契约字段，不额外拼进键里，避免同一路径因字段顺序不同生成两条候选。
     *
     * 动作细节（锚点文本、点击顺序）**不**进键：四层树里页面才是稳定边界，
     * 把动作也拼进去会让"同一流程换了个按钮文案"变成另一条配方，自动学习永远积累不起来。
     */
    private fun stableKeyOf(spec: TaskSpec, activity: String, pages: List<ProcedurePage>): String {
        val path = pages.joinToString(">") { page ->
            page.pagePath?.substringAfterLast('.') ?: page.title
        }
        return listOf(spec.packageName, spec.taskType, activity, path).joinToString("|")
    }

    /**
     * 功能：生成配方标题
     * 参数：spec 任务约束
     * 返回：用户原话截断后的短标题；原话为空时给一个通用名
     */
    private fun titleOf(spec: TaskSpec): String =
        spec.inputText.trim().take(TITLE_LIMIT).ifEmpty { "自动记录的操作" }

    /**
     * 功能：算 laplace 平滑后的成功率
     * 参数：success 成功次数；fail 失败次数
     * 返回：取值 (0,1) 的置信度种子
     *
     * 不直接用 success/(success+fail)：1/1 = 1.0 会让"只成功过一次"看起来和"成功十次"一样可信。
     */
    private fun laplace(success: Int, fail: Int): Double =
        (success + 1.0) / (success + fail + 2.0)

    private companion object {
        /** 少于两个动作不构成"操作路径"，单个点击没有记忆价值。 */
        const val MIN_STEPS = 2

        /** 达到多少次跨任务成功才从候选转 ACTIVE（§2.5.5）。 */
        const val ACTIVATE_AFTER_SUCCESSES = 2

        /** 连续多少次注入后仍然后置条件未达成即转 STALE（§2.5.4 统计触发器）。 */
        const val STALE_AFTER_FAILURES = 2

        /** 自动标题的最大长度。 */
        const val TITLE_LIMIT = 24

        /**
         * 入口前置断言最多取几个锚点。
         *
         * 取 3 个：单锚点在列表页会撞到重复文案或广告位，太多则入口页稍一改版就断言失败。
         */
        const val MAX_PRECONDITION_ANCHORS = 3

        /**
         * 动态文本特征：纯数字/金额、时间、相对时间、日期。
         *
         * 只用于判断"这条文案能不能当锚点"，不做完整解析；误判的代价只是退回无障碍描述。
         */
        val DYNAMIC_TEXT = Regex(
            "^(\\s*[\\d\\s.,:：%+\\-]+\\s*)$" +
                "|(\\d{1,2}[:：]\\d{2})" +
                "|(\\d+\\s*(分钟|小时|秒|天|条|个|人|次|元|折|km|m)\\s*(前|后|以内|起)?)" +
                "|(\\d{1,4}[-/年]\\d{1,2}[-/月]\\d{1,2})",
            RegexOption.IGNORE_CASE,
        )
    }
}
