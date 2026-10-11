package com.movieshell

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.PhpSourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.WebSourceManager
import com.movieshell.player.PlayerManager
import com.movieshell.player.navigateToPlayer
import com.movieshell.player.playerDestination
import com.movieshell.ui.detail.DetailScreen
import com.movieshell.ui.favorites.FavoritesScreen
import com.movieshell.ui.home.HomeScreen
import com.movieshell.ui.manual.ManualScreen
import com.movieshell.ui.search.SearchScreen
import com.movieshell.ui.settings.SettingsScreen
import com.movieshell.ui.sources.SourcesScreen
import com.movieshell.ui.theme.MovieShellTheme
import com.movieshell.ui.web.WebSniffScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "首页", Icons.Filled.Home),
    Tab("search", "搜索", Icons.Filled.Search),
    Tab("sources", "站源", Icons.Filled.Storage),
    Tab("settings", "设置", Icons.Filled.Settings),
    Tab("favorites", "收藏", Icons.Filled.Favorite),
)

private const val DETAIL_ROUTE = "detail/{vodId}"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerManager.init(this)
        DrpySourceManager.init(this)
        PySourceManager.init(this)
        PhpSourceManager.init(this)
        WebSourceManager.init(this)
        setContent {
            MovieShellTheme {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val current = backStackEntry?.destination
                // 详情页等非 tab 页面隐藏底部导航栏
                val showBottomBar = current?.hierarchy?.any { dest ->
                    tabs.any { it.route == dest.route }
                } == true

                Scaffold(
                    bottomBar = {
                        if (showBottomBar) {
                            NavigationBar {
                                tabs.forEach { tab ->
                                    NavigationBarItem(
                                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                                        label = { Text(tab.label) },
                                        selected = current?.hierarchy?.any { it.route == tab.route } == true,
                                        onClick = { navController.navigateTab(tab.route) }
                                    )
                                }
                            }
                        }
                    }
                ) { inner ->
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.padding(inner)
                    ) {
                        composable("home") {
                            HomeScreen(
                                onSearchClick = { navController.navigateTab("search") },
                                onVodClick = { vodId -> navController.navigate("detail/$vodId") },
                            )
                        }
                        composable("search") {
                            SearchScreen(
                                onVodClick = { vodId -> navController.navigate("detail/$vodId") },
                            )
                        }
                        composable("sources") {
                            SourcesScreen(
                                onWebSniff = { siteName, siteUrl ->
                                    navController.navigate(
                                        "web_sniff/${Uri.encode(siteName)}/${Uri.encode(siteUrl)}"
                                    )
                                },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onManualClick = { navController.navigate("manual") },
                            )
                        }
                        composable("favorites") {
                            FavoritesScreen(
                                onVodClick = { vodId -> navController.navigate("detail/$vodId") },
                            )
                        }
                        composable("manual") {
                            ManualScreen(onBack = { navController.popBackStack() })
                        }
                        composable(
                            route = DETAIL_ROUTE,
                            arguments = listOf(navArgument("vodId") { type = NavType.StringType }),
                        ) { entry ->
                            DetailScreen(
                                vodId = entry.arguments?.getString("vodId").orEmpty(),
                                onBack = { navController.popBackStack() },
                                onPlay = { playUrl, title ->
                                    // DetailScreen 已做完播放决策（磁力/直链解析）：
                                    // playUrl 为空 -> 播放页显示"暂无可播放地址"
                                    navController.navigateToPlayer(
                                        url = playUrl,
                                        title = title,
                                    )
                                },
                            )
                        }
                        playerDestination(onBack = { navController.popBackStack() })
                        composable(
                            route = "web_sniff/{name}/{url}",
                            arguments = listOf(
                                navArgument("name") { type = NavType.StringType },
                                navArgument("url") { type = NavType.StringType },
                            ),
                        ) { entry ->
                            val siteName = entry.arguments?.getString("name").orEmpty()
                            val siteUrl = entry.arguments?.getString("url").orEmpty()
                            WebSniffScreen(
                                name = siteName,
                                url = siteUrl,
                                onBack = { navController.popBackStack() },
                                onPlay = { playUrl, title ->
                                    navController.navigateToPlayer(
                                        url = playUrl,
                                        title = title,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // 真正退出应用时释放播放器；旋转等配置变更时不释放
        if (isFinishing) PlayerManager.release()
        super.onDestroy()
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
