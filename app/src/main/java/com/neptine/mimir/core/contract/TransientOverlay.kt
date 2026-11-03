package com.neptine.mimir.core.contract

/**
 * 一次性提示与广告分类（设计文档 §1.6.1）。
 *
 * 只描述"这一帧看起来是什么"，不决定"能不能点"：处置由 Guard 按分类与按钮语义裁决。
 * 分类结果只对当前 observation_id 有效，不跨帧复用，也**不得写入长期记忆**。
 *
 * @property kind 分类结果
 * @property reason 判定依据，写入 prompt 与审计
 * @property closeIndex 可安全关闭的元素编号；没有安全入口时为 null
 * @property closeLabel 安全关闭按钮的文案
 */
data class OverlayClassification(
    val kind: OverlayKind,
    val reason: String,
    val closeIndex: Int? = null,
    val closeLabel: String? = null,
) {
    /**
     * 功能：拼出一句可审计的说明
     * 参数：无
     * 返回：分类、依据与关闭入口
     */
    fun summary(): String = buildString {
        append(kind.name)
        append('：')
        append(reason)
        if (closeIndex != null) append("；安全关闭=#$closeIndex「${closeLabel.orEmpty()}」")
    }
}

/** 遮挡分类：可安全关闭的一次性遮挡，或必须由本人决定的遮挡。 */
enum class OverlayKind {
    /** 纯营销/活动/非强制性引导，且存在不产生副作用的关闭入口。 */
    TRANSIENT_OVERLAY,

    /** 协议、权限、账号、更新等需要本人决定的遮挡；不得自动接受或跳过。 */
    BLOCKING_OVERLAY,
}

/**
 * 遮挡文案词表（设计文档 §1.6.1）。
 *
 * 分类与处置共用同一份词表：分类器决定"是不是遮挡"，Guard 决定"这个按钮能不能点"，
 * 两处若各写一份，迟早会出现"分类器认为安全、Guard 认为高危"的死角。
 */
object OverlayPolicy {

    /** 强关闭文案：出现它基本就说明这是一个可跳过的一次性遮挡。 */
    val STRONG_DISMISS_TEXTS = listOf(
        "跳过", "以后再说", "稍后再说", "下次再说", "暂不", "暂不升级", "我知道了", "知道了",
    )

    /** 弱关闭文案：普通表单也常见，必须同时有营销线索与小页面结构才认定为遮挡。 */
    val WEAK_DISMISS_TEXTS = listOf("关闭", "取消")

    /** 营销/活动线索。 */
    val MARKETING_HINTS = listOf(
        "开屏广告", "广告", "活动", "限时", "福利", "红包", "优惠", "新用户",
        "首单", "抽奖", "邀请", "礼包", "领取",
    )

    /**
     * 需要本人决定的事项（§1.6.1 第 2 条）。
     *
     * 命中即判 BLOCKING：任何"同意、授权、开通、立即更新"都不能借道广告关闭逻辑放行。
     */
    val RESTRICTED_HINTS = listOf(
        "隐私政策", "用户协议", "权限", "授权", "登录", "注册", "新版本", "版本更新",
        "立即更新", "系统更新", "升级", "支付", "付款",
    )

    /** 高风险按钮文案：即使在可关闭的遮挡里也不得自动点击。 */
    val RISK_TEXTS = listOf(
        "同意", "允许", "授权", "开通", "订阅", "立即更新", "立即安装", "立即下载",
        "下载", "安装", "升级", "领取", "绑定", "登录", "注册", "支付", "付款", "验证码", "密码",
    )

    /**
     * 功能：判断标签是不是强关闭入口
     * 参数：label 元素文案
     * 返回：命中强关闭词表为 true
     */
    fun isStrongDismiss(label: String): Boolean = label in STRONG_DISMISS_TEXTS

    /**
     * 功能：判断标签是不是关闭入口（含弱关闭）
     * 参数：label 元素文案
     * 返回：强关闭、弱关闭或以「关闭」开头时为 true
     */
    fun isDismiss(label: String): Boolean =
        isStrongDismiss(label) || label in WEAK_DISMISS_TEXTS || label.startsWith("关闭")

    /**
     * 功能：判断按钮是否属于遮挡内的高风险语义
     * 参数：label 元素文案
     * 返回：命中风险词表为 true
     */
    fun isRisky(label: String): Boolean = RISK_TEXTS.any { label.contains(it) }
}
