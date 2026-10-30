package com.movieshell

import androidx.compose.ui.graphics.luminance

import androidx.compose.ui.graphics.toArgb

import androidx.compose.runtime.SideEffect

import com.movieshell.ui.theme.V5

import android.net.Uri
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.AppToastOverlay
import android.os.Bundle
import android.content.Intent
import android.content.res.Configuration
import android.view.KeyEvent
import com.movieshell.ui.reader.VolumeKeyRouter
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.movieshell.data.download.FollowCheckWorker
import com.movieshell.data.download.SubRefreshWorker
import com.movieshell.data.download.Notify
import com.movieshell.data.repo.DownloadStore
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.FollowStore
import com.movieshell.data.repo.LiveStore
import com.movieshell.data.repo.PluginStore
import com.movieshell.data.repo.SourceStateStore
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.LogStore
import com.movieshell.data.repo.NovelDirectoryFill
import com.movieshell.data.repo.NovelStore
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
import kotlinx.coroutines.withContext
import com.movieshell.data.repo.PlayerEngine
import com.movieshell.player.EngineHost
import com.movieshell.player.MpvPlayerManager
import com.movieshell.player.MusicPlayer
import com.movieshell.player.PlayerManager
import com.movieshell.player.PlaySession
import com.movieshell.ui.novel.NovelSearchViewModel
import com.movieshell.ui.novel.VerifyGateDialog
import com.movieshell.ui.components.V5NavigationBar
import com.movieshell.ui.components.V5NavItem
import com.movieshell.ui.components.LocalDockClearance
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import com.movieshell.ui.home.HomeRefreshRequest
import com.movieshell.ui.music.MiniPlayer
import com.movieshell.ui.log.LogBubbleOverlay
import com.movieshell.ui.market.PyDepGuideOverlay
import com.movieshell.ui.theme.MovieShellTheme
import com.movieshell.ui.theme.V5Motion


/** 通知已消费路由在 savedInstanceState 里的 key（F69 去重用）。 */
private const val KEY_CONSUMED_ROUTE = "consumed_notify_route"

class MainActivity : ComponentActivity() {

    /** 通知点进来的待跳转路由（route to subKey），消费一次。 */
    private val pendingRoute = mutableStateOf<Pair<String, String>?>(null)

    /**
     * v0.217.0（F69）：已接受的通知路由——重建去重用。原来 onCreate 无条件
     * 从 Intent 读路由，进程被杀恢复后同一条路由又被消费一次，返回栈里
     * 多一层同样的详情页；现在接受时即记下，重建时 Intent 里还是同一条
     * 就跳过（savedInstanceState 恢复）；onNewIntent 是新投递，照常接受。
     */
    private var consumedRoute: String? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRouteIntent(intent, consumed = null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        consumedRoute?.let { outState.putString(KEY_CONSUMED_ROUTE, it) }
    }

