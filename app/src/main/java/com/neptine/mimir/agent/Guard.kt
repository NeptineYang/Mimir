package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.ActionKind
import com.neptine.mimir.core.contract.AgentAction
import com.neptine.mimir.core.contract.ObservationMode
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.OverlayKind
import com.neptine.mimir.core.contract.OverlayPolicy
import com.neptine.mimir.core.contract.RiskLevel
import com.neptine.mimir.core.contract.TaskSpec
import javax.inject.Inject

/**
 * 风险闸门（设计文档 §4.2）。
 *
 * 判定不依赖模型自述，也不只依赖控件树：结合 TaskSpec、动作类型、页面状态、目标包名/Activity、
 * 树文本与目标文本，任一高风险信号命中即升级。
 *
 * 界面内容一律视为不可信数据：App 文案、广告与 OCR 文本不得被当作系统指令。
 *
 * 一期只做"识别"，不做 Reviewer 复核（设计文档 §5.2 一期范围：单模型执行 + 基础 Guard，
 * 至少完整识别 L2A/L2B）。因此 L1 直接放行并在理由里写明"一期无 Reviewer"，
 * 而不是假装复核过。
 */
class Guard @Inject constructor(
    private val textInput: TextInput,
    private val credentials: CredentialBroker,
) {

    /**
     * 功能：判定一个待执行动作的风险等级与处置
     * 参数：spec 任务不可变约束；action 待执行动作；snapshot 当前观察
     * 返回：风险等级、基线是否放行、是否需要当次确认与理由
     *
     * L2A 的 allowed 恒为 false：它表示"尚无匹配授权时不能执行"，由上层查授权账本后决定；
     * requiresConfirmation 才是"可以给用户一次确认机会"。L2B 两者都为 false，不可被授权覆盖。
     */
    suspend fun assess(
        spec: TaskSpec,
        action: AgentAction,
        snapshot: ObservationSnapshot,
    ): GuardDecision = when (action) {
        is AgentAction.Finish -> GuardDecision(RiskLevel.L0, true, "声明完成，交由 Verifier 判定")
        is AgentAction.StepDone -> GuardDecision(RiskLevel.L0, true, "声明步骤完成，无外部副作用")
        is AgentAction.Replan -> GuardDecision(RiskLevel.L0, true, "请求重规划，无外部副作用")
        is AgentAction.Do -> assessDo(spec, action, snapshot)
    }

    /**
     * 功能：判定一个具体动作
     * 参数：spec 任务约束；action 动作；snapshot 当前观察
     * 返回：判定结果
     */
    private suspend fun assessDo(
        spec: TaskSpec,
        action: AgentAction.Do,
        snapshot: ObservationSnapshot,
    ): GuardDecision = when (action.kind) {
        ActionKind.SWIPE, ActionKind.BACK, ActionKind.HOME, ActionKind.WAIT ->
            GuardDecision(RiskLevel.L0, true, "可逆导航")

        ActionKind.LAUNCH -> assessLaunch(spec, action)
        ActionKind.TEXT -> assessTextInput(action, snapshot)
        ActionKind.TAP -> assessTap(action, snapshot)
        ActionKind.CREDENTIAL -> assessCredential(action, snapshot)
    }

    /**
     * 功能：判定凭据填充
     * 参数：action 动作；snapshot 当前观察
     * 返回：判定结果
     *
     * 凭据永远需要当次授权（L2A）；若目标属于支付/验证码/身份验证场景，直接 L2B，
     * 不给确认入口。凭据不存在、包名不符、目标不是密码框等一律拒绝并转人工。
     */
    private suspend fun assessCredential(
        action: AgentAction.Do,
        snapshot: ObservationSnapshot,
    ): GuardDecision {
        val id = action.text.orEmpty()
        val check = credentials.check(id, snapshot, action.elementId)
        check.problem?.let { problem ->
            return if (check.humanOnly) {
                GuardDecision(RiskLevel.L2B, false, problem)
            } else {
                GuardDecision(RiskLevel.L2A, false, problem)
            }
        }
        if (textInput.availability() == TextPath.NONE) {
            return GuardDecision(RiskLevel.L2A, false, "没有可用的文本输入路径，凭据无法填充")
        }
        val record = check.record
        return GuardDecision(
            RiskLevel.L2A,
            false,
            "使用凭据「${record?.accountAlias.orEmpty()}」填充登录密码，需要当次确认",
            requiresConfirmation = true,
        )
    }

    /**
     * 功能：判定拉起应用
     * 参数：spec 任务约束；action 动作
     * 返回：判定结果
     *
     * TaskSpec 目前只有单个 packageName（设计文档 §1.3.1 的 allowed_packages 白名单
     * 尚未落到契约里），因此这里退化为"不为空就必须一致"。
     */
    private fun assessLaunch(spec: TaskSpec, action: AgentAction.Do): GuardDecision {
        val target = action.text.orEmpty()
        if (target.isEmpty()) {
            return GuardDecision(RiskLevel.L1, false, "launch 动作缺包名")
        }
        if (spec.packageName.isNotEmpty() && target != spec.packageName) {
            return GuardDecision(RiskLevel.L1, false, "目标包名 $target 不在任务白名单 ${spec.packageName} 内")
        }
        return GuardDecision(RiskLevel.L0, true, "拉起目标应用 $target")
    }

    /**
     * 功能：判定文本输入
     * 参数：action 动作；snapshot 当前观察
     * 返回：判定结果
     *
     * 两条路径的门禁不同：
     * - 无障碍 ACTION_SET_TEXT 只改目标输入框，不切全局输入法、不抢主屏焦点，不需要过 P5；
     * - 自带 IME + commitText 会切换全局默认输入法，任务期间主屏失去键盘，必须先过 P5
     *   （§4.7 第 1 条一票否决）。
     * 两条都不可用时按写入动作拦成人工接管，而不是静默失败。
     */
    private suspend fun assessTextInput(
        action: AgentAction.Do,
        snapshot: ObservationSnapshot,
    ): GuardDecision {
        if (action.text.isNullOrEmpty()) {
            return GuardDecision(RiskLevel.L1, false, "input 动作缺正文")
        }
        if (snapshot.mode == ObservationMode.VISION_ONLY) {
            // 控件树被封锁：没有节点可写，只能逐字符注入按键。它不碰输入法，
            // 但只能表达 ASCII；含中文的正文会在执行层失败并转人工。
            return GuardDecision(
                RiskLevel.L1,
                true,
                "控件树被封锁，文本输入改走按键注入（仅 ASCII 可表达，不切输入法）",
            )
        }
        return when (textInput.availability()) {
            TextPath.ACCESSIBILITY -> GuardDecision(
                RiskLevel.L1,
                true,
                "文本输入走无障碍 ACTION_SET_TEXT：不切输入法、不影响主屏",
            )

            TextPath.IME -> GuardDecision(
                RiskLevel.L1,
                true,
                "文本输入走自带输入法（P5 已通过）；一期无 Reviewer 复核",
            )

            TextPath.NONE -> GuardDecision(
                RiskLevel.L1,
                false,
                "没有可用的文本输入路径（无障碍未连接、P5 未通过），按 §4.7 转人工。" +
                    "目标：${targetTextOf(action, snapshot)}",
            )
        }
    }

    /**
     * 功能：判定点击
     * 参数：action 动作；snapshot 当前观察
     * 返回：判定结果
     */
    private fun assessTap(action: AgentAction.Do, snapshot: ObservationSnapshot): GuardDecision {
        val target = targetTextOf(action, snapshot)
        // 一次性遮挡先按分类裁决：只有分类器给出的安全关闭入口可以直接点；
        // 高风险按钮（同意/授权/更新/下载…）即使在可关闭的广告里也不借道放行（§1.6.1）。
        snapshot.overlay?.let { overlay ->
            return when (overlay.kind) {
                OverlayKind.TRANSIENT_OVERLAY -> when {
                    overlay.closeIndex == action.elementId || OverlayPolicy.isDismiss(target) -> GuardDecision(
                        RiskLevel.L0,
                        true,
                        "瞬时遮挡的安全关闭「${overlay.closeLabel.orEmpty()}」：${overlay.reason}",
                    )

                    OverlayPolicy.isRisky(target) -> GuardDecision(
                        RiskLevel.L2A,
                        false,
                        "瞬时遮挡内的「$target」属于高风险按钮，不允许借道关闭逻辑放行，转人工",
                    )

                    else -> GuardDecision(
                        RiskLevel.L2A,
                        false,
                        "界面被瞬时遮挡（${overlay.reason}），先关闭遮挡再操作「$target」，转人工",
                    )
                }

                OverlayKind.BLOCKING_OVERLAY -> GuardDecision(
                    RiskLevel.L2A,
                    false,
                    "遮挡涉及需本人决定的事项（${overlay.reason}），不得自动处理，转人工",
                )
            }
        }

        if (target.isEmpty()) {
            // 纯视觉坐标点击：落点没有文字控件（空输入框、纯图标）。这类位置没有语义可判，
            // 但它是"输入框/图标"的常规操作，按 L1 放行并留审计；真正的写入动作
            // （发送、支付等有文字的按钮）落点会命中元素，仍走下面的关键词分级。
            if (action.normX != null && action.normY != null) {
                return GuardDecision(
                    RiskLevel.L1,
                    true,
                    "坐标点击：落点没有文字控件（空输入框/图标），按普通操作放行",
                )
            }
            // 元素级点击但读不到任何文本：外卖/购物车的底部导航、购物车图标、加号都属于这一类。
            // 它和坐标点击同源（都是"没有语义的图标"），不该判得比坐标点击更死；
            // 但页面语境还能提供一层保护：页面上出现支付级文案时，图标点击必须人工确认。
            val pageRisk = matchLevel(pageText(snapshot), HUMAN_ONLY_KEYWORDS)
            if (pageRisk != null) {
                return GuardDecision(
                    RiskLevel.L2A,
                    false,
                    "页面含「$pageRisk」，图标按钮（无文本）点击需要你确认一次",
                    requiresConfirmation = true,
                )
            }
            return GuardDecision(
                RiskLevel.L1,
                true,
                "图标按钮（无文本）：页面无支付级文案，按普通导航放行",
            )
        }

        matchLevel(target, HUMAN_ONLY_KEYWORDS)?.let { keyword ->
            return GuardDecision(RiskLevel.L2B, false, "目标「$target」命中仅本人步骤关键词「$keyword」，硬阻断")
        }
        // 「支付」「付款」不能按子串匹配：淘宝/拼多多的商品卡标题里常带销量文案
        // 「全网1000+人付款」，按子串判会把"点商品"直接判成支付步骤。
        // 只有整段文本本身就是一个支付动作标签（可带金额）时才按仅本人步骤处理。
        if (isPaymentActionLabel(target)) {
            return GuardDecision(RiskLevel.L2B, false, "目标「$target」是支付动作标签，硬阻断")
        }
        matchLevel(target, AUTHORIZABLE_KEYWORDS)?.let { keyword ->
            return GuardDecision(
                RiskLevel.L2A,
                false,
                "目标「$target」命中外部副作用关键词「$keyword」，需要一次性授权",
                requiresConfirmation = true,
            )
        }

        // 通用确认按钮：单独看不出后果，必须结合整页语义（§4.2 第 1 条）
        if (target in GENERIC_CONFIRM_TEXTS) {
            val pageText = pageText(snapshot)
            val pageHit = matchLevel(pageText, HUMAN_ONLY_KEYWORDS + AUTHORIZABLE_KEYWORDS)
            if (pageHit != null) {
                return GuardDecision(
                    RiskLevel.L2A,
                    false,
                    "通用确认按钮「$target」出现在含「$pageHit」的页面上，升级为需要一次性授权",
                    requiresConfirmation = true,
                )
            }
        }

        if (target in NAVIGATION_TEXTS) {
            return GuardDecision(RiskLevel.L0, true, "纯导航按钮「$target」")
        }
        return GuardDecision(RiskLevel.L1, true, "普通写入「$target」，一期无 Reviewer 复核")
    }

    /**
     * 功能：把整页元素文本拼成一段，用于判断"按钮+页面语义"的组合风险
     * 参数：snapshot 当前观察
     * 返回：页面文本
     */
    private fun pageText(snapshot: ObservationSnapshot): String =
        snapshot.elements.joinToString(" ") { it.text.ifEmpty { it.contentDescription } }

    /**
     * 功能：识别"必须由本人完成"的验证页面
     * 参数：snapshot 当前观察
     * 返回：命中的关键词；不是验证页为 null
     *
     * 只查页面文本、不看模型动作：美团这类 App 会在首页直接盖一层滑块拼图，
     * 模型看不到"该点哪里"时会反复滑动/点击，把风控越点越严。命中即整页交人，
     * 不依赖模型是否碰巧选中了验证相关文案。
     */
    fun pageVerification(snapshot: ObservationSnapshot): String? =
        matchLevel(pageText(snapshot), VERIFICATION_PAGE_KEYWORDS)

    /**
     * 功能：判断一段文本是否整体就是"支付/付款"动作标签
     * 参数：text 目标文本
     * 返回：是支付动作标签为 true
     *
     * 只认整段匹配（可带"去/立即/确认"前缀与金额后缀），不做子串匹配：
     * 商品标题「支付宝到账语音播报器」、销量「1000+人付款」都不该判成支付步骤。
     */
    private fun isPaymentActionLabel(text: String): Boolean {
        val compact = text.filterNot { it.isWhitespace() }
        return compact.isNotEmpty() && compact.length <= PAYMENT_LABEL_MAX_LENGTH &&
            PAYMENT_ACTION_PATTERN.matches(compact)
    }

    /**
     * 功能：在文本里找第一个命中的关键词
     * 参数：text 待检查文本；keywords 关键词表
     * 返回：命中的关键词；未命中为 null
     */
    private fun matchLevel(text: String, keywords: List<String>): String? =
        keywords.firstOrNull { text.contains(it) }

    private companion object {
        /** 仅本人可做的资金与身份步骤（§4.2 L2B）。命中即硬阻断，不可被授权覆盖。 */
        val HUMAN_ONLY_KEYWORDS = listOf(
            // 「支付」「付款」单独见下：只能整段匹配，避免命中商品销量文案与商品标题
            "立即支付", "确认支付", "去支付", "收银台",
            "支付密码", "密码", "验证码", "指纹", "面容", "人脸", "免密",
            // 风控人机验证：美团/滴滴这类 App 会弹滑块或点选验证，本项目的模型做不了，
            // 也不该由 agent 去尝试——直接判 L2B 交人，避免它反复点导致风控升级。
            "人机验证", "安全验证", "滑块", "拖动滑块", "滑动验证", "请完成验证", "身份验证",
            "银行卡", "身份证", "转账",
        )

        /**
         * 支付动作标签：可选动词前缀 + 支付/付款 + 可选金额。
         * 用于替代「支付」「付款」的子串匹配，避免商品标题与销量文案误判。
         */
        val PAYMENT_ACTION_PATTERN = Regex(
            """(请|去|立即|马上|确认|现在)?(支付|付款)(订单)?([¥￥]?\d+(\.\d+)?(元)?)?""",
        )

        /** 支付动作标签的最大长度：超过这个长度的文本是描述而不是按钮。 */
        const val PAYMENT_LABEL_MAX_LENGTH = 16

        /** 页面级验证关键词：命中即整页交人，不依赖模型选中具体元素。 */
        val VERIFICATION_PAGE_KEYWORDS = listOf(
            "人机验证", "安全验证", "拖动滑块", "拖动下方滑块", "滑动验证", "请完成验证", "滑块", "拼图",
            "请输入图片中的内容", "请输入验证码", "验证码",
            // 美团风控会换题型：滑块 → 图片文字 → 连点成线，文案里不一定带"滑块"。
            "用最短线连接", "最短线", "身份核实", "请完成下方验证",
        )

        /** 可授权的外部副作用（§4.2 L2A）。 */
        val AUTHORIZABLE_KEYWORDS = listOf(
            "提交订单", "确认下单", "立即购买", "立即抢购", "确认支付订单",
            "发送", "发布", "提交", "删除", "移除", "清空", "注销", "解绑", "订阅", "开通会员",
        )

        /** 通用确认文案；必须结合页面语义才能定级。 */
        val GENERIC_CONFIRM_TEXTS = setOf("确定", "确认", "继续", "好的", "同意")

        /** 明确可逆的导航文案。 */
        val NAVIGATION_TEXTS = setOf(
            "返回", "取消", "关闭", "跳过", "以后再说", "搜索", "更多", "菜单", "首页", "上一页", "下一页",
        )
    }
}

/**
 * 闸门判定结果。
 *
 * @property riskLevel 判定出的风险等级
 * @property allowed 基线是否允许执行（L2A 与 L2B 恒为 false）
 * @property reason 判定理由，写入审计
 * @property requiresConfirmation 是否允许走当次确认：L2A 为 true，L2B 与语义不明恒为 false
 */
data class GuardDecision(
    val riskLevel: RiskLevel,
    val allowed: Boolean,
    val reason: String,
    val requiresConfirmation: Boolean = false,
)
