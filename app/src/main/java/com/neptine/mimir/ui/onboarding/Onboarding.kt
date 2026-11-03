package com.neptine.mimir.ui.onboarding

import android.content.Context

/**
 * 首次使用的四步引导标记。
 *
 * 只存一个布尔值：引导是否已经完成或被用户明确跳过。用独立 SharedPreferences 而不是
 * 记忆库：它描述的是"这台设备上的这个安装有没有走过引导"，既不是配方，也不该被导出。
 */
object Onboarding {

    /** 引导标记文件；与业务数据完全隔离。 */
    private const val PREFS = "mimir_onboarding"

    /** 标记键：引导已结束。 */
    private const val KEY_DONE = "guide_done"

    /**
     * 功能：判断是否仍需要展示引导
     * 参数：context 任意上下文
     * 返回：需要展示为 true
     */
    fun needsGuide(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_DONE, false)

    /**
     * 功能：标记引导已结束
     * 参数：context 任意上下文
     * 返回：无
     *
     * 完成与"跳过"都写同一个标记：两者都是用户的明确决定，重启后再弹一次只会让人烦。
     */
    fun markDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_DONE, true).apply()
    }

    /** 取引导专用的 SharedPreferences。 */
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
