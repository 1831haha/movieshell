package com.movieshell.ui.settings

import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import com.movieshell.ui.components.V5RingGauge
import com.movieshell.ui.components.V5Tile
import com.movieshell.ui.theme.ok

import com.movieshell.ui.theme.V5
import com.movieshell.ui.theme.StaticGlowBackground
import androidx.compose.ui.text.font.FontFamily
import com.movieshell.ui.theme.ok

import com.movieshell.ui.components.V5Tile
import com.movieshell.ui.components.V5Row
import com.movieshell.ui.components.V5RingGauge
import com.movieshell.ui.components.dockClearance
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Storage
import android.content.ClipData
import com.movieshell.ui.components.AppSwitch
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.search.currentSourceName as searchCurrentSourceName
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.core.content.FileProvider
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.movieshell.ui.components.TechTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movieshell.data.store.AiChatStore
import com.movieshell.data.ai.AiRepairEngine
import com.movieshell.data.ai.AiRepairSettings
import com.movieshell.core.util.AtomicFile
import com.movieshell.data.sync.BackupManager
import com.movieshell.data.sync.CloudSyncManager
import com.movieshell.data.store.EmbyStore
import com.movieshell.data.store.FollowStore
import com.movieshell.data.source.JsRuntime
import com.movieshell.data.source.SniffConfig
import com.movieshell.data.store.DownloadStore
import com.movieshell.data.store.DohSettings
import com.movieshell.core.log.CrashHandler
import com.movieshell.core.log.LogBus
import com.movieshell.data.ai.LlmClient
import com.movieshell.data.store.LogStore
import com.movieshell.core.util.StoragePaths
import com.movieshell.ui.preview.fileProviderAuthority
import com.movieshell.data.store.PlayerSettings
import com.movieshell.domain.model.PlayerEngine
import com.movieshell.player.PlayerManager
import com.movieshell.ui.changelog.CHANGELOG
import com.movieshell.data.source.PluginMarket
import com.movieshell.data.store.PluginStore
import com.movieshell.data.store.SettingsLayoutStore
import com.movieshell.data.store.ThemeCollectionStore
import com.movieshell.data.store.ThemeStore
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.V5SectionTitle
import com.movieshell.ui.theme.LocalBrandGradient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.movieshell.data.store.ApiSourceStore
import com.movieshell.data.source_mgr.DrpySourceManager
import com.movieshell.data.source_mgr.PhpSourceManager
import com.movieshell.data.source_mgr.PySourceManager
import com.movieshell.data.store.SourceStateStore
import com.movieshell.data.source_mgr.SubSiteStore
import com.movieshell.player.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Image
import coil.imageLoader

