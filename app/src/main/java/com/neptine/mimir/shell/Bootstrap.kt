package com.neptine.mimir.shell

import android.os.Build
import android.os.Looper
import android.os.Process
import android.util.Log
import com.neptine.mimir.device.shell.MimirShellService
import com.neptine.mimir.device.shell.ShellContext
import kotlin.system.exitProcess

/**
 * 壳进程入口：由 adb shell 以 shell(uid 2000) 身份通过 `app_process` 拉起。
 *
 * 与 Shizuku 的 UserService 走的是同一条路（app_process + CLASSPATH 指向 APK），
 * 区别只在于谁来下发启动命令：这里是我们自己的 adb 通道。
 *
 * 调用约定：`CLASSPATH=<base.apk> app_process /system/bin --nice-name=<进程名>
 * com.neptine.mimir.shell.Bootstrap --mode=serve --proto=1 --token=… --apk=…`
 */
object Bootstrap {

    private const val TAG = "MimirBootstrap"

    /**
     * 功能：进程入口
     * 参数：args `--key=value` 形式的启动参数
     * 返回：无（serve 模式进入 Looper 循环，不返回）
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val opts = runCatching { ShellArgs.parse(args) }.getOrElse { error ->
            Log.e(TAG, "参数非法：${error.message}")
            exitProcess(2)
        }

        if (opts.mode == ShellArgs.MODE_SELFTEST) {
            selfTest(opts)
            return
        }

        // 必须在建 Context 之前：ActivityThread.systemMain() 要求主线程已有 Looper。
        Looper.prepareMainLooper()

        // 构造期会经 ShellContext 建 Context；构造失败不阻塞服务创建，原因随首次调用回传。
        val service = MimirShellService()
        Log.i(TAG, "serving pid=${Process.myPid()} uid=${Process.myUid()} proto=${opts.protocol}")

        val delivered = ShellDelivery(opts).start(service)
        if (!delivered) {
            // 交付不上就没有存在价值：留在后台只会和下一轮拉起的进程抢屏。
            Log.e(TAG, "交付失败，退出")
            exitProcess(1)
        }
        Looper.loop()
    }

    /**
     * 功能：自检——只验证 dex 能加载、进程拿到了 shell uid
     * 参数：opts 启动参数
     * 返回：无；打印一行固定前缀的结果后退出
     *
     * 不建 Context、不建屏：把"命令根本跑不起来"和"跑起来了但权限不对"分开，
     * 排障时先看这一行有没有出现。
     */
    private fun selfTest(opts: ShellArgs) {
        val apkOk = opts.apk.isNotEmpty() && java.io.File(opts.apk).isFile
        println(
            "MIMIR_SHELL_OK uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT} " +
                "apkOk=$apkOk proto=${opts.protocol}",
        )
        exitProcess(0)
    }
}
