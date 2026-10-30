package com.movieshell.ui.settings

import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import com.movieshell.ui.components.V5Segmented
import com.movieshell.ui.components.V5RingGauge
import com.movieshell.ui.components.V5Dot
import com.movieshell.ui.components.V5Tile
import com.movieshell.ui.theme.seedColorScheme
import com.movieshell.ui.theme.isDark
import com.movieshell.ui.theme.ok

import com.movieshell.ui.theme.V5
import androidx.compose.ui.text.font.FontFamily
import com.movieshell.ui.theme.ok

import com.movieshell.ui.components.V5Tile
import com.movieshell.ui.components.V5Row
import com.movieshell.ui.components.V5Dot
import com.movieshell.ui.components.V5RingGauge
import com.movieshell.ui.components.V5Segmented
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movieshell.data.download.DownloadManager
import com.movieshell.data.download.FollowCheckWorker
import com.movieshell.data.download.SubRefreshWorker
import com.movieshell.data.repo.AtomicFile
import com.movieshell.data.repo.BackupManager
import com.movieshell.data.repo.CloudSyncManager
import com.movieshell.data.repo.DownloadStore
import com.movieshell.data.repo.FollowStore
import com.movieshell.data.repo.SubRefreshStore
import com.movieshell.data.source.JsRuntime
import com.movieshell.data.repo.CrashHandler
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.LogStore
import com.movieshell.data.repo.StoragePaths
import com.movieshell.data.repo.PlayerEngine
import com.movieshell.player.MpvPluginLoader
import com.movieshell.ui.preview.fileProviderAuthority
import com.movieshell.data.repo.PlayerSettings
import com.movieshell.player.PlayerManager
import com.movieshell.data.repo.PluginMarket
import com.movieshell.data.repo.PluginStore
import com.movieshell.data.repo.ThemeStore
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.movieshell.data.repo.ApiSourceStore
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.PhpSourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SourceStateStore
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.player.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Image
import coil.imageLoader
import com.movieshell.data.repo.BackgroundStore

