package com.neptine.mimir.device.shizuku

import android.os.SystemClock
import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.DisplaySpec
import com.neptine.mimir.core.contract.Point
import com.neptine.mimir.core.contract.ProbeId
import com.neptine.mimir.core.contract.ProbeResult
import com.neptine.mimir.device.CapabilityProbe
import com.neptine.mimir.device.DeviceTransport
import com.neptine.mimir.observation.FrameFingerprint
import com.neptine.mimir.observation.ScreenshotProvider
import com.neptine.mimir.observation.TreeProvider
import com.neptine.mimir.service.MimirAccessibilityService
import kotlinx.coroutines.delay

/**
 * 启动期能力探针 P1–P5（设计文档 §2.2.2）。
 *
 * 目的不是"检测失败就崩"，而是把机型差异变成可展示的降级项：
 * 用户在配置页能看到这台机器哪一项不支持，而不是遇到一个静默失败的任务。
 *
 * 每条探针都自带 try/catch，单条失败不影响其余条目——探针的价值在于"逐项说清原因"。
 */
class ShizukuCapabilityProbe(
    private val transport: DeviceTransport,
    private val trees: TreeProvider,
    private val screenshots: ScreenshotProvider,
) : CapabilityProbe {

    /**
     * 功能：依次执行 P1–P5
     * 参数：无
     * 返回：每项探针结果，按 ProbeId 声明顺序返回
     *
     * 执行顺序与展示顺序不同，是为了让互不干扰的项排在前面：
     * P2 会向副屏中心注入一次点击，真实 App 常常因此跳到子页面（设置首页中心是
     * "移动网络"，它属于 com.android.phone，跨包新建 task 会落回主屏），副屏随即变空。
     * 真机 V1 就是这样把 P4 打成"副屏上没有可见窗口"的——P4 只取树、不改界面，
     * 必须排在注入之前。
     */
    override suspend fun runAll(): List<ProbeResult> {
        if (!transport.isAvailable()) {
            val reason = "Shizuku 未就绪：需要先启动 Shizuku 服务并在本应用内授权"
            return ProbeId.entries.map { ProbeResult(it, false, reason) }
        }

        val handle = runCatching { transport.displays.create(DisplaySpec()) }.getOrElse { error ->
            // 这里既可能是建屏失败，也可能是 Shizuku UserService 没起来；
            // 统一叫"副屏不可用"，具体原因由异常消息给出（10s 超时曾被误报成建屏失败）。
            val reason = "副屏不可用：${error.message}"
            return ProbeId.entries.map { ProbeResult(it, false, reason) }
        }

        return try {
            listOf(
                probeVirtualDisplay(handle),
                probeAccessibilityVisibility(handle),
                probeTargetedCapture(handle),
                probeTargetedInject(handle),
                probeZeroInterferenceInput(),
            ).sortedBy { it.probeId.ordinal }
        } finally {
            runCatching { transport.displays.destroy(handle) }
        }
    }

    /**
     * 功能：P1 虚拟屏——建屏后目标 App 是否真的渲染在这块屏上
     * 参数：handle 建屏句柄
     * 返回：探针结果
     *
     * 测试目标刻意用系统设置而不是本应用：把本应用拉到自己建的副屏上，会连带把正在
     * 显示设置页的任务搬到副屏，探针结束时副屏销毁 → Activity 被销毁 → 探针协程被取消。
     *
     * 判据不能只看亮度：PUBLIC 虚拟屏没有应用窗口时会显示壁纸，平均亮度可能高达 240+，
     * 于是"App 根本没起来、或落到了主屏"会被判成通过，后面 P4 再报"屏上没有可见窗口"，
     * 把真实原因掩盖掉。因此必须在取帧之后确认屏上前台应用就是刚拉起的那个包。
     */
    private suspend fun probeVirtualDisplay(handle: DisplayHandle): ProbeResult {
        val launchedPackage = TEST_LAUNCH_PACKAGES.firstOrNull { packageName ->
            runCatching { transport.activities.launch(packageName, handle) }.isSuccess
        } ?: return ProbeResult(ProbeId.P1_VIRTUAL_DISPLAY, false, "找不到可用于测试的启动包")

        // 等首帧渲染；不做"连续两次安静"判定，探针只需要证明目标 App 出现在这块屏上。
        delay(RENDER_SETTLE_MS)

        val frame = runCatching { transport.displays.capture(handle, CAPTURE_TIMEOUT_MS) }
            .getOrElse { return ProbeResult(ProbeId.P1_VIRTUAL_DISPLAY, false, "取帧异常：${it.message}") }

        val luma = FrameFingerprint.averageLuma(frame)
        if (luma <= BLACK_LUMA_THRESHOLD) {
            return ProbeResult(
                ProbeId.P1_VIRTUAL_DISPLAY,
                false,
                "建屏成功但取到全黑帧（平均亮度 ${"%.1f".format(luma)}）",
            )
        }
        return placementVerdict(handle, launchedPackage, luma)
    }

    /**
     * 功能：按"屏上前台应用"给 P1 定论
     * 参数：handle 建屏句柄；launchedPackage 刚拉起的包名；luma 平均亮度
     * 返回：探针结果
     *
     * 无障碍未连接时读不到屏上包名，此时只按亮度判并通过，并在 detail 里写明"未校验包名"，
     * 不把 a11y 的问题伪装成 P1 通过。
     */
    private suspend fun placementVerdict(
        handle: DisplayHandle,
        launchedPackage: String,
        luma: Double,
    ): ProbeResult {
        val onDisplay = runCatching { transport.activities.foregroundOn(handle) }.getOrNull()?.packageName
        val brightness = "平均亮度 ${"%.1f".format(luma)}"
        return when {
            onDisplay == launchedPackage -> ProbeResult(
                ProbeId.P1_VIRTUAL_DISPLAY,
                true,
                "displayId=${handle.displayId}，屏上应用 $onDisplay，$brightness",
            )

            onDisplay == null && MimirAccessibilityService.instance == null -> ProbeResult(
                ProbeId.P1_VIRTUAL_DISPLAY,
                true,
                "displayId=${handle.displayId}，$launchedPackage $brightness；无障碍未连接，未校验屏上包名",
            )

            else -> ProbeResult(
                ProbeId.P1_VIRTUAL_DISPLAY,
                false,
                "displayId=${handle.displayId} 上看到的是 ${onDisplay ?: "壁纸（没有应用窗口）"}，" +
                    "不是目标 $launchedPackage：App 可能被拉到了主屏，或系统弹窗盖在上面",
            )
        }
    }

    /**
     * 功能：P2 定向注入——事件是否被系统接受
     * 参数：handle 建屏句柄
     * 返回：探针结果
     *
     * 尚未验证的一项如实写进 detail：主屏"无任何响应"需要读取主屏前台状态才能判定，
     * 而本项目在数据入口就过滤主屏，探针不越这条线；该项由 P5 的人工流程覆盖。
     */
    private suspend fun probeTargetedInject(handle: DisplayHandle): ProbeResult {
        val center = Point(handle.spec.width / 2, handle.spec.height / 2)
        val accepted = runCatching { transport.inputs.tap(handle, center, PRESS_MS) }
            .getOrElse { return ProbeResult(ProbeId.P2_TARGETED_INJECT, false, "注入异常：${it.message}") }

        return if (accepted) {
            ProbeResult(
                ProbeId.P2_TARGETED_INJECT,
                true,
                "注入返回 true；未校验主屏无响应（探针不读取主屏数据，由 P5 人工流程覆盖）",
            )
        } else {
            ProbeResult(ProbeId.P2_TARGETED_INJECT, false, "系统拒绝注入：反射 setDisplayId 或 INJECT_EVENTS 权限不可用")
        }
    }

    /**
     * 功能：P3 定向取帧——哪条兜底路径能在超时内拿到非黑帧
     * 参数：handle 建屏句柄
     * 返回：探针结果
     */
    private suspend fun probeTargetedCapture(handle: DisplayHandle): ProbeResult {
        val start = SystemClock.elapsedRealtime()
        val captured = runCatching { screenshots.capture(handle) }
            .getOrElse { return ProbeResult(ProbeId.P3_TARGETED_CAPTURE, false, "两条取帧路径均失败：${it.message}") }
        val elapsed = SystemClock.elapsedRealtime() - start

        val luma = FrameFingerprint.averageLuma(captured.image)
        return if (luma > BLACK_LUMA_THRESHOLD) {
            ProbeResult(ProbeId.P3_TARGETED_CAPTURE, true, "来源 ${captured.source}，耗时 ${elapsed}ms")
        } else {
            ProbeResult(ProbeId.P3_TARGETED_CAPTURE, false, "来源 ${captured.source} 但取到全黑帧，耗时 ${elapsed}ms")
        }
    }

    /**
     * 功能：P4 无障碍可见性——能否枚举到该副屏的窗口
     * 参数：handle 建屏句柄
     * 返回：探针结果
     */
    private suspend fun probeAccessibilityVisibility(handle: DisplayHandle): ProbeResult {
        val frame = runCatching { trees.capture(handle) }.getOrElse {
            val visible = MimirAccessibilityService.instance?.visibleDisplayIds()
            val onDisplay = runCatching { transport.activities.foregroundOn(handle) }.getOrNull()?.packageName
            return ProbeResult(
                ProbeId.P4_ACCESSIBILITY_VISIBILITY,
                false,
                "取树失败：${it.message}；无障碍当前仅可见 display $visible；" +
                    "屏上应用 ${onDisplay ?: "无（App 可能跳到了主屏，或副屏已空）"}",
            )
        }
        return if (frame.elements.isNotEmpty()) {
            ProbeResult(
                ProbeId.P4_ACCESSIBILITY_VISIBILITY,
                true,
                "枚举到 ${frame.elements.size} 个元素（${frame.packageName}）；主屏窗口在数据入口按 displayId 过滤",
            )
        } else {
            ProbeResult(ProbeId.P4_ACCESSIBILITY_VISIBILITY, false, "窗口可枚举但没有任何可交互元素，观测退化为纯视觉")
        }
    }

    /**
     * 功能：P5 零干扰文本输入
     * 参数：无
     * 返回：探针结果
     *
     * 这一项无法自动判定：它要求用户在主屏持续真实打字，同时观察副屏输入是否影响
     * 主屏键盘、候选、焦点与剪贴板。在人工实测通过前一律按"未通过"处理，
     * 上层据此禁止自动文本输入（§4.7 第 1 条：一票否决项）。
     */
    private fun probeZeroInterferenceInput(): ProbeResult = ProbeResult(
        ProbeId.P5_ZERO_INTERFERENCE_TEXT,
        false,
        "未验证：需要主屏持续打字的人工配合。通过前禁止在真实任务中自动输入文本",
    )

    private companion object {
        /** 探针的测试启动目标；系统设置在所有机型上都有启动入口。 */
        val TEST_LAUNCH_PACKAGES = listOf("com.android.settings")

        /** 建屏后等首帧渲染的时间。 */
        const val RENDER_SETTLE_MS = 1500L

        /** 单次取帧超时。 */
        const val CAPTURE_TIMEOUT_MS = 300L

        /** 探针点击的按压时长。 */
        const val PRESS_MS = 60L

        /** 平均亮度低于该值视为全黑帧。 */
        const val BLACK_LUMA_THRESHOLD = 6.0
    }
}
