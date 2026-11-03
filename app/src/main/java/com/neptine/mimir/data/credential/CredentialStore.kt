package com.neptine.mimir.data.credential

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.neptine.mimir.core.contract.CredentialPurpose
import com.neptine.mimir.core.contract.CredentialRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject

/**
 * 凭据加密存储（设计文档 §2.5.8）。
 *
 * 密码密文与账号别名一起保存在 EncryptedSharedPreferences 中，主密钥由 Android Keystore
 * 持有且不可导出。Room、配方导出与普通审计都不保存凭据；读取明文只允许 CredentialBroker
 * 在当次授权后调用 [loadSecret]。
 *
 * 不放进 Room 的原因：数据库副本会被 `run-as cat` 拉走做联调，且导出/备份路径都以数据库为源；
 * 凭据一旦进库，任何"顺手导出"都可能把密文带出去。
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 首次访问时才建，避免应用启动就做 Keystore 操作。 */
    private val prefs: SharedPreferences by lazy { createPreferences() }

    /**
     * 功能：列出全部凭据元数据
     * 参数：无
     * 返回：按创建时间倒序的凭据；损坏条目跳过
     */
    fun list(): List<CredentialRecord> = prefs.all.values
        .mapNotNull { value -> (value as? String)?.let(::decode) }
        .sortedByDescending { it.createdAt }

    /**
     * 功能：列出指定应用的凭据
     * 参数：packageName 目标包名
     * 返回：绑定到该包的凭据元数据
     */
    fun listFor(packageName: String): List<CredentialRecord> =
        list().filter { it.packageName == packageName }

    /**
     * 功能：按标识取凭据元数据
     * 参数：id 凭据标识
     * 返回：凭据元数据；不存在或损坏时为 null
     */
    fun find(id: String): CredentialRecord? = prefs.getString(keyOf(id), null)?.let(::decode)

    /**
     * 功能：保存一条凭据
     * 参数：record 元数据；secret 密码明文（只在本方法内写入加密存储）
     * 返回：无
     */
    fun save(record: CredentialRecord, secret: String) {
        val payload = buildJsonObject {
            put("id", record.id)
            put("package", record.packageName)
            put("alias", record.accountAlias)
            put("purpose", record.purpose.name)
            put("createdAt", record.createdAt)
            put("secret", secret)
        }.toString()
        prefs.edit().putString(keyOf(record.id), payload).apply()
    }

    /**
     * 功能：删除一条凭据
     * 参数：id 凭据标识
     * 返回：无
     */
    fun delete(id: String) {
        prefs.edit().remove(keyOf(id)).apply()
    }

    /**
     * 功能：读取密码明文
     * 参数：id 凭据标识
     * 返回：密码明文；只有 CredentialBroker 可以调用
     *
     * 故意不提供"批量读取"：一次只解一条，授权与填充的粒度才可能对得上。
     */
    internal fun loadSecret(id: String): String? {
        val root = prefs.getString(keyOf(id), null)?.let(::parseObject) ?: return null
        return root["secret"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
    }

    /**
     * 功能：把存储值解码成元数据
     * 参数：raw JSON 文本
     * 返回：元数据；字段缺失或用途未知时为 null
     */
    private fun decode(raw: String): CredentialRecord? {
        val root = parseObject(raw) ?: return null
        val packageName = root.string("package") ?: return null
        val alias = root.string("alias") ?: return null
        val purpose = runCatching { CredentialPurpose.valueOf(root.string("purpose").orEmpty()) }.getOrNull()
            ?: return null
        val id = root.string("id") ?: return null
        return CredentialRecord(
            id = id,
            packageName = packageName,
            accountAlias = alias,
            purpose = purpose,
            createdAt = root["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    /** 取字符串字段；缺失或类型不符时为 null。 */
    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** 宽松解析 JSON 对象；损坏值不抛异常。 */
    private fun parseObject(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()

    /**
     * 功能：创建加密偏好存储
     * 参数：无
     * 返回：EncryptedSharedPreferences 实例
     */
    private fun createPreferences(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 偏好键：id 只参与键名，不写入值。 */
    private fun keyOf(id: String): String = "cred:$id"

    private companion object {
        /** 与 API Key 分开一个文件：凭据可单独删除，不误伤模型配置。 */
        const val FILE_NAME = "mimir_credentials"
    }
}
