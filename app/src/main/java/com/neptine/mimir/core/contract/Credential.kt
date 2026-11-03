package com.neptine.mimir.core.contract

/**
 * 凭据元数据（设计文档 §2.5.8）。
 *
 * 这里只有"用哪条凭据"的定位信息，**不含密码本体**：密码密文只存在加密存储里，
 * 由 CredentialBroker 在用户当次授权后本地读取并填充。模型上下文、审计与配方只能看到
 * 本结构，不能看到明文或可复原的密文。
 *
 * @property id 凭据标识；模型只提交这个值
 * @property packageName 绑定的目标应用包名；包名不符拒绝解密
 * @property accountAlias 账号别名（如邮箱/手机号），用于用户识别，不参与填充
 * @property purpose 允许的用途；目前只允许普通登录密码
 * @property createdAt 创建时间（毫秒）
 */
data class CredentialRecord(
    val id: String,
    val packageName: String,
    val accountAlias: String,
    val purpose: CredentialPurpose,
    val createdAt: Long,
)

/** 凭据用途；支付密码、验证码、生物识别等身份步骤永远不在此列（§2.5.8 第 5 条）。 */
enum class CredentialPurpose {
    /** 普通 App 登录密码；仅限用户明确保存的账号密码，不含任何支付/身份验证环节。 */
    LOGIN_PASSWORD,
}
