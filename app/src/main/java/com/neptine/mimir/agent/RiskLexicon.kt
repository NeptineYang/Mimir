package com.neptine.mimir.agent

/**
 * 风险词表与纯匹配规则（设计文档 §4.2）。
 *
 * 从 Guard 抽出来只为一件事：词表是安全边界，必须在 JVM 单测里逐条锁定，
 * 而不是靠“看一遍代码觉得没问题”。本对象不依赖 Android。
 *
 * 匹配口径：
 * - 中文按子串匹配（中文没有词边界）；
 * - 英文按 ASCII 单词边界、忽略大小写匹配，避免 `post` 命中 `postal`、
 *   `pay` 命中 `payment` 这类误伤；
 * - 确定/导航这类短按钮按“整段文本”匹配，不做子串。
 */
internal object RiskLexicon {

    /** 仅本人可做的资金与身份步骤（L2B）：命中即硬阻断，不可被授权覆盖。 */
    val HUMAN_ONLY_KEYWORDS = listOf(
        // 「支付」「付款」单独见下：只能整段匹配，避免命中商品销量文案与商品标题
        "立即支付", "确认支付", "去支付", "收银台",
        "支付密码", "密码", "验证码", "指纹", "面容", "人脸", "免密",
        // 风控人机验证：美团/滴滴这类 App 会弹滑块或点选验证，本项目的模型做不了，
        // 也不该由 agent 去尝试——直接判 L2B 交人，避免它反复点导致风控升级。
        "人机验证", "安全验证", "滑块", "拖动滑块", "滑动验证", "请完成验证", "身份验证",
        "银行卡", "身份证", "转账",
        // English：与上面逐项对齐；transfer 只认资金语义（bank/wire/transfer money），
        // 避免把文件传输类任务误判成 L2B。
        "pay now", "confirm payment", "cashier", "payment password", "password",
        "verification code", "fingerprint", "face id", "face unlock", "face recognition",
        "biometric", "one-click pay", "passwordless payment",
        "human verification", "security verification", "slider", "slide to verify",
        "drag the slider", "complete verification", "verify you are human", "identity verification",
        "bank card", "id card", "bank transfer", "wire transfer", "transfer money",
    )

    /** 可授权的外部副作用（L2A）。 */
    val AUTHORIZABLE_KEYWORDS = listOf(
        "提交订单", "确认下单", "立即购买", "立即抢购", "确认支付订单",
        "发送", "发布", "提交", "删除", "移除", "清空", "注销", "解绑", "订阅", "开通会员",
        // English：checkout 按“提交订单”级别处理（L2A），真正的 Pay now 在 L2B 表里。
        "submit order", "place order", "confirm order", "buy now", "purchase", "checkout",
        "send", "publish", "post", "submit", "delete", "remove", "clear",
        "unsubscribe", "unbind", "subscribe", "join membership",
    )

    /** 页面级验证关键词：命中即整页交人，不依赖模型选中具体元素。 */
    val VERIFICATION_PAGE_KEYWORDS = listOf(
        "人机验证", "安全验证", "拖动滑块", "拖动下方滑块", "滑动验证", "请完成验证", "滑块", "拼图",
        "请输入图片中的内容", "请输入验证码", "验证码",
        // 美团风控会换题型：滑块 → 图片文字 → 连点成线，文案里不一定带"滑块"。
        "用最短线连接", "最短线", "身份核实", "请完成下方验证",
        // English
        "captcha", "human verification", "security verification", "slide to verify",
        "drag the slider", "complete verification", "verification code",
        "two-factor", "2fa", "security check", "verify you are human", "identity verification",
    )

    /** 消息发送按钮的文案；命中这些且任务明确要求发消息时才跳过二次授权。 */
    val SEND_MESSAGE_TARGETS = listOf("发送", "回复", "send", "reply")

    /** 只剩付款动作的按钮文案；点餐/下单类任务走到这里就交接给本人，不再代点。 */
    val PAYMENT_ONLY_KEYWORDS = listOf(
        "立即支付", "确认支付", "去支付", "收银台", "支付密码", "免密支付", "指纹支付", "面容支付",
        "pay now", "confirm payment", "cashier", "payment password", "one-click pay",
        "fingerprint payment", "face payment",
    )

