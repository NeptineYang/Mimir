package com.neptine.mimir.agent

/**
 * 任务目标包的范围判定（设计文档 §1.2 前台校验 / §4.6 第 2 条）。
 *
 * 前台包名严格相等在真机上过于苛刻，会把两类正常情况判成"前台不在任务目标内"：
 * 1. App 把自己的功能拆到子包进程里（美团 → 美团外卖 `com.sankuai.meituan.takeoutnew`），
 *    点击后前台包名立刻不再是目标包，任务在第一步就被拦死；
 * 2. 首次启动弹系统权限框（`com.android.permissioncontroller` 等），
 *    不处理就永远进不去 App，拦下来只能让用户放弃。
 *
 * 因此范围放宽到"目标包 + 以目标包为前缀的子包 + 系统权限弹窗"，
 * 其余包名一律仍按越界处理。放宽的部分会在审计里写明实际命中的是哪个包。
 */
internal object PackageScope {

    /**
     * 功能：判断前台包名是否仍在任务范围内
     * 参数：target 任务目标包名；actual 实际前台包名
     * 返回：在范围内为 true；target 为空（未解析出目标应用）时恒为 false
     */
    fun isWithin(target: String, actual: String): Boolean {
        if (target.isEmpty() || actual.isEmpty()) return false
        return isTargetOrChild(target, actual) || isSystemDialog(actual)
    }

    /**
     * 功能：判断实际包名是目标包本身还是它的子包
     * 参数：target 任务目标包名；actual 实际前台包名
     * 返回：相等或以 "$target." 开头为 true
     */
    fun isTargetOrChild(target: String, actual: String): Boolean =
        target.isNotEmpty() && (actual == target || actual.startsWith("$target."))

    /**
     * 功能：判断包名是否为系统权限/安装弹窗
     * 参数：packageName 前台包名
     * 返回：命中已知系统弹窗为 true
     *
     * 清单只收"必须处理才能继续"的弹窗宿主，不把系统设置、SystemUI 这类整机界面算进来——
     * 放行它们等于允许 agent 在任意系统页面里乱点。清单之外的新弹窗仍会挂起，
     * 由人工接管后把实际包名补进这里，而不是靠猜。
     */
    fun isSystemDialog(packageName: String): Boolean = packageName in SYSTEM_DIALOG_PACKAGES

    /** 已知的系统权限/安装弹窗宿主包名。 */
    private val SYSTEM_DIALOG_PACKAGES = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.lbe.security.miui",
    )
}
