package com.neptine.mimir.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neptine.mimir.agent.MemoryReverifier
import com.neptine.mimir.agent.ReverifyVerdict
import com.neptine.mimir.core.contract.ActionKind
import com.neptine.mimir.core.contract.Anchor
import com.neptine.mimir.core.contract.AnchorLevel
import com.neptine.mimir.core.contract.MemorySource
import com.neptine.mimir.core.contract.MemoryStatus
import com.neptine.mimir.core.contract.Procedure
import com.neptine.mimir.core.contract.ProcedureStep
import com.neptine.mimir.data.db.dao.MemoryDao
import com.neptine.mimir.data.db.entity.PreferenceEntity
import com.neptine.mimir.data.db.entity.ProcedureEntity
import com.neptine.mimir.data.memory.ProcedureCodec
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
    val stepsText: String,
    val postconditionText: String,
    val signatureText: String,
)

/** 策略列表项的展示模型。 */
data class PreferenceRow(
    val id: String,
    val title: String,
    val scope: String,
    val content: String,
    val source: String,
)

/** 编辑中的配方草稿；列表与表单共用同一份，避免两处状态不同步。 */
data class ProcedureDraft(
    val id: String?,
    val title: String,
    val packageName: String,
    val taskType: String,
    val anchors: String,
    val steps: String,
    val postcondition: String,
    val signature: String,
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
    private val reverifier: MemoryReverifier,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val draftFlow = MutableStateFlow<ProcedureDraft?>(null)
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
                            messageFlow.value = "重验通过：$detail"
                        }
                        ReverifyVerdict.FAILED -> {
                            errorFlow.value = "重验未通过，配方已转 STALE：$detail"
                        }
                        ReverifyVerdict.UNAVAILABLE -> {
                            errorFlow.value = "无法核对：$detail"
                        }
                    }
                    reverifier.acknowledge()
                }
            }
        }
    }

    /** 页面状态。 */
    val state: StateFlow<MemoryUiState> = combine(
        combine(
            memoryDao.observeProcedures(),
            memoryDao.observePreferences(),
            draftFlow,
            messageFlow,
            errorFlow,
        ) { procedures, preferences, draft, message, error ->
            MemoryUiState(
                procedures = procedures.map { it.toRow() },
                preferences = preferences.map { it.toRow() },
                draft = draft,
                message = message,
                error = error,
            )
        },
        verifyingFlow,
    ) { base, verifyingId ->
        base.copy(verifyingId = verifyingId)
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
            steps = "",
            postcondition = "",
            signature = "",
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
            draftFlow.value = ProcedureCodec.fromEntity(entity).toDraft()
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
     * 草稿里的步骤按"每行一个动作"读：`TAP 领取` / `TEXT 搜索框` / `BACK`。
     * 只有 TAP 与 TEXT 需要文本（写成 A2 锚点），其余动作不挂锚点。
     */
    fun saveDraft() {
        val draft = draftFlow.value ?: return
        if (draft.title.isBlank() || draft.packageName.isBlank()) {
            errorFlow.value = "标题与包名不能为空"
            return
        }
        val steps = parseSteps(draft.steps)
        if (steps == null) {
            errorFlow.value = "步骤格式不对：每行一个动作，例如 `TAP 领取`、`TEXT 搜索框`、`BACK`"
            return
        }
        viewModelScope.launch {
            val existing = draft.id?.let { memoryDao.findProcedure(it) }
            val source = existing?.let { runCatching { MemorySource.valueOf(it.source) }.getOrNull() }
                ?: MemorySource.USER_WRITTEN
            val procedure = Procedure(
                id = draft.id ?: UUID.randomUUID().toString(),
                packageName = draft.packageName.trim(),
                taskType = draft.taskType.trim().ifEmpty { DEFAULT_TASK_TYPE },
                stableKey = stableKeyOf(draft),
                category = existing?.category.orEmpty(),
                title = draft.title.trim(),
                precondition = draft.anchors.toAnchors(),
                steps = steps,
                postcondition = draft.postcondition.lines().map(String::trim).filter(String::isNotEmpty),
                pageSignature = draft.signature.lines().map(String::trim).filter(String::isNotEmpty),
                source = source,
                status = existing?.let { runCatching { MemoryStatus.valueOf(it.status) }.getOrNull() }
                    ?: MemoryStatus.ACTIVE,
                successCount = existing?.successCount ?: 0,
                failCount = existing?.failCount ?: 0,
                confidence = if (source == MemorySource.USER_WRITTEN) 1.0 else existing?.confidence ?: 0.5,
                appVersionCode = versionCodeOf(draft.packageName.trim()),
                validUntil = existing?.validUntil,
                lastVerifiedAt = existing?.lastVerifiedAt ?: 0L,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                lastTaskId = existing?.lastTaskId,
            )
            runCatching { memoryDao.upsertProcedure(ProcedureCodec.toEntity(procedure)) }
                .onSuccess {
                    draftFlow.value = null
                    messageFlow.value = "已保存「${procedure.title}」"
                }
                .onFailure { errorFlow.value = it.message ?: "保存失败" }
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
                errorFlow.value = "该配方已失效，请先立即重验；通过后会自动恢复启用"
                return@launch
            }
            val next = if (entity.status == MemoryStatus.ACTIVE.name) {
                MemoryStatus.DISABLED.name
            } else {
                MemoryStatus.ACTIVE.name
            }
            memoryDao.upsertProcedure(entity.copy(status = next))
            messageFlow.value = if (next == MemoryStatus.ACTIVE.name) "已启用" else "已停用"
        }
    }

    /**
     * 功能：删除一条配方
     * 参数：id 配方标识
     * 返回：无
     */
    fun deleteProcedure(id: String) {
        viewModelScope.launch {
            memoryDao.deleteProcedure(id)
            messageFlow.value = "已删除"
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
            messageFlow.value = "已删除"
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
                val procedures = memoryDao.observeProcedures().first().map(ProcedureCodec::fromEntity)
                val text = ProcedureCodec.encodeBundle(procedures)
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(text.toByteArray())
                } ?: error("无法写入所选文件")
                procedures.size
            }.onSuccess { count -> messageFlow.value = "已导出 $count 条配方" }
                .onFailure { errorFlow.value = it.message ?: "导出失败" }
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
                    ?: error("无法读取所选文件")
                val procedures = ProcedureCodec.decodeBundle(text)
                    ?: error("不是 Mimir 导出的记忆文件")
                procedures.forEach { memoryDao.upsertProcedure(ProcedureCodec.toEntity(it)) }
                procedures.size
            }.onSuccess { count -> messageFlow.value = "已导入 $count 条配方" }
                .onFailure { errorFlow.value = it.message ?: "导入失败" }
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
     * 功能：解析步骤文本
     * 参数：text 每行一个动作
     * 返回：步骤列表；有空行外的非法行时返回 null
     */
    private fun parseSteps(text: String): List<ProcedureStep>? {
        val steps = mutableListOf<ProcedureStep>()
        text.lines().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val kindName = line.substringBefore(' ').uppercase()
            val label = line.substringAfter(' ', "").trim()
            val kind = runCatching { ActionKind.valueOf(kindName) }.getOrNull() ?: return null
            if (kind == ActionKind.TAP || kind == ActionKind.TEXT) {
                if (label.isEmpty()) return null
            }
            steps += ProcedureStep(
                actionKind = kind,
                anchor = label.takeIf { it.isNotEmpty() }?.let {
                    Anchor(level = AnchorLevel.A2, text = it)
                },
                pagePath = null,
            )
        }
        return steps.ifEmpty { null }
    }

    /**
     * 功能：算稳定键
     * 参数：draft 草稿
     * 返回：包名 + 任务类型 + 标题的规范化摘要
     *
     * 手写配方的稳定键只取这三段：页面路径与前后置条件在编辑期拿不到，
     * 硬拼空串会让同一份配方在不同页面各生成一条。
     */
    private fun stableKeyOf(draft: ProcedureDraft): String =
        listOf(draft.packageName.trim(), draft.taskType.trim(), draft.title.trim()).joinToString("|")

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
    }
}

