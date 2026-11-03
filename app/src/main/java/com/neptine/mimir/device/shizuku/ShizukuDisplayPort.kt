package com.neptine.mimir.device.shizuku

import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.DisplaySpec
import com.neptine.mimir.core.contract.ImageFormat
import com.neptine.mimir.core.contract.ImageFrame
import com.neptine.mimir.device.DisplayPort
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 虚拟屏端口的 Shizuku 实现（设计文档 §2.2 / §2.2.1）。
 *
 * 建屏与取帧都在 shell 进程完成：CAPTURE_VIDEO_OUTPUT 是 signature 级权限，
 * 只有 shell uid 持有；app 进程直接调 createVirtualDisplay 会拿到 null。
 *
 * 所有方法都切到 IO 线程：AIDL 是同步 binder 调用，留在主线程会直接触发 ANR。
 */
class ShizukuDisplayPort(private val shell: ShizukuShell) : DisplayPort {

    /** 本进程内的副屏代次；虚拟屏重建后递增，旧观察与旧授权随之失效。 */
    private val generation = AtomicLong(0)

    /**
     * 功能：创建私有虚拟屏
     * 参数：spec 建屏参数
     * 返回：建屏句柄；displayId 无效时抛异常
     */
    override suspend fun create(spec: DisplaySpec): DisplayHandle = withContext(Dispatchers.IO) {
        val service = shell.require()
        val displayId =
            service.createDisplay(NAME_PREFIX + System.currentTimeMillis(), spec.width, spec.height, spec.dpi)
        require(displayId != -1) { "createVirtualDisplay 返回无效 displayId" }
        DisplayHandle(displayId, generation.incrementAndGet(), spec)
    }

    /**
     * 功能：销毁虚拟屏
     * 参数：handle 建屏句柄
     * 返回：无
     */
    override suspend fun destroy(handle: DisplayHandle): Unit = withContext(Dispatchers.IO) {
        shell.require().destroyDisplay(handle.displayId)
    }

    /**
     * 功能：抓取一帧副屏画面（原生尺寸，WebP）
     * 参数：handle 建屏句柄；timeoutMs 取帧超时
     * 返回：图像帧；取帧失败时抛异常，由上层降级到下一级兜底
     */
    override suspend fun capture(handle: DisplayHandle, timeoutMs: Long): ImageFrame =
        withContext(Dispatchers.IO) {
            val bytes = shell.require().captureDisplay(handle.displayId, timeoutMs.toInt())
                ?: throw IllegalStateException("PixelCopy 取帧失败")
            ImageFrame(handle.spec.width, handle.spec.height, ImageFormat.WEBP, bytes)
        }

    private companion object {
        /** 屏名前缀，便于在 dumpsys display 里辨认本项目创建的屏。 */
        const val NAME_PREFIX = "mimir-"
    }
}
