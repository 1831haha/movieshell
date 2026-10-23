package com.movieshell.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.core.content.FileProvider
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
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
    // v0.51.0：WebDAV 云同步
    val scope = rememberCoroutineScope()
    var showCloudDialog by remember { mutableStateOf(false) }
    var cloudWorking by remember { mutableStateOf(false) }
    var lastSyncText by remember { mutableStateOf("") }
    // v0.20.0：播放器内核（Media3 默认，libmpv 可选，记忆选择）
    val engine by PlayerSettings.engine.collectAsState()
    var showEngineDialog by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf("计算中…") }
    var showClearCacheDialog by remember { mutableStateOf(false) }
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
    fun exportBackup() {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(StoragePaths.backupDir(context), "movieshell_backup_$ts.json")
            val json = when (val r = BackupManager.exportJson(context)) {
                is BackupManager.ExportResult.Ok -> r.json
                is BackupManager.ExportResult.Failed -> {
                    LogBus.e("Backup", "备份导出失败：${r.reason}")
                    Toast.makeText(context, "导出失败：${r.reason}", Toast.LENGTH_LONG).show()
                    return
                }
            }
            // v0.170.0：原子写盘，写盘失败如实报错，不谎报已导出
            if (!AtomicFile.writeText(file, json)) {
                LogBus.e("Backup", "备份导出失败：写盘失败")
                Toast.makeText(context, "导出失败：写盘失败", Toast.LENGTH_LONG).show()
                return
            }
            LogBus.i("Backup", "备份已导出：${file.absolutePath}")
            Toast.makeText(context, "备份已导出：${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            LogBus.e("Backup", "备份导出失败：${e.message}")
            Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** v0.25.0：崩溃日志打包 zip，经系统分享（FileProvider + ACTION_SEND） */
    fun exportCrashLog() {
        try {
            val zip = CrashHandler.exportZip(context)
            if (zip == null) {
                Toast.makeText(context, "暂无崩溃日志", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    // 恢复仍需用户选文件，保留 picker
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: return@rememberLauncherForActivityResult
            when (val r = BackupManager.importJson(context, text)) {
                is BackupManager.RestoreResult.Ok ->
                    Toast.makeText(context, "恢复成功", Toast.LENGTH_SHORT).show()
                is BackupManager.RestoreResult.Failed ->
                    Toast.makeText(context, "恢复失败：${r.reason}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "恢复失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // v0.80.0：信息卡统计（追剧/历史/源），进设置页时拉一次
    var followCount by remember { mutableIntStateOf(0) }
    var histCount by remember { mutableIntStateOf(0) }
    var subCount by remember { mutableIntStateOf(0) }
    var localCount by remember { mutableIntStateOf(0) }
    // v0.148.0：统计加载完成前不显示 0（0 和“真没有”分不出来）
    var statsLoaded by remember { mutableStateOf(false) }
    var currentSourceName by remember { mutableStateOf("未设置") }
    // v0.82.0：背景底图
    var showBgMenu by remember { mutableStateOf(false) }
    val bgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = BackgroundStore.setCustomBg(context, uri)
            Toast.makeText(
                context,
                if (ok) "背景底图已更换" else "设置失败，换张图片试试",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    LaunchedEffect(Unit) {
        runCatching { BackgroundStore.load(context) }
        // v0.101.0：缓存占用（视频 m3u8 分片 + Coil 图片磁盘缓存）
        cacheSize = runCatching { refreshCacheSize(context) }.getOrDefault(cacheSize)
        // v0.147.0：统计失败不再静默回 0（之前 0 和“真没有”分不出来），记日志方便定位
        followCount = runCatching { FollowStore.readSync(context).size }
            .onFailure { LogBus.w("SettingsStats", "追剧统计失败：${it.message}") }
            .getOrDefault(0)
        histCount = runCatching { withContext(Dispatchers.IO) { HistoryStore.getHistory(context).size } }
            .onFailure { LogBus.w("SettingsStats", "历史统计失败：${it.message}") }
            .getOrDefault(0)
        subCount = runCatching { withContext(Dispatchers.IO) { ApiSourceStore.load(context).size } }
            .onFailure { LogBus.w("SettingsStats", "订阅统计失败：${it.message}") }
            .getOrDefault(0)
        localCount = runCatching {
            withContext(Dispatchers.IO) {
                DrpySourceManager.listRules().size + PySourceManager.listRules().size +
                    PhpSourceManager.listRules().size
            }
        }.onFailure { LogBus.w("SettingsStats", "本地统计失败：${it.message}") }
            .getOrDefault(0)
        currentSourceName = runCatching {
            val cur = SubSiteStore.getCurrent(context)
            if (cur != null) {
                SubSiteStore.getCached(context, cur.subUrl)
                    ?.sites?.firstOrNull { it.key == cur.siteKey }?.name ?: "订阅源"
            } else if (SourceStateStore.activeDrpyRule() != null) {
                DrpySourceManager.activeRuleName() ?: "drpy 规则"
            } else if (SourceStateStore.activePyRule() != null) {
                PySourceManager.activeRuleName() ?: "py 规则"
            } else {
                "未设置"
            }
        }.getOrDefault("未设置")
        statsLoaded = true
    }

    // v0.80.0：设置页按 DsPlayer「我的」重设计——信息卡 + 快捷宫格 + 主题卡 + 功能分组
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SoftDimens.PagePadding),
    ) {
        // 1. 顶部信息卡：头像 + 侠影 + 版本徽章 + 当前源 + 统计
        item {
            Spacer(Modifier.height(12.dp))
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
                            value = if (statsLoaded) "$followCount" else "…",
                            modifier = Modifier.weight(1f),
                            onClick = onFollowClick,
                        )
                        ProfileStat(
                            label = "历史",
                            value = if (statsLoaded) "$histCount" else "…",
                            modifier = Modifier.weight(1f),
                            onClick = onHistoryClick,
                        )
                        ProfileStat(
                            label = "源",
                            value = if (statsLoaded) "${subCount + localCount}" else "…",
                            sub = if (statsLoaded) "订阅$subCount 本地$localCount" else "统计中…",
                            modifier = Modifier.weight(1f),
                            onClick = onSourcesClick,
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
        // 3. 主题卡片：三段切换 + 12 种主题色横滑
        item {
            Spacer(Modifier.height(12.dp))
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "主题",
                            style = SoftType.CardTitle,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
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
                    Spacer(Modifier.height(12.dp))
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
        }
        // 4. 播放与解析
        item {
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "播放与解析") {
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
                            Switch(
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
        // 5. 插件与同步
        item {
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "插件与同步") {
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
                        Switch(
                            checked = showIncompatible,
                            onCheckedChange = {
                                PluginStore.setShowIncompatible(context, it)
                            },
                        )
                    },
                )
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
                        Switch(
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
                        Switch(
                            checked = followCheckEnabled,
                            onCheckedChange = {
                                FollowStore.setCheckEnabled(context, it)
                                followCheckEnabled = it
                                FollowCheckWorker.reschedule(context)
                            },
                        )
                    },
                )
                // v0.51.0：WebDAV 云同步（订阅/设置/播放记录一键备份恢复）
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
                    subtitle = "导出 / 导入 JSON 备份文件",
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
                            subtitle = "从 JSON 备份文件恢复订阅与规则",
                            onClick = { importLauncher.launch("application/json") },
                        )
                    }
                }
            }
        }
        // 6. 存储与帮助
        item {
            Spacer(Modifier.height(12.dp))
            SettingsGroup(title = "存储与帮助") {
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
                run {
                    val logEnabled by LogStore.enabled.collectAsState()
                    SettingsRow(
                        icon = Icons.Filled.BugReport,
                        title = "运行日志悬浮窗",
                        subtitle = if (logEnabled) "已开启：小泡可拖动，点开看日志" else "关闭",
                        onClick = { LogStore.setEnabled(context, !logEnabled) },
                        trailing = {
                            Switch(
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
                SettingsRow(
                    icon = Icons.Filled.Info,
                    title = "关于侠影",
                    subtitle = "版本 ${com.movieshell.BuildConfig.VERSION_NAME} · 本地影视聚合",
                    onClick = { /* 占位：可跳转关于页 */ },
                )
                // v0.30.0：更新日志独立入口
                SettingsRow(
                    icon = Icons.Filled.History,
                    title = "更新日志",
                    subtitle = "每一版的更新内容",
                    onClick = onChangelogClick,
                )
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
                    Toast.makeText(context, "去 设置-插件中心 安装 libmpv 播放器引擎", Toast.LENGTH_LONG).show()
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
                        Toast.makeText(context, "缓存已清理", Toast.LENGTH_SHORT).show()
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
    // v0.51.0：WebDAV 云同步配置 + 备份/恢复
    if (showCloudDialog) {
        val cfg = remember { CloudSyncManager.getConfig(context) }
        var urlInput by remember { mutableStateOf(cfg.url) }
        var userInput by remember { mutableStateOf(cfg.user) }
        var passInput by remember { mutableStateOf(cfg.pass) }
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
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        AppButton(
                            text = "测试连接",
                            variant = AppButtonVariant.Ghost,
                            enabled = !cloudWorking,
                            onClick = {
                                CloudSyncManager.saveConfig(context, urlInput, userInput, passInput)
                                cloudWorking = true
                                scope.launch {
                                    val err = CloudSyncManager.test(context)
                                    cloudWorking = false
                                    Toast.makeText(
                                        context,
                                        err ?: "连接成功",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        AppButton(
                            text = "备份到云端",
                            enabled = !cloudWorking,
                            onClick = {
                                CloudSyncManager.saveConfig(context, urlInput, userInput, passInput)
                                cloudWorking = true
                                scope.launch {
                                    val err = CloudSyncManager.backup(context)
                                    cloudWorking = false
                                    lastSyncText = CloudSyncManager.lastSyncText(context)
                                    Toast.makeText(
                                        context,
                                        err ?: "备份已上传",
                                        Toast.LENGTH_SHORT,
                                    ).show()
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
                            CloudSyncManager.saveConfig(context, urlInput, userInput, passInput)
                            cloudWorking = true
                            scope.launch {
                                val err = CloudSyncManager.restore(context)
                                cloudWorking = false
                                lastSyncText = CloudSyncManager.lastSyncText(context)
                                Toast.makeText(
                                    context,
                                    err ?: "已从云端恢复",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
                        Toast.makeText(context, "已恢复默认背景", Toast.LENGTH_SHORT).show()
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
        trailingContent = trailing ?: {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
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
    onClick: () -> Unit = {},
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
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

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(Locale.US, bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(Locale.US, bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KB".format(Locale.US, bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

private suspend fun refreshCacheSize(context: Context): String =
    withContext(Dispatchers.IO) {
        formatBytes(cacheDirs(context).sumOf { dirBytes(it) })
    }
