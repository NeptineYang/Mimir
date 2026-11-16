package com.neptine.mimir.core.contract

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态迁移的边界（0.3.3 hotfix）。
 *
 * `CREATED` 必须在建屏/准备阶段失败时也能落 `FAILED`：
 * 缺这条边，任务会停在 CREATED，前台服务队列立刻再次取到同一条，
 * 形成紧密重试循环（实测 1600+ 次/分钟）。
 */
class TaskStateTransitionTest {

    /**
     * 排队任务准备失败可以落 FAILED。
     */
    @Test
    fun createdCanFailBeforeStart() {
        assertTrue(TaskState.FAILED in allowedTransitions(TaskState.CREATED))
    }

    /**
     * 新增 FAILED 边不能挤掉原有的规划与打断路径。
     */
    @Test
    fun createdStillCanPlanAndAbort() {
        val next = allowedTransitions(TaskState.CREATED)
        assertTrue(TaskState.PLANNING in next)
        assertTrue(TaskState.ABORTED in next)
    }
}
