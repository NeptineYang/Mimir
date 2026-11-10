package com.neptine.mimir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.neptine.mimir.ui.component.GrainEdge
import com.neptine.mimir.ui.component.GrainFade
import com.neptine.mimir.ui.theme.LocalMimirColors
import kotlinx.coroutines.launch

/**
 * 打开侧边栏的动作。
 *
 * 用 CompositionLocal 而不是给每个页面加参数：打开抽屉是"窗口级"能力，
 * 与页面内容无关；八个页面各传一个 lambda 只会让签名变长，还会让页面误以为
 * 自己拥有一个导航职责。
 */
val LocalOpenDrawer = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * 功能：装配整个应用的骨架（侧边栏 + 导航图）
 * 参数：pendingTakeover 交接通知带来的任务标识；onTakeoverHandled 跳转完成后的清理回调
 * 返回：无
 *
 * 底栏已经移除：四个一级页改由抽屉承载，页面因此多出 56dp 的可视高度，
 * 也不再需要在 Scaffold 里算底栏占位。接管页是沉浸式流程，关闭抽屉手势，
 * 避免用户在接管过程中被导航打断；该页自己保留顶部返回入口。
 */
@Composable
fun MimirApp(
    pendingTakeover: State<String?>,
    onTakeoverHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = MimirTab.entries.firstOrNull { it.matches(currentRoute) }
    val immersive = currentRoute == ROUTE_TAKEOVER

    // 点「我来处理」时先跳接管页；清理标识，避免返回后又被重新跳转
    val takeoverTaskId by pendingTakeover
    LaunchedEffect(takeoverTaskId) {
        val taskId = takeoverTaskId ?: return@LaunchedEffect
        drawerState.close()
        navController.navigate(routeTakeover(taskId)) { launchSingleTop = true }
        onTakeoverHandled()
    }

    CompositionLocalProvider(
        LocalOpenDrawer provides { scope.launch { drawerState.open() } },
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // 接管页占满屏幕且有自己的返回入口，边缘手势会与画面上的注入操作抢事件
            gesturesEnabled = !immersive,
            scrimColor = Color.Black.copy(alpha = 0.24f),
            drawerContent = {
                MimirDrawerContent(
                    currentTab = currentTab,
                    onSelect = { tab ->
                        scope.launch { drawerState.close() }
                        navController.navigateToTab(tab)
                    },
                )
            },
        ) {
            // 颗粒画在应用根层，而不是每个页面的页眉里：
            // 这样它从屏幕物理边缘（状态栏之上、导航栏之下）开始，不再被页面的
            // statusBarsPadding 切出一条断层；页面自身不铺底色，透出这一层纸面。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LocalMimirColors.current.canvas),
            ) {
                GrainFade(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .align(Alignment.TopCenter),
                    edge = GrainEdge.Top,
                )
                GrainFade(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .align(Alignment.BottomCenter),
                    edge = GrainEdge.Bottom,
                    // 底边常与正文重叠，浓度降到一半，只留纸面质感
                    opacity = 0.5f,
                )
                MimirNavHost(
                    navController = navController,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
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
