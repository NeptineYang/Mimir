package com.neptine.mimir.ui.viewmodel

import com.neptine.mimir.ui.i18n.tStatic
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.neptine.mimir.agent.MemoryReverifier
import com.neptine.mimir.agent.ReverifyVerdict
import com.neptine.mimir.core.contract.Anchor
import com.neptine.mimir.core.contract.AnchorLevel
import com.neptine.mimir.core.contract.MemorySource
import com.neptine.mimir.core.contract.MemoryStatus
import com.neptine.mimir.core.contract.Preference
import com.neptine.mimir.core.contract.PreferenceCategory
import com.neptine.mimir.core.contract.Procedure
import com.neptine.mimir.core.contract.ProcedurePage
import com.neptine.mimir.data.db.MimirDatabase
import com.neptine.mimir.data.db.dao.MemoryDao
import com.neptine.mimir.data.db.entity.PreferenceEntity
import com.neptine.mimir.data.db.entity.ProcedureActionEntity
import com.neptine.mimir.data.db.entity.ProcedureEntity
import com.neptine.mimir.data.db.entity.ProcedurePageEntity
import com.neptine.mimir.data.memory.ProcedureCodec
import com.neptine.mimir.data.memory.ProcedureTree
import com.neptine.mimir.service.AgentForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 配方列表项与详情的展示模型。 */
data class ProcedureRow(
    val id: String,
    val title: String,
    val packageName: String,
    val taskType: String,
    val status: String,
    val source: String,
    val confidence: Double,
    val successCount: Int,
    val failCount: Int,
    val lastVerifiedAt: Long,
    val anchorsText: String,
    val postconditionText: String,
    val signatureText: String,
    /** 四层树：App → 任务 → 页面 → 动作；列表只展示页面数与动作数，编辑器用整棵树。 */
    val pages: List<ProcedurePage>,
) {
    /** 页面数量（第三层节点数）。 */
    val pageCount: Int get() = pages.size

    /** 动作数量（第四层节点数）。 */
    val actionCount: Int get() = pages.sumOf { it.actions.size }
}

/** 偏好列表项的展示模型。 */
data class PreferenceRow(
    val id: String,
    val category: PreferenceCategory,
    val subject: String?,
    val title: String,
    val scope: String,
    val content: String,
    val value: String,
    val confidence: Double,
    val source: String,
    val status: String,
)

/** 编辑中的偏好草稿。 */
data class PreferenceDraft(
    val id: String?,
    val category: PreferenceCategory,
    val subject: String,
    val title: String,
    val content: String,
    val value: String,
    val scope: String,
)

/** 编辑中的配方草稿；列表与表单共用同一份，避免两处状态不同步。 */
data class ProcedureDraft(
    val id: String?,
    val title: String,
    val packageName: String,
    val taskType: String,
    val anchors: String,
    val postcondition: String,
    val signature: String,
    /** 树直接作为草稿的一部分：页面与动作的增删改都作用在它上面，保存时整树替换。 */
    val pages: List<ProcedurePage> = emptyList(),
)

/** 记忆页状态。 */
data class MemoryUiState(
    val procedures: List<ProcedureRow>,
    val preferences: List<PreferenceRow>,
    val draft: ProcedureDraft?,
    val message: String?,
    val error: String?,
    /** 正在立即重验的配方；为空表示没有核对在跑。 */
    val verifyingId: String? = null,
    /** 正在编辑的偏好；为空表示没有打开偏好表单。 */
    val preferenceDraft: PreferenceDraft? = null,
)