    /** 用户原话里“让我发消息”的意图词（英文按单词边界匹配）。 */
    val SEND_INTENT_KEYWORDS = listOf(
        "发送", "发消息", "发给", "发一条", "发个消息", "回消息", "回复", "告诉",
        // 提问类任务（DeepSeek/其它问答 App）点「发送」也是在执行用户明确要求
        "问", "提问", "询问", "咨询", "ask", "question", "prompt", "query",
        "send", "message", "reply", "tell",
    )

    /** 出现这些词的任务属于资金场景，「发送」不能跳过授权。 */
    val PAYMENT_INTENT_KEYWORDS = listOf(
        "转账", "付款", "支付", "红包", "金额", "收款",
        "pay", "payment", "transfer", "red packet", "amount", "bank transfer", "wire transfer",
    )

    /** 通用确认文案；必须结合页面语义才能定级。 */
    val GENERIC_CONFIRM_TEXTS = setOf(
        "确定", "确认", "继续", "好的", "同意",
        "ok", "okay", "confirm", "continue", "agree", "yes",
    )

    /** 明确可逆的导航文案。 */
    val NAVIGATION_TEXTS = setOf(
        "返回", "取消", "关闭", "跳过", "以后再说", "搜索", "更多", "菜单", "首页", "上一页", "下一页",
        "back", "cancel", "close", "skip", "search", "more", "menu", "home", "previous", "next",
    )

    /** 支付动作标签的最大长度：超过这个长度的文本是描述而不是按钮。 */
    const val PAYMENT_LABEL_MAX_LENGTH = 16

    /**
     * 中文支付动作标签：可选动词前缀 + 支付/付款 + 可选金额。
     * 用于替代「支付」「付款」的子串匹配，避免商品标题与销量文案误判。
     */
    val PAYMENT_ACTION_PATTERN = Regex(
        """(请|去|立即|马上|确认|现在)?(支付|付款)(订单)?([¥￥]?\d+(\.\d+)?(元)?)?""",
    )

    /**
     * 英文支付动作标签：只认整段文本（可带 now 与金额），checkout 不算
     * “只剩付款”（它对应提交订单，仍在 L2A 表里）。
     */
    val PAYMENT_ACTION_PATTERN_EN = Regex(
        "(?i)^(pay|confirm payment|cashier)( now)?(\\s*[¥\\$￥]?\\d+(\\.\\d+)?)?$",
    )

    /**
     * 功能：找目标文本命中的仅本人步骤关键词
     * 参数：text 目标文本
     * 返回：命中的关键词；未命中为 null
     */
    fun humanOnly(text: String): String? = matchLevel(text, HUMAN_ONLY_KEYWORDS)

    /**
     * 功能：找目标文本命中的可授权副作用关键词
     * 参数：text 目标文本
     * 返回：命中的关键词；未命中为 null
     */
    fun authorizable(text: String): String? = matchLevel(text, AUTHORIZABLE_KEYWORDS)

    /**
     * 功能：识别“必须由本人完成”的验证页面
     * 参数：pageText 整页文本
     * 返回：命中的关键词；不是验证页为 null
     */
    fun verificationPage(pageText: String): String? = matchLevel(pageText, VERIFICATION_PAGE_KEYWORDS)

    /**
     * 功能：判断目标是否为“只剩付款”的按钮
     * 参数：target 目标文本
     * 返回：命中的关键词；不是付款按钮时为 null
     *
     * 与 L2B 判定共用一套词，但这里只取资金类：验证码、密码、滑块这些仍然走原来的硬阻断，
     * 不能被“待支付页交接”分支当成任务正常收尾。
     */
    fun paymentOnly(target: String): String? {
        matchLevel(target, PAYMENT_ONLY_KEYWORDS)?.let { return it }
        return if (isPaymentActionLabel(target)) "支付" else null
    }

    /**
     * 功能：判断一段文本是否整体就是“支付/付款”动作标签
     * 参数：text 目标文本
     * 返回：是支付动作标签为 true
     *
     * 中文与英文都只认整段匹配（可带金额），不做子串匹配：
     * 商品标题「支付宝到账语音播报器」、销量「1000+人付款」、英文「Payment history」
     * 都不该判成支付步骤。
     */
    fun isPaymentActionLabel(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val compact = trimmed.filterNot { it.isWhitespace() }
        if (compact.length > PAYMENT_LABEL_MAX_LENGTH) return false
        return PAYMENT_ACTION_PATTERN.matches(compact) || PAYMENT_ACTION_PATTERN_EN.matches(trimmed)
    }

