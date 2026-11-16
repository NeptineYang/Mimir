package com.neptine.mimir.ui.screen

import com.neptine.mimir.ui.i18n.LanguagePrefs
import com.neptine.mimir.ui.i18n.UiLanguage
import com.neptine.mimir.ui.i18n.UiLanguageState
import com.neptine.mimir.ui.i18n.t
import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neptine.mimir.ui.component.EmptyState
import com.neptine.mimir.ui.component.ExpandableSection
import com.neptine.mimir.ui.component.KeyValueRow
import com.neptine.mimir.ui.component.MimirHairline
import com.neptine.mimir.ui.component.MimirListRow
import com.neptine.mimir.ui.component.MimirPageHeader
import com.neptine.mimir.ui.component.MonoLabel
import com.neptine.mimir.ui.component.PrimaryPillButton
import com.neptine.mimir.ui.component.SectionBlock
import com.neptine.mimir.ui.component.StatusChip
import com.neptine.mimir.ui.component.TextActionButton
import com.neptine.mimir.device.selfhost.PrivilegeStatus
import com.neptine.mimir.ui.permission.PermissionStatus
import com.neptine.mimir.ui.permission.privilegeStatusDetail
import com.neptine.mimir.ui.permission.privilegeStatusText
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.observation.AuditCleanupResult
import com.neptine.mimir.observation.AuditStorageUsage
import com.neptine.mimir.ui.viewmodel.AppCapabilityItem
import com.neptine.mimir.ui.viewmodel.CapabilityRow
import com.neptine.mimir.ui.viewmodel.SettingsUiState
import com.neptine.mimir.ui.viewmodel.SettingsViewModel
import com.neptine.mimir.ui.viewmodel.formatBytes
import com.neptine.mimir.ui.viewmodel.formatTime

/**
 * 功能：设置页——权限状态、模型后端与设备能力矩阵
 * 参数：无
 * 返回：无
 */
@Composable
fun SettingsScreen() {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalMimirColors.current

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }

    // 从系统设置页返回时立即刷新，不需要用户手动下拉。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
        viewModel.refreshAppMatrix()
    }

    // 语言切换后重算权限与能力矩阵里的派生文案，避免页面上残留旧语言
    LaunchedEffect(UiLanguageState.current) {
        viewModel.refreshPermissions()
        viewModel.refreshAppMatrix()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MimirSpacing.page),
    ) {
        MimirPageHeader(
            title = "设置".t(),
        )

        LanguageSection()

        val onOpenNotifications = {
            if (Build.VERSION.SDK_INT >= 33 && state.notifications != PermissionStatus.GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.openNotificationSettings()
            }
        }

        if (state.guideVisible) {
            OnboardingSection(
                state = state,
                onFixPrivilege = viewModel::fixPrivilege,
                onOpenAccessibility = viewModel::openAccessibilitySettings,
                onOpenNotifications = onOpenNotifications,
                onEditModel = viewModel::startModelEdit,
                onDone = viewModel::completeGuide,
            )
        }

        PermissionSection(
            state = state,
            onFixPrivilege = viewModel::fixPrivilege,
            onOpenAccessibility = viewModel::openAccessibilitySettings,
            onOpenNotifications = onOpenNotifications,
            onOpenResultBanner = viewModel::openResultBannerSettings,
        )

        PrivilegeSection(
            state = state,
            onStartPairing = viewModel::startPairing,
            onStartService = viewModel::startPrivilegeService,
            onClearKey = viewModel::clearAdbKey,
        )

        ModelSection(
            state = state,
            onEdit = viewModel::startModelEdit,
            onDismissEdit = viewModel::cancelModelEdit,
            onSave = viewModel::saveModel,
            onTest = viewModel::testModel,
        )

        AsrSection(state = state, onTest = viewModel::testAsr)

        StorageSection(
            usage = state.storage,
            clearing = state.storageClearing,
            cleared = state.storageCleared,
            onClear = viewModel::clearScreenshots,
        )

        CapabilitySection(
            capabilities = state.capabilities,
            probing = state.probing,
            onRunProbe = viewModel::runCapabilityProbe,
        )

        AppMatrixSection(
            state = state,
            onRefresh = viewModel::refreshAppMatrix,
        )

        Spacer(modifier = Modifier.height(MimirSpacing.section))
        MonoLabel(text = "MIMIR ${state.appVersion} · API 36", color = colors.muted)
        Spacer(modifier = Modifier.height(MimirSpacing.huge))
    }
}

