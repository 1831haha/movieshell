package com.movieshell.ui.settings

import android.content.ClipData
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.core.content.FileProvider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movieshell.data.download.DownloadManager
import com.movieshell.data.download.FollowCheckWorker
import com.movieshell.data.download.SubRefreshWorker
import com.movieshell.data.repo.BackupManager
import com.movieshell.data.repo.DownloadStore
import com.movieshell.data.repo.FollowStore
import com.movieshell.data.repo.SubRefreshStore
import com.movieshell.data.repo.CrashHandler
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.LogStore
import com.movieshell.data.repo.ParseStore
import com.movieshell.data.repo.StoragePaths
import com.movieshell.data.repo.PlayerEngine
import com.movieshell.player.MpvPluginLoader
import com.movieshell.ui.preview.fileProviderAuthority
import com.movieshell.data.repo.PlayerSettings
import com.movieshell.data.repo.PluginStore
import com.movieshell.data.repo.ThemeStore
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.SectionTitle
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// v0.27.0 Soft Tech 换皮：分组卡片走 AppCard，分组标题走 SectionTitle，
// 对话框 20dp 圆角 + 输入框走 AppTextField，按钮走 AppButton。逻辑与文案零变更。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onManualClick: () -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    onChangelogClick: () -> Unit = {},
    onSourcesClick: () -> Unit = {}, // v0.36.0：站源管理入口（tab 删除后搬进设置）
) {
    val context = LocalContext.current
    // v0.7.1：主题读持久化 ThemeStore，不再是本地假状态
    val themeMode by ThemeStore.mode.collectAsState()
    val themeLabel = when (themeMode) {
        ThemeStore.MODE_DARK -> "深色"
        ThemeStore.MODE_LIGHT -> "浅色"
        else -> "跟随系统"
    }
    var showThemeDialog by remember { mutableStateOf(false) }
    var backupExpanded by remember { mutableStateOf(false) }
    // v0.20.0：播放器内核（Media3 默认，libmpv 可选，记忆选择）
    val engine by PlayerSettings.engine.collectAsState()
    var showEngineDialog by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf("128 MB") }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showAddParseDialog by remember { mutableStateOf(false) } // v0.19.0：自定义解析接口
    // v0.23.0：插件市场源（多源）+ 显示不兼容插件开关
    val marketSources by PluginStore.marketSources.collectAsState()
    val activeMarketIdx by PluginStore.activeMarketIdx.collectAsState()
    val showIncompatible by PluginStore.showIncompatible.collectAsState()
    var showMarketSourcesDialog by remember { mutableStateOf(false) }
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
            file.writeText(BackupManager.exportJson(context))
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SoftDimens.PagePadding),
    ) {
        item {
            Spacer(Modifier.height(SoftDimens.CardSpacing))
            SettingsGroup(title = "外观") {
                SettingsRow(
                    icon = Icons.Filled.Palette,
                    title = "主题",
                    subtitle = themeLabel,
                    onClick = { showThemeDialog = true },
                )
                // v0.12.0：主题色换色器（DsPlayer 式色圈）
                ThemeColorPickerRow()
            }
            Spacer(Modifier.height(SoftDimens.CardSpacing))
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
            Spacer(Modifier.height(SoftDimens.CardSpacing))
            // v0.19.0：自定义解析接口（优先于订阅自带 parses）
            SettingsGroup(title = "解析接口") {
                val customParses by ParseStore.custom.collectAsState()
                SettingsRow(
                    icon = Icons.Filled.Add,
                    title = "添加解析接口",
                    subtitle = "自定义 jx 解析，播放时优先尝试",
                    onClick = { showAddParseDialog = true },
                )
                customParses.forEachIndexed { index, parse ->
                    SettingsRow(
                        icon = Icons.Filled.Link,
                        title = parse.name,
                        subtitle = "${if (parse.type == 1) "JSON" else "嗅探"} · ${parse.url}",
                        onClick = { },
                        trailing = {
                            IconButton(onClick = { ParseStore.remove(context, index) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                    )
                }
                if (customParses.isEmpty()) {
                    Text(
                        text = "暂无自定义解析，播放时只用订阅自带的解析线路",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(SoftDimens.CardSpacing))
            // v0.23.0：插件市场源（多源）+ 显示不兼容插件
            SettingsGroup(title = "插件") {
                SettingsRow(
                    icon = Icons.Filled.Store,
                    title = "插件市场源",
                    subtitle = marketSources.getOrNull(activeMarketIdx)?.name
                        ?: PluginStore.DEFAULT_MARKET_NAME,
                    onClick = { showMarketSourcesDialog = true },
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
            }
            Spacer(Modifier.height(SoftDimens.CardSpacing))
            // v0.36.0：源管理（站源 tab 删除，入口搬进设置；复用 sources 路由的完整逻辑）
            SettingsGroup(title = "源管理") {
                SettingsRow(
                    icon = Icons.Filled.Storage,
                    title = "站源管理",
                    subtitle = "订阅源 / 本地源 / 规则管理",
                    onClick = onSourcesClick,
                )
            }
            Spacer(Modifier.height(SoftDimens.CardSpacing))
            SettingsGroup(title = "追剧与下载") {
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
                SettingsRow(
                    icon = Icons.Filled.Download,
                    title = "下载管理",
                    subtitle = "查看下载队列与已完成",
                    onClick = onDownloadsClick,
                )
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
            }
            Spacer(Modifier.height(SoftDimens.CardSpacing))
            SettingsGroup(title = "存储") {
                SettingsRow(
                    icon = Icons.Filled.Cached,
                    title = "清理缓存",
                    subtitle = "共占用 $cacheSize",
                    onClick = { showClearCacheDialog = true },
                )
            }
            Spacer(Modifier.height(SoftDimens.CardSpacing))
            SettingsGroup(title = "备份与恢复") {
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
            Spacer(Modifier.height(SoftDimens.CardSpacing))
            SettingsGroup(title = "调试") {
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
                // v0.25.0：崩溃日志导出（xiaying/crash/ 打包 zip 经系统分享）
                val crashCount = remember { CrashHandler.list(context).size }
                SettingsRow(
                    icon = Icons.Filled.Warning,
                    title = "导出崩溃日志",
                    subtitle = if (crashCount > 0) "共 $crashCount 份，打包分享" else "暂无崩溃记录",
                    onClick = { exportCrashLog() },
                )
            }
            Spacer(Modifier.height(SoftDimens.CardSpacing))
            SettingsGroup(title = "帮助") {
                SettingsRow(
                    icon = Icons.Filled.MenuBook,
                    title = "使用手册",
                    subtitle = "加站源 / 规则 / 磁力，看这篇就够了",
                    onClick = onManualClick,
                )
            }
            Spacer(Modifier.height(SoftDimens.CardSpacing))
            SettingsGroup(title = "关于") {
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
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showThemeDialog) {
        SingleChoiceDialog(
            title = "选择主题",
            options = listOf("跟随系统", "深色", "浅色"),
            selected = themeLabel,
            onDismiss = { showThemeDialog = false },
            onSelect = {
                val mode = when (it) {
                    "深色" -> ThemeStore.MODE_DARK
                    "浅色" -> ThemeStore.MODE_LIGHT
                    else -> ThemeStore.MODE_SYSTEM
                }
                ThemeStore.setMode(context, mode)
                showThemeDialog = false
            },
        )
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
                    Toast.makeText(context, "去 实验室-插件市场 安装 libmpv 播放器引擎", Toast.LENGTH_LONG).show()
                }
            },
        )
    }
    // v0.23.0：插件市场源管理（增删 + 切换当前源）
    if (showMarketSourcesDialog) {
        var nameInput by remember { mutableStateOf("") }
        var urlInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showMarketSourcesDialog = false },
            shape = RoundedCornerShape(SoftDimens.CardCorner),
            title = { Text("插件市场源", style = SoftType.CardTitle) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    marketSources.forEachIndexed { i, s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = (if (i == activeMarketIdx) "● " else "") + s.name,
                                    style = SoftType.Body,
                                )
                                Text(
                                    text = s.url,
                                    style = SoftType.Caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            if (i != activeMarketIdx) {
                                AppButton(
                                    text = "切换",
                                    onClick = { PluginStore.setActiveMarket(context, i) },
                                    variant = AppButtonVariant.Ghost,
                                )
                            }
                            if (marketSources.size > 1) {
                                IconButton(onClick = {
                                    PluginStore.removeMarketSource(context, i)
                                }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    AppTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        placeholder = "名称",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    AppTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = "market.json 地址",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "可添加 DsPlayer 官方市场（实验性）：\n${PluginStore.DSPLAYER_MARKET_URL}",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                AppButton(
                    text = "添加",
                    onClick = {
                        if (!PluginStore.addMarketSource(context, nameInput, urlInput)) {
                            Toast.makeText(context, "名称/地址为空或地址已存在", Toast.LENGTH_SHORT).show()
                        } else {
                            nameInput = ""
                            urlInput = ""
                        }
                    },
                )
            },
            dismissButton = {
                AppButton(
                    text = "关闭",
                    onClick = { showMarketSourcesDialog = false },
                    variant = AppButtonVariant.Ghost,
                )
            },
        )
    }
    // v0.19.0：添加自定义解析接口
    if (showAddParseDialog) {
        var parseName by remember { mutableStateOf("") }
        var parseUrl by remember { mutableStateOf("") }
        var parseType by remember { mutableStateOf(1) }
        AlertDialog(
            onDismissRequest = { showAddParseDialog = false },
            shape = RoundedCornerShape(SoftDimens.CardCorner),
            title = { Text("添加解析接口", style = SoftType.CardTitle) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AppTextField(
                        value = parseName,
                        onValueChange = { parseName = it },
                        placeholder = "名称",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    AppTextField(
                        value = parseUrl,
                        onValueChange = { parseUrl = it },
                        placeholder = "接口地址（视频地址拼在后面）",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = parseType == 1, onClick = { parseType = 1 })
                        Text(
                            "JSON 接口",
                            style = SoftType.Body,
                            modifier = Modifier.clickable { parseType = 1 },
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        RadioButton(selected = parseType == 0, onClick = { parseType = 0 })
                        Text(
                            "网页嗅探",
                            style = SoftType.Body,
                            modifier = Modifier.clickable { parseType = 0 },
                        )
                    }
                    Text(
                        text = "JSON：GET 地址+视频地址，取返回 JSON 的 url 字段；嗅探：用 WebView 打开抓直链",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                AppButton(
                    text = "添加",
                    onClick = {
                        ParseStore.add(context, parseName, parseUrl, parseType)
                        showAddParseDialog = false
                    },
                )
            },
            dismissButton = {
                AppButton(
                    text = "取消",
                    onClick = { showAddParseDialog = false },
                    variant = AppButtonVariant.Ghost,
                )
            },
        )
    }
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            shape = RoundedCornerShape(SoftDimens.CardCorner),
            title = { Text("清理缓存", style = SoftType.CardTitle) },
            text = {
                Text(
                    "确定要清理 $cacheSize 缓存吗？播放记录与站源配置不会被删除。",
                    style = SoftType.Body,
                )
            },
            confirmButton = {
                AppButton(
                    text = "确定",
                    onClick = {
                        cacheSize = "0 MB" // 占位：实际接入 CacheManager 后执行真实清理
                        showClearCacheDialog = false
                    },
                )
            },
            dismissButton = {
                AppButton(
                    text = "取消",
                    onClick = { showClearCacheDialog = false },
                    variant = AppButtonVariant.Ghost,
                )
            },
        )
    }
}

@Composable
private fun SettingsGroup(
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
private fun SettingsRow(
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
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(SoftDimens.CardCorner),
        title = { Text(title, style = SoftType.CardTitle) },
        text = {
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
        },
        confirmButton = {},
        dismissButton = {
            AppButton(
                text = "取消",
                onClick = onDismiss,
                variant = AppButtonVariant.Ghost,
            )
        },
    )
}

/**
 * v0.12.0：主题色换色器。DsPlayer 式横向色圈，点选即全 App 换主色。
 */
@Composable
private fun ThemeColorPickerRow() {
    val context = LocalContext.current
    val selectedColor by ThemeStore.color.collectAsState()
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = "主题色",
            style = SoftType.Body,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeStore.COLOR_OPTIONS.forEach { argb ->
                val isSelected = argb == selectedColor
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
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

