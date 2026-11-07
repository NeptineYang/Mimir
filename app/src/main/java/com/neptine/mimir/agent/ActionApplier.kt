package com.neptine.mimir.agent

import android.view.KeyEvent
import com.neptine.mimir.core.contract.ActionKind
import com.neptine.mimir.core.contract.AgentAction
import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.ObservationMode
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.Point
import com.neptine.mimir.device.DeviceTransport
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * 把动作落到设备上（设计文档 §1.2 执行层 / §4.5 轻量拟人化）。
 *
 * 只负责"发出去"：返回值只代表系统接受事件，不代表目标 App 收到或动作生效。
 * 生效判定由后置条件与 Verifier 负责，二者不能混。
 */
class ActionApplier @Inject constructor(
    private val transport: DeviceTransport,
    private val textInput: TextInput,
    private val credentials: CredentialBroker,
) {

    /**
     * 功能：执行一个界面动作
     * 参数：handle 建屏句柄；action 动作（元素编号已在决策阶段映射为指纹）；
     *       snapshot 决策依据的观察；凭据动作必须传入，其它动作可以为空
     * 返回：系统是否接受事件；WAIT 恒为 true
     */
    suspend fun apply(
        handle: DisplayHandle,
        action: AgentAction.Do,
        snapshot: ObservationSnapshot? = null,
    ): Boolean = when (action.kind) {
        ActionKind.TAP -> tap(handle, action)
        ActionKind.SWIPE -> transport.inputs.swipe(handle, action.swipePath)
        ActionKind.BACK -> transport.inputs.key(handle, KeyEvent.KEYCODE_BACK)
        ActionKind.HOME -> transport.inputs.key(handle, KeyEvent.KEYCODE_HOME)
        ActionKind.LAUNCH -> {
            runCatching { transport.activities.launch(action.text.orEmpty(), handle) }
                .getOrDefault(false)
            true
        }
        ActionKind.WAIT -> {
            delay(WAIT_MS)
            true
        }
        ActionKind.TEXT -> textInput.type(
            handle,
            action,
            visionOnly = snapshot?.mode == ObservationMode.VISION_ONLY,
        )
        // 没有观察就无法复验包名与目标框；宁可不填，也不拿旧凭据盲填
        ActionKind.CREDENTIAL -> snapshot?.let { credentials.fill(handle, action, it) } ?: false
    }

    /**
     * 功能：点击动作的目标中心点
     * 参数：handle 建屏句柄；action 点击动作
     * 返回：系统是否接受事件
     *
     * 坐标加 ±3px 抖动、按压时长 60–140ms 随机：机械化的固定节奏会触发风控（§4.5），
     * 这里只做最小随机化，不做设备指纹伪装。
     */
    private suspend fun tap(handle: DisplayHandle, action: AgentAction.Do): Boolean {
        // 纯视觉坐标点击：目标没有指纹（空输入框这类没有文字的控件）。
        action.targetFingerprint?.bounds ?: run {
            val nx = action.normX ?: return false
            val ny = action.normY ?: return false
            val x = (nx * handle.spec.width / NORM_SCALE).coerceIn(0, handle.spec.width - 1)
            val y = (ny * handle.spec.height / NORM_SCALE).coerceIn(0, handle.spec.height - 1)
            android.util.Log.d(TAG, "TAP 坐标 norm=($nx,$ny) 原生=($x,$y)")
            val pressMs = Random.nextLong(MIN_PRESS_MS, MAX_PRESS_MS)
            return transport.inputs.tap(handle, Point(x, y), pressMs)
        }
        val bounds = action.targetFingerprint.bounds
        val center = bounds.center
        val jittered = Point(
            x = (center.x + Random.nextInt(-JITTER_PX, JITTER_PX + 1)).coerceIn(bounds.left, bounds.right),
            y = (center.y + Random.nextInt(-JITTER_PX, JITTER_PX + 1)).coerceIn(bounds.top, bounds.bottom),
        )
        android.util.Log.d(
            TAG,
            "TAP 元素 bounds=(${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}) " +
                "落点=(${jittered.x},${jittered.y})",
        )
        val pressMs = Random.nextLong(MIN_PRESS_MS, MAX_PRESS_MS)
        return transport.inputs.tap(handle, jittered, pressMs)
    }

    private companion object {
        /** 点击诊断日志标签；与 MimirOcr 的 bounds 对照可确认落点是否命中。 */
        const val TAG = "MimirTap"

        /** 归一化坐标的满量程（0–1000）。 */
        const val NORM_SCALE = 1000

        /** 坐标抖动幅度（像素）。 */
        const val JITTER_PX = 3

        /** 按压时长下限（毫秒）。 */
        const val MIN_PRESS_MS = 60L

        /** 按压时长上限（毫秒）。 */
        const val MAX_PRESS_MS = 140L

        /** WAIT 动作的固定等待（毫秒）。 */
        const val WAIT_MS = 1500L
    }
}