/**
 * 功能：渲染「特权服务」分组——状态、配对与启动
 * 参数：state 设置页状态；onStartPairing 发起配对（会在通知栏弹输入框）；
 *       onStartService 手动拉起壳进程；onClearKey 清除已保存的 adb 密钥
 * 返回：无
 *
 * 配对码在通知栏里输入，不在应用内弹框：HyperOS 在设置失去前台的瞬间就销毁系统的
 * 配对对话框，配对端口随之关闭；通知栏输入不切前台，端口才能活着。
 */
@Composable
private fun PrivilegeSection(
    state: SettingsUiState,
    onStartPairing: () -> Unit,
    onStartService: () -> Unit,
    onClearKey: () -> Unit,
) {
    val colors = LocalMimirColors.current
    val adb = state.adb

    ExpandableSection(
        title = "特权服务".t(),
        summary = privilegeStatusText(state.privilegeStatus),
    ) {
        Text(
            text = privilegeStatusDetail(state.privilegeStatus),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        if (!adb.paired) {
            Text(
                text = "在系统「开发者选项 → 无线调试」里点「使用配对码配对设备」，" +
                    "再点下面的按钮，下拉通知栏把 6 位码回复进去。全过程不需要切回 Mimir。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(top = MimirSpacing.xxs),
            )
        }
        Row(
            modifier = Modifier.padding(top = MimirSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryPillButton(
                text = when {
                    adb.busy -> "配对中…".t()
                    adb.paired -> "重新配对".t()
                    else -> "配对无线调试".t()
                },
                onClick = onStartPairing,
                enabled = !adb.busy,
            )
            if (adb.paired) {
                TextActionButton(text = "清除密钥".t(), onClick = onClearKey)
            }
        }
        adb.result?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink,
                modifier = Modifier.padding(top = MimirSpacing.sm),
            )
        }

        MimirHairline(modifier = Modifier.padding(vertical = MimirSpacing.sm))

        // 服务段：配对只解决"能不能连"，这一步才把壳进程真正拉起来；
        // 正常情况下回到前台会自动拉起，这里的按钮是手动兜底。
        Text(
            text = "服务：%1\$s".t(
                when {
                    state.privilegeStatus == PrivilegeStatus.READY -> "运行中".t()
                    state.service.starting -> "启动中…".t()
                    else -> "未启动".t()
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
        )
        Row(
            modifier = Modifier.padding(top = MimirSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryPillButton(
                text = "启动特权服务".t(),
                onClick = onStartService,
                enabled = adb.paired && !state.service.starting,
                disabledHint = if (adb.paired) null else "先完成配对".t(),
            )
        }
        state.service.error?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.padding(top = MimirSpacing.sm),
            )
        }
    }
}

/**
 * 功能：渲染权限三段
 * 参数：state 设置页状态；三个权限动作回调；完成提醒开关动作回调
 * 返回：无
 */
@Composable
private fun PermissionSection(
    state: SettingsUiState,
    onFixPrivilege: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenResultBanner: () -> Unit,
) {
    val ready = listOf(state.privilege, state.accessibility, state.notifications, state.resultBanner)
        .count { it == PermissionStatus.GRANTED }
    ExpandableSection(title = "权限".t(), summary = "%1\$d / 4 已就绪".t(ready)) {
        PermissionRow(
            name = "特权服务".t(),
            status = state.privilege,
            statusLabel = privilegeStatusText(state.privilegeStatus),
            detail = privilegeStatusDetail(state.privilegeStatus),
            actionLabel = if (state.privilege == PermissionStatus.GRANTED) null else "去处理".t(),
            onAction = onFixPrivilege,
        )
        PermissionRow(
            name = "无障碍服务".t(),
            status = state.accessibility,
            detail = "只读取副屏上目标 App 的控件文本，不读你主屏的内容。".t(),
            actionLabel = if (state.accessibility == PermissionStatus.GRANTED) null else "去开启".t(),
            onAction = onOpenAccessibility,
        )
        PermissionRow(
            name = "通知".t(),
            status = state.notifications,
            detail = "人工交接与完成报告的唯一通道；会以横幅弹出提醒。".t(),
            actionLabel = if (state.notifications == PermissionStatus.GRANTED) null else "去设置".t(),
            onAction = onOpenNotifications,
        )
        PermissionRow(
            name = "完成提醒".t(),
            status = state.resultBanner,
            detail = "任务结束时把通知横幅弹到主屏（含锁屏）；需要系统里开启「任务结果」渠道的悬浮通知。".t(),
            actionLabel = if (state.resultBanner == PermissionStatus.GRANTED) null else "去开启".t(),
            onAction = onOpenResultBanner,
        )
    }
}

/**
 * 功能：渲染界面语言切换（中文 / English）
 * 参数：无
 * 返回：无
 *
 * 放在设置页第一组，且不参与折叠：语言本身是"看得懂才找得到"的前提，
 * 藏进手风琴会让英文用户先面对一屏中文再去找开关。
 *
 * 两个选项都是独立胶囊按钮，选中的用实心填充——与全站按钮语义一致。
 */
@Composable
private fun LanguageSection() {
    val colors = LocalMimirColors.current
    val context = LocalContext.current
    val language = UiLanguageState.current

    Column {
        MimirHairline()
        Column(
            modifier = Modifier.padding(top = MimirSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.xxs),
        ) {
            Text(
                text = "界面语言".t(),
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
            )
            Text(
                text = "切换后立即生效，只影响界面文案".t(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        Row(
            modifier = Modifier.padding(top = MimirSpacing.md, bottom = MimirSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextActionButton(
                text = "中文",
                onClick = { selectLanguage(context, UiLanguage.ZH) },
                emphasis = language == UiLanguage.ZH,
            )
            TextActionButton(
                text = "English",
                onClick = { selectLanguage(context, UiLanguage.EN) },
                emphasis = language == UiLanguage.EN,
            )
        }
    }
}

/** 功能：写入语言选择；参数：context 上下文、language 目标语言；返回：无。 */
private fun selectLanguage(context: Context, language: UiLanguage) {
    if (UiLanguageState.current == language) return
    UiLanguageState.current = language
    LanguagePrefs.write(context, language)
}

/**
 * 功能：渲染一行权限状态
 * 参数：name 权限名；status 当前状态；detail 说明；actionLabel 操作文案，null 表示不显示操作；
 *       onAction 操作回调
 * 返回：无
 */
@Composable
private fun PermissionRow(
    name: String,
    status: PermissionStatus,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit,
    statusLabel: String? = null,
) {
    val colors = LocalMimirColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MimirSpacing.lg),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.ink,
                )
                StatusChip(text = statusLabel ?: status.label(), fg = status.color())
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.slate,
            )
        }
        if (actionLabel != null) {
            TextActionButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.padding(start = MimirSpacing.md),
            )
        }
    }
}

