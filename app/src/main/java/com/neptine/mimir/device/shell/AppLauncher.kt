package com.neptine.mimir.device.shell

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * 拉起 App 与包枚举（设计文档 §2.2）。
 *
 * launchDisplayId 只是启动意图，不是隔离证明：启动后必须由上层重新读取目标 Activity 的
 * 实际 displayId，不符即中止。本类只负责"把意图发出去"。
 *
 * 走 `/system/bin/am start --display` 而不是 PendingIntent + ActivityOptions：
 * 真机（HyperOS 3.0）实测 PendingIntent 那条路径不可靠——同一份代码时而把目标 App
 * 丢到主屏（`mCurrentFocus` 在主屏变成目标 App）、时而复用主屏已有的任务栈再整体搬走
 * （栈顶停在上一轮遗留的页面）。`am start --display` 走的是 shell 身份直连
 * ActivityTaskManager，实测稳定落在指定 display 且栈顶是 launcher activity。
 * 代价是一次 fork（约 50–200ms），拉起是低频操作，可以接受。
 */
internal class AppLauncher(private val context: Context) {

    private val packageManager = context.packageManager
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    /**
     * 功能：把目标 App 拉起并显示在指定副屏
     * 参数：packageName 目标包名；displayId 目标屏
     * 返回：am 命令执行成功为 true；无启动入口、屏不存在或 am 报错时为 false
     *
     * 不清空目标 App 已有任务栈：主屏后台可能留着用户的会话（购物车、草稿），
     * 清栈会把它们一起抹掉。任务该不该在"主屏已开着同一 App"时启动，由上层的主屏
     * 冲突检测决定，不在这一层做破坏性操作。
     */
    fun launch(packageName: String, displayId: Int): Boolean {
        if (displayManager.getDisplay(displayId) == null) {
            Log.d(TAG, "拉起失败：display $displayId 不存在")
            return false
        }
        val component = launcherComponentOf(packageName) ?: return false
        return startComponent(component, displayId)
    }

    /**
     * 功能：按系统 Intent action 在指定副屏打开页面
     * 参数：action 系统 action；displayId 目标屏
     * 返回：am 命令执行成功为 true；action 无法解析或启动失败时为 false
     *
     * 用于只读复验：App 内部从搜索页点结果时可能不带 display 启动，页面会落到主屏；
     * 这里按系统 action 解析出组件，再用 `am start --display` 在副屏开一份干净的只读页面。
     */
    fun launchAction(action: String, displayId: Int): Boolean {
        if (displayManager.getDisplay(displayId) == null) {
            Log.d(TAG, "action 拉起失败：display $displayId 不存在")
            return false
        }
        val intent = Intent(action).addCategory(Intent.CATEGORY_DEFAULT)
        val activity = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo ?: return false
        val component = "${activity.packageName}/${activity.name}"
        return startComponent(component, displayId)
    }

    /**
     * 功能：用 am start 在指定副屏启动组件
     * 参数：component `包名/Activity 全名`；displayId 目标屏
     * 返回：am 命令执行成功且 intent 没有被投递给已有实例为 true
     */
    private fun startComponent(component: String, displayId: Int): Boolean {
        val process = ProcessBuilder(
            AM_BINARY, "start",
            FLAGS_OPTION, FORCE_NEW_TASK,
            DISPLAY_OPTION, displayId.toString(),
            COMPONENT_OPTION, component,
        ).redirectErrorStream(true).start()

        val finished = process.waitFor(AM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            Log.d(TAG, "am start 超时（${AM_TIMEOUT_SECONDS}s）：$component → display $displayId")
            return false
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.exitValue()
        // am 在"intent 被投递给已有实例"时仍返回 0，只能从输出里认出来
        val started = code == 0 && !output.contains(NOT_STARTED_HINT)
        Log.d(TAG, "am start --display $displayId $component → exit=$code started=$started ${output.trim().take(OUTPUT_LIMIT)}")
        return started
    }

    /**
     * 功能：解析出目标包的启动组件
     * 参数：packageName 目标包名
     * 返回：`包名/Activity 全名`；没有启动入口时为 null
     *
     * am start 需要显式组件名：只给包名时它自己也会去解析，但解析失败的错误发生在子进程里，
     * 不如在这里先判空、让上层拿到明确的 false。
     */
    private fun launcherComponentOf(packageName: String): String? {
        val activity = packageManager.getLaunchIntentForPackage(packageName)?.component ?: return null
        return "${activity.packageName}/${activity.className}"
    }

    /**
     * 功能：列出有启动入口的应用包名
     * 参数：无
     * 返回：包名数组（去重）
     */
    fun launchablePackages(): Array<String> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager
            .queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .distinct()
            .toTypedArray()
    }

    private companion object {
        /** 诊断日志标签，与 shell 进程其他日志保持一致。 */
        const val TAG = "MimirShell"

        /** 系统自带的 activity 启动命令。 */
        const val AM_BINARY = "/system/bin/am"

        /** 指定目标显示的参数名。 */
        const val DISPLAY_OPTION = "--display"

        /** 指定组件名的参数名。 */
        const val COMPONENT_OPTION = "-n"

        /** 指定 intent flag 的参数名。 */
        const val FLAGS_OPTION = "-f"

        /**
         * 强制新建独立 task：FLAG_ACTIVITY_NEW_TASK(0x10000000) | FLAG_ACTIVITY_MULTIPLE_TASK(0x08000000)。
         *
         * 缺了 MULTIPLE_TASK，目标 App 已有实例时 am 只会把 intent 投递给那个实例
         * （日志：`Activity not started, intent has been delivered to currently running
         * top-most instance`，退出码仍是 0），副屏永远拿不到 App。
         * 加 MULTIPLE_TASK 后在副屏新建一个从 launcher activity 开始的 task，
         * 主屏上用户原有的那份实例不受影响。
         */
        const val FORCE_NEW_TASK = "0x18000000"

        /** am 命令的等待上限（秒）；正常在 1 秒内返回。 */
        const val AM_TIMEOUT_SECONDS = 10L

        /** 日志里回显 am 输出的上限（字符）。 */
        const val OUTPUT_LIMIT = 200

        /** am 把 intent 投递给已有实例时打印的提示；退出码仍为 0，不能据此判成功。 */
        const val NOT_STARTED_HINT = "Activity not started"
    }
}
