package com.neptine.mimir.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.neptine.mimir.ui.screen.AuditEventScreen
import com.neptine.mimir.ui.screen.AuditScreen
import com.neptine.mimir.ui.screen.AuditTaskScreen
import com.neptine.mimir.ui.screen.CredentialScreen
import com.neptine.mimir.ui.screen.MemoryScreen
import com.neptine.mimir.ui.screen.ScheduledTaskScreen
import com.neptine.mimir.ui.screen.SettingsScreen
import com.neptine.mimir.ui.screen.TakeoverScreen
import com.neptine.mimir.ui.onboarding.Onboarding
import com.neptine.mimir.ui.screen.HistoryScreen
import com.neptine.mimir.ui.screen.TaskScreen

/** 任务页：输入自然语言任务并创建。 */
const val ROUTE_TASK = "task"

/** 历史页：已结束任务的记录与审计入口。 */
const val ROUTE_HISTORY = "history"

/** 配置页：权限、模型与设备能力。 */
const val ROUTE_SETTINGS = "settings"

/** 记忆页：配方与策略的白盒编辑，侧边栏一级模块。 */
const val ROUTE_MEMORY = "memory"

/** 凭据页：普通登录密码的加密存储，侧边栏一级模块。 */
const val ROUTE_CREDENTIALS = "credentials"

/** 定时任务页：设定时间让 Agent 自动执行，全屏展示、不挂底栏。 */
const val ROUTE_SCHEDULED = "scheduled"

/** 路由参数名：任务标识。审计回放与接管页共用。 */
const val ARG_TASK_ID = "taskId"

/** 路由参数名：审计事件主键。 */
const val ARG_EVENT_ID = "eventId"

/** 审计首页：高危操作事件流。 */
const val ROUTE_AUDIT = "audit"

/** 审计事件详情：单条事件的证据。 */
const val ROUTE_AUDIT_EVENT = "audit/event/{$ARG_EVENT_ID}"

/** 按任务回放：完整时间轴（带可选任务参数）。 */
const val ROUTE_AUDIT_TASK = "audit/task?$ARG_TASK_ID={$ARG_TASK_ID}"

/** 接管页的注册路由（带可选参数），全屏展示、不挂底栏。 */
const val ROUTE_TAKEOVER = "takeover?$ARG_TASK_ID={$ARG_TASK_ID}"

/**
 * 功能：拼出审计事件详情的导航目标串
 * 参数：eventId 审计行主键
 * 返回：可直接交给 NavController 的路由串
 */
fun routeAuditEvent(eventId: Long): String = "audit/event/$eventId"

/**
 * 功能：拼出"按任务回放"的导航目标串
 * 参数：taskId 任务标识；为空表示进入"选择任务"态
 * 返回：可直接交给 NavController 的路由串
 */
fun routeAuditTask(taskId: String? = null): String =
    if (taskId == null) "audit/task" else "audit/task?$ARG_TASK_ID=$taskId"

/**
 * 功能：拼出接管页的导航目标串
 * 参数：taskId 任务标识；为空表示无指定任务
 * 返回：可直接交给 NavController 的路由串
 */
fun routeTakeover(taskId: String? = null): String =
    if (taskId == null) "takeover" else "takeover?$ARG_TASK_ID=$taskId"

/**
 * 功能：注册应用内导航图
 * 参数：navController 导航控制器；modifier 修饰符，由外层 Scaffold 传入内边距
 * 返回：无
 */
@Composable
fun MimirNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    // 首次安装先落在设置页：四步引导在那里，先让用户配好再回任务页。
    // 引导完成或跳过后写入独立偏好，下一次启动恢复从任务页开始。
    val context = LocalContext.current
    val startRoute = remember { if (Onboarding.needsGuide(context)) ROUTE_SETTINGS else ROUTE_TASK }

    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier,
    ) {
        composable(ROUTE_TASK) {
            TaskScreen(
                onOpenTakeover = { taskId -> navController.navigate(routeTakeover(taskId)) },
                onOpenSettings = { navController.navigateToTab(MimirTab.SETTINGS) },
            )
        }
        composable(ROUTE_HISTORY) {
            HistoryScreen(
                onCreateTask = { navController.navigateToTab(MimirTab.TASK) },
                onOpenAudit = { taskId -> navController.navigate(routeAuditTask(taskId)) },
            )
        }
        composable(ROUTE_AUDIT) {
            AuditScreen(
                onOpenEvent = { eventId -> navController.navigate(routeAuditEvent(eventId)) },
                onOpenTaskReplay = { navController.navigate(routeAuditTask()) },
            )
        }
        composable(
            route = ROUTE_AUDIT_EVENT,
            arguments = listOf(eventIdArgument()),
        ) {
            AuditEventScreen(
                onBack = { navController.popBackStack() },
                onOpenTask = { taskId -> navController.navigate(routeAuditTask(taskId)) },
            )
        }
        composable(
            route = ROUTE_AUDIT_TASK,
            arguments = listOf(taskIdArgument()),
        ) {
            AuditTaskScreen(
                onBack = { navController.popBackStack() },
                onSelectTask = { taskId -> navController.navigate(routeAuditTask(taskId)) },
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen()
        }
        composable(ROUTE_MEMORY) {
            MemoryScreen()
        }
        composable(ROUTE_CREDENTIALS) {
            CredentialScreen()
        }
        composable(ROUTE_SCHEDULED) {
            ScheduledTaskScreen()
        }
        composable(
            route = ROUTE_TAKEOVER,
            arguments = listOf(taskIdArgument()),
        ) {
            TakeoverScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * 功能：构造可选的任务标识路由参数
 * 参数：无
 * 返回：可空、默认值为 null 的导航参数声明
 */
private fun taskIdArgument() = navArgument(ARG_TASK_ID) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

/**
 * 功能：构造审计事件主键路由参数
 * 参数：无
 * 返回：Long 型、必填的导航参数声明
 */
private fun eventIdArgument() = navArgument(ARG_EVENT_ID) {
    type = NavType.LongType
}
