package com.movieshell

/**
 * Q1 路由图拆分（由 q1q3-muse 分支移植到 ui-v5，纯搬移、行为不变）：
 * 原来 MainActivity.kt 里 NavHost 的全部目的地搬到这里，
 * 以 NavGraphBuilder 扩展函数 appDestinations 挂回 MainActivity 的 NavHost。
 * tabs / TAB_ROUTES / DETAIL_ROUTE / navigateTab / Tab 也随路由图一起搬过来
 * （改 private 为 internal，MainActivity 的底栏与转场仍用它们）；
 * 转场（enter/exit/popEnter/popExit）仍留在 MainActivity，不属于路由图。
 * V5 口径：tabs 用 ui-v5 的 Filled 选中图标版；目的地内 this@MainActivity 改走
 * context 参数、lifecycleScope 改走 scope 参数。
 */

import kotlinx.coroutines.CoroutineScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.Icons
import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.movieshell.data.novel.NovelSearchResult
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.FollowStore
import com.movieshell.data.repo.PhpSourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.player.navigateToPlayer
import com.movieshell.player.playerDestination
import com.movieshell.ui.actor.ActorWorksScreen
import com.movieshell.ui.bookshelf.BookshelfScreen
import com.movieshell.ui.changelog.ChangelogScreen
import com.movieshell.ui.detail.DetailScreen
import com.movieshell.ui.download.DownloadScreen
import com.movieshell.ui.follow.FollowScreen
import com.movieshell.ui.history.HistoryScreen
import com.movieshell.ui.home.HomeScreen
import com.movieshell.ui.home.HomeViewModel
import com.movieshell.ui.live.LiveManageScreen
import com.movieshell.ui.live.LiveScreen
import com.movieshell.ui.manual.ManualScreen
import com.movieshell.ui.market.PluginCenterScreen
import com.movieshell.ui.market.PluginDetailScreen
import com.movieshell.ui.music.MusicPlayerScreen
import com.movieshell.ui.music.MusicScreen
import com.movieshell.ui.music.MusicSourceScreen
import com.movieshell.ui.music.PlaylistDetailScreen
import com.movieshell.ui.novel.BookSourceScreen
import com.movieshell.ui.novel.ExploreAggregateScreen
import com.movieshell.ui.novel.ExploreScreen
import com.movieshell.ui.novel.NovelSearchScreen
import com.movieshell.ui.novel.PurifyRulesScreen
import com.movieshell.ui.novel.SearchBookDetailScreen
import com.movieshell.ui.novel.SourceGroup
import com.movieshell.ui.preview.FilePreviewScreen
import com.movieshell.ui.reader.ReaderScreen
import com.movieshell.ui.search.SearchScreen
import com.movieshell.ui.settings.ParseSettingsScreen
import com.movieshell.ui.settings.SettingsScreen
import com.movieshell.ui.settings.SniffPatternsScreen
import com.movieshell.ui.sources.SourcesScreen
import com.movieshell.ui.web.WebSniffScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

