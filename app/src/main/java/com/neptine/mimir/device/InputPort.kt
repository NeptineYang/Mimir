package com.neptine.mimir.device

import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.Point

/**
 * 定向输入注入端口（设计文档 §2.2）。
 *
 * 用 injectInputEvent 而不是 `input` 命令行：后者每次 fork 一个进程约 100–200ms，
 * 前者是纯 binder 调用。所有方法的返回值只代表**系统是否接受事件**，
 * 不代表目标 App 已收到或动作已生效——结果判定必须靠后置条件。
 */
interface InputPort {

    /**
     * 功能：在副屏指定点点击一次
     * 参数：handle 建屏句柄；point 目标点（原生像素，执行层已加抖动）；pressMs 按压时长（毫秒）
     * 返回：系统是否接受事件
     */
    suspend fun tap(handle: DisplayHandle, point: Point, pressMs: Long): Boolean

    /**
     * 功能：在副屏执行一次滑动
     * 参数：handle 建屏句柄；path 轨迹点（原生像素，执行层已生成缓动曲线）
     * 返回：系统是否接受事件
     */
    suspend fun swipe(handle: DisplayHandle, path: List<Point>): Boolean

    /**
     * 功能：向副屏注入一次按键
     * 参数：handle 建屏句柄；keyCode Android KeyEvent 键值
     * 返回：系统是否接受事件
     */
    suspend fun key(handle: DisplayHandle, keyCode: Int): Boolean

    /**
     * 功能：向副屏注入一次带修饰键的按键
     * 参数：handle 建屏句柄；keyCode 键值；metaState 修饰键位（SHIFT 等）
     * 返回：系统是否接受事件
     */
    suspend fun keyWithMeta(handle: DisplayHandle, keyCode: Int, metaState: Int): Boolean

    /**
     * 功能：向副屏注入单次按键动作
     * 参数：handle 建屏句柄；keyCode 键值；metaState 修饰位；action KeyEvent.ACTION_DOWN/ACTION_UP
     * 返回：系统是否接受事件
     */
    suspend fun keyAction(handle: DisplayHandle, keyCode: Int, metaState: Int, action: Int): Boolean

    /**
     * 功能：写入全局剪贴板
     * 参数：text 文本
     * 返回：是否写入成功
     */
    suspend fun setClipboard(text: String): Boolean
}