/**
 * 功能：渲染模型后端一段
 * 参数：state 设置页状态；编辑/保存回调
 * 返回：无
 */
@Composable
private fun ModelSection(
    state: SettingsUiState,
    onEdit: () -> Unit,
    onDismissEdit: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit,
    onTest: () -> Unit,
) {
    val configured = state.model
    val summary = when {
        !state.modelLoaded -> "读取中".t()
        configured == null -> "尚未配置".t()
        else -> "${configured.baseUrl} · ${configured.executorModel}"
    }
    ExpandableSection(title = "模型后端".t(), summary = summary) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MimirSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextActionButton(
                text = if (state.modelTesting) "测试中".t() else "测试".t(),
                onClick = onTest,
                enabled = state.modelLoaded && !state.modelTesting && state.hasApiKey,
            )
            TextActionButton(
                text = "编辑".t(),
                onClick = onEdit,
                enabled = state.modelLoaded && !state.modelEditing,
                emphasis = true,
            )
        }
        when {
            !state.modelLoaded -> Spacer(modifier = Modifier.height(MimirSpacing.md))
            configured == null -> EmptyState(
                title = "尚未配置模型后端".t(),
                hint = "点上方编辑，填 base_url、模型名与 API Key。密钥存 EncryptedSharedPreferences，".t() +
                    "表里只留别名，不上传、不进日志。".t(),
            )
            else -> {
                KeyValueRow(key = "base_url", value = configured.baseUrl, mono = true)
                KeyValueRow(key = "planner", value = configured.plannerModel, mono = true)
                KeyValueRow(key = "executor", value = configured.executorModel, mono = true)
                KeyValueRow(key = "reviewer", value = configured.reviewerModel, mono = true)
                KeyValueRow(
                    key = "密钥".t(),
                    value = if (state.hasApiKey) "已保存（加密存储）".t() else "未保存".t(),
                    mono = true,
                )
                state.modelTestResult?.let { result ->
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.startsWith("连通正常") || result.startsWith("Connection OK")) {
                            LocalMimirColors.current.deepGreen
                        } else {
                            LocalMimirColors.current.coral
                        },
                        modifier = Modifier.padding(top = MimirSpacing.xs),
                    )
                }
            }
        }
    }

    if (state.modelEditing) {
        ModelEditDialog(
            state = state,
            onDismiss = onDismissEdit,
            onSave = onSave,
        )
    }
}

