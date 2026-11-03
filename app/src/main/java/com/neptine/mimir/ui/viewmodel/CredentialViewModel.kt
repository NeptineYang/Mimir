package com.neptine.mimir.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neptine.mimir.core.contract.CredentialPurpose
import com.neptine.mimir.core.contract.CredentialRecord
import com.neptine.mimir.data.credential.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 凭据页状态（设计文档 §2.5.8）。
 *
 * 只暴露元数据：密码明文进入 [CredentialStore] 后不再通过任何 UI 状态回读。
 *
 * @property rows 凭据列表
 * @property error 错误提示；无错误为 null
 * @property message 成功提示；无提示为 null
 */
data class CredentialUiState(
    val rows: List<CredentialRow> = emptyList(),
    val error: String? = null,
    val message: String? = null,
)

/**
 * 凭据列表展示行。
 *
 * @property id 凭据标识（模型只提交它）
 * @property packageName 绑定应用
 * @property accountAlias 账号别名
 * @property createdAt 创建时间
 */
data class CredentialRow(
    val id: String,
    val packageName: String,
    val accountAlias: String,
    val createdAt: Long,
)

@HiltViewModel
class CredentialViewModel @Inject constructor(
    private val store: CredentialStore,
) : ViewModel() {

    private val _state = MutableStateFlow(CredentialUiState())
    val state: StateFlow<CredentialUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * 功能：重新读取凭据元数据
     * 参数：无
     * 返回：无
     */
    fun refresh() {
        viewModelScope.launch {
            val rows = runCatching { store.list() }.getOrDefault(emptyList()).map { record ->
                CredentialRow(
                    id = record.id,
                    packageName = record.packageName,
                    accountAlias = record.accountAlias,
                    createdAt = record.createdAt,
                )
            }
            _state.value = _state.value.copy(rows = rows)
        }
    }

    /**
     * 功能：保存一条普通登录密码
     * 参数：packageName 目标包名；accountAlias 账号别名；secret 密码明文
     * 返回：无
     *
     * 明文只传给加密存储，不进入日志、状态或任何提示文本。
     */
    fun save(packageName: String, accountAlias: String, secret: String) {
        val pkg = packageName.trim()
        val alias = accountAlias.trim()
        if (pkg.isEmpty() || !pkg.contains('.')) {
            _state.value = _state.value.copy(error = "包名要写成完整应用 ID，例如 com.example.app")
            return
        }
        if (alias.isEmpty()) {
            _state.value = _state.value.copy(error = "账号别名不能为空")
            return
        }
        if (secret.isEmpty()) {
            _state.value = _state.value.copy(error = "密码不能为空")
            return
        }
        runCatching {
            store.save(
                CredentialRecord(
                    id = "cred-${UUID.randomUUID()}",
                    packageName = pkg,
                    accountAlias = alias,
                    purpose = CredentialPurpose.LOGIN_PASSWORD,
                    createdAt = System.currentTimeMillis(),
                ),
                secret,
            )
        }.onSuccess {
            _state.value = _state.value.copy(error = null, message = "已保存；密码只存在 Keystore 加密存储中")
            refresh()
        }.onFailure {
            _state.value = _state.value.copy(error = "保存失败：${it.message ?: it::class.java.simpleName}")
        }
    }

    /**
     * 功能：删除一条凭据
     * 参数：id 凭据标识
     * 返回：无
     */
    fun delete(id: String) {
        runCatching { store.delete(id) }
            .onSuccess {
                _state.value = _state.value.copy(error = null, message = "已删除该凭据")
                refresh()
            }
            .onFailure {
                _state.value = _state.value.copy(error = "删除失败：${it.message ?: it::class.java.simpleName}")
            }
    }

    /**
     * 功能：清除提示
     * 参数：无
     * 返回：无
     */
    fun clearNotice() {
        _state.value = _state.value.copy(error = null, message = null)
    }
}
