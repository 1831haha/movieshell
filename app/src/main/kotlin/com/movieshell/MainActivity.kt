package com.movieshell

import android.net.Uri
import android.os.Bundle
import android.content.Intent
import android.util.Rational
import android.app.PictureInPictureParams
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.movieshell.data.download.FollowCheckWorker
import com.movieshell.data.download.SubRefreshWorker
import com.movieshell.data.download.Notify
import com.movieshell.data.repo.DetailSiteHolder
import com.movieshell.data.repo.DownloadStore
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.FollowStore
import com.movieshell.data.repo.LiveStore
import com.movieshell.data.repo.PluginStore
import com.movieshell.data.repo.SourceStateStore
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.LogStore
import com.movieshell.data.repo.PhpSourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SpiderEngine
import com.movieshell.data.repo.StoragePaths
import com.movieshell.data.repo.SubSiteEngines
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.data.repo.ParseStore
import com.movieshell.data.repo.PlayerSettings
import com.movieshell.data.repo.SniffPatternStore
import com.movieshell.data.repo.ThemeStore
import com.movieshell.data.repo.WebSourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.movieshell.data.repo.PlayerEngine
import com.movieshell.player.EngineHost
import com.movieshell.player.MpvPlayerManager
import com.movieshell.player.PlayerManager
import com.movieshell.player.PlaySession
import com.movieshell.player.navigateToPlayer
import com.movieshell.player.playerDestination
import com.movieshell.ui.bookshelf.BookshelfScreen
import com.movieshell.ui.detail.DetailScreen
import com.movieshell.ui.download.DownloadScreen
import com.movieshell.ui.follow.FollowScreen
import com.movieshell.ui.history.HistoryScreen
import com.movieshell.ui.novel.NovelSearchScreen
import com.movieshell.ui.reader.ReaderScreen
import com.movieshell.ui.components.SoftBottomDock
import com.movieshell.ui.components.SoftDockItem
import com.movieshell.ui.home.HomeScreen
import com.movieshell.ui.home.HomeViewModel
import com.movieshell.ui.lab.LabScreen
import com.movieshell.ui.live.LiveManageScreen
import com.movieshell.ui.live.LiveScreen
import com.movieshell.ui.log.LogBubbleOverlay
import com.movieshell.ui.changelog.ChangelogScreen
import com.movieshell.ui.manual.ManualScreen
import com.movieshell.ui.market.PluginDetailScreen
import com.movieshell.ui.market.PluginMarketScreen
import com.movieshell.ui.market.PyDepGuideOverlay
import com.movieshell.ui.preview.FilePreviewScreen
import com.movieshell.ui.search.SearchScreen
import com.movieshell.ui.settings.SettingsScreen
import com.movieshell.ui.sources.SourcesScreen
import com.movieshell.ui.trash.TrashScreen
import com.movieshell.ui.theme.MovieShellTheme
import com.movieshell.ui.theme.SoftMotion
import com.movieshell.ui.web.WebSniffScreen

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

