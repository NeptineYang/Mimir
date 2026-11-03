package com.neptine.mimir.device

import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.ForegroundApp

/**
 * 拉起与前台校验端口（设计文档 §2.2 / §1.2）。
 *
 * launchDisplayId 只是启动意图，不是隔离证明：启动后与每次注入前都必须重新读取
 * 目标 Activity 的实际 displayId，不符即中止，不允许降级到主屏。
 */
interface ActivityPort {

    /**
     * 功能：把目标 App 拉起并显示在指定副屏
     * 参数：packageName 目标包名；handle 建屏句柄
     * 返回：无
     */
    suspend fun launch(packageName: String, handle: DisplayHandle)

    /**
     * 功能：把目标 App 打开到**主屏**（人工接管用）
     * 参数：packageName 目标包名
     * 返回：无；无启动入口时抛异常
     *
     * 只由用户点通知里的「在主屏打开」触发：project 的硬约束是"不主动占用主屏"，
     * 所以这条通道不在任何自动化路径上。
     */
    suspend fun launchOnMain(packageName: String)

    /**
     * 功能：按系统 Intent action 在指定副屏打开页面
     * 参数：action 系统 action（如 android.settings.DEVICE_INFO_SETTINGS）；handle 建屏句柄
     * 返回：启动意图是否已发出
     *
     * App 内部从搜索页点结果时可能不带 display 启动，页面会落到主屏；这条通道用于
     * 复验时把同一个只读页面在副屏重新打开，参数是系统 action 而不是包名。
     */
    suspend fun launchAction(action: String, handle: DisplayHandle): Boolean

    /**
     * 功能：读取指定副屏上的前台应用
     * 参数：handle 建屏句柄
     * 返回：前台应用信息；该屏无前台应用时为 null
     */
    suspend fun foregroundOn(handle: DisplayHandle): ForegroundApp?

    /**
     * 功能：移除目标包不在副屏上的任务
     * 参数：packageName 目标包名；handle 建屏句柄
     * 返回：至少移除一个任务为 true
     *
     * App 用 deep link 启动内部页面时，AMS 会在默认显示新建 root task 并把整个任务
     * reparent 过去，用户主屏随即被占。副屏上此时连 root task 都不剩，只能"在副屏重新
     * 拉起 + 清掉主屏那份"，这条通道负责清场，保证屏幕立刻恢复干净。
     */
    suspend fun removeTasksOutside(packageName: String, handle: DisplayHandle): Boolean

    /**
     * 功能：判断目标包是否在副屏上存在任务
     * 参数：packageName 目标包名；handle 建屏句柄
     * 返回：任务存在为 true
     *
     * 这是"拉起是否真的落到副屏"的第二证据源：主路径读无障碍窗口，索引偶发退化读不到
     * 时用 AMS 任务列表兜底，避免把索引问题误判成拉起失败。
     */
    suspend fun hasTaskOnDisplay(packageName: String, handle: DisplayHandle): Boolean

    /**
     * 功能：把目标 App 在副屏上的根任务搬到主屏
     * 参数：packageName 目标包名；handle 建屏句柄
     * 返回：搬走的系统 taskId；没有任务或移动失败时为 null
     *
     * 结果页交接：用户点结果通知时，把整栈搬到主屏并置顶，
     * 直接回到 Agent 停留的页面，避免从启动页重新走一遍。
     */
    suspend fun moveTaskToMain(packageName: String, handle: DisplayHandle): Int?

    /**
     * 功能：把指定系统任务带到前台
     * 参数：taskId 系统任务标识
     * 返回：系统接受为 true
     */
    suspend fun bringTaskToFront(taskId: Int): Boolean
}
