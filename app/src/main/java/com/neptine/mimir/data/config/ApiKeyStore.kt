package com.neptine.mimir.data.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型 API Key 的加密存储（设计文档 §4.6）。
 *
 * 密钥本体只存在这里：Room 的 model_config 表只保存别名，避免 toString/日志/崩溃报告
 * 把密钥带出去。主密钥由 Android Keystore 持有且不可导出。
 */
@Singleton
class ApiKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 首次访问时才建，避免应用启动就做 Keystore 操作。 */
    private val prefs: SharedPreferences by lazy { createPreferences() }

    /**
     * 功能：保存一条密钥
     * 参数：alias 别名（与 model_config.api_key_alias 对应）；apiKey 密钥本体
     * 返回：无
     */
    fun save(alias: String, apiKey: String) {
        prefs.edit().putString(alias, apiKey).apply()
    }

    /**
     * 功能：读取一条密钥
     * 参数：alias 别名
     * 返回：密钥本体；未保存或解密失败时为 null
     */
    fun load(alias: String): String? = runCatching { prefs.getString(alias, null) }.getOrNull()

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

    private companion object {
        /** 加密偏好文件名。 */
        const val FILE_NAME = "mimir_secrets"
    }
}
