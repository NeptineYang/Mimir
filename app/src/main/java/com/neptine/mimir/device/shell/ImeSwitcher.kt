package com.neptine.mimir.device.shell

/**
 * 输入法切换（设计文档 §2.2）。
 *
 * 走 /system/bin/ime 脚本而不是硬编码 binder transaction code：
 * 参考实现用 transact(1598246212, ...) 这类魔法数字，跨 Android 版本必碎。
 *
 * 硬门提醒：`ime set` 改的是系统默认输入法，P5 未通过前上层不得调用 select()。
 */
internal class ImeSwitcher {

    /**
     * 功能：启用或停用某个输入法
     * 参数：imeId 输入法组件 id；enabled 是否启用
     * 返回：命令是否成功
     */
    fun setEnabled(imeId: String, enabled: Boolean): Boolean =
        run("ime", if (enabled) "enable" else "disable", imeId) != null

    /**
     * 功能：把某个输入法切换为系统默认
     * 参数：imeId 输入法组件 id
     * 返回：命令是否成功
     */
    fun set(imeId: String): Boolean = run("ime", "set", imeId) != null

    /**
     * 功能：读取当前系统默认输入法
     * 参数：无
     * 返回：输入法组件 id；读取失败返回空串
     */
    fun current(): String = run("settings", "get", "secure", "default_input_method")?.trim().orEmpty()

    /**
     * 功能：执行一条 shell 命令并回收进程
     * 参数：vararg args 命令与参数
     * 返回：标准输出；退出码非 0 时返回 null
     */
    private fun run(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(*args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
}