/**
 * 记忆页状态持有者（设计文档 §2.5.7 白盒编辑）。
 *
 * 列出来、改状态、删、导入导出，以及"立即重验"的入口：重验本身交给前台服务，
 * 用一块私有虚拟屏读出当前页面后核对锚点与签名——记忆页自己不起屏，也不假装验过。
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryDao: MemoryDao,
    private val database: MimirDatabase,
    private val reverifier: MemoryReverifier,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val draftFlow = MutableStateFlow<ProcedureDraft?>(null)
    private val preferenceDraftFlow = MutableStateFlow<PreferenceDraft?>(null)
    private val messageFlow = MutableStateFlow<String?>(null)
    private val errorFlow = MutableStateFlow<String?>(null)
    private val verifyingFlow = MutableStateFlow<String?>(null)

    init {
        // 重验在服务里跑，结果通过单例状态流回到页面；拿到终局就更新提示并清掉状态，
        // 避免重新进入记忆页时把上一次的结论再弹一遍。
        viewModelScope.launch {
            reverifier.status.collect { status ->
                val id = status.procedureId
                verifyingFlow.value = id.takeIf { status.running }
                val verdict = status.verdict
                if (id != null && !status.running && verdict != null) {
                    val detail = status.detail.orEmpty()
                    when (verdict) {
                        ReverifyVerdict.PASSED -> {
                            errorFlow.value = null
                            messageFlow.value = "重验通过：%1\$s".tStatic(detail)
                        }
                        ReverifyVerdict.FAILED -> {
                            errorFlow.value = "重验未通过，配方已转 STALE：%1\$s".tStatic(detail)
                        }
                        ReverifyVerdict.UNAVAILABLE -> {
                            errorFlow.value = "无法核对：%1\$s".tStatic(detail)
                        }
                    }
                    reverifier.acknowledge()
                }
            }
        }
    }

    /**
     * 配方与其树的组合流。
     *
     * 三个 Flow 合成一次：编辑页需要整片森林，逐条查节点会变成 N+1；
     * 合成放在这里而不是 UI 层，列表与草稿就能共享同一份树。
     */
    private val treeFlow = combine(
        memoryDao.observeProcedures(),
        memoryDao.observeAllPages(),
        memoryDao.observeAllActions(),
    ) { procedures, pages, actions ->
        val pagesByProcedure = pages.groupBy { it.procedureId }
        val actionsByProcedure = actions.groupBy { it.procedureId }
        procedures.map { entity ->
            ProcedureWithTree(
                entity = entity,
                pages = ProcedureTree.assemble(
                    pages = pagesByProcedure[entity.id].orEmpty(),
                    actions = actionsByProcedure[entity.id].orEmpty(),
                    anchorOf = ProcedureCodec::decodeAnchorOrNull,
                    slotsOf = ProcedureCodec::decodeSlots,
                ),
            )
        }
    }

    /** 不含进行中状态的基础页面状态。 */
    private val baseState = combine(
        treeFlow,
        memoryDao.observePreferences(),
        draftFlow,
        messageFlow,
        errorFlow,
    ) { rows, preferences, draft, message, error ->
        MemoryUiState(
            procedures = rows.map { it.toRow() },
            preferences = preferences.map { it.toRow() },
            draft = draft,
            message = message,
            error = error,
        )
    }

    /** 页面状态。 */
    val state: StateFlow<MemoryUiState> = combine(
        baseState,
        verifyingFlow,
        preferenceDraftFlow,
    ) { base, verifyingId, preferenceDraft ->
        base.copy(verifyingId = verifyingId, preferenceDraft = preferenceDraft)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MemoryUiState(emptyList(), emptyList(), null, null, null),
        )

    /**
     * 功能：打开新建表单
     * 参数：无
     * 返回：无
     */
    fun startCreate() {
        errorFlow.value = null
        draftFlow.value = ProcedureDraft(
            id = null,
            title = "",
            packageName = "",
            taskType = "",
            anchors = "",
            postcondition = "",
            signature = "",
            // 新建时先给一个空页面：树至少要有第三层，否则用户不知道该从哪里加动作。
            pages = listOf(newPage()),
        )
    }

    /**
     * 功能：打开编辑表单
     * 参数：id 配方标识
     * 返回：无
     */
    fun startEdit(id: String) {
        viewModelScope.launch {
            val entity = memoryDao.findProcedure(id) ?: return@launch
            errorFlow.value = null
            draftFlow.value = ProcedureCodec.fromEntity(entity, loadTree(id)).toDraft()
        }
    }

    /**
     * 功能：更新草稿
     * 参数：draft 新草稿
     * 返回：无
     */
    fun updateDraft(draft: ProcedureDraft) {
        draftFlow.value = draft
    }

    /**
     * 功能：关闭表单
     * 参数：无
     * 返回：无
     */
    fun cancelDraft() {
        draftFlow.value = null
        errorFlow.value = null
    }

    /**
     * 功能：保存草稿
     * 参数：无
     * 返回：无
     *
     * 树按草稿原样写：页面与动作的增删改都已作用在 `draft.pages` 上，
     * 保存时整树替换（同一事务里先删后插），不做增量比对。
     */
    fun saveDraft() {
        val draft = draftFlow.value ?: return
        if (draft.title.isBlank() || draft.packageName.isBlank()) {
            errorFlow.value = "标题与包名不能为空".tStatic()
            return
        }
        viewModelScope.launch {
            val existing = draft.id?.let { memoryDao.findProcedure(it) }
            val source = existing?.let { runCatching { MemorySource.valueOf(it.source) }.getOrNull() }
                ?: MemorySource.USER_WRITTEN
            // 先归一化再算键：stable_key 与实体里的 taskType 必须用同一个值，
            // 否则手写配方会写成"包名||标题"而实体是 GENERAL，跨路径去重直接失效。
            val packageName = draft.packageName.trim()
            val taskType = draft.taskType.trim().ifEmpty { DEFAULT_TASK_TYPE }
            val procedure = Procedure(
                id = draft.id ?: UUID.randomUUID().toString(),
                packageName = packageName,
                taskType = taskType,
                stableKey = stableKeyOf(packageName, taskType, draft.title.trim()),
                category = existing?.category.orEmpty(),
                title = draft.title.trim(),
                precondition = draft.anchors.toAnchors(),
                pages = draft.pages,
                postcondition = draft.postcondition.lines().map(String::trim).filter(String::isNotEmpty),
                pageSignature = draft.signature.lines().map(String::trim).filter(String::isNotEmpty),
                source = source,
                status = existing?.let { runCatching { MemoryStatus.valueOf(it.status) }.getOrNull() }
                    ?: MemoryStatus.ACTIVE,
                successCount = existing?.successCount ?: 0,
                failCount = existing?.failCount ?: 0,
                confidence = if (source == MemorySource.USER_WRITTEN) 1.0 else existing?.confidence ?: 0.5,
                appVersionCode = versionCodeOf(packageName),
                validUntil = existing?.validUntil,
                lastVerifiedAt = existing?.lastVerifiedAt ?: 0L,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                lastTaskId = existing?.lastTaskId,
            )
            runCatching { persist(procedure) }
                .onSuccess {
                    draftFlow.value = null
                    messageFlow.value = "已保存「%1\$s」".tStatic(procedure.title)
                }
                .onFailure { errorFlow.value = it.message ?: "保存失败".tStatic() }
        }
    }

    /**
     * 功能：切换配方启用状态
     * 参数：id 配方标识
     * 返回：无
     *
     * 在 ACTIVE 与 DISABLED 之间切换，不动 STALE：STALE 是"用之前核对没过"的结论，
     * 只能靠重新验证恢复，不能靠点一下按钮洗白。
     */
    fun toggleStatus(id: String) {
        viewModelScope.launch {
            val entity = memoryDao.findProcedure(id) ?: return@launch
            if (entity.status == MemoryStatus.STALE.name) {
                errorFlow.value = "该配方已失效，请先立即重验；通过后会自动恢复启用".tStatic()
                return@launch
            }
            val next = if (entity.status == MemoryStatus.ACTIVE.name) {
                MemoryStatus.DISABLED.name
            } else {
                MemoryStatus.ACTIVE.name
            }
            memoryDao.upsertProcedure(entity.copy(status = next))
            messageFlow.value = if (next == MemoryStatus.ACTIVE.name) "已启用".tStatic() else "已停用".tStatic()
        }
    }

    /**
     * 功能：删除一条配方
     * 参数：id 配方标识
     * 返回：无
     *
     * 主表与两张节点表在同一事务里删；漏掉节点会留下无法再被引用的孤儿页面/动作。
     */
    fun deleteProcedure(id: String) {
        viewModelScope.launch {
            database.withTransaction {
                memoryDao.deleteProcedure(id)
                memoryDao.deleteActionsOf(id)
                memoryDao.deletePagesOf(id)
            }
            messageFlow.value = "已删除".tStatic()
        }
    }

    /**
     * 功能：打开新建偏好表单
     * 参数：无
     * 返回：无
     */
    fun startCreatePreference() {
        errorFlow.value = null
        preferenceDraftFlow.value = PreferenceDraft(
            id = null,
            category = PreferenceCategory.GENERAL,
            subject = "",
            title = "",
            content = "",
            value = "",
            scope = "",
        )
    }

    /**
     * 功能：打开编辑偏好表单
     * 参数：id 偏好标识
     * 返回：无
     */
    fun startEditPreference(id: String) {
        viewModelScope.launch {
            val entity = memoryDao.findPreference(id) ?: return@launch
            errorFlow.value = null
            preferenceDraftFlow.value = ProcedureCodec.fromEntity(entity).toDraft()
        }
    }

    /**
     * 功能：更新偏好草稿
     * 参数：draft 新草稿
     * 返回：无
     */
    fun updatePreferenceDraft(draft: PreferenceDraft) {
        preferenceDraftFlow.value = draft
    }

    /**
     * 功能：关闭偏好表单
     * 参数：无
     * 返回：无
     */
    fun cancelPreferenceDraft() {
        preferenceDraftFlow.value = null
        errorFlow.value = null
    }

    /**
     * 功能：保存偏好
     * 参数：无
     * 返回：无
     *
     * 手写与"任务后确认"共用这一条保存路径：新建时来源记 USER_WRITTEN（置信度 1.0），
     * 从任务上下文预填的草稿可以把来源改成 USER_TAUGHT（0.9），由调用方在草稿里带过来。
     */
    fun savePreferenceDraft() {
        val draft = preferenceDraftFlow.value ?: return
        if (draft.title.isBlank() || draft.content.isBlank()) {
            errorFlow.value = "标题与内容不能为空".tStatic()
            return
        }
        viewModelScope.launch {
            val existing = draft.id?.let { memoryDao.findPreference(it) }
            val preference = Preference(
                id = draft.id ?: UUID.randomUUID().toString(),
                scope = draft.scope.trim().ifEmpty { null },
                category = draft.category,
                subject = draft.subject.trim().ifEmpty { null },
                title = draft.title.trim(),
                content = draft.content.trim(),
                // 结构化值为空时退回正文：后续按值去重/匹配至少要有一份可用数据
                value = draft.value.trim().ifEmpty { draft.content.trim() },
                confidence = existing?.confidence ?: DEFAULT_PREFERENCE_CONFIDENCE,
                source = existing?.let { runCatching { MemorySource.valueOf(it.source) }.getOrNull() }
                    ?: MemorySource.USER_WRITTEN,
                status = existing?.let { runCatching { MemoryStatus.valueOf(it.status) }.getOrNull() }
                    ?: MemoryStatus.ACTIVE,
                hitCount = existing?.hitCount ?: 0,
                lastUsedAt = existing?.lastUsedAt ?: 0L,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
            runCatching { memoryDao.upsertPreference(ProcedureCodec.toEntity(preference)) }
                .onSuccess {
                    preferenceDraftFlow.value = null
                    messageFlow.value = "已保存偏好「%1\$s」".tStatic(preference.title)
                }
                .onFailure { errorFlow.value = it.message ?: "保存失败".tStatic() }
        }
    }

    /**
     * 功能：切换偏好启停
     * 参数：id 偏好标识
     * 返回：无
     */
    fun togglePreference(id: String) {
        viewModelScope.launch {
            val entity = memoryDao.findPreference(id) ?: return@launch
            val next = if (entity.status == MemoryStatus.ACTIVE.name) {
                MemoryStatus.DISABLED.name
            } else {
                MemoryStatus.ACTIVE.name
            }
            memoryDao.updatePreferenceStatus(id, next)
        }
    }

    /**
     * 功能：删除一条策略
     * 参数：id 策略标识
     * 返回：无
     */
    fun deletePreference(id: String) {
        viewModelScope.launch {
            memoryDao.deletePreference(id)
            messageFlow.value = "已删除".tStatic()
        }
    }

    /**
     * 功能：把全部配方导出到用户选定的文件
     * 参数：uri 目标文件
     * 返回：无
     */
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val procedures = loadAllProcedures()
                val preferences = memoryDao.observePreferences().first()
                    .map(ProcedureCodec::fromEntity)
                val text = ProcedureCodec.encodeBundle(procedures, preferences)
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(text.toByteArray())
                } ?: error("无法写入所选文件".tStatic())
                procedures.size + preferences.size
            }.onSuccess { count -> messageFlow.value = "已导出 %1\$d 条记忆（配方与偏好）".tStatic(count) }
                .onFailure { errorFlow.value = it.message ?: "导出失败".tStatic() }
        }
    }

    /**
     * 功能：从用户选定的文件导入配方
     * 参数：uri 源文件
     * 返回：无
     */
    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: error("无法读取所选文件".tStatic())
                val bundle = ProcedureCodec.decodeBundle(text)
                    ?: error("不是 Mimir 导出的记忆文件".tStatic())
                // 逐条整树写入：导入文件里带的是 pages，旧文件只有 steps，
                // ProcedureCodec 已在解码时按 pagePath 补好树。
                bundle.procedures.forEach { persist(it) }
                bundle.preferences.forEach {
                    memoryDao.upsertPreference(ProcedureCodec.toEntity(it))
                }
                bundle.procedures.size + bundle.preferences.size
            }.onSuccess { count -> messageFlow.value = "已导入 %1\$d 条记忆（配方与偏好）".tStatic(count) }
                .onFailure { errorFlow.value = it.message ?: "导入失败".tStatic() }
        }
    }

    /**
     * 功能：立即重验一条配方
     * 参数：id 配方标识
     * 返回：无
     *
     * 走前台服务而不是当前 ViewModel：核对要建私有副屏、拉起目标 App，
     * 用户在等结果期间可能切走页面，协程必须活在服务里才不会被回收。
     */
    fun reverify(id: String) {
        errorFlow.value = null
        messageFlow.value = null
        verifyingFlow.value = id
        AgentForegroundService.reverifyMemory(context, id)
    }

    /**
     * 功能：清掉提示条
     * 参数：无
     * 返回：无
     */
    fun dismissNotice() {
        messageFlow.value = null
        errorFlow.value = null
    }

    /**
     * 功能：把配方与树整体写库
     * 参数：procedure 配方
     * 返回：无
     *
     * 事务内先删后插：保存是"整树替换"，几十个节点的规模做增量比对不值得，
     * 反而容易在删除/移动后留下错位的 sort_order。
     */
    private suspend fun persist(procedure: Procedure) {
        database.withTransaction {
            memoryDao.upsertProcedure(ProcedureCodec.toEntity(procedure))
            memoryDao.deleteActionsOf(procedure.id)
            memoryDao.deletePagesOf(procedure.id)
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
     * 功能：读取一条配方的树
     * 参数：procedureId 配方标识
     * 返回：页面与动作组成的树
     */
    private suspend fun loadTree(procedureId: String): List<ProcedurePage> =
        ProcedureTree.assemble(
            pages = memoryDao.findPages(procedureId),
            actions = memoryDao.findActions(procedureId),
            anchorOf = ProcedureCodec::decodeAnchorOrNull,
            slotsOf = ProcedureCodec::decodeSlots,
        )

    /**
     * 功能：读取全部配方及其树
     * 参数：无
     * 返回：配方列表；导出与导入校验用
     */
    private suspend fun loadAllProcedures(): List<Procedure> =
        memoryDao.observeProcedures().first().map { entity ->
            ProcedureCodec.fromEntity(entity, loadTree(entity.id))
        }

    /**
     * 功能：新建一个空页面节点
     * 参数：无
     * 返回：带随机标识的页面
     */
    private fun newPage(): ProcedurePage = ProcedurePage(
        id = UUID.randomUUID().toString(),
        title = "新页面",
        pagePath = null,
    )

    /**
     * 功能：算稳定键
     * 参数：packageName 包名；taskType 已归一化的任务类型；title 标题
     * 返回：包名 + 任务类型 + 标题的规范化摘要
     *
     * 手写配方的稳定键只取这三段：页面路径与前后置条件在编辑期拿不到，
     * 硬拼空串会让同一份配方在不同页面各生成一条。
     */
    private fun stableKeyOf(packageName: String, taskType: String, title: String): String =
        listOf(packageName, taskType, title).joinToString("|")

    /**
     * 功能：读目标 App 的当前版本号
     * 参数：packageName 包名
     * 返回：longVersionCode；读不到时为 0
     */
    private fun versionCodeOf(packageName: String): Int = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

    private companion object {
        /** 没填任务类型时的兜底值，与 Planner 的默认值保持一致。 */
        const val DEFAULT_TASK_TYPE = "GENERAL"

        /** 手写偏好的初始置信度；任务后确认的草稿由调用方改来源与置信度。 */
        const val DEFAULT_PREFERENCE_CONFIDENCE = 1.0
    }
}

/**
 * 配方行与其树的组合。
 *
 * @property entity 配方主表行
 * @property pages 该配方的页面/动作树
 */
private data class ProcedureWithTree(
    val entity: ProcedureEntity,
    val pages: List<ProcedurePage>,
)

/**
 * 功能：把配方行与其树转成展示模型
 * 参数：无（扩展函数）
 * 返回：ProcedureRow
 */
private fun ProcedureWithTree.toRow(): ProcedureRow {
    val procedure = ProcedureCodec.fromEntity(entity, pages)
    return ProcedureRow(
        id = entity.id,
        title = entity.title,
        packageName = entity.packageName,
        taskType = entity.taskType,
        status = entity.status,
        source = entity.source,
        confidence = entity.confidence,
        successCount = entity.successCount,
        failCount = entity.failCount,
        lastVerifiedAt = entity.lastVerifiedAt,
        anchorsText = procedure.precondition.joinToString("\n") { it.text ?: it.resourceId.orEmpty() },
        postconditionText = procedure.postcondition.joinToString("\n"),
        signatureText = procedure.pageSignature.joinToString("\n"),
        pages = pages,
    )
}

/**
 * 功能：把策略行转成展示模型
 * 参数：无（扩展函数）
 * 返回：PreferenceRow
 */
private fun PreferenceEntity.toRow(): PreferenceRow = PreferenceRow(
    id = id,
    category = runCatching { PreferenceCategory.valueOf(category) }
        .getOrDefault(PreferenceCategory.GENERAL),
    subject = subject,
    title = title,
    scope = scope ?: "全局",
    content = content,
    value = value,
    confidence = confidence,
    source = source,
    status = status,
)

/**
 * 功能：把偏好转成编辑草稿
 * 参数：无（扩展函数）
 * 返回：草稿
 */
private fun Preference.toDraft(): PreferenceDraft = PreferenceDraft(
    id = id,
    category = category,
    subject = subject.orEmpty(),
    title = title,
    content = content,
    value = value,
    scope = scope.orEmpty(),
)

/**
 * 功能：把配方转成编辑草稿
 * 参数：无（扩展函数）
 * 返回：草稿
 */
private fun Procedure.toDraft(): ProcedureDraft = ProcedureDraft(
    id = id,
    title = title,
    packageName = packageName,
    taskType = taskType,
    anchors = precondition.joinToString("\n") { it.text ?: it.resourceId.orEmpty() },
    postcondition = postcondition.joinToString("\n"),
    signature = pageSignature.joinToString("\n"),
    pages = pages,
)

/**
 * 功能：把多行文本转成 A2 锚点列表
 * 参数：无（扩展函数，作用于多行文本）
 * 返回：锚点列表
 */
private fun String.toAnchors(): List<Anchor> =
    lines().map(String::trim).filter(String::isNotEmpty)
        .map { Anchor(level = AnchorLevel.A2, text = it) }