// v0.27.0 Soft Tech 换皮：分组卡片走 AppCard，分组标题走 V5SectionTitle，
// 对话框 20dp 圆角 + 输入框走 TechTextField，按钮走 AppButton。逻辑与文案零变更。

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onManualClick: () -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    onChangelogClick: () -> Unit = {},
    onSourcesClick: () -> Unit = {}, // v0.36.0：站源管理入口（tab 删除后搬进设置）
    onParseSettingsClick: () -> Unit = {}, // v0.78.2：解析接口子页面入口
    onThemeCollectionClick: () -> Unit = {}, // v1.2.5：主题收藏夹子页面入口
    onPlaybackSettingsClick: () -> Unit = {}, // v1.2.6：播放设置子页面入口
    onDataSettingsClick: () -> Unit = {}, // v1.2.19：数据设置子页面入口
    // v1.3.26：Clash 代理设置页入口（订阅 + 每源走代理开关）
    onClashSettingsClick: () -> Unit = {},
    // v1.4.8 A4：网络设置页入口（DoH）
    onNetworkSettingsClick: () -> Unit = {},
    // v1.2.14：TMDB 独立设置页入口（播放设置页 TMDB Key 行点进）
    onTmdbSettingsClick: () -> Unit = {},
    onPluginCenterClick: () -> Unit = {}, // v0.127.0：插件中心（插件/服务/市场三页签，原两入口合并）
    // v1.1.31：内核选 libmpv 但插件未安装时，直接跳市场页签装插件
    onOpenMarketClick: () -> Unit = onPluginCenterClick,
    // v1.1.31：Emby / Jellyfin 媒体服务器入口
    onMediaServerClick: () -> Unit = {},
    onSniffPatternsClick: () -> Unit = {}, // v0.79.0：嗅探正则管理（v0.168.0 起入口在快捷宫格）
    onHistoryClick: () -> Unit = {}, // v0.80.0：快捷宫格-观看历史
    onFollowClick: () -> Unit = {}, // v0.90.0：快捷宫格-我的追剧（资料卡已可点进历史/追剧）
    // v1.1.3：AI 助手设置区“打开对话”与写源失败“去对话里接着改”跳 AI TAB
    onAiChatClick: () -> Unit = {},
) {
    val context = LocalContext.current
    // v0.7.1：主题读持久化 ThemeStore，不再是本地假状态
    // v0.80.0：主题改内联三段选择，不再弹窗
    val themeMode by ThemeStore.mode.collectAsState()
    var backupExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // v1.1.31：媒体服务器入口副标题（已添加台数）
    val embyServers by EmbyStore.servers.collectAsState()
    val embySubtitle = if (embyServers.isEmpty()) "未添加 · 支持 Emby / Jellyfin" else "已添加 ${embyServers.size} 台"
    // v1.2.14：播放设置组头行副标题写实时值。内核与自动播放走 StateFlow，
    // 改动立即反映；去广告与并行数没有 Flow，读进设置页时的快照，
    // 在行里改完后回设置页重组时刷新（行本身实时显示，不会误导）。
    val playbackEngine by PlayerSettings.engine.collectAsState()
    val playbackAutoplay by PlayerSettings.liveAutoplay.collectAsState()
    var playbackAdFilter by remember { mutableStateOf(SniffConfig.adFilterEnabled) }
    var playbackMaxParallel by remember { mutableStateOf(DownloadStore.maxParallel(context)) }
    LaunchedEffect(Unit) {
        SniffConfig.ensureLoaded(context)
        playbackAdFilter = SniffConfig.adFilterEnabled
        playbackMaxParallel = DownloadStore.maxParallel(context)
    }
    val playbackHeaderSubtitle = run {
        val engineText = if (playbackEngine == PlayerEngine.MEDIA3) "Media3" else "libmpv"
        val adText = if (playbackAdFilter) "去广告开" else "去广告关"
        val autoplayText = when (playbackAutoplay) {
            PlayerSettings.LIVE_AUTOPLAY_WIFI -> "自动播放仅 Wi-Fi"
            PlayerSettings.LIVE_AUTOPLAY_OFF -> "自动播放关"
            else -> "自动播放始终"
        }
        "$engineText · $adText · $autoplayText · 并行 ${playbackMaxParallel} 个"
    }
    // v0.78.2：解析接口已搬进子页面（ParseSettingsScreen），本页只留入口
    // v0.23.0：显示不兼容插件开关
    val showIncompatible by PluginStore.showIncompatible.collectAsState()

    // v1.2.19：备份导出 / 导入与中断快照已抽进 BackupExpandBlock（SettingItems.kt），
    // 展开块自带 picker 与状态，行搬到哪跟到哪。

    // v0.80.0：信息卡统计（追剧/历史/源），进设置页时拉一次
    var followCount by remember { mutableIntStateOf(0) }
    // v0.279.0 旧版：追剧环形仪表 = 有更新的追剧占比（真实数据，来自 FollowStore.hasNew）
    var followNewCount by remember { mutableIntStateOf(0) }
    var histCount by remember { mutableIntStateOf(0) }
    var subCount by remember { mutableIntStateOf(0) }
    var localCount by remember { mutableIntStateOf(0) }
    // v0.148.0：统计加载完成前不显示 0（0 和“真没有”分不出来）
    var statsLoaded by remember { mutableStateOf(false) }
    // v0.171.0：任一统计加载失败时整行标失败，不再用 0 冒充"真没有"
    var statsFailed by remember { mutableStateOf(false) }
    // 源名只作页头纯文字展示（不是格子、不可点），复用搜索页同一口径
    var currentSourceName by remember { mutableStateOf("未设置") }
    // v0.247.0（F41）：派生值（开关/统计/当前源）原来只在首次
    // 组合拉一次，别的入口改后回设置页仍显示旧值；抽成 refreshDerived，
    // 首屏与每次 ON_RESUME 各跑一次（沿用 HomeScreen 的 resume 刷新口径）。
    // 首屏那次与首次 ON_RESUME 会重复跑一轮，结果幂等、值相同，接受这点浪费。
    suspend fun refreshDerived() {
        runCatching { ThemeCollectionStore.init(context) }
        SettingsLayoutStore.init(context)
        // v1.4.8 A4：网络入口副标题读 DoH 开关实时值。
        DohSettings.init(context)
        // v0.147.0：统计失败不再静默回 0（之前 0 和“真没有”分不出来），记日志方便定位
        // v0.171.0：失败汇总到 statsFailed，界面上显示"—"与"统计失败"而不是 0
        var failed = false
        // v0.188.0：4 项统计共用 safeCount（L04）：失败记日志并汇总到 statsFailed，返回 0；
        // 口径与原来逐项 runCatching 完全一致（标签文案不变），只收敛重复写法。
        suspend fun safeCount(tag: String, block: suspend () -> Int): Int =
            runCatching { block() }
                .onFailure { failed = true; LogBus.w("SettingsStats", "$tag 统计失败：${it.message}") }
                .getOrDefault(0)
        followCount = safeCount("追剧") {
            val all = FollowStore.readSync(context)
            followNewCount = all.count { it.hasNew }
            all.size
        }
        histCount = safeCount("历史") { withContext(Dispatchers.IO) { HistoryStore.getHistory(context).size } }
        subCount = safeCount("订阅") { withContext(Dispatchers.IO) { ApiSourceStore.load(context).size } }
        localCount = safeCount("本地") {
            withContext(Dispatchers.IO) {
                DrpySourceManager.listRules().size + PySourceManager.listRules().size +
                    PhpSourceManager.listRules().size
            }
        }
        // 源名复用搜索页算法（与首页换源胶囊同一口径），刷新失败保留上次有效值
        currentSourceName = runCatching { searchCurrentSourceName(context) }.getOrNull() ?: currentSourceName
        statsFailed = failed
        statsLoaded = true
    }
    LaunchedEffect(Unit) { refreshDerived() }
    // v1.1.31：媒体服务器配置读盘（只读一次，供入口副标题用）
    LaunchedEffect(Unit) { EmbyStore.ensureLoaded(context) }
    val settingsLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(settingsLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { refreshDerived() }
        }
        settingsLifecycle.addObserver(observer)
        onDispose { settingsLifecycle.removeObserver(observer) }
    }

    // v0.80.0 起：资料统计 + 快捷入口 + 外观 + 功能分组；v0.279.0 bento 仪表盘。
    // v0.292.0 V5（design-tokens 5.2，用户定案「指标界面直接触顶」）：设置页没有顶栏——
    // 不再显示「设置」标题/版本号/当前源一行（版本号在「关于侠影」，当前源在首页换源胶囊）；
    // 内容从状态栏下 8dp 开始：追剧/历史/源 三个统计环 → 5 个工具磁贴 → Android 16 式分段列表分组。
    // 原有每一项设置、入口、动作都保留，弹窗与逻辑一行未动。
    val cs = MaterialTheme.colorScheme
    val logEnabled by LogStore.enabled.collectAsState()
    // v1.4.8 A4：网络入口副标题（DoH 开关实时值）。
    val dohEnabled by DohSettings.enabled.collectAsState()
    val themeKey by ThemeStore.themeKey.collectAsState()
    val statText: (Int) -> String = { n -> if (!statsLoaded) "…" else if (statsFailed) "—" else "$n" }
    // v1.2.7：设置页活布局——拖拽状态 + 布局表（可移行住哪、按什么顺序）。
    val dragState = rememberSettingDragState()
    val settingsLayout by SettingsLayoutStore.layout.collectAsState()
    LaunchedEffect(settingsLayout) { dragState.containers = settingsLayout }
    val onSettingDrop: (String, SettingDropTarget) -> Unit = { id, target ->
        when (target) {
            is SettingDropTarget.Insert -> SettingsLayoutStore.move(context, id, target.container, target.index)
            // v1.2.19：子页面只认自家行（播放放播放页、数据拖进数据页），
            // 串页的 move 由 SettingsLayoutStore.moveInLayout 直接拦下，这里不用另判；
            // 拖到入口上松手就搬进去。
            is SettingDropTarget.EnterPage ->
                SettingsLayoutStore.move(context, id, target.container, Int.MAX_VALUE)
            SettingDropTarget.BackToMain -> Unit
        }
    }
    // v1.2.19：数据行动作只剩三样（打开 AI 对话、备份展开态）；三弹窗与缓存占用
    // 都已搬进分支自带状态，行拖到哪都能用。
    val dataActions = DataRowActions(
        onAiChatClick = onAiChatClick,
        isBackupExpanded = backupExpanded,
        onBackupToggle = { backupExpanded = !backupExpanded },
    )
    // v1.2.16：开始拖就收起备份展开块——子行不占落点高度，插入线不落在展开块里。
    LaunchedEffect(dragState.isDragging) {
        if (dragState.isDragging) backupExpanded = false
    }
    // v1.2.7：设置页活布局——LazyColumn 与拖拽悬浮层同处一个 Box，悬浮副本才能跟手走。
    Box(Modifier.fillMaxSize()) {
        // v1.3.33：二级页静态渐变底（LazyColumn 原来的 surfaceContainer 平底去掉）。
        StaticGlowBackground(Modifier.fillMaxSize())
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = V5.Gutter,
                end = V5.Gutter,
                top = V5.StatusGap,
                bottom = dockClearance() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 1. 三个统计环：追剧（有更新占比）/ 历史（占 50 条上限）/ 源（本地占比）；
            // 三格都可点：追剧页 / 历史页 / 站源管理（2026-10-28 用户要求恢复“源”入口）。
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatGaugeTile(
                        label = "追剧",
                        value = statText(followCount),
                        sub = if (statsLoaded && !statsFailed && followCount > 0) "$followNewCount 部更新" else if (!statsLoaded) "统计中…" else "暂无更新",
                        progress = if (followCount > 0) followNewCount.toFloat() / followCount else 0f,
                        ringColor = cs.primary,
                        onClick = onFollowClick,
                        modifier = Modifier.weight(1f),
                    )
                    StatGaugeTile(
                        label = "历史",
                        value = statText(histCount),
                        sub = if (statsLoaded && !statsFailed) "上限 50" else null,
                        progress = histCount / 50f,
                        ringColor = cs.secondary,
                        onClick = onHistoryClick,
                        modifier = Modifier.weight(1f),
                    )
                    StatGaugeTile(
                        label = "源",
                        value = statText(subCount + localCount),
                        sub = if (!statsLoaded) "统计中…" else if (statsFailed) "统计失败" else "订阅 $subCount · 本地 $localCount",
                        progress = if (subCount + localCount > 0) localCount.toFloat() / (subCount + localCount) else 0f,
                        ringColor = cs.tertiary,
                        onClick = onSourcesClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
            // 2. 工具磁贴（原快捷宫格 5 个入口，一行排开）
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(V5.CornerCard))
                        .background(cs.surfaceContainerLowest)
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                ) {
                    // v0.168.0：我的追剧与统计重复已去掉；289 去重：站源管理只留统计环“源”。
                    QuickEntry("嗅探正则", Icons.Outlined.ManageSearch, onSniffPatternsClick, Modifier.weight(1f))
                    QuickEntry("解析接口", Icons.Outlined.Link, onParseSettingsClick, Modifier.weight(1f))
                    QuickEntry("离线下载", Icons.Outlined.Download, onDownloadsClick, Modifier.weight(1f))
                    QuickEntry("插件中心", Icons.Outlined.Extension, onPluginCenterClick, Modifier.weight(1f))
                    QuickEntry("使用手册", Icons.Outlined.MenuBook, onManualClick, Modifier.weight(1f))
                }
            }
            // 3. 外观
            item { SettingsCaption("外观") }
            item {
                // v1.1.14：分组收进圆角卡片——每组一个 AppCard（24dp），组内行内容与顺序原样
                AppCard(
                    shape = RoundedCornerShape(V5.CornerCard),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // v1.2.6：主题与主题收藏夹合并为一个「主题」入口（用户要求）——点开进收藏夹页，
                // 模式/配色/炫彩/海报取色在收藏夹页的「自定义配色」行里开面板；背景底图入口也在收藏夹页。
                val colorSource by ThemeStore.source.collectAsState()
                val modeLabel = when (themeMode) {
                    ThemeStore.MODE_LIGHT -> "浅色"
                    ThemeStore.MODE_DARK -> "深色"
                    else -> "跟随系统"
                }
                val colorLabel = when {
                    colorSource == ThemeStore.SOURCE_POSTER -> "海报取色"
                    colorSource == ThemeStore.SOURCE_DYNAMIC -> "系统取色"
                    themeKey.startsWith("g:") ->
                        "炫彩 " + (ThemeStore.GRADIENT_OPTIONS.firstOrNull { "g:${it.id}" == themeKey }?.name ?: "渐变")
                    themeKey.startsWith("c:") ->
                        "预设色 " + (themeKey.removePrefix("c:").toIntOrNull()?.let { ThemeStore.colorName(it) } ?: "自定义")
                    else -> "预设色"
                }
                SegItem(
                    position = SegPos.Single,
                    icon = Icons.Outlined.Palette,
                    title = "主题",
                    subtitle = "$modeLabel · $colorLabel",
                    onClick = onThemeCollectionClick,
                )
                    }
                }
            }
            // 4. 播放 · 解析
            item { SettingsCaption("播放 · 解析") }
            item {
                AppCard(
                    shape = RoundedCornerShape(V5.CornerCard),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.settingDropContainer(dragState, SettingsLayoutStore.MAIN_PLAYBACK),
                    ) {
                // v1.2.6：播放设置子页面入口——长按一行拖到它上面松手，行就搬进播放设置页。
                // v1.2.19：只有播放行能进这页，拖着数据行时入口不高亮（高亮了松手也没用）。
                val entryHovered = dragState.hoverTarget ==
                    SettingDropTarget.EnterPage(SettingsLayoutStore.PAGE_PLAYBACK) &&
                    dragState.draggedId !in SettingsLayoutStore.DATA_ITEMS
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingDropEntry(dragState, SettingsLayoutStore.PAGE_PLAYBACK)
                        .then(
                            if (entryHovered) Modifier.border(
                                2.dp, cs.primary, RoundedCornerShape(V5.CornerCard),
                            ) else Modifier,
                        ),
                ) {
                    SegItem(
                        position = SegPos.First,
                        icon = Icons.Outlined.SmartDisplay,
                        title = "播放设置",
                        subtitle = if (entryHovered) "松手放进来" else playbackHeaderSubtitle,
                        onClick = onPlaybackSettingsClick,
                    )
                }
                // v1.2.7：这组里的可移行按布局表渲染，长按可拖走（进播放设置页 / 组内调顺序）。
                val playbackItems = settingsLayout[SettingsLayoutStore.MAIN_PLAYBACK].orEmpty()
                playbackItems.forEach { id ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .settingDraggable(dragState, id, onSettingDrop)
                            .then(if (dragState.draggedId == id) Modifier.alpha(0.3f) else Modifier),
                    ) {
                        MovableSettingRow(
                            id = id,
                            style = SettingRowStyle.Card,
                            cardPosition = SegPos.Middle,
                            divider = true,
                            onOpenMarketClick = onOpenMarketClick,
                            onTmdbSettingsClick = onTmdbSettingsClick,
                        )
                    }
                }
                // v1.1.31：Emby / Jellyfin 媒体服务器（自建服务器直连，不经过中转）
                SegItem(
                    position = SegPos.Last,
                    icon = Icons.Outlined.Storage,
                    title = "媒体服务器",
                    subtitle = embySubtitle,
                    onClick = onMediaServerClick,
                )
                    }
                }
            }
            // 5. 数据
            // v1.2.16：和“播放 · 解析”一样长按拖动——组内调顺序、两组之间互拖；
            // v1.2.19：数据行拖进“数据设置”子页面收纳（播放行不进这页，数据行不进播放页），
            // 备份展开块跟在备份行后，行搬到哪子行跟到哪。
            item { SettingsCaption("数据") }
            item {
                AppCard(
                    shape = RoundedCornerShape(V5.CornerCard),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.settingDropContainer(dragState, SettingsLayoutStore.MAIN_DATA),
                    ) {
                        val dataItems = settingsLayout[SettingsLayoutStore.MAIN_DATA].orEmpty()
                        val dataPageCount = settingsLayout[SettingsLayoutStore.PAGE_DATA]?.size ?: 0
                        // v1.2.19：数据设置子页面入口——长按数据行拖到它上面松手，行就搬进数据设置页。
                        // 只有数据行能进这页，拖着播放行时入口不高亮。
                        val dataEntryHovered = dragState.hoverTarget ==
                            SettingDropTarget.EnterPage(SettingsLayoutStore.PAGE_DATA) &&
                            dragState.draggedId in SettingsLayoutStore.DATA_ITEMS
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .settingDropEntry(dragState, SettingsLayoutStore.PAGE_DATA)
                                .then(
                                    if (dataEntryHovered) Modifier.border(
                                        2.dp, cs.primary, RoundedCornerShape(V5.CornerCard),
                                    ) else Modifier,
                                ),
                        ) {
                            SegItem(
                                position = if (dataItems.isEmpty()) SegPos.Single else SegPos.First,
                                icon = Icons.Outlined.Archive,
                                title = "数据设置",
                                subtitle = if (dataEntryHovered) "松手放进来"
                                else if (dataPageCount == 0) "把数据行拖进来收纳"
                                else "已收纳 $dataPageCount 行",
                                onClick = onDataSettingsClick,
                            )
                        }
                        if (dataItems.isEmpty()) {
                            Text(
                                "还没有行：去“播放 · 解析”长按一行，拖到这里松手。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        dataItems.forEachIndexed { index, id ->
                            val backupIsLast = id == SettingsLayoutStore.ITEM_DATA_BACKUP &&
                                index == dataItems.size - 1
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .settingDraggable(dragState, id, onSettingDrop)
                                    .then(if (dragState.draggedId == id) Modifier.alpha(0.3f) else Modifier),
                            ) {
                                MovableSettingRow(
                                    id = id,
                                    style = SettingRowStyle.Card,
                                    // 卡片顶上多了“数据设置”入口（SegPos.First），行不再是第一行。
                                    cardPosition = when {
                                        dataItems.size == 1 -> if (id == SettingsLayoutStore.ITEM_DATA_BACKUP && backupExpanded) SegPos.Middle else SegPos.Last
                                        index == 0 -> SegPos.Middle
                                        index == dataItems.size - 1 && !(backupIsLast && backupExpanded) -> SegPos.Last
                                        else -> SegPos.Middle
                                    },
                                    divider = false,
                                    onOpenMarketClick = onOpenMarketClick,
                                    onTmdbSettingsClick = onTmdbSettingsClick,
                                    dataActions = dataActions,
                                )
                            }
                            if (id == SettingsLayoutStore.ITEM_DATA_BACKUP) {
                                AnimatedVisibility(visible = backupExpanded) {
                                    BackupExpandBlock(
                                        style = SettingRowStyle.Card,
                                        isLastRow = backupIsLast,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // 6. 网络（v1.4.8 A4：DoH 设置子页入口，不进可拖拽体系）
            item { SettingsCaption("网络") }
            item {
                AppCard(
                    shape = RoundedCornerShape(V5.CornerCard),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SegItem(
                            position = SegPos.Single,
                            icon = Icons.Outlined.Link,
                            title = "网络",
                            subtitle = if (dohEnabled) "DoH 已开启" else "DoH 已关闭 · DNS over HTTPS",
                            onClick = onNetworkSettingsClick,
                        )
                    }
                }
            }
            // 7. 插件（289：“插件市场源”占位格已删，插件中心只剩工具磁贴一个入口）
            item { SettingsCaption("插件") }
            item {
                AppCard(
                    shape = RoundedCornerShape(V5.CornerCard),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SegItem(
                    position = SegPos.First,
                    icon = Icons.Outlined.VpnKey,
                    title = "Clash 代理",
                    subtitle = "订阅 + 每源走代理开关",
                    onClick = onClashSettingsClick,
                )
                SegItem(
                    position = SegPos.Last,
                    icon = Icons.Outlined.Extension,
                    title = "显示不兼容插件",
                    subtitle = (if (showIncompatible) "显示" else "隐藏") + " · apk/import 仅查看",
                    onClick = { PluginStore.setShowIncompatible(context, !showIncompatible) },
                    trailing = {
                        AppSwitch(
                            checked = showIncompatible,
                            onCheckedChange = { PluginStore.setShowIncompatible(context, it) },
                        )
                    },
                )
                    }
                }
            }
            // 8. 帮助
            item { SettingsCaption("帮助") }
            item {
                AppCard(
                    shape = RoundedCornerShape(V5.CornerCard),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SegItem(
                    position = SegPos.First,
                    icon = Icons.Outlined.BugReport,
                    title = "运行日志悬浮窗",
                    subtitle = if (logEnabled) "已开启 · 小泡可拖动，点开看日志" else "关闭 · 排查问题时打开",
                    onClick = { LogStore.setEnabled(context, !logEnabled) },
                    trailing = {
                        AppSwitch(
                            checked = logEnabled,
                            onCheckedChange = { LogStore.setEnabled(context, it) },
                        )
                    },
                )
                // v0.188.0：关于不可点，版本信息在副标题里展示
                SegItem(
                    position = SegPos.Middle,
                    icon = Icons.Outlined.Info,
                    title = "关于侠影",
                    subtitle = "v${com.movieshell.BuildConfig.VERSION_NAME} · 本地影视聚合",
                    leading = {
                        Image(
                            painter = painterResource(com.movieshell.R.drawable.ic_brand_mark),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                    },
                )
                // v0.30.0：更新日志独立入口
                // v1.2.14：副标题写实时值——静态日志里记了多少版。
                SegItem(
                    position = SegPos.Last,
                    icon = Icons.Outlined.NewReleases,
                    title = "更新日志",
                    subtitle = "共 ${CHANGELOG.size} 版更新记录",
                    onClick = onChangelogClick,
                )
                    }
                }
            }
        }
        SettingDragOverlay(dragState, SettingRowStyle.Card, onOpenMarketClick, dataActions)
    }
}