/**
 * 功能：渲染端侧语音识别一段
 * 参数：state 设置页状态；onTest 自检回调
 * 返回：无
 *
 * 端侧模型没有"连通性"可测，这里的自检是加载一次并报耗时：演示前先点一下，
 * 把"模型没打进包 / ABI 不匹配"提前暴露，而不是等上台按了麦克风才发现。
 */
@Composable
private fun AsrSection(
    state: SettingsUiState,
    onTest: () -> Unit,
) {
    ExpandableSection(title = "语音识别".t(), summary = "sherpa-onnx · 端侧流式 · 中文".t()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MimirSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm, Alignment.End),
        ) {
            TextActionButton(
                text = if (state.asrTesting) "自检中".t() else "自检".t(),
                onClick = onTest,
                enabled = !state.asrTesting,
            )
        }
        KeyValueRow(key = "引擎".t(), value = "sherpa-onnx 2.0.1 · 端侧流式".t(), mono = true)
        KeyValueRow(key = "模型".t(), value = "zipformer multi-zh-hans · int8", mono = true)
        state.asrResult?.let { result ->
            Text(
                text = result,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.startsWith("加载失败")) {
                    LocalMimirColors.current.coral
                } else {
                    LocalMimirColors.current.deepGreen
                },
                modifier = Modifier.padding(top = MimirSpacing.xs),
            )
        }
    }
}

/**
 * 功能：渲染审计截图的存储与清理一段
 * 参数：usage 当前占用；clearing 是否正在清理；cleared 最近一次清理结果；onClear 清理回调
 * 返回：无
 *
 * 截图在应用私有目录里，相册看不到；这一段回答"占了多少、怎么清"。
 * 清理只删已完成任务，进行中的任务仍需要回放画面，不能一起删。
 */