    private fun handleRouteIntent(intent: Intent?, consumed: String?) {
        if (intent?.action != Notify.ACTION_ROUTE) return
        val route = intent.getStringExtra(Notify.EXTRA_ROUTE).orEmpty()
        if (route.isNotBlank() && route != consumed) {
            consumedRoute = route
            pendingRoute.value = route to intent.getStringExtra(Notify.EXTRA_SUBKEY).orEmpty()
        }
        // 消费后清掉 action，同一 Intent 对象再投递时不再重放。
        intent.action = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRouteIntent(intent, savedInstanceState?.getString(KEY_CONSUMED_ROUTE))
        // v0.7.2：日志总线最先初始化（接住之后所有初始化的异常）
        LogBus.init(this)
        // v0.210.0：后台验证用的应用上下文（搜书验证先在后台 WebView 里过，不过才弹页）
        com.movieshell.data.novel.VerifyGate.init(applicationContext)
        LogBus.i("App", "侠影启动")
        // v0.185.0：轻量自保护壳——硬拦截（可调试包/调试器已连）直接退出，
        // root/模拟器只警告不拦；验签不做（云构建每轮新签名，写死必误杀，见 changelog）。
        val guard = com.movieshell.data.repo.AppGuard.check(this)
        if (guard.hardFail) {
            AppToast.show(this, "安全检查未通过（${guard.hardReasons.joinToString("、")}），已退出", long = true)
            finish()
            return
        }
        if (guard.warnings.isNotEmpty()) {
            AppToast.show(this, "当前为${guard.warnings.joinToString("、")}，继续使用风险自担", long = true)
        }
        ThemeStore.init(this)
        // v0.237.0 的“跟随系统一次性切浅色”迁移已撤掉（v0.279.0 旧版）：默认改 经典暗色，
        // 没存过模式的用户直接走默认暗色；显式存过亮色/跟随系统的不动（见 ThemeStore.init）。
        LogStore.init(this)
        PlayerSettings.init(this)
        com.movieshell.data.repo.MusicSettings.init(this)
        com.movieshell.player.danmaku.DanmakuSettings.init(this) // v0.54：弹幕开关
        com.movieshell.player.danmaku.DanmakuSource.initCache(this) // v0.55：弹幕磁盘缓存
        ParseStore.load(this) // v0.19.0：自定义解析接口
        // v0.7.2：存储迁移必须在各 Manager init 之前（Manager 的目录现在走 StoragePaths）
        if (!StoragePaths.migrateFromPrivate(this) && StoragePaths.hasAccess(this)) {
            AppToast.show(this, "文件迁移失败，旧文件保留在原处（旧位置：${filesDir.absolutePath} 下的 drpy、py、php、web，新位置：${StoragePaths.publicRoot().absolutePath}）", long = true)
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
        // v0.147.0：恢复上次选中的片源（文件仍在且未被禁用才恢复），否则首页空态
        SourceStateStore.restoreActiveRule(this)
        // v0.26.0：追剧 + 下载
        FollowStore.init(this)
        // v0.246.0：下载 init 放后台——原来主线程读任务 JSON + 扫孤儿目录 + 清理旧任务；
        // _tasks 是 StateFlow，下载页观察它自动刷新；Worker 的 claimNext 持同一把锁、
        // 内存空时回退读盘，init 没跑完也不丢任务。
        lifecycleScope.launch(Dispatchers.IO) { DownloadStore.init(this@MainActivity) }
        FollowCheckWorker.reschedule(this)
        // v0.46.0：订阅自动刷新（每天 1 次）
        SubRefreshWorker.reschedule(this)
        WebSourceManager.init(this)
        SniffPatternStore.init(this)
        SubSiteEngines.init(this)
        SpiderEngine.init(this)
        // v0.5.1 升级修复：订阅站源相对路径/丢失规则文件后台修复，不阻塞启动
        lifecycleScope.launch(Dispatchers.IO) {
            // v0.169.0：收藏功能已删除，清理老数据文件（只跑一次）
            val migPrefs = getSharedPreferences("movieshell_migrations", android.content.Context.MODE_PRIVATE)
            if (!migPrefs.getBoolean("legacy_favorites_cleaned_v169", false)) {
                java.io.File(filesDir, "favorites.json").delete()
                java.io.File(filesDir, "playlists.json").delete()
                migPrefs.edit().putBoolean("legacy_favorites_cleaned_v169", true).apply()
                LogBus.i("App", "已清理老收藏数据文件")
            }
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
                // v0.274.0（T42）：弹窗状态改 rememberSaveable——原来转屏后
                // remember 重置，跳过一次的弹窗又弹出来；已授权时也不弹，
                // 防“授权后回 App、恢复的旧状态又把弹窗顶出来”。
                var showPermDialog by rememberSaveable { mutableStateOf(!StoragePaths.hasAccess(this@MainActivity)) }
                if (showPermDialog && !StoragePaths.hasAccess(this@MainActivity)) {
                    TechDialog(
                        onDismissRequest = {
                            showPermDialog = false
                            LogBus.w("StoragePaths", "用户跳过了存储权限申请")
                        },
                        title = "需要存储权限",
                        confirmText = "去授权",
                        onConfirm = {
                            showPermDialog = false
                            StoragePaths.requestAccess(this@MainActivity)
                        },
                        dismissText = "跳过",
                    ) {
                        Text("规则、备份、日志要存到 /storage/emulated/0/xiaying/，请授予「所有文件访问权限」。跳过也能用，文件会存在应用私有目录。")
                    }
                }
                Box(Modifier.fillMaxSize()) {
                val navController = rememberNavController()
                // v0.249.0 收尾：进阅读器的最终关卡——全 App 只有两处 navigate("reader/")，
                // 各页自己的守卫（书架点书、详情页等目录）是第一层，这里是兜底：
                // 未就绪的书不进空阅读页，FAILED 顺手重试；书不在架沿用老行为。
                val openReaderChecked: (String) -> Unit = { id ->
                    lifecycleScope.launch {
                        val appCtx = applicationContext
                        val novel = withContext(Dispatchers.IO) { NovelStore.get(appCtx, id) }
                        if (novel == null) {
                            navController.navigate("reader/$id")
                            return@launch
                        }
                        val hasChapters = withContext(Dispatchers.IO) {
                            NovelStore.hasChaptersFile(appCtx, id)
                        }
                        when {
                            novel.dirState == NovelStore.DIR_READY && hasChapters ->
                                navController.navigate("reader/$id")
                            novel.dirState == NovelStore.DIR_FAILED ||
                                (novel.dirState == NovelStore.DIR_READY && !hasChapters) -> {
                                NovelDirectoryFill.refillFromShelf(appCtx, novel)
                                AppToast.show(appCtx, "正在重新获取目录", long = false)
                            }
                            else -> AppToast.show(appCtx, "正在获取目录…", long = false)
                        }
                    }
                }
                // v0.26.0：通知点进来的深度跳转（消费一次）
                LaunchedEffect(pendingRoute.value) {
                    val (route, subKey) = pendingRoute.value ?: return@LaunchedEffect
                    pendingRoute.value = null
                    // B1：通知进详情不再写全局 holder，subKey 转成路由的
                    // siteKey 参数，详情页按参数解析；已发出的旧通知也走
                    // 同一条转换，不依赖 holder 残留值。
                    // B5：空 subKey 也显式带 siteKey=（本地规则站）。Q3 起全局 holder
                    // 已删除、缺省值也是空串，显式带上只为路由自描述，不再有回退旧站的风险。
                    val finalRoute = if (route.startsWith("detail/")) {
                        val sep = if (route.contains("?")) "&" else "?"
                        "$route${sep}siteKey=${Uri.encode(subKey)}"
                    } else {
                        route
                    }
                    runCatching { navController.navigate(finalRoute) }
                }
                val backStackEntry by navController.currentBackStackEntryAsState()
                val current = backStackEntry?.destination
                // 详情页等非 tab 页面隐藏底部导航栏
                // v0.96.2：直播页横屏全屏播放时也隐藏，播放器真正占满
                val curRoute = current?.route
                // v0.228.0：找书框离页清词——221 的 dispose 只在页面真正
                // 销毁时触发，tab 切换时页面留栈不销毁，框里还留着上次
                // 的词；路由离开书架/找书两个搜书宿主时清框词，结果与历史不动。
                val novelSearchVm: NovelSearchViewModel = viewModel()
                LaunchedEffect(curRoute) {
                    if (curRoute != null && curRoute != "bookshelf" && curRoute != "search") {
                        novelSearchVm.setKeyword("")
                    }
                    // v0.231.0：重进找书不留上次记录——228 只清了框词，结果列表还留在 ViewModel 里，空框配旧结果；进找书路由时把结果也清掉。
                    if (curRoute == "search") {
                        novelSearchVm.clearResults()
                    }
                }
                val liveLandscape = curRoute == "live" &&
                    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                // F8：键盘弹起时底栏会被顶到键盘上方，改成键盘可见时隐藏底栏，
                // 键盘收起自动恢复；横屏部分不改侧边导航（改动大、且底栏本身是浮动胶囊，
                // 横屏留白主要来自 dockClearance，F5 已按屏宽加列，横屏不再单靠底栏占位）。
                val imeVisible = WindowInsets.ime.getBottom(density) > 0
                val showBottomBar = (current?.hierarchy?.any { dest ->
                    tabs.any { it.route == dest.route }
                } == true) && !liveLandscape && !imeVisible
                // v0.292.0 V5：系统栏跟 M3 色板走——状态栏取当前页底色（设置页 surfaceContainer，
                // 其余 surface），导航栏有底栏时与 NavigationBar 同色；图标深浅随色板明暗。
                // 视频播放页自己管沉浸式（隐藏系统栏），这里只改颜色不改可见性。
                val sysScheme = MaterialTheme.colorScheme
                val statusBg = if (curRoute == "settings") sysScheme.surfaceContainer else sysScheme.background
                val navBg = if (showBottomBar) sysScheme.surfaceContainer else sysScheme.background
                SideEffect {
                    val w = this@MainActivity.window
                    @Suppress("DEPRECATION")
                    w.statusBarColor = statusBg.toArgb()
                    @Suppress("DEPRECATION")
                    w.navigationBarColor = navBg.toArgb()
                    val light = statusBg.luminance() > 0.5f
                    androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                        isAppearanceLightStatusBars = light
                        isAppearanceLightNavigationBars = navBg.luminance() > 0.5f
                    }
                }
                // v0.91.0：全局迷你播放条——有在播曲目且不在播放页/视频页时，悬在底部导航上方
                // v0.108.0 B3：只在音乐 tab（含其子页）显示，小说/看剧 tab 不再出现；
                // 锁屏通知走 MediaSession 不动
                val musicState by MusicPlayer.state.collectAsState()
                val onMusicTab = curRoute == "music" ||
                    curRoute == "music_source" ||
                    curRoute?.startsWith("playlist_detail/") == true
                val showMiniPlayer = musicState.track != null && onMusicTab

                // v0.279.0 底栏 B：底栏改居中浮动胶囊，浮在内容上方（不再占 Scaffold
                // bottomBar 的高度）；内容从底栏下面滚过，底部 100dp 淡出。tab 页的滚动
                // 内容底部留白由 LocalDockClearance 提供（78dp + 迷你播放条实测高度）。
                // 非 tab 页（音乐子页等）没有底栏，迷你播放条照旧放 bottomBar 占位。
                var miniPlayerHeightPx by remember { mutableStateOf(0) }
                val miniPlayerHeight = with(density) { miniPlayerHeightPx.toDp() }
                // F6：dockClearance 须在 Scaffold 外算——Scaffold 内容 lambda 内
                // 定义的值，Scaffold 外的 AppToastOverlay 引用不到（v0.283.0 构建挂点）。
                val dockClearance = if (showBottomBar) {
                    V5.DockClearance + (if (showMiniPlayer) miniPlayerHeight else 0.dp)
                } else 0.dp
                Scaffold(
                    // v0.238.0 顶部贴顶：Scaffold 默认把状态栏高度算进内容内边距，
                    // 本 App 窗口本身已在状态栏下方排布（未开 edge-to-edge），再算一遍就是双倍顶距；
                    // 只保留底部，顶部内边距归零，各页顶栏贴到内容顶。
                    // v0.261.0：水平方向不再保留——safeDrawing Horizontal 在横竖屏切换后
                    // 会在左侧留下一条约 107/1000 宽的白边（详情页播放器左白边实测），
                    // 各页本身已有 12/16dp 内边距，播放器更要贴边，水平 inset 不能再叠加。
                    // 底部同样归零——顶部双倍的前车之鉴：窗口本身已在系统导航栏
                    // 上方，Scaffold 再加 safeDrawing 底部等于又顶起一个导航栏高度，底栏与
                    // 系统栏之间露出一截窗口底色，底部黑边看起来宽了一倍。
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        if (showMiniPlayer && !showBottomBar) {
                            MiniPlayer(
                                playState = musicState,
                                onOpenPlayer = { navController.navigate("music_player") },
                            )
                        }
                    }
                ) { inner ->
                    CompositionLocalProvider(LocalDockClearance provides dockClearance) {
                    Box(Modifier.padding(inner).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.fillMaxSize(),
                        // v0.27.0 Soft Tech：页面切换统一淡入 + 8dp 位移，300ms ease-out
                        // v0.222.0：tab 切 tab 时退出混合改为 0ms 淡出（exit lambda 不接受 null，须返回非空 ExitTransition）——旧页瞬间撤掉、新页
                        // 仍淡入，不再有双页叠在一起的重影帧；exit/popExit 的 lambda
                        // 里 initialState 是正在退出的页、targetState 是要进入的页，
                        // 两边都是 tab 才判 tab 切换，tab 与子页之间仍走原转场。
                        enterTransition = {
                            fadeIn(tween(V5Motion.Fast, easing = V5Motion.Ease)) +
                                slideInVertically(tween(V5Motion.Fast, easing = V5Motion.Ease)) { with(density) { 8.dp.roundToPx() } }
                        },
                        exitTransition = {
                            if (initialState.destination.route?.let { it in TAB_ROUTES } == true &&
                                targetState.destination.route?.let { it in TAB_ROUTES } == true
                            ) {
                                fadeOut(tween(0))
                            } else {
                                fadeOut(tween(V5Motion.Fast, easing = V5Motion.Ease))
                            }
                        },
                        popEnterTransition = {
                            fadeIn(tween(V5Motion.Fast, easing = V5Motion.Ease))
                        },
                        popExitTransition = {
                            if (initialState.destination.route?.let { it in TAB_ROUTES } == true &&
                                targetState.destination.route?.let { it in TAB_ROUTES } == true
                            ) {
                                fadeOut(tween(0))
                            } else {
                                fadeOut(tween(V5Motion.Fast, easing = V5Motion.Ease)) +
                                    slideOutVertically(tween(V5Motion.Fast, easing = V5Motion.Ease)) { with(density) { 8.dp.roundToPx() } }
                            }
                        },
                    ) {
                        appDestinations(navController, this@MainActivity, this@MainActivity.lifecycleScope, openReaderChecked)
                    }
                    // v0.292.0 V5：M3 NavigationBar 贴底（实底，不再浮动/淡出）+ 音乐 tab 的迷你播放条
                    if (showBottomBar) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (showMiniPlayer) {
                                Box(Modifier.onSizeChanged { miniPlayerHeightPx = it.height }) {
                                    MiniPlayer(
                                        playState = musicState,
                                        onOpenPlayer = { navController.navigate("music_player") },
                                    )
                                }
                            }
                            val selectedIdx = tabs.indexOfFirst { t ->
                                current?.hierarchy?.any { it.route == t.route } == true
                            }.takeIf { it >= 0 } ?: 0
                            V5NavigationBar(
                                items = tabs.map { V5NavItem(it.label, it.icon, it.selectedIcon) },
                                selectedIndex = selectedIdx,
                                onSelect = { i -> navController.navigateTab(tabs[i].route) },
                                // 288（用户定案）：长按底部首页 tab 刷新当前源。
                                // 只认首页下标：先切回首页，再发一次刷新信号；
                                // 已在首页时就是直接刷新。其他 tab 长按不变（无回调）。
                                onLongClick = { i ->
                                    if (tabs[i].route == "home") {
                                        navController.navigateTab("home")
                                        HomeRefreshRequest.request()
                                    }
                                },
                            )
                        }
                    }
                    } // 闭合内容 Box
                    } // 闭合 LocalDockClearance
                }
                // v0.211.0：全仓统一精致提示悬浮层（替代系统 Toast，挂根 Box 最上层）
                // F6：离底高度跟底栏/迷你播放条走——有底栏用 dockClearance+16dp，
                // 没底栏但有迷你播放条用迷你条高度+16dp，否则离底 16dp。
                AppToastOverlay(
                    bottomPadding = when {
                        showBottomBar -> dockClearance + 16.dp
                        showMiniPlayer -> miniPlayerHeight + 16.dp
                        else -> 16.dp
                    },
                )
                // v0.7.2：运行日志悬浮窗（应用内悬浮）
                LogBubbleOverlay()
                // v0.27.2：PY 缺库一键安装引导
                PyDepGuideOverlay()
                // v0.201.0：Cloudflare/WebView 验证通道（有请求时弹全屏验证页）
                VerifyGateDialog()
                }
            }
        }
    }

    // v0.72.0：音量键翻页——阅读器注册回调后接管音量键
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (VolumeKeyRouter.onVolumeUp?.invoke() == true) return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (VolumeKeyRouter.onVolumeDown?.invoke() == true) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
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
        // v0.264.0（GPT 对齐后，方案 A）：按 Home 不再自动进画中画——用户原话
        // “我都退出 app。视频还在播放”，自动 PiP 正是投诉的根因。
        // 改为只暂停，不进 PiP、不释放会话、不动 PlaySession.playerVisible
        // （playerVisible 由 PlayerScreen 的组合生命周期维护，Home 时改它会
        // 让回 App 后仍被误判为不可见；返回 App 后用户点播放可续播）。
        // 手动画中画仍走 PlayerScreen.enterPip(context)，与自动路径无关，
        // 程序主动进 PiP 不经过 onUserLeaveHint，不会被这段暂停误伤。
        val playing = PlayerManager.player?.isPlaying == true ||
            (EngineHost.engine() == PlayerEngine.LIBMPV && MpvPlayerManager.isPlaying)
        if (PlaySession.playerVisible && playing) {
            runCatching { EngineHost.pause() }
            LogBus.i("App", "Home 暂停，不自动进入画中画")
        }
    }
}

