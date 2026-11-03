package com.neptine.mimir.device

import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.DisplaySpec
import com.neptine.mimir.core.contract.ImageFrame

/**
 * 虚拟屏端口（设计文档 §2.2）。
 *
 * 建屏、销毁与取帧都需要 shell 级权限，实现落在 Shizuku UserService（shell uid）。
 */
interface DisplayPort {

    /**
     * 功能：创建私有虚拟屏
     * 参数：spec 建屏参数（默认 720×1600 @ 293dpi）
     * 返回：建屏句柄；displayId 无效时抛异常
     */
    suspend fun create(spec: DisplaySpec): DisplayHandle

    /**
     * 功能：销毁虚拟屏并释放取帧 surface
     * 参数：handle 建屏句柄
     * 返回：无
     */
    suspend fun destroy(handle: DisplayHandle)

    /**
     * 功能：抓取一帧副屏画面
     * 参数：handle 建屏句柄；timeoutMs 单次取帧超时（毫秒）
     * 返回：编码后的图像帧
     */
    suspend fun capture(handle: DisplayHandle, timeoutMs: Long = 300L): ImageFrame
}
