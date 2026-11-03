package com.neptine.mimir.device

/**
 * 应用枚举与解析端口（设计文档 §2.2）。
 *
 * 用于把"打开美团"解析成具体包名；实现跑在 shell uid 下并声明 QUERY_ALL_PACKAGES。
 */
interface PackagePort {

    /**
     * 功能：列出可启动的第三方应用包名
     * 参数：无
     * 返回：包名列表
     */
    suspend fun launchablePackages(): List<String>

    /**
     * 功能：判断某包名是否可启动
     * 参数：packageName 目标包名
     * 返回：可启动为 true
     */
    suspend fun isLaunchable(packageName: String): Boolean
}
