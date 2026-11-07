package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.CompletionContract
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.PlanStep
import com.neptine.mimir.core.contract.PlanSubtask
import com.neptine.mimir.core.contract.Preference
import com.neptine.mimir.core.contract.PreferenceCategory
import com.neptine.mimir.core.contract.TaskBudget
import com.neptine.mimir.core.contract.TaskConstraints
import com.neptine.mimir.core.contract.TaskPlan
import com.neptine.mimir.core.contract.TaskSpec
import com.neptine.mimir.data.config.ModelConfigRepository
import com.neptine.mimir.data.remote.ChatMessage
import com.neptine.mimir.data.remote.ChatOptions
import com.neptine.mimir.data.remote.ChatUsage
import com.neptine.mimir.data.remote.LlmClient
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 规划器（设计文档 §2.4 / §1.3.2）。
 *
 * 三件事，都是低频调用：
 * 1. [resolveSpec]：把用户一句话解析成 TaskSpec（任务类型、目标包名、参数、约束、完成边界）。
 *    UI 的"一句话发送"不填表单，这一步就是 §1.3 首步说的"由 Planner 回填"。
 * 2. [planTask]：把 TaskSpec 拆成 TaskPlan——子任务清单 + 每个子任务的有序步骤 + 每步的前后置条件。
 * 3. [replan]：受限重规划。只在入口不可用、缺货、原计划假设失效时调用，沿用原 TaskSpec 的
 *    白名单、参数、预算与授权；给不出可行计划时返回 null，由上层转人工。
 *
 * 规划器不得修改 TaskSpec 的全局约束，也不得新增授权；输出不可解析按"规划失败"处理。
 */
