package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.AgentAction
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.data.db.dao.RiskGrantDao
import com.neptine.mimir.data.db.entity.RiskGrantEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次性授权账本（设计文档 §1.3.1 / §4.2）。
 *
 * 授权绑定"动作类型 + 参数摘要 + 目标包名 + 完成边界"，签发后只能用一次。
 * 消费走单条带条件的 UPDATE，靠受影响行数判定是否抢到，避免重复点击与崩溃后复用。
 *
 * 一期只实现 DEFERRED_CONFIRM（用户点「继续一次」后签发）；TaskSpec 里的 AUTO 预授权
 * 需要参数完整时自动生成，当前 Planner 不产出它，因此这里不假装支持。
 */
@Singleton
class GrantBroker @Inject constructor(
    private val grantDao: RiskGrantDao,
) {

    /**
     * 功能：签发一条一次性授权
     * 参数：taskId 任务标识；actionKind 绑定的动作类型；paramsDigest 参数摘要；
     *       targetPackage 绑定的目标包名；scopeText 完成边界
     * 返回：已落库的授权
     */
    suspend fun issue(
        taskId: String,
        actionKind: String,
        paramsDigest: String,
        targetPackage: String,
        scopeText: String,
    ): RiskGrantEntity {
        val now = System.currentTimeMillis()
        val grant = RiskGrantEntity(
            grantId = UUID.randomUUID().toString(),
            taskId = taskId,
            actionKind = actionKind,
            paramsDigest = paramsDigest,
            targetPackage = targetPackage,
            scopeText = scopeText,
            consumed = false,
            consumedAt = null,
            consumedByActionId = null,
            issuedAt = now,
            expiresAt = now + GRANT_WINDOW_MS,
        )
        grantDao.insert(grant)
        return grant
    }

    /**
     * 功能：为当前动作找一条可用授权
     * 参数：taskId 任务标识；action 待执行动作；snapshot 当前观察
     * 返回：匹配的授权；没有匹配为 null
     *
     * 匹配的是摘要而不是动作标识：用户确认后页面会重新观测、元素编号会重排，
     * 只有"同类动作 + 同参数 + 同包名"才谈得上还是同一次授权。
     */
    suspend fun findMatch(
        taskId: String,
        action: AgentAction.Do,
        snapshot: ObservationSnapshot,
    ): RiskGrantEntity? {
        val digest = paramsDigestOf(action, snapshot)
        val now = System.currentTimeMillis()
        return grantDao.findAllByTask(taskId)
            .filter { grant ->
                !grant.consumed &&
                    grant.actionKind == action.kind.name &&
                    grant.paramsDigest == digest &&
                    grant.targetPackage == snapshot.packageName &&
                    (grant.expiresAt == null || grant.expiresAt > now)
            }
            .maxByOrNull { it.issuedAt }
    }

    /**
     * 功能：原子消费一条授权
     * 参数：grantId 授权标识；actionId 消费它的动作标识
     * 返回：抢到授权为 true；已被消费或不存在为 false
     */
    suspend fun consume(grantId: String, actionId: String): Boolean =
        grantDao.consumeAtomically(grantId, System.currentTimeMillis(), actionId) > 0

    private companion object {
        /** 授权有效期：用户确认后不会隔很久才恢复执行，5 分钟足够覆盖一次交接。 */
        const val GRANT_WINDOW_MS = 5 * 60 * 1000L
    }
}
