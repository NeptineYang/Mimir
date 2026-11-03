package com.neptine.mimir.data.config

import com.neptine.mimir.data.db.dao.ConfigDao
import com.neptine.mimir.data.db.entity.ModelConfigEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型后端的读写口（设计文档 §2.4 / §4.6）。
 *
 * 表里只存 base_url、三个模型名与密钥别名；密钥本体在 [ApiKeyStore]。
 * 角色分工（Planner / Executor / Reviewer）各自可以指向不同模型，因此这里按角色取。
 */
@Singleton
class ModelConfigRepository @Inject constructor(
    private val configDao: ConfigDao,
    private val keyStore: ApiKeyStore,
) {

    /**
     * 功能：读取某个角色实际可用的后端参数
     * 参数：role 角色
     * 返回：后端参数；未配置或缺少密钥时为 null
     */
    suspend fun resolve(role: ModelRole): ResolvedModel? {
        val config = configDao.findModelConfig() ?: return null
        val apiKey = keyStore.load(config.apiKeyAlias).orEmpty()
        if (config.baseUrl.isBlank() || apiKey.isBlank()) return null

        val model = when (role) {
            ModelRole.PLANNER -> config.plannerModel
            ModelRole.EXECUTOR -> config.executorModel
            ModelRole.REVIEWER -> config.reviewerModel
        }
        if (model.isBlank()) return null
        return ResolvedModel(baseUrl = config.baseUrl, apiKey = apiKey, model = model)
    }

    /**
     * 功能：保存后端配置
     * 参数：baseUrl 后端地址；三个模型名；apiKey 密钥（为空表示不修改已有密钥）
     * 返回：无
     */
    suspend fun save(
        baseUrl: String,
        plannerModel: String,
        executorModel: String,
        reviewerModel: String,
        apiKey: String?,
    ) {
        if (!apiKey.isNullOrBlank()) {
            keyStore.save(API_KEY_ALIAS, apiKey)
        }
        configDao.upsertModelConfig(
            ModelConfigEntity(
                id = CONFIG_ID,
                baseUrl = baseUrl.trim(),
                apiKeyAlias = API_KEY_ALIAS,
                plannerModel = plannerModel.trim(),
                executorModel = executorModel.trim(),
                reviewerModel = reviewerModel.trim(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * 功能：判断是否已保存过密钥
     * 参数：无
     * 返回：已保存为 true
     */
    fun hasApiKey(): Boolean = !keyStore.load(API_KEY_ALIAS).isNullOrBlank()

    /** 模型角色。 */
    enum class ModelRole { PLANNER, EXECUTOR, REVIEWER }

    private companion object {
        /** 一期只支持一套后端配置。 */
        const val CONFIG_ID = "default"

        /** 密钥在加密存储中的别名；写进 model_config，便于将来支持多套后端。 */
        const val API_KEY_ALIAS = "default"
    }
}

/**
 * 解析后的后端参数。
 *
 * @property baseUrl OpenAI 兼容地址
 * @property apiKey 密钥本体；只在内存里流转，不落库
 * @property model 模型名
 */
data class ResolvedModel(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)
