package com.neptine.mimir.data.memory

import com.neptine.mimir.core.contract.ActionKind
import com.neptine.mimir.core.contract.Anchor
import com.neptine.mimir.core.contract.AnchorLevel
import com.neptine.mimir.core.contract.MemorySource
import com.neptine.mimir.core.contract.MemoryStatus
import com.neptine.mimir.core.contract.Preference
import com.neptine.mimir.core.contract.Procedure
import com.neptine.mimir.core.contract.ProcedureStep
import com.neptine.mimir.data.db.entity.PreferenceEntity
import com.neptine.mimir.data.db.entity.ProcedureEntity
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 配方与 JSON 之间的唯一转换点（设计文档 §2.5.2）。
 *
 * 两个方向都要，且必须是同一份映射：
 * - 表 ⇄ 契约：`mem_procedure` 的 JSON 列（前置/步骤/后置/页面签名）由这里读写；
 * - 契约 ⇄ 导出文件：白盒编辑的导出/导入走这里，导出的东西要能原样导回来。
 *
 * 导出**不包含凭据**：凭据库不在记忆体系里，也不属于该导出范围（§2.5.7）。
 */
object ProcedureCodec {

    /**
     * 功能：把配方表行还原成契约对象
     * 参数：entity 数据库行
     * 返回：配方；JSON 列损坏时按空列表处理，不抛异常
     */
    fun fromEntity(entity: ProcedureEntity): Procedure = Procedure(
        id = entity.id,
        packageName = entity.packageName,
        taskType = entity.taskType,
        stableKey = entity.stableKey,
        category = entity.category,
        title = entity.title,
        precondition = decodeAnchors(entity.preconditionJson),
        steps = decodeSteps(entity.stepsJson),
        postcondition = decodeStrings(entity.postconditionJson),
        pageSignature = decodeStrings(entity.pageSignatureJson),
        source = enumOrDefault(entity.source, MemorySource.USER_WRITTEN),
        status = enumOrDefault(entity.status, MemoryStatus.DISABLED),
        successCount = entity.successCount,
        failCount = entity.failCount,
        confidence = entity.confidence,
        appVersionCode = entity.appVersionCode,
        validUntil = entity.validUntil,
        lastVerifiedAt = entity.lastVerifiedAt,
        createdAt = entity.createdAt,
        lastTaskId = entity.lastTaskId,
    )

    /**
     * 功能：把契约对象写成配方表行
     * 参数：procedure 配方
     * 返回：数据库行
     */
    fun toEntity(procedure: Procedure): ProcedureEntity = ProcedureEntity(
        id = procedure.id,
        packageName = procedure.packageName,
        taskType = procedure.taskType,
        stableKey = procedure.stableKey,
        category = procedure.category,
        title = procedure.title,
        preconditionJson = buildJsonArray {
            procedure.precondition.forEach { add(anchorJson(it)) }
        }.toString(),
        stepsJson = buildJsonArray {
            procedure.steps.forEach { add(stepJson(it)) }
        }.toString(),
        postconditionJson = stringsJson(procedure.postcondition),
        pageSignatureJson = stringsJson(procedure.pageSignature),
        source = procedure.source.name,
        status = procedure.status.name,
        successCount = procedure.successCount,
        failCount = procedure.failCount,
        confidence = procedure.confidence,
        appVersionCode = procedure.appVersionCode,
        validUntil = procedure.validUntil,
        lastVerifiedAt = procedure.lastVerifiedAt,
        createdAt = procedure.createdAt,
        lastTaskId = procedure.lastTaskId,
    )

    /**
     * 功能：把策略表行还原成契约对象
     * 参数：entity 数据库行
     * 返回：策略
     */
    fun fromEntity(entity: PreferenceEntity): Preference = Preference(
        id = entity.id,
        scope = entity.scope,
        title = entity.title,
        content = entity.content,
        source = enumOrDefault(entity.source, MemorySource.USER_WRITTEN),
        createdAt = entity.createdAt,
    )

