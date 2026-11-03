package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.ActionKind
import com.neptine.mimir.core.contract.CompletionContract
import com.neptine.mimir.core.contract.GrantSource
import com.neptine.mimir.core.contract.RiskGrant
import com.neptine.mimir.core.contract.TaskBudget
import com.neptine.mimir.core.contract.TaskConstraints
import com.neptine.mimir.core.contract.TaskSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * TaskSpec 与 `tasks.spec_json` 之间的编解码（设计文档 §1.3.1）。
 *
 * TaskSpec 是契约层产物，**没有加 @Serializable**：给它加序列化注解属于改契约，
 * 因此这里用显式字段映射，编解码规则集中在一处，避免各调用点各写一份 JSON。
 */
object TaskSpecCodec {

    /**
     * 功能：把 TaskSpec 编码为紧凑 JSON
     * 参数：spec 任务约束
     * 返回：JSON 文本
     */
    fun encode(spec: TaskSpec): String = buildJsonObject {
        put("taskId", spec.taskId)
        put("taskType", spec.taskType)
        put("packageName", spec.packageName)
        putJsonArray("allowedPackages") {
            spec.allowedPackages.forEach { add(it) }
        }
        put("inputText", spec.inputText)
        putJsonObject("parameters") {
            spec.parameters.forEach { (key, value) -> put(key, value) }
        }
        putJsonObject("constraints") {
            put("maxAmountCents", spec.constraints.maxAmountCents)
            putJsonArray("forbiddenActions") {
                spec.constraints.forbiddenActions.forEach { add(it) }
            }
            putJsonArray("notes") {
                spec.constraints.notes.forEach { add(it) }
            }
        }
        putJsonArray("riskGrants") {
            spec.riskGrants.forEach { grant ->
                add(
                    buildJsonObject {
                        put("grantId", grant.grantId)
                        put("actionKind", grant.actionKind.name)
                        put("paramsDigest", grant.paramsDigest)
                        put("targetPackage", grant.targetPackage)
                        put("scopeText", grant.scopeText)
                        put("source", grant.source.name)
                        put("issuedAt", grant.issuedAt)
                        put("expiresAt", grant.expiresAt)
                    },
                )
            }
        }
        putJsonObject("completionContract") {
            put("successBoundary", spec.completionContract.successBoundary)
            putJsonArray("requiredFields") {
                spec.completionContract.requiredFields.forEach { add(it) }
            }
            putJsonArray("allowedHandoffPoints") {
                spec.completionContract.allowedHandoffPoints.forEach { add(it) }
            }
            put("requireSingleResult", spec.completionContract.requireSingleResult)
        }
        putJsonObject("budget") {
            put("maxSteps", spec.budget.maxSteps)
            put("maxDurationMs", spec.budget.maxDurationMs)
            put("maxModelCalls", spec.budget.maxModelCalls)
            put("maxStepDurationMs", spec.budget.maxStepDurationMs)
        }
    }.toString()

    /**
     * 功能：从 JSON 还原 TaskSpec
     * 参数：text spec_json 文本
     * 返回：TaskSpec；字段缺失时取契约默认值
     */
    fun decode(text: String): TaskSpec {
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))

        return TaskSpec(
            taskId = root.string("taskId"),
            taskType = root.string("taskType"),
            packageName = root.string("packageName"),
            allowedPackages = root.stringList("allowedPackages"),
            inputText = root.string("inputText"),
            parameters = root.objectOrEmpty("parameters").mapValues { it.value.jsonPrimitive.content },
            constraints = decodeConstraints(root.objectOrEmpty("constraints")),
            riskGrants = decodeGrants(root),
            completionContract = decodeCompletion(root.objectOrEmpty("completionContract")),
            budget = decodeBudget(root.objectOrEmpty("budget")),
        )
    }

    /**
     * 功能：还原全局约束
     * 参数：node constraints 对象
     * 返回：TaskConstraints
     */
    private fun decodeConstraints(node: JsonObject) = TaskConstraints(
        maxAmountCents = node["maxAmountCents"]?.jsonPrimitive?.longOrNull,
        forbiddenActions = node.stringList("forbiddenActions"),
        notes = node.stringList("notes"),
    )

    /**
     * 功能：还原完成契约
     * 参数：node completionContract 对象
     * 返回：CompletionContract
     */
    private fun decodeCompletion(node: JsonObject) = CompletionContract(
        successBoundary = node.string("successBoundary"),
        requiredFields = node.stringList("requiredFields"),
        allowedHandoffPoints = node.stringList("allowedHandoffPoints"),
        requireSingleResult = node["requireSingleResult"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true,
    )

    /**
     * 功能：还原预算
     * 参数：node budget 对象
     * 返回：TaskBudget
     */
    private fun decodeBudget(node: JsonObject) = TaskBudget(
        maxSteps = node["maxSteps"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 40,
        maxDurationMs = node["maxDurationMs"]?.jsonPrimitive?.longOrNull ?: 10 * 60 * 1000L,
        maxModelCalls = node["maxModelCalls"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 60,
        maxStepDurationMs = node["maxStepDurationMs"]?.jsonPrimitive?.longOrNull ?: 20_000L,
    )

    /**
     * 功能：还原一次性授权列表
     * 参数：root spec 根对象
     * 返回：授权列表
     */
    private fun decodeGrants(root: JsonObject): List<RiskGrant> =
        runCatching {
            root["riskGrants"]?.jsonArray.orEmpty().map { element ->
                val node = element.jsonObject
                RiskGrant(
                    grantId = node.string("grantId"),
                    actionKind = ActionKind.valueOf(node.string("actionKind").ifEmpty { "TAP" }),
                    paramsDigest = node.string("paramsDigest"),
                    targetPackage = node.string("targetPackage"),
                    scopeText = node.string("scopeText"),
                    source = GrantSource.valueOf(node.string("source").ifEmpty { "AUTO" }),
                    issuedAt = node["issuedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    expiresAt = node["expiresAt"]?.jsonPrimitive?.longOrNull,
                )
            }
        }.getOrDefault(emptyList())

    /** 取字符串字段；缺失或非字符串时为空串。 */
    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    /** 取子对象；缺失时为 null。 */
    private fun JsonObject.objectOrEmpty(key: String): JsonObject =
        runCatching { this[key]?.jsonObject }.getOrNull() ?: JsonObject(emptyMap())

    /** 取字符串数组；缺失或类型不符时为空列表。 */
    private fun JsonObject.stringList(key: String): List<String> =
        runCatching { this[key]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull } }
            .getOrDefault(emptyList())
}