@Composable
internal fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    // v0.279.0 旧版：分组 = 10.5sp t3 小标题 + s1 圆角 10 的卡片（行间 1px 分隔线）。
    Column {
        SettingsCaption(title)
        Spacer(Modifier.height(6.dp))
        V5Tile(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
            content()
        }
    }
}

@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
    // v0.188.0：不可点行（如关于行）传 false，去掉点击态与右侧箭头。
    clickable: Boolean = true,
    divider: Boolean = true,
) {
    // v0.279.0 旧版：44dp 行，图标 17 t2，标题 13/500，副标题 11 t3，右侧箭头 t3 或开关。
    V5Row(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = if (clickable) onClick else null,
        showChevron = clickable && trailing == null,
        divider = divider,
        trailing = if (clickable && trailing != null) ({ trailing() }) else null,
    )
}

/** v0.292.0 V5：设置分组标题（labelLarge primary，32dp 内缩 = 页边距 16 + 16，上 24 下 8）。 */
@Composable
private fun SettingsCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/**
 * v0.188.0：旧字幕 key 条数统计（只读不删）。
 * 判定口径与 PlayerSettings.clearLegacyPerShowKeys 一致：前缀 + 纯数字 hash、
 * 不含竖线分隔符；新 key 一律含竖线，不会被计入。前缀常量在 PlayerSettings 里是
 * 私有的，这里逐字照抄三处前缀，改动前缀时两处须同步。
 */
