package com.neptine.mimir.device.shizuku

import android.view.accessibility.AccessibilityWindowInfo
import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.ForegroundApp
import com.neptine.mimir.device.ActivityPort
import com.neptine.mimir.service.MimirAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 拉起与前台校验端口的 Shizuku 实现（设计文档 §1.2 / §2.2）。
 *
 * 拉起走 ActivityOptions.launchDisplayId；校验必须走无障碍窗口，因为
 * "启动意图"不等于"真的在那个屏上"——App 可能自己拒绝多屏或在主屏兜底。
 */
class ShizukuActivityPort(private val shell: ShizukuShell) : ActivityPort {

    private companion object {
        /** 主屏 displayId：am start 传入 0 即可落在默认显示。 */
        const val MAIN_DISPLAY_ID = 0
    }

    /**
     * 功能：把目标 App 拉起并显示到指定副屏
     * 参数：packageName 目标包名；handle 建屏句柄
     * 返回：无；无启动入口时抛异常
     */
    override suspend fun launch(packageName: String, handle: DisplayHandle): Unit =
        withContext(Dispatchers.IO) {
            val launched = shell.require().launchApp(packageName, handle.displayId)
            check(launched) { "包名 $packageName 没有可用的启动入口" }
        }

    /**
     * 功能：把目标 App 打开到主屏（人工接管）
     * 参数：packageName 目标包名
     * 返回：无；无启动入口时抛异常
     */
    override suspend fun launchOnMain(packageName: String): Unit = withContext(Dispatchers.IO) {
        val launched = shell.require().launchApp(packageName, MAIN_DISPLAY_ID)
        check(launched) { "包名 $packageName 没有可用的启动入口" }
    }

    /**
     * 功能：按系统 Intent action 在指定副屏打开页面
     * 参数：action 系统 action；handle 建屏句柄
     * 返回：启动意图是否已发出；action 无法解析或 shell 异常时为 false
     */
    override suspend fun launchAction(action: String, handle: DisplayHandle): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { shell.require().launchAction(action, handle.displayId) }
                .getOrDefault(false)
        }

    /**
     * 功能：读取指定副屏上的前台应用
     * 参数：handle 建屏句柄
     * 返回：前台应用信息；该屏无应用窗口或无障碍未连接时为 null
     *
     * Activity 名取窗口标题：无障碍窗口不直接暴露 Activity 类名，
     * 标题在绝大多数 App 上等于当前页面标题，足够用于"包名 + 页面"的一致性判断。
     *
     * 必须按 TYPE_APPLICATION 过滤：副屏是 PUBLIC 显示，SystemUI 的通知栏/气泡窗口
     * 也会镜像过来，取第一个窗口可能拿到 com.android.systemui，把"App 没在屏上"误判成
     * "屏上是系统界面"。
     */
    override suspend fun foregroundOn(handle: DisplayHandle): ForegroundApp? =
        withContext(Dispatchers.IO) {
            val service = MimirAccessibilityService.instance ?: return@withContext null
            val windows = service.windowsOnDisplay(handle.displayId)
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val focused = windows.firstOrNull { it.isFocused } ?: windows.firstOrNull() ?: return@withContext null
            val root = focused.root ?: return@withContext null
            val packageName = root.packageName?.toString() ?: return@withContext null
            ForegroundApp(
                packageName = packageName,
                activityName = focused.title?.toString().orEmpty(),
                displayId = focused.displayId,
            )
        }

    /**
     * 功能：移除目标包不在副屏上的任务
     * 参数：packageName 目标包名；handle 建屏句柄
     * 返回：至少移除一个任务为 true
     */
    override suspend fun removeTasksOutside(packageName: String, handle: DisplayHandle): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { shell.require().removeTasksOutside(packageName, handle.displayId) }
                .getOrDefault(false)
        }

    /**
     * 功能：判断目标包是否在副屏上存在任务
     * 参数：packageName 目标包名；handle 建屏句柄
     * 返回：任务存在为 true；shell 不可用时为 false
     */
    override suspend fun hasTaskOnDisplay(packageName: String, handle: DisplayHandle): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { shell.require().hasTaskOnDisplay(packageName, handle.displayId) }
                .getOrDefault(false)
        }

    /**
     * 功能：把目标包在副屏上的根任务搬到主屏
     * 参数：packageName 目标包名；handle 建屏句柄
     * 返回：搬走的系统 taskId；失败为 null
     */
    override suspend fun moveTaskToMain(packageName: String, handle: DisplayHandle): Int? =
        withContext(Dispatchers.IO) {
            runCatching { shell.require().moveTaskToMain(packageName, handle.displayId) }
                .getOrNull()
                ?.takeIf { it >= 0 }
        }

    /**
     * 功能：把系统任务带到前台
     * 参数：taskId 系统任务标识
     * 返回：系统接受为 true
     */
    override suspend fun bringTaskToFront(taskId: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { shell.require().bringTaskToFront(taskId) }.getOrDefault(false)
        }
}