class Planner @Inject constructor(
    private val llm: LlmClient,
    private val models: ModelConfigRepository,
) {

    /**
     * 功能：把自然语言任务解析成 TaskSpec 的字段
     * 参数：inputText 用户原话；taskId 任务标识；launchablePackages 本机可启动的包名
     * 返回：可用的 TaskSpec，以及本次调用的 token 用量（后端不返回时为 null）
     * 抛出：ModelUnavailableException 未配置后端；PlanParseException 输出不可解析
     *
     * 参数留空是刻意的：设计文档要求"参数不完整必须等参数确定后请求当次确认"，
     * 不能在解析阶段凭空补一个商品或地址。
     */
    suspend fun resolveSpec(
        inputText: String,
        taskId: String,
        launchablePackages: List<String>,
        autoApproveL2a: Boolean = false,
    ): Planned<TaskSpec> {
        val model = requireModel()
        val user = buildString {
            appendLine("用户任务：$inputText")
            appendLine()
            appendLine("本机可启动的应用包名（只能从中选一个，选不出就留空）：")
            appendLine(launchablePackages.take(MAX_PACKAGES).joinToString("、"))
            if (launchablePackages.size > MAX_PACKAGES) {
                appendLine("（另有 ${launchablePackages.size - MAX_PACKAGES} 个未列出）")
            }
        }

        // 解析只做字段抽取，不需要长推理；用 low 档，避免每个任务开局白等 5 秒
        val reply = call(
            model, SPEC_SYSTEM_PROMPT, user,
            reasoningEffort = SPEC_REASONING_EFFORT, maxTokens = SPEC_MAX_TOKENS,
        )
        val raw = reply.content
        val payload = LlmJson.extract(raw) ?: throw PlanParseException("任务解析输出不可读：${raw.take(RAW_LIMIT)}")

        val packageName = payload.string("package")
        val resolvedPackage = if (packageName in launchablePackages) packageName else ""

        return Planned(TaskSpec(
            taskId = taskId,
            taskType = payload.string("taskType").ifEmpty { "GENERAL" },
            packageName = resolvedPackage,
            allowedPackages = if (resolvedPackage.isEmpty()) emptyList() else listOf(resolvedPackage),
            inputText = inputText,
            parameters = parametersOf(payload),
            constraints = constraintsOf(payload),
            riskGrants = emptyList(),
            completionContract = completionOf(payload),
            budget = TaskBudget(),
            // 定时任务的预设授权由创建方带进来：解析只是补全包名/参数，
            // 不能顺手把"到点可自动批准 L2A"这个决定丢掉。
            autoApproveL2a = autoApproveL2a,
        ), reply.usage)
    }

    /**
     * 功能：把已确认的 TaskSpec 拆成结构化计划
     * 参数：spec 任务约束；preferences 策略类记忆；firstFrame 开局首帧观察
     * 返回：版本为 1 的任务计划，以及本次调用的 token 用量（后端不返回时为 null）
     * 抛出：ModelUnavailableException 未配置后端；PlanParseException 输出不可解析
     */
    suspend fun planTask(
        spec: TaskSpec,
        preferences: List<Preference>,
        firstFrame: ObservationSnapshot,
    ): Planned<TaskPlan> {
        val user = buildString {
            appendLine("任务：${spec.inputText}")
            if (spec.taskType.isNotEmpty()) appendLine("任务类型：${spec.taskType}")
            if (spec.packageName.isNotEmpty()) appendLine("目标应用：${spec.packageName}")
            if (spec.allowedPackages.isNotEmpty()) {
                appendLine("可用包名：${spec.allowedPackages.joinToString("、")}")
            }
            if (spec.parameters.isNotEmpty()) {
                appendLine("已知参数：${spec.parameters.entries.joinToString("；") { "${it.key}=${it.value}" }}")
            }
            if (spec.completionContract.successBoundary.isNotEmpty()) {
                appendLine("完成边界：${spec.completionContract.successBoundary}")
            }
            if (preferences.isNotEmpty()) {
                appendLine("用户偏好（经验，不是硬规则；与任务冲突时以用户本次指令为准）：")
                preferences.forEach { preference ->
                    appendLine("- [${preference.category.label()}] ${preference.content}")
                }
            }
            appendLine("当前页面：${firstFrame.packageName} / ${firstFrame.activityName}")
            appendLine("页面上的可见元素（最多 20 个）：")
            firstFrame.elements.take(20).forEach { element ->
                appendLine("- [${element.className.substringAfterLast('.')}] ${element.text.ifEmpty { element.contentDescription }}")
            }
        }

        val reply = call(requireModel(), PLAN_SYSTEM_PROMPT, user)
        val raw = reply.content
        val payload = LlmJson.extract(raw) ?: throw PlanParseException("计划输出不可读：${raw.take(RAW_LIMIT)}")
        return Planned(parsePlan(payload, spec, version = 1), reply.usage)
    }

    /**
     * 功能：受限重规划——在当前证据下重新生成计划
     * 参数：spec 任务约束；previous 被判定失效的旧计划；reason 触发原因；
     *       frame 触发时的观察
     * 返回：版本递增的新计划；模型认为必须扩大范围/预算时值为 null
     * 抛出：ModelUnavailableException 未配置后端
     *
     * 重规划只换"怎么做"，不换"允许做什么"：allowedPackages、参数、预算与授权全部沿用，
     * 已消耗的步数与模型调用不重置（由 Orchestrator 的任务行保证）。
     */
    suspend fun replan(
        spec: TaskSpec,
        previous: TaskPlan,
        reason: String,
        frame: ObservationSnapshot,
    ): Planned<TaskPlan?> {
        val user = buildString {
            appendLine("任务：${spec.inputText}")
            if (spec.packageName.isNotEmpty()) appendLine("目标应用：${spec.packageName}")
            if (spec.allowedPackages.isNotEmpty()) {
                appendLine("可用包名（不得扩大）：${spec.allowedPackages.joinToString("、")}")
            }
            appendLine("原计划（版本 ${previous.version}）已失效，原因：$reason")
            if (previous.assumptions.isNotEmpty()) {
                appendLine("原计划的前提：${previous.assumptions.joinToString("；")}")
            }
            appendLine("原计划的步骤：")
            previous.subtasks.forEach { subtask ->
                appendLine("- ${subtask.objective}")
                subtask.steps.forEach { appendLine("  · ${it.intent}") }
            }
            appendLine("当前页面：${frame.packageName} / ${frame.activityName}")
            appendLine("页面上的可见元素（最多 20 个）：")
            frame.elements.take(20).forEach { element ->
                appendLine("- [${element.className.substringAfterLast('.')}] ${element.text.ifEmpty { element.contentDescription }}")
            }
        }

        val reply = call(requireModel(), REPLAN_SYSTEM_PROMPT, user)
        val raw = reply.content
        val payload = LlmJson.extract(raw) ?: throw PlanParseException("重规划输出不可读：${raw.take(RAW_LIMIT)}")
        if (payload.string("give_up").isNotEmpty()) return Planned(null, reply.usage)
        return Planned(parsePlan(payload, spec, previous.version + 1), reply.usage)
    }

    /**
     * 功能：把模型输出的计划 JSON 转成 TaskPlan
     * 参数：payload 模型输出对象；spec 任务约束；version 计划版本
     * 返回：校验后的计划
     * 抛出：PlanParseException 没有可用子任务或步骤时
     *
     * 校验做三件事：包名必须落在 allowedPackages 内、步骤不能为空、后置条件去重去空。
     * 越界的包名不报错而是收敛回 spec.packageName——模型编造包名是常见失败模式，
     * 为此丢掉整份计划不划算。
     */
    private fun parsePlan(payload: JsonObject, spec: TaskSpec, version: Int): TaskPlan {
        val subtaskNodes = runCatching { payload["subtasks"]?.jsonArray }.getOrNull()
            ?: throw PlanParseException("计划输出缺少 subtasks 数组")

        val subtasks = subtaskNodes.take(MAX_SUBTASKS).mapIndexedNotNull { index, element ->
            val node = runCatching { element.jsonObject }.getOrNull() ?: return@mapIndexedNotNull null
            val objective = node.string("objective")
            val steps = runCatching { node["steps"]?.jsonArray }.getOrNull()
                ?.take(MAX_STEPS_PER_SUBTASK)
                ?.mapIndexedNotNull { stepIndex, stepElement ->
                    stepOf(stepElement, "s${index + 1}-${stepIndex + 1}")
                }
                .orEmpty()
            if (objective.isEmpty() || steps.isEmpty()) return@mapIndexedNotNull null
            PlanSubtask(
                subtaskId = "s${index + 1}",
                targetPackage = targetPackageOf(node.string("package"), spec),
                objective = objective,
                dependencies = node.stringList("dependencies"),
                steps = steps,
            )
        }
        if (subtasks.isEmpty()) throw PlanParseException("计划输出没有可用的子任务或步骤")

        return TaskPlan(
            planId = "${spec.taskId}:$version",
            version = version,
            subtasks = subtasks,
            assumptions = payload.stringList("assumptions"),
        )
    }

    /**
     * 功能：把单个步骤节点转成 PlanStep
     * 参数：element 步骤 JSON；stepId 生成的步骤标识
     * 返回：步骤；意图为空时为 null
     */
    private fun stepOf(element: JsonElement, stepId: String): PlanStep? {
        val node = runCatching { element.jsonObject }.getOrNull() ?: return null
        val intent = node.string("intent")
        if (intent.isEmpty()) return null
        return PlanStep(
            stepId = stepId,
            intent = intent,
            parameters = node.stringList("parameters"),
            precondition = node.string("precondition"),
            postcondition = node.stringList("postcondition")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_POSTCONDITIONS),
        )
    }

    /**
     * 功能：把子任务声明的包名收敛到白名单内
     * 参数：declared 模型给出的包名；spec 任务约束
     * 返回：合法的包名；越界或为空时取 spec.packageName
     */
    private fun targetPackageOf(declared: String, spec: TaskSpec): String = when {
        declared.isEmpty() -> spec.packageName
        spec.allowedPackages.isEmpty() -> spec.packageName
        declared in spec.allowedPackages -> declared
        else -> spec.packageName
    }

    /**
     * 功能：取规划角色可用的后端
     * 参数：无
     * 返回：后端参数
     * 抛出：未配置时抛 ModelUnavailableException
     */
    private suspend fun requireModel(): com.neptine.mimir.data.config.ResolvedModel =
        models.resolve(ModelConfigRepository.ModelRole.PLANNER)
            ?: throw ModelUnavailableException("未配置 Planner 可用的模型后端")

    /**
     * 功能：发一次规划请求并取回复正文
     * 参数：model 后端；system 系统提示；user 用户文本
     * 返回：模型回复正文与本次调用的 token 用量
     */
    private suspend fun call(
        model: com.neptine.mimir.data.config.ResolvedModel,
        system: String,
        user: String,
        reasoningEffort: String = REASONING_EFFORT,
        maxTokens: Int = MAX_TOKENS,
    ): PlannerReply {
        val response = llm.chat(
            baseUrl = model.baseUrl,
            apiKey = model.apiKey,
            model = model.model,
            messages = listOf(
                ChatMessage(role = "system", content = system),
                ChatMessage(role = "user", content = user),
            ),
            // 规划要在没有界面的情况下拆步骤、写后置条件，值得比单步决策更强的推理；
            // 但同样要设上限，否则一次规划的长尾会把整轮请求拖到超时。
            options = ChatOptions(reasoningEffort = reasoningEffort, maxTokens = maxTokens),
        )
        return PlannerReply(
            content = response.choices.firstOrNull()?.message?.content.orEmpty(),
            usage = response.usage,
        )
    }

    /**
     * 功能：取结构化参数
     * 参数：payload 模型输出对象
     * 返回：键值对；没有 parameters 字段时为空
     */
    private fun parametersOf(payload: JsonObject): Map<String, String> =
        runCatching {
            payload["parameters"]?.jsonObject?.mapValues { (_, value) ->
                value.jsonPrimitive.contentOrNull.orEmpty()
            }.orEmpty()
        }.getOrDefault(emptyMap())

    /**
     * 功能：取全局约束
     * 参数：payload 模型输出对象
     * 返回：TaskConstraints
     */
    private fun constraintsOf(payload: JsonObject): TaskConstraints {
        val node = runCatching { payload["constraints"]?.jsonObject }.getOrNull()
            ?: return TaskConstraints()
        return TaskConstraints(
            maxAmountCents = node.string("maxAmountCents").toLongOrNull(),
            forbiddenActions = node.stringList("forbiddenActions"),
            notes = node.stringList("notes"),
        )
    }

    /**
     * 功能：取完成契约
     * 参数：payload 模型输出对象
     * 返回：CompletionContract
     */
    private fun completionOf(payload: JsonObject): CompletionContract {
        val node = runCatching { payload["completion"]?.jsonObject }.getOrNull()
            ?: return CompletionContract(successBoundary = "", requiredFields = emptyList())
        return CompletionContract(
            successBoundary = node.string("successBoundary"),
            requiredFields = node.stringList("requiredFields"),
            allowedHandoffPoints = node.stringList("allowedHandoffPoints"),
        )
    }

    private companion object {
        /**
         * 计划生成与重规划的思考强度。
         *
         * 规划要在看不到界面的情况下拆步骤、写可核对的后置条件，判断质量直接影响后面
         * 每一次动作；这里保留 high（默认 max 对规划收益有限但很贵），单步决策才降到 low。
         */
        const val REASONING_EFFORT = "high"

        /** 规划输出上限：一份计划通常 1–2 KB，留足思考余量即可。 */
        const val MAX_TOKENS = 4096

        /**
         * 任务解析的思考强度。
         *
         * resolveSpec 只是把一句话映射成包名/taskType/参数，不需要长推理链；
         * 实测 high 档单次 ttfb 6.8–10.7 秒、输出 266–347 token，属于纯浪费。
         */
        const val SPEC_REASONING_EFFORT = "low"

        /** 任务解析输出上限：一段几百字节的 JSON 足够。 */
        const val SPEC_MAX_TOKENS = 512

        /** 送给模型的候选包名上限；整机几百个包名会挤爆上下文。 */
        const val MAX_PACKAGES = 120

        /** 一份计划的子任务上限；再多也不是"简单演示任务"了。 */
        const val MAX_SUBTASKS = 6

        /** 单个子任务的步骤上限；超出部分直接截断，避免计划把预算撑爆。 */
        const val MAX_STEPS_PER_SUBTASK = 8

        /** 单步后置条件最多保留几条文本片段。 */
        const val MAX_POSTCONDITIONS = 3

        /** 异常信息里回显的输出上限。 */
        const val RAW_LIMIT = 200
    }
}