@Composable
private fun StorageSection(
    usage: AuditStorageUsage?,
    clearing: Boolean,
    cleared: AuditCleanupResult?,
    onClear: () -> Unit,
) {
    val colors = LocalMimirColors.current
    var confirming by remember { mutableStateOf(false) }
    val summary = usage?.let { "%1\$s · %2\$d 个文件".t(formatBytes(it.bytes), it.files) }
        ?: "读取中".t()

    ExpandableSection(title = "存储".t(), summary = summary) {
        KeyValueRow(key = "审计截图".t(), value = summary, mono = true)
        KeyValueRow(
            key = "自动清理".t(),
            value = "超过 30 天的截图自动删除，总量上限 300 MB。".t(),
        )
        KeyValueRow(key = "保留范围".t(), value = "进行中的任务不受影响。".t())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MimirSpacing.sm, bottom = MimirSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm, Alignment.End),
        ) {
            TextActionButton(
                text = if (clearing) "清理中".t() else "清理截图".t(),
                onClick = { confirming = true },
                enabled = !clearing && (usage?.files ?: 0) > 0,
            )
        }
        cleared?.let { result ->
            Text(
                text = if (result.deletedDirs == 0) {
                    "没有可清理的截图".t()
                } else {
                    "已清理 %1\$s".t(formatBytes(result.freedBytes))
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.deepGreen,
            )
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(text = "清理审计截图".t(), style = MaterialTheme.typography.titleMedium) },
            text = { Text(text = "将删除已结束任务的全部审计截图，进行中的任务保留。".t()) },
            confirmButton = {
                TextActionButton(
                    text = "清理".t(),
                    onClick = {
                        confirming = false
                        onClear()
                    },
                    emphasis = true,
                )
            },
            dismissButton = {
                TextActionButton(
                    text = "取消".t(),
                    onClick = { confirming = false },
                    color = colors.muted,
                )
            },
        )
    }
}

/**
 * 功能：模型后端编辑弹窗
 * 参数：state 设置页状态；关闭与保存回调
 * 返回：无
 */
@Composable
private fun ModelEditDialog(
    state: SettingsUiState,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit,
) {
    val summary = state.model
    var baseUrl by remember { mutableStateOf(summary?.baseUrl.orEmpty()) }
    var planner by remember { mutableStateOf(summary?.plannerModel.orEmpty()) }
    var executorModel by remember { mutableStateOf(summary?.executorModel.orEmpty()) }
    var reviewer by remember { mutableStateOf(summary?.reviewerModel.orEmpty()) }
    var apiKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!state.modelSaving) onDismiss() },
        title = { Text(text = "模型后端".t(), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm)) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("base_url") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = executorModel,
                    onValueChange = { executorModel = it },
                    label = { Text("executor 模型".t()) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = planner,
                    onValueChange = { planner = it },
                    label = { Text("planner 模型（留空沿用 executor）".t()) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = reviewer,
                    onValueChange = { reviewer = it },
                    label = { Text("reviewer 模型（留空沿用 executor）".t()) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(if (state.hasApiKey) "API Key（留空则不修改）".t() else "API Key") },
                    singleLine = true,
                )
                if (state.modelError != null) {
                    Text(
                        text = state.modelError,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalMimirColors.current.coral,
                    )
                }
            }
        },
        confirmButton = {
            TextActionButton(
                text = if (state.modelSaving) "保存中".t() else "保存".t(),
                onClick = { onSave(baseUrl, planner, executorModel, reviewer, apiKey) },
                enabled = !state.modelSaving,
                emphasis = true,
            )
        },
        dismissButton = {
            TextActionButton(
                text = "取消".t(),
                onClick = onDismiss,
                enabled = !state.modelSaving,
                color = LocalMimirColors.current.muted,
            )
        },
    )
}

/**
 * 功能：渲染设备能力矩阵一段
 * 参数：capabilities 探针结果
 * 返回：无
 */
