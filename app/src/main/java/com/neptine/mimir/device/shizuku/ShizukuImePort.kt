package com.neptine.mimir.device.shizuku

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
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
 * 两个用途：
 * 1. 任务执行期接管默认输入法，让主屏不再弹出可见键盘（自带 IME 不绘制键盘）；
 *    任务结束或转人工时 [restore] 切回用户原来的输入法。
 * 2. 文本输入的 commitText 回退路径，仍然受 P5 硬门约束（见 [com.neptine.mimir.agent.TextInput]）。
 *
 * 原始输入法 id 落 SharedPreferences：进程在任务中途被杀时，下次启动还能切回去，
 * 不会把用户手机留在一块没有键盘的状态。
 */
class ShizukuImePort(
    private val context: Context,
    private val shell: ShizukuShell,
) : ImePort {

    /** 切换前的输入法 id；restore 用它切回去。内存值优先，进程重启后从落盘值恢复。 */
    private var originalImeId: String? = null

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 功能：把自带输入法切为系统默认
     * 参数：无
     * 返回：切换是否成功
     */
    override suspend fun select(): Boolean = withContext(Dispatchers.IO) {
        val service = shell.require()
        val mine = mineImeId()
        val current = service.currentImeId()
        if (current == mine) return@withContext true

        // 先落盘再切换：切换成功后进程若被杀，下次启动才有东西可恢复。
        // 记录前先折算：把 ADB Keyboard 这类测试输入法记成"原始输入法"，
        // 恢复时会把主屏锁在没有键盘的状态（真机踩到过）。
        originalImeId = restoreTargetFor(current).takeIf { it.isNotEmpty() }
        originalImeId?.let { prefs.edit().putString(KEY_ORIGINAL, it).apply() }
        service.setImeEnabled(mine, true) && service.setIme(mine)
    }

    /** 当前默认输入法是否已经是自带的无键盘输入法。 */
    override fun isActive(): Boolean = isMineActive()

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
        val saved = originalImeId ?: prefs.getString(KEY_ORIGINAL, null) ?: return@withContext
        val original = restoreTargetFor(saved)
        val service = shell.require()
        service.setIme(original)
        service.setImeEnabled(mineImeId(), false)
        originalImeId = null
        prefs.edit().remove(KEY_ORIGINAL).apply()
    }

    /**
     * 功能：进程重启后清理上次任务遗留的输入法接管
     * 参数：无
     * 返回：无
     *
     * 只读 Settings.Secure 判断当前默认输入法是不是自己，命中才去绑定 shell；
     * 不是自己就什么都不做，避免冷启动无谓拉起 UserService。
     */
    override suspend fun restoreIfLeftOver() {
        if (!isMineActive()) {
            prefs.edit().remove(KEY_ORIGINAL).apply()
            return
        }
        runCatching { restore() }
    }

    /** 当前默认输入法是否是 Mimir 自带的那一个（读 secure 设置，不需要 Shizuku）。 */
    private fun isMineActive(): Boolean = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD,
    ) == mineImeId()

    /** 自带输入法的组件 id。 */
    private fun mineImeId(): String =
        ComponentName(context, MimirInputMethodService::class.java).flattenToShortString()

    /**
     * 功能：把记录下来的输入法折算成真正可恢复的目标
     * 参数：id 切换前读到的默认输入法 id
     * 返回：可恢复的 id；找不到替代品时原样返回
     *
     * ADB Keyboard 这类输入法没有可见键盘，只用于自动化注入文本：
     * 它一旦被当成"用户原来的输入法"恢复回去，主屏就再也打不了字。
     * 因此遇到它时改取一个已启用的普通输入法（排除它自己与本应用）。
     */
    private fun restoreTargetFor(id: String): String {
        if (id.isEmpty() || !isTestOnlyIme(id)) return id
        val fallback = enabledImeIds().firstOrNull { !isTestOnlyIme(it) && it != mineImeId() }
        if (fallback != null) {
            Log.w(TAG, "记录的输入法是测试输入法 $id，恢复目标改为 $fallback")
        }
        return fallback ?: id
    }

    /** 当前已启用的输入法 id 列表；读不到时为空。 */
    private fun enabledImeIds(): List<String> {
        val manager = context.getSystemService(InputMethodManager::class.java) ?: return emptyList()
        return manager.enabledInputMethodList.map { it.id }
    }

    /** 是否是只用于自动化的无键盘输入法（用户拿它无法打字）。 */
    private fun isTestOnlyIme(id: String): Boolean = id.startsWith(TEST_ONLY_IME_PREFIX)

    private companion object {
        /** 接管状态落盘的文件名与键：只存原始输入法 id，不放任何任务内容。 */
        const val PREFS_NAME = "mimir_ime_state"
        const val KEY_ORIGINAL = "original_ime_id"

        /** ADB Keyboard 的包名前缀；它不绘制键盘，不能作为恢复目标。 */
        const val TEST_ONLY_IME_PREFIX = "com.android.adbkeyboard/"

        const val TAG = "MimirIme"
    }
}