/** 规划器一次调用的回复与用量；用量随结果一起交给上层落库。 */
internal data class PlannerReply(val content: String, val usage: ChatUsage?)

/** 规划器产出的结果与本次调用的用量。 */
data class Planned<T>(val value: T, val usage: ChatUsage?)

/** 规划输出不可解析。 */
class PlanParseException(message: String) : IllegalStateException(message)

/** 取字符串字段的公共小工具；Planner 与 ActionProtocol 共用。 */
internal fun JsonObject.string(key: String): String =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull.orEmpty() }.getOrDefault("")

/** 取字符串数组的公共小工具。 */
internal fun JsonObject.stringList(key: String): List<String> =
    runCatching { this[key]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull } }
        .getOrDefault(emptyList())

/** 从模型输出里截取 JSON 对象的公共小工具。 */
internal object LlmJson {
    /**
     * 功能：截出原始输出里的第一个 JSON 对象
     * 参数：raw 模型原始输出
     * 返回：解析后的对象；找不到或不合法时为 null
     */
    fun extract(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        }.getOrNull()
    }
}

/** 任务解析系统提示。 */
private val SPEC_SYSTEM_PROMPT = """
你是任务解析器。把用户的一句话任务解析成 JSON，只输出 JSON，不要解释。
{"taskType":"<大写下划线类型，如 ORDER_FOOD / NAVIGATE_SETTINGS / SEND_MESSAGE>",
 "package":"<从给定包名列表里选一个；选不出就空串>",
 "parameters":{<结构化参数，如 商品/数量/收件人/关键词；没有就空对象>},
 "constraints":{"maxAmountCents":<金额上限，分为单位，没有就 null>,
                "forbiddenActions":[<用户明说不要做的事>],"notes":[<其他约束>]},
 "completion":{"successBoundary":"<怎样算完成>","requiredFields":[<完成时必须核对的字段名>]}}
规则：
1. package 只能取用户消息里列出的包名，不得编造。
2. 参数不确定就不要猜；宁可留空。
3. 不得把"下单"扩大成"付款"。
4. requiredFields 必须是**任务完成时页面上真的能看到的文本片段**（如「我的设备」「订单已提交」「黄焖鸡米饭」），
   最多 3 项；不要写 currentScreen、status、page 这类字段名或变量名——那些在界面上读不到，
   会让完成校验必然失败。想不出可核对的文本就留空数组。
5. **以界面语言为准，不要翻译用户原话**。用户用英文提问而界面是中文时，字段仍然要写界面上的中文文本
   （例如小米设置里是「我的设备」「OS 版本」，不是 "About phone" / "MIUI version"）；
   本地断言按字面匹配，写英文等于把一次成功的任务判成不符。
""".trimIndent()

