package com.neptine.mimir.agent

import android.content.Context
import com.neptine.mimir.core.contract.TaskPlan
import com.neptine.mimir.core.contract.TaskState
import com.neptine.mimir.data.config.ModelConfigRepository
import com.neptine.mimir.data.db.dao.AuditDao
import com.neptine.mimir.data.db.dao.TaskDao
import com.neptine.mimir.data.db.entity.AuditEntity
import com.neptine.mimir.data.db.entity.TaskEntity
import com.neptine.mimir.data.remote.ChatMessage
import com.neptine.mimir.data.remote.ChatOptions
import com.neptine.mimir.data.remote.LlmClient
import com.neptine.mimir.ui.i18n.LanguagePrefs
import com.neptine.mimir.ui.i18n.UiLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 任务结果总结（v0.3.4）。
 *
 * 终态之后补一段"这条任务到底做成了什么"：历史页与任务页都读 `tasks.summary`。
 * 单独起一个应用级作用域，不阻塞任务收尾（收尾还要还输入法、释放副屏）；
 * 调用失败或没有可用模型时写模板兜底，保证终态任务一定有可读的结论。
 */
@Singleton
class TaskSummaryWriter @Inject constructor(
    private val llm: LlmClient,
    private val models: ModelConfigRepository,
    private val taskDao: TaskDao,
    private val auditDao: AuditDao,
    @ApplicationContext private val context: Context,
) {

    /** 应用级作用域：写总结不占用任务协程，进程退出即结束。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 功能：异步补写一条任务的总结
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 重复调用是幂等的：已有总结直接返回，不会重复计费。
     */
    fun schedule(taskId: String) {
        scope.launch { runCatching { write(taskId) } }
    }

    /**
     * 功能：补写一条任务的总结
     * 参数：taskId 任务标识
     * 返回：无；写入 `tasks.summary`
     *
     * 只处理终态任务：未结束的任务没有可总结的结果。
     * 步骤摘要取最近 12 条，足够覆盖一条长任务的收尾段落，也不会把 prompt 撑爆。
     */
    suspend fun write(taskId: String) {
        val task = taskDao.findById(taskId) ?: return
        if (!task.summary.isNullOrBlank()) return
        if (runCatching { TaskState.valueOf(task.state) }.getOrNull()?.isTerminal != true) return

        val english = LanguagePrefs.read(context) == UiLanguage.EN
        val steps = runCatching { auditDao.recentSteps(taskId, RECENT_STEPS) }
            .getOrDefault(emptyList())
            .reversed()
        val plan = runCatching { TaskPlanCodec.decode(task.planJson) }.getOrNull()
        val generated = runCatching { generate(task, plan, steps, english) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

        val state = runCatching { TaskState.valueOf(task.state) }.getOrNull()
        taskDao.updateSummary(
            taskId = taskId,
            summary = generated ?: fallbackSummary(
                english = english,
                state = state,
                input = task.inputText,
                steps = task.stepCount,
                reason = task.suspendReason,
            ),
        )
    }

    /**
     * 功能：调用模型生成总结
     * 参数：task 任务行；plan 计划（可为空）；steps 最近审计步骤；english 是否英文界面
     * 返回：1–3 句总结；没有可用模型时返回空串
     *
     * 用 REVIEWER 角色（通常配便宜文本模型），未配置时退回 EXECUTOR；
     * 不发截图：总结只需要执行轨迹文本，视觉调用会白花一倍成本。
     */
    private suspend fun generate(
        task: TaskEntity,
        plan: TaskPlan?,
        steps: List<AuditEntity>,
        english: Boolean,
    ): String {
        val model = models.resolve(ModelConfigRepository.ModelRole.REVIEWER)
            ?: models.resolve(ModelConfigRepository.ModelRole.EXECUTOR)
            ?: return ""
        val response = llm.chat(
            baseUrl = model.baseUrl,
            apiKey = model.apiKey,
            model = model.model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt(english)),
                ChatMessage(role = "user", content = userPrompt(task, plan, steps, english)),
            ),
            // 总结是纯文本，不需要 JSON；low 档避免为几句话做深度推理。
            options = ChatOptions(reasoningEffort = "low", maxTokens = MAX_TOKENS, forceJson = false),
        )
        val raw = response.choices.firstOrNull()?.message?.content.orEmpty()
        return raw.trim()
            .removeSurrounding("\"")
            .replace(WHITESPACE, " ")
            .take(MAX_CHARS)
    }

    /**
     * 功能：构造总结的系统提示
     * 参数：english 是否英文界面
     * 返回：系统提示文本
     */
    private fun systemPrompt(english: Boolean): String = if (english) {
        "You summarize what a phone automation task actually did. Use only the given execution " +
            "records. Write 2-4 sentences about what was done, where it stopped, and how it ended. " +
            "Do not evaluate, advise, or invent events. Output the summary only, no title or markdown."
    } else {
        "你是手机任务执行器的结果总结器。只根据给定的执行记录写 2–4 句总结，" +
            "说明做了什么、停在哪、结果如何；不要评价、不要建议、不要编造未发生的事。" +
            "只输出总结正文，不要标题或 Markdown。"
    }

    /**
     * 功能：构造总结的用户提示
     * 参数：task 任务行；plan 计划（可为空）；steps 最近步骤；english 是否英文界面
     * 返回：用户提示文本
     *
     * 列表都做了截断：计划最多 10 条、步骤最多 8 条，足以说明任务轮廓。
     */
    private fun userPrompt(
        task: TaskEntity,
        plan: TaskPlan?,
        steps: List<AuditEntity>,
        english: Boolean,
    ): String = buildString {
        val labels = if (english) {
            listOf("Task", "State", "Note", "Plan")
        } else {
            listOf("用户任务", "终态", "结束说明", "计划")
        }
        appendLine("${labels[0]}：${task.inputText.take(INPUT_LIMIT)}")
        appendLine("${labels[1]}：${task.state}")
        task.suspendReason?.takeIf { it.isNotBlank() }?.let {
            appendLine("${labels[2]}：${it.take(INPUT_LIMIT)}")
        }
        plan?.let { current ->
            appendLine("${labels[3]}：")
            current.subtasks.take(PLAN_LIMIT).forEach { subtask ->
                appendLine("- ${subtask.objective.take(INPUT_LIMIT)}")
            }
        }
        if (steps.isNotEmpty()) {
            appendLine(if (english) "Recent steps:" else "最近步骤：")
            steps.takeLast(STEP_LIMIT).forEach { step ->
                val summary = stepSummary(step)
                    ?: step.subGoal.takeIf { it.isNotBlank() }
                    ?: step.actionKind
                appendLine("- ${summary.take(INPUT_LIMIT)}")
            }
        }
    }

    /**
     * 功能：取审计行里模型自述的步骤摘要
     * 参数：step 审计实体
     * 返回：summary 字段；没有或解析失败时为 null
     *
     * 只读 model_output 的 summary 键，不复用 UI 层的同名辅助函数：
     * agent 层不反向依赖 ui 包。
     */
    private fun stepSummary(step: AuditEntity): String? = runCatching {
        Json.parseToJsonElement(step.modelOutput)
            .jsonObject["summary"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        /** 最近步骤取多少条。 */
        const val RECENT_STEPS = 12

        /** prompt 里最多列多少条计划。 */
        const val PLAN_LIMIT = 10

        /** prompt 里最多列多少条步骤。 */
        const val STEP_LIMIT = 8

        /** 单段文本进入 prompt 的最大长度。 */
        const val INPUT_LIMIT = 120

        /** 总结输出上限；超长直接截断，避免模型写成小作文。 */
        const val MAX_CHARS = 200

        /** 输出 token 上限。 */
        const val MAX_TOKENS = 300

        /** 折叠模型输出里的换行与多余空格。 */
        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * 功能：生成模板总结（模型不可用时的兜底）
 * 参数：english 是否英文界面；state 终态；input 任务原话；steps 已执行步数；reason 结束说明
 * 返回：一句可读的结论
 *
 * 模板只陈述事实，不加评价；与模型总结共用同一列，界面不需要区分来源。
 */
internal fun fallbackSummary(
    english: Boolean,
    state: TaskState?,
    input: String,
    steps: Int,
    reason: String?,
): String {
    val title = input.trim().take(60).ifEmpty { if (english) "the task" else "该任务" }
    val note = reason?.takeIf { it.isNotBlank() }
    return when (state) {
        TaskState.DONE, TaskState.DONE_READBACK ->
            if (english) "Finished “$title” in $steps steps." else "已完成「$title」，共 $steps 步。"
        TaskState.COMPLETED_UNVERIFIED, TaskState.VERIFIED_BY_HUMAN ->
            if (english) {
                "Finished “$title” in $steps steps; the result was not fully re-verified."
            } else {
                "已执行完「$title」，共 $steps 步；结果未完全复验。"
            }
        TaskState.FAILED ->
            if (english) {
                "“$title” failed after $steps steps" + (note?.let { ": $it" } ?: ".")
            } else {
                "「$title」执行失败，已走 $steps 步" + (note?.let { "：$it" } ?: "。")
            }
        TaskState.ABORTED ->
            if (english) "“$title” was cancelled." else "「$title」已取消。"
        else ->
            if (english) "“$title” ended after $steps steps." else "「$title」已结束，共 $steps 步。"
    }
}
