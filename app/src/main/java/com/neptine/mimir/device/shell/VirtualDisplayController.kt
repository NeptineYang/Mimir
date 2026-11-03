package com.neptine.mimir.device.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 虚拟屏的创建、销毁与取帧（设计文档 §2.2 / §2.2.1）。
 *
 * 取帧主路径是 PixelCopy：它在 Surface 上直接抓一帧，不走 screencap 的 fork + PNG 解码。
 * 每块屏自带一个 ImageReader 作为输出面；PixelCopy 从该 Surface 消费缓冲区，
 * 因此不能再用 acquireLatestImage 抢同一批缓冲区。
 */
internal class VirtualDisplayController(context: Context) {

    /**
     * 自己构造 DisplayManager，而不是取 getSystemService 的缓存单例。
     *
     * SystemServiceRegistry 的 DisplayManager 是本进程首次请求时创建的单例，绑定的是那个
     * Context；ActivityThread.systemMain() 优先触发时，它绑定的就是包名为 android 的系统上下文，
     * 于是 createVirtualDisplay 会报 "packageName must match the owner uid"。
     * 构造器是 @hide，但 shell 进程可反射。
     */
    private val displayManager: DisplayManager = runCatching {
        DisplayManager::class.java.getConstructor(Context::class.java).newInstance(context)
    }.getOrElse { context.getSystemService(DisplayManager::class.java) }

    /** 按 displayId 索引的建屏会话。 */
    private val sessions = ConcurrentHashMap<Int, Session>()

    /** PixelCopy 的回调需要一个 Looper，独立线程避免占用 binder 线程池。 */
    private val copyThread = HandlerThread("mimir-pixelcopy").apply { start() }
    private val copyHandler = Handler(copyThread.looper)

    /**
     * 功能：创建一个私有虚拟屏
     * 参数：name 屏名；width/height 原生像素；dpi 密度
     * 返回：新建屏的 displayId
     */
    fun create(name: String, width: Int, height: Int, dpi: Int): Int {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val display = displayManager.createVirtualDisplay(
            name, width, height, dpi, reader.surface, CREATE_FLAGS,
        )
            ?: throw IllegalStateException("createVirtualDisplay 返回 null")
        val displayId = display.display.displayId
        if (displayId == -1) {
            reader.close()
            throw IllegalStateException("createVirtualDisplay 返回无效 displayId")
        }
        sessions[displayId] = Session(display, reader, width, height)
        return displayId
    }

    /**
     * 功能：销毁虚拟屏并释放取帧面
     * 参数：displayId 目标屏
     * 返回：无
     */
    fun destroy(displayId: Int) {
        sessions.remove(displayId)?.release()
    }

    /**
     * 功能：抓取一帧并编码为 WebP
     * 参数：displayId 目标屏；timeoutMs 取帧超时（毫秒）
     * 返回：WebP 字节；屏不存在、超时或 PixelCopy 失败时返回 null
     */
    fun capture(displayId: Int, timeoutMs: Int): ByteArray? {
        val session = sessions[displayId] ?: return null
        val bitmap = Bitmap.createBitmap(session.width, session.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var copyResult = PixelCopy.ERROR_UNKNOWN

        PixelCopy.request(
            session.reader.surface,
            Rect(0, 0, session.width, session.height),
            bitmap,
            { result ->
                copyResult = result
                latch.countDown()
            },
            copyHandler,
        )

        val finished = latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!finished || copyResult != PixelCopy.SUCCESS) {
            bitmap.recycle()
            return null
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** 单块屏的持有物；释放顺序必须是"先放屏，再关 reader"。 */
    private class Session(
        val display: VirtualDisplay,
        val reader: ImageReader,
        val width: Int,
        val height: Int,
    ) {
        fun release() {
            runCatching { display.release() }
            runCatching { reader.close() }
        }
    }

    private companion object {
        /**
         * 建屏 flags。
         *
         * 实测（AVD API 37）：flags=0 的私有屏不在无障碍收录范围内，
         * getWindowsOnAllDisplays() 只能看到 display [0]，观测层会退化为纯视觉。
         * VIRTUAL_DISPLAY_FLAG_PUBLIC 是当前验证中的对照项，见探针 P4。
         *
         * 必须带 DESTROY_CONTENT_ON_REMOVAL（@hide，值 1<<8）：公开虚拟屏默认在销毁时
         * 把内容**搬到主屏**，真机上表现为任务结束后目标 App 突然出现在用户主屏，
         * 下一个任务再被主屏冲突检测拦下（2026-11-02 实测微信）。
         *
         * TRUSTED（@hide）用于让副屏成为可信显示：shell 持有 ADD_TRUSTED_DISPLAY，
         * 允许它在独立显示组之外承载 task。实测 OWN_DISPLAY_GROUP 会让
         * `am start --display N` 完全失效（任务起不来），所以不能加。
         */
        val CREATE_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            FLAG_DESTROY_CONTENT_ON_REMOVAL or
            FLAG_TRUSTED

        /** VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL（@hide，公开 SDK 里没有符号）。 */
        const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8

        /** VIRTUAL_DISPLAY_FLAG_TRUSTED（@hide；shell 持有 ADD_TRUSTED_DISPLAY 权限）。 */
        const val FLAG_TRUSTED = 1 shl 10

        /** WebP 质量；只影响字节数与传输延迟，不改变送模型的视觉 token 数（§2.3.1）。 */
        const val WEBP_QUALITY = 70
    }
}
