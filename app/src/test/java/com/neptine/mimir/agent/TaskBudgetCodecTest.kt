package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.CompletionContract
import com.neptine.mimir.core.contract.TaskBudget
import com.neptine.mimir.core.contract.TaskConstraints
import com.neptine.mimir.core.contract.TaskSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 任务预算的默认值与编解码（0.3.3）。
 *
 * 预算决定"长任务能不能跑完"：默认值、缺省回落与编解码必须一致，
 * 否则升级后新建任务是 60 步、旧格式回落却是 40 步，排障时会对不上。
 */
class TaskBudgetCodecTest {

    /** 功能：构造一个最小 TaskSpec；参数：无；返回：预算为默认值的任务约束 */
    private fun spec() = TaskSpec(
        taskId = "task-1",
        taskType = "GENERAL",
        packageName = "",
        inputText = "测试任务",
        parameters = emptyMap(),
        constraints = TaskConstraints(),
        riskGrants = emptyList(),
        completionContract = CompletionContract(
            successBoundary = "",
            requiredFields = emptyList(),
        ),
        budget = TaskBudget(),
    )

    /**
     * 默认预算与产品决策一致：60 步 / 90 次 / 45 分钟。
     */
    @Test
    fun defaultsMatchAgreedBudget() {
        val budget = TaskBudget()
        assertEquals(60, budget.maxSteps)
        assertEquals(90, budget.maxModelCalls)
        assertEquals(45 * 60 * 1000L, budget.maxDurationMs)
    }

    /**
     * 旧格式或字段缺失时回落到同一套默认值。
     */
    @Test
    fun decodeFallsBackToRaisedDefaults() {
        val decoded = TaskSpecCodec.decode("{}")
        assertEquals(60, decoded.budget.maxSteps)
        assertEquals(90, decoded.budget.maxModelCalls)
        assertEquals(45 * 60 * 1000L, decoded.budget.maxDurationMs)
    }

    /**
     * 往返编解码不丢预算。
     */
    @Test
    fun encodeDecodeKeepsBudget() {
        val decoded = TaskSpecCodec.decode(TaskSpecCodec.encode(spec()))
        assertEquals(60, decoded.budget.maxSteps)
        assertEquals(90, decoded.budget.maxModelCalls)
        assertEquals(45 * 60 * 1000L, decoded.budget.maxDurationMs)
        assertEquals(120_000L, decoded.budget.maxStepDurationMs)
    }
}
