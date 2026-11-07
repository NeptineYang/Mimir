package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.RiskLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预设授权的边界（设计文档 §5.7）。
 *
 * 这四个用例覆盖的是安全边界，不是实现细节：
 * 一旦 L2B 被预设授权放行，定时任务就可能在无人值守时完成支付或读取验证码。
 */
class StandingGrantTest {

    /**
     * L2A 且任务开启预设授权：放行。
     */
    @Test
    fun l2aWithStandingGrantIsApproved() {
        val decision = GuardDecision(
            riskLevel = RiskLevel.L2A,
            allowed = false,
            reason = "目标「提交」命中外部副作用关键词",
            requiresConfirmation = true,
        )
        assertTrue(canAutoApprove(decision, autoApproveL2a = true))
    }

    /**
     * L2A 但任务没开预设授权：仍走当次确认。
     */
    @Test
    fun l2aWithoutStandingGrantNeedsConfirmation() {
        val decision = GuardDecision(
            riskLevel = RiskLevel.L2A,
            allowed = false,
            reason = "目标「发送」命中外部副作用关键词",
            requiresConfirmation = true,
        )
        assertFalse(canAutoApprove(decision, autoApproveL2a = false))
    }

    /**
     * L2B 永远不被预设授权放行：Guard 给的 requiresConfirmation 为 false。
     */
    @Test
    fun l2bIsNeverAutoApproved() {
        val decision = GuardDecision(
            riskLevel = RiskLevel.L2B,
            allowed = false,
            reason = "目标「立即支付」是仅本人步骤",
            requiresConfirmation = false,
        )
        assertFalse(canAutoApprove(decision, autoApproveL2a = true))
    }

    /**
     * 普通动作本来就允许执行，与预设授权无关。
     */
    @Test
    fun ordinaryActionDoesNotUseStandingGrant() {
        val decision = GuardDecision(
            riskLevel = RiskLevel.L1,
            allowed = true,
            reason = "普通写入",
        )
        assertFalse(canAutoApprove(decision, autoApproveL2a = true))
    }
}
