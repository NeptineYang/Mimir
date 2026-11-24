package com.neptine.mimir.core.contract

/**
 * 任务结束或挂起的原因码（稳定性统计口径）。
 *
 * 目标不是替代给人看的原因文案，而是在其旁边落一个稳定、可聚合的枚举：
 * 历史页按码分布统计，后续调优才有同一把尺子。文案可以改，码不允许随意改。
 */
enum class TaskReason {
    // ---------- 成功与交付 ----------
    DONE,
    DONE_READBACK,
    VERIFIED_BY_HUMAN,
    COMPLETED_UNVERIFIED,
    DELIVERED_UNVERIFIED,
    USER_ABORT,

    // ---------- 预算与卡死 ----------
    BUDGET_STEPS,
    BUDGET_CALLS,
    BUDGET_TIME,
    STUCK,
    NO_EFFECT,
    OUTCOME_UNKNOWN,

    // ---------- 验证与安全 ----------
    VERIFY_MISMATCH,
    MAIN_SCREEN_CONFLICT,
    FOREGROUND_OUT_OF_SCOPE,
    AUTH_REQUIRED,
    L2B_BLOCKED,

    // ---------- 中断与环境 ----------
    INTERRUPTED,
    UNRESOLVED_WRITE,
    MODEL_UNAVAILABLE,
    MODEL_TIMEOUT,
    MODEL_PARSE,
    PLAN_FAILED,
    ENV_OFFLINE,
    LAUNCH_FAILED,
    DISPLAY_MISMATCH,
    REPLAN_EXHAUSTED,
    OTHER,
    ;

    companion object {

        /**
         * 功能：按目标状态与原因文案归类
         * 参数：state 目标状态；reason 人工可读的挂起/失败原因
         * 返回：原因码；非终态且非 NEED_HUMAN 时为 [OTHER]
         *
         * 成功与放弃直接由状态决定；失败与转人工才读文案。
         * 文案只作为弱信号，分类规则集中在 [classifyFailure] / [classifyHuman]。
         */
        fun classify(state: TaskState, reason: String?): TaskReason = when (state) {
            TaskState.DONE -> DONE
            TaskState.DONE_READBACK -> DONE_READBACK
            TaskState.VERIFIED_BY_HUMAN -> VERIFIED_BY_HUMAN
            TaskState.COMPLETED_UNVERIFIED -> COMPLETED_UNVERIFIED
            TaskState.ABORTED -> USER_ABORT
            TaskState.FAILED -> classifyFailure(reason)
            TaskState.NEED_HUMAN -> classifyHuman(reason)
            else -> OTHER
        }

        /**
         * 功能：把失败原因归类
         * 参数：reason 失败原因文案
         * 返回：原因码
         */
        private fun classifyFailure(reason: String?): TaskReason {
            val text = reason.orEmpty()
            return when {
                text.contains("步数预算耗尽") -> BUDGET_STEPS
                text.contains("模型调用预算耗尽") -> BUDGET_CALLS
                text.contains("任务时长超限") -> BUDGET_TIME
                text.contains("写入结果未知") || text.contains("未决写入") -> UNRESOLVED_WRITE
                text.contains("进程中断") -> INTERRUPTED
                text.contains("卡在同一页") || text.contains("没有改变界面") -> STUCK
                text.contains("无障碍服务未连接") ||
                    text.contains("特权服务未启动") ||
                    text.contains("没发现无线调试端口") ||
                    text.contains("binder haven't been received") -> ENV_OFFLINE
                text.contains("未配置可用的模型") -> MODEL_UNAVAILABLE
                text.contains("模型接口超时") ||
                    text.contains("单步决策超过") ||
                    text.contains("模型接口") && text.contains("超时") -> MODEL_TIMEOUT
                text.contains("规划失败") ||
                    text.contains("没有可用子任务") ||
                    text.contains("计划为空") -> PLAN_FAILED
                text.contains("无法解析") || text.contains("不可读") -> MODEL_PARSE
                text.contains("目标应用启动到了主屏") ||
                    text.contains("副屏上没出现") ||
                    text.contains("未出现在副屏") ||
                    text.contains("仍未出现") -> LAUNCH_FAILED
                text.contains("副屏已重建") ||
                    text.contains("观察来自 display") && text.contains("不一致") -> DISPLAY_MISMATCH
                text.contains("重规划") &&
                    (text.contains("次") || text.contains("耗尽")) -> REPLAN_EXHAUSTED
                text.contains("无效动作") || text.contains("NO_EFFECT") -> NO_EFFECT
                text.contains("结果未知") || text.contains("无法判定") -> OUTCOME_UNKNOWN
                else -> OTHER
            }
        }

        /**
         * 功能：把转人工原因归类
         * 参数：reason 挂起原因文案
         * 返回：原因码
         *
         * 安全类判断放在前：支付/验证码等 L2B 优先于泛化的"需要确认"。
         */
        private fun classifyHuman(reason: String?): TaskReason {
            val text = reason.orEmpty()
            return when {
                text.contains("主屏正在使用") -> MAIN_SCREEN_CONFLICT
                text.contains("不在任务目标") || text.contains("禁止注入") ->
                    FOREGROUND_OUT_OF_SCOPE
                text.contains("写入结果未知") || text.contains("未决写入") -> UNRESOLVED_WRITE
                text.contains("进程中断") -> INTERRUPTED
                text.contains("支付") ||
                    text.contains("验证码") ||
                    text.contains("人脸") ||
                    text.contains("指纹") ||
                    text.contains("银行卡") -> L2B_BLOCKED
                text.contains("需要一次性授权") ||
                    text.contains("需要你确认") ||
                    text.contains("需要确认") -> AUTH_REQUIRED
                (text.contains("验证") || text.contains("初验") || text.contains("复验")) &&
                    (text.contains("不通过") || text.contains("未通过") ||
                        text.contains("不一致") || text.contains("不符") ||
                        text.contains("MISMATCH")) -> VERIFY_MISMATCH
                text.contains("重规划") &&
                    (text.contains("次") || text.contains("耗尽")) -> REPLAN_EXHAUSTED
                text.contains("卡在同一页") -> STUCK
                text.contains("结果未知") || text.contains("无法判定") -> OUTCOME_UNKNOWN
                text.contains("无障碍服务未连接") || text.contains("特权服务未启动") -> ENV_OFFLINE
                else -> OTHER
            }
        }
    }
}
