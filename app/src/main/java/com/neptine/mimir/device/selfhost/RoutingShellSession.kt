package com.neptine.mimir.device.selfhost

import android.content.Context
import com.neptine.mimir.device.shell.IMimirShell
import com.neptine.mimir.device.shell.ShellSession
import com.neptine.mimir.device.shizuku.ShizukuShell
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 两套宿主的仲裁者。
 *
 * 路由规则：自建壳进程就绪就优先用它，否则退到外置 Shizuku。
 * 优先自建是因为它不依赖第三方应用、授权由本应用自己掌握；保留 Shizuku 只是为了
 * 在自建通道被 ROM 拦住时有条能用的退路。
 *
 * 注意：两者的进程名相同（`com.neptine.mimir:shell`），同一时刻只能有一个存活——
 * 所以启动自建壳进程前必须确保 Shizuku 那份没有在跑，否则会互相被 ShellJanitor 清掉。
 */
@Singleton
class RoutingShellSession @Inject constructor(
    @ApplicationContext context: Context,
    private val selfHost: ShellBridge,
) : ShellSession {

    private val shizuku = ShizukuShell(context)

    /**
     * 功能：判断当前是否有可用 binder
     * 参数：无
     * 返回：自建或 Shizuku 任一就绪为 true
     */
    override fun isReady(): Boolean = selfHost.isReady() || shizuku.isReady()

    /**
     * 功能：取服务代理，优先自建通道
     * 参数：无
     * 返回：可用的服务代理
     */
    override suspend fun require(): IMimirShell =
        if (selfHost.isReady()) selfHost.require() else shizuku.require()
}
