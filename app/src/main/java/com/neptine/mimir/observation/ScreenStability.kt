package com.neptine.mimir.observation

import android.os.SystemClock
import com.neptine.mimir.core.contract.DisplayHandle
import kotlinx.coroutines.delay

/**
 * 界面安静判定（设计文档 §1.3 第 2 条）。
 *
 * 每 120ms 轮询一次布局哈希与画面指纹，连续两次都相同才算安静。
 * 关键约束：**安静只表示"可以读取下一帧"，不表示加载完成，更不表示上一步已生效**；
 * 超时返回 false，由调用方转成 OUTCOME_UNKNOWN，而不是当作成功。
 *
 * 轮询期间最后一次成功读到的树一并返回：新页面创建时窗口根节点会短暂不可读，
 * 这棵树往往是动作后唯一还能拿到的证据，丢掉它就只能判"结果未知"。
 */
class ScreenStability(
    private val trees: TreeProvider,
    private val screenshots: ScreenshotProvider,
) {

    /**
     * 功能：等待副屏界面进入安静状态
     * 参数：handle 建屏句柄；timeoutMs 超时（毫秒）
     * 返回：是否安静，以及轮询期间最后一次成功读到的树
     */
    suspend fun awaitQuiet(handle: DisplayHandle, timeoutMs: Long = DEFAULT_TIMEOUT_MS): StabilityResult {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var previousHash: String? = null
        var previousPrint: IntArray? = null
        var lastTree: TreeFrame? = null

        while (SystemClock.elapsedRealtime() < deadline) {
            val tree = runCatching { trees.capture(handle) }.getOrNull()
            if (tree != null) lastTree = tree
            val framePrint = runCatching { FrameFingerprint.of(screenshots.capture(handle).image) }.getOrNull()

            // 用布局哈希判"结构是否停住"：倒计时、轮播页码这类纯文本变化不代表页面还在切换，
            // 拿内容哈希判会让这类页面永远不安静，每步白等满超时。
            val stable = tree != null && framePrint != null && tree.layoutHash == previousHash &&
                previousPrint != null && FrameFingerprint.meanDiff(framePrint, previousPrint) <= PIXEL_THRESHOLD
            if (stable) return StabilityResult(quiet = true, lastTree = lastTree)

            previousHash = tree?.layoutHash
            previousPrint = framePrint
            delay(POLL_INTERVAL_MS)
        }
        return StabilityResult(quiet = false, lastTree = lastTree)
    }

    private companion object {
        /** 轮询间隔。 */
        const val POLL_INTERVAL_MS = 120L

        /** 默认超时；超时即 OUTCOME_UNKNOWN，不是成功。 */
        const val DEFAULT_TIMEOUT_MS = 3000L

        /** 平均灰度差阈值；低于它认为画面没动。 */
        const val PIXEL_THRESHOLD = 3.0
    }
}

/**
 * 安静判定结果。
 *
 * @property quiet 是否在超时前观察到安静
 * @property lastTree 轮询期间最后一次成功读到的树；一次都没读到为 null
 */
data class StabilityResult(
    val quiet: Boolean,
    val lastTree: TreeFrame?,
)
