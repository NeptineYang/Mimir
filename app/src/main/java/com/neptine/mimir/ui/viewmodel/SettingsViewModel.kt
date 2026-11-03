package com.neptine.mimir.ui.viewmodel

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neptine.mimir.data.config.HealthResult
import com.neptine.mimir.data.config.ModelConfigRepository
import com.neptine.mimir.data.config.ModelHealthChecker
import com.neptine.mimir.data.db.dao.ConfigDao
import com.neptine.mimir.data.db.dao.MemoryDao
import com.neptine.mimir.data.db.dao.TaskDao
import com.neptine.mimir.data.db.entity.CapabilityEntity
import com.neptine.mimir.device.CapabilityProbe
import com.neptine.mimir.device.DeviceTransport
import com.neptine.mimir.ui.onboarding.Onboarding
import com.neptine.mimir.ui.permission.PermissionReader
import com.neptine.mimir.ui.permission.PermissionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import javax.inject.Inject

/** Shizuku 授权请求码。 */
private const val SHIZUKU_REQUEST_CODE = 4001

/** 日志标签。 */
private const val TAG = "SettingsViewModel"

/** 一条设备能力探针结果。 */
data class CapabilityRow(
    val probeId: String,
    val passed: Boolean,
    val detail: String,
    val deviceModel: String,
    val checkedAt: Long,
)

/** 模型后端的只读摘要；密钥本体不在库里，只有别名。 */
data class ModelSummary(
    val baseUrl: String,
    val apiKeyAlias: String,
    val plannerModel: String,
    val executorModel: String,
    val reviewerModel: String,
)

/**
 * 按 App 的能力行。
 *
 * @property packageName 包名
 * @property label 应用名
 * @property launchable 当前是否可启动（来自 PackagePort）
 * @property taskTotal 历史任务数
 * @property taskDone 其中成功数
 * @property procedureTotal 配方数
 * @property procedureActive 其中 ACTIVE 配方数
 * @property lastUsedAt 最近一次任务更新时间；没有任务时为 0
 */
data class AppCapabilityItem(
    val packageName: String,
    val label: String,
    val launchable: Boolean,
    val taskTotal: Int,
    val taskDone: Int,
    val procedureTotal: Int,
    val procedureActive: Int,
    val lastUsedAt: Long,
)

/** 设置页状态。 */
data class SettingsUiState(
    val shizuku: PermissionStatus,
    val accessibility: PermissionStatus,
    val notifications: PermissionStatus,
    val capabilities: List<CapabilityRow>,
    val model: ModelSummary?,
    val modelLoaded: Boolean,
    val hasApiKey: Boolean,
    val modelEditing: Boolean,
    val appVersion: String,
    val probing: Boolean,
    val modelSaving: Boolean,
    val modelError: String?,
    val modelTesting: Boolean,
    val modelTestResult: String?,
    /** 首次四步引导是否仍然显示。 */
    val guideVisible: Boolean = false,
    /** 按 App 能力矩阵。 */
    val appMatrix: List<AppCapabilityItem> = emptyList(),
    /** 矩阵是否正在刷新。 */
    val appMatrixLoading: Boolean = false,
    /** 本机可启动应用总数；矩阵为空时说明"有记录才列"而不是"一个都没装"。 */
    val launchableCount: Int = 0,
    /** 可启动列表是否真的读到了；读不到时矩阵行只能标"未知"，不能标"不可用"。 */
    val launchabilityKnown: Boolean = false,
)

