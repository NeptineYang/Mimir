package com.neptine.mimir.device.shell

import android.os.Process
import android.util.Log
import java.io.File

/**
 * shell 侧遗留进程清理。
 *
 * 真机实测：装包（或换 UserService 版本）后 Shizuku 会为新客户端再起一个
 * `com.neptine.mimir:shell` 进程，旧进程不会自己退出。虚拟屏对象归旧进程所有，
 * 新进程拿不到它的句柄，于是那块屏永远回收不掉——反复若干次就会耗尽虚拟屏资源，
 * 只能重启手机恢复。
 *
 * 旧进程与新进程同属 shell uid，因此新进程可以直接把它结束；进程一死，
 * 它持有的虚拟屏由系统释放。只在"新进程首次被调用"时执行一次。
 */
internal object ShellJanitor {

    /**
     * 功能：结束同包的其它 shell 进程
     * 参数：无
     * 返回：结束的进程数
     *
     * 只按"进程名完全等于本包 shell 进程名"匹配，且跳过自己：
     * 同一时刻本应用只有一个主进程，因此其它同名 shell 进程必然是上一轮留下的。
     */
    fun killSiblings(): Int {
        val self = Process.myPid()
        var killed = 0
        runCatching {
            File("/proc").listFiles()?.forEach { entry ->
                val pid = entry.name.toIntOrNull() ?: return@forEach
                if (pid == self) return@forEach
                val name = runCatching {
                    File(entry, "cmdline").readText().trim('\u0000', '\n', ' ')
                }.getOrNull()
                if (name != PROCESS_NAME) return@forEach
                if (runCatching { Process.killProcess(pid) }.isSuccess) killed++
            }
        }.onFailure { Log.w(TAG, "清理遗留 shell 进程失败：${it.message}") }
        if (killed > 0) Log.i(TAG, "清理遗留 shell 进程 $killed 个")
        return killed
    }

    private const val TAG = "MimirShell"

    /** 本应用 shell UserService 的进程名（与 dumpsys/ps 中的显示一致）。 */
    const val PROCESS_NAME = "com.neptine.mimir:shell"
}