// v0.36.0：5 tab 按 mockup 重排——首页/直播/书架/实验室/设置；站源 tab 删除，管理入口搬进设置页。
// 未选中用 Outlined，选中用 Filled＋青色（SoftBottomDock 里处理）。
private val tabs = listOf(
    Tab("home", "首页", Icons.Outlined.Home, Icons.Filled.Home),
    Tab("live", "直播", Icons.Outlined.OndemandVideo, Icons.Filled.OndemandVideo),
    Tab("bookshelf", "书架", Icons.Outlined.Book, Icons.Filled.Book),
    Tab("lab", "实验室", Icons.Outlined.Science, Icons.Filled.Science),
    Tab("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings),
)

private const val DETAIL_ROUTE = "detail/{vodId}?title={title}"

class MainActivity : ComponentActivity() {

    /** 通知点进来的待跳转路由（route to subKey），消费一次。 */
    private val pendingRoute = mutableStateOf<Pair<String, String>?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRouteIntent(intent)
    }

    private fun handleRouteIntent(intent: Intent?) {
        if (intent?.action != Notify.ACTION_ROUTE) return
        val route = intent.getStringExtra(Notify.EXTRA_ROUTE).orEmpty()
        if (route.isNotBlank()) {
            pendingRoute.value = route to intent.getStringExtra(Notify.EXTRA_SUBKEY).orEmpty()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRouteIntent(intent)
        // v0.7.2：日志总线最先初始化（接住之后所有初始化的异常）
        LogBus.init(this)
        LogBus.i("App", "侠影启动")
        ThemeStore.init(this)
        LogStore.init(this)
        PlayerSettings.init(this)
        com.movieshell.player.danmaku.DanmakuSettings.init(this) // v0.54：弹幕开关
        ParseStore.load(this) // v0.19.0：自定义解析接口
        // v0.7.2：存储迁移必须在各 Manager init 之前（Manager 的目录现在走 StoragePaths）
        if (!StoragePaths.migrateFromPrivate(this) && StoragePaths.hasAccess(this)) {
            Toast.makeText(this, "文件迁移失败，旧文件保留在原处", Toast.LENGTH_LONG).show()
        }
        PlayerManager.init(this)
        DrpySourceManager.init(this)
        PySourceManager.init(this)
        PhpSourceManager.init(this)
        // v0.22.0：插件市场本地状态
        PluginStore.init(this)
        // v0.24.0：直播源 + 源启停状态
        LiveStore.init(this)
        SourceStateStore.init(this)
        // v0.26.0：追剧 + 下载
        FollowStore.init(this)
        DownloadStore.init(this)
        FollowCheckWorker.reschedule(this)
        // v0.46.0：订阅自动刷新（每天 1 次）
        SubRefreshWorker.reschedule(this)
        PhpSourceManager.init(this)
        WebSourceManager.init(this)
        SniffPatternStore.init(this)
        SubSiteEngines.init(this)
        SpiderEngine.init(this)
        // v0.5.1 升级修复：订阅站源相对路径/丢失规则文件后台修复，不阻塞启动
        lifecycleScope.launch(Dispatchers.IO) {
            SubSiteStore.repairOnUpgrade(this@MainActivity)
            // v0.8.0 升级修复：老缓存里 csp_ 站标的 KIND_UNSUPPORTED 转 KIND_SPIDER
            SubSiteStore.repairSpiderKind(this@MainActivity)
        }
        setContent {
            val themeMode by ThemeStore.mode.collectAsState()
            val themeColorInt by ThemeStore.color.collectAsState()
            val density = LocalDensity.current
            val darkTheme = when (themeMode) {
                ThemeStore.MODE_LIGHT -> false
                ThemeStore.MODE_DARK -> true
                else -> isSystemInDarkTheme()
            }
            MovieShellTheme(
                darkTheme = darkTheme,
                themeColor = Color(themeColorInt),
            ) {
                // v0.7.2：存储权限引导（无权限时文件走私有回退目录，App 照常用）
                var showPermDialog by remember { mutableStateOf(!StoragePaths.hasAccess(this@MainActivity)) }
                if (showPermDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            showPermDialog = false
                            LogBus.w("StoragePaths", "用户跳过了存储权限申请")
                        },
                        title = { Text("需要存储权限") },
                        text = { Text("规则、备份、日志要存到 /storage/emulated/0/xiaying/，请授予「所有文件访问权限」。跳过也能用，文件会存在应用私有目录。") },
                        confirmButton = {
                            TextButton(onClick = {
                                showPermDialog = false
                                StoragePaths.requestAccess(this@MainActivity)
                            }) { Text("去授权") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPermDialog = false }) { Text("跳过") }
                        },
                    )
                }
                Box(Modifier.fillMaxSize()) {
                val navController = rememberNavController()
                val ctx = LocalContext.current
                // v0.26.0：通知点进来的深度跳转（消费一次）
                LaunchedEffect(pendingRoute.value) {
                    val (route, subKey) = pendingRoute.value ?: return@LaunchedEffect
                    pendingRoute.value = null
                    if (subKey.isNotBlank()) {
                        DetailSiteHolder.site = SubSiteStore.findSite(ctx, subKey)
                            ?.let { (u, r) -> SubSiteStore.toSite(u, r) }
                    }
                    runCatching { navController.navigate(route) }
                }
                val backStackEntry by navController.currentBackStackEntryAsState()
                val current = backStackEntry?.destination
                // 详情页等非 tab 页面隐藏底部导航栏
                val showBottomBar = current?.hierarchy?.any { dest ->
                    tabs.any { it.route == dest.route }
                } == true

                Scaffold(
                    bottomBar = {
                        if (showBottomBar) {
                            // v0.27.1：悬浮 dock 式导航（替代 NavigationBar 脏灰药丸选中态）
                            val selectedIdx = tabs.indexOfFirst { t ->
                                current?.hierarchy?.any { it.route == t.route } == true
                            }.takeIf { it >= 0 } ?: 0
                            SoftBottomDock(
                                items = tabs.map { SoftDockItem(it.label, it.icon, it.selectedIcon) },
                                selectedIndex = selectedIdx,
                                onSelect = { i -> navController.navigateTab(tabs[i].route) },
                            )
                        }
                    }
                ) { inner ->
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.padding(inner),
                        // v0.27.0 Soft Tech：页面切换统一淡入 + 8dp 位移，300ms ease-out
                        enterTransition = {
                            fadeIn(tween(SoftMotion.Fast, easing = SoftMotion.Ease)) +
                                slideInVertically(tween(SoftMotion.Fast, easing = SoftMotion.Ease)) { with(density) { 8.dp.roundToPx() } }
                        },
                        exitTransition = {
                            fadeOut(tween(SoftMotion.Fast, easing = SoftMotion.Ease))
                        },
                        popEnterTransition = {
                            fadeIn(tween(SoftMotion.Fast, easing = SoftMotion.Ease))
                        },
                        popExitTransition = {
                            fadeOut(tween(SoftMotion.Fast, easing = SoftMotion.Ease)) +
                                slideOutVertically(tween(SoftMotion.Fast, easing = SoftMotion.Ease)) { with(density) { 8.dp.roundToPx() } }
                        },
                    ) {
                        composable("home") {
                            // v0.53.0（三点之3）：viewModel() 按 home 的 back stack entry 作用域，
                            // navigate("detail") 时实例保留，popBackStack 回来不重建
                            val homeVm: HomeViewModel = viewModel()
                            HomeScreen(
                                vm = homeVm,
                                onSearchClick = { navController.navigateTab("search") },
                                // v0.47.0：标题透传，详情页加载中不再显示裸 vodId
                                onVodClick = { vodId, title ->
                                    navController.navigate("detail/${Uri.encode(vodId)}?title=${Uri.encode(title)}")
                                },
                                // v0.24.0：播放历史
                                onHistoryClick = { navController.navigate("history") },
                                // v0.26.0：我的追剧
                                onFollowClick = { navController.navigate("follow") },
                                // v0.35.0：小说书架
                                onBookshelfClick = { navController.navigate("bookshelf") },
                            )
                        }
                        composable("search") {
                            SearchScreen(
                                onVodClick = { vodId -> navController.navigate("detail/${Uri.encode(vodId)}") },
                            )
                        }
                        composable("sources") {
                            SourcesScreen(
                                onWebSniff = { siteName, siteUrl ->
                                    navController.navigate(
                                        "web_sniff/${Uri.encode(siteName)}/${Uri.encode(siteUrl)}"
                                    )
                                },
                                onPreview = { kind, fileName ->
                                    navController.navigate(
                                        "preview/$kind/${Uri.encode(fileName)}"
                                    )
                                },
                                // v0.24.0：回收站入口（v0.27.4：插件市场入口移到实验室）
                                onTrashClick = { navController.navigate("trash") },
                            )
                        }
                        // v0.24.0：直播 Tab
                        composable("live") {
                            LiveScreen(
                                onPlayChannel = { ch ->
                                    navController.navigateToPlayer(
                                        url = ch.url,
                                        title = ch.name,
                                        isLive = true,
                                    )
                                },
                                onManageClick = { navController.navigate("live_manage") },
                            )
                        }
                        composable("live_manage") {
                            LiveManageScreen(
                                onBack = { navController.popBackStack() },
                            )
                        }
                        // v0.24.0：播放历史独立页
                        composable("history") {
                            HistoryScreen(
                                onBack = { navController.popBackStack() },
                                onPlay = { entry ->
                                    if (entry.kind == "live") {
                                        navController.navigateToPlayer(
                                            url = entry.url,
                                            title = entry.title,
                                            isLive = true,
                                        )
                                    } else {
                                        navController.navigateToPlayer(
                                            url = entry.url,
                                            title = entry.title,
                                        )
                                    }
                                },
                            )
                        }
                        // v0.26.0：我的追剧
                        composable("follow") {
                            FollowScreen(
                                onBack = { navController.popBackStack() },
                                onVodClick = { item ->
                                    // 恢复站点上下文 / 激活对应规则，再进详情
                                    when (item.sourceType) {
                                        "drpy" -> {
                                            DetailSiteHolder.site = null
                                            DrpySourceManager.setActiveRule(item.sourceKey)
                                        }
                                        "py" -> {
                                            DetailSiteHolder.site = null
                                            PySourceManager.setActiveRule(item.sourceKey)
                                        }
                                        else -> {
                                            DetailSiteHolder.site = item.sourceKey
                                                .takeIf { it.isNotBlank() }
                                                ?.let { key ->
                                                    SubSiteStore.findSite(
                                                        this@MainActivity,
                                                        key,
                                                    )?.let { (u, r) -> SubSiteStore.toSite(u, r) }
                                                }
                                        }
                                    }
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        FollowStore.clearNew(this@MainActivity, item.id)
                                    }
                                    navController.navigate("detail/${Uri.encode(item.vodId)}")
                                },
                            )
                        }
                        // v0.35.0：小说书架
                        composable("bookshelf") {
                            BookshelfScreen(
                                onNovelClick = { id ->
                                    navController.navigate("reader/$id")
                                },
                                onFindBookClick = { navController.navigate("novel_search") },
                            )
                        }
                        composable("novel_search") {
                            NovelSearchScreen(
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(
                            "reader/{novelId}",
                            arguments = listOf(navArgument("novelId") { type = NavType.StringType }),
                        ) { entry ->
                            val novelId = entry.arguments?.getString("novelId").orEmpty()
                            ReaderScreen(
                                novelId = novelId,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        // v0.26.0：下载管理
                        composable("downloads") {
                            DownloadScreen(
                                onBack = { navController.popBackStack() },
                                onPlayLocal = { path, title ->
                                    navController.navigateToPlayer(
                                        url = "file://$path",
                                        title = title,
                                    )
                                },
                            )
                        }
                        // v0.24.0：回收站
                        composable("trash") {
                            TrashScreen(
                                onBack = { navController.popBackStack() },
                            )
                        }
                        // v0.22.0：插件市场页；v0.23.0：插件详情页
                        composable("market") {
                            PluginMarketScreen(
                                onBack = { navController.popBackStack() },
                                onDetail = { id -> navController.navigate("market_detail/$id") },
                            )
                        }
                        composable("market_detail/{id}") { entry ->
                            PluginDetailScreen(
                                pluginId = entry.arguments?.getString("id").orEmpty(),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onManualClick = { navController.navigate("manual") },
                                onDownloadsClick = { navController.navigate("downloads") },
                                onChangelogClick = { navController.navigate("changelog") },
                                onSourcesClick = { navController.navigate("sources") },
                            )
                        }
                        // v0.30.0：更新日志独立页
                        composable("changelog") {
                            ChangelogScreen(onBack = { navController.popBackStack() })
                        }
                        composable("lab") {
                            LabScreen(
                                onBack = { navController.popBackStack() },
                                // v0.27.4：插件市场入口移到实验室
                                onMarketClick = { navController.navigate("market") },
                            )
                        }
                        composable("manual") {
                            ManualScreen(onBack = { navController.popBackStack() })
                        }
                        // v0.9.0：文件预览器（站源页长按菜单 → 文件预览）
                        composable(
                            route = "preview/{kind}/{fileName}",
                            arguments = listOf(
                                navArgument("kind") { type = NavType.StringType },
                                navArgument("fileName") { type = NavType.StringType },
                            ),
                        ) { entry ->
                            FilePreviewScreen(
                                kind = entry.arguments?.getString("kind").orEmpty(),
                                fileName = Uri.decode(
                                    entry.arguments?.getString("fileName").orEmpty(),
                                ),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(
                            route = DETAIL_ROUTE,
                            arguments = listOf(
                                navArgument("vodId") { type = NavType.StringType },
                                // v0.47.0：可选标题，加载中顶栏先显示它，不再裸奔 vodId
                                navArgument("title") {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                            ),
                        ) { entry ->
                            val detailVodId = entry.arguments?.getString("vodId").orEmpty()
                            val detailTitle = entry.arguments?.getString("title").orEmpty()
                            DetailScreen(
                                vodId = detailVodId,
                                initialTitle = detailTitle,
                                onBack = { navController.popBackStack() },
                                onPlay = { playUrl, title ->
                                    // DetailScreen 已做完播放决策（磁力/直链解析）：
                                    // playUrl 为空 -> 播放页显示"暂无可播放地址"
                                    // v0.16.2：带 vodId，按剧取片头片尾
                                    navController.navigateToPlayer(
                                        url = playUrl,
                                        title = title,
                                        vodId = detailVodId,
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
                // v0.7.2：运行日志悬浮窗（应用内悬浮）
                LogBubbleOverlay()
                // v0.27.2：PY 缺库一键安装引导
                PyDepGuideOverlay()
                }
            }
        }
    }

    override fun onDestroy() {
        // 真正退出应用时释放播放器；旋转等配置变更时不释放
        if (isFinishing) {
            PlayerManager.release()
            MpvPlayerManager.release() // v0.20.0：libmpv 引擎
        }
        super.onDestroy()
    }

    /**
     * v0.19.0：播放中按 Home 键自动进画中画（暂停/不在播放页则不进）。
     * v0.20.0：双引擎——Media3 看 ExoPlayer 状态，libmpv 看 MpvPlayerManager。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val playing = PlayerManager.player?.isPlaying == true ||
            (EngineHost.engine() == PlayerEngine.LIBMPV && MpvPlayerManager.isPlaying)
        if (PlaySession.playerVisible && playing) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                )
                LogBus.i("App", "自动进入画中画")
            }.onFailure { LogBus.w("App", "进入画中画失败：${it.message}") }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
