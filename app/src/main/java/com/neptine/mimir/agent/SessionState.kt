package com.neptine.mimir.agent

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

/**
 * 任务执行期会话计数（设计文档 §1.4 checkpoint）。
 *
 * 只装"不落库就无法续跑"的计数，不装观察与句柄：元素编号、displayId 一律不复用，
 * 恢复时重新观测（§4.3 第 3 条）。重规划次数与越界自愈次数是任务级余量，跨步骤保留；
 * 动作数、驳回次数与注入配方是步骤级计数，只在计划版本与游标都没变时沿用，
 * 否则续跑会拿下一步的计数器接着算上一步的账。
 *
 * @property actionsOnStep 当前步骤上已执行的动作数
 * @property replans 已用掉的受限重规划次数
 * @property stepRejections 当前步骤连续被驳回的完成声明次数
 * @property stepMemoryIds 当前步骤注入过的配方；被重规划取代时记它们一次失败
 * @property packageEscapes 已用掉的前台越界自愈次数
 * @property planVersion 计数所属的计划版本；用于判断步骤级计数是否仍然有效
 * @property subtaskIndex 计数所属的子任务下标
 * @property stepIndex 计数所属的步骤下标
 * @property overlaySeen 本轮轨迹是否出现过一次性遮挡；影响记忆写入闸门
 * @property overlayDismissals 已关闭过多少次遮挡；用于识别关闭循环
 */
data class SessionState(
    val actionsOnStep: Int = 0,
    val replans: Int = 0,
    val stepRejections: Int = 0,
    val stepMemoryIds: List<String> = emptyList(),
    val packageEscapes: Int = 0,
    val planVersion: Int = 0,
    val subtaskIndex: Int = 0,
    val stepIndex: Int = 0,
    val overlaySeen: Boolean = false,
    val overlayDismissals: Int = 0,
)

/**
 * SessionState 与 `tasks.session_json` 之间的编解码。
 *
 * 与 TaskPlanCodec 同样的理由：契约层不加 @Serializable，字段映射集中在一处。
 * 解码失败一律返回默认计数而不是抛异常——旧版本残留或半截 JSON 不应该让任务起不来。
 */
object SessionStateCodec {

    /**
     * 功能：把会话计数编码为紧凑 JSON
     * 参数：state 会话计数
     * 返回：JSON 文本
     */
    fun encode(state: SessionState): String = buildJsonObject {
        put("actionsOnStep", state.actionsOnStep)
        put("replans", state.replans)
        put("stepRejections", state.stepRejections)
        put("packageEscapes", state.packageEscapes)
        put("planVersion", state.planVersion)
        put("subtaskIndex", state.subtaskIndex)
        put("stepIndex", state.stepIndex)
        put("overlaySeen", state.overlaySeen)
        put("overlayDismissals", state.overlayDismissals)
        putJsonArray("stepMemoryIds") {
            state.stepMemoryIds.forEach { add(it) }
        }
    }.toString()

    /**
     * 功能：从 JSON 还原会话计数
     * 参数：text session_json 文本
     * 返回：会话计数；文本为空或不可解析时为默认计数
     */
    fun decode(text: String): SessionState {
        if (text.isEmpty()) return SessionState()
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject }
            .getOrNull() ?: return SessionState()
        return SessionState(
            actionsOnStep = root.int("actionsOnStep"),
            replans = root.int("replans"),
            stepRejections = root.int("stepRejections"),
            stepMemoryIds = root.stringList("stepMemoryIds"),
            packageEscapes = root.int("packageEscapes"),
            planVersion = root.int("planVersion"),
            subtaskIndex = root.int("subtaskIndex"),
            stepIndex = root.int("stepIndex"),
            overlaySeen = root.boolean("overlaySeen"),
            overlayDismissals = root.int("overlayDismissals"),
        )
    }

    /** 取整数字段；缺失、非数字或为负时按 0。 */
    private fun JsonObject.int(key: String): Int =
        runCatching { this[key]?.jsonPrimitive?.intOrNull ?: 0 }.getOrDefault(0).coerceAtLeast(0)

    /** 取字符串数组；缺失或类型不符时为空列表。 */
    private fun JsonObject.stringList(key: String): List<String> =
        runCatching { this[key]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull } }
            .getOrDefault(emptyList())

    /** 取布尔字段；缺失或类型不符时按 false。 */
    private fun JsonObject.boolean(key: String): Boolean =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false }
            .getOrDefault(false)
}
