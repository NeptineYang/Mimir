package com.neptine.mimir.core.contract

/**
 * 动作协议（设计文档 §1.3 / §2.4）。
 *
 * 模型只输出固定 schema，且必须携带 observation_id 与目标指纹；
 * 授权、注入与证据判定都在本地完成，不属于模型职责。
 */
sealed interface AgentAction {

    /**
     * 执行一个界面动作。
     *
     * @property kind 动作类型
     * @property observationId 决策所依据的观察编号；执行前会重新观测比对，不匹配即作废
     * @property targetFingerprint 目标身份；树模式与视觉模式都由它承载
     * @property elementId 树模式下的元素编号 #N；纯视觉模式为空
     * @property normX 纯视觉模式的相对横坐标（0–1000）
     * @property normY 纯视觉模式的相对纵坐标（0–1000）
     * @property text 文本动作的正文；凭据动作里是 credential_id（不是密码本体）
     * @property swipePath 滑动轨迹（原生像素）；非滑动动作为空
     * @property stepDone 本动作同时完成了当前计划步骤（"边做边声明"）
     * @property stepSummary 步骤完成说明；与 [stepDone] 一起使用时写进审计
     */
    data class Do(
        val kind: ActionKind,
        val observationId: String,
        val targetFingerprint: TargetFingerprint?,
        val elementId: Int? = null,
        val normX: Int? = null,
        val normY: Int? = null,
        val text: String? = null,
        val swipePath: List<Point> = emptyList(),
        val stepDone: Boolean = false,
        val stepSummary: String? = null,
    ) : AgentAction

    /**
     * 声明任务完成；这只是待验证声明，不是结果证据（§4.4.1）。
     *
     * @property observationId 声明所依据的观察编号
     * @property summary 完成说明
     */
    data class Finish(
        val observationId: String,
        val summary: String,
    ) : AgentAction

    /**
     * 声明当前计划步骤已达成（§1.3.2）。
     *
     * 模型只能"声明"；是否推进游标由 Orchestrator 用该步骤的后置条件在当前界面上复核。
     * 它不产生任何外部副作用，因此永远不是 L2 动作。
     *
     * @property observationId 声明所依据的观察编号
     * @property summary 这一步做了什么
     */
    data class StepDone(
        val observationId: String,
        val summary: String,
    ) : AgentAction

    /**
     * 请求受限重规划（§1.3.2）。
     *
     * 只有模型能看出"入口不可用 / 商品缺货 / 前提失效"这类界面事实。模型只能"请求"，
     * 是否重规划、能不能重规划由 Orchestrator 按次数上限与预算决定。
     *
     * @property observationId 请求所依据的观察编号
     * @property reason 哪条前提失效
     */
    data class Replan(
        val observationId: String,
        val reason: String,
    ) : AgentAction
}

/** 动作类型，与设备层端口一一对应（§1.2 ActionRegistry）。 */
enum class ActionKind {
    TAP,
    SWIPE,
    TEXT,
    BACK,
    HOME,
    LAUNCH,
    WAIT,

    /**
     * 使用一条已保存的普通登录密码（设计文档 §2.5.8）。
     *
     * 模型只提交 credential_id；明文由本地 CredentialBroker 解密后经受控输入通道填充，
     * 不进入 prompt、审计参数或任何日志。该动作永远需要用户当次授权。
     */
    CREDENTIAL,
}
