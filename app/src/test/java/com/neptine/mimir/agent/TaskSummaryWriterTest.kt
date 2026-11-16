package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.TaskState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板总结的边界（v0.3.4）。
 *
 * 模型不可用时模板是唯一兜底：它必须说清终态与步数，
 * 失败任务必须带上原因，且原因缺失时不能留下悬空的冒号。
 */
class TaskSummaryWriterTest {

    /**
     * 成功任务写出"已完成"与步数。
     */
    @Test
    fun doneSummaryMentionsSteps() {
        val text = fallbackSummary(
            english = false,
            state = TaskState.DONE,
            input = "打开计算器算 128×64",
            steps = 8,
            reason = null,
        )
        assertTrue(text.contains("已完成"))
        assertTrue(text.contains("8 步"))
        assertTrue(text.contains("128×64"))
    }

    /**
     * 失败任务带上结束原因。
     */
    @Test
    fun failedSummaryCarriesReason() {
        val text = fallbackSummary(
            english = false,
            state = TaskState.FAILED,
            input = "点一份外卖",
            steps = 3,
            reason = "步数预算耗尽（60 步）",
        )
        assertTrue(text.contains("执行失败"))
        assertTrue(text.contains("步数预算耗尽"))
    }

    /**
     * 原因缺失时不出现"失败："这种悬空标点。
     */
    @Test
    fun failedSummaryWithoutReasonStaysClean() {
        val text = fallbackSummary(
            english = false,
            state = TaskState.FAILED,
            input = "点一份外卖",
            steps = 3,
            reason = null,
        )
        assertTrue(text.contains("执行失败"))
        assertTrue(!text.trimEnd().endsWith("："))
        assertTrue(!text.contains("：。"))
    }

    /**
     * 取消与英文界面各有一条可读结论。
     */
    @Test
    fun abortedAndEnglishSummariesAreReadable() {
        val aborted = fallbackSummary(
            english = false,
            state = TaskState.ABORTED,
            input = "测试",
            steps = 1,
            reason = "用户放弃",
        )
        assertTrue(aborted.contains("已取消"))

        val english = fallbackSummary(
            english = true,
            state = TaskState.DONE,
            input = "open calculator",
            steps = 4,
            reason = null,
        )
        assertTrue(english.contains("Finished"))
        assertTrue(english.contains("4 steps"))
    }
}
