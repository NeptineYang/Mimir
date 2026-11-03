package com.neptine.mimir.data.memory

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 目标 App 版本号读取（记忆的"版本触发器"要用）。
 *
 * 单独成类而不是各自复制三行：检索与写入都要用同一个口径——读不到就返回 0，
 * 调用方按"版本未知"处理（版本系数 1.0），不能因为读不到就把记忆判死。
 */
@Singleton
class AppVersionReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 功能：读目标 App 的当前版本号
     * 参数：packageName 包名
     * 返回：longVersionCode；读不到时为 0
     */
    fun versionCodeOf(packageName: String): Int = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)
}