internal fun countLegacyPerShowKeys(context: Context): Int {
    val prefs = context.getSharedPreferences("movieshell_prefs", Context.MODE_PRIVATE)
    val prefixes = listOf("skip_intro_vod_", "skip_outro_vod_", "subtitle_vod_")
    return prefs.all.keys.count { key ->
        val prefix = prefixes.firstOrNull { key.startsWith(it) } ?: return@count false
        val rest = key.removePrefix(prefix)
        rest.isNotEmpty() && rest.toIntOrNull() != null && !rest.contains("|")
    }
}

@Composable
internal fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    // v1.3.25 UI 优化：可选副标题（放 TechDialog 的 subtitle 位）与每档小字说明
    //（按选项文案取），都不传时与原来完全一致，其他调用处不受影响。
    subtitle: String? = null,
    descriptions: Map<String, String>? = null,
) {
    TechDialog(
        onDismissRequest = onDismiss,
        title = title,
        subtitle = subtitle,
    ) {
            // v1.2.15：收紧行高 + 选中行浅底高亮（效果图定版）；点行即选即关，不加确认按钮。
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { option ->
                    val isSelected = option == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                else Color.Transparent,
                            )
                            .clickable { onSelect(option) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                option,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            descriptions?.get(option)?.let { desc ->
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelect(option) },
                        )
                    }
                }
            }
    }
}

