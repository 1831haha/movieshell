package com.movieshell.ui.settings

import com.movieshell.ui.theme.V4
import com.movieshell.ui.theme.V4Type
import com.movieshell.ui.components.V4Tile
import com.movieshell.ui.components.V4Row
import com.movieshell.ui.components.V4Dot
import com.movieshell.ui.components.V4RingGauge
import com.movieshell.ui.components.V4Segmented
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
import com.movieshell.ui.components.V4SectionTitle
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

// v0.27.0 Soft Tech 换皮：分组卡片走 AppCard，分组标题走 V4SectionTitle，
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
    // v0.279.0 V4：追剧环形仪表 = 有更新的追剧占比（真实数据，来自 FollowStore.hasNew）
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

    // v0.80.0：设置页按 DsPlayer「我的」重设计——信息卡 + 快捷宫格 + 主题卡 + 功能分组
    // v0.279.0 V4 · SETTINGS B：bento 仪表盘——资料磁贴 + 三个环形仪表（追剧/历史/源，真实统计）
    // + 3×2 工具磁贴 + 分组 key–value 小卡（开关/当前值）。原有每一项设置、入口、动作都保留，
    // 位置对照见提交说明里的清单；弹窗与逻辑一行未动。
    val v = V4.c
    val customBg by BackgroundStore.customBg.collectAsState()
    val gravityRotate by PlayerSettings.gravityRotate.collectAsState()
    val logEnabled by LogStore.enabled.collectAsState()
    val themeKey by ThemeStore.themeKey.collectAsState()
    var jsTick by remember { mutableStateOf(0) }
    val crashCount = remember { CrashHandler.list(context).size }
    val statText: (Int) -> String = { n -> if (!statsLoaded) "…" else if (statsFailed) "—" else "$n" }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = V4.Gutter,
            end = V4.Gutter,
            bottom = dockClearance() + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 页头：设置 + 版本号（等宽 t3）；下面一行纯文字显示当前源名——
        // 不是格子、不可点、不带箭头，所以不算入口，也不复活已删的资料磁贴；
        // 资料卡的“源”入口（仪表“源”，点进站源管理）原样保留，仍是唯一入口。
        item {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("设置", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = v.t1)
                    Spacer(Modifier.weight(1f))
                    Text("v${com.movieshell.BuildConfig.VERSION_NAME}", style = V4Type.MonoSmall, color = v.t3)
                }
                Text(
                    text = "当前源：$currentSourceName",
                    fontSize = 11.sp,
                    color = v.t2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 289 去重后用户纠偏：不要占位格——资料磁贴点不了就整块删掉，
        // 不留一个看着像入口、点着没反应的大格子；站源管理只剩仪表“源”一个入口，
        // 源名改在页头用一行纯文字显示（见页头那块）。
        // 2. 三个环形仪表：追剧（有更新占比）/ 历史（占 50 条上限）/ 源（本地占比）
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatGaugeTile(
                    label = "追剧",
                    value = statText(followCount),
                    sub = if (statsLoaded && !statsFailed && followCount > 0) "$followNewCount 部更新" else null,
                    progress = if (followCount > 0) followNewCount.toFloat() / followCount else 0f,
                    onClick = onFollowClick,
                    modifier = Modifier.weight(1f),
                )
                StatGaugeTile(
                    label = "历史",
                    value = statText(histCount),
                    sub = if (statsLoaded && !statsFailed) "上限 50" else null,
                    progress = histCount / 50f,
                    onClick = onHistoryClick,
                    modifier = Modifier.weight(1f),
                )
                // 2026-10-28 用户要求恢复：资料卡“源”可点，进站源管理
                StatGaugeTile(
                    label = "源",
                    value = statText(subCount + localCount),
                    sub = if (!statsLoaded) "统计中…" else if (statsFailed) "统计失败" else "订阅$subCount",
                    sub2 = if (statsLoaded && !statsFailed) "本地$localCount" else null,
                    progress = if (subCount + localCount > 0) localCount.toFloat() / (subCount + localCount) else 0f,
                    onClick = onSourcesClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // 3. 工具磁贴 3×2（原快捷宫格 6 个入口）
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // v0.168.0：我的追剧与资料卡追剧统计重复，去掉；补嗅探正则入口
                    QuickEntry("嗅探正则", Icons.Outlined.Tune, onSniffPatternsClick, Modifier.weight(1f))
                    QuickEntry("解析接口", Icons.Outlined.Link, onParseSettingsClick, Modifier.weight(1f))
                    QuickEntry("离线下载", Icons.Outlined.Download, onDownloadsClick, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickEntry("插件中心", Icons.Outlined.Inventory2, onPluginCenterClick, Modifier.weight(1f))
                    // 289 去重：工具磁贴“站源管理”删掉，站源管理只留仪表“源”；
                    // 第二行剩插件中心、使用手册两个，照样铺满一行。
                    QuickEntry("使用手册", Icons.Outlined.MenuBook, onManualClick, Modifier.weight(1f))
                }
            }
        }
        // 4. 外观
        item { SettingsCaption("外观") }
        item {
            // 亮色 / 暗色 / 跟随系统（v0.80.0 内联三段）
            V4Tile(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("主题模式", fontSize = 12.sp, color = v.t2)
                    Spacer(Modifier.width(12.dp))
                    val modes = listOf(
                        "亮色" to ThemeStore.MODE_LIGHT,
                        "暗色" to ThemeStore.MODE_DARK,
                        "跟随系统" to ThemeStore.MODE_SYSTEM,
                    )
                    V4Segmented(
                        options = modes.map { it.first },
                        selected = modes.indexOfFirst { it.second == themeMode },
                        onSelect = { ThemeStore.setMode(context, modes[it].second) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // 主题色：单色 + 炫彩渐变预设（v0.87.1），横滑
                V4Tile(modifier = Modifier.weight(1f).height(72.dp)) {
                    Text("主题色", fontSize = 10.sp, color = v.t3)
                    Spacer(Modifier.weight(1f))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(ThemeStore.COLOR_OPTIONS) { argb ->
                            ThemeSwatch(
                                brush = androidx.compose.ui.graphics.SolidColor(Color(argb)),
                                selected = themeKey == "c:$argb",
                                onClick = { ThemeStore.setColor(context, argb) },
                            )
                        }
                        items(ThemeStore.GRADIENT_OPTIONS) { preset ->
                            ThemeSwatch(
                                brush = Brush.horizontalGradient(preset.colors.map { Color(it) }),
                                selected = themeKey == "g:${preset.id}",
                                onClick = { ThemeStore.setGradient(context, preset.id) },
                            )
                        }
                    }
                }
                // v0.82.0：背景底图
                KvTile(
                    caption = "背景底图",
                    value = if (customBg != null) "已自定义" else "默认",
                    sub = if (customBg != null) "首页生效" else null,
                    onClick = {
                        if (customBg != null) showBgMenu = true
                        else bgPicker.launch("image/*")
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // 5. 播放 · 解析
        item { SettingsCaption("播放 · 解析") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // v0.20.0：播放器内核——Media3（默认）/ libmpv；v0.29.0：libmpv 插件化，未安装时标注
                KvTile(
                    caption = "播放器内核",
                    value = if (engine == PlayerEngine.MEDIA3) "Media3" else "libmpv",
                    mono = true,
                    sub = when {
                        engine == PlayerEngine.MEDIA3 -> "默认内核"
                        MpvPluginLoader.isInstalled(context) -> "插件内核"
                        else -> "插件未安装"
                    },
                    showChevron = false,
                    onClick = { showEngineDialog = true },
                    modifier = Modifier.weight(1f),
                )
                // v0.27.7：重力感应自动横屏（默认开）
                KvTile(
                    caption = "重力感应横屏",
                    value = if (gravityRotate) "自动" else "关闭",
                    sub = "随手机方向",
                    onClick = { PlayerSettings.setGravityRotate(context, !gravityRotate) },
                    trailing = {
                        AppSwitch(
                            checked = gravityRotate,
                            onCheckedChange = { PlayerSettings.setGravityRotate(context, it) },
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                // v0.114.0：JS 引擎（V8）状态——点按执行一次真实连接检测
                val jsState = remember(jsTick) {
                    when {
                        JsRuntime.jsSourcesSupported -> 1
                        JsRuntime.lastDisableReason != null -> 2
                        else -> 0
                    }
                }
                KvTile(
                    caption = "JS 引擎",
                    value = when (jsState) { 1 -> "V8 可用"; 2 -> "已禁用"; else -> "未检测" },
                    dot = when (jsState) { 1 -> v.ok; 2 -> v.live; else -> v.t3 },
                    sub = when (jsState) {
                        1 -> "JS 源正常"
                        2 -> JsRuntime.lastDisableReason ?: "JS 源已禁用"
                        else -> "点按检测"
                    },
                    showChevron = false,
                    onClick = {
                        scope.launch {
                            runCatching { JsRuntime.sandbox() }
                            jsTick++
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // 6. 数据
        item { SettingsCaption("数据") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // v0.46.0：订阅自动刷新开关
                KvTile(
                    caption = "订阅自动刷新",
                    value = if (subRefreshEnabled) "每天 1 次" else "关闭",
                    sub = "失效的在订阅管理标出",
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
                    modifier = Modifier.weight(1f),
                )
                KvTile(
                    caption = "追剧更新提醒",
                    value = if (followCheckEnabled) "每天 2 次" else "关闭",
                    sub = "有新集数发通知",
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
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // v0.51.0：WebDAV 云同步；v0.186.0：范围是订阅、追剧、书架、音乐、直播，不含插件与设置
                val cloudConfigured = CloudSyncManager.isConfigured(context)
                KvTile(
                    caption = "WebDAV 云同步",
                    value = if (cloudConfigured) "已配置" else "未配置",
                    sub = if (cloudConfigured) CloudSyncManager.lastSyncText(context) else "坚果云/Alist/群晖",
                    onClick = {
                        lastSyncText = CloudSyncManager.lastSyncText(context)
                        showCloudDialog = true
                    },
                    modifier = Modifier.weight(1f),
                )
                KvTile(
                    caption = "备份与恢复",
                    value = "导出 / 导入",
                    sub = "订阅·追剧·书架·音乐·直播",
                    onClick = { backupExpanded = !backupExpanded },
                    trailing = {
                        Icon(
                            imageVector = if (backupExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (backupExpanded) "收起" else "展开",
                            tint = v.t3,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            AnimatedVisibility(visible = backupExpanded) {
                V4Tile(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
                    Text(
                        "导出 / 导入 JSON 备份文件：订阅、追剧、书架、音乐、直播（不含插件、下载、播放历史与设置）",
                        fontSize = 10.5.sp,
                        color = v.t3,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Backup,
                        title = "备份到文件",
                        subtitle = "导出到 /storage/emulated/0/xiaying/backup/",
                        onClick = { exportBackup() },
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Restore,
                        title = "从文件恢复",
                        subtitle = "从 JSON 备份文件恢复订阅、当前规则、追剧、书架、音乐、直播",
                        onClick = { importLauncher.launch("application/json") },
                    )
                    // v0.216.0（C15）：上次恢复中断/失败后留下的恢复前快照
                    if (hasPreSnapshot) {
                        SettingsRow(
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KvTile(
                    caption = "同时下载数",
                    value = "$maxParallel 个并行",
                    sub = "点按切换（1-3）",
                    showChevron = false,
                    onClick = {
                        val next = if (maxParallel >= 3) 1 else maxParallel + 1
                        DownloadStore.setMaxParallel(context, next)
                        maxParallel = next
                        DownloadManager.pump(context)
                    },
                    modifier = Modifier.weight(1f),
                )
                KvTile(
                    caption = "清理缓存",
                    value = cacheSize,
                    mono = true,
                    sub = "视频分片 + 图片缓存",
                    onClick = { showClearCacheDialog = true },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            // v0.188.0：旧字幕 key 清理入口——点开先说明条数，确认后才删
            V4Tile(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
                SettingsRow(
                    icon = Icons.Outlined.Subtitles,
                    title = "清理旧字幕记忆",
                    subtitle = "只删旧格式按剧记忆，需手动确认",
                    onClick = {
                        legacySubtitleCount = countLegacyPerShowKeys(context)
                        showClearLegacySubtitleDialog = true
                    },
                    divider = false,
                )
            }
        }
        // 7. 插件
        item { SettingsCaption("插件") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // 289 去重后用户纠偏：不要占位格——“插件市场源”点不了就整格删掉，
                // 不留只展示市场数的空壳；插件中心只剩工具磁贴一个入口。
                KvTile(
                    caption = "显示不兼容插件",
                    value = if (showIncompatible) "显示" else "隐藏",
                    sub = "apk/import 仅查看",
                    onClick = { PluginStore.setShowIncompatible(context, !showIncompatible) },
                    trailing = {
                        AppSwitch(
                            checked = showIncompatible,
                            onCheckedChange = { PluginStore.setShowIncompatible(context, it) },
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // 8. 帮助
        item { SettingsCaption("帮助") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KvTile(
                    caption = "运行日志悬浮窗",
                    value = if (logEnabled) "已开启" else "关闭",
                    sub = if (logEnabled) "小泡可拖动，点开看日志" else "排查问题时打开",
                    onClick = { LogStore.setEnabled(context, !logEnabled) },
                    trailing = {
                        AppSwitch(
                            checked = logEnabled,
                            onCheckedChange = { LogStore.setEnabled(context, it) },
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                // v0.25.0：崩溃日志导出（xiaying/crash/ 打包 zip 经系统分享）
                KvTile(
                    caption = "导出崩溃日志",
                    value = if (crashCount > 0) "共 $crashCount 份" else "暂无",
                    sub = if (crashCount > 0) "打包分享" else "暂无崩溃记录",
                    onClick = { exportCrashLog() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // v0.188.0：关于不可点，版本信息在值里展示
                KvTile(
                    caption = "关于侠影",
                    value = com.movieshell.BuildConfig.VERSION_NAME,
                    mono = true,
                    sub = "本地影视聚合",
                    onClick = null,
                    modifier = Modifier.weight(1f),
                )
                // v0.30.0：更新日志独立入口
                KvTile(
                    caption = "更新日志",
                    value = "每版内容",
                    sub = "每一版的更新内容",
                    onClick = onChangelogClick,
                    modifier = Modifier.weight(1f),
                )
            }
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
                style = V4Type.Body,
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
                style = V4Type.Body,
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
                    Text(lastSyncText, style = V4Type.Meta,
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
                            style = V4Type.Meta,
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
            Text(cloudConflictText, style = V4Type.Meta)
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
    // v0.279.0 V4：分组 = 10.5sp t3 小标题 + s1 圆角 10 的卡片（行间 1px 分隔线）。
    Column {
        SettingsCaption(title)
        Spacer(Modifier.height(6.dp))
        V4Tile(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
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
    // v0.279.0 V4：44dp 行，图标 17 t2，标题 13/500，副标题 11 t3，右侧箭头 t3 或开关。
    V4Row(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = if (clickable) onClick else null,
        showChevron = clickable && trailing == null,
        divider = divider,
        trailing = if (clickable && trailing != null) ({ trailing() }) else null,
    )
}

/** v0.279.0 V4：设置分组小标题（10.5sp 500 t3，上 6dp）。 */
@Composable
private fun SettingsCaption(text: String) {
    Text(
        text = text,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Medium,
        color = V4.c.t3,
        modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 0.dp),
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
                        headlineContent = { Text(option, style = V4Type.Body) },
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
 * v0.279.0 V4：环形仪表磁贴——48dp 环 + 中间等宽数值，右侧标签 12/500 + 副标签 9.5 t3。
 */
@Composable
private fun StatGaugeTile(
    label: String,
    value: String,
    progress: Float,
    modifier: Modifier = Modifier,
    sub: String? = null,
    sub2: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val v = V4.c
    V4Tile(modifier = modifier.height(68.dp), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            V4RingGauge(value = value, progress = progress)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = v.t1, maxLines = 1)
                if (sub != null) {
                    Text(sub, fontSize = 9.5.sp, color = v.t3, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
                if (sub2 != null) {
                    Text(sub2, fontSize = 9.5.sp, color = v.t3, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * v0.80.0：快捷宫格入口（对标 DsPlayer 我的页宫格）。
 * v0.279.0 V4：工具磁贴——高 58，强调色图标在上、11.5/500 文字在下。
 */
@Composable
private fun QuickEntry(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    V4Tile(modifier = modifier.height(58.dp), onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = V4.c.accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = V4.c.t1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * v0.279.0 V4：key–value 小卡——上面 10sp t3 标题，下面值 13/600（技术值等宽 14），
 * 右侧开关或箭头，可选 10sp t3 副标题；可选状态点（如 V8 可用 绿点）。
 */
@Composable
private fun KvTile(
    caption: String,
    value: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    sub: String? = null,
    mono: Boolean = false,
    dot: Color? = null,
    showChevron: Boolean = onClick != null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val v = V4.c
    V4Tile(modifier = modifier.height(72.dp), onClick = onClick, padding = PaddingValues(start = 10.dp, end = 6.dp, top = 9.dp, bottom = 8.dp)) {
        Text(caption, fontSize = 10.sp, color = v.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                V4Dot(dot)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                value,
                fontSize = if (mono) 14.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (mono) V4.Mono else null,
                color = v.t1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                trailing != null -> Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) { trailing() }
                showChevron -> Icon(Icons.Filled.ChevronRight, null, tint = v.t3, modifier = Modifier.size(16.dp))
            }
        }
        if (sub != null) {
            Text(sub, fontSize = 10.sp, color = v.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** v0.279.0 V4：主题色色圈（15dp；选中 = 2dp s1 间隙 + 1.5dp t1 圈）。 */
@Composable
private fun ThemeSwatch(
    brush: Brush,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val v = V4.c
    Box(
        modifier = Modifier
            .size(21.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.border(1.5.dp, v.t1, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected; contentDescription = if (selected) "已选主题色" else "主题色" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(15.dp)
                .clip(CircleShape)
                .background(brush),
        )
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
