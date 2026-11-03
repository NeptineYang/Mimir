package com.neptine.mimir.di

import com.neptine.mimir.data.remote.LlmClient
import com.neptine.mimir.data.remote.OpenAiCompatibleClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import com.neptine.mimir.data.remote.NetworkTimingListener
import okhttp3.OkHttpClient

/** 网络装配：JSON 解析器、HTTP 客户端与模型后端实现。 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * 功能：提供 JSON 解析器
     * 参数：无
     * 返回：忽略未知字段的 Json 实例
     *
     * ignoreUnknownKeys 必须开启：各后端会在响应里附加自定义字段。
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    /**
     * 功能：提供 HTTP 客户端
     * 参数：无
     * 返回：共享的 OkHttpClient 实例
     *
     * 必须显式配超时：OkHttp 默认 readTimeout 只有 10 秒，而本项目用的是**非流式**
     * chat/completions——服务端生成完整段回复后才发出第一个字节，一次视觉请求
     * （540×1200 截图 + 元素表）超过 10 秒是常态。真机上表现为任务直接 FAILED，
     * 原因只写一个 "timeout"，与网络不通难以区分。
     *
     * callTimeout 放在 readTimeout 之外：它管整次调用（含重定向与重试），
     * 只作兜底，不替代读超时。
     */
    @Provides
    @Singleton
    fun provideHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // 分段计时：区分"网络/DNS 慢"与"服务端生成慢"，见 NetworkTimingListener 的说明。
        .eventListener(NetworkTimingListener())
        .build()

    /**
     * 功能：提供模型后端实现
     * 参数：client OpenAI 兼容客户端
     * 返回：LlmClient 实例
     */
    @Provides
    @Singleton
    fun provideLlmClient(client: OpenAiCompatibleClient): LlmClient = client

    /** 建立连接的上限（秒）；真机蜂窝网握手可能偏慢。 */
    private const val CONNECT_TIMEOUT_SECONDS = 15L

    /**
     * 等模型完整回复的上限（秒）；非流式请求的等待全部算在这里。
     *
     * 120s 在真机上被真实模型打穿过一次（GLM-5.3-flash + 截图 + 元素表，单次视觉请求 >120s），
     * 任务直接判 FAILED 并丢掉已经走了 3 步的上下文。180s 仍被高德路线页打穿
     * （2026-11-03 实测单次视觉请求 >180s），再放宽到 240s：宁可慢，也不要因为
     * 服务端排队把可继续的任务判死。
     */
    private const val READ_TIMEOUT_SECONDS = 240L

    /** 上传请求体的上限（秒）；截图是 base64，体积不大但弱网下仍要给余量。 */
    private const val WRITE_TIMEOUT_SECONDS = 30L

    /** 整次调用的绝对上限（秒）；含重定向与重试，必须大于读超时。 */
    private const val CALL_TIMEOUT_SECONDS = 300L
}
