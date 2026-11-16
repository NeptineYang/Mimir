package com.neptine.mimir.device.selfhost

import android.content.Context
import android.os.IBinder
import android.util.Log
import com.neptine.mimir.device.shell.IMimirShell
import com.neptine.mimir.device.shell.ShellSession
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 自建壳进程的 binder 持有者。
 *
 * 与 Shizuku 那条路的最大差别：binder 不再由外部库递进来，而是壳进程通过
 * [com.neptine.mimir.device.shell.BridgeProvider] 主动 attach 过来；本类负责校验后的
 * 缓存、死亡监听与就绪状态广播。
 */
@Singleton
class ShellBridge @Inject constructor(
    @ApplicationContext private val context: Context,
) : ShellSession {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val mutex = Mutex()

    @Volatile
    private var cached: IMimirShell? = null

    @Volatile
    private var cachedPid: Int = -1

    private val _ready = MutableStateFlow(false)

    /** 供界面与路由订阅的就绪状态。 */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * 功能：取当前握手令牌（app 侧唯一真相）
     * 参数：无
     * 返回：上次启动时生成的令牌；从未启动过为空串
     */
    fun currentToken(): String = prefs.getString(KEY_TOKEN, "").orEmpty()

    /**
     * 功能：生成并持久化新的握手令牌
     * 参数：无
     * 返回：32 位十六进制令牌
     *
     * 持久化是为了让"壳进程活着、app 进程重启"的场景仍能重连；
     * 每次主动启动都会换成新的，旧壳进程 attach 时会被 provider 拒绝并退出。
     */
    fun newToken(): String = UUID.randomUUID().toString().replace("-", "").also {
        prefs.edit().putString(KEY_TOKEN, it).apply()
    }

    /**
     * 功能：壳进程交付成功后的登记（在 provider 的 binder 线程调用）
     * 参数：raw 壳进程 binder；pid 其进程号
     * 返回：true 表示接受；false 表示已有活跃实例，请调用方让新来的退出
     *
     * 令牌是持久化的，同一个令牌能让两个进程都通过校验——实测出现过两个
     * `:privileged` 同时存活、互相抢绑定。这里以"已有活跃 binder 就拒绝新来的"兜住。
     */
    fun onAttached(raw: IBinder, pid: Int): Boolean {
        val existing = cached
        if (existing != null && existing.asBinder().isBinderAlive && cachedPid != pid) {
            Log.w(TAG, "拒绝重复接入：已有活跃实例 pid=$cachedPid，新来者 pid=$pid")
            return false
        }
        // 死亡监听要尽早挂：attach 与进程猝死的窗口很小，但真的存在。
        runCatching {
            raw.linkToDeath({ onDied(pid) }, 0)
        }.getOrElse {
            Log.w(TAG, "linkToDeath 失败：${it.message}")
            return false
        }
        synchronized(this) {
            cached = IMimirShell.Stub.asInterface(raw)
            cachedPid = pid
        }
        _ready.value = true
        Log.i(TAG, "壳进程已接入 pid=$pid")
        return true
    }

    /**
     * 功能：壳进程死亡时清空缓存
     * 参数：pid 死亡进程号
     * 返回：无
     *
     * 只清"自己这一代"：并发拉起时旧进程的死亡回调可能晚于新进程的接入，
     * 不做代次判断会把刚接好的 binder 清掉。
     */
    private fun onDied(pid: Int) {
        synchronized(this) {
            if (cachedPid == pid) {
                cached = null
                cachedPid = -1
            }
        }
        _ready.value = cached != null
        Log.i(TAG, "壳进程已断开 pid=$pid")
    }

    /**
     * 功能：判断是否已有可用 binder
     * 参数：无
     * 返回：可用为 true
     */
    override fun isReady(): Boolean = cached?.asBinder()?.isBinderAlive == true

    /**
     * 功能：取服务代理
     * 参数：无
     * 返回：服务代理；未接入时抛异常
     */
    override suspend fun require(): IMimirShell = mutex.withLock {
        cached ?: error("特权服务未启动：请先在设置页启动特权服务")
    }

    /**
     * 功能：取当前安装包的 APK 路径，用于校验壳进程是否跑在旧版本上
     * 参数：无
     * 返回：base.apk 绝对路径
     */
    fun apkPath(): String =
        context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir

    private companion object {
        const val TAG = "ShellBridge"

        /** 令牌单独存，避免与 adb 密钥混在一个文件里。 */
        const val PREFS_NAME = "mimir_shell"

        const val KEY_TOKEN = "attach_token"
    }
}