/** 计划生成系统提示。 */
private val PLAN_SYSTEM_PROMPT = """
你是手机任务规划器。把任务拆成 1–4 个有序子任务，每个子任务给出可执行的步骤。只输出 JSON，不要解释。
{"assumptions":["<计划依赖的前提，例如「已登录」「地址已存在」>"],
 "subtasks":[{"objective":"<子任务目标>","package":"<目标包名，可为空>",
   "steps":[{"intent":"<这一步要做什么>",
             "parameters":["<用到的参数键名，可空>"],
             "precondition":"<进入这一步前页面上应满足什么，可空>",
             "postcondition":["<这一步完成后页面上才会新出现的文本片段>"]}]}]}
规则：
1. 子任务必须能在目标应用内通过界面操作完成；不得改变任务范围，不得新增用户没要求的动作。
2. 每个子任务 2–6 步，每步只做一件可观察的事；总步数控制在 10 步以内。
3. postcondition 必须是**执行完这一步之后页面上才会新出现的文本**（例如「设备名称」「订单已提交」），
   不要把进入这一步时就已经存在的文字写进去；实在给不出可核对的文本就留空数组。
   优先**逐字复用**上面列出的页面文本；如果这一步要进入一个你还没见过的页面，就留空数组——
   猜一个译文或同义词（例如把「我的设备」写成「关于手机」）会让本地断言必然失败。
4. 不得把"下单"扩大成"付款"；不得替用户决定地址、规格、数量。
5. 最后一步应到达任务的完成边界。
""".trimIndent()

