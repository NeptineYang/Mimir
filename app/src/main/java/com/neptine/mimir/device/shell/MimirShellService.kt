package com.neptine.mimir.device.shell

import android.content.Context
import androidx.annotation.Keep

/**
 * 设备层特权服务入口（设计文档 §1.2 设备层 / §2.2）。
 *
 * 由 Shizuku 在 shell uid 的 app_process 里反射实例化（通过 Shizuku.UserServiceArgs 指定本类），
 * 因此必须保留类名可反射：@Keep 防止 R8 改名或裁掉。
 *
 * 职责边界：只做命令转发与结果回传。风险分级、授权消费、结果判定全部留在 app 进程，
 * 这个进程里没有任何"该不该做"的判断。
 */
@Keep
class MimirShellService : IMimirShell.Stub() {

    /**
     * Context 必须在构造期（Shizuku 在主线程实例化本服务）建立：
     * ActivityThread.systemMain() 要求当前线程已 prepareMainLooper，而后续 AIDL 调用
     * 都落在 binder 线程池，那时再建会直接抛 "Can't create handler inside thread ..."。
     *
     * 用 Result 兜住：构造失败不阻止服务创建，具体原因随第一次真实调用回传，
     * 否则客户端只能看到一条笼统的"绑定失败"。
     */
    private val contextResult: Result<Context> = runCatching { ShellContext.create() }

    private val context: Context
        get() = contextResult.getOrElse { throw IllegalStateException("shell Context 初始化失败：${it.message}") }

    private val displays: VirtualDisplayController by lazy { VirtualDisplayController(context) }

    private val launcher: AppLauncher by lazy { AppLauncher(context) }

    private val tasks: TaskMover by lazy { TaskMover(context) }

    private val inputs = InputInjector()

    private val clipboard = ClipboardWriter()

    private val ime = ImeSwitcher()

    /**
     * 功能：创建私有虚拟屏
     * 参数：name 屏名；width/height 原生像素；dpi 密度
     * 返回：displayId
     */
    override fun createDisplay(name: String, width: Int, height: Int, dpi: Int): Int =
        displays.create(name, width, height, dpi)

    /**
     * 功能：销毁虚拟屏
     * 参数：displayId 目标屏
     * 返回：无
     */
    override fun destroyDisplay(displayId: Int) = displays.destroy(displayId)

    /**
     * 功能：回收调用方已不再持有的虚拟屏
     * 参数：keepDisplayId 需要保留的屏；-1 表示全部销毁
     * 返回：销毁的屏数量
     *
     * 先清掉上一轮遗留的 shell 进程：它们的虚拟屏句柄在本进程里看不到，
     * 只能靠结束进程让系统释放。清理后本进程的 sessions 才是全部存量。
     */
    override fun reapDisplays(keepDisplayId: Int): Int {
        ShellJanitor.killSiblings()
        return displays.reap(keepDisplayId)
    }

    /**
     * 功能：抓取一帧副屏画面
     * 参数：displayId 目标屏；timeoutMs 取帧超时（毫秒）
     * 返回：WebP 字节；取帧失败返回 null
     */
    override fun captureDisplay(displayId: Int, timeoutMs: Int): ByteArray? =
        displays.capture(displayId, timeoutMs)

    /**
     * 功能：把目标 App 拉起并显示到指定副屏
     * 参数：packageName 目标包名；displayId 目标屏
     * 返回：启动意图是否已发出
     */
    override fun launchApp(packageName: String, displayId: Int): Boolean =
        launcher.launch(packageName, displayId)

    /**
     * 功能：按系统 Intent action 在指定副屏打开页面
     * 参数：action 系统 action；displayId 目标屏
     * 返回：启动意图是否已发出
     */
    override fun launchAction(action: String, displayId: Int): Boolean =
        launcher.launchAction(action, displayId)