    /**
     * 功能：判断目标是否是消息发送按钮
     * 参数：target 目标文本
     * 返回：命中发送/回复文案为 true
     */
    fun isSendTarget(target: String): Boolean = matchLevel(target, SEND_MESSAGE_TARGETS) != null

    /**
     * 功能：判断目标是否是通用确认按钮
     * 参数：target 目标文本
     * 返回：是确定/确认/继续等按钮为 true
     */
    fun isGenericConfirm(target: String): Boolean = normalize(target) in GENERIC_CONFIRM_TEXTS

    /**
     * 功能：判断目标是否是明确可逆的导航按钮
     * 参数：target 目标文本
     * 返回：是返回/取消/关闭等按钮为 true
     */
    fun isNavigation(target: String): Boolean = normalize(target) in NAVIGATION_TEXTS

    /**
     * 功能：任务是否明确要求“发送消息/回复”
     * 参数：inputText 用户原话；taskType 任务类型
     * 返回：明确要求为 true
     *
     * 先排除转账/红包/金额类任务：那些任务里的「发送」是资金动作，仍走授权/人工。
     * taskType 命中 SEND_MESSAGE/CHAT/REPLY，或用户原话里出现发送/回复意图，都算明确要求。
     */
    fun isExplicitMessageSend(inputText: String, taskType: String): Boolean {
        if (PAYMENT_INTENT_KEYWORDS.any { containsKeyword(inputText, it) }) return false
        val type = taskType.uppercase()
        val typeHit = listOf("SEND_MESSAGE", "CHAT", "REPLY", "ASK", "QUESTION").any { type.contains(it) }
        return typeHit || SEND_INTENT_KEYWORDS.any { containsKeyword(inputText, it) }
    }

    /**
     * 功能：在文本里找第一个命中的关键词
     * 参数：text 待检查文本；keywords 关键词表
     * 返回：命中的关键词；未命中为 null
     */
    fun matchLevel(text: String, keywords: List<String>): String? =
        keywords.firstOrNull { containsKeyword(text, it) }

    /**
     * 功能：按关键词是否 ASCII 选择匹配方式
     * 参数：text 待检查文本；keyword 关键词
     * 返回：命中为 true
     */
    private fun containsKeyword(text: String, keyword: String): Boolean =
        if (keyword.isAscii()) containsAsciiWord(text, keyword) else text.contains(keyword)

    /**
     * 功能：判断字符串是否全部为 ASCII
     * 参数：无（接收者即字符串）
     * 返回：全部 ASCII 为 true
     */
    private fun String.isAscii(): Boolean = all { it.code < 128 }

    /**
     * 功能：英文按单词边界匹配（忽略大小写）
     * 参数：text 待检查文本；keyword 英文关键词
     * 返回：命中为 true
     *
     * 用 ASCII 字母数字做边界，而不是 `Char.isLetterOrDigit()`：
     * 关键词两侧是中文时也应视为边界（例如「删除Delete」）。
     */
    private fun containsAsciiWord(text: String, keyword: String): Boolean {
        var index = text.indexOf(keyword, ignoreCase = true)
        while (index >= 0) {
            val before = text.getOrNull(index - 1)
            val after = text.getOrNull(index + keyword.length)
            if (!before.isAsciiWordChar() && !after.isAsciiWordChar()) return true
            index = text.indexOf(keyword, index + 1, ignoreCase = true)
        }
        return false
    }

    /**
     * 功能：判断字符是否属于 ASCII 单词字符
     * 参数：无（接收者即可空字符）
     * 返回：ASCII 字母或数字为 true
     */
    private fun Char?.isAsciiWordChar(): Boolean =
        this != null && (this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9')

    /**
     * 功能：归一化短按钮文本（去首尾空白、转小写）
     * 参数：text 原始文本
     * 返回：用于整段匹配的文本
     */
    private fun normalize(text: String): String = text.trim().lowercase()
}
