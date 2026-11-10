package com.neptine.mimir.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neptine.mimir.ui.component.AuditStepDetail
import com.neptine.mimir.ui.component.EmptyState
import com.neptine.mimir.ui.component.KeyValueRow
import com.neptine.mimir.ui.component.MimirPageHeader
import com.neptine.mimir.ui.component.SectionBlock
import com.neptine.mimir.ui.component.TextActionButton
import com.neptine.mimir.ui.i18n.t
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.viewmodel.AuditEventUiState
import com.neptine.mimir.ui.viewmodel.AuditEventViewModel
import com.neptine.mimir.ui.viewmodel.formatTime

/**
 * 功能：单条审计事件的证据页
 * 参数：onBack 返回列表；onOpenTask 跳到该任务的完整回放
 * 返回：无
 *
 * 上半段回答"这条事件属于谁、什么时候发生"，下半段才是原始证据。
 * 事件随任务一起删除时给出明确空态，不静默留白。
 */
@Composable
fun AuditEventScreen(
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val viewModel: AuditEventViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MimirSpacing.page),
    ) {
        MimirPageHeader(
            title = "审计证据".t(),
            trailing = { TextActionButton(text = "← 返回".t(), onClick = onBack) },
        )

        when (val current = state) {
            AuditEventUiState.Loading -> Unit
            AuditEventUiState.Missing -> EmptyState(
                title = "该事件已不存在".t(),
                hint = "审计记录可能随任务一起被删除。".t(),
            )
            is AuditEventUiState.Loaded -> {
                SectionBlock(title = "任务".t()) {
                    KeyValueRow(
                        key = "任务".t(),
                        value = current.detail.task?.inputText ?: current.detail.taskId,
                    )
                    KeyValueRow(
                        key = "任务标识".t(),
                        value = current.detail.taskId,
                        mono = true,
                    )
                    KeyValueRow(
                        key = "步序".t(),
                        value = "第 %1\$d 步".t(current.detail.step.stepIndex),
                    )
                    KeyValueRow(
                        key = "发生时间".t(),
                        value = formatTime(current.detail.step.timestamp),
                        mono = true,
                    )
                    TextActionButton(
                        text = "查看任务全部步骤".t(),
                        onClick = { onOpenTask(current.detail.taskId) },
                        modifier = Modifier.padding(top = MimirSpacing.md),
                    )
                }

                Column(modifier = Modifier.padding(top = MimirSpacing.xl)) {
                    AuditStepDetail(step = current.detail.step)
                }
            }
        }

        Spacer(modifier = Modifier.height(MimirSpacing.huge))
    }
}
