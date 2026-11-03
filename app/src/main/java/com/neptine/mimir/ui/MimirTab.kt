package com.neptine.mimir.ui

/**
 * 底部导航的四个一级页面。
 *
 * @property code 两位编号，视觉上替代图标
 * @property label 大写英文标签，等宽渲染
 * @property destinationRoute 该页在导航图里的注册路由（可能是带占位符的模式串）
 * @property targetRoute 点击时真正导航的目标串
 * @property description 中文说明，只进无障碍朗读
 */
enum class MimirTab(
    val code: String,
    val label: String,
    val destinationRoute: String,
    val targetRoute: String,
    val description: String,
) {
    TASK("01", "TASK", ROUTE_TASK, ROUTE_TASK, "任务"),
    HISTORY("02", "HISTORY", ROUTE_HISTORY, ROUTE_HISTORY, "历史"),
    AUDIT("03", "AUDIT", ROUTE_AUDIT, ROUTE_AUDIT_ENTRY, "审计"),
    SETTINGS("04", "SETUP", ROUTE_SETTINGS, ROUTE_SETTINGS, "设置"),
    ;

    /**
     * 功能：判断某个已注册路由是否属于本页
     * 参数：route 导航目的地注册的路由串
     * 返回：属于本页为 true
     */
    fun matches(route: String?): Boolean = route == destinationRoute
}
