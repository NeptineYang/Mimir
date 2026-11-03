package com.neptine.mimir.observation

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.neptine.mimir.core.contract.CaptureSource
import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.ImageFormat
import com.neptine.mimir.core.contract.ImageFrame
import com.neptine.mimir.device.DisplayPort
import com.neptine.mimir.service.MimirAccessibilityService
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 截图采集（设计文档 §2.2 三级兜底 / §2.3.1 图像规格）。
 *
 * v1 实现两条路径：
 * 1. PixelCopy（shell 进程）：主路径，单帧 300ms 超时；
 * 2. 无障碍 takeScreenshot(displayId)：兜底，系统限频约 300ms/次。
 *
 * 设计文档里的第三条 screencap 兜底在虚拟屏上不成立：`screencap -d` 只接受**物理** displayId，
 * 虚拟屏没有物理 id（DisplayInfo.address 为 null），这条路径对副屏恒失败。
 * 与其留一段永远跑不通的代码，不如在探针 P3 里如实报告"可用路径数为 2"。
 *
 * 输出统一缩放到送模型规格（默认 540×1200）：副屏按 720×1600 原生渲染，
 * 模型输入独立缩放，二者解耦（§2.2.1）。
 */
class FallbackScreenshotProvider(private val displays: DisplayPort) : ScreenshotProvider {

    /**
     * 功能：抓取一帧副屏画面并缩放到送模型规格
     * 参数：handle 建屏句柄
     * 返回：图像帧与取帧来源；两条路径都失败时抛异常
     */
    override suspend fun capture(handle: DisplayHandle): CapturedFrame = withContext(Dispatchers.IO) {
        captureByPixelCopy(handle)?.let { return@withContext it }
        captureByAccessibility(handle)?.let { return@withContext it }
        throw IllegalStateException("取帧失败：PixelCopy 与无障碍 takeScreenshot 均未返回画面")
    }

    /**
     * 功能：走 PixelCopy 主路径取帧
     * 参数：handle 建屏句柄
     * 返回：取帧结果；失败返回 null
     */
    private suspend fun captureByPixelCopy(handle: DisplayHandle): CapturedFrame? = runCatching {
        val native = displays.capture(handle, CAPTURE_TIMEOUT_MS)
        CapturedFrame(scaleToModelSize(native), CaptureSource.PIXELCOPY)
    }.getOrNull()

    /**
     * 功能：走无障碍 takeScreenshot 兜底取帧
     * 参数：handle 建屏句柄
     * 返回：取帧结果；无障碍未连接或系统拒绝时返回 null
     */
    private suspend fun captureByAccessibility(handle: DisplayHandle): CapturedFrame? {
        val service = MimirAccessibilityService.instance ?: return null
        val bitmap = requestAccessibilityBitmap(service, handle.displayId) ?: return null
        return try {
            CapturedFrame(scaleToModelSize(encode(bitmap)), CaptureSource.ACCESSIBILITY)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 功能：向无障碍服务请求一帧截图并转为软件位图
     * 参数：service 无障碍服务；displayId 目标显示
     * 返回：位图；失败返回 null
     */
    private suspend fun requestAccessibilityBitmap(
        service: AccessibilityService,
        displayId: Int,
    ): Bitmap? = suspendCancellableCoroutine { continuation ->
        val directExecutor = Executor { it.run() }
        service.takeScreenshot(
            displayId,
            directExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val bitmap = runCatching {
                        val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    runCatching { result.hardwareBuffer.close() }
                    continuation.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    continuation.resume(null)
                }
            },
        )
    }

    /**
     * 功能：把原生帧缩放到送模型规格并重新编码
     * 参数：native 原生尺寸的图像帧
     * 返回：缩放后的图像帧；解码失败时原样返回
     */
    private fun scaleToModelSize(native: ImageFrame): ImageFrame {
        val source = BitmapFactory.decodeByteArray(native.bytes, 0, native.bytes.size) ?: return native
        return try {
            val scaled = Bitmap.createScaledBitmap(source, MODEL_WIDTH, MODEL_HEIGHT, true)
            val frame = encode(scaled)
            if (scaled !== source) scaled.recycle()
            frame
        } finally {
            source.recycle()
        }
    }

    /**
     * 功能：把位图编码为 WebP 图像帧
     * 参数：bitmap 位图
     * 返回：WebP 帧
     */
    private fun encode(bitmap: Bitmap): ImageFrame {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, out)
        return ImageFrame(bitmap.width, bitmap.height, ImageFormat.WEBP, out.toByteArray())
    }

    private companion object {
        /** 单次 PixelCopy 取帧超时。 */
        const val CAPTURE_TIMEOUT_MS = 300L

        /** 送模型默认宽度（§2.3.1 默认档）。 */
        const val MODEL_WIDTH = 540

        /** 送模型默认高度（与副屏 720×1600 同比例）。 */
        const val MODEL_HEIGHT = 1200

        /** WebP 质量；只影响字节数，不影响视觉 token 数。 */
        const val WEBP_QUALITY = 70
    }
}