/**
 * v0.80.0：信息卡统计项（追剧 / 历史 / 源）。
 * v0.292.0 V5：surfaceContainerLowest 24dp 圆角卡，居中 56dp 统计环（CircularProgressIndicator 外观）
 * + titleSmall 标签 + bodySmall 副标签。
 */
@Composable
private fun StatGaugeTile(
    label: String,
    value: String,
    progress: Float,
    ringColor: Color,
    modifier: Modifier = Modifier,
    sub: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    V5Tile(
        modifier = modifier,
        onClick = onClick,
        color = cs.surfaceContainerLowest,
        corner = V5.CornerCard,
        padding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            V5RingGauge(value = value, progress = progress, color = ringColor)
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1)
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * v0.80.0：快捷宫格入口。
 * v0.292.0 V5：工具磁贴——48dp primaryContainer 16dp 圆角图标井 + labelMedium 文字。
 */
@Composable
private fun QuickEntry(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(V5.CornerLg))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(V5.CornerLg))
                .background(cs.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 分段列表位置：决定外圆角（组首尾 24dp，组内 6dp）。 */
internal enum class SegPos { First, Middle, Last, Single }

/**
 * v0.292.0 V5：Android 16 式分段列表项——surfaceContainerLowest 底，组首尾外角 24dp、内角 6dp，
 * 前置 40dp primaryContainer 圆形图标，bodyLarge 标题 + bodyMedium 副标题，尾部开关/箭头/自定义；
 * [content] 放在标题下方（主题模式分段按钮、主题色色圈）。
 */
@Composable
internal fun SegItem(
    position: SegPos,
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    // v1.3.25 UI 优化：禁用态（灰态说明行，如暂无每日备份）——整行 45% 透明、
    // 不可点、无箭头；默认 true，其他调用处不受影响。
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    // v0.294.4：自定义 40dp 前导图（关于侠影用品牌标）；为 null 走默认 primaryContainer 圆底图标
    leading: (@Composable () -> Unit)? = null,
    // v0.294.10：content 必须放最后——尾随 lambda 写法对应最后一个参数（曾因 leading 排在后面导致主题行失效）
    content: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val big = V5.CornerCard
    val small = V5.CornerXs
    val shape = when (position) {
        SegPos.First -> RoundedCornerShape(topStart = big, topEnd = big, bottomStart = small, bottomEnd = small)
        SegPos.Middle -> RoundedCornerShape(small)
        SegPos.Last -> RoundedCornerShape(topStart = small, topEnd = small, bottomStart = big, bottomEnd = big)
        SegPos.Single -> RoundedCornerShape(big)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cs.surfaceContainerLowest)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (!enabled) Modifier.alpha(0.45f) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
            } else Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                trailing != null -> {
                    Spacer(Modifier.width(8.dp))
                    trailing()
                }
                onClick != null && enabled && content == null -> {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant)
                }
            }
        }
        if (content != null) {
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}