@Composable
private fun CapabilitySection(
    capabilities: List<CapabilityRow>,
    probing: Boolean,
    onRunProbe: () -> Unit,
) {
    val colors = LocalMimirColors.current
    val summary = if (capabilities.isEmpty()) {
        "尚未检测（P1–P5）".t()
    } else {
        "${capabilities.count { it.passed }} / ${capabilities.size} 通过"
    }
    ExpandableSection(title = "设备能力 P1–P5".t(), summary = summary) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MimirSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm, Alignment.End),
        ) {
            TextActionButton(
                text = if (probing) "检测中".t() else "重新检测".t(),
                onClick = onRunProbe,
                enabled = !probing,
            )
        }
        if (capabilities.isEmpty()) {
            EmptyState(
                title = "尚未运行设备探针".t(),
                hint = "P1 虚拟屏 / P2 定向注入 / P3 定向取帧 / P4 无障碍可见性 / P5 零干扰输入。".t() +
                    "点上方重新检测，逐项结果会写进本机数据库。".t(),
            )
            Text(
                text = "P5 需要人工配合：主屏持续打字时观察副屏输入是否抢焦点。".t(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        } else {
            capabilities.forEach { capability ->
                MimirListRow(
                    title = if (capability.passed) "通过".t() else "不支持".t(),
                    leading = { MonoLabel(text = capability.probeId, color = colors.ink) },
                    note = capability.detail,
                    meta = "${capability.deviceModel} · ${formatTime(capability.checkedAt)}",
                )
            }
        }
    }
}

/**
 * 功能：渲染首次使用的四步引导
 * 参数：state 设置页状态；四个配置动作；onDone 完成或跳过
 * 返回：无
 *
 * 四步与权限段一一对应，但顺序固定：先给 shell 级权限，再给读屏与回报通道，最后配模型。
 * 任何一步未完成都允许跳过，但按钮文案写明"跳过只是隐藏"，不让人以为已经配好了。
 */
@Composable
private fun OnboardingSection(
    state: SettingsUiState,
    onFixPrivilege: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenNotifications: () -> Unit,
    onEditModel: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = LocalMimirColors.current
    val modelReady = state.modelLoaded && state.model != null && state.hasApiKey
    val allReady = state.privilege == PermissionStatus.GRANTED &&
        state.accessibility == PermissionStatus.GRANTED &&
        state.notifications == PermissionStatus.GRANTED &&
        modelReady

    SectionBlock(
        title = "开始使用".t(),
        modifier = Modifier.padding(top = MimirSpacing.md),
        trailing = { MonoLabel(text = "首次配置".t(), color = colors.muted) },
    ) {
        Text(
            text = "四步就绪之后，任务才能建屏、读屏并把结果回报给你。".t(),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.slate,
            modifier = Modifier.padding(bottom = MimirSpacing.sm),
        )
        GuideStepRow(
            title = "启用特权服务".t(),
            ready = state.privilege == PermissionStatus.GRANTED,
            actionLabel = "去处理".t(),
            onAction = onFixPrivilege,
        )
        GuideStepRow(
            title = "开启无障碍服务".t(),
            ready = state.accessibility == PermissionStatus.GRANTED,
            actionLabel = "去开启".t(),
            onAction = onOpenAccessibility,
        )
        GuideStepRow(
            title = "允许通知".t(),
            ready = state.notifications == PermissionStatus.GRANTED,
            actionLabel = "去设置".t(),
            onAction = onOpenNotifications,
        )
        GuideStepRow(
            title = "配置模型后端".t(),
            ready = modelReady,
            actionLabel = "去配置".t(),
            onAction = onEditModel,
        )
        TextActionButton(
            text = if (allReady) "完成引导".t() else "跳过（不再显示）".t(),
            onClick = onDone,
            emphasis = allReady,
            modifier = Modifier.padding(top = MimirSpacing.sm),
        )
        if (!allReady) {
            Text(
                text = "跳过只是隐藏这张引导，不会替你完成配置。".t(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(top = MimirSpacing.xs, bottom = MimirSpacing.md),
            )
        }
    }
}

/**
 * 功能：渲染引导里的一步
 * 参数：title 步骤名；ready 是否已就绪；actionLabel 未就绪时的操作文案；onAction 操作回调
 * 返回：无
 *
 * 不显示 01/02/03/04：这四步是并列的配置项，先后顺序并不严格，
 * 序号只会让人以为必须按顺序完成。
 */
@Composable
private fun GuideStepRow(
    title: String,
    ready: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val colors = LocalMimirColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MimirSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
            Text(
                text = if (ready) "已完成".t() else "未完成".t(),
                style = MaterialTheme.typography.bodySmall,
                color = if (ready) colors.deepGreen else colors.slate,
            )
        }
        if (!ready) {
            TextActionButton(text = actionLabel, onClick = onAction)
        }
    }
}

