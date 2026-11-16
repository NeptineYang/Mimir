package com.neptine.mimir.device.shell

import android.content.Context
import android.os.Process
import android.util.Log

/**
 * shell 进程内的 Context 获取（设计文档 §2.2）。
 *
 * Shizuku 的 UserService 由 app_process 直接实例化，没有 Application 也没有 Activity，
 * 因此拿不到普通 Context。做法是让 ActivityThread 以 system 身份建一个系统上下文。
 *
 * 关键一步：**不能直接用系统上下文去建虚拟屏**。DisplayManagerService 会校验
 * "包名必须属于调用 uid"，而系统上下文的包名是 android（uid 1000），调用者却是 shell(2000)，
 * 于是 createVirtualDisplay 直接抛 "packageName must match the owner uid"。
 * 因此再按当前 uid 反查其包名（shell → com.android.shell）建一个同属主上下文。
 *
 * 前提：调用线程必须已 prepareMainLooper（Shizuku starter 在 Looper.loop() 之前构造本服务）。
 */
internal object ShellContext {

    /** 诊断日志标签；shell 进程的日志与 app 进程同在 logcat，便于定位权限问题。 */
    private const val TAG = "MimirShell"

    /** 进程内唯一实例：ActivityThread.systemMain() 重复调用会互相踩，缓存一次即可。 */
    @Volatile
    private var cached: Context? = null

    /**
     * 功能：构造 shell 进程可用的、包名与当前 uid 匹配的 Context
     * 参数：无
     * 返回：归属当前 uid 的上下文；构造失败时抛异常，由调用方转成探针失败原因
     */
    fun create(): Context {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: build().also { cached = it }
        }
    }

    /**
     * 功能：实际构造 Context
     * 参数：无
     * 返回：归属当前 uid 的上下文
     */
    private fun build(): Context {
        val systemContext = createSystemContext()
        // 必须用 getPackagesForUid：getNameForUid 对 shell 返回的是共享 uid 名
        // "android.uid.shell:2000"，拿它去 createPackageContext 必然失败。
        val ownerPackage = systemContext.packageManager.getPackagesForUid(Process.myUid())?.firstOrNull()
            ?: return systemContext
        val ownerContext = runCatching { systemContext.createPackageContext(ownerPackage, 0) }
            .getOrDefault(systemContext)
        forceOpPackageName(ownerContext, ownerPackage)
        Log.i(
            TAG,
            "shell ctx uid=${Process.myUid()} ownerPackage=$ownerPackage " +
                "packageName=${ownerContext.packageName} opPackage=${ownerContext.opPackageName}",
        )
        return ownerContext
    }

    /**
     * 功能：把 Context 的"调用方包名"改成与当前 uid 匹配的包名
     * 参数：context 目标上下文；packageName 该 uid 所属包名（shell 是 com.android.shell）
     * 返回：无
     *
     * 为什么必须改：AMS 处理 `ContentResolver.call` 时校验"调用方包名是否属于调用方 uid"，
     * 系统上下文的包名是 android（uid 1000），而我们是 uid 2000，于是直接抛
     * `SecurityException: Given calling package android does not match caller's uid 2000`。
     *
     * Android 14 起 `getOpPackageName()` 读的是 `mAttributionSource.mAttributionSourceState.packageName`，
     * 只改 `mOpPackageName` 不够（实测无效），两处都要写。
     */
    private fun forceOpPackageName(context: Context, packageName: String) {
        runCatching {
            val contextClass = context.javaClass
            setField(contextClass, context, "mOpPackageName", packageName)
            setField(contextClass, context, "mBasePackageName", packageName)

            val attribution = contextClass.getDeclaredField("mAttributionSource").apply {
                isAccessible = true
            }.get(context) ?: return@runCatching
            val state = attribution.javaClass.getDeclaredField("mAttributionSourceState").apply {
                isAccessible = true
            }.get(attribution) ?: return@runCatching

            setField(state.javaClass, state, "packageName", packageName)
            // uid 也一起对齐：部分校验读的是 state 里的 uid 而不是 Binder 的。
            setField(state.javaClass, state, "uid", Process.myUid())
            Log.i(TAG, "调用方包名已改为 $packageName")
        }.onFailure { Log.w(TAG, "改调用方包名失败：${it.javaClass.simpleName} ${it.message}") }
    }

    /**
     * 功能：反射写一个字段，字段不存在时静默跳过
     * 参数：ownerClass 声明类；target 目标对象；name 字段名；value 新值
     * 返回：无
     */
    private fun setField(ownerClass: Class<*>, target: Any, name: String, value: Any) {
        runCatching {
            ownerClass.getDeclaredField(name).apply {
                isAccessible = true
                set(target, value)
            }
        }
    }

    /**
     * 功能：借 ActivityThread 建系统上下文
     * 参数：无
     * 返回：系统上下文
     */
    private fun createSystemContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
        return activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as Context
    }
}