    /**
     * 功能：把策略写成表行
     * 参数：preference 策略
     * 返回：数据库行
     */
    fun toEntity(preference: Preference): PreferenceEntity = PreferenceEntity(
        id = preference.id,
        scope = preference.scope,
        title = preference.title,
        content = preference.content,
        source = preference.source.name,
        createdAt = preference.createdAt,
    )

    /**
     * 功能：把配方列表编码成可导出的 JSON 文本
     * 参数：procedures 配方列表
     * 返回：形如 `{"version":1,"procedures":[...]}` 的文本
     */
    fun encodeBundle(procedures: List<Procedure>): String = buildJsonObject {
        put("version", BUNDLE_VERSION)
        putJsonArray("procedures") {
            procedures.forEach { add(procedureJson(it)) }
        }
    }.toString()

    /**
     * 功能：解析导入文件
     * 参数：text 文件文本
     * 返回：配方列表；格式不符时返回 null（调用方据此提示"不是本应用导出的文件"）
     */
    fun decodeBundle(text: String): List<Procedure>? {
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject }
            .getOrNull() ?: return null
        val nodes = runCatching { root["procedures"]?.jsonArray }.getOrNull() ?: return null
        return nodes.mapNotNull { node -> runCatching { procedureOf(node.jsonObject) }.getOrNull() }
    }

    /**
     * 功能：把一条配方编成 JSON 对象
     * 参数：procedure 配方
     * 返回：JSON 对象
     */
    private fun procedureJson(procedure: Procedure): JsonObject = buildJsonObject {
        put("id", procedure.id)
        put("packageName", procedure.packageName)
        put("taskType", procedure.taskType)
        put("stableKey", procedure.stableKey)
        put("category", procedure.category)
        put("title", procedure.title)
        putJsonArray("precondition") {
            procedure.precondition.forEach { add(anchorJson(it)) }
        }
        putJsonArray("steps") {
            procedure.steps.forEach { add(stepJson(it)) }
        }
        put("postcondition", stringsJson(procedure.postcondition))
        put("pageSignature", stringsJson(procedure.pageSignature))
        put("source", procedure.source.name)
        put("status", procedure.status.name)
        put("successCount", procedure.successCount)
        put("failCount", procedure.failCount)
        put("confidence", procedure.confidence)
        put("appVersionCode", procedure.appVersionCode)
        put("validUntil", procedure.validUntil)
        put("lastVerifiedAt", procedure.lastVerifiedAt)
        put("createdAt", procedure.createdAt)
    }

    /**
     * 功能：把 JSON 对象还原成配方
     * 参数：node JSON 对象
     * 返回：配方
     * 抛出：缺关键字段或类型不符时由 runCatching 的调用方吞掉，这里不兜底
     */
    private fun procedureOf(node: JsonObject): Procedure = Procedure(
        id = node.string("id"),
        packageName = node.string("packageName"),
        taskType = node.string("taskType"),
        stableKey = node.string("stableKey"),
        category = node.string("category"),
        title = node.string("title"),
        precondition = node["precondition"]?.jsonArray.orEmpty()
            .mapNotNull { runCatching { anchorOf(it.jsonObject) }.getOrNull() },
        steps = node["steps"]?.jsonArray.orEmpty()
            .mapNotNull { runCatching { stepOf(it.jsonObject) }.getOrNull() },
        postcondition = node["postcondition"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull },
        pageSignature = node["pageSignature"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull },
        source = enumOrDefault(node.string("source"), MemorySource.USER_WRITTEN),
        status = enumOrDefault(node.string("status"), MemoryStatus.DISABLED),
        successCount = node["successCount"]?.jsonPrimitive?.intOrNull ?: 0,
        failCount = node["failCount"]?.jsonPrimitive?.intOrNull ?: 0,
        confidence = node["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        appVersionCode = node["appVersionCode"]?.jsonPrimitive?.intOrNull ?: 0,
        validUntil = node["validUntil"]?.jsonPrimitive?.longOrNull,
        lastVerifiedAt = node["lastVerifiedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        createdAt = node["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
    )

    /**
     * 功能：编码单条锚点
     * 参数：anchor 锚点
     * 返回：JSON 对象
     */
    private fun anchorJson(anchor: Anchor): JsonObject = buildJsonObject {
        put("level", anchor.level.name)
        put("resourceId", anchor.resourceId)
        put("text", anchor.text)
        put("contentDescription", anchor.contentDescription)
        put("className", anchor.className)
        put("relativeTo", anchor.relativeTo)
        put("offsetDpX", anchor.offsetDpX)
        put("offsetDpY", anchor.offsetDpY)
        put("validUntil", anchor.validUntil)
    }

    /**
     * 功能：解析单条锚点
     * 参数：node JSON 对象
     * 返回：锚点
     */
    private fun anchorOf(node: JsonObject): Anchor = Anchor(
        level = enumOrDefault(node.string("level"), AnchorLevel.A2),
        resourceId = node.stringOrNull("resourceId"),
        text = node.stringOrNull("text"),
        contentDescription = node.stringOrNull("contentDescription"),
        className = node.stringOrNull("className"),
        relativeTo = node.stringOrNull("relativeTo"),
        offsetDpX = node["offsetDpX"]?.jsonPrimitive?.intOrNull,
        offsetDpY = node["offsetDpY"]?.jsonPrimitive?.intOrNull,
        validUntil = node["validUntil"]?.jsonPrimitive?.longOrNull,
    )

    /**
     * 功能：编码配方中的一步
     * 参数：step 步骤
     * 返回：JSON 对象
     */
    private fun stepJson(step: ProcedureStep): JsonObject = buildJsonObject {
        put("actionKind", step.actionKind.name)
        put("anchor", step.anchor?.let { anchorJson(it) } ?: JsonNull)
        put("pagePath", step.pagePath)
        putJsonObject("parameterSlots") {
            step.parameterSlots.forEach { (key, value) -> put(key, value) }
        }
    }

    /**
     * 功能：解析配方中的一步
     * 参数：node JSON 对象
     * 返回：步骤
     */
    private fun stepOf(node: JsonObject): ProcedureStep = ProcedureStep(
        actionKind = enumOrDefault(node.string("actionKind"), ActionKind.TAP),
        anchor = runCatching { node["anchor"]?.jsonObject }.getOrNull()?.let { anchorOf(it) },
        pagePath = node.stringOrNull("pagePath"),
        parameterSlots = runCatching {
            node["parameterSlots"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        }.getOrDefault(emptyMap()),
    )

    /**
     * 功能：把字符串列表编成 JSON 数组文本，直接写进表列
     * 参数：values 字符串列表
     * 返回：JSON 文本
     */
    private fun stringsJson(values: List<String>): String = buildJsonArray {
        values.forEach { add(it) }
    }.toString()

    /**
     * 功能：解析表列里的字符串数组
     * 参数：text JSON 文本
     * 返回：字符串列表；损坏或为空时返回空列表
     */
    private fun decodeStrings(text: String): List<String> =
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray
                .mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrDefault(emptyList())

    /**
     * 功能：解析表列里的锚点数组
     * 参数：text JSON 文本
     * 返回：锚点列表；损坏或为空时返回空列表
     */
    private fun decodeAnchors(text: String): List<Anchor> =
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray
                .mapNotNull { runCatching { anchorOf(it.jsonObject) }.getOrNull() }
        }.getOrDefault(emptyList())

    /**
     * 功能：解析表列里的步骤数组
     * 参数：text JSON 文本
     * 返回：步骤列表；损坏或为空时返回空列表
     */
    private fun decodeSteps(text: String): List<ProcedureStep> =
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray
                .mapNotNull { runCatching { stepOf(it.jsonObject) }.getOrNull() }
        }.getOrDefault(emptyList())

    /** 取字符串字段；缺失或非字符串时为空串。 */
    private fun JsonObject.string(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull.orEmpty() }.getOrDefault("")

    /** 取可空字符串字段；缺失或值为 null 时为 null。 */
    private fun JsonObject.stringOrNull(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    /** 按名字取枚举；名字不认识时退回默认值，避免旧文件让导入整体失败。 */
    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)

    /** 导出文件格式版本；将来字段不兼容时据此拒绝或迁移。 */
    private const val BUNDLE_VERSION = 1
}
