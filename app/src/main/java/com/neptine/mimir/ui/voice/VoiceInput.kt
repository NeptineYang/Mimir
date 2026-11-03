package com.neptine.mimir.ui.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** 语音输入的状态。 */
enum class VoiceStatus {
    /** 空闲。 */
    IDLE,

    /** 正在收音。 */
    LISTENING,

    /** 本机没有可用的识别服务。 */
    UNAVAILABLE,

    /** 没有麦克风权限。 */
    DENIED,
}

/**
 * 按住说话的控制器。
 *
 * 用的是系统 SpeechRecognizer：录音由识别服务处理，本应用不保存音频文件，
 * 也不把音频写进任何自建请求。若后续要改为端侧或自建识别，替换本类即可，
 * 页面只依赖 [status] / [message] / [start] / [stop]。
 */
@Stable
class VoiceInputController internal constructor(
    private val context: Context,
) {

    /** 当前状态；Compose 读它会自动重组。 */
    var status: VoiceStatus by mutableStateOf(VoiceStatus.IDLE)
        private set

    /** 一次性提示（错误原因或权限缺失）；下次按下时清空。 */
    var message: String? by mutableStateOf(null)
        private set

    /** 识别出文本时的回调；由 [rememberVoiceInputController] 绑定。 */
    internal var onText: (String) -> Unit = {}

    private var recognizer: SpeechRecognizer? = null

    /**
     * 功能：判断是否已授予麦克风权限
     * 参数：无
     * 返回：已授予为 true
     */
    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * 功能：开始收音（按下时调用）
     * 参数：无
     * 返回：无
     */
    fun start() {
        message = null
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status = VoiceStatus.UNAVAILABLE
            message = "本机没有可用的语音识别服务"
            return
        }
        if (!hasPermission()) {
            status = VoiceStatus.DENIED
            message = "需要麦克风权限才能语音输入"
            return
        }
        val engine = recognizer ?: createRecognizer()
        status = VoiceStatus.LISTENING
        runCatching { engine.startListening(recognitionIntent()) }
            .onFailure {
                status = VoiceStatus.IDLE
                message = "无法启动语音识别"
            }
    }

    /**
     * 功能：结束收音并索取结果（松开时调用）
     * 参数：无
     * 返回：无
     *
     * 状态立即回到 IDLE：停止收音后识别服务未必回调（没有声音时它可能什么都不发），
     * 若等回调才复位，按钮会一直停在"正在听"。结果与错误仍由监听器继续送达。
     */
    fun stop() {
        recognizer?.stopListening()
        status = VoiceStatus.IDLE
    }

    /**
     * 功能：释放识别引擎
     * 参数：无
     * 返回：无
     *
     * 必须在页面离开时调用，否则识别服务会常驻并占用麦克风。
     */
    fun release() {
        recognizer?.destroy()
        recognizer = null
        status = VoiceStatus.IDLE
    }

    /**
     * 功能：创建并配置识别引擎
     * 参数：无
     * 返回：识别引擎实例
     */
    private fun createRecognizer(): SpeechRecognizer {
        val engine = SpeechRecognizer.createSpeechRecognizer(context)
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                status = VoiceStatus.IDLE
                message = describeError(error)
            }

            override fun onResults(results: Bundle?) {
                status = VoiceStatus.IDLE
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(onText)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(onText)
            }
        })
        recognizer = engine
        return engine
    }

    /**
     * 功能：构造识别请求
     * 参数：无
     * 返回：交给 SpeechRecognizer 的 Intent
     */
    private fun recognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    /**
     * 功能：把识别错误码翻译成可读原因
     * 参数：error SpeechRecognizer 的错误码
     * 返回：中文说明
     */
    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请再说一次"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到声音"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别需要网络"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙，请稍后再试"
        SpeechRecognizer.ERROR_CLIENT -> "识别被中断"
        else -> "语音识别失败（错误码 $error）"
    }
}

/**
 * 功能：创建并记住一个语音输入控制器
 * 参数：onText 识别出文本时的回调
 * 返回：VoiceInputController
 *
 * 离开组合时释放识别引擎；回调用 rememberUpdatedState 保鲜，
 * 避免每次重组都重建引擎。
 */
@Composable
fun rememberVoiceInputController(onText: (String) -> Unit): VoiceInputController {
    val context = LocalContext.current
    val controller = remember { VoiceInputController(context.applicationContext) }
    val latest = rememberUpdatedState(onText)
    SideEffect { controller.onText = { latest.value(it) } }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}
