package com.example.dianzicheng.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavGraph.Companion.findStartDestination

import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MainScreen(
    scaleViewModel: ScaleViewModel,
    historyViewModel: HistoryViewModel,
    profileViewModel: ProfileViewModel,
    isPairingComplete: Boolean?,
    onPairingComplete: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 常量列表缓存一次，避免每次重组重新分配
    val items = remember { listOf("测量", "历史", "我的") }
    val routes = remember { listOf("dashboard", "history", "profile") }
    val icons = remember { listOf(MiuixIcons.Home, MiuixIcons.Recent, MiuixIcons.Contacts) }

    if (isPairingComplete == null) {
        // DataStore 异步读取中，渲染空背景，杜绝因初值 false 瞬时挂载 PairingScreen 并误触发 startPairingScan()
        // （Miuix：根 Scaffold 尚未建立，这里只需一块纯背景）
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background)
        ) {}
        return
    }

    if (!isPairingComplete) {
        PairingScreen(
            viewModel = scaleViewModel,
            onPairingComplete = onPairingComplete,
            onNavigateBack = null
        )
        return
    }

    // 配对页 / 历史数据页均为二级页面：隐藏底部导航栏，由页面内返回键退出
    val isSubScreen = currentRoute == "pairing" ||
        currentRoute == "history_data"

    Scaffold(
        bottomBar = {
            if (!isSubScreen) {
                NavigationBar {
                    items.forEachIndexed { index, item ->
                        val route = routes[index]
                        val isSelected = currentRoute == route
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                if (currentRoute != route) {
                                    navController.navigate(route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = icons[index],
                            label = item
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("dashboard") {
                DashboardScreen(
                    viewModel = scaleViewModel,
                    onNavigateToPairing = { navController.navigate("pairing") }
                )
            }
            composable("history") {
                HistoryScreen(
                    viewModel = historyViewModel,
                    onNavigateToHistoryData = {
                        navController.navigate("history_data")
                    }
                )
            }
            composable("profile") {
                ProfileScreen(
                    viewModel = profileViewModel,
                    onNavigateToPairing = { navController.navigate("pairing") }
                )
            }
            composable("pairing") {
                PairingScreen(
                    viewModel = scaleViewModel,
                    onPairingComplete = { navController.popBackStack() },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("history_data") {
                // 直接订阅 historyViewModel 的 Flow：删除记录后本页数据同步刷新
                val historyData by historyViewModel.history.collectAsState()
                val members by historyViewModel.members.collectAsState()
                HistoryDataScreen(
                    history = historyData,
                    members = members,
                    onDelete = { historyViewModel.deleteMeasurement(it) },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