// v0.36.0：5 tab 按 mockup 重排——首页/直播/书架/音乐/设置；站源 tab 删除，管理入口搬进设置页。
// v0.79.0：实验室 tab 改为音乐；实验室功能（插件市场/嗅探正则）搬进设置页。
// 全量重做：底栏 6 TAB——首页/直播/书架/音乐/AI/设置；框架先起来，
// AI 具体内容后面再完善（AiScreen 目前只有新风格外壳，不做假功能）。
// F2：AI 空壳先从底栏移除，底栏回到 5 TAB，功能做好再加回。
// 未选中用 Outlined，选中用 Filled＋青色（V5NavigationBar 里处理）。
// v0.279.0 底栏 B：线性图标（Lucide 风格），选中/未选中同一套描边图标，
// 选中靠强调色药丸 + 白色图标文字区分。
internal val tabs = listOf(
    // v0.292.0 V5：M3 NavigationBar——未选中描边图标、选中实心图标（Material Symbols 口径）。
    Tab("home", "首页", Icons.Outlined.Home, Icons.Filled.Home),
    Tab("live", "直播", Icons.Outlined.LiveTv, Icons.Filled.LiveTv),
    Tab("bookshelf", "书架", Icons.Outlined.Book, Icons.Filled.Book),
    Tab("music", "音乐", Icons.Outlined.MusicNote, Icons.Filled.MusicNote),
    Tab("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings),
)

// v0.222.0：tab 级目的地（含经 navigateTab 到达的 search）——tab 切换的
// 退出混合判定用：两边都是 tab 时旧页瞬间撤掉，不再有双页叠在一起的
// 300ms 重影帧；tab 与子页之间仍走原转场。
internal val TAB_ROUTES = setOf("home", "live", "bookshelf", "music", "settings", "search")

internal const val DETAIL_ROUTE = "detail/{vodId}?title={title}&episode={episode}&siteKey={siteKey}"
internal fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
/** 全部路由目的地（原 MainActivity NavHost 内联块，原样搬移）。 */
fun NavGraphBuilder.appDestinations(
    navController: NavHostController,
    context: Context,
    scope: CoroutineScope,
) {
                        composable("home") {
                            // v0.53.0（三点之3）：viewModel() 按 home 的 back stack entry 作用域，
                            // navigate("detail") 时实例保留，popBackStack 回来不重建
                            val homeVm: HomeViewModel = viewModel()
                            HomeScreen(
                                vm = homeVm,
                                onSearchClick = { navController.navigateTab("search") },
                                // v0.47.0：标题透传，详情页加载中不再显示裸 vodId
                                onVodClick = { vodId, title, siteKey ->
                                    val siteParam = if (siteKey.isNotBlank()) {
                                        "&siteKey=${Uri.encode(siteKey)}"
                                    } else {
                                        "&siteKey="
                                    }
                                    navController.navigate(
                                        "detail/${Uri.encode(vodId)}?title=${Uri.encode(title)}$siteParam",
                                    )
                                },
                                // v0.24.0：播放历史
                                onHistoryClick = { navController.navigate("history") },
                                // v0.26.0：我的追剧
                                onFollowClick = { navController.navigate("follow") },
                                // v0.35.0：小说书架
                                onBookshelfClick = { navController.navigate("bookshelf") },
                                // v0.139.0：首页空态去站源管理（管理入口在设置页）
                                onManageSources = { navController.navigateTab("settings") },
                                // v0.75.0：继续观看——带集数进详情页
                                // v0.189.0：进详情前先按追剧同款逻辑恢复源，
                                // 否则详情按换源后的当前源拉，旧源的 vodId 和集数对不上。
                                // 旧历史无源、订阅站查不到时保持当前源，不弹错、不清现有源。
                                onContinueWatch = { vodId, title, episode, sourceType, sourceKey ->
                                    // v0.271.0（T25）：Drpy 切源走 suspend 版，放 scope 里
                                    // 等切完再进详情，否则详情按旧源拉；其余分支仍同步（只验文件/查缓存）。
                                    scope.launch {
                                    // v0.215.0：失效源不毁当前激活态——setActiveRule 先验文件，
                                    // 切源失败（源已删）时不进详情页、不动当前源，只提示。
                                    val sourceOk = when (sourceType) {
                                        "drpy" -> if (sourceKey.isNotBlank()) {
                                            DrpySourceManager.setActiveRuleAsync(sourceKey)
                                        } else true
                                        "py" -> if (sourceKey.isNotBlank()) {
                                            PySourceManager.setActiveRule(sourceKey)
                                        } else true
                                        "php" -> if (sourceKey.isNotBlank()) {
                                            PhpSourceManager.setActiveRule(sourceKey)
                                        } else true
                                        "sub" -> if (sourceKey.isNotBlank()) {
                                            SubSiteStore.findSite(
                                                context,
                                                sourceKey,
                                            ) != null
                                        } else true
                                        else -> true
                                    }
                                    if (sourceOk) {
                                        // B1：订阅站带 sourceKey 作 siteKey，本地规则站带空串，
                                        // 详情页按参数解析，不再读 holder。
                                        val siteParam = if (sourceType == "sub" && sourceKey.isNotBlank()) {
                                            "&siteKey=${Uri.encode(sourceKey)}"
                                        } else {
                                            "&siteKey="
                                        }
                                        navController.navigate(
                                            "detail/${Uri.encode(vodId)}?title=${Uri.encode(title)}&episode=$episode$siteParam",
                                        )
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            "该记录的源已删除或不可用，当前源未变",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    }
                                },
                            )
                        }
                        composable("search") {
                            SearchScreen(
                                onVodClick = { vodId, siteKey ->
                                    val siteParam = if (siteKey.isNotBlank()) {
                                        "?siteKey=${Uri.encode(siteKey)}"
                                    } else {
                                        "?siteKey="
                                    }
                                    navController.navigate("detail/${Uri.encode(vodId)}$siteParam")
                                },
                            )
                        }
                        // v0.88.0：演员作品页（点详情页演员名进入）
                        composable(
                            route = "actor_works/{name}",
                            arguments = listOf(
                                navArgument("name") { type = NavType.StringType },
                            ),
                        ) { entry ->
                            val actorName = entry.arguments?.getString("name").orEmpty()
                            ActorWorksScreen(
                                actorName = actorName,
                                onBack = { navController.popBackStack() },
                                onVodClick = { vodId, siteKey ->
                                    val siteParam = if (siteKey.isNotBlank()) {
                                        "?siteKey=${Uri.encode(siteKey)}"
                                    } else {
                                        "?siteKey="
                                    }
                                    navController.navigate("detail/${Uri.encode(vodId)}$siteParam")
                                },
                            )
                        }
                        composable(
                            "sources?tab={tab}",
                            arguments = listOf(
                                navArgument("tab") {
                                    type = NavType.IntType
                                    defaultValue = 0
                                },
                            ),
                        ) { backStackEntry ->
                            SourcesScreen(
                                // v0.90.0：通知点进来可直达检测 tab
                                initialTab = backStackEntry.arguments?.getInt("tab") ?: 0,
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
                                // v0.108.0 B6.1：站源页点站点设为当前源后自动回主页 tab
                                onGoHome = { navController.navigateTab("home") },
                            )
                        }
                        // v0.24.0：直播 Tab（v0.79.0：顶部嵌入播放器+下方选台；全屏跳独立播放页）
                        composable("live") {
                            LiveScreen(
                                onFullscreenChannel = { ch ->
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
                        composable("history") {                            HistoryScreen(
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
                                    // v0.271.0（T25）：Drpy 切源走 suspend 版，放 scope 里等切完。
                                    scope.launch {
                                    // 恢复站点上下文 / 激活对应规则，再进详情
                                    // v0.217.0（F51）：照抄继续观看分支——切源失败
                                    // （源已删）时不进详情、不动当前源，只提示；
                                    // 原来返回值被丢弃，失败也照常进详情。
                                    val sourceOk = when (item.sourceType) {
                                        "drpy" -> {
                                            DrpySourceManager.setActiveRuleAsync(item.sourceKey)
                                        }
                                        "py" -> {
                                            PySourceManager.setActiveRule(item.sourceKey)
                                        }
                                        else -> {
                                            if (item.sourceKey.isBlank()) {
                                                true
                                            } else {
                                                SubSiteStore.findSite(
                                                    context,
                                                    item.sourceKey,
                                                ) != null
                                            }
                                        }
                                    }
                                    if (sourceOk) {
                                        scope.launch(Dispatchers.IO) {
                                            FollowStore.clearNew(context, item.id)
                                        }
                                        // B1：订阅追剧带 sourceKey 作 siteKey，本地规则带空串。
                                        val siteParam = if (item.sourceType == "sub" && item.sourceKey.isNotBlank()) {
                                            "?siteKey=${Uri.encode(item.sourceKey)}"
                                        } else {
                                            "?siteKey="
                                        }
                                        navController.navigate("detail/${Uri.encode(item.vodId)}$siteParam")
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            "该记录的源已删除或不可用，当前源未变",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    }
                                },
                            )
                        }
                        // v0.35.0：小说书架（v0.82.0：顶部三 tab 移除，找书/书源改为独立路由）
                        composable("bookshelf") {
                            BookshelfScreen(
                                onNovelClick = openReaderChecked,
                                onManageSourcesClick = { navController.navigate("book_source") },
                                // v0.98.0：书籍详情里的净化规则快捷入口
                                onOpenPurifyRules = { navController.navigate("purify_rules") },
                                // v0.229.0：书架内嵌找书点卡片进书籍信息整页。
                                onOpenDetail = { group, item ->
                                    navController.navigate(
                                        "novel_book_detail/${Uri.encode(group.key)}" +
                                            "?sourceName=${Uri.encode(group.name)}" +
                                            "&remoteId=${Uri.encode(item.remoteId)}" +
                                            "&title=${Uri.encode(item.title)}" +
                                            "&author=${Uri.encode(item.author.orEmpty())}" +
                                            "&cover=${Uri.encode(item.cover.orEmpty())}",
                                    )
                                },
                                // v0.248.0：发现页胶囊进单源发现页（分类预选）。
                                onOpenExplore = { key, cat ->
                                    navController.navigate(
                                        "explore/${Uri.encode(key)}?cat=${Uri.encode(cat.orEmpty())}",
                                    )
                                },
                            )
                        }
                        composable(
                            "novel_search?pySource={pySource}",
                            arguments = listOf(navArgument("pySource") {
                                type = NavType.StringType
                                defaultValue = ""
                            }),
                        ) { entry ->
                            NovelSearchScreen(
                                onBack = { navController.popBackStack() },
                                onManageSources = { navController.navigate("book_source") },
                                pySourceName = entry.arguments?.getString("pySource")
                                    ?.takeIf { it.isNotBlank() },
                                // v0.229.0：独立找书页点卡片进书籍信息整页。
                                onOpenDetail = { group, item ->
                                    navController.navigate(
                                        "novel_book_detail/${Uri.encode(group.key)}" +
                                            "?sourceName=${Uri.encode(group.name)}" +
                                            "&remoteId=${Uri.encode(item.remoteId)}" +
                                            "&title=${Uri.encode(item.title)}" +
                                            "&author=${Uri.encode(item.author.orEmpty())}" +
                                            "&cover=${Uri.encode(item.cover.orEmpty())}",
                                    )
                                },
                            )
                        }
                        // v0.229.0：搜书结果书籍信息整页（对标图 1）。
                        composable(
                            route = "novel_book_detail/{sourceKey}?sourceName={sourceName}&remoteId={remoteId}&title={title}&author={author}&cover={cover}",
                            arguments = listOf(
                                navArgument("sourceKey") { type = NavType.StringType },
                                navArgument("sourceName") { type = NavType.StringType; defaultValue = "" },
                                navArgument("remoteId") { type = NavType.StringType; defaultValue = "" },
                                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                                navArgument("author") { type = NavType.StringType; defaultValue = "" },
                                navArgument("cover") { type = NavType.StringType; defaultValue = "" },
                            ),
                        ) { entry ->
                            val detailKey = entry.arguments?.getString("sourceKey").orEmpty()
                            SearchBookDetailScreen(
                                group = SourceGroup(
                                    key = detailKey,
                                    name = entry.arguments?.getString("sourceName").orEmpty(),
                                    books = emptyList(),
                                ),
                                item = NovelSearchResult(
                                    sourceKey = detailKey,
                                    remoteId = entry.arguments?.getString("remoteId").orEmpty(),
                                    title = entry.arguments?.getString("title").orEmpty(),
                                    author = entry.arguments?.getString("author")?.takeIf { it.isNotBlank() },
                                    cover = entry.arguments?.getString("cover")?.takeIf { it.isNotBlank() },
                                ),
                                onBack = { navController.popBackStack() },
                                onOpenReader = openReaderChecked,
                            )
                        }
                        // v0.83.0：音乐全屏播放页
                        composable("music_player") {
                            MusicPlayerScreen(
                                onBack = { navController.popBackStack() },
                                // v0.121.0：切换音源弹窗底部「管理音源与 Cookie →」
                                onOpenMusicSource = {
                                    navController.navigate("music_source")
                                },
                            )
                        }
                        composable("book_source") {
                            BookSourceScreen(
                                onBack = { navController.popBackStack() },
                                onOpenPySource = { ruleName ->
                                    navController.navigate("novel_search?pySource=$ruleName")
                                },
                                // v0.201.0：发现页入口（书架子结构内）
                                onOpenExplore = { key ->
                                    navController.navigate("explore/${Uri.encode(key)}")
                                },
                                // v0.214.0：发现聚合页入口
                                onOpenExploreAggregate = {
                                    navController.navigate("explore_aggregate")
                                },
                                // v0.95.0：净化规则管理
                                onOpenPurifyRules = { navController.navigate("purify_rules") },
                            )
                        }
                        composable(
                            "explore/{sourceKey}?cat={cat}",
                            arguments = listOf(
                                navArgument("sourceKey") { type = NavType.StringType },
                                navArgument("cat") { type = NavType.StringType; defaultValue = "" },
                            ),
                        ) { entry ->
                            ExploreScreen(
                                sourceKey = entry.arguments?.getString("sourceKey").orEmpty(),
                                initialCategory = entry.arguments?.getString("cat")?.takeIf { it.isNotBlank() },
                                onBack = { navController.popBackStack() },
                            )
                        }
                        // v0.214.0：发现聚合页
                        composable("explore_aggregate") {
                            ExploreAggregateScreen(
                                onBack = { navController.popBackStack() },
                                onOpenExplore = { key ->
                                    navController.navigate("explore/${Uri.encode(key)}")
                                },
                            )
                        }
                        // v0.95.0：净化规则管理页
                        composable("purify_rules") {
                            PurifyRulesScreen(
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
                        // v0.127.0：插件中心（插件/服务/市场三页签）统一入口；
                        // v0.188.0：旧路由 market / service_plugins 已删（全仓无内部调用、
                        // 无深链路入口，见 L09/L10），只留 plugin_center/{tab} 一个入口
                        composable(
                            "plugin_center/{tab}",
                            arguments = listOf(
                                navArgument("tab") { type = NavType.IntType; defaultValue = 0 },
                            ),
                        ) { entry ->
                            PluginCenterScreen(
                                initialTab = entry.arguments?.getInt("tab") ?: 0,
                                onBack = { navController.popBackStack() },
                                // v0.246.0：详情路由带市场——市场名可能含特殊字符，Uri 编码后放路径
                                onDetail = { id, market ->
                                    navController.navigate(
                                        "market_detail/${android.net.Uri.encode(market)}/${android.net.Uri.encode(id)}",
                                    )
                                },
                            )
                        }
                        composable("market_detail/{market}/{id}") { entry ->
                            PluginDetailScreen(
                                pluginId = entry.arguments?.getString("id").orEmpty(),
                                market = entry.arguments?.getString("market").orEmpty(),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        // F2：AI 页只有 COMING SOON 空壳，底栏与路由先移除，
                        // AiScreen 文件保留不删，功能做好再加回。
                        composable("settings") {
                            SettingsScreen(
                                onManualClick = { navController.navigate("manual") },
                                onDownloadsClick = { navController.navigate("downloads") },
                                onChangelogClick = { navController.navigate("changelog") },
                                onSourcesClick = { navController.navigate("sources") },
                                // v0.78.2：解析接口子页面
                                onParseSettingsClick = { navController.navigate("parse_settings") },
                                // v0.127.0：插件中心单入口（原插件市场/服务插件合并）
                                onPluginCenterClick = { navController.navigate("plugin_center/0") },
                                onSniffPatternsClick = { navController.navigate("sniff_patterns") },
                                // v0.80.0：快捷宫格-观看历史
                                onHistoryClick = { navController.navigate("history") },
                                // v0.90.0：快捷宫格-我的追剧
                                onFollowClick = { navController.navigate("follow") },
                            )
                        }
                        // v0.79.0：嗅探正则管理（原实验室）
                        composable("sniff_patterns") {
                            SniffPatternsScreen(
                                onBack = { navController.popBackStack() },
                            )
                        }
                        // v0.78.2：解析接口子页面（列表/测活/添加收进这里）
                        composable("parse_settings") {
                            ParseSettingsScreen(
                                onBack = { navController.popBackStack() },
                            )
                        }
                        // v0.30.0：更新日志独立页
                        composable("changelog") {
                            ChangelogScreen(onBack = { navController.popBackStack() })
                        }
                        // v0.79.0：实验室 tab 改为音乐 tab；原实验室功能已搬进设置页
                        composable("music") {
                            MusicScreen(
                                onOpenPlaylist = { id ->
                                    navController.navigate("playlist_detail/$id")
                                },
                                onOpenMusicSource = {
                                    navController.navigate("music_source")
                                },
                            )
                        }
                        // v0.100.0：音乐源管理页
                        composable("music_source") {
                            MusicSourceScreen(onBack = { navController.popBackStack() })
                        }
                        // v0.84.0：歌单详情页
                        composable(
                            "playlist_detail/{playlistId}",
                            arguments = listOf(
                                navArgument("playlistId") { type = NavType.StringType },
                            ),
                        ) { entry ->
                            PlaylistDetailScreen(
                                playlistId = entry.arguments?.getString("playlistId").orEmpty(),
                                onBack = { navController.popBackStack() },
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
                                // v0.75.0：可选起播集（继续观看带过来），-1=不指定
                                navArgument("episode") {
                                    type = NavType.IntType
                                    defaultValue = -1
                                },
                                // B1/Q3：详情站点只跟路由走（全局 holder 已删除）。
                                // 各入口一律带 siteKey，空串即本地规则站；缺省也按空串处理。
                                // 参数存在返回栈条目里，返回与进程被杀恢复都原样带回。
                                navArgument("siteKey") {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                            ),
                        ) { entry ->
                            val detailVodId = entry.arguments?.getString("vodId").orEmpty()
                            val detailEpisode = entry.arguments?.getInt("episode", -1) ?: -1
                            val detailSiteKey = entry.arguments?.getString("siteKey")
                            DetailScreen(
                                vodId = detailVodId,
                                siteKey = detailSiteKey,
                                initialEpisode = detailEpisode,
                                onBack = { navController.popBackStack() },
                                // v0.88.0：点演员名→演员作品页（专属海报墙，不再走搜索 tab）
                                onActorClick = { actorName ->
                                    navController.navigate("actor_works/${Uri.encode(actorName)}")
                                },
                                onPlay = { playUrl, title ->
                                    // DetailScreen 已做完播放决策（磁力/直链解析）：
                                    // playUrl 为空 -> 播放页显示"暂无可播放地址"
                                    // v0.16.2：带 vodId，按剧取片头片尾
                                    // Q3：带详情站 key，独立播放页按它记字幕/片头片尾/进度的源
                                    navController.navigateToPlayer(
                                        url = playUrl,
                                        title = title,
                                        vodId = detailVodId,
                                        siteKey = detailSiteKey,
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
