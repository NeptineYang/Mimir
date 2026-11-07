package com.neptine.mimir.data.memory

import com.neptine.mimir.core.contract.ActionKind
import com.neptine.mimir.core.contract.Anchor
import com.neptine.mimir.core.contract.AnchorLevel
import com.neptine.mimir.core.contract.MemorySource
import com.neptine.mimir.core.contract.MemoryStatus
import com.neptine.mimir.core.contract.Preference
import com.neptine.mimir.core.contract.PreferenceCategory
import com.neptine.mimir.core.contract.Procedure
import com.neptine.mimir.core.contract.ProcedureAction
import com.neptine.mimir.core.contract.ProcedurePage
import com.neptine.mimir.core.contract.ProcedureStep
import com.neptine.mimir.data.db.entity.PreferenceEntity
import com.neptine.mimir.data.db.entity.ProcedureEntity
import kotlinx.serialization.json.Json
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
     * 功能：把配方表行与树节点还原成契约对象
     * 参数：entity 数据库行；pages 已组装好的配方树
     * 返回：配方；JSON 列损坏时按空列表处理，不抛异常
     *
     * 树不在这里查库：该方法保持纯函数，调用方（检索器 / 编辑页）负责一次性取好节点，
     * 避免 Codec 依赖 DAO 之后在迁移与导出里也被迫带上数据库。
     */
    fun fromEntity(entity: ProcedureEntity, pages: List<ProcedurePage>): Procedure = Procedure(
        id = entity.id,
        packageName = entity.packageName,
        taskType = entity.taskType,
        stableKey = entity.stableKey,
        category = entity.category,
        title = entity.title,
        precondition = decodeAnchors(entity.preconditionJson),
        pages = pages,
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
     * 功能：把偏好表行还原成契约对象
     * 参数：entity 数据库行
     * 返回：偏好
     */
    fun fromEntity(entity: PreferenceEntity): Preference = Preference(
        id = entity.id,
        scope = entity.scope,
        category = enumOrDefault(entity.category, PreferenceCategory.GENERAL),
        subject = entity.subject,
        title = entity.title,
        content = entity.content,
        value = entity.value,
        confidence = entity.confidence,
        source = enumOrDefault(entity.source, MemorySource.USER_WRITTEN),
        status = enumOrDefault(entity.status, MemoryStatus.ACTIVE),
        hitCount = entity.hitCount,
        lastUsedAt = entity.lastUsedAt,
        createdAt = entity.createdAt,
    )

    /**
     * 功能：把偏好写成表行
     * 参数：preference 偏好
     * 返回：数据库行
     */
    fun toEntity(preference: Preference): PreferenceEntity = PreferenceEntity(
        id = preference.id,
        scope = preference.scope,
        category = preference.category.name,
        subject = preference.subject,
        title = preference.title,
        content = preference.content,
        value = preference.value,
        confidence = preference.confidence,
        source = preference.source.name,
        status = preference.status.name,
        hitCount = preference.hitCount,
        lastUsedAt = preference.lastUsedAt,
        createdAt = preference.createdAt,
    )

    /**
     * 功能：把配方与偏好编码成可导出的 JSON 文本
     * 参数：procedures 配方列表；preferences 偏好列表
     * 返回：形如 `{"version":2,"procedures":[...],"preferences":[...]}` 的文本
     *
     * 偏好必须一起导出：它是用户手写的东西，丢了无法从审计重建。
     * 旧版本导出文件没有 preferences 字段，导入时按空列表处理。
     */
    fun encodeBundle(procedures: List<Procedure>, preferences: List<Preference>): String = buildJsonObject {
        put("version", BUNDLE_VERSION)
        putJsonArray("procedures") {
            procedures.forEach { add(procedureJson(it)) }
        }
        putJsonArray("preferences") {
            preferences.forEach { add(preferenceJson(it)) }
        }
    }.toString()

    /**
     * 功能：解析导入文件
     * 参数：text 文件文本
     * 返回：配方与偏好；格式不符时返回 null（调用方据此提示"不是本应用导出的文件"）
     */
    fun decodeBundle(text: String): MemoryBundle? {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrNull() ?: return null
        val procedureNodes = runCatching { root["procedures"]?.jsonArray }.getOrNull() ?: return null
        val preferenceNodes = runCatching { root["preferences"]?.jsonArray }.getOrNull().orEmpty()
        return MemoryBundle(
            procedures = procedureNodes.mapNotNull { node ->
                runCatching { procedureOf(node.jsonObject) }.getOrNull()
            },
            preferences = preferenceNodes.mapNotNull { node ->
                runCatching { preferenceOf(node.jsonObject) }.getOrNull()
            },
        )
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
        // steps 是树派生出来的兼容视图：旧版本导入只认 steps，新版本只认 pages。
        // 两个都写，导出文件才能在两个版本之间来回搬。
        putJsonArray("steps") {
            procedure.steps.forEach { add(stepJson(it)) }
        }
        putJsonArray("pages") {
            procedure.pages.forEach { add(pageJson(it)) }
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
    private fun procedureOf(node: JsonObject): Procedure {
        val steps = node["steps"]?.jsonArray.orEmpty()
            .mapNotNull { runCatching { stepOf(it.jsonObject) }.getOrNull() }
        // 旧导出文件与旧表行只有 steps：按 pagePath 连续分组补出树，不让导入直接失败。
        val pages = node["pages"]?.jsonArray.orEmpty()
            .mapNotNull { runCatching { pageOf(it.jsonObject) }.getOrNull() }
            .ifEmpty { ProcedureTree.groupSteps(steps) }
        return Procedure(
            id = node.string("id"),
            packageName = node.string("packageName"),
            taskType = node.string("taskType"),
            stableKey = node.string("stableKey"),
            category = node.string("category"),
            title = node.string("title"),
            precondition = node["precondition"]?.jsonArray.orEmpty()
                .mapNotNull { runCatching { anchorOf(it.jsonObject) }.getOrNull() },
            pages = pages,
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
    }

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
     * 功能：编码配方树的一个页面节点
     * 参数：page 页面
     * 返回：JSON 对象
     */
    private fun pageJson(page: ProcedurePage): JsonObject = buildJsonObject {
        put("id", page.id)
        put("title", page.title)
        put("pagePath", page.pagePath)
        put("signature", stringsJson(page.signature))
        putJsonArray("actions") {
            page.actions.forEach { add(actionJson(it)) }
        }
    }

    /**
     * 功能：解析配方树的一个页面节点
     * 参数：node JSON 对象
     * 返回：页面
     */
    private fun pageOf(node: JsonObject): ProcedurePage = ProcedurePage(
        id = node.string("id"),
        title = node.string("title"),
        pagePath = node.stringOrNull("pagePath"),
        signature = node["signature"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull },
        actions = node["actions"]?.jsonArray.orEmpty()
            .mapNotNull { runCatching { actionOf(it.jsonObject) }.getOrNull() },
    )

    /**
     * 功能：编码配方树的一个动作节点
     * 参数：action 动作
     * 返回：JSON 对象
     */
    private fun actionJson(action: ProcedureAction): JsonObject = buildJsonObject {
        put("id", action.id)
        put("actionKind", action.actionKind.name)
        put("title", action.title)
        put("anchor", action.anchor?.let { anchorJson(it) } ?: JsonNull)
        putJsonObject("parameterSlots") {
            action.parameterSlots.forEach { (key, value) -> put(key, value) }
        }
    }

    /**
     * 功能：解析配方树的一个动作节点
     * 参数：node JSON 对象
     * 返回：动作
     */
    private fun actionOf(node: JsonObject): ProcedureAction = ProcedureAction(
        id = node.string("id"),
        actionKind = enumOrDefault(node.string("actionKind"), ActionKind.TAP),
        title = node.string("title"),
        anchor = runCatching { node["anchor"]?.jsonObject }.getOrNull()?.let { anchorOf(it) },
        parameterSlots = runCatching {
            node["parameterSlots"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        }.getOrDefault(emptyMap()),
    )

    /**
     * 功能：编码一条偏好
     * 参数：preference 偏好
     * 返回：JSON 对象
     */
    private fun preferenceJson(preference: Preference): JsonObject = buildJsonObject {
        put("id", preference.id)
        put("scope", preference.scope)
        put("category", preference.category.name)
        put("subject", preference.subject)
        put("title", preference.title)
        put("content", preference.content)
        put("value", preference.value)
        put("confidence", preference.confidence)
        put("source", preference.source.name)
        put("status", preference.status.name)
        put("hitCount", preference.hitCount)
        put("lastUsedAt", preference.lastUsedAt)
        put("createdAt", preference.createdAt)
    }

    /**
     * 功能：解析一条偏好
     * 参数：node JSON 对象
     * 返回：偏好
     */
    private fun preferenceOf(node: JsonObject): Preference = Preference(
        id = node.string("id"),
        scope = node.stringOrNull("scope"),
        category = enumOrDefault(node.string("category"), PreferenceCategory.GENERAL),
        subject = node.stringOrNull("subject"),
        title = node.string("title"),
        content = node.string("content"),
        value = node.string("value"),
        confidence = node["confidence"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
        source = enumOrDefault(node.string("source"), MemorySource.USER_WRITTEN),
        status = enumOrDefault(node.string("status"), MemoryStatus.ACTIVE),
        hitCount = node["hitCount"]?.jsonPrimitive?.intOrNull ?: 0,
        lastUsedAt = node["lastUsedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        createdAt = node["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
    )

    /**
     * 功能：解析表列里的字符串数组（供树节点组装复用）
     * 参数：text JSON 文本
     * 返回：字符串列表；损坏或为空时返回空列表
     */
    fun decodeStringsForTree(text: String): List<String> = decodeStrings(text)

    /**
     * 功能：编码字符串数组为表列文本（供树节点写入复用）
     * 参数：values 字符串列表
     * 返回：JSON 数组文本
     */
    fun encodeStringsForTree(values: List<String>): String = stringsJson(values)

    /**
     * 功能：按名字解析动作类型
     * 参数：name 枚举名
     * 返回：动作类型；不认识时退回 TAP
     */
    fun actionKindOf(name: String): ActionKind = enumOrDefault(name, ActionKind.TAP)

    /**
     * 功能：解析表列里的锚点 JSON
     * 参数：text JSON 文本；null 表示没有锚点
     * 返回：锚点；损坏时为 null
     */
    fun decodeAnchorOrNull(text: String?): Anchor? =
        text?.let { runCatching { anchorOf(Json.parseToJsonElement(it).jsonObject) }.getOrNull() }

    /**
     * 功能：解析表列里的参数槽位 JSON
     * 参数：text JSON 文本
     * 返回：槽位映射；损坏或为空时返回空映射
     */
    fun decodeSlots(text: String): Map<String, String> =
        runCatching {
            Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())

    /**
     * 功能：编码锚点为表列文本
     * 参数：anchor 锚点；null 表示没有锚点
     * 返回：JSON 文本或 null
     */
    fun encodeAnchor(anchor: Anchor?): String? = anchor?.let { anchorJson(it).toString() }

    /**
     * 功能：编码参数槽位为表列文本
     * 参数：slots 槽位映射
     * 返回：JSON 对象文本
     */
    fun encodeSlots(slots: Map<String, String>): String = buildJsonObject {
        slots.forEach { (key, value) -> put(key, value) }
    }.toString()

    /**
     * 功能：解析旧配方行里的步骤数组（供数据库迁移使用）
     * 参数：text steps_json 列
     * 返回：步骤列表；损坏或为空时为空列表
     */
    fun decodeStepsForMigration(text: String): List<ProcedureStep> = decodeSteps(text)

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

    /** 导出文件格式版本；v2 起带 preferences，v1 文件仍可导入（偏好按空列表处理）。 */
    private const val BUNDLE_VERSION = 2
}

/**
 * 导入导出包。
 *
 * @property procedures 配方（含四层树）
 * @property preferences 偏好；旧文件没有这一段时为空
 */
data class MemoryBundle(
    val procedures: List<Procedure>,
    val preferences: List<Preference>,
)
