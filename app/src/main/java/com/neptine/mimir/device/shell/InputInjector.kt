package com.neptine.mimir.device.shell

import android.content.Context
import android.hardware.input.IInputManager
import android.os.Build
import android.os.ServiceManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * 定向输入注入（设计文档 §2.2）。
 *
 * 用 IInputManager.injectInputEvent 而不是 `input` 命令行：后者每次 fork 一个进程约 100–200ms，
 * 而 Agent 每一步都要注入。
 *
 * 两条硬约束：
 * 1. 事件必须经反射 setDisplayId 指定副屏，否则会落到主屏，等于直接违反零干扰；
 * 2. 返回值只代表系统接受事件，不代表目标 App 收到或动作生效——结果判定在 app 进程。
 */
internal class InputInjector {

    /** input 系统服务；第一次注入时才取，避免服务构造期就依赖 binder。 */
    private val manager: IInputManager by lazy {
        IInputManager.Stub.asInterface(ServiceManager.getService(Context.INPUT_SERVICE))
    }

    /** InputEvent.setDisplayId 是 @hide，只能反射；拿不到时后续注入必须失败而不是猜主屏。 */
    private val setDisplayIdMethod = runCatching {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }.getOrNull()

    /**
     * 功能：在指定副屏点一次
     * 参数：displayId 目标屏；x/y 原生像素坐标；pressMs 按压时长
     * 返回：按下与抬起是否都被系统接受
     */
    fun tap(displayId: Int, x: Float, y: Float, pressMs: Long): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = injectTouch(displayId, downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        Thread.sleep(pressMs.coerceAtLeast(0))
        val up = injectTouch(displayId, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
        return down && up
    }

    /**
     * 功能：沿轨迹在指定副屏滑一次
     * 参数：displayId 目标屏；xs/ys 轨迹点（原生像素）；durationMs 总时长
     * 返回：全部事件是否都被系统接受
     */
    fun swipe(displayId: Int, xs: FloatArray, ys: FloatArray, durationMs: Long): Boolean {
        if (xs.size < 2 || xs.size != ys.size) return false

        val downTime = SystemClock.uptimeMillis()
        var accepted = injectTouch(displayId, downTime, downTime, MotionEvent.ACTION_DOWN, xs[0], ys[0])

        val gapMs = durationMs / (xs.size - 1)
        for (index in 1 until xs.lastIndex) {
            Thread.sleep(gapMs.coerceAtLeast(0))
            val move = injectTouch(
                displayId, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, xs[index], ys[index],
            )
            accepted = accepted && move
        }

        Thread.sleep(gapMs.coerceAtLeast(0))
        val up = injectTouch(
            displayId, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, xs.last(), ys.last(),
        )
        return accepted && up
    }

    /**
     * 功能：向指定副屏注入一次按键
     * 参数：displayId 目标屏；keyCode Android KeyEvent 键值
     * 返回：按下与抬起是否都被系统接受
     */
    fun key(displayId: Int, keyCode: Int): Boolean = keyWithMeta(displayId, keyCode, 0)

    /**
     * 功能：向指定副屏注入一次带修饰键的按键
     * 参数：displayId 目标屏；keyCode 键值；metaState 修饰键位（SHIFT 等）
     * 返回：按下与抬起是否都被系统接受
     *
     * 逐字符键入 ASCII 文本时用：大写字母与符号要靠 SHIFT/ALT 位表达，
     * 只发 keyCode 会被目标当成小写或无效字符。
     */
    fun keyAction(displayId: Int, keyCode: Int, metaState: Int, action: Int): Boolean =
        injectKey(displayId, SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, keyCode, metaState)

    fun keyWithMeta(displayId: Int, keyCode: Int, metaState: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = injectKey(displayId, downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, metaState)
        val up = injectKey(
            displayId, downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, metaState,
        )
        return down && up
    }

    /**
     * 功能：构造并注入一个触摸事件
     * 参数：displayId 目标屏；downTime 按下时刻；eventTime 事件时刻；action 动作；x/y 坐标
     * 返回：系统是否接受
     */
    private fun injectTouch(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): Boolean {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        return try {
            inject(event, displayId)
        } finally {
            event.recycle()
        }
    }

    /**
     * 功能：构造并注入一个按键事件
     * 参数：displayId 目标屏；downTime 按下时刻；eventTime 事件时刻；action 动作；keyCode 键值
     * 返回：系统是否接受
     */
    private fun injectKey(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        action: Int,
        keyCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val event = KeyEvent(downTime, eventTime, action, keyCode, metaState)
        event.source = InputDevice.SOURCE_KEYBOARD
        return inject(event, displayId)
    }

    /**
     * 功能：指定 displayId 后注入
     * 参数：event 待注入事件；displayId 目标屏
     * 返回：系统是否接受
     *
     * displayId 设置失败时直接返回 false，不允许退回主屏注入。
     */
    private fun inject(event: InputEvent, displayId: Int): Boolean {
        val setter = setDisplayIdMethod ?: return false
        runCatching { setter.invoke(event, displayId) }.getOrElse { return false }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.injectInputEventToTarget(event, MODE_WAIT_FOR_FINISH, INVALID_UID)
        } else {
            manager.injectInputEvent(event, MODE_WAIT_FOR_FINISH)
        }
    }

    private companion object {
        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH 的取值；该常量是 @hide。 */
        const val MODE_WAIT_FOR_FINISH = 2

        /** 不限定目标 uid，等价于普通 injectInputEvent。 */
        const val INVALID_UID = -1
    }
}
