package com.neptine.mimir.ui.voice

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * 端侧流式语音识别引擎（sherpa-onnx + 中文 zipformer transducer）。
 *
 * 模型放在 assets/asr 下随 APK 分发，识别全程在本机完成：音频不落盘、不出设备。
 * 进程内只建一个实例——加载 70 MB 级模型要数秒，重复创建会卡顿并叠加内存。
 */
internal object OfflineAsrEngine {

    /** 采样率；必须与 AudioRecord 的录音参数一致。 */
    const val SAMPLE_RATE = 16000

    /** 模型在 assets 内的目录。文件名取自 sherpa-onnx 模型发布包，不能改写。 */
    private const val MODEL_DIR = "asr"
    private const val ENCODER = "$MODEL_DIR/encoder-epoch-20-avg-1-chunk-16-left-128.int8.onnx"
    private const val DECODER = "$MODEL_DIR/decoder-epoch-20-avg-1-chunk-16-left-128.onnx"
    private const val JOINER = "$MODEL_DIR/joiner-epoch-20-avg-1-chunk-16-left-128.int8.onnx"
    private const val TOKENS = "$MODEL_DIR/tokens.txt"

    /** 热词加权；sherpa-onnx 官方示例用 2.0，配置默认 1.5。 */
    private const val HOTWORDS_SCORE = 2.0f

    /** 模型声明的建模单元：中文按字。热词必须同单元，英文词不会生效。 */
    private const val MODELING_UNIT = "cjkchar"

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    /** 上一次加载失败的原因；加载成功后清空。 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 功能：判断引擎是否已加载
     * 参数：无
     * 返回：已加载为 true
     */
    fun isLoaded(): Boolean = recognizer != null

    /**
     * 功能：确保引擎可用（首次调用会加载模型，耗时数秒，必须在工作线程调用）
     * 参数：context 用于取 AssetManager
     * 返回：引擎实例；失败时为 null，原因见 [lastError]
     */
    @Synchronized
    fun ensureLoaded(context: Context): OnlineRecognizer? {
        recognizer?.let { return it }
        lastError = null
        return try {
            build(context.applicationContext).also { recognizer = it }
        } catch (t: Throwable) {
            // JNI 侧失败抛 UnsatisfiedLinkError 或 IllegalArgumentException，两者都要能落到 UI 上
            lastError = t.message ?: t.javaClass.simpleName
            null
        }
    }

    /**
     * 功能：开一条识别流
     * 参数：hotwords 热词表，每行一个词；空串表示不加权
     * 返回：识别流；引擎未加载时为 null
     */
    fun newStream(hotwords: String): OnlineStream? = recognizer?.createStream(hotwords)

    /**
     * 功能：设置页自检：加载一次并报告耗时
     * 参数：context 应用上下文
     * 返回：可直接展示的中文结论
     */
    fun selfCheck(context: Context): String {
        if (recognizer != null) return "模型已就绪（进程内已加载）"
        val begin = SystemClock.elapsedRealtime()
        ensureLoaded(context) ?: return "加载失败：${lastError ?: "未知原因"}"
        return "加载正常，耗时 ${SystemClock.elapsedRealtime() - begin} ms"
    }

    /**
     * 功能：构造识别引擎配置
     * 参数：context 应用上下文
     * 返回：流式 transducer 引擎
     */
    private fun build(context: Context): OnlineRecognizer {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = ENCODER,
                    decoder = DECODER,
                    joiner = JOINER,
                ),
                tokens = TOKENS,
                numThreads = 2,
                // 留空让 sherpa-onnx 从模型元数据自检类型。写死 "zipformer" 时，
                // 本包（chunk-16-left-128 结构）会被当成另一种编码器，原生层直接 exit(-1)。
                modelType = "",
                modelingUnit = MODELING_UNIT,
            ),
            // 热词只在 modified_beam_search 下生效；改成 greedy_search 会静默失效
            decodingMethod = "modified_beam_search",
            hotwordsScore = HOTWORDS_SCORE,
        )
        return OnlineRecognizer(assetManager = context.assets, config = config)
    }
}