/**
 * 功能：渲染按 App 能力矩阵
 * 参数：state 设置页状态；onRefresh 刷新回调
 * 返回：无
 *
 * 三态合并成一个标记：可执行 = 可启动 + 四项权限/模型就绪；待权限 = 可启动但还没配齐；
 * 不可用 = 可启动列表里没有它（已卸载或不可启动）。读不到可启动列表时写"未知"，
 * 不把"没读到"说成"不支持"。
 */
@Composable
private fun AppMatrixSection(
    state: SettingsUiState,
    onRefresh: () -> Unit,
) {
    val colors = LocalMimirColors.current
    val readyNow = state.privilege == PermissionStatus.GRANTED &&
        state.accessibility == PermissionStatus.GRANTED &&
        state.modelLoaded &&
        state.model != null &&
        state.hasApiKey

    ExpandableSection(
        title = "按 App 能力".t(),
        summary = "可启动 %1\$d 个 · 有记录 %2\$d 个".t(state.launchableCount, state.appMatrix.size),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm, Alignment.End),
        ) {
            TextActionButton(
                text = if (state.appMatrixLoading) "刷新中".t() else "刷新".t(),
                onClick = onRefresh,
                enabled = !state.appMatrixLoading,
            )
        }
        Text(
            text = "可启动应用 ${state.launchableCount} 个；下表只列有任务或配方记录的 App。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.padding(bottom = MimirSpacing.sm),
        )
        if (state.appMatrix.isEmpty()) {
            EmptyState(
                title = "暂无可评估的 App".t(),
                hint = "跑一个任务或新建一条配方后，这里会按 App 汇总任务数、配方数与可执行状态。".t(),
            )
        } else {
            state.appMatrix.forEach { item ->
                AppMatrixRow(item = item, readyNow = readyNow, launchabilityKnown = state.launchabilityKnown)
            }
        }
    }
}

/**
 * 功能：渲染矩阵里的一行
 * 参数：item App 行；readyNow 四项配置是否就绪；launchabilityKnown 可启动列表是否读到
 * 返回：无
 */
@Composable
private fun AppMatrixRow(
    item: AppCapabilityItem,
    readyNow: Boolean,
    launchabilityKnown: Boolean,
) {
    val colors = LocalMimirColors.current
    val capability = when {
        !launchabilityKnown -> "未知".t()
        !item.launchable -> "不可用".t()
        readyNow -> "可执行".t()
        else -> "待权限".t()
    }
    val color = when (capability) {
        "可执行".t() -> colors.deepGreen
        "不可用".t() -> colors.coral
        else -> colors.slate
    }
    val recent = if (item.lastUsedAt > 0) formatTime(item.lastUsedAt) else "未使用".t()
    MimirListRow(
        title = item.label,
        meta = item.packageName,
        note = "任务 ${item.taskTotal}（成功 ${item.taskDone}）· " +
            "配方 ${item.procedureTotal}（ACTIVE ${item.procedureActive}）· 最近 $recent",
        leading = { StatusChip(text = capability, fg = color) },
    )
}

/**
 * 功能：权限状态的中文标签
 * 参数：无（扩展函数）
 * 返回：展示用短标签
 */
@Composable
private fun PermissionStatus.label(): String = when (this) {
    PermissionStatus.GRANTED -> "已就绪".t()
    PermissionStatus.DENIED -> "未授权".t()
    PermissionStatus.UNAVAILABLE -> "未运行".t()
}

/**
 * 功能：权限状态的强调色
 * 参数：无（扩展函数）
 * 返回：强调色；chip 的底色与描边据此派生
 */
@Composable
private fun PermissionStatus.color(): Color = when (this) {
    PermissionStatus.GRANTED -> LocalMimirColors.current.deepGreen
    PermissionStatus.DENIED -> LocalMimirColors.current.coral
    PermissionStatus.UNAVAILABLE -> LocalMimirColors.current.slate
}
