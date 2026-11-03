package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.CredentialPurpose
import com.neptine.mimir.core.contract.CredentialRecord
import com.neptine.mimir.core.contract.ObservationMode
import com.neptine.mimir.core.contract.ObservationSnapshot

/**
 * 凭据使用规则（设计文档 §2.5.8）。
 *
 * 纯本地判定，不调模型：凭据是否存在、目标包名是否匹配、目标是否真的是密码框、
 * 页面是否属于支付/身份验证场景。任何一条不满足都拒绝使用；`humanOnly` 表示命中
 * 支付、验证码、身份验证等 L2B 场景，连"当次确认"也不给。
 *
 * @property record 命中的凭据元数据；不存在为 null
 * @property problem 拒绝原因；可以使用时为 null
 * @property humanOnly 是否属于只能本人操作的场景
 */
data class CredentialCheck(
    val record: CredentialRecord?,
    val problem: String?,
    val humanOnly: Boolean,
) {
    /** 是否可以使用该凭据。 */
    val usable: Boolean get() = problem == null
}

/**
 * 凭据使用规则的唯一判定点。
 *
 * Broker 在填充前、Guard 在授权前都调用这里；两处共用同一份规则，
 * 避免出现"Guard 允许、Broker 拒绝"或反过来的死角。
 */
object CredentialPolicy {

    /** 页面级 L2B 线索：命中即不允许凭据自动填充，只能由本人操作。 */
    private val HUMAN_ONLY_PAGE_HINTS = listOf(
        "支付密码", "收银台", "转账", "银行卡", "身份证", "人脸", "面容", "指纹",
        "修改密码", "重置密码", "新密码",
    )

    /** 目标元素级 L2B 线索：字段本身就在做身份验证。 */
    private val HUMAN_ONLY_FIELD_HINTS = listOf(
        "支付密码", "验证码", "动态码", "身份证", "银行卡",
    )

    /**
     * 功能：判断一条凭据能否用于当前观察
     * 参数：record 凭据元数据；snapshot 当前观察；elementId 目标输入框编号
     * 返回：判定结果
     */
    fun check(
        record: CredentialRecord?,
        snapshot: ObservationSnapshot,
        elementId: Int?,
    ): CredentialCheck {
        if (record == null) {
            return CredentialCheck(null, "凭据不存在或已删除", humanOnly = false)
        }
        if (record.purpose != CredentialPurpose.LOGIN_PASSWORD) {
            return CredentialCheck(record, "该凭据的用途不是普通登录密码", humanOnly = false)
        }
        if (record.packageName != snapshot.packageName) {
            return CredentialCheck(
                record,
                "凭据绑定 ${record.packageName}，当前页面是 ${snapshot.packageName}",
                humanOnly = false,
            )
        }
        if (snapshot.overlay != null) {
            return CredentialCheck(record, "界面被一次性遮挡覆盖，填充前必须先处理遮挡", humanOnly = false)
        }
        if (snapshot.mode == ObservationMode.VISION_ONLY) {
            return CredentialCheck(record, "控件树不可读，无法确认目标是否为密码框", humanOnly = false)
        }
        val element = snapshot.elements.firstOrNull { it.index == elementId }
            ?: return CredentialCheck(record, "目标元素不在当前观察里", humanOnly = false)
        val label = element.text.ifEmpty { element.contentDescription }
        if (HUMAN_ONLY_FIELD_HINTS.any { label.contains(it) }) {
            return CredentialCheck(record, "目标「$label」属于身份验证字段，只能本人操作", humanOnly = true)
        }
        if (!element.editable) {
            return CredentialCheck(record, "目标「$label」不是输入框", humanOnly = false)
        }
        if (!element.password && !label.contains("密码")) {
            return CredentialCheck(record, "无法确认目标「$label」是登录密码框", humanOnly = false)
        }
        val pageText = snapshot.elements.joinToString(" ") { it.text.ifEmpty { it.contentDescription } }
        if (HUMAN_ONLY_PAGE_HINTS.any { pageText.contains(it) }) {
            return CredentialCheck(record, "页面包含支付/身份验证线索，只能本人操作", humanOnly = true)
        }
        return CredentialCheck(record, null, humanOnly = false)
    }
}
