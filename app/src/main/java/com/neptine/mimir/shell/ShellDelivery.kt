package com.neptine.mimir.shell

import android.content.Context
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.neptine.mimir.device.shell.IMimirShell
import com.neptine.mimir.device.shell.ShellJanitor
import com.neptine.mimir.device.shell.ShellContext
import com.neptine.mimir.device.shell.ShellProtocol
import rikka.hidden.compat.ActivityManagerApis

/**
 * 把壳进程的 binder 交给 app 进程。
 *
 * 传递方式与 Shizuku 一致：壳进程没有 ContentResolver 之外的通道能带上 IBinder，
 * 因此走 app 的 ContentProvider `call()`，把 binder 放进 Bundle 里递过去。
 * 安全边界由 provider 的 `android:permission="INTERACT_ACROSS_USERS_FULL"` 与
 * 令牌校验共同保证（见 BridgeProvider）。
 */
class ShellDelivery(private val opts: ShellArgs) {

    /**
     * 功能：启动交付流程（阻塞重试若干次后返回，调用方随后进入 Looper 循环）
     * 参数：service 壳进程的服务实现
     * 返回：无
     */
    fun start(service: IMimirShell.Stub): Boolean {
        // 上一代壳在 app 进程更换后会变成孤儿（binder 断了但进程还在），
        // 它会一直占着一块虚拟屏。新壳一启动就把同名兄弟清掉。
        ShellJanitor.killSiblings()

        val context = runCatching { ShellContext.create() }.getOrElse { error ->
            Log.e(TAG, "建 Context 失败，放弃交付：${error.message}")
            return false
        }
        val authority = "${opts.pkg}${ShellProtocol.AUTHORITY_SUFFIX}"

        repeat(ATTEMPTS) { attempt ->
            if (attach(context, authority, service)) {
                Log.i(TAG, "attach 成功（第 ${attempt + 1} 次）")
                return true
            }
            Thread.sleep(RETRY_GAP_MS)
        }
        Log.w(TAG, "attach 未成功：app 可能不在前台或 provider 不可达")
        return false
    }

    /**
     * 功能：执行一次交付
     * 参数：context 壳进程上下文；uri 目标 provider；service 服务实现
     * 返回：app 接受为 true
     */
    private fun attach(context: Context, authority: String, service: IMimirShell.Stub): Boolean {
        val extras = Bundle().apply {
            putBinder(ShellProtocol.KEY_BINDER, service.asBinder())
            putString(ShellProtocol.KEY_TOKEN, opts.token)
            putInt(ShellProtocol.KEY_PROTOCOL, opts.protocol)
            putString(ShellProtocol.KEY_APK, opts.apk)
            putInt(ShellProtocol.KEY_PID, Process.myPid())
            putLong(ShellProtocol.KEY_STARTED, opts.startedAt)
        }
        return runCatching {
            val reply = callProvider(context, authority, extras)
            val accepted = reply?.getBoolean(ShellProtocol.KEY_ACCEPTED) == true
            if (!accepted) {
                Log.w(TAG, "attach 被拒：${reply?.getString(ShellProtocol.KEY_REJECT)}")
            }
            accepted
        }.getOrElse { error ->
            // 反射调用会把真正原因包在 InvocationTargetException 里，必须取 cause 才有用。
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            Log.w(TAG, "attach 异常：${cause.javaClass.simpleName} ${cause.message}")
            false
        }
    }

    /**
     * 功能：把载荷送到 app 的 provider，拿回执
     * 参数：context 壳进程上下文；authority 目标 provider；extras 载荷
     * 返回：回执 Bundle
     *
     * 不能用 ContentResolver：裸 app_process 在 AMS 里没有 app 记录，
     * `getContentProvider` 会抛 "Unable to find app for caller"。只能走
     * `getContentProviderExternal`（Shizuku 用同一条路），再直接调 IContentProvider。
     */
    private fun callProvider(context: Context, authority: String, extras: Bundle): Bundle? {
        val provider = ActivityManagerApis.getContentProviderExternal(authority, 0, null, authority)
            ?: error("拿不到 provider：$authority")

        // 不同系统版本的 IContentProvider#call 重载不同：Android 12+ 是
        // call(AttributionSource, String, String, Bundle)，更早是 call(String, ...)。
        // 运行时按参数类型挑，避免把版本判断写死。
        // 实测 Android 16 上 IContentProvider 同时有两套 call：
        //   (AttributionSource, String authority, String method, String arg, Bundle)
        //   (String callingPkg, String method, String arg, Bundle)
        // 优先用前者（系统正在走的路）。第二个参数是 authority，传错会被
        // ContentProviderProxy 以 "The authority null does not match …" 拒掉。
        val methods = provider.javaClass.methods.filter { it.name == "call" }
        val attributionCall = methods.firstOrNull {
            it.parameterTypes.firstOrNull()?.name == "android.content.AttributionSource"
        }
        if (attributionCall != null) {
            val args = arrayOfNulls<Any?>(attributionCall.parameterTypes.size)
            args[0] = context.attributionSource
            args[1] = authority
            args[attributionCall.parameterTypes.size - 3] = ShellProtocol.METHOD_ATTACH
            args[attributionCall.parameterTypes.size - 2] = null
            args[attributionCall.parameterTypes.size - 1] = extras
            return attributionCall.invoke(provider, *args) as? Bundle
        }
        return provider.call(context.packageName, ShellProtocol.METHOD_ATTACH, null, extras)
    }

    private companion object {
        const val TAG = "MimirDelivery"

        /** 交付重试次数与间隔：app 可能刚好在重建，留出约 2 秒窗口。 */
        const val ATTEMPTS = 5
        const val RETRY_GAP_MS = 400L
    }
}
