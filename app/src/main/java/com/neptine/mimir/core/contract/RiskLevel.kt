package com.neptine.mimir.core.contract

/**
 * 四级风险分级（设计文档 §4.2）。
 *
 * 判定不依赖模型自述：Guard 结合 TaskSpec、动作类型、页面语义与目标包名决定级别，
 * 无法确定后果时按更高一级处理。
 */
enum class RiskLevel {
    /** 只读或可逆导航：滑动、返回、Home、打开 App、点搜索框。 */
    L0,

    /** 普通写入：加购、选规格、填非敏感表单。 */
    L1,

    /** 可授权外部副作用：提交待支付订单、发消息、发布、删除。 */
    L2A,

    /** 仅本人资金与身份步骤：支付、密码、验证码、授权登录。 */
    L2B,
    ;

    /** 是否为写入类动作（L0 以外都算）。 */
    val isWrite: Boolean get() = this != L0

    /** 是否硬阻断且禁止 Agent 续作（只有 L2B）。 */
    val isHumanOnly: Boolean get() = this == L2B
}
