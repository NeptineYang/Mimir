package com.neptine.mimir.ui.component

import com.neptine.mimir.ui.i18n.t
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirRadius
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.viewmodel.AuditRow
import com.neptine.mimir.ui.viewmodel.formatTime

/**
 * 功能：渲染审计时间轴上的一步（摘要行）
 * 参数：step 审计步骤；selected 是否为当前展开证据的一步；title 主标题；
 *       showActionKind 是否在元信息里补动作类型；showGeneration 是否显示副屏代次；
 *       onClick 点击回调，null 表示只看摘要
 * 返回：无
 *
 * 历史页与"按任务回放"共用这一行。历史页传 subGoal 作标题（"点了什么"），
 * 动作类型退到元信息；回放页保留动作类型作标题，方便按协议核对。
 * 两处共用同一行，字段增减时不会漏改其中一处。
 */
@Composable
fun AuditStepRow(
    step: AuditRow,
    selected: Boolean = false,
    title: String = step.actionKind,
    showActionKind: Boolean = false,
    showGeneration: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalMimirColors.current
    val meta = buildList {
        if (showActionKind) add(step.actionKind)
        add(formatTime(step.timestamp))
        if (showGeneration) add("displayGen ${step.displayGeneration}")
    }.joinToString(" · ")

    MimirListRow(
        title = title,
        leading = {
            MonoLabel(
                text = "STEP ${step.stepIndex}",
                color = if (selected) colors.ink else colors.muted,
            )
        },
        meta = meta,
        trailing = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
            ) {
                step.riskLevel?.let { RiskChip(level = it) }
                Text(
                    text = step.decision,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.slate,
                )
            }
        },
        onClick = onClick,
    )
}

/**
 * 功能：渲染某一步的完整证据面板
 * 参数：step 选中的审计步骤
 * 返回：无
 *
 * 面板用暖灰底而不是再加一层标题与分隔线：它属于"当前这一步"的补充材料，
 * 视觉上必须从时间轴里浮出来，否则会被读成又一段并列的步骤。
 */
@Composable
fun AuditStepDetail(step: AuditRow) {
    val colors = LocalMimirColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MimirSpacing.md)
            .clip(RoundedCornerShape(MimirRadius.md))
            .background(colors.softStone.copy(alpha = 0.55f))
            .padding(MimirSpacing.lg),
    ) {
        Text(
            text = "第 ${step.stepIndex} 步证据",
            style = MaterialTheme.typography.titleSmall,
            color = colors.ink,
        )
        KeyValueRow(key = "当前步骤".t(), value = step.subGoal)
        KeyValueRow(key = "动作类型".t(), value = step.actionKind, mono = true)
        KeyValueRow(key = "参数摘要".t(), value = step.paramsDigest, mono = true)
        KeyValueRow(
            key = "系统返回".t(),
            value = when (step.systemReturnValue) {
                true -> "true（仅代表系统接收）".t()
                false -> "false（注入失败，可重试与否取决于幂等性）".t()
                null -> "—"
            },
        )
        KeyValueRow(key = "意图状态".t(), value = step.intentStatus ?: "—", mono = true)
        KeyValueRow(key = "动作结果".t(), value = step.outcome ?: "—", mono = true)
        KeyValueRow(key = "幂等分类".t(), value = step.idempotency, mono = true)
        KeyValueRow(key = "风险等级".t(), value = step.riskLevel?.name ?: "—", mono = true)
        KeyValueRow(key = "处置结论".t(), value = step.decision, mono = true)
        KeyValueRow(key = "复核理由".t(), value = step.reviewReason ?: "—")
        KeyValueRow(key = "目标文本".t(), value = step.targetText ?: "—")
        KeyValueRow(key = "目标指纹".t(), value = step.targetFingerprint ?: "—", mono = true)
        KeyValueRow(key = "授权标识".t(), value = step.grantId ?: "—", mono = true)
        KeyValueRow(key = "图像 token".t(), value = "${step.imageTokens}", mono = true)
        KeyValueRow(key = "验证证据".t(), value = step.verificationEvidence ?: "—")
        KeyValueRow(key = "前帧路径".t(), value = step.screenshotBefore ?: "—", mono = true)
        KeyValueRow(key = "后帧路径".t(), value = step.screenshotAfter ?: "—", mono = true)

        Text(
            text = "模型输出".t(),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.slate,
            modifier = Modifier.padding(top = MimirSpacing.md),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MimirSpacing.sm)
                .clip(RoundedCornerShape(MimirRadius.xs))
                .background(colors.surface)
                .horizontalScroll(rememberScrollState())
                .padding(MimirSpacing.md),
        ) {
            Text(
                text = step.modelOutput,
                style = MaterialTheme.typography.labelMedium,
                color = colors.ink,
                softWrap = false,
                maxLines = 1,
            )
        }
        Text(
            text = "截图预览需要图像加载组件，本轮不引入，因此只显示路径。".t(),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.padding(top = MimirSpacing.sm),
        )
    }
}
