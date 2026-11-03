package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.AnchorLevel
import com.neptine.mimir.core.contract.Bounds
import com.neptine.mimir.core.contract.NormalizedBox
import com.neptine.mimir.core.contract.TargetFingerprint
import com.neptine.mimir.core.contract.TargetMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 目标指纹与审计列之间的编解码。
 *
 * 审计的 `target_fingerprint` 列原先存 `data class` 的 `toString()`：人能读，但机器解析要正则，
 * 而"从轨迹重建配方"必须可靠地取回 resourceId / text / className。这里改成 JSON，
 * 老行（toString 格式）解析失败按"没有指纹"处理——只影响历史回放，不影响新写入。
 */
object TargetFingerprintCodec {

    /**
     * 功能：把指纹编成紧凑 JSON
     * 参数：fingerprint 目标指纹
     * 返回：JSON 文本
     */
    fun encode(fingerprint: TargetFingerprint): String = buildJsonObject {
        put("mode", fingerprint.mode.name)
        put("anchorLevel", fingerprint.anchorLevel.name)
        put("resourceId", fingerprint.resourceId)
        put("text", fingerprint.text)
        put("contentDescription", fingerprint.contentDescription)
        put("className", fingerprint.className)
        put("ancestorPath", fingerprint.ancestorPath)
        fingerprint.bounds?.let { box ->
            putJsonObject("bounds") {
                put("left", box.left)
                put("top", box.top)
                put("right", box.right)
                put("bottom", box.bottom)
            }
        }
        put("ocrText", fingerprint.ocrText)
        fingerprint.normalizedBox?.let { box ->
            putJsonObject("normalizedBox") {
                put("left", box.left)
                put("top", box.top)
                put("right", box.right)
                put("bottom", box.bottom)
            }
        }
        put("localImageHash", fingerprint.localImageHash)
    }.toString()

    /**
     * 功能：从审计列还原指纹
     * 参数：text 列内容
     * 返回：指纹；空值、旧格式或损坏时为 null
     */
    fun decode(text: String?): TargetFingerprint? {
        if (text.isNullOrEmpty()) return null
        val node = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject }
            .getOrNull() ?: return null
        val mode = runCatching { TargetMode.valueOf(node.string("mode")) }.getOrNull() ?: return null
        val level = runCatching { AnchorLevel.valueOf(node.string("anchorLevel")) }.getOrNull()
            ?: AnchorLevel.A3
        return TargetFingerprint(
            mode = mode,
            anchorLevel = level,
            resourceId = node.stringOrNull("resourceId"),
            text = node.stringOrNull("text"),
            contentDescription = node.stringOrNull("contentDescription"),
            className = node.stringOrNull("className"),
            ancestorPath = node.stringOrNull("ancestorPath"),
            bounds = node.box("bounds")?.let { Bounds(it[0], it[1], it[2], it[3]) },
            ocrText = node.stringOrNull("ocrText"),
            normalizedBox = node.box("normalizedBox")?.let { NormalizedBox(it[0], it[1], it[2], it[3]) },
            localImageHash = node.stringOrNull("localImageHash"),
        )
    }

    /** 取字符串字段；缺失或为 null 时返回空串。 */
    private fun JsonObject.string(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull.orEmpty() }.getOrDefault("")

    /** 取可空字符串字段。 */
    private fun JsonObject.stringOrNull(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    /** 取矩形字段；缺失时返回 null。 */
    private fun JsonObject.box(key: String): IntArray? {
        val node = runCatching { this[key]?.jsonObject }.getOrNull() ?: return null
        fun value(name: String): Int? = node[name]?.jsonPrimitive?.intOrNull
        return intArrayOf(
            value("left") ?: return null,
            value("top") ?: return null,
            value("right") ?: return null,
            value("bottom") ?: return null,
        )
    }
}
