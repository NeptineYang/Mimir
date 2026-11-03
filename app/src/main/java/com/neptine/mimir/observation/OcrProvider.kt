package com.neptine.mimir.observation

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.neptine.mimir.core.contract.Bounds
import com.neptine.mimir.core.contract.ImageFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 端侧 OCR（设计文档 §2.3.2）。
 *
 * 用途只有一个：**控件树被 App 封锁时，用截图里的文字补出一份元素表**，让模型仍然能按
 * "选 #N"的方式操作，而不是退化成"给个坐标赌一把"。微信就是这种 App：窗口有画面、
 * `children=0`，一行文本都读不到。
 *
 * 边界：
 * - 只识别文本与位置，不判断可点性（OCR 不知道什么是按钮），可点性靠后续动作自己验证；
 * - 结果只用于**只读导航**。纯视觉下的文本输入与凭据填充仍然 fail closed——
 *   没有控件节点就构造不出 `ACTION_SET_TEXT` 的目标。
 */
@Singleton
class OcrProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 识别器按需创建；中文模型已随 APK 打包，不依赖 Play 服务在线下载。 */
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 功能：识别一帧图里的所有文本行
     * 参数：image 送模型的截图（坐标以该图自身像素为准）
     * 返回：文本行与包围盒；解码失败或识别失败时返回空列表（调用方按"没有元素"处理）
     */
    suspend fun recognize(image: ImageFrame): List<OcrLine> = withContext(Dispatchers.Default) {
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
        }.getOrNull() ?: return@withContext emptyList()

        val text: Text? = suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { continuation.resume(null) }
        }

        text?.toLines() ?: emptyList()
    }

    /**
     * 功能：把识别结果摊平成行
     * 参数：无（扩展函数，作用于 ML Kit 的 Text）
     * 返回：非空文本行
     */
    private fun Text.toLines(): List<OcrLine> =
        textBlocks.flatMap { block -> block.lines }
            .mapNotNull { line ->
                val box: Rect = line.boundingBox ?: return@mapNotNull null
                if (line.text.isBlank()) return@mapNotNull null
                OcrLine(
                    text = line.text.trim(),
                    bounds = Bounds(box.left, box.top, box.right, box.bottom),
                )
            }
}

/**
 * 一行 OCR 结果。
 *
 * @property text 识别出的文本（已去首尾空白）
 * @property bounds 包围盒，坐标系与送识别的截图一致
 */
data class OcrLine(val text: String, val bounds: Bounds)
