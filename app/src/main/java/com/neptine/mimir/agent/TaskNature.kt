package com.neptine.mimir.agent

/**
 * 任务性质判定（稳定性交付门禁，v0.3.20 起）。
 *
 * 旧口径只有"查看关键词"一票判定，`MISMATCH` 时把大量操作任务也按页面交付，
 * 用户看到的是"没做完就退出"。这里改为两层：
 * - 查看类：明确的查看/打开/搜索/播放等页面意图；
 * - 只要出现发送、删除、支付、计算等写操作/数值信号，一律按操作类处理；
 * - 外卖/购物车/结算保留页面交付（用户自己付款）。
 * 操作类验证不通过必须转人工，不能静默交付。
 */
object TaskNature {

    /**
     * 功能：判断任务是否是查看类
     * 参数：inputText 用户原始输入
     * 返回：有页面意图且没有写操作/计算信号为 true
     *
     * 「打开」「找」「设置」这类词单独不构成操作：打开设置看页面、打开某 App 停留
     * 都属于页面交付。只有发送/删除/关闭/计算等明确动作才把任务升级为操作类。
     */
    fun isViewTask(inputText: String): Boolean {
        val text = inputText.lowercase()
        if (VIEW_HINTS.none { text.contains(it) }) return false
        val operationHit = OPERATION_HINTS.any { text.contains(it) } ||
            CALCULATION.containsMatchIn(text)
        return !operationHit
    }

    /**
     * 功能：判断任务的交付物是否是"某个页面"
     * 参数：inputText 用户原始输入
     * 返回：查看类或购物/结算类为 true
     *
     * 购物类保留旧口径：外卖、购物车、待支付页本身就是交付物，
     * 剩下付款必须由用户本人完成（L2B 硬断），不能因为字段文案对不上就喊人回来重跑。
     */
    fun isDeliveryPageTask(inputText: String): Boolean {
        if (isViewTask(inputText)) return true
        val text = inputText.lowercase()
        return SHOPPING_HINTS.any { text.contains(it) }
    }

    /** 查看意图关键词；只收明确的"看/搜/播/读/打开"。 */
    private val VIEW_HINTS = listOf(
        "打开", "查看", "看看", "浏览", "阅读", "搜索", "查找", "找", "查询", "播放", "观看",
        "试听", "试看", "问",
        "open", "view", "show", "browse", "read", "search", "find", "play", "watch", "ask",
    )

    /** 写操作/动作关键词；命中后即使同时出现查看词，也按操作类处理。 */
    private val OPERATION_HINTS = listOf(
        "发送", "回复", "删除", "修改", "编辑", "调整", "调高", "调低", "切换", "设为", "改成",
        "开启", "关闭", "提交", "支付", "付款", "购买", "下单", "点单", "预约", "添加", "关注",
        "点赞", "评论", "转发", "分享", "清空", "移除", "取消", "保存",
        "send", "reply", "delete", "edit", "change", "enable", "disable", "submit",
        "pay", "buy", "order", "book", "add", "follow", "like", "comment", "share", "remove",
        "clear", "cancel", "save",
    )

    /**
     * 计算动作：`计算` 后面不能紧跟 `器`。
     *
     * 「打开计算器」是打开 App（页面交付），「计算 12×34」才是需要核对数值的操作；
     * 直接用子串会把所有带「计算器」的任务误判成操作类。
     */
    private val CALCULATION = Regex("计算(?!器)")

    /** 购物/结算类关键词：页面就是交付物，允许停在待支付页。 */
    private val SHOPPING_HINTS = listOf(
        "外卖", "购物车", "加购", "下单", "点单", "点一", "买", "结算", "待支付", "去支付",
        "order", "cart", "checkout", "waimai", "takeout",
    )
}
