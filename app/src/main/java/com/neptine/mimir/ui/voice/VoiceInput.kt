package com.neptine.mimir.ui.voice

import com.neptine.mimir.ui.i18n.tStatic
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult
import com.k2fsa.sherpa.onnx.OnlineStream

/** 语音输入的状态。 */
enum class VoiceStatus {
    /** 空闲。 */
    IDLE,

    /** 首次按下时正在加载端侧模型。 */
    PREPARING,

    /** 正在收音。 */
    LISTENING,

    /** 已松开，正在把尾音解完。 */
    RECOGNIZING,

    /** 端侧模型不可用（assets 缺失或原生库加载失败）。 */
    UNAVAILABLE,

    /** 没有麦克风权限。 */
    DENIED,
}

/**
 * 按住说话的控制器。
 *
 * 录音用 AudioRecord（16 kHz 单声道），识别用 [OfflineAsrEngine] 在端侧流式完成：
 * 音频既不落盘也不出设备。页面只依赖 [status] / [message] / [start] / [stop]。
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

    /** 识别最终结果；由 [rememberVoiceInputController] 绑定。 */
    internal var onText: (String) -> Unit = {}

    /** 识别中间结果，用于边说边回显。 */
    internal var onPartial: (String) -> Unit = {}

    private val main = Handler(Looper.getMainLooper())

    /** 为 false 时收音循环退出；跨线程读写。 */
    @Volatile
    private var capturing = false

    /** 本次按压过短，结果整段丢弃。 */
    @Volatile
    private var discard = false

    private var startedAt = 0L
    private var worker: Thread? = null

    /** 预热线程是否在跑，避免页面反复进出时重复起线程。 */
    @Volatile
    private var warming = false

    /** 热词缓存：只在装了/卸了 App 之后才需要重算。 */
    @Volatile
    private var hotwordsCache: String? = null

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
     *
     * 模型首次加载要数秒，因此不在这里阻塞：状态先进 PREPARING，
     * 等录音真正开始后由工作线程改成 LISTENING。
     */
    fun start() {
        // 上一轮还在收尾解码时不开新的：两路 AudioRecord 会互相抢麦
        if (capturing || worker?.isAlive == true) return
        message = null
        if (!hasPermission()) {
            status = VoiceStatus.DENIED
            message = "需要麦克风权限才能语音输入".tStatic()
            return
        }
        discard = false
        startedAt = SystemClock.elapsedRealtime()
        capturing = true
        status = if (OfflineAsrEngine.isLoaded()) VoiceStatus.LISTENING else VoiceStatus.PREPARING
        worker = Thread({ capture() }, "mimir-voice").also { it.start() }
    }

    /**
     * 功能：结束收音（松开时调用）
     * 参数：无
     * 返回：无
     *
     * 只置位让收音线程收尾，不在这里等它——等会把主线程卡住。
     * 按得太短（< [MIN_PRESS_MS]）视为误触，整段结果丢弃。
     */
    fun stop() {
        if (!capturing) return
        if (SystemClock.elapsedRealtime() - startedAt < MIN_PRESS_MS) {
            discard = true
            post { message = "按住说话，松开结束".tStatic() }
        } else if (!discard) {
            post { status = VoiceStatus.RECOGNIZING }
        }
        capturing = false
    }

    /**
     * 功能：停止收音并释放线程
     * 参数：无
     * 返回：无
     *
     * 不释放已加载的模型：模型常驻进程，下次按压省掉数秒加载。
     */
    fun release() {
        discard = true
        capturing = false
        post { status = VoiceStatus.IDLE }
    }

    /**
     * 功能：提前加载模型与热词（页面出现时调用）
     * 参数：无
     * 返回：无
     *
     * 首次加载模型约 4 秒，热词枚举要查一次 PackageManager（几十毫秒）。
     * 两者都放在空闲时做掉，"按下 → 出字"就只剩录音与解码的开销。
     */
    fun warmUp() {
        // 没授权就不预热：为不用语音的人常驻 70 MB 模型没有道理
        if (!hasPermission() || OfflineAsrEngine.isLoaded() || warming) return
        warming = true
        Thread({
            OfflineAsrEngine.ensureLoaded(context)
            hotwords()
            warming = false
        }, "mimir-voice-warmup").start()
    }

    /**
     * 功能：收音线程主体：加载模型、开录音、边录边解码、收尾出结果
     * 参数：无
     * 返回：无
     */
    private fun capture() {
        // 冷启动只能先等模型；热启动（页面出现时已预热）这里直接返回
        if (!OfflineAsrEngine.isLoaded() && OfflineAsrEngine.ensureLoaded(context) == null) {
            post {
                status = VoiceStatus.UNAVAILABLE
                message = "语音模型加载失败：%1\$s".tStatic(OfflineAsrEngine.lastError ?: "未知原因".tStatic())
            }
            capturing = false
            return
        }
        // 先把麦克风开起来再建识别流：录音晚 100 ms，开口第一个字就没了
        val record = openRecorder()
        if (record == null) {
            post {
                status = VoiceStatus.IDLE
                message = "无法打开麦克风".tStatic()
            }
            capturing = false
            return
        }
        record.startRecording()
        post { if (capturing) status = VoiceStatus.LISTENING }
        // 等待模型期间用户可能已经松手：说明清楚，不装作"没听清"
        if (!capturing) {
            runCatching { record.stop() }
            record.release()
            post {
                status = VoiceStatus.IDLE
                message = "模型已加载，请再按一次".tStatic()
            }
            return
        }
        val engine = OfflineAsrEngine.ensureLoaded(context)
        val stream = engine?.createStream(hotwords())
        if (engine == null || stream == null) {
            runCatching { record.stop() }
            record.release()
            post {
                status = VoiceStatus.UNAVAILABLE
                message = "语音模型不可用".tStatic()
            }
            capturing = false
            return
        }
        try {
            pump(engine, record, stream)
            deliver(finish(engine, stream))
        } catch (t: Throwable) {
            post { message = "录音失败：%1\$s".tStatic(t.message ?: t.javaClass.simpleName) }
        } finally {
            runCatching { record.stop() }
            record.release()
            stream.release()
            capturing = false
            discard = false
            post { status = VoiceStatus.IDLE }
        }
    }

    /**
     * 功能：取热词串（首次枚举已装 App，之后复用）
     * 参数：无
     * 返回：每行一个词的热词表
     */
    private fun hotwords(): String =
        hotwordsCache ?: buildVoiceHotwords(context).also { hotwordsCache = it }

    /**
     * 功能：把麦克风数据持续喂给识别流并回显中间结果
     * 参数：engine 识别引擎；record 已开始录音的采集器；stream 本次识别流
     * 返回：无
     */
    private fun pump(engine: OnlineRecognizer, record: AudioRecord, stream: OnlineStream) {
        val buffer = ShortArray(CHUNK_SAMPLES)
        var last = ""
        while (capturing && SystemClock.elapsedRealtime() - startedAt < MAX_PRESS_MS) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            stream.acceptWaveform(toFloat(buffer, read), OfflineAsrEngine.SAMPLE_RATE)
            while (engine.isReady(stream)) engine.decode(stream)
            val text = engine.getResult(stream).text.trim()
            if (text.isNotEmpty() && text != last) {
                last = text
                post { onPartial(text) }
            }
        }
        if (SystemClock.elapsedRealtime() - startedAt >= MAX_PRESS_MS) {
            post { message = "已到 %1\$d 秒上限，自动结束".tStatic(MAX_PRESS_MS / 1000) }
        }
        // 松手时音频缓冲里往往还剩最后几十毫秒：不喂进去，句尾那个字就被吞掉
        drain(engine, record, stream)
    }

    /**
     * 功能：把采集器缓冲里剩余的音频喂给识别流
     * 参数：engine 识别引擎；record 采集器；stream 本次识别流
     * 返回：无
     *
     * AudioRecord 没有"还有多少可读"的接口，只能非阻塞读到 0 为止。
     */
    private fun drain(engine: OnlineRecognizer, record: AudioRecord, stream: OnlineStream) {
        val buffer = ShortArray(CHUNK_SAMPLES)
        while (true) {
            val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
            if (read <= 0) break
            stream.acceptWaveform(toFloat(buffer, read), OfflineAsrEngine.SAMPLE_RATE)
        }
        while (engine.isReady(stream)) engine.decode(stream)
    }

    /**
     * 功能：补一段静音把尾部音素冲出来，再取最终文本
     * 参数：engine 识别引擎；stream 本次识别流
     * 返回：带标点的最终文本（可能为空）
     */
    private fun finish(engine: OnlineRecognizer, stream: OnlineStream): String {
        stream.acceptWaveform(FloatArray(TAIL_SILENCE_SAMPLES), OfflineAsrEngine.SAMPLE_RATE)
        stream.inputFinished()
        while (engine.isReady(stream)) engine.decode(stream)
        return punctuate(engine.getResult(stream))
    }

    /**
     * 功能：给识别结果补标点
     * 参数：result 识别结果（含 tokens 与逐 token 时间戳）
     * 返回：带标点的文本；原文为空时返回空串
     *
     * 这个流式 transducer 不输出标点。停顿是最可靠的切分信号：相邻 token 的
     * 时间戳间隔超过 [PAUSE_SECONDS] 就补一个逗号，结尾补句号。
     * 重建结果必须与原文逐字一致才采用——英文/子词模型重建会走样，那时退回原文加句号。
     */
    private fun punctuate(result: OnlineRecognizerResult): String {
        val text = result.text.trim()
        if (text.isEmpty()) return ""
        val rebuilt = rebuildWithCommas(result)
        val body = if (rebuilt == text) rebuilt else text
        return if (body.last() in "。！？，") body else "$body。"
    }

    /**
     * 功能：按 token 时间戳在停顿处插入逗号
     * 参数：result 识别结果
     * 返回：重建后的文本；token 与时间戳对不上时返回原文
     */
    private fun rebuildWithCommas(result: OnlineRecognizerResult): String {
        val tokens = result.tokens
        val stamps = result.timestamps
        if (tokens.isEmpty() || stamps.size != tokens.size) return result.text.trim()
        val builder = StringBuilder()
        var lastEnd = Float.NaN
        for (i in tokens.indices) {
            val piece = tokens[i]
            if (piece.isBlank()) continue
            val stamp = stamps[i]
            if (!lastEnd.isNaN() && stamp - lastEnd >= PAUSE_SECONDS) builder.append('，')
            builder.append(piece)
            lastEnd = stamp
        }
        return builder.toString()
    }

    /**
     * 功能：把最终结果交给页面
     * 参数：text 最终文本
     * 返回：无
     */
    private fun deliver(text: String) {
        if (discard) return
        if (text.isEmpty()) {
            post { message = "没有听清，请再说一次".tStatic() }
            return
        }
        post { onText(text) }
    }

    /**
     * 功能：打开 16 kHz 单声道采集器
     * 参数：无
     * 返回：已初始化的 AudioRecord；不可用时为 null
     *
     * 权限在 [start] 里已经查过，这里不再重复，避免在收音线程上多一次跨进程调用。
     */
    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(
            OfflineAsrEngine.SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return null
        // VOICE_RECOGNITION 源不做 AGC 与降噪，读数更接近模型训练时的分布
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            OfflineAsrEngine.SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            min * 4,
        )
        if (record.state == AudioRecord.STATE_INITIALIZED) return record
        record.release()
        return null
    }

    /**
     * 功能：把 16 位整型采样转成模型要的 [-1, 1] 浮点
     * 参数：buffer 采样缓冲；count 有效长度
     * 返回：长度为 count 的浮点数组
     */
    private fun toFloat(buffer: ShortArray, count: Int): FloatArray {
        val out = FloatArray(count)
        for (i in 0 until count) out[i] = buffer[i] / 32768f
        return out
    }

    /**
     * 功能：把状态更新抛回主线程
     * 参数：block 要执行的动作
     * 返回：无
     */
    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private companion object {
        /** 单次读数 100 ms，兼顾延迟与调用次数。 */
        const val CHUNK_SAMPLES = 1600

        /** 收尾补 300 ms 静音。 */
        const val TAIL_SILENCE_SAMPLES = 4800

        /** 单次按压上限，避免忘记松手一直录。 */
        const val MAX_PRESS_MS = 60_000L

        /** 低于此时长视为误触。 */
        const val MIN_PRESS_MS = 300L

        /** 相邻 token 间隔超过这个秒数就认为说话人停顿过一次。 */
        const val PAUSE_SECONDS = 0.45f
    }
}

/**
 * 功能：创建并记住一个语音输入控制器
 * 参数：onText 识别出最终文本时的回调；onPartial 中间结果回调
 * 返回：VoiceInputController
 *
 * 离开组合时停止收音；回调用 rememberUpdatedState 保鲜，
 * 避免每次重组都重建控制器。
 */
@Composable
fun rememberVoiceInputController(
    onText: (String) -> Unit,
    onPartial: (String) -> Unit = {},
): VoiceInputController {
    val context = LocalContext.current
    val controller = remember { VoiceInputController(context.applicationContext) }
    val latestText = rememberUpdatedState(onText)
    val latestPartial = rememberUpdatedState(onPartial)
    SideEffect {
        controller.onText = { latestText.value(it) }
        controller.onPartial = { latestPartial.value(it) }
    }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}
