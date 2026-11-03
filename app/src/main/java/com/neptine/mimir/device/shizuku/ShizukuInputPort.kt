package com.neptine.mimir.device.shizuku

import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.Point
import com.neptine.mimir.device.InputPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 定向注入端口的 Shizuku 实现（设计文档 §2.2）。
 *
 * 注入放在 shell 进程：IInputManager 是 @hide 接口，且 INJECT_EVENTS 只有 shell 持有。
 * 返回值只代表系统接受事件，不代表目标 App 收到——生效与否由 app 进程的后置条件判定。
 *
 * 所有方法都切到 IO 线程：AIDL 是同步 binder 调用，且点击含按压时长，留在主线程会卡界面。
 */
class ShizukuInputPort(private val shell: ShizukuShell) : InputPort {

    /**
     * 功能：在副屏指定点点击一次
     * 参数：handle 建屏句柄；point 目标点（原生像素）；pressMs 按压时长
     * 返回：系统是否接受事件
     */
    override suspend fun tap(handle: DisplayHandle, point: Point, pressMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            shell.require().tap(handle.displayId, point.x.toFloat(), point.y.toFloat(), pressMs)
        }

    /**
     * 功能：在副屏执行一次滑动
     * 参数：handle 建屏句柄；path 轨迹点（原生像素）
     * 返回：系统是否接受全部事件
     */
    override suspend fun swipe(handle: DisplayHandle, path: List<Point>): Boolean {
        if (path.size < 2) return false
        return withContext(Dispatchers.IO) {
            val xs = FloatArray(path.size) { path[it].x.toFloat() }
            val ys = FloatArray(path.size) { path[it].y.toFloat() }
            shell.require().swipe(handle.displayId, xs, ys, DEFAULT_SWIPE_MS)
        }
    }

    /**
     * 功能：向副屏注入一次按键
     * 参数：handle 建屏句柄；keyCode Android KeyEvent 键值
     * 返回：系统是否接受事件
     */
    override suspend fun key(handle: DisplayHandle, keyCode: Int): Boolean =
        withContext(Dispatchers.IO) { shell.require().key(handle.displayId, keyCode) }

    override suspend fun keyWithMeta(handle: DisplayHandle, keyCode: Int, metaState: Int): Boolean =
        withContext(Dispatchers.IO) { shell.require().keyWithMeta(handle.displayId, keyCode, metaState) }

    override suspend fun keyAction(
        handle: DisplayHandle,
        keyCode: Int,
        metaState: Int,
        action: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        shell.require().keyAction(handle.displayId, keyCode, metaState, action)
    }

    override suspend fun setClipboard(text: String): Boolean = withContext(Dispatchers.IO) {
        shell.require().setClipboardText(text)
    }

    private companion object {
        /** 默认滑动时长；拟人化的轨迹与速度随机化在执行层做，不在注入层（§4.5）。 */
        const val DEFAULT_SWIPE_MS = 300L
    }
}
