package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.AgentAction
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.UiElement
import java.security.MessageDigest

/**
 * 动作身份（设计文档 §1.3.1 / §4.2）。
 *
 * 一次性授权绑定"动作 + 参数 + 目标包名"，审计也要能说明"这一步到底做了什么"，
 * 两处必须用同一份摘要。摘要刻意不含元素编号与坐标：编号只对当前帧有效，
 * 跨帧必然变化，把它算进去会让授权在恢复后永远对不上。
 */

/**
 * 功能：取动作目标的文本
 * 参数：action 动作；snapshot 决策依据的观察
 * 返回：元素文本，缺失时退到无障碍描述；找不到元素为空串
 */
/** 归一化坐标满量程：模型给的 x/y 是 0–1000。 */
internal const val NORM_SCALE = 1000

internal fun targetTextOf(action: AgentAction.Do, snapshot: ObservationSnapshot): String {
    val element = snapshot.elements.firstOrNull { it.index == action.elementId }
        ?: elementAtPoint(snapshot, action.normX, action.normY)
        ?: return ""
    // 密码框的文本一律不进审计、摘要或通知；部分 WebView 会把明文暴露给无障碍
    if (element.password) return "(密码框)"
    return element.text.ifEmpty { element.contentDescription }
}

/**
 * 功能：取归一化坐标落点上的元素
 * 参数：snapshot 观察；normX/normY 0–1000 的坐标
 * 返回：命中的元素；坐标为空或落点无元素时为 null
 */
internal fun elementAtPoint(snapshot: ObservationSnapshot, normX: Int?, normY: Int?): UiElement? {
    if (normX == null || normY == null) return null
    val px = normX * snapshot.nativeWidth / NORM_SCALE
    val py = normY * snapshot.nativeHeight / NORM_SCALE
    return snapshot.elements.firstOrNull {
        px in it.bounds.left..it.bounds.right && py in it.bounds.top..it.bounds.bottom
    }
}

/**
 * 功能：计算动作参数摘要
 * 参数：action 动作；snapshot 决策依据的观察
 * 返回：SHA-256 十六进制摘要
 *
 * 摘要覆盖动作类型、目标包名、目标文本与输入正文。页面变化导致目标文本变化时摘要随之变化，
 * 授权即自动失效。不用 hashCode：32 位的碰撞概率不足以承担授权绑定的责任。
 */
internal fun paramsDigestOf(action: AgentAction.Do, snapshot: ObservationSnapshot): String {
    val raw = listOf(
        action.kind.name,
        snapshot.packageName,
        targetTextOf(action, snapshot),
        action.text.orEmpty(),
        // 坐标点击必须把坐标算进摘要：同一个「空白处」不能因为页面重排就复用旧授权。
        action.normX?.toString().orEmpty(),
        action.normY?.toString().orEmpty(),
    ).joinToString("|")
    return MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