/** 受限重规划系统提示。 */
private val REPLAN_SYSTEM_PROMPT = """
你是手机任务规划器，正在做受限重规划。只输出 JSON，不要解释。
原计划已失效。请输出一份新计划，schema 与首次规划相同：
{"assumptions":["..."],
 "subtasks":[{"objective":"...","package":"...",
   "steps":[{"intent":"...","parameters":[],"precondition":"...","postcondition":["..."]}]}]}
如果继续执行必须扩大包名范围、提高预算、修改用户给定的关键参数，或必须由用户本人决定，
改为输出 {"give_up":"<需要用户做什么>"}。

规则：
1. 可用包名不得扩大；不得新增授权；不得改变用户要求的任务范围。
2. postcondition 必须是执行完这一步之后页面上才会新出现的文本，**优先逐字复用当前页面文本**；
   要进入没见过的页面就留空数组，不要猜译文或同义词。
3. 从当前页面出发，不要假装已经完成了没做过的步骤。
""".trimIndent()

/**
 * 功能：偏好类别的中文标签
 * 参数：无（扩展函数）
 * 返回：注入 prompt 用的短标签
 */
private fun PreferenceCategory.label(): String = when (this) {
    PreferenceCategory.PLATFORM -> "平台"
    PreferenceCategory.MERCHANT -> "常点"
    PreferenceCategory.TONE -> "语气"
    PreferenceCategory.ORDER_STYLE -> "点餐方式"
    PreferenceCategory.GENERAL -> "经验"
}
