package com.neptine.mimir.device.shell

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * 跨显示的任务清场（设计文档 §2.2 拉起与隔离）。
 *
 * 真实 App 会用 deep link 启动自己的内部页面（美团点搜索框 → `imeituan://.../SearchActivity`），
 * 这类启动没有指定显示，AMS 会在**默认显示**为它新建 root task，随后把整个 App 的任务
 * reparent 过去——用户主屏就被这个 App 占住了。这种情况从外部无法预防。
 *
 * 为什么不做"搬回副屏"：搬运需要目标屏上有一个 root task 当落点，而任务被 reparent 走后
 * 副屏上连 root task 都不剩（真机实测 `getAllRootTaskInfosOnDisplay` 返回空）；
 * `moveTaskToDisplay` 在 Android 16 上已不存在，`moveRootTaskToDisplay` 对该场景返回 false。
 * 因此恢复策略放在 app 层：在副屏重新拉起一个干净实例，再用本类清掉主屏那份。
 *
 * 权限依据（真机实测 com.android.shell）：`REAL_GET_TASKS` 与 `REMOVE_TASKS` 均为 granted，
 * 所以 getRunningTasks / removeTask 可用；`removeTask` 是 @hide，只能反射。
 */
internal class TaskMover(context: Context) {

    /** 用于取任务列表；shell uid 持 REAL_GET_TASKS，不会被过滤成本进程自己的任务。 */
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    /**
     * 功能：移除目标包不在指定显示上的任务（即占据用户主屏的那份）
     * 参数：packageName 目标包名；displayId 需要保留的显示
     * 返回：至少移除一个任务为 true
     */
    fun removeOutside(packageName: String, displayId: Int): Boolean {
        val service = service() ?: return false
        var removed = false
        for (task in tasksOutside(packageName, displayId)) {
            if (removeTask(service, task.taskId)) {
                Log.d(TAG, "清场 display ${task.displayId}：task=${task.taskId} pkg=$packageName")
                removed = true
            }
        }
        return removed
    }

    /**
     * 功能：判断目标包是否在指定显示上存在任务
     * 参数：packageName 目标包名；displayId 目标显示
     * 返回：存在为 true
     *
     * 只回答"AMS 里有没有这个包在这块屏上的任务"，不依赖无障碍窗口索引：
     * 真机上多次建屏/拆屏后，无障碍的 windowsOnAllDisplays 会偶发不再收录副屏，
     * 此时 launched 明明成功、任务也在副屏，但 foregroundOn 读不到任何窗口。
     * 拉起校验用它兜底，避免把"索引退化"误判成"应用没拉起来"并直接判任务失败。
     */
    fun hasTaskOnDisplay(packageName: String, displayId: Int): Boolean =
        tasksOnDisplay(packageName, displayId).isNotEmpty()

