package com.neptine.mimir.ui

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 已装应用的展示条目。
 *
 * @property packageName 应用 ID；只在保存与绑定时内部使用，不在界面展示
 * @property label 桌面显示名
 */
data class AppEntry(
    val packageName: String,
    val label: String,
)

/**
 * 功能：取包名对应的软件名
 * 参数：context 上下文；packageName 应用 ID
 * 返回：桌面显示名；应用未安装或读取失败时回落包名
 *
 * 回落包名只在"应用已卸载"时出现，用来解释绑定为什么失效，正常路径不会展示。
 */
fun appLabelOf(context: Context, packageName: String): String {
    if (packageName.isBlank()) return ""
    return runCatching {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

/**
 * 功能：列出可启动的应用
 * 参数：context 上下文
 * 返回：按软件名排序、同包去重的应用条目
 *
 * 用 MAIN/LAUNCHER 查询而不是全部已装应用：配方与凭据绑定的是"能打开、能被 Agent 操作"的 App。
 * 查询放在 IO 线程，避免首次打开选择器时卡住界面。
 */
suspend fun launchableApps(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
    val manager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    runCatching {
        manager.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = runCatching {
                    manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                AppEntry(packageName = pkg, label = label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}
