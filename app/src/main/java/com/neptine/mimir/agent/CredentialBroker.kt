package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.ActionKind
import com.neptine.mimir.core.contract.AgentAction
import com.neptine.mimir.core.contract.CredentialPurpose
import com.neptine.mimir.core.contract.CredentialRecord
import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.data.credential.CredentialStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地凭据代理（设计文档 §2.5.8）。
 *
 * 核心原则是"Agent 可以请求使用一条凭据，但不能读取这条凭据"：
 * - 模型只提交 credential_id；
 * - 明文只在本类解密，并立刻交给受控文本输入通道（无障碍 ACTION_SET_TEXT 优先）；
 * - prompt、审计、动作意图与日志都只看到 credential_id 与填充结果。
 *
 * 填充前会再次执行 [CredentialPolicy] 的全部校验，不依赖 Guard 是否已经放行。
 */
@Singleton
class CredentialBroker @Inject constructor(
    private val store: CredentialStore,
    private val textInput: TextInput,
) {

    /**
     * 功能：列出当前页面可用的凭据元数据
     * 参数：packageName 当前前台包名
     * 返回：绑定到该包的登录凭据；只含 id / 账号别名，不含密码
     */
    fun availableFor(packageName: String): List<CredentialRecord> =
        store.listFor(packageName).filter { it.purpose == CredentialPurpose.LOGIN_PASSWORD }

    /**
     * 功能：按标识取凭据元数据
     * 参数：id 凭据标识
     * 返回：元数据；不存在为 null
     */
    fun find(id: String): CredentialRecord? = store.find(id)

    /**
     * 功能：校验一次凭据使用请求
     * 参数：id 凭据标识；snapshot 当前观察；elementId 目标输入框编号
     * 返回：校验结果
     */
    fun check(id: String, snapshot: ObservationSnapshot, elementId: Int?): CredentialCheck =
        CredentialPolicy.check(find(id), snapshot, elementId)

    /**
     * 功能：把一条凭据填充到当前密码框
     * 参数：handle 建屏句柄；action 凭据动作（text 是 credential_id）；snapshot 决策时的观察
     * 返回：受控输入通道是否接受填充；任何校验失败都返回 false
     *
     * 不重试、不回显、不记录明文；填充结果是否可信由 PostCondition 判定，
     * 无法判定时上层按 OUTCOME_UNKNOWN 转人工核对。
     */
    suspend fun fill(
        handle: DisplayHandle,
        action: AgentAction.Do,
        snapshot: ObservationSnapshot,
    ): Boolean {
        if (action.kind != ActionKind.CREDENTIAL) return false
        val id = action.text.orEmpty()
        if (id.isEmpty()) return false
        val check = check(id, snapshot, action.elementId)
        if (!check.usable) return false
        val secret = store.loadSecret(id)?.takeIf { it.isNotEmpty() } ?: return false
        // 明文只在这里短暂存在：构造一个 TEXT 动作交给既有输入通道，随后立刻离开作用域
        val fillAction = action.copy(kind = ActionKind.TEXT, text = secret)
        return textInput.type(handle, fillAction)
    }
}
