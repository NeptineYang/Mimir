package com.neptine.mimir.core.contract

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 稳定性原因码的分类口径。
 *
 * 成功与放弃由状态直接决定；失败/转人工才依赖文案。这里锁住最常见的几类文案，
 * 防止改一句话就把整类任务从统计里漏掉或归错组。
 */
class TaskReasonTest {

    /** 成功与放弃由状态直接决定，不读文案。 */
    @Test
    fun successAndAbortComeFromState() {
        assertEquals(TaskReason.DONE, TaskReason.classify(TaskState.DONE, null))
        assertEquals(TaskReason.DONE_READBACK, TaskReason.classify(TaskState.DONE_READBACK, "ok"))
        assertEquals(
            TaskReason.COMPLETED_UNVERIFIED,
            TaskReason.classify(TaskState.COMPLETED_UNVERIFIED, "已停在目标页面"),
        )
        assertEquals(TaskReason.USER_ABORT, TaskReason.classify(TaskState.ABORTED, "用户放弃"))
    }

    /** 预算耗尽按三种预算拆开。 */
    @Test
    fun failureBudgetIsSplitByKind() {
        assertEquals(
            TaskReason.BUDGET_STEPS,
            TaskReason.classify(TaskState.FAILED, "步数预算耗尽（60 步）"),
        )
        assertEquals(
            TaskReason.BUDGET_CALLS,
            TaskReason.classify(TaskState.FAILED, "模型调用预算耗尽（90 次）"),
        )
        assertEquals(
            TaskReason.BUDGET_TIME,
            TaskReason.classify(TaskState.FAILED, "任务时长超限（2700 秒）"),
        )
    }

    /** 失败原因里的卡死、环境与模型错误各归其类。 */
    @Test
    fun failureMapsStuckEnvironmentAndModel() {
        assertEquals(
            TaskReason.STUCK,
            TaskReason.classify(TaskState.FAILED, "连续 3 次「TAP 坐标点击」都没有改变界面，疑似卡在同一页"),
        )
        assertEquals(
            TaskReason.ENV_OFFLINE,
            TaskReason.classify(TaskState.FAILED, "无障碍服务未连接，无法判断目标应用是否落在副屏上"),
        )
        assertEquals(
            TaskReason.MODEL_PARSE,
            TaskReason.classify(TaskState.FAILED, "连续 2 次输出无法解析：..."),
        )
        assertEquals(
            TaskReason.LAUNCH_FAILED,
            TaskReason.classify(TaskState.FAILED, "目标应用启动到了主屏而不是副屏"),
        )
        assertEquals(
            TaskReason.LAUNCH_FAILED,
            TaskReason.classify(TaskState.FAILED, "等待 24 秒后，副屏 22 上仍未出现 tv.danmaku.bili"),
        )
        assertEquals(
            TaskReason.DISPLAY_MISMATCH,
            TaskReason.classify(TaskState.FAILED, "副屏已重建（代次 2 ≠ 1）"),
        )
        assertEquals(
            TaskReason.OTHER,
            TaskReason.classify(TaskState.FAILED, "未知错误"),
        )
    }

    /** 转人工原因里，安全类优先于泛化的确认类。 */
    @Test
    fun humanMapsSafetyBeforeAuth() {
        assertEquals(
            TaskReason.MAIN_SCREEN_CONFLICT,
            TaskReason.classify(TaskState.NEED_HUMAN, "主屏正在使用 com.android.settings，与任务共享状态，任务已暂停"),
        )
        assertEquals(
            TaskReason.FOREGROUND_OUT_OF_SCOPE,
            TaskReason.classify(
                TaskState.NEED_HUMAN,
                "前台应用是 com.google.android.settings.intelligence，不在任务目标 com.android.settings 范围内，禁止注入",
            ),
        )
        assertEquals(
            TaskReason.L2B_BLOCKED,
            TaskReason.classify(TaskState.NEED_HUMAN, "支付密码需要本人输入"),
        )
        assertEquals(
            TaskReason.AUTH_REQUIRED,
            TaskReason.classify(TaskState.NEED_HUMAN, "目标「Delete」命中外部副作用关键词「delete」，需要一次性授权"),
        )
        assertEquals(
            TaskReason.VERIFY_MISMATCH,
            TaskReason.classify(TaskState.NEED_HUMAN, "初验未通过：订单已提交（期望包含…）"),
        )
        assertEquals(
            TaskReason.INTERRUPTED,
            TaskReason.classify(TaskState.NEED_HUMAN, "应用进程中断，任务停在第 3 步"),
        )
        assertEquals(
            TaskReason.UNRESOLVED_WRITE,
            TaskReason.classify(TaskState.NEED_HUMAN, "应用进程中断，有 1 条写入结果未知；不能自动重放"),
        )
        assertEquals(
            TaskReason.REPLAN_EXHAUSTED,
            TaskReason.classify(
                TaskState.NEED_HUMAN,
                "计划连续失效且已重规划 2 次：步骤 s1-1 执行 12 个动作仍未达成",
            ),
        )
    }
}
