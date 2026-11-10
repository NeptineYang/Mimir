package com.neptine.mimir.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.neptine.mimir.ui.component.MimirIcons

/**
 * 侧边栏里的四个一级页面。
 *
 * @property label 侧边栏文字，中文直接可读
 * @property icon Lucide 线性图标
 * @property destinationRoute 该页在导航图里的注册路由（可能是带占位符的模式串）
 * @property targetRoute 点击时真正导航的目标串
 * @property description 无障碍朗读用的完整说明
 */
enum class MimirTab(
    val label: String,
    val icon: ImageVector,
    val destinationRoute: String,
    val targetRoute: String,
    val description: String,
) {
    TASK("任务", MimirIcons.Task, ROUTE_TASK, ROUTE_TASK, "任务：创建并查看进行中的任务"),
    HISTORY("历史", MimirIcons.History, ROUTE_HISTORY, ROUTE_HISTORY, "历史：已结束的任务与审计步骤"),
    AUDIT("审计", MimirIcons.Audit, ROUTE_AUDIT, ROUTE_AUDIT, "审计：需要人确认或已被阻断的操作"),
    MEMORY("记忆", MimirIcons.Memory, ROUTE_MEMORY, ROUTE_MEMORY, "记忆：配方与偏好策略的白盒编辑"),
    CREDENTIALS("凭据", MimirIcons.Key, ROUTE_CREDENTIALS, ROUTE_CREDENTIALS, "凭据：登录密码的加密存储"),
    SCHEDULED("定时", MimirIcons.Clock, ROUTE_SCHEDULED, ROUTE_SCHEDULED, "定时：按设定时间自动执行任务"),
    SETTINGS("设置", MimirIcons.Settings, ROUTE_SETTINGS, ROUTE_SETTINGS, "设置：权限、模型与设备能力"),
    ;

    /**
     * 功能：判断某个已注册路由是否属于本页
     * 参数：route 导航目的地注册的路由串
     * 返回：属于本页为 true
     */
    fun matches(route: String?): Boolean = route == destinationRoute
}