/**
 * 设置页状态持有者。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configDao: ConfigDao,
    private val taskDao: TaskDao,
    private val memoryDao: MemoryDao,
    private val transport: DeviceTransport,
    private val permissions: PermissionReader,
    private val probe: CapabilityProbe,
    private val models: ModelConfigRepository,
    private val healthChecker: ModelHealthChecker,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val permissionFlow = MutableStateFlow(permissions.snapshot())
    private val modelFlow = MutableStateFlow(ModelSectionState())
    private val probingFlow = MutableStateFlow(false)
    private val guideFlow = MutableStateFlow(Onboarding.needsGuide(context))
    private val matrixFlow = MutableStateFlow(MatrixState())

    /**
     * Shizuku 授权结果的回调；只在实例存活期间注册，onCleared 时移除。
     * 不注册就发起请求，用户授权后界面不会刷新。
     */
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        permissionFlow.value = permissions.snapshot()
    }

    /** 页面状态。 */
    val state: StateFlow<SettingsUiState> = combine(
        combine(
            permissionFlow,
            configDao.observeCapabilities(),
            modelFlow,
            probingFlow,
            guideFlow,
        ) { current, capabilities, modelSection, probing, guideVisible ->
            SettingsUiState(
                shizuku = current.shizuku,
                accessibility = current.accessibility,
                notifications = current.notifications,
                capabilities = capabilities.map { it.toRow() },
                model = modelSection.summary,
                modelLoaded = modelSection.loaded,
                hasApiKey = modelSection.hasApiKey,
                modelEditing = modelSection.editing,
                appVersion = permissions.appVersion(),
                probing = probing,
                modelSaving = modelSection.saving,
                modelError = modelSection.error,
                modelTesting = modelSection.testing,
                modelTestResult = modelSection.testResult,
                guideVisible = guideVisible,
            )
        },
        matrixFlow,
    ) { base, matrix ->
        base.copy(
            appMatrix = matrix.items,
            appMatrixLoading = matrix.loading,
            launchableCount = matrix.launchableCount,
            launchabilityKnown = matrix.launchabilityKnown,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(
                shizuku = permissionFlow.value.shizuku,
                accessibility = permissionFlow.value.accessibility,
                notifications = permissionFlow.value.notifications,
                capabilities = emptyList(),
                model = null,
                modelLoaded = false,
                hasApiKey = false,
                modelEditing = false,
                appVersion = permissions.appVersion(),
                probing = false,
                modelSaving = false,
                modelError = null,
                modelTesting = false,
                modelTestResult = null,
                guideVisible = guideFlow.value,
            ),
        )

    init {
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuListener) }
        loadModelConfig()
        refreshAppMatrix()
    }

    /**
     * 功能：执行 P1–P5 探针并把结果落库
     * 参数：无
     * 返回：无
     *
     * 结果按 probe_id 覆盖写入，UI 的 device_capability Flow 会自动刷新，不需要回读。
     */
    fun runCapabilityProbe() {
        if (probingFlow.value) return
        viewModelScope.launch {
            probingFlow.value = true
            try {
                val results = probe.runAll()
                val deviceModel = Build.MODEL.orEmpty()
                val checkedAt = System.currentTimeMillis()
                results.forEach { result ->
                    configDao.upsertCapability(
                        CapabilityEntity(
                            probeId = result.probeId.name,
                            passed = result.passed,
                            detail = result.detail,
                            deviceModel = deviceModel,
                            checkedAt = checkedAt,
                        ),
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "设备探针执行失败", e)
            } finally {
                probingFlow.value = false
            }
        }
    }

    /**
     * 功能：重新读取三项权限状态
     * 参数：无
     * 返回：无
     */
    fun refreshPermissions() {
        permissionFlow.value = permissions.snapshot()
    }

    /**
     * 功能：结束首次引导
     * 参数：无
     * 返回：无
     *
     * 完成与跳过都走这里：标记写进独立的偏好文件，下次启动不再自动落在设置页。
     */
    fun completeGuide() {
        Onboarding.markDone(context)
        guideFlow.value = false
    }

    /**
     * 功能：刷新按 App 能力矩阵
     * 参数：无
     * 返回：无
     *
     * 数据来自三处：可启动包列表（能力判断）、任务表的包名聚合（用没用过）、
     * 配方表的包名聚合（有没有经验）。只列有任务或配方记录的 App，
     * 不把整机几百个可启动包铺成一屏。
     */
    fun refreshAppMatrix() {
        if (matrixFlow.value.loading) return
        viewModelScope.launch {
            matrixFlow.value = matrixFlow.value.copy(loading = true)
            val launchable = runCatching { transport.packages.launchablePackages() }
            val launchableSet = launchable.getOrDefault(emptyList()).toSet()
            val known = launchable.isSuccess
            val tasks = runCatching { taskDao.packageStats() }.getOrDefault(emptyList())
            val memories = runCatching { memoryDao.packageStats() }.getOrDefault(emptyList())
            val rows = (tasks.map { it.packageName } + memories.map { it.packageName })
                .distinct()
                .map { packageName ->
                    val task = tasks.firstOrNull { it.packageName == packageName }
                    val memory = memories.firstOrNull { it.packageName == packageName }
                    AppCapabilityItem(
                        packageName = packageName,
                        label = labelOf(packageName),
                        launchable = packageName in launchableSet,
                        taskTotal = task?.total ?: 0,
                        taskDone = task?.done ?: 0,
                        procedureTotal = memory?.total ?: 0,
                        procedureActive = memory?.active ?: 0,
                        lastUsedAt = maxOf(task?.lastUsedAt ?: 0L, memory?.lastCreatedAt ?: 0L),
                    )
                }
                .sortedWith(
                    compareByDescending<AppCapabilityItem> { it.lastUsedAt }
                        .thenByDescending { it.taskTotal + it.procedureTotal },
                )
                .take(MAX_MATRIX_ROWS)
            matrixFlow.value = MatrixState(
                items = rows,
                loading = false,
                launchableCount = launchableSet.size,
                launchabilityKnown = known,
            )
        }
    }

    /**
     * 功能：取应用名
     * 参数：packageName 包名
     * 返回：桌面显示名；应用已卸载或读不到时退回包名
     */
    private fun labelOf(packageName: String): String = runCatching {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /**
     * 功能：发起 Shizuku 授权请求
     * 参数：无
     * 返回：无
     */
    fun requestShizukuPermission() {
        permissions.requestShizukuPermission(SHIZUKU_REQUEST_CODE)
    }

    /**
     * 功能：打开系统无障碍设置页
     * 参数：无
     * 返回：无
     */
    fun openAccessibilitySettings() {
        permissions.openAccessibilitySettings()
    }

    /**
     * 功能：打开本应用通知设置页
     * 参数：无
     * 返回：无
     */
    fun openNotificationSettings() {
        permissions.openAppNotificationSettings()
    }

    /**
     * 功能：读取模型后端配置
     * 参数：无
     * 返回：无
     *
     * findModelConfig 是一次性 suspend 查询，没有 Flow；只在进入页面与保存后重读。
     */
    private fun loadModelConfig() {
        viewModelScope.launch {
            val config = configDao.findModelConfig()
            modelFlow.value = modelFlow.value.copy(
                summary = config?.let {
                    ModelSummary(
                        baseUrl = it.baseUrl,
                        apiKeyAlias = it.apiKeyAlias,
                        plannerModel = it.plannerModel,
                        executorModel = it.executorModel,
                        reviewerModel = it.reviewerModel,
                    )
                },
                loaded = true,
                hasApiKey = models.hasApiKey(),
            )
        }
    }

    /**
     * 功能：打开模型配置编辑弹窗
     * 参数：无
     * 返回：无
     */
    fun startModelEdit() {
        modelFlow.value = modelFlow.value.copy(editing = true, error = null)
    }

    /**
     * 功能：关闭模型配置编辑弹窗
     * 参数：无
     * 返回：无
     */
    fun cancelModelEdit() {
        modelFlow.value = modelFlow.value.copy(editing = false, error = null)
    }

    /**
     * 功能：保存模型配置
     * 参数：baseUrl 后端地址；planner/executor/reviewer 模型名；apiKey 密钥（留空表示不修改）
     * 返回：无
     *
     * 密钥写进 EncryptedSharedPreferences，表里只留别名；保存失败把原因回填到弹窗，不静默失败。
     */
    fun saveModel(
        baseUrl: String,
        plannerModel: String,
        executorModel: String,
        reviewerModel: String,
        apiKey: String,
    ) {
        if (modelFlow.value.saving) return
        if (baseUrl.isBlank() || executorModel.isBlank()) {
            modelFlow.value = modelFlow.value.copy(error = "base_url 与 executor 模型名不能为空")
            return
        }
        modelFlow.value = modelFlow.value.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching {
                models.save(
                    baseUrl = baseUrl,
                    plannerModel = plannerModel.ifBlank { executorModel },
                    executorModel = executorModel,
                    reviewerModel = reviewerModel.ifBlank { executorModel },
                    apiKey = apiKey.takeIf { it.isNotBlank() },
                )
            }.onSuccess {
                modelFlow.value = modelFlow.value.copy(saving = false, editing = false)
                loadModelConfig()
            }.onFailure { error ->
                modelFlow.value = modelFlow.value.copy(
                    saving = false,
                    error = error.message ?: "保存失败",
                )
            }
        }
    }

    /**
     * 功能：对已保存的模型后端做一次连通性自检
     * 参数：无
     * 返回：无
     *
     * 只发一条最小请求，用来把"连不上"（地址/密钥/网络）与"答得不好"（模型能力）分开。
     * 结果写在页面上，不落库：它是即时诊断，不是设备能力。
     */
    fun testModel() {
        if (modelFlow.value.testing) return
        modelFlow.value = modelFlow.value.copy(testing = true, testResult = null)
        viewModelScope.launch {
            val result = healthChecker.check()
            val text = when (result) {
                is HealthResult.Success ->
                    "连通正常 · ${result.model} · ${result.latencyMs}ms · 回复「${result.reply}」"
                is HealthResult.Failure -> "连通失败 · ${result.reason}"
            }
            modelFlow.value = modelFlow.value.copy(testing = false, testResult = text)
        }
    }

    override fun onCleared() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuListener) }
        super.onCleared()
    }
}