    /**
     * 功能：移除目标包不在指定屏上的任务
     * 参数：packageName 目标包名；displayId 需要保留的屏
     * 返回：至少移除一个任务为 true
     */
    override fun removeTasksOutside(packageName: String, displayId: Int): Boolean =
        tasks.removeOutside(packageName, displayId)

    /**
     * 功能：判断目标包是否在指定屏上存在任务
     * 参数：packageName 目标包名；displayId 目标屏
     * 返回：存在为 true
     */
    override fun hasTaskOnDisplay(packageName: String, displayId: Int): Boolean =
        tasks.hasTaskOnDisplay(packageName, displayId)

    /**
     * 功能：把目标包在指定屏上的根任务搬到主屏
     * 参数：packageName 目标包名；displayId 原屏
     * 返回：搬走的系统 taskId；失败为 -1
     */
    override fun moveTaskToMain(packageName: String, displayId: Int): Int =
        tasks.moveTaskToMain(packageName, displayId)

    /**
     * 功能：把系统任务带到前台
     * 参数：taskId 系统任务标识
     * 返回：系统接受为 true
     */
    override fun bringTaskToFront(taskId: Int): Boolean = tasks.bringTaskToFront(taskId)

    /**
     * 功能：在副屏点一次
     * 参数：displayId 目标屏；x/y 原生像素；pressMs 按压时长
     * 返回：系统是否接受事件
     */
    override fun tap(displayId: Int, x: Float, y: Float, pressMs: Long): Boolean =
        inputs.tap(displayId, x, y, pressMs)

    /**
     * 功能：在副屏滑一次
     * 参数：displayId 目标屏；xs/ys 轨迹；durationMs 总时长
     * 返回：系统是否接受全部事件
     */
    override fun swipe(displayId: Int, xs: FloatArray, ys: FloatArray, durationMs: Long): Boolean =
        inputs.swipe(displayId, xs, ys, durationMs)

    /**
     * 功能：向副屏注入按键
     * 参数：displayId 目标屏；keyCode 键值
     * 返回：系统是否接受事件
     */
    override fun key(displayId: Int, keyCode: Int): Boolean = inputs.key(displayId, keyCode)

    override fun keyWithMeta(displayId: Int, keyCode: Int, metaState: Int): Boolean =
        inputs.keyWithMeta(displayId, keyCode, metaState)

    override fun keyAction(displayId: Int, keyCode: Int, metaState: Int, action: Int): Boolean =
        inputs.keyAction(displayId, keyCode, metaState, action)

    /**
     * 功能：把文本写进系统剪贴板
     * 参数：text 待写入的文本
     * 返回：系统接受为 true
     *
     * 走 shell 通道（[ClipboardWriter]）：Context 的 ClipboardManager 会把继承自系统
     * 上下文的 opPackageName（android）当 callingPackage，与 uid 2000 不匹配而被拒。
     * 这条通路是中文输入的最后一条不切输入法的路，失败必须留下原因。
     */
    override fun setClipboardText(text: String): Boolean = clipboard.write(text)

    /**
     * 功能：列出有启动入口的应用包名
     * 参数：无
     * 返回：包名数组
     */
    override fun launchablePackages(): Array<String> = launcher.launchablePackages()

    /**
     * 功能：启用或停用输入法
     * 参数：imeId 输入法组件 id；enabled 是否启用
     * 返回：命令是否成功
     */
    override fun setImeEnabled(imeId: String, enabled: Boolean): Boolean =
        ime.setEnabled(imeId, enabled)

    /**
     * 功能：切换系统默认输入法
     * 参数：imeId 输入法组件 id
     * 返回：命令是否成功
     */
    override fun setIme(imeId: String): Boolean = ime.set(imeId)

    /**
     * 功能：读取当前系统默认输入法
     * 参数：无
     * 返回：输入法组件 id；读取失败为空串
     */
    override fun currentImeId(): String = ime.current()
}