// v0.27.0 Soft Tech 换皮：分组卡片走 AppCard，分组标题走 V5SectionTitle，
// 对话框 20dp 圆角 + 输入框走 AppTextField，按钮走 AppButton。逻辑与文案零变更。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onManualClick: () -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    onChangelogClick: () -> Unit = {},
    onSourcesClick: () -> Unit = {}, // v0.36.0：站源管理入口（tab 删除后搬进设置）
    onParseSettingsClick: () -> Unit = {}, // v0.78.2：解析接口子页面入口
    onPluginCenterClick: () -> Unit = {}, // v0.127.0：插件中心（插件/服务/市场三页签，原两入口合并）
    onSniffPatternsClick: () -> Unit = {}, // v0.79.0：嗅探正则管理（v0.168.0 起入口在快捷宫格）
    onHistoryClick: () -> Unit = {}, // v0.80.0：快捷宫格-观看历史
    onFollowClick: () -> Unit = {}, // v0.90.0：快捷宫格-我的追剧（资料卡已可点进历史/追剧）
) {
    val context = LocalContext.current
    // v0.7.1：主题读持久化 ThemeStore，不再是本地假状态
    // v0.80.0：主题改内联三段选择，不再弹窗
    val themeMode by ThemeStore.mode.collectAsState()
    var backupExpanded by remember { mutableStateOf(false) }
    // v0.216.0（C15）：中断恢复快照入口——展开备份区时查一次，恢复动作后刷新
    var hasPreSnapshot by remember { mutableStateOf(false) }
    LaunchedEffect(backupExpanded) {
        if (backupExpanded) hasPreSnapshot = BackupManager.hasPreRestoreSnapshot(context)
    }
    // v0.51.0：WebDAV 云同步
    val scope = rememberCoroutineScope()
    var showCloudDialog by remember { mutableStateOf(false) }
    var cloudWorking by remember { mutableStateOf(false) }
    var lastSyncText by remember { mutableStateOf("") }
    // v0.186.0：云端更新的冲突确认弹窗（已有 TechDialog 机制，不新开入口）
    var showCloudConflict by remember { mutableStateOf(false) }
    var cloudConflictText by remember { mutableStateOf("") }
    // v0.20.0：播放器内核（Media3 默认，libmpv 可选，记忆选择）
    val engine by PlayerSettings.engine.collectAsState()
    var showEngineDialog by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf("计算中…") }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    // v0.188.0：旧字幕记忆清理入口用——只在用户点清理行时统计条数、点确认时删除，
    // 进设置页不自动删任何用户设置。
    var showClearLegacySubtitleDialog by remember { mutableStateOf(false) }
    var legacySubtitleCount by remember { mutableIntStateOf(-1) }
    // v0.78.2：解析接口已搬进子页面（ParseSettingsScreen），本页只留入口
    // v0.23.0：显示不兼容插件开关
    val showIncompatible by PluginStore.showIncompatible.collectAsState()
    // v0.26.0：追剧更新检测开关 + 下载并行数
    var followCheckEnabled by remember { mutableStateOf(FollowStore.isCheckEnabled(context)) }
    // v0.46.0：订阅自动刷新开关
    var subRefreshEnabled by remember { mutableStateOf(SubRefreshStore.isEnabled(context)) }
    var maxParallel by remember { mutableStateOf(DownloadStore.maxParallel(context)) }

    // v0.7.2：备份直接导出到 xiaying/backup/，不再弹系统文件选择器
    // v0.186.0：exportJson 改 suspend（追剧/书架/音乐走 suspend 读接口），这里进协程
    fun exportBackup() {
        scope.launch {
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(StoragePaths.backupDir(context), "movieshell_backup_$ts.json")
                // v0.270.0（T26）：导出拼 JSON 与原子写盘都在 IO 线程——原来
                // scope.launch 是主线程，备份大时写盘（含 fsync）会卡界面。
                var json = ""
                var exportError: String? = null
                withContext(Dispatchers.IO) {
                    when (val r = BackupManager.exportJson(context)) {
                        is BackupManager.ExportResult.Ok -> json = r.json
                        is BackupManager.ExportResult.Failed -> exportError = r.reason
                    }
                }
                if (exportError != null) {
                    LogBus.e("Backup", "备份导出失败：$exportError")
                    AppToast.show(context, "导出失败：$exportError", long = true)
                    return@launch
                }
                // v0.170.0：原子写盘，写盘失败如实报错，不谎报已导出
                val written = withContext(Dispatchers.IO) { AtomicFile.writeText(file, json) }
                if (!written) {
                    LogBus.e("Backup", "备份导出失败：写盘失败")
                    AppToast.show(context, "导出失败：写盘失败", long = true)
                    return@launch
                }
                LogBus.i("Backup", "备份已导出：${file.absolutePath}")
                AppToast.show(context, "备份已导出：${file.absolutePath}", long = true)
            } catch (e: Exception) {
                LogBus.e("Backup", "备份导出失败：${e.message}")
                AppToast.show(context, "导出失败：${e.message}", long = true)
            }
        }
    }

    /** v0.25.0：崩溃日志打包 zip，经系统分享（FileProvider + ACTION_SEND） */
    fun exportCrashLog() {
        try {
            val zip = CrashHandler.exportZip(context)
            if (zip == null) {
                AppToast.show(context, "暂无崩溃日志", long = false)
                return
            }
            val uri = FileProvider.getUriForFile(
                context, fileProviderAuthority(context), zip
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("crash", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "分享崩溃日志"))
            LogBus.i("CrashHandler", "崩溃日志已导出分享：${zip.name}")
        } catch (e: Exception) {
            LogBus.e("CrashHandler", "崩溃日志导出失败：${e.message}")
            AppToast.show(context, "导出失败：${e.message}", long = true)
        }
    }
    // 恢复仍需用户选文件，保留 picker
    // v0.186.0：importJson 改 suspend（新段走 suspend 写接口），这里进协程
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                // v0.270.0（T26）：读备份文件与恢复写盘都在 IO 线程，原来在主线程读字节。
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    }
                } ?: return@launch
                when (val r = withContext(Dispatchers.IO) { BackupManager.importJson(context, text) }) {
                    is BackupManager.RestoreResult.Ok ->
                        AppToast.show(context, "恢复成功", long = false)
                    is BackupManager.RestoreResult.Failed ->
                        AppToast.show(context, "恢复失败：${r.reason}", long = true)
                }
            } catch (e: Exception) {
                AppToast.show(context, "恢复失败：${e.message}", long = true)
            }
        }
    }

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
    // v0.82.0：背景底图
    var showBgMenu by remember { mutableStateOf(false) }
    val bgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = BackgroundStore.setCustomBg(context, uri)
            AppToast.show(context, if (ok) "背景底图已更换" else "设置失败，换张图片试试", long = false)
        }
    }
    // v0.247.0（F41）：派生值（开关/统计/当前源/缓存占用）原来只在首次
    // 组合拉一次，别的入口改后回设置页仍显示旧值；抽成 refreshDerived，
    // 首屏与每次 ON_RESUME 各跑一次（沿用 HomeScreen 的 resume 刷新口径）。
    // 首屏那次与首次 ON_RESUME 会重复跑一轮，结果幂等、值相同，接受这点浪费。
    suspend fun refreshDerived() {
        // 开关重读：备份恢复等跨入口写盘后回页即刷新
        followCheckEnabled = FollowStore.isCheckEnabled(context)
        subRefreshEnabled = SubRefreshStore.isEnabled(context)
        maxParallel = DownloadStore.maxParallel(context)
        runCatching { BackgroundStore.load(context) }
        // v0.101.0：缓存占用（视频 m3u8 分片 + Coil 图片磁盘缓存）
        cacheSize = runCatching { refreshCacheSize(context) }.getOrDefault(cacheSize)
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
    val customBg by BackgroundStore.customBg.collectAsState()
    val gravityRotate by PlayerSettings.gravityRotate.collectAsState()
    val logEnabled by LogStore.enabled.collectAsState()
    val themeKey by ThemeStore.themeKey.collectAsState()
    var jsTick by remember { mutableStateOf(0) }
    val crashCount = remember { CrashHandler.list(context).size }
    val statText: (Int) -> String = { n -> if (!statsLoaded) "…" else if (statsFailed) "—" else "$n" }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surfaceContainer),
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
                    .padding(horizontal = 8.dp, vertical = 16.dp),
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
            // 亮色 / 暗色 / 跟随系统（v0.80.0 内联三段）——M3 分段按钮
            SegItem(position = SegPos.First, icon = Icons.Outlined.Contrast, title = "主题模式") {
                val modes = listOf(
                    "亮色" to ThemeStore.MODE_LIGHT,
                    "暗色" to ThemeStore.MODE_DARK,
                    "跟随系统" to ThemeStore.MODE_SYSTEM,
                )
                V5Segmented(
                    options = modes.map { it.first },
                    selected = modes.indexOfFirst { it.second == themeMode },
                    onSelect = { ThemeStore.setMode(context, modes[it].second) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            // 主题色：海报取色（V5 新增）/ 系统取色（Android 12+）/ 单色预设 / 炫彩渐变预设（v0.87.1），横滑
            val colorSource by ThemeStore.source.collectAsState()
            val dark = cs.isDark
            SegItem(
                position = SegPos.Middle,
                icon = Icons.Outlined.Palette,
                title = "主题色",
                subtitle = when (colorSource) {
                    ThemeStore.SOURCE_POSTER -> "海报取色 · 跟随首页当前海报"
                    ThemeStore.SOURCE_DYNAMIC -> "系统取色 · 跟随壁纸"
                    else -> if (themeKey.startsWith("g:")) "炫彩预设 · 锚点色作种子" else "预设色 · 按 M3 色调生成整套配色"
                },
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    item {
                        ThemeSwatch(
                            selected = themeKey == "p:poster",
                            label = "海报取色",
                            onClick = { ThemeStore.setSource(context, ThemeStore.SOURCE_POSTER) },
                        ) {
                            PosterSwatchArt()
                        }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        item {
                            ThemeSwatch(
                                selected = themeKey == "d:dynamic",
                                label = "系统取色",
                                onClick = { ThemeStore.setSource(context, ThemeStore.SOURCE_DYNAMIC) },
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(cs.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Outlined.Wallpaper, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                    items(ThemeStore.COLOR_OPTIONS) { argb ->
                        ThemeSwatch(
                            selected = themeKey == "c:$argb",
                            label = "主题色",
                            onClick = { ThemeStore.setColor(context, argb) },
                        ) {
                            SeedSwatchArt(seedColorScheme(argb, dark))
                        }
                    }
                    items(ThemeStore.GRADIENT_OPTIONS) { preset ->
                        ThemeSwatch(
                            selected = themeKey == "g:${preset.id}",
                            label = preset.name,
                            onClick = { ThemeStore.setGradient(context, preset.id) },
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Brush.sweepGradient(preset.colors.map { Color(it) } + Color(preset.colors.first()))),
                            )
                        }
                    }
                }
            }
        }
        item {
            // v0.82.0：背景底图
            SegItem(
                position = SegPos.Last,
                icon = Icons.Outlined.Wallpaper,
                title = "背景底图",
                subtitle = (if (customBg != null) "已自定义" else "默认") + " · 首页生效",
                onClick = {
                    if (customBg != null) showBgMenu = true
                    else bgPicker.launch("image/*")
                },
            )
        }
        // 4. 播放 · 解析
        item { SettingsCaption("播放 · 解析") }
        item {
            // v0.20.0：播放器内核——Media3（默认）/ libmpv；v0.29.0：libmpv 插件化，未安装时标注
            SegItem(
                position = SegPos.First,
                icon = Icons.Outlined.SmartDisplay,
                title = "播放器内核",
                subtitle = when {
                    engine == PlayerEngine.MEDIA3 -> "Media3（默认）"
                    MpvPluginLoader.isInstalled(context) -> "libmpv · 插件内核"
                    else -> "libmpv · 插件未安装"
                },
                onClick = { showEngineDialog = true },
            )
        }
        item {
            // v0.27.7：重力感应自动横屏（默认开）
            SegItem(
                position = SegPos.Middle,
                icon = Icons.Outlined.ScreenRotation,
                title = "重力感应横屏",
                subtitle = if (gravityRotate) "自动 · 随手机方向" else "关闭",
                onClick = { PlayerSettings.setGravityRotate(context, !gravityRotate) },
                trailing = {
                    AppSwitch(
                        checked = gravityRotate,
                        onCheckedChange = { PlayerSettings.setGravityRotate(context, it) },
                    )
                },
            )
        }
        item {
            // v0.114.0：JS 引擎（V8）状态——点按执行一次真实连接检测
            val jsState = remember(jsTick) {
                when {
                    JsRuntime.jsSourcesSupported -> 1
                    JsRuntime.lastDisableReason != null -> 2
                    else -> 0
                }
            }
            SegItem(
                position = SegPos.Last,
                icon = Icons.Outlined.Code,
                title = "JS 引擎",
                subtitle = when (jsState) {
                    1 -> "V8 可用 · JS 源正常"
                    2 -> "已禁用 · " + (JsRuntime.lastDisableReason ?: "JS 源已禁用")
                    else -> "未检测 · 点按检测"
                },
                onClick = {
                    scope.launch {
                        runCatching { JsRuntime.sandbox() }
                        jsTick++
                    }
                },
                trailing = {
                    V5Dot(
                        when (jsState) { 1 -> cs.ok; 2 -> cs.error; else -> cs.outline },
                        size = 10.dp,
                    )
                },
            )
        }
        // 5. 数据
        item { SettingsCaption("数据") }
        item {
            // v0.46.0：订阅自动刷新开关
            SegItem(
                position = SegPos.First,
                icon = Icons.Outlined.Sync,
                title = "订阅自动刷新",
                subtitle = (if (subRefreshEnabled) "每天 1 次" else "关闭") + " · 失效的在订阅管理标出",
                onClick = {
                    val next = !subRefreshEnabled
                    SubRefreshStore.setEnabled(context, next)
                    subRefreshEnabled = next
                    SubRefreshWorker.reschedule(context)
                },
                trailing = {
                    AppSwitch(
                        checked = subRefreshEnabled,
                        onCheckedChange = {
                            SubRefreshStore.setEnabled(context, it)
                            subRefreshEnabled = it
                            SubRefreshWorker.reschedule(context)
                        },
                    )
                },
            )
        }
        item {
            SegItem(
                position = SegPos.Middle,
                icon = Icons.Outlined.NotificationsActive,
                title = "追剧更新提醒",
                subtitle = (if (followCheckEnabled) "每天 2 次" else "关闭") + " · 有新集数发通知",
                onClick = {
                    val next = !followCheckEnabled
                    FollowStore.setCheckEnabled(context, next)
                    followCheckEnabled = next
                    FollowCheckWorker.reschedule(context)
                },
                trailing = {
                    AppSwitch(
                        checked = followCheckEnabled,
                        onCheckedChange = {
                            FollowStore.setCheckEnabled(context, it)
                            followCheckEnabled = it
                            FollowCheckWorker.reschedule(context)
                        },
                    )
                },
            )
        }
        item {
            // v0.51.0：WebDAV 云同步；v0.186.0：范围是订阅、追剧、书架、音乐、直播，不含插件与设置
            val cloudConfigured = CloudSyncManager.isConfigured(context)
            SegItem(
                position = SegPos.Middle,
                icon = Icons.Outlined.CloudSync,
                title = "WebDAV 云同步",
                subtitle = if (cloudConfigured) "已配置 · " + CloudSyncManager.lastSyncText(context) else "未配置 · 坚果云/Alist/群晖",
                onClick = {
                    lastSyncText = CloudSyncManager.lastSyncText(context)
                    showCloudDialog = true
                },
            )
        }
        item {
            SegItem(
                position = SegPos.Middle,
                icon = Icons.Outlined.Backup,
                title = "备份与恢复",
                subtitle = "导出 / 导入 · 订阅·追剧·书架·音乐·直播",
                onClick = { backupExpanded = !backupExpanded },
                trailing = {
                    Icon(
                        imageVector = if (backupExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (backupExpanded) "收起" else "展开",
                        tint = cs.onSurfaceVariant,
                    )
                },
            )
        }
        item {
            AnimatedVisibility(visible = backupExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SegItem(
                        position = SegPos.Middle,
                        icon = Icons.Outlined.Info,
                        title = "说明",
                        subtitle = "导出 / 导入 JSON 备份文件：订阅、追剧、书架、音乐、直播（不含插件、下载、播放历史与设置）",
                    )
                    SegItem(
                        position = SegPos.Middle,
                        icon = Icons.Outlined.Backup,
                        title = "备份到文件",
                        subtitle = "导出到 /storage/emulated/0/xiaying/backup/",
                        onClick = { exportBackup() },
                    )
                    SegItem(
                        position = SegPos.Middle,
                        icon = Icons.Outlined.Restore,
                        title = "从文件恢复",
                        subtitle = "从 JSON 备份文件恢复订阅、当前规则、追剧、书架、音乐、直播",
                        onClick = { importLauncher.launch("application/json") },
                    )
                    // v0.216.0（C15）：上次恢复中断/失败后留下的恢复前快照
                    if (hasPreSnapshot) {
                        SegItem(
                            position = SegPos.Middle,
                            icon = Icons.Outlined.History,
                            title = "恢复中断前的备份",
                            subtitle = "上次恢复没完成，导回恢复开始前的数据",
                            onClick = {
                                scope.launch {
                                    when (val r = BackupManager.restorePreRestoreSnapshot(context)) {
                                        is BackupManager.RestoreResult.Ok -> {
                                            hasPreSnapshot = false
                                            AppToast.show(context, "已恢复到中断前的数据", long = false)
                                        }
                                        is BackupManager.RestoreResult.Failed ->
                                            AppToast.show(context, "恢复失败：${r.reason}", long = true)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        item {
            SegItem(
                position = SegPos.Middle,
                icon = Icons.Outlined.Downloading,
                title = "同时下载数",
                subtitle = "$maxParallel 个并行 · 点按切换（1-3）",
                onClick = {
                    val next = if (maxParallel >= 3) 1 else maxParallel + 1
                    DownloadStore.setMaxParallel(context, next)
                    maxParallel = next
                    DownloadManager.pump(context)
                },
                trailing = {
                    Text("$maxParallel", style = MaterialTheme.typography.titleMedium, color = cs.primary)
                },
            )
        }
        item {
            SegItem(
                position = SegPos.Middle,
                icon = Icons.Outlined.CleaningServices,
                title = "清理缓存",
                subtitle = "$cacheSize · 视频分片 + 图片缓存",
                onClick = { showClearCacheDialog = true },
            )
        }
        item {
            // v0.188.0：旧字幕 key 清理入口——点开先说明条数，确认后才删
            SegItem(
                position = SegPos.Last,
                icon = Icons.Outlined.Subtitles,
                title = "清理旧字幕记忆",
                subtitle = "只删旧格式按剧记忆，需手动确认",
                onClick = {
                    legacySubtitleCount = countLegacyPerShowKeys(context)
                    showClearLegacySubtitleDialog = true
                },
            )
        }
        // 6. 插件（289：“插件市场源”占位格已删，插件中心只剩工具磁贴一个入口）
        item { SettingsCaption("插件") }
        item {
            SegItem(
                position = SegPos.Single,
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
        // 7. 帮助
        item { SettingsCaption("帮助") }
        item {
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
        }
        item {
            // v0.25.0：崩溃日志导出（xiaying/crash/ 打包 zip 经系统分享）
            SegItem(
                position = SegPos.Middle,
                icon = Icons.Outlined.Report,
                title = "导出崩溃日志",
                subtitle = if (crashCount > 0) "共 $crashCount 份 · 打包分享" else "暂无崩溃记录",
                onClick = { exportCrashLog() },
            )
        }
        item {
            // v0.188.0：关于不可点，版本信息在副标题里展示
            SegItem(
                position = SegPos.Middle,
                icon = Icons.Outlined.Info,
                title = "关于侠影",
                subtitle = "v${com.movieshell.BuildConfig.VERSION_NAME} · 本地影视聚合",
            )
        }
        item {
            // v0.30.0：更新日志独立入口
            SegItem(
                position = SegPos.Last,
                icon = Icons.Outlined.NewReleases,
                title = "更新日志",
                subtitle = "每一版的更新内容",
                onClick = onChangelogClick,
            )
        }
    }

    // v0.20.0：播放器内核选择（持久化，播放器内一键切换走同一偏好）
    // v0.29.0：libmpv 插件化——未安装时选 libmpv 提示去插件市场，不切换
    if (showEngineDialog) {
        SingleChoiceDialog(
            title = "播放器内核",
            options = listOf("Media3（默认）", "libmpv"),
            selected = if (engine == PlayerEngine.MEDIA3) "Media3（默认）" else "libmpv",
            onDismiss = { showEngineDialog = false },
            onSelect = {
                if (it.startsWith("Media3") || MpvPluginLoader.isInstalled(context)) {
                    PlayerSettings.setEngine(
                        context,
                        if (it.startsWith("Media3")) PlayerEngine.MEDIA3 else PlayerEngine.LIBMPV,
                    )
                    showEngineDialog = false
                } else {
                    AppToast.show(context, "去 设置-插件中心 安装 libmpv 播放器引擎", long = true)
                }
            },
        )
    }
    if (showClearCacheDialog) {
        TechDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = "清理缓存",
            confirmText = "确定",
            onConfirm = {
                showClearCacheDialog = false
                scope.launch(Dispatchers.IO) {
                    // 先关 SimpleCache 再删目录（顺序不能反）；Coil 用官方 clear()，
                    // 不直接删它开着的磁盘缓存 journal。
                    PlayerManager.releaseCache()
                    runCatching { context.imageLoader.diskCache?.clear() }
                    cacheDirs(context).forEach { dir ->
                        runCatching { dir.deleteRecursively() }
                    }
                    cacheSize = refreshCacheSize(context)
                    withContext(Dispatchers.Main) {
                        AppToast.show(context, "缓存已清理", long = false)
                    }
                }
            },
        ) {
            Text(
                "清理视频缓存（m3u8 分片）与图片缓存，共 $cacheSize。播放记录与站源配置不会被删除。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    // v0.188.0：旧字幕记忆清理确认——清理前说明条数，确认后提示结果；
    // 只调 186 已提供的 clearLegacyPerShowKeys，不自动删用户现有设置。
    if (showClearLegacySubtitleDialog) {
        TechDialog(
            onDismissRequest = { showClearLegacySubtitleDialog = false },
            title = "清理旧字幕记忆",
            confirmText = "清理",
            onConfirm = {
                showClearLegacySubtitleDialog = false
                val n = PlayerSettings.clearLegacyPerShowKeys(context)
                legacySubtitleCount = -1
                AppToast.show(context, if (n > 0) "已清理旧字幕记忆 $n 条" else "没有旧字幕记忆可清理", long = false)
            },
        ) {
            Text(
                if (legacySubtitleCount >= 0) {
                    "共 $legacySubtitleCount 条旧格式按剧记忆（旧字幕地址与片头片尾跳过秒数）。" +
                        "只删旧格式 key，新格式记忆不受影响；还没在新格式下打开过的剧，" +
                        "旧记忆删后不再恢复，确认后再清理。"
                } else {
                    "只删旧格式按剧记忆，新格式记忆不受影响。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    // v0.51.0：WebDAV 云同步配置 + 备份/恢复
    if (showCloudDialog) {
        val cfg = remember { CloudSyncManager.getConfig(context) }
        var urlInput by remember { mutableStateOf(cfg.url) }
        var userInput by remember { mutableStateOf(cfg.user) }
        var passInput by remember { mutableStateOf(cfg.pass) }
        // v0.217.0（F68）：密码默认遮掩，可点“显示”临时查看——原来
        // 明文躺在对话框里，截屏、旁人瞥见都直接看到。
        var showPass by remember { mutableStateOf(false) }
        // v0.186.0：局域网 http 风险确认，默认不勾选，勾选状态随配置持久化
        var allowLanHttp by remember { mutableStateOf(cfg.allowLanHttp) }
        TechDialog(
            onDismissRequest = { if (!cloudWorking) showCloudDialog = false },
            title = "WebDAV 云同步",
            dismissText = "关闭",
        ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(lastSyncText, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    TechTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = "服务器地址",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !cloudWorking,
                    )
                    Spacer(Modifier.height(8.dp))
                    TechTextField(
                        value = userInput,
                        onValueChange = { userInput = it },
                        placeholder = "账号",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !cloudWorking,
                    )
                    Spacer(Modifier.height(8.dp))
                    TechTextField(
                        value = passInput,
                        onValueChange = { passInput = it },
                        placeholder = "密码",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !cloudWorking,
                        visualTransformation = if (showPass) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { showPass = !showPass },
                            enabled = !cloudWorking,
                        ) {
                            Text(if (showPass) "隐藏" else "显示", fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = allowLanHttp,
                            onCheckedChange = { allowLanHttp = it },
                            enabled = !cloudWorking,
                        )
                        Text(
                            "我知道风险：允许局域网 http（密码仅 Base64 编码传输）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        AppButton(
                            text = "测试连接",
                            variant = AppButtonVariant.Ghost,
                            enabled = !cloudWorking,
                            onClick = {
                                // v0.186.0：地址非法不保存、不测试，直接报校验原因
                                if (!CloudSyncManager.saveConfig(
                                        context, urlInput, userInput, passInput, allowLanHttp,
                                    )
                                ) {
                                    AppToast.show(context, CloudSyncManager.validateUrl(urlInput.trim(), allowLanHttp)
                                            ?: "地址无效", long = true)
                                    return@AppButton
                                }
                                cloudWorking = true
                                scope.launch {
                                    val err = CloudSyncManager.test(context)
                                    cloudWorking = false
                                    AppToast.show(context, err ?: "连接成功", long = false)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        AppButton(
                            text = "备份到云端",
                            enabled = !cloudWorking,
                            onClick = {
                                if (!CloudSyncManager.saveConfig(
                                        context, urlInput, userInput, passInput, allowLanHttp,
                                    )
                                ) {
                                    AppToast.show(context, CloudSyncManager.validateUrl(urlInput.trim(), allowLanHttp)
                                            ?: "地址无效", long = true)
                                    return@AppButton
                                }
                                cloudWorking = true
                                scope.launch {
                                    when (val r = CloudSyncManager.backup(context)) {
                                        is CloudSyncManager.SyncResult.Ok ->
                                            AppToast.show(context, "备份已上传", long = false)
                                        is CloudSyncManager.SyncResult.Failed ->
                                            AppToast.show(context, r.reason, long = true)
                                        is CloudSyncManager.SyncResult.Conflict -> {
                                            cloudConflictText = r.reason
                                            showCloudConflict = true
                                        }
                                    }
                                    cloudWorking = false
                                    lastSyncText = CloudSyncManager.lastSyncText(context)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    AppButton(
                        text = "从云端恢复",
                        variant = AppButtonVariant.Ghost,
                        enabled = !cloudWorking,
                        onClick = {
                            if (!CloudSyncManager.saveConfig(
                                    context, urlInput, userInput, passInput, allowLanHttp,
                                )
                            ) {
                                AppToast.show(context, CloudSyncManager.validateUrl(urlInput.trim(), allowLanHttp)
                                        ?: "地址无效", long = true)
                                return@AppButton
                            }
                            cloudWorking = true
                            scope.launch {
                                when (val r = CloudSyncManager.restore(context)) {
                                    is CloudSyncManager.SyncResult.Ok ->
                                        AppToast.show(context, "已从云端恢复", long = false)
                                    is CloudSyncManager.SyncResult.Failed ->
                                        AppToast.show(context, r.reason, long = true)
                                    is CloudSyncManager.SyncResult.Conflict ->
                                        AppToast.show(context, r.reason, long = true)
                                }
                                cloudWorking = false
                                lastSyncText = CloudSyncManager.lastSyncText(context)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
        }
    }

    // v0.186.0：云端更新冲突确认（已有 TechDialog 机制，不新开入口）
    if (showCloudConflict) {
        TechDialog(
            onDismissRequest = { if (!cloudWorking) showCloudConflict = false },
            title = "云端备份更新",
            confirmText = "仍要上传",
            dismissText = "取消",
            onConfirm = {
                showCloudConflict = false
                cloudWorking = true
                scope.launch {
                    when (val r = CloudSyncManager.backup(context, force = true)) {
                        is CloudSyncManager.SyncResult.Ok ->
                            AppToast.show(context, "备份已上传", long = false)
                        is CloudSyncManager.SyncResult.Failed ->
                            AppToast.show(context, r.reason, long = true)
                        is CloudSyncManager.SyncResult.Conflict ->
                            AppToast.show(context, r.reason, long = true)
                    }
                    cloudWorking = false
                    lastSyncText = CloudSyncManager.lastSyncText(context)
                }
            },
        ) {
            Text(cloudConflictText, style = MaterialTheme.typography.bodySmall)
        }
    }

    // v0.82.0：背景底图操作菜单
    if (showBgMenu) {
        TechDialog(
            onDismissRequest = { showBgMenu = false },
            title = "背景底图",
            confirmText = "更换图片",
            onConfirm = {
                showBgMenu = false
                bgPicker.launch("image/*")
            },
        ) {
            Text("更换首页的背景底图，或恢复默认。")
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = {
                    showBgMenu = false
                    scope.launch {
                        // v0.273.0（T33）：清空按删除结果给提示——原来删没删掉都说已恢复。
                        val ok = BackgroundStore.clearCustomBg(context)
                        AppToast.show(context, if (ok) "已恢复默认背景" else "恢复失败，请重试", long = false)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("恢复默认", modifier = Modifier.fillMaxWidth()) }
        }
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
        modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 8.dp),
    )
}

/**
 * v0.188.0：旧字幕 key 条数统计（只读不删）。
 * 判定口径与 PlayerSettings.clearLegacyPerShowKeys 一致：前缀 + 纯数字 hash、
 * 不含竖线分隔符；新 key 一律含竖线，不会被计入。前缀常量在 PlayerSettings 里是
 * 私有的，这里逐字照抄三处前缀，改动前缀时两处须同步。
 */
private fun countLegacyPerShowKeys(context: Context): Int {
    val prefs = context.getSharedPreferences("movieshell_prefs", Context.MODE_PRIVATE)
    val prefixes = listOf("skip_intro_vod_", "skip_outro_vod_", "subtitle_vod_")
    return prefs.all.keys.count { key ->
        val prefix = prefixes.firstOrNull { key.startsWith(it) } ?: return@count false
        val rest = key.removePrefix(prefix)
        rest.isNotEmpty() && rest.toIntOrNull() != null && !rest.contains("|")
    }
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    TechDialog(
        onDismissRequest = onDismiss,
        title = title,
    ) {
            Column {
                options.forEach { option ->
                    ListItem(
                        headlineContent = { Text(option, style = MaterialTheme.typography.bodyMedium) },
                        trailingContent = {
                            RadioButton(
                                selected = option == selected,
                                onClick = { onSelect(option) },
                            )
                        },
                        modifier = Modifier.clickable { onSelect(option) },
                    )
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
private enum class SegPos { First, Middle, Last, Single }

/**
 * v0.292.0 V5：Android 16 式分段列表项——surfaceContainerLowest 底，组首尾外角 24dp、内角 6dp，
 * 前置 40dp primaryContainer 圆形图标，bodyLarge 标题 + bodyMedium 副标题，尾部开关/箭头/自定义；
 * [content] 放在标题下方（主题模式分段按钮、主题色色圈）。
 */
@Composable
private fun SegItem(
    position: SegPos,
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
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
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                onClick != null && content == null -> {
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

/** 主题色色圈（48dp；选中 = 2dp primary 外圈 + 3dp 间隙）。 */
@Composable
private fun ThemeSwatch(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    art: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.border(2.dp, cs.primary, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected; contentDescription = if (selected) "已选：$label" else label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (selected) 44.dp else 48.dp)
                .clip(CircleShape),
        ) { art() }
    }
}

/** 预设色圈：左半 primary，右上 primaryContainer，右下 tertiaryContainer（M3 色板预览）。 */
@Composable
private fun SeedSwatchArt(scheme: androidx.compose.material3.ColorScheme) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxSize().background(scheme.primary))
        Column(Modifier.weight(1f).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(scheme.primaryContainer))
            Box(Modifier.weight(1f).fillMaxWidth().background(scheme.tertiaryContainer))
        }
    }
}

/** 海报取色色圈：当前色板四象限 + 中心取色图标。 */
@Composable
private fun PosterSwatchArt() {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                Box(Modifier.weight(1f).fillMaxSize().background(cs.primaryContainer))
                Box(Modifier.weight(1f).fillMaxSize().background(cs.primary))
            }
            Row(Modifier.weight(1f)) {
                Box(Modifier.weight(1f).fillMaxSize().background(cs.tertiaryContainer))
                Box(Modifier.weight(1f).fillMaxSize().background(cs.secondary))
            }
        }
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(cs.surfaceContainerLowest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = cs.primary, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * v0.101.0：设置页缓存清理。
 * - 视频缓存：Media3 SimpleCache，目录为 cacheDir/video_cache（见 PlayerManager.videoCache）。
 * - 图片缓存：Coil 2 默认磁盘缓存目录 cacheDir/image_cache。
 */
private fun cacheDirs(context: Context): List<File> = listOf(
    File(context.cacheDir, "video_cache"),
    File(context.cacheDir, "image_cache"),
)

private fun dirBytes(dir: File): Long =
    if (dir.isDirectory) {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } else {
        0L
    }

private suspend fun refreshCacheSize(context: Context): String =
    withContext(Dispatchers.IO) {
        PluginMarket.formatSize(cacheDirs(context).sumOf { dirBytes(it) }).ifBlank { "0 B" }
    }
