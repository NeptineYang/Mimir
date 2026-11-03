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

    /**
     * 功能：构造 shell 进程可用的、包名与当前 uid 匹配的 Context
     * 参数：无
     * 返回：归属当前 uid 的上下文；构造失败时抛异常，由调用方转成探针失败原因
     */
    fun create(): Context {
        val systemContext = createSystemContext()
        // 必须用 getPackagesForUid：getNameForUid 对 shell 返回的是共享 uid 名
        // "android.uid.shell:2000"，拿它去 createPackageContext 必然失败。
        val ownerPackage = systemContext.packageManager.getPackagesForUid(Process.myUid())?.firstOrNull()
            ?: return systemContext
        val ownerContext = runCatching { systemContext.createPackageContext(ownerPackage, 0) }
            .getOrDefault(systemContext)
        Log.i(
            TAG,
            "shell ctx uid=${Process.myUid()} ownerPackage=$ownerPackage " +
                "packageName=${ownerContext.packageName} opPackage=${ownerContext.opPackageName}",
        )
        return ownerContext
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