/**
 * 设置页里"模型后端"一段的完整状态。
 *
 * @property summary 已保存的配置摘要
 * @property loaded 是否已读过一次配置
 * @property hasApiKey 是否已保存过密钥
 * @property editing 编辑弹窗是否打开
 * @property saving 是否正在保存
 * @property error 上次保存的错误
 */
private data class ModelSectionState(
    val summary: ModelSummary? = null,
    val loaded: Boolean = false,
    val hasApiKey: Boolean = false,
    val editing: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val testing: Boolean = false,
    val testResult: String? = null,
)

/**
 * 按 App 能力矩阵的加载状态。
 *
 * @property items 已聚合的行
 * @property loading 是否正在刷新
 * @property launchableCount 可启动包总数
 * @property launchabilityKnown 可启动列表是否读取成功
 */
private data class MatrixState(
    val items: List<AppCapabilityItem> = emptyList(),
    val loading: Boolean = false,
    val launchableCount: Int = 0,
    val launchabilityKnown: Boolean = false,
)

/** 矩阵最多展示多少行：按最近使用与用量排序后的前几条，避免铺满整屏。 */
private const val MAX_MATRIX_ROWS = 8

/**
 * 功能：把探针实体转成展示模型
 * 参数：无（扩展函数）
 * 返回：CapabilityRow
 */
private fun CapabilityEntity.toRow(): CapabilityRow = CapabilityRow(
    probeId = probeId,
    passed = passed,
    detail = detail,
    deviceModel = deviceModel,
    checkedAt = checkedAt,
)