/**
 * 功能：把配方行转成展示模型
 * 参数：无（扩展函数）
 * 返回：ProcedureRow
 */
private fun ProcedureEntity.toRow(): ProcedureRow {
    val procedure = ProcedureCodec.fromEntity(this)
    return ProcedureRow(
        id = id,
        title = title,
        packageName = packageName,
        taskType = taskType,
        status = status,
        source = source,
        confidence = confidence,
        successCount = successCount,
        failCount = failCount,
        lastVerifiedAt = lastVerifiedAt,
        anchorsText = procedure.precondition.joinToString("\n") { it.text ?: it.resourceId.orEmpty() },
        stepsText = procedure.steps.joinToString("\n") { step ->
            val label = step.anchor?.text ?: step.anchor?.contentDescription.orEmpty()
            if (label.isEmpty()) step.actionKind.name else "${step.actionKind.name} $label"
        },
        postconditionText = procedure.postcondition.joinToString("\n"),
        signatureText = procedure.pageSignature.joinToString("\n"),
    )
}

/**
 * 功能：把策略行转成展示模型
 * 参数：无（扩展函数）
 * 返回：PreferenceRow
 */
private fun PreferenceEntity.toRow(): PreferenceRow = PreferenceRow(
    id = id,
    title = title,
    scope = scope ?: "全局",
    content = content,
    source = source,
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
    steps = steps.joinToString("\n") { step ->
        val label = step.anchor?.text ?: step.anchor?.contentDescription.orEmpty()
        if (label.isEmpty()) step.actionKind.name else "${step.actionKind.name} $label"
    },
    postcondition = postcondition.joinToString("\n"),
    signature = pageSignature.joinToString("\n"),
)

/**
 * 功能：把多行文本转成 A2 锚点列表
 * 参数：无（扩展函数，作用于多行文本）
 * 返回：锚点列表
 */
private fun String.toAnchors(): List<Anchor> =
    lines().map(String::trim).filter(String::isNotEmpty)
        .map { Anchor(level = AnchorLevel.A2, text = it) }
