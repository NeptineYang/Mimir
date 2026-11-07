package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.RiskLevel

/**
 * 定时任务的预设授权判定（设计文档 §5.7）。
 *
 * 只对 L2A 生效。L2B 在 Guard 那一步 `requiresConfirmation` 恒为 false，
 * 所以这里天然不会放行支付、验证码、人脸；不需要也不应该在调用点再做一次风险等级判断。
 *
 * 预设授权放行的是"这一类动作"而不是"这一个动作"，所以调用方仍要按当前页面重新断言目标，
 * 审计也必须单独写一条 STANDING_GRANT。
 *
 * @param decision 闸门判定
 * @param autoApproveL2a 任务是否开启预设授权
 * @return true 表示可以跳过一次性授权直接执行
 */
fun canAutoApprove(decision: GuardDecision, autoApproveL2a: Boolean): Boolean =
    autoApproveL2a && decision.requiresConfirmation && decision.riskLevel == RiskLevel.L2A
