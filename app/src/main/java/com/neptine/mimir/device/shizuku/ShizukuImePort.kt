package com.neptine.mimir.device.shizuku

import android.content.ComponentName
import android.content.Context
import com.neptine.mimir.device.ImePort
import com.neptine.mimir.service.MimirInputMethodService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文本输入端口的 Shizuku 实现（设计文档 §2.2 / §4.7）。
 *
 * 切换走 shell 进程的 `ime set`；提交文本走本进程的 [MimirInputMethodService]，
 * 因为 commitText 必须发生在持有 InputConnection 的那个进程。
 *
 * 硬门：P5 未通过前上层不得调用 select()，否则就是在真实任务里改用户默认输入法。
 */
class ShizukuImePort(
    private val context: Context,
    private val shell: ShizukuShell,
) : ImePort {

    /** 切换前的输入法 id；restore 用它切回去。 */
    private var originalImeId: String? = null

    /**
     * 功能：把自带输入法切为系统默认
     * 参数：无
     * 返回：切换是否成功
     */
    override suspend fun select(): Boolean = withContext(Dispatchers.IO) {
        val service = shell.require()
        val mine = ComponentName(context, MimirInputMethodService::class.java).flattenToShortString()
        val current = service.currentImeId()
        if (current == mine) return@withContext true

        originalImeId = current.ifEmpty { null }
        service.setImeEnabled(mine, true) && service.setIme(mine)
    }

    /**
     * 功能：向当前焦点输入框提交文本
     * 参数：text 待提交的 Unicode 文本
     * 返回：无；输入法未启动或提交失败时抛异常
     */
    override suspend fun commitText(text: String) {
        withContext(Dispatchers.Main) {
            val service = MimirInputMethodService.instance
                ?: throw IllegalStateException("自带输入法未运行，无法提交文本")
            check(service.commitText(text)) { "当前没有可用的输入连接" }
        }
    }

    /**
     * 功能：恢复用户原本的输入法
     * 参数：无
     * 返回：无
     */
    override suspend fun restore(): Unit = withContext(Dispatchers.IO) {
        val original = originalImeId ?: return@withContext
        val service = shell.require()
        service.setIme(original)
        val mine = ComponentName(context, MimirInputMethodService::class.java).flattenToShortString()
        service.setImeEnabled(mine, false)
        originalImeId = null
    }
}