    /**
     * 功能：把目标包在指定显示上的根任务搬到主屏
     * 参数：packageName 目标包名；displayId 原显示
     * 返回：搬走的系统 taskId；没有任务或移动失败返回 -1
     *
     * 结果页交接用：用户点结果通知时，把整个根任务搬到主屏并置顶，
     * 用户看到的就是 Agent 当时停留的页面。
     * 用 `am display move-stack` 而不是反射：真机上 IActivityTaskManager 代理
     * 没有 moveRootTaskToDisplay(int,int,boolean) 这个签名（实测 NoSuchMethodException），
     * 而 am 命令与 shell 命令行走的是同一条系统实现。
     */
    fun moveTaskToMain(packageName: String, displayId: Int): Int {
        val task = tasksOnDisplay(packageName, displayId).firstOrNull()
        if (task == null) {
            Log.d(TAG, "搬到主屏失败：display $displayId 上没有 $packageName 的任务")
            return -1
        }
        val moved = runCatching {
            val process = ProcessBuilder(
                "/system/bin/am",
                "display",
                "move-stack",
                task.taskId.toString(),
                DISPLAY_MAIN.toString(),
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            Log.d(TAG, "am display move-stack ${task.taskId} -> $DISPLAY_MAIN exit=$exit out=${output.take(240)}")
            // am 在系统内部抛异常时退出码也可能是 0，只能从输出里认出来
            exit == 0 && !output.containsAny(MOVE_FAILURE_HINTS)
        }.onFailure { Log.d(TAG, "am display move-stack 失败：${it.message}") }.getOrDefault(false)
        if (!moved) {
            Log.d(TAG, "搬回主屏失败：task=${task.taskId} pkg=$packageName display=$displayId")
            return -1
        }
        // 命令成功不等于任务真的落到了主屏：真实 App 的任务可能带子任务，
        // move-stack 之后需要从 AMS 侧确认，否则用户点通知只会看到一个空动作。
        if (!awaitTaskOnMain(packageName)) {
            Log.d(TAG, "搬回主屏后校验失败：display $DISPLAY_MAIN 上没有 $packageName 的任务")
            return -1
        }
        Log.d(TAG, "搬回主屏：task=${task.taskId} pkg=$packageName")
        return task.taskId
    }

    /**
     * 功能：等待目标包的任务出现在主屏
     * 参数：packageName 目标包名
     * 返回：3 次轮询内出现为 true
     *
     * getRunningTasks 是快照，跨显示搬运后立刻读可能还是旧的，所以要轮询而不是只读一次。
     */
    private fun awaitTaskOnMain(packageName: String): Boolean {
        repeat(MOVE_VERIFY_ATTEMPTS) { attempt ->
            if (tasksOnDisplay(packageName, DISPLAY_MAIN).isNotEmpty()) return true
            if (attempt < MOVE_VERIFY_ATTEMPTS - 1) Thread.sleep(MOVE_VERIFY_GAP_MS)
        }
        return false
    }

    /**
     * 功能：把指定系统任务带到前台
     * 参数：taskId 系统任务标识
     * 返回：系统接受为 true
     *
     * 两级手段，因为真机上第一级不一定成：
     * 1. 反射 `IActivityTaskManager.startActivityFromRecents`——不写死第二个参数类型：
     *    真机（Android 16 / HyperOS 4.0）上它的签名是 `(int, Bundle)`，按
     *    `(int, ActivityOptions)` 反射直接 NoSuchMethodException，这正是"点了通知只打开
     *    App 首页"的直接原因之一。
     * 2. 仍然没起来时，用 `am start -f NEW_TASK|REORDER_TO_FRONT` 打开该任务的栈顶 Activity：
     *    shell 不受后台启动限制，且 REORDER_TO_FRONT 复用已有实例、不新建页面。
     */
    fun bringTaskToFront(taskId: Int): Boolean {
        val service = service()
        if (service != null) {
            val candidates = service.javaClass.methods
                .filter { it.name == BRING_TO_FRONT_METHOD && it.parameterTypes.size == 2 }
            for (method in candidates) {
                val result = runCatching { method.invoke(service, taskId, null) }
                    .onFailure {
                        Log.d(TAG, "startActivityFromRecents 调用失败：${it.javaClass.simpleName} ${it.message}")
                    }
                    .getOrNull()
                Log.d(TAG, "置顶调用 ${method.parameterTypes.joinToString { p -> p.simpleName }} 返回=$result")
                // startActivityFromRecents 成功时可能返回 START_SUCCESS(0)，也可能返回
                // START_TASK_TO_FRONT(2)（任务被带到前台）。真机实测拿到的是 2，
                // 只认 0 会把已经成功的情况判成失败，再白跑一次 am start。
                if (result is Int && result in BRING_SUCCESS_CODES) return true
            }
            if (candidates.isEmpty()) {
                val names = service.javaClass.methods
                    .filter { it.name.contains("FromRecents", ignoreCase = true) }
                    .joinToString { it.name + it.parameterTypes.joinToString(",", "(", ")") { p -> p.simpleName } }
                Log.d(TAG, "找不到可用的置顶方法，候选=$names")
            }
        }

        val component = topActivityOf(taskId) ?: return false
        return startWithReorder(component, taskId)
    }

    /**
     * 功能：用 am start 把任务栈顶 Activity 提到前台
     * 参数：component 栈顶 Activity；taskId 目标任务（仅用于日志）
     * 返回：命令执行成功为 true
     */
    private fun startWithReorder(component: android.content.ComponentName, taskId: Int): Boolean =
        runCatching {
            val process = ProcessBuilder(
                "/system/bin/am", "start",
                "-f", REORDER_FLAGS,
                "--display", DISPLAY_MAIN.toString(),
                "-n", "${component.packageName}/${component.className}",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            Log.d(TAG, "am start 置顶 task=$taskId exit=$exit out=${output.take(240)}")
            exit == 0 && !output.containsAny(MOVE_FAILURE_HINTS)
        }.onFailure { Log.d(TAG, "am start 置顶失败：${it.message}") }.getOrDefault(false)

    /**
     * 功能：读某个系统任务的栈顶 Activity
     * 参数：taskId 系统任务标识
     * 返回：栈顶组件；读不到为 null
     */
    private fun topActivityOf(taskId: Int): android.content.ComponentName? =
        runningTasks().firstOrNull { intField(it, "taskId", "id") == taskId }
            ?.let { info ->
                runCatching { info.javaClass.getField("topActivity").get(info) as? android.content.ComponentName }
                    .getOrNull()
            }

    /**
     * 功能：列出目标包在指定显示上的任务
     * 参数：packageName 目标包名；displayId 目标显示
     * 返回：任务标识列表
     */
    private fun tasksOnDisplay(packageName: String, displayId: Int): List<TaskRef> {
        val all = runningTasks()
        val refs = all.mapNotNull { info ->
            val owner = topPackageOf(info) ?: return@mapNotNull null
            val taskDisplayId = intField(info, "displayId") ?: return@mapNotNull null
            val taskId = intField(info, "taskId", "id") ?: return@mapNotNull null
            if (owner != packageName || taskDisplayId != displayId) null
            else TaskRef(taskId = taskId, displayId = taskDisplayId)
        }
        Log.d(TAG, "任务扫描：共 ${all.size} 个，命中 $packageName 且在 display $displayId 上的 ${refs.size} 个：$refs")
        return refs
    }

    /**
     * 功能：列出目标包不在指定显示上的任务
     * 参数：packageName 目标包名；displayId 需要保留的显示
     * 返回：任务标识与所在显示的列表
     */
    private fun tasksOutside(packageName: String, displayId: Int): List<TaskRef> {
        val all = runningTasks()

        val refs = all.mapNotNull { info ->
            val owner = topPackageOf(info) ?: return@mapNotNull null
            val taskDisplayId = intField(info, "displayId") ?: return@mapNotNull null
            val taskId = intField(info, "taskId", "id") ?: return@mapNotNull null
            if (owner != packageName || taskDisplayId == displayId) null
            else TaskRef(taskId = taskId, displayId = taskDisplayId)
        }
        Log.d(TAG, "任务扫描：共 ${all.size} 个，命中 $packageName 且不在 display $displayId 上的 ${refs.size} 个：$refs")
        return refs
    }

    /**
     * 功能：取当前所有任务
     * 参数：无
     * 返回：RunningTaskInfo 列表；读取失败时为空列表
     *
     * 用公开的 ActivityManager.getRunningTasks：IActivityTaskManager.getTasks(int)
     * 在 Android 16 上已不存在（反射直接 NoSuchMethodException）。
     */
    private fun runningTasks(): List<Any> =
        runCatching { activityManager?.getRunningTasks(Int.MAX_VALUE).orEmpty() }
            .onFailure { Log.d(TAG, "getRunningTasks 失败：${it.javaClass.simpleName} ${it.message}") }
            .getOrNull().orEmpty()

    /**
     * 功能：读任务栈顶的包名
     * 参数：info RunningTaskInfo 实例
     * 返回：包名；字段缺失时为 null
     *
     * 只认 topActivity：baseActivity 是任务创建时的 Activity，App 跳到子页面后它仍是入口页，
     * 用它判断会把"任务已经跑到别处"误判成"还在原处"。
     */
    private fun topPackageOf(info: Any): String? = runCatching {
        val component = info.javaClass.getField("topActivity").get(info) as? android.content.ComponentName
        component?.packageName
    }.getOrNull()

    /**
     * 功能：读整数字段，兼容同一语义的不同字段名
     * 参数：info 目标对象；names 候选字段名，按优先级排列
     * 返回：第一个能读到的值；都读不到为 null
     */
    private fun intField(info: Any, vararg names: String): Int? {
        for (name in names) {
            val value = runCatching { info.javaClass.getField(name).getInt(info) }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    /**
     * 功能：移除一个任务
     * 参数：service IActivityTaskManager 代理；taskId 任务标识
     * 返回：系统接受为 true
     */
    private fun removeTask(service: Any, taskId: Int): Boolean = runCatching {
        val method = service.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)
        method.invoke(service, taskId) as? Boolean ?: false
    }.getOrDefault(false)

    /**
     * 功能：取 IActivityTaskManager 代理
     * 参数：无
     * 返回：代理对象；取不到为 null
     *
     * ActivityTaskManager.getService() 是 @hide 静态方法，只能反射调用。
     */
    private fun service(): Any? = runCatching {
        Class.forName("android.app.ActivityTaskManager")
            .getMethod("getService")
            .invoke(null)
    }.onFailure { Log.d(TAG, "取 ActivityTaskManager 失败：${it.message}") }.getOrNull()

    /**
     * 功能：判断命令输出里是否含任一失败提示
     * 参数：hints 失败提示片段
     * 返回：命中任一为 true
     */
    private fun String.containsAny(hints: List<String>): Boolean = hints.any { contains(it) }

    /** 一个任务的最小标识。 */
    private data class TaskRef(val taskId: Int, val displayId: Int)

    private companion object {
        /** 诊断日志标签。 */
        const val TAG = "MimirShell"

        /** 主屏显示编号；Android 上默认显示恒为 0。 */
        const val DISPLAY_MAIN = 0

        /** 搬运后校验的次数与间隔：getRunningTasks 是快照，跨屏搬运后要等一拍。 */
        const val MOVE_VERIFY_ATTEMPTS = 3
        const val MOVE_VERIFY_GAP_MS = 300L

        /** am 输出里代表"命令到了但系统拒绝"的片段。 */
        val MOVE_FAILURE_HINTS = listOf("Error", "Exception", "not found", "Unable")

        /** 任务置顶的系统方法名。 */
        const val BRING_TO_FRONT_METHOD = "startActivityFromRecents"

        /** START_SUCCESS：系统接受置顶请求的返回值。 */
        const val START_SUCCESS = 0

        /** 置顶的成功返回码：0 = START_SUCCESS，2 = START_TASK_TO_FRONT（任务已带到前台）。 */
        val BRING_SUCCESS_CODES = setOf(START_SUCCESS, 2)

        /**
         * 置顶用的 am 启动标志。
         *
         * NEW_TASK(0x10000000) | REORDER_TO_FRONT(0x00020000)：
         * 前者允许在已有任务里定位 Activity，后者复用已有实例并把它移到任务顶部，
         * 不会新建一个丢参数的重复页面。
         */
        const val REORDER_FLAGS = "0x10020000"
    }
}
