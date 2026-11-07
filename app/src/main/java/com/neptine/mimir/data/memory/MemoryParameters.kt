package com.neptine.mimir.data.memory

import com.neptine.mimir.core.contract.Anchor
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.Procedure

/**
 * 参数槽位替换（设计文档 §2.5.2 / §2.5.5）。
 *
 * 写入配方时把动态参数值（收件人、商品名、消息正文）替换成 `{字段}` 占位符：
 * stable_key 不因参数不同而分裂，锚点也不会把某一次的具体取值教给下一次。
 * 检索与重验时再用当前任务的参数填回去；填不出来的占位符原样保留，
 * 让锚点断言自然失败，而不是拿上一次的收件人去点这一次的单。
 */
object MemoryParameters {

    /**
     * `{字段}` 占位符；字段名非空、不含花括号。
     *
     * 收尾的 `}` 必须转义：Android 的 ICU 正则把未转义的 `}` 当作语法错误，
     * 而桌面 JVM 是宽容的——只在真机上才会暴露成 ExceptionInInitializerError。
     */
    private val placeholder = Regex("""\{([^{}]+)\}""")

    /**
     * 掩码结果。
     *
     * @property text 替换后的文本
     * @property slots 本次实际用到的槽位（字段名 → 写入时的取值）
     */
    data class MaskedText(val text: String, val slots: Map<String, String>)

    /**
     * 功能：把文本里的动态参数值替换成占位符
     * 参数：text 原始文本；parameters 任务参数；minValueLength 最短参与替换的取值长度
     * 返回：替换后的文本与实际命中的槽位
     *
     * 按取值长度从长到短替换：同一段文本里既有"张三"又有"张三丰"时，先换长的，
     * 否则短值会先把长值切碎，占位符就恢复不出原意。
     */
    fun mask(text: String, parameters: Map<String, String>, minValueLength: Int = 1): MaskedText {
        if (text.isEmpty() || parameters.isEmpty()) return MaskedText(text, emptyMap())
        var result = text
        val slots = linkedMapOf<String, String>()
        parameters.entries
            .filter { it.key.isNotBlank() && it.value.length >= minValueLength }
            .sortedByDescending { it.value.length }
            .forEach { (key, value) ->
                if (result.contains(value)) {
                    result = result.replace(value, "{$key}")
                    slots[key] = value
                }
            }
        return MaskedText(result, slots)
    }

    /**
     * 功能：用当前任务参数填回占位符
     * 参数：text 含占位符的文本；parameters 当前任务参数；slots 写入时记录的槽位取值
     * 返回：填充后的文本；两处都取不到的占位符原样保留
     *
     * 优先级是"当前任务参数 > 写入时取值"：配方可以换个收件人复用，
     * 但当前任务没给这个字段时，退回写入时的取值仍比留一个 `{recipient}` 更可用。
     */
    fun fill(text: String, parameters: Map<String, String>, slots: Map<String, String> = emptyMap()): String {
        if (text.isEmpty()) return text
        return placeholder.replace(text) { match ->
            val key = match.groupValues[1]
            parameters[key] ?: slots[key] ?: match.value
        }
    }

    /**
     * 功能：填回锚点里的占位符
     * 参数：anchor 锚点；parameters 当前任务参数；slots 写入时槽位
     * 返回：填充后的锚点；原锚点为空时返回 null
     */
    fun fillAnchor(anchor: Anchor?, parameters: Map<String, String>, slots: Map<String, String>): Anchor? {
        if (anchor == null) return null
        return anchor.copy(
            text = anchor.text?.let { fill(it, parameters, slots) },
            contentDescription = anchor.contentDescription?.let { fill(it, parameters, slots) },
            resourceId = anchor.resourceId?.let { fill(it, parameters, slots) },
        )
    }

    /**
     * 功能：把配方里的锚点全部换成当前参数下的具体文本
     * 参数：procedure 配方；parameters 当前任务参数
     * 返回：填充后的配方副本（槽位表保留原样，供下次还能退回写入时取值）
     *
     * 返回副本而不是改原对象：同一条配方在不同任务里以不同参数复用，
     * 填过的锚点只属于这一次检索。
     */
    fun resolved(procedure: Procedure, parameters: Map<String, String>): Procedure {
        if (parameters.isEmpty() && procedure.steps.all { it.parameterSlots.isEmpty() }) return procedure
        return procedure.copy(
            precondition = procedure.precondition.map { anchor ->
                fillAnchor(anchor, parameters, emptyMap()) ?: anchor
            },
            // 参数槽位挂在动作节点上；按页面重写整棵树，steps 作为派生视图会自动跟上。
            pages = procedure.pages.map { page ->
                page.copy(
                    actions = page.actions.map { action ->
                        action.copy(
                            anchor = fillAnchor(action.anchor, parameters, action.parameterSlots),
                        )
                    },
                )
            },
        )
    }

    /**
     * 功能：生成页面签名（规范化静态文本集合）
     * 参数：snapshot 入口页观察；parameters 写入时的任务参数
     * 返回：去掉动态参数值后的文本集合
     *
     * 签名要比对"页面结构有没有变"，所以动态值一律不留在集合里：
     * 纯参数值（整条文本就是一个收件人）直接剔除，嵌在文案里的值换成占位符。
     * 调用方对当前页面做同一套掩码，两侧才可比。
     */
    fun signature(snapshot: ObservationSnapshot, parameters: Map<String, String>): List<String> =
        snapshot.elements
            .mapNotNull { element -> element.text.ifEmpty { element.contentDescription }.takeIf { it.isNotEmpty() } }
            .map { mask(it, parameters, SIGNATURE_MIN_VALUE_LENGTH).text }
            .filter { it.isNotEmpty() && !placeholder.matches(it) }
            .distinct()
            .take(SIGNATURE_LIMIT)

    /** 页面签名里参与掩码的最短取值长度；单字值（数量、页码）掩掉会把静态文案也切碎。 */
    private const val SIGNATURE_MIN_VALUE_LENGTH = 2

    /** 页面签名最多保留多少条文本，与检索侧一致。 */
    private const val SIGNATURE_LIMIT = 30
}
