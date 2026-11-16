package com.neptine.mimir.device.shell

/**
 * 特权服务的会话抽象：只回答"有没有 binder"与"把 binder 给我"。
 *
 * 两套宿主（外置 Shizuku、自建壳进程）都实现它，端口类因此不需要知道自己跑在谁身上。
 */
interface ShellSession {

    /**
     * 功能：判断当前是否已有可用的 binder
     * 参数：无
     * 返回：可用为 true
     */
    fun isReady(): Boolean

    /**
     * 功能：取得服务代理
     * 参数：无
     * 返回：可用的服务代理；不可用时抛异常
     */
    suspend fun require(): IMimirShell
}
