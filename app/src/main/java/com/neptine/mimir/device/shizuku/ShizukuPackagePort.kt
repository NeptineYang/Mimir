package com.neptine.mimir.device.shizuku

import com.neptine.mimir.device.PackagePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用枚举端口的 Shizuku 实现（设计文档 §2.2）。
 *
 * 跑在 shell 进程：shell uid 天然可枚举全部已装应用，
 * 不依赖 app 侧的 QUERY_ALL_PACKAGES 声明是否被 ROM 收紧。
 */
class ShizukuPackagePort(private val shell: ShizukuShell) : PackagePort {

    /**
     * 功能：列出可启动的第三方应用包名
     * 参数：无
     * 返回：包名列表
     */
    override suspend fun launchablePackages(): List<String> = withContext(Dispatchers.IO) {
        shell.require().launchablePackages().toList()
    }

    /**
     * 功能：判断某包名是否可启动
     * 参数：packageName 目标包名
     * 返回：可启动为 true
     */
    override suspend fun isLaunchable(packageName: String): Boolean =
        packageName in launchablePackages()
}
