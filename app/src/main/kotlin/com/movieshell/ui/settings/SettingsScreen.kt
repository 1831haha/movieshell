package com.movieshell.ui.settings

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
import com.movieshell.ui.components.SectionTitle
import com.movieshell.ui.theme.LocalBrandGradient
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
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

// v0.27.0 Soft Tech 换皮：分组卡片走 AppCard，分组标题走 SectionTitle，
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
    // v0.23.0：插件市场源（多源）+ 显示不兼容插件开关
    // v0.134.0：市场增删改收敛到插件中心「市场 → 管理」，本页只留市场数展示
    val marketSources by PluginStore.marketSources.collectAsState()
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
                val json = when (val r = BackupManager.exportJson(context)) {
                    is BackupManager.ExportResult.Ok -> r.json
                    is BackupManager.ExportResult.Failed -> {
                        LogBus.e("Backup", "备份导出失败：${r.reason}")
                        AppToast.show(context, "导出失败：${r.reason}", long = true)
                        return@launch
                    }
                }
                // v0.170.0：原子写盘，写盘失败如实报错，不谎报已导出
                if (!AtomicFile.writeText(file, json)) {
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
                val text = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                } ?: return@launch
                when (val r = BackupManager.importJson(context, text)) {
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
    var histCount by remember { mutableIntStateOf(0) }
    var subCount by remember { mutableIntStateOf(0) }
    var localCount by remember { mutableIntStateOf(0) }
    // v0.148.0：统计加载完成前不显示 0（0 和“真没有”分不出来）
    var statsLoaded by remember { mutableStateOf(false) }
    // v0.171.0：任一统计加载失败时整行标失败，不再用 0 冒充"真没有"
    var statsFailed by remember { mutableStateOf(false) }
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
        followCount = safeCount("追剧") { FollowStore.readSync(context).size }
        histCount = safeCount("历史") { withContext(Dispatchers.IO) { HistoryStore.getHistory(context).size } }
        subCount = safeCount("订阅") { withContext(Dispatchers.IO) { ApiSourceStore.load(context).size } }
        localCount = safeCount("本地") {
            withContext(Dispatchers.IO) {
                DrpySourceManager.listRules().size + PySourceManager.listRules().size +
                    PhpSourceManager.listRules().size
            }
        }
        // v0.217.0（F63）：当前源只展示搜索页同一口径——原来设置页自己
        // 另写一套（只看缓存记录名，禁用／不支持的源也照样显示，缺 PHP
        // 分支），与搜索页“未设置当前源”打架；现在复用搜索页的算法，
        // 只有搜索页认的当前源才显示，否则显示“未设置”。
        // v0.247.0（F41）第 3 轮：刷新失败保留上一次有效值——首屏初值
        // 就是“未设置”，首屏失败显示不变；回页刷新失败不再把已显示的
        // 有效源名覆成“未设置”。
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
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SoftDimens.PagePadding),
    ) {
        // 1. 顶部信息卡：头像 + 侠影 + 版本徽章 + 当前源 + 统计
        // v0.238.0：顶部贴顶——首项上方的 12dp 间隔去掉，信息卡贴顶
        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Brush.horizontalGradient(LocalBrandGradient.current)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "侠",
                                // v0.109.0 A9：侠字压在品牌渐变圆上——炫彩预设含荧光高明度色段，
                                // 白字在其上对比度约 1.9:1；按渐变平均明度取对比度更高的字色
                                //（>0.2 取深色 0xFF1A1408，否则白字），品牌渐变可自定义故自适应
                                color = if (LocalBrandGradient.current.map { it.luminance() }
                                        .average() > 0.2f
                                ) Color(0xFF1A1408) else Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "侠影",
                                    style = SoftType.Title,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = com.movieshell.BuildConfig.VERSION_NAME,
                                        style = SoftType.Caption,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF00C853)),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = currentSourceName,
                                    style = SoftType.Caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(LocalSoftExtra.current.border),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        ProfileStat(
                            label = "追剧",
                            value = if (!statsLoaded) "…" else if (statsFailed) "—" else "$followCount",
                            modifier = Modifier.weight(1f),
                            onClick = onFollowClick,
                        )
                        ProfileStat(
                            label = "历史",
                            value = if (!statsLoaded) "…" else if (statsFailed) "—" else "$histCount",
                            modifier = Modifier.weight(1f),
                            onClick = onHistoryClick,
                        )
                        // v0.188.0：资料卡“源”只做统计展示，不设点击；
                        // 站源管理唯一入口留给下方快捷宫格，不发明重复入口。
                        ProfileStat(
                            label = "源",
                            value = if (!statsLoaded) "…" else if (statsFailed) "—" else "${subCount + localCount}",
                            sub = if (!statsLoaded) "统计中…" else if (statsFailed) "统计失败" else "订阅$subCount 本地$localCount",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        // 2. 快捷宫格：最常用的 6 个入口（两行）
        item {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // v0.168.0：我的追剧与资料卡追剧统计重复，去掉；补嗅探正则入口
                QuickEntry(
                    label = "嗅探正则",
                    icon = Icons.Filled.Tune,
                    onClick = onSniffPatternsClick,
                    modifier = Modifier.weight(1f),
                )
                QuickEntry(
                    label = "解析接口",
                    icon = Icons.Filled.Link,
                    onClick = onParseSettingsClick,
                    modifier = Modifier.weight(1f),
                )
                QuickEntry(
                    label = "离线下载",
                    icon = Icons.Filled.Download,
                    onClick = onDownloadsClick,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickEntry(
                    label = "插件中心",
                    icon = Icons.Filled.Store,
                    onClick = onPluginCenterClick,
                    modifier = Modifier.weight(1f),
                )
                QuickEntry(
                    label = "站源管理",
                    icon = Icons.Filled.Storage,
                    onClick = onSourcesClick,
                    modifier = Modifier.weight(1f),
                )
                QuickEntry(
                    label = "使用手册",
                    icon = Icons.Filled.MenuBook,
                    onClick = onManualClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // 3. 外观
        item {
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "外观") {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 亮色 / 暗色 / 跟随系统
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(4.dp),
                    ) {
                        listOf(
                            "亮色" to ThemeStore.MODE_LIGHT,
                            "暗色" to ThemeStore.MODE_DARK,
                            "跟随系统" to ThemeStore.MODE_SYSTEM,
                        ).forEach { (label, mode) ->
                            val sel = themeMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (sel) MaterialTheme.colorScheme.primary
                                        else Color.Transparent,
                                    )
                                    .clickable { ThemeStore.setMode(context, mode) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    style = SoftType.Body,
                                    color = if (sel) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(text = "主题色", style = SoftType.Body)
                    Spacer(Modifier.height(8.dp))
                    val themeKey by ThemeStore.themeKey.collectAsState()
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(ThemeStore.COLOR_OPTIONS) { argb ->
                            val isSelected = themeKey == "c:$argb"
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .border(
                                        width = if (isSelected) 3.dp else SoftDimens.BorderWidth,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else LocalSoftExtra.current.border,
                                        shape = CircleShape,
                                    )
                                    .clickable { ThemeStore.setColor(context, argb) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "已选",
                                        // v0.105.0：圆圈是用户自选主题色（含浅色），按明度取深/白勾
                                        tint = if (Color(argb).luminance() > 0.5f) Color(0xFF1A1408) else Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        // v0.87.1：炫彩渐变主题（非单色）：渐变面跟随换色，主色取锚点
                        items(ThemeStore.GRADIENT_OPTIONS) { preset ->
                            val key = "g:${preset.id}"
                            val isSelected = key == themeKey
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Brush.horizontalGradient(preset.colors.map { Color(it) }))
                                    .border(
                                        width = if (isSelected) 3.dp else SoftDimens.BorderWidth,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else LocalSoftExtra.current.border,
                                        shape = CircleShape,
                                    )
                                    .clickable { ThemeStore.setGradient(context, preset.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "已选",
                                        // v0.105.0：渐变预设可能偏浅，按色带平均明度取深/白勾
                                        tint = if (preset.colors.map { Color(it).luminance() }.average() > 0.5f) Color(0xFF1A1408) else Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                // v0.82.0：背景底图
                val customBg by BackgroundStore.customBg.collectAsState()
                SettingsRow(
                    icon = Icons.Filled.Image,
                    title = "背景底图",
                    subtitle = if (customBg != null) "已自定义（首页生效）" else "默认",
                    onClick = {
                        if (customBg != null) showBgMenu = true
                        else bgPicker.launch("image/*")
                    },
                )
            }
        }
        // 4. 播放
        item {
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "播放") {
                // v0.20.0：播放器内核——Media3（默认）/ libmpv，播放器内也可一键切换
                // v0.29.0：libmpv 已插件化，未安装时标注
                SettingsRow(
                    icon = Icons.Filled.Memory,
                    title = "播放器内核",
                    subtitle = when {
                        engine == PlayerEngine.MEDIA3 -> "Media3（默认）"
                        MpvPluginLoader.isInstalled(context) -> "libmpv"
                        else -> "libmpv（插件未安装）"
                    },
                    onClick = { showEngineDialog = true },
                )
                // v0.27.7：重力感应自动横屏（默认开）
                run {
                    val gravityRotate by PlayerSettings.gravityRotate.collectAsState()
                    SettingsRow(
                        icon = Icons.Filled.ScreenRotation,
                        title = "重力感应自动横屏",
                        subtitle = "播放时随手机方向自动横竖屏",
                        onClick = { PlayerSettings.setGravityRotate(context, !gravityRotate) },
                        trailing = {
                            AppSwitch(
                                checked = gravityRotate,
                                onCheckedChange = {
                                    PlayerSettings.setGravityRotate(context, it)
                                },
                            )
                        },
                    )
                }
            }
        }
        // 5. 解析
        item {
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "解析") {
                // v0.114.0：JS 引擎（V8）状态——WebView 过旧时 JS 源会被禁用并给出原因，
                // 点按执行一次真实连接检测（首次使用 JS 源前也能提前知道结果）
                run {
                    var jsTick by remember { mutableStateOf(0) }
                    val jsSubtitle = remember(jsTick) {
                        when {
                            JsRuntime.jsSourcesSupported -> "V8 引擎可用，JS 源正常"
                            JsRuntime.lastDisableReason != null ->
                                "JS 源已禁用：" + JsRuntime.lastDisableReason
                            else -> "未检测，点按检测"
                        }
                    }
                    SettingsRow(
                        icon = Icons.Filled.Memory,
                        title = "JS 引擎状态",
                        subtitle = jsSubtitle,
                        onClick = {
                            scope.launch {
                                runCatching { JsRuntime.sandbox() }
                                jsTick++
                            }
                        },
                    )
                }
            }
        }
        // 6. 数据
        item {
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "数据") {
                // v0.46.0：订阅自动刷新开关
                SettingsRow(
                    icon = Icons.Filled.CloudSync,
                    title = "订阅自动刷新",
                    subtitle = "每天自动刷新一次订阅，失效的在订阅管理里标出来",
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
                SettingsRow(
                    icon = Icons.Filled.PlaylistAddCheck,
                    title = "追剧更新提醒",
                    subtitle = "每天检测 2 次追的剧，有新集数发通知",
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
                // v0.51.0：WebDAV 云同步；v0.186.0：范围是订阅、追剧、书架、音乐、直播，不含插件与设置
                SettingsRow(
                    icon = Icons.Filled.CloudSync,
                    title = "WebDAV 云同步",
                    subtitle = if (CloudSyncManager.isConfigured(context)) {
                        CloudSyncManager.lastSyncText(context)
                    } else {
                        "未配置（支持坚果云/Alist/群晖等）"
                    },
                    onClick = {
                        lastSyncText = CloudSyncManager.lastSyncText(context)
                        showCloudDialog = true
                    },
                )
                SettingsRow(
                    icon = Icons.Filled.Backup,
                    title = "备份与恢复",
                    subtitle = "导出 / 导入 JSON 备份文件：订阅、追剧、书架、音乐、直播（不含插件、下载、播放历史与设置）",
                    onClick = { backupExpanded = !backupExpanded },
                    trailing = {
                        Icon(
                            imageVector = if (backupExpanded) Icons.Filled.ExpandLess
                            else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                AnimatedVisibility(visible = backupExpanded) {
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        SettingsRow(
                            icon = Icons.Filled.Backup,
                            title = "备份到文件",
                            subtitle = "导出到 /storage/emulated/0/xiaying/backup/",
                            onClick = { exportBackup() },
                        )
                        SettingsRow(
                            icon = Icons.Filled.Restore,
                            title = "从文件恢复",
                            subtitle = "从 JSON 备份文件恢复订阅、当前规则、追剧、书架、音乐、直播",
                            onClick = { importLauncher.launch("application/json") },
                        )
                        // v0.216.0（C15）：上次恢复中断/失败后留下的恢复前快照
                        if (hasPreSnapshot) {
                            SettingsRow(
                                icon = Icons.Filled.History,
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
                SettingsRow(
                    icon = Icons.Filled.Speed,
                    title = "同时下载数",
                    subtitle = "$maxParallel 个任务并行（1-3）",
                    onClick = {
                        val next = if (maxParallel >= 3) 1 else maxParallel + 1
                        DownloadStore.setMaxParallel(context, next)
                        maxParallel = next
                        DownloadManager.pump(context)
                    },
                )
                SettingsRow(
                    icon = Icons.Filled.Cached,
                    title = "清理缓存",
                    subtitle = "共占用 $cacheSize",
                    onClick = { showClearCacheDialog = true },
                )
                // v0.188.0：旧字幕 key 清理入口——点开先说明条数，确认后才删；
                // 只删旧格式 key，新格式记忆与当前字幕设置不受影响。
                SettingsRow(
                    icon = Icons.Filled.Subtitles,
                    title = "清理旧字幕记忆",
                    subtitle = "只删旧格式按剧记忆，需手动确认",
                    onClick = {
                        legacySubtitleCount = countLegacyPerShowKeys(context)
                        showClearLegacySubtitleDialog = true
                    },
                )
            }
        }
        // 7. 插件
        item {
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "插件") {
                SettingsRow(
                    icon = Icons.Filled.Store,
                    title = "插件市场源",
                    subtitle = "已添加 ${marketSources.size} 个市场，在插件中心 → 市场 → 管理中维护",
                    onClick = onPluginCenterClick,
                )
                SettingsRow(
                    icon = Icons.Filled.Visibility,
                    title = "显示不兼容插件",
                    subtitle = "显示 apk/import 等侠影不支持的插件（仅查看，不可安装）",
                    onClick = {
                        PluginStore.setShowIncompatible(context, !showIncompatible)
                    },
                    trailing = {
                        AppSwitch(
                            checked = showIncompatible,
                            onCheckedChange = {
                                PluginStore.setShowIncompatible(context, it)
                            },
                        )
                    },
                )
            }
        }
        // 8. 帮助
        item {
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "帮助") {
                run {
                    val logEnabled by LogStore.enabled.collectAsState()
                    SettingsRow(
                        icon = Icons.Filled.BugReport,
                        title = "运行日志悬浮窗",
                        subtitle = if (logEnabled) "已开启：小泡可拖动，点开看日志" else "关闭",
                        onClick = { LogStore.setEnabled(context, !logEnabled) },
                        trailing = {
                            AppSwitch(
                                checked = logEnabled,
                                onCheckedChange = { LogStore.setEnabled(context, it) },
                            )
                        },
                    )
                }
                // v0.25.0：崩溃日志导出（xiaying/crash/ 打包 zip 经系统分享）
                run {
                    val crashCount = remember { CrashHandler.list(context).size }
                    SettingsRow(
                        icon = Icons.Filled.Warning,
                        title = "导出崩溃日志",
                        subtitle = if (crashCount > 0) "共 $crashCount 份，打包分享" else "暂无崩溃记录",
                        onClick = { exportCrashLog() },
                    )
                }
                // v0.188.0：关于行去掉点击态，不再是点了没反应的占位点击；
                // 版本信息仍在副标题展示，更新日志入口在下一行。
                SettingsRow(
                    icon = Icons.Filled.Info,
                    title = "关于侠影",
                    subtitle = "版本 ${com.movieshell.BuildConfig.VERSION_NAME} · 本地影视聚合",
                    onClick = {},
                    clickable = false,
                )
                // v0.30.0：更新日志独立入口
                SettingsRow(
                    icon = Icons.Filled.History,
                    title = "更新日志",
                    subtitle = "每一版的更新内容",
                    onClick = onChangelogClick,
                )
            }
            Spacer(Modifier.height(16.dp))
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
                style = SoftType.Body,
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
                style = SoftType.Body,
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
                    Text(lastSyncText, style = SoftType.Caption,
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
                            style = SoftType.Caption,
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
            Text(cloudConflictText, style = SoftType.Caption)
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
                        BackgroundStore.clearCustomBg(context)
                        AppToast.show(context, "已恢复默认背景", long = false)
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
    Column {
        SectionTitle(
            title = title,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
        AppCard(modifier = Modifier.fillMaxWidth()) {
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
) {
    ListItem(
        headlineContent = { Text(title, style = SoftType.Body) },
        supportingContent = {
            Text(
                subtitle,
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = if (!clickable) null else trailing ?: {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = if (clickable) Modifier.clickable(onClick = onClick) else Modifier,
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
                        headlineContent = { Text(option, style = SoftType.Body) },
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
 */
@Composable
private fun ProfileStat(
    label: String,
    value: String,
    sub: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = SoftType.Title,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = SoftType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sub != null) {
            Text(
                text = sub,
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}

/**
 * v0.80.0：快捷宫格入口（对标 DsPlayer 我的页宫格）。
 */
@Composable
private fun QuickEntry(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(LocalSoftExtra.current.glass)
            .border(SoftDimens.BorderWidth, LocalSoftExtra.current.border, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = SoftType.Caption,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
