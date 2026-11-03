package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.PlanCursor
import com.neptine.mimir.core.contract.PlanStep
import com.neptine.mimir.core.contract.PlanSubtask
import com.neptine.mimir.core.contract.TaskPlan
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * TaskPlan 与 `tasks.plan_json` 之间的编解码（设计文档 §1.3.2）。
 *
 * 与 TaskSpecCodec 同样的理由：契约层不加 @Serializable，字段映射集中在一处，
 * 避免各调用点各写一份 JSON。计划是"执行到哪"的载体，编码必须无损。
 */
object TaskPlanCodec {

    /**
     * 功能：把计划编码为紧凑 JSON
     * 参数：plan 任务计划
     * 返回：JSON 文本
     */
    fun encode(plan: TaskPlan): String = buildJsonObject {
        put("planId", plan.planId)
        put("version", plan.version)
        putJsonArray("assumptions") {
            plan.assumptions.forEach { add(it) }
        }
        putJsonArray("subtasks") {
            plan.subtasks.forEach { subtask ->
                add(
                    buildJsonObject {
                        put("subtaskId", subtask.subtaskId)
                        put("targetPackage", subtask.targetPackage)
                        put("objective", subtask.objective)
                        putJsonArray("dependencies") {
                            subtask.dependencies.forEach { add(it) }
                        }
                        putJsonArray("steps") {
                            subtask.steps.forEach { step ->
                                add(
                                    buildJsonObject {
                                        put("stepId", step.stepId)
                                        put("intent", step.intent)
                                        putJsonArray("parameters") {
                                            step.parameters.forEach { add(it) }
                                        }
                                        put("precondition", step.precondition)
                                        putJsonArray("postcondition") {
                                            step.postcondition.forEach { add(it) }
                                        }
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
    }.toString()

    /**
     * 功能：从 JSON 还原计划
     * 参数：text plan_json 文本
     * 返回：计划；文本为空或结构不可用时为 null（调用方据此重新规划）
     */
    fun decode(text: String): TaskPlan? {
        if (text.isEmpty()) return null
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject }
            .getOrNull() ?: return null

        val subtasks = runCatching {
            root["subtasks"]?.jsonArray.orEmpty().mapNotNull { element ->
                val node = element.jsonObject
                val steps = node["steps"]?.jsonArray.orEmpty().mapNotNull { stepElement ->
                    val step = stepElement.jsonObject
                    val intent = step.string("intent")
                    if (intent.isEmpty()) return@mapNotNull null
                    PlanStep(
                        stepId = step.string("stepId"),
                        intent = intent,
                        parameters = step.stringList("parameters"),
                        precondition = step.string("precondition"),
                        postcondition = step.stringList("postcondition"),
                    )
                }
                if (steps.isEmpty()) return@mapNotNull null
                PlanSubtask(
                    subtaskId = node.string("subtaskId"),
                    targetPackage = node.string("targetPackage"),
                    objective = node.string("objective"),
                    dependencies = node.stringList("dependencies"),
                    steps = steps,
                )
            }
        }.getOrNull().orEmpty()

        if (subtasks.isEmpty()) return null
        return TaskPlan(
            planId = root.string("planId"),
            version = root["version"]?.jsonPrimitive?.intOrNull ?: 1,
            subtasks = subtasks,
            assumptions = root.stringList("assumptions"),
        )
    }

    /**
     * 功能：把任务行上的游标字段还原为游标
     * 参数：plan 已还原的计划；planVersion 任务行上的版本；subtaskIndex 子任务下标；stepIndex 步骤下标
     * 返回：与计划版本一致的游标；版本不符或越界时退回起始游标
     *
     * 版本不符说明计划被重规划过而游标没跟上，这时按起始游标重来比按旧下标硬走安全。
     */
    fun cursorOf(plan: TaskPlan, planVersion: Int, subtaskIndex: Int, stepIndex: Int): PlanCursor {
        if (planVersion != plan.version) return PlanCursor.startOf(plan)
        val cursor = PlanCursor(plan.version, subtaskIndex, stepIndex)
        if (cursor.subtaskIndex !in plan.subtasks.indices) return PlanCursor.startOf(plan)
        val steps = plan.subtasks[cursor.subtaskIndex].steps
        if (cursor.stepIndex !in steps.indices) return PlanCursor.startOf(plan)
        return cursor
    }

    /** 取字符串字段；缺失或非字符串时为空串。 */
    private fun JsonObject.string(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull.orEmpty() }.getOrDefault("")

    /** 取字符串数组；缺失或类型不符时为空列表。 */
    private fun JsonObject.stringList(key: String): List<String> =
        runCatching { this[key]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull } }
            .getOrDefault(emptyList())
}
