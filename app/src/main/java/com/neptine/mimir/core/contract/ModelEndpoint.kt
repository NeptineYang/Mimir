package com.neptine.mimir.core.contract

/**
 * 模型后端配置（设计文档 §2.4 / §4.6）。
 *
 * 刻意不含 apiKey：data class 的 toString 会把字段打进日志与崩溃报告，
 * 密钥保存在 EncryptedSharedPreferences 中，调用时按 apiKeyAlias 取出再注入。
 *
 * @property baseUrl OpenAI 兼容接口地址；云端 API、局域网 vLLM、公网反代只是这里不同
 * @property apiKeyAlias 密钥在加密存储中的别名
 * @property plannerModel 规划模型名；只在开局调用一次
 * @property executorModel 执行模型名；每一步调用
 * @property reviewerModel 复核模型名；L1 与视觉写入前调用
 */
data class ModelConfig(
    val baseUrl: String,
    val apiKeyAlias: String,
    val plannerModel: String,
    val executorModel: String,
    val reviewerModel: String,
)
