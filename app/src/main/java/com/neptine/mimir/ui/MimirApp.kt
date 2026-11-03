package com.neptine.mimir.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.neptine.mimir.ui.component.MimirTabBar
import com.neptine.mimir.ui.theme.LocalMimirColors

/**
 * 功能：装配整个应用的骨架（脚手架 + 底栏 + 导航图）
 * 参数：pendingTakeover 交接通知带来的任务标识；onTakeoverHandled 跳转完成后的清理回调
 * 返回：无
 *
 * 底栏的显隐由当前目的地决定：四个一级页显示，接管页不显示。
 * 逻辑放在 NavHost 之外，因为底栏高度会参与 Scaffold 的内边距计算，
 * 若在 NavHost 内部判断，接管页会白留 56dp 空档。
 */
@Composable
fun MimirApp(
    pendingTakeover: State<String?>,
    onTakeoverHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = MimirTab.entries.firstOrNull { it.matches(currentRoute) }

    // 点「我来处理」时先跳接管页；清理标识，避免返回后又被重新跳转
    val takeoverTaskId by pendingTakeover
    LaunchedEffect(takeoverTaskId) {
        val taskId = takeoverTaskId ?: return@LaunchedEffect
        navController.navigate(routeTakeover(taskId)) { launchSingleTop = true }
        onTakeoverHandled()
    }

    Scaffold(
        containerColor = LocalMimirColors.current.canvas,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (currentTab != null) {
                MimirTabBar(
                    selected = currentTab,
                    onSelect = { tab -> navController.navigateToTab(tab) },
                )
            }
        },
    ) { innerPadding ->
        MimirNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/**
 * 功能：切换到某个一级页面
 * 参数：tab 目标一级页
 * 返回：无
 *
 * popUpTo(startDestination) + saveState/restoreState 让四个 tab 各自保留滚动位置，
 * 且返回栈不会因为反复切页而无限增长。
 */
fun NavHostController.navigateToTab(tab: MimirTab) {
    navigate(tab.targetRoute) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
