package com.movieshell.ui.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import com.movieshell.core.log.LogBus
import com.movieshell.core.util.AtomicFile
import com.movieshell.core.util.StoragePaths
import com.movieshell.data.ai.AiRepairEngine
import com.movieshell.data.ai.AiRepairSettings
import com.movieshell.data.ai.LlmClient
import com.movieshell.data.download.DownloadManager
import com.movieshell.data.download.FollowCheckWorker
import com.movieshell.data.download.SubRefreshWorker
import com.movieshell.data.source.PluginMarket
import com.movieshell.data.source.SniffConfig
import com.movieshell.data.source.TmdbClient
import com.movieshell.data.source.subtitle.SubtitleLanguage
import com.movieshell.data.store.AiChatStore
import com.movieshell.data.store.DownloadStore
import com.movieshell.data.store.FollowStore
import com.movieshell.data.store.PlayerSettings
import com.movieshell.data.store.SettingsLayoutStore
import com.movieshell.data.store.SubRefreshStore
import com.movieshell.data.sync.BackupManager
import com.movieshell.data.sync.CloudSyncManager
import com.movieshell.domain.model.PlayerEngine
import com.movieshell.player.Anime4K
import com.movieshell.player.EngineHost
import com.movieshell.player.EnginePlugins
import com.movieshell.player.MagnetCache
import com.movieshell.player.MagnetRemote
import com.movieshell.player.MpvPluginLoader
import com.movieshell.player.PlayerManager
import com.movieshell.player.TrackLanguage
import com.movieshell.player.VideoCacheSettings
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppSwitch
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.TechTextField
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.2.7：可拖动的设置项注册表。
 *
 * 每一项只定义一次（图标 / 标题 / 副标题 / 点按动作 / 尾部控件 / 弹窗），
 * 住在哪由 SettingsLayoutStore 说了算；渲染时按所在页面自动换样式——
 * 设置页走 SegItem 卡片行，子页面走 SettingsRow 细行，内容与动作完全一样。
 * 新行要加入拖动：在 settingRowContent 里加一个分支，再往
 * SettingsLayoutStore.ALL_ITEMS 与 DEFAULT_LAYOUT 里登记。
 * v1.2.16：数据组 12 行也进了注册表（订阅自动刷新 / 追剧更新提醒 /
 * WebDAV / AI 助手 / 打开对话 / 备份与恢复 / 清理缓存 / 视频缓存上限 /
 * 视频缓存保留时长 / 磁力缓存 / 磁力引擎 / 清理旧字幕记忆），与播放组
 * 在主页面互拖；v1.2.19：三弹窗与缓存占用也搬进分支自带状态，数据行能
 * 拖进播放设置子页面，在子页面里点开也一样用。
 */
enum class SettingRowStyle { Card, Plain }

data class SettingRowContent(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
    val trailing: (@Composable () -> Unit)? = null,
    val dialog: (@Composable () -> Unit)? = null,
)

/**
 * v1.2.19：数据行里必须回宿主屏拿的东西只剩三样——打开 AI 对话（跨页跳转）、
 * 备份展开态（行与展开块共用，宿主屏各自持有）。三弹窗（云同步/AI/清缓存）与
 * 缓存占用都已搬进分支自带状态，行拖到哪都能用；数据行至此能进播放设置子页面。
 */
data class DataRowActions(
    val onAiChatClick: () -> Unit = {},
    val isBackupExpanded: Boolean = false,
    val onBackupToggle: () -> Unit = {},
)

@Composable
internal fun MovableSettingRow(
    id: String,
    style: SettingRowStyle,
    cardPosition: SegPos,
    divider: Boolean,
    onOpenMarketClick: () -> Unit,
    // v1.2.14：TMDB 行点进独立页（SettingItems 里不再弹 Key 弹窗）
    onTmdbSettingsClick: () -> Unit = {},
    // v1.2.16：数据行动作（见 DataRowActions）；播放行不用，默认即可。
    dataActions: DataRowActions = DataRowActions(),
) {
    val c = settingRowContent(id, onOpenMarketClick, onTmdbSettingsClick, dataActions)
    when (style) {
        SettingRowStyle.Card -> SegItem(
            position = cardPosition,
            icon = c.icon,
            title = c.title,
            subtitle = c.subtitle,
            onClick = c.onClick,
            trailing = c.trailing,
        )
        SettingRowStyle.Plain -> SettingsRow(
            icon = c.icon,
            title = c.title,
            subtitle = c.subtitle,
            onClick = c.onClick,
            trailing = c.trailing,
            divider = divider,
        )
    }
    c.dialog?.invoke()
}

@Composable
private fun settingRowContent(
    id: String,
    onOpenMarketClick: () -> Unit,
    onTmdbSettingsClick: () -> Unit = {},
    dataActions: DataRowActions = DataRowActions(),
): SettingRowContent {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    return when (id) {
        // v1.2.16：数据组 12 行（与播放组在主页面互拖；各分支自带状态，动作
        // 里只回设置页拿云同步/AI 大弹窗、打开对话、备份展开与清理缓存弹窗）。
        SettingsLayoutStore.ITEM_DATA_SUB_REFRESH -> {
            // v0.46.0：订阅自动刷新开关
            var enabled by remember { mutableStateOf(SubRefreshStore.isEnabled(context)) }
            SettingRowContent(
                icon = Icons.Outlined.Sync,
                title = "订阅自动刷新",
                subtitle = (if (enabled) "每天 1 次" else "关闭") + " · 失效的在订阅管理标出",
                onClick = {
                    val next = !enabled
                    SubRefreshStore.setEnabled(context, next)
                    enabled = next
                    SubRefreshWorker.reschedule(context)
                },
                trailing = {
                    AppSwitch(
                        checked = enabled,
                        onCheckedChange = {
                            SubRefreshStore.setEnabled(context, it)
                            enabled = it
                            SubRefreshWorker.reschedule(context)
                        },
                    )
                },
            )
        }
        SettingsLayoutStore.ITEM_DATA_FOLLOW_REMIND -> {
            // v0.26.0：追剧更新检测开关
            var enabled by remember { mutableStateOf(FollowStore.isCheckEnabled(context)) }
            SettingRowContent(
                icon = Icons.Outlined.NotificationsActive,
                title = "追剧更新提醒",
                subtitle = (if (enabled) "每天 2 次" else "关闭") + " · 有新集数发通知",
                onClick = {
                    val next = !enabled
                    FollowStore.setCheckEnabled(context, next)
                    enabled = next
                    FollowCheckWorker.reschedule(context)
                },
                trailing = {
                    AppSwitch(
                        checked = enabled,
                        onCheckedChange = {
                            FollowStore.setCheckEnabled(context, it)
                            enabled = it
                            FollowCheckWorker.reschedule(context)
                        },
                    )
                },
            )
        }
        SettingsLayoutStore.ITEM_DATA_WEBDAV -> {
            // v0.51.0：WebDAV 云同步；配置弹窗搬进分支（v1.2.19：原来在设置页），
            // 行拖到哪弹窗跟到哪，子页面里点开也一样用。
            val cloudConfigured = CloudSyncManager.isConfigured(context)
            var showDialog by remember { mutableStateOf(false) }
            SettingRowContent(
                icon = Icons.Outlined.CloudSync,
                title = "WebDAV 云同步",
                subtitle = if (cloudConfigured) "已配置 · " + CloudSyncManager.lastSyncText(context) else "未配置 · 坚果云/Alist/群晖",
                onClick = { showDialog = true },
                dialog = {
                    if (showDialog) {
                        WebDavConfigDialog(onDismiss = { showDialog = false })
                    }
                },
            )
        }
        SettingsLayoutStore.ITEM_DATA_AI -> {
            // v1.0.9：AI 助手——先在这里选 AI、填 Key；设置弹窗搬进分支（v1.2.19：原来在设置页）。
            val aiProvider = AiRepairSettings.provider(context)
            val aiConfigured = AiRepairSettings.apiKey(context).isNotBlank()
            var showDialog by remember { mutableStateOf(false) }
            SettingRowContent(
                icon = Icons.Outlined.AutoFixHigh,
                title = "AI 助手",
                subtitle = if (aiConfigured) "${aiProvider.name} · 已填 Key" else "未配置 · DeepSeek / 豆包 / 千问…",
                onClick = { showDialog = true },
                dialog = {
                    if (showDialog) {
                        AiSettingsDialog(
                            onAiChatClick = dataActions.onAiChatClick,
                            onDismiss = { showDialog = false },
                        )
                    }
                },
            )
        }
        SettingsLayoutStore.ITEM_DATA_AI_CHAT -> {
            // v1.1.3：打开对话——和 AI 接着聊写源、修源，可粘贴日志
            SettingRowContent(
                icon = Icons.Outlined.SmartToy,
                title = "打开对话",
                subtitle = "和 AI 接着聊写源、修源，可粘贴日志",
                onClick = dataActions.onAiChatClick,
            )
        }
        SettingsLayoutStore.ITEM_DATA_BACKUP -> {
            // 备份与恢复——点按展开/收起，子行（说明/备份/恢复/中断快照）由宿主屏
            // 在行后调 BackupExpandBlock 渲染，行搬到哪子行跟到哪。
            SettingRowContent(
                icon = Icons.Outlined.Backup,
                title = "备份与恢复",
                subtitle = "导出 / 导入 · 订阅·追剧·书架·音乐·直播",
                onClick = dataActions.onBackupToggle,
                trailing = {
                    Icon(
                        imageVector = if (dataActions.isBackupExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (dataActions.isBackupExpanded) "收起" else "展开",
                        tint = cs.onSurfaceVariant,
                    )
                },
            )
        }
        SettingsLayoutStore.ITEM_DATA_CLEAR_CACHE -> {
            // v1.2.19：清理确认弹窗搬进分支（原来在设置页）；占用数分支自算，
            // 清完只刷新本行的数，视频上限行的“已用”下次进页面时重算。
            val scope = rememberCoroutineScope()
            var cacheSize by remember { mutableStateOf("计算中…") }
            var showDialog by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                cacheSize = runCatching { refreshCacheSize(context) }.getOrDefault(cacheSize)
            }
            SettingRowContent(
                icon = Icons.Outlined.CleaningServices,
                title = "清理缓存",
                subtitle = "$cacheSize · 视频分片 + 图片缓存",
                onClick = { showDialog = true },
                dialog = {
                    if (showDialog) {
                        TechDialog(
                            onDismissRequest = { showDialog = false },
                            title = "清理缓存",
                            confirmText = "确定",
                            onConfirm = {
                                showDialog = false
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
                },
            )
        }
        SettingsLayoutStore.ITEM_DATA_VIDEO_CAP -> {
            // v1.2.14：第 6 项——视频缓存上限可调（默认 500MB；点按循环切换档位，改动下次播放生效，不用重启）。
            var videoCapMb by remember { mutableStateOf(VideoCacheSettings.capMb(context)) }
            // v1.2.19：占用数分支自算（原来走设置页的 videoSize），进页面时算一次。
            var videoSize by remember { mutableStateOf("—") }
            LaunchedEffect(Unit) {
                videoSize = withContext(Dispatchers.IO) {
                    runCatching {
                        PluginMarket.formatSize(dirBytes(File(context.cacheDir, "video_cache")))
                    }.getOrDefault("—")
                }
            }
            fun cycle() {
                val next = VideoCacheSettings.nextInCycle(
                    VideoCacheSettings.CAP_CHOICES_MB, videoCapMb,
                )
                videoCapMb = next
                VideoCacheSettings.setCapMb(context, next)
                AppToast.show(context, "视频缓存上限：${VideoCacheSettings.formatCapMb(next)}，下次播放生效", long = false)
            }
            SettingRowContent(
                icon = Icons.Outlined.CleaningServices,
                title = "视频缓存上限",
                subtitle = "已用 $videoSize · 上限 ${VideoCacheSettings.formatCapMb(videoCapMb)} · 改动下次播放生效",
                onClick = { cycle() },
                trailing = {
                    TextButton(onClick = { cycle() }) { Text("上限 ${VideoCacheSettings.formatCapMb(videoCapMb)}", style = MaterialTheme.typography.labelLarge) }
                },
            )
        }
        SettingsLayoutStore.ITEM_DATA_VIDEO_AGE -> {
            // v1.2.14：第 6 项——视频缓存保留时长（默认 30 天；超期分片在建缓存完成与写新分片时自动清掉，正在播的不碰）。
            var videoMaxAgeDays by remember { mutableStateOf(VideoCacheSettings.maxAgeDays(context)) }
            fun cycle() {
                val next = VideoCacheSettings.nextInCycle(
                    VideoCacheSettings.MAX_AGE_CHOICES_DAYS, videoMaxAgeDays,
                )
                videoMaxAgeDays = next
                VideoCacheSettings.setMaxAgeDays(context, next)
                AppToast.show(context, "视频缓存保留时长：${VideoCacheSettings.formatAge(next)}，下次播放生效", long = false)
            }
            SettingRowContent(
                icon = Icons.Outlined.CleaningServices,
                title = "视频缓存保留时长",
                subtitle = if (videoMaxAgeDays <= 0) {
                    "旧分片不过期 · 只受上限约束 · 改动下次播放生效"
                } else {
                    "超过 ${VideoCacheSettings.formatAge(videoMaxAgeDays)}未用的分片自动清理 · 改动下次播放生效"
                },
                onClick = { cycle() },
                trailing = {
                    TextButton(onClick = { cycle() }) { Text(VideoCacheSettings.formatAge(videoMaxAgeDays), style = MaterialTheme.typography.labelLarge) }
                },
            )
        }
        SettingsLayoutStore.ITEM_DATA_MAGNET_CACHE -> {
            // v0.294.26：磁力缓存占用 + 清理；尾部点按循环切换上限
            val scope = rememberCoroutineScope()
            var magnetSize by remember { mutableStateOf("计算中…") }
            var magnetCap by remember { mutableStateOf(MagnetCache.capGb(context)) }
            var showDialog by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                magnetSize = withContext(Dispatchers.IO) {
                    runCatching { MagnetCache.format(MagnetCache.totalBytes(context)) }.getOrDefault("—")
                }
            }
            SettingRowContent(
                icon = Icons.Outlined.CleaningServices,
                title = "磁力缓存",
                subtitle = "$magnetSize · 超过上限或 3 天未用自动清理",
                onClick = { showDialog = true },
                trailing = {
                    TextButton(onClick = {
                        val ch = MagnetCache.CAP_CHOICES_GB
                        val next = ch[(ch.indexOf(magnetCap) + 1).mod(ch.size)]
                        magnetCap = next
                        MagnetCache.setCapGb(context, next)
                        AppToast.show(context, "磁力缓存上限：${next}GB", long = false)
                    }) { Text("上限 ${magnetCap}GB", style = MaterialTheme.typography.labelLarge) }
                },
                dialog = {
                    if (showDialog) {
                        TechDialog(
                            onDismissRequest = { showDialog = false },
                            title = "清理磁力缓存",
                            confirmText = "清理",
                            onConfirm = {
                                showDialog = false
                                scope.launch(Dispatchers.IO) {
                                    // v0.294.31：先结束磁力进程再删，避免删到正在读写的文件
                                    runCatching { MagnetRemote.stopBlocking(context) }
                                    val keep = emptySet<String>()
                                    MagnetCache.dir(context).listFiles()
                                        ?.filter { it.name !in keep }
                                        ?.forEach { runCatching { it.deleteRecursively() } }
                                    magnetSize = MagnetCache.format(MagnetCache.totalBytes(context))
                                    withContext(Dispatchers.Main) {
                                        AppToast.show(context, "磁力缓存已清理", long = false)
                                    }
                                }
                            },
                        ) {
                            Text(
                                "删除磁力边下边播留下的视频文件，共 $magnetSize。正在进行的磁力播放会被停止。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                },
            )
        }
        SettingsLayoutStore.ITEM_DATA_MAGNET_ENGINE -> {
            // v0.294.32：磁力引擎优先级（迅雷优先 / BT 优先），失败自动换另一个
            var prefer by remember { mutableStateOf(EnginePlugins.prefer(context)) }
            val bt = EnginePlugins.btInstalled(context)
            val xl = EnginePlugins.xlInstalled(context)
            SettingRowContent(
                icon = Icons.Outlined.CleaningServices,
                title = "磁力引擎",
                subtitle = "迅雷${if (xl) "已装" else "未装"} · BT${if (bt) "已装" else "未装"} · 失败自动换另一个",
                onClick = {
                    prefer = if (prefer == "bt") "xunlei" else "bt"
                    EnginePlugins.setPrefer(context, prefer)
                },
                trailing = {
                    Text(
                        if (prefer == "bt") "BT 优先" else "迅雷优先",
                        style = MaterialTheme.typography.labelLarge, color = cs.primary,
                    )
                },
            )
        }
        SettingsLayoutStore.ITEM_DATA_LEGACY_SUBTITLE -> {
            // v0.188.0：旧字幕 key 清理入口——点开先说明条数，确认后才删
            // v1.2.14：副标题写实时值——点过一次行后就显示已统计的条数，清理完归零。
            var legacySubtitleCount by remember { mutableIntStateOf(-1) }
            var showDialog by remember { mutableStateOf(false) }
            SettingRowContent(
                icon = Icons.Outlined.Subtitles,
                title = "清理旧字幕记忆",
                subtitle = if (legacySubtitleCount >= 0) "共 $legacySubtitleCount 条旧记忆 · 点按清理" else "只删旧格式按剧记忆，需手动确认",
                onClick = {
                    legacySubtitleCount = countLegacyPerShowKeys(context)
                    showDialog = true
                },
                dialog = {
                    if (showDialog) {
                        TechDialog(
                            onDismissRequest = { showDialog = false },
                            title = "清理旧字幕记忆",
                            confirmText = "清理",
                            onConfirm = {
                                showDialog = false
                                val n = PlayerSettings.clearLegacyPerShowKeys(context)
                                legacySubtitleCount = 0
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
                },
            )
        }
        SettingsLayoutStore.ITEM_ENGINE -> {
            // v0.20.0：播放器内核（Media3 默认，libmpv 可选，记忆选择）
            val engine by PlayerSettings.engine.collectAsState()
            var showEngineDialog by remember { mutableStateOf(false) }
            SettingRowContent(
                icon = Icons.Outlined.SmartDisplay,
                title = "播放器内核",
                subtitle = when {
                    engine == PlayerEngine.MEDIA3 -> "Media3（默认）"
                    MpvPluginLoader.isInstalled(context) -> "libmpv · 插件内核"
                    else -> "libmpv · 插件未安装"
                },
                onClick = { showEngineDialog = true },
                dialog = {
                    if (showEngineDialog) {
                        // v1.1.31 精致化：未安装时选项上直接写明点后去市场安装，选之前就知道
                        val libmpvOption =
                            if (MpvPluginLoader.isInstalled(context)) "libmpv" else "libmpv（未安装 · 点后去市场安装）"
                        SingleChoiceDialog(
                            title = "播放器内核",
                            options = listOf("Media3（默认）", libmpvOption),
                            selected = if (engine == PlayerEngine.MEDIA3) "Media3（默认）" else libmpvOption,
                            onDismiss = { showEngineDialog = false },
                            onSelect = {
                                if (it.startsWith("Media3") || MpvPluginLoader.isInstalled(context)) {
                                    PlayerSettings.setEngine(
                                        context,
                                        if (it.startsWith("Media3")) PlayerEngine.MEDIA3 else PlayerEngine.LIBMPV,
                                    )
                                    // v1.3.21 M16：设置换内核只对下一次起播生效——这里拿不到当前
                                    // 播放地址/进度/请求头/字幕（EngineHost.currentUrl 未对外暴露，
                                    // 设置页也不在播放器组合内），做不了“取断点停播再重播”；
                                    // 只清掉失败转移写下的临时覆盖，否则当前播放的重试点仍走旧覆盖。
                                    EngineHost.setEngineOverride(null)
                                    AppToast.show(context, "播放器内核已切换，下次播放时生效", long = false)
                                    showEngineDialog = false
                                } else {
                                    // v1.1.31：未安装不再只 Toast，直接跳市场页签装插件
                                    showEngineDialog = false
                                    onOpenMarketClick()
                                }
                            },
                        )
                    }
                },
            )
        }
        SettingsLayoutStore.ITEM_AUTOPLAY -> {
            // v0.294.5：直播自动播放——始终 / 仅 Wi‑Fi / 关闭；点整行弹紧凑下拉选
            val liveAutoplay by PlayerSettings.liveAutoplay.collectAsState()
            var showMenu by remember { mutableStateOf(false) }
            val labels = listOf("始终", "仅 Wi‑Fi", "关闭")
            SettingRowContent(
                icon = Icons.Outlined.LiveTv,
                title = "直播自动播放",
                subtitle = when (liveAutoplay) {
                    PlayerSettings.LIVE_AUTOPLAY_WIFI -> "仅 Wi‑Fi 下进直播页自动起播"
                    PlayerSettings.LIVE_AUTOPLAY_OFF -> "关闭 · 点频道才播"
                    else -> "始终 · 进直播页自动起播"
                },
                onClick = { showMenu = true },
                trailing = {
                    Box {
                        Text(
                            labels.getOrElse(liveAutoplay) { labels[0] },
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.primary,
                        )
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            labels.forEachIndexed { i, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        showMenu = false
                                        PlayerSettings.setLiveAutoplay(context, i)
                                    },
                                    trailingIcon = if (i == liveAutoplay) {
                                        { Icon(Icons.Outlined.Check, null, tint = cs.primary) }
                                    } else null,
                                )
                            }
                        }
                    }
                },
            )
        }
        SettingsLayoutStore.ITEM_ADFILTER -> {
            // v1.1.31：去广告总开关（SniffConfig.adFilterEnabled，默认开；Media3 与 libmpv 共用）
            var adFilterOn by remember { mutableStateOf(SniffConfig.adFilterEnabled) }
            LaunchedEffect(Unit) {
                SniffConfig.ensureLoaded(context)
                adFilterOn = SniffConfig.adFilterEnabled
            }
            SettingRowContent(
                icon = Icons.Outlined.Block,
                title = "去广告",
                subtitle = if (adFilterOn) "开 · 过滤 m3u8 插播广告" else "关 · 不过滤插播广告",
                onClick = {
                    SniffConfig.update(context, adFilter = !adFilterOn)
                    adFilterOn = !adFilterOn
                },
                trailing = {
                    AppSwitch(
                        checked = adFilterOn,
                        onCheckedChange = {
                            SniffConfig.update(context, adFilter = it)
                            adFilterOn = it
                        },
                    )
                },
            )
        }
        SettingsLayoutStore.ITEM_MAX_PARALLEL -> {
            var maxParallel by remember { mutableStateOf(DownloadStore.maxParallel(context)) }
            SettingRowContent(
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
        SettingsLayoutStore.ITEM_TMDB_KEY -> {
            // v1.2.13：TMDB Key（本地文件夹海报墙用）——key 默认空，用户自己填；
            // 没填或清空后海报墙不联网只显示占位；key 本身不显示在副标题里。
            // v1.2.14：点行进独立页（填 Key、测试连接、改地址都在那页做），这里只留状态副标题。
            val stored = TmdbClient.storedApiKey(context)
            SettingRowContent(
                icon = Icons.Outlined.Image,
                title = "TMDB Key",
                subtitle = when {
                    stored == null -> "未填写 · 海报墙暂不可用"
                    stored.isBlank() -> "已清空 · 海报墙不联网，只显示占位"
                    else -> "已填写 · 海报墙匹配用"
                },
                onClick = onTmdbSettingsClick,
            )
        }
        SettingsLayoutStore.ITEM_DEF_ORIENTATION -> {
            // v1.2.14 B片（9）：默认播放方向——进播放器时锁定的方向，默认跟随重力感应
            val defOrientation by PlayerSettings.defaultOrientation.collectAsState()
            var showDialog by remember { mutableStateOf(false) }
            val labels = listOf("跟随重力感应", "横屏", "竖屏")
            SettingRowContent(
                icon = Icons.Outlined.ScreenRotation,
                title = "默认播放方向",
                subtitle = "进播放器时" + labels.getOrElse(defOrientation) { labels[0] },
                onClick = { showDialog = true },
                dialog = {
                    if (showDialog) {
                        SingleChoiceDialog(
                            title = "默认播放方向",
                            options = labels,
                            selected = labels.getOrElse(defOrientation) { labels[0] },
                            onDismiss = { showDialog = false },
                            onSelect = {
                                PlayerSettings.setDefaultOrientation(context, labels.indexOf(it))
                                showDialog = false
                            },
                        )
                    }
                },
            )
        }
        SettingsLayoutStore.ITEM_TRANSITION -> {
            // v1.2.14 B片（9）：播放器打开/关闭的转场动画
            val transitionMode by PlayerSettings.transitionMode.collectAsState()
            var showDialog by remember { mutableStateOf(false) }
            val labels = listOf("系统默认", "淡入", "缩放", "海报碎片")
            SettingRowContent(
                icon = Icons.Outlined.Movie,
                title = "转场动画",
                subtitle = "打开/关闭播放器时" + labels.getOrElse(transitionMode) { labels[0] },
                onClick = { showDialog = true },
                dialog = {
                    if (showDialog) {
                        SingleChoiceDialog(
                            title = "转场动画",
                            options = labels,
                            selected = labels.getOrElse(transitionMode) { labels[0] },
                            onDismiss = { showDialog = false },
                            onSelect = {
                                PlayerSettings.setTransitionMode(context, labels.indexOf(it))
                                showDialog = false
                            },
                        )
                    }
                },
            )
        }
        SettingsLayoutStore.ITEM_AUDIO_LANG -> {
            // v1.2.14 B片（3）：首选音频语言——多音轨时起播自动选，齿轮页可手动改
            val audioLang by PlayerSettings.audioLanguage.collectAsState()
            var showDialog by remember { mutableStateOf(false) }
            val options = TrackLanguage.AUDIO_LANGUAGE_OPTIONS
            SettingRowContent(
                icon = Icons.Outlined.MusicNote,
                title = "首选音频语言",
                subtitle = "多音轨时优先选" + TrackLanguage.optionLabel(audioLang),
                onClick = { showDialog = true },
                dialog = {
                    if (showDialog) {
                        SingleChoiceDialog(
                            title = "首选音频语言",
                            options = options.map { it.second },
                            selected = TrackLanguage.optionLabel(audioLang),
                            onDismiss = { showDialog = false },
                            onSelect = { label ->
                                PlayerSettings.setAudioLanguage(
                                    context,
                                    options.firstOrNull { it.second == label }?.first.orEmpty(),
                                )
                                showDialog = false
                            },
                        )
                    }
                },
            )
        }
        SettingsLayoutStore.ITEM_SUB_LANG -> {
            // v1.2.14 B片（3）：首选字幕语言——在线字幕搜索时匹配的排前面
            val subLang by PlayerSettings.subtitleLanguage.collectAsState()
            var showDialog by remember { mutableStateOf(false) }
            val options = SubtitleLanguage.SUBTITLE_LANGUAGE_OPTIONS
            SettingRowContent(
                icon = Icons.Outlined.Subtitles,
                title = "首选字幕语言",
                subtitle = "在线字幕搜索时优先" + SubtitleLanguage.optionLabel(subLang),
                onClick = { showDialog = true },
                dialog = {
                    if (showDialog) {
                        SingleChoiceDialog(
                            title = "首选字幕语言",
                            options = options.map { it.second },
                            selected = SubtitleLanguage.optionLabel(subLang),
                            onDismiss = { showDialog = false },
                            onSelect = { label ->
                                PlayerSettings.setSubtitleLanguage(
                                    context,
                                    options.firstOrNull { it.second == label }?.first.orEmpty(),
                                )
                                showDialog = false
                            },
                        )
                    }
                },
            )
        }
        SettingsLayoutStore.ITEM_ANIME4K -> {
            // v1.3.25 合并 5：Anime4K 画质增强——仅 libmpv 引擎生效，
            // 换档即时生效（正在用 libmpv 播时直接挂着色器）。
            val anime4kKey by PlayerSettings.anime4kMode.collectAsState()
            var showDialog by remember { mutableStateOf(false) }
            val mode = Anime4K.fromKey(anime4kKey)
            SettingRowContent(
                icon = Icons.Outlined.AutoAwesome,
                title = "画质增强（Anime4K）",
                subtitle = "仅 libmpv 引擎生效，当前：" + mode.label +
                    if (mode == Anime4K.Mode.VL) "（画质档发热明显）" else "",
                onClick = { showDialog = true },
                dialog = {
                    if (showDialog) {
                        SingleChoiceDialog(
                            title = "画质增强（Anime4K）",
                            options = Anime4K.Mode.values().map { it.label },
                            selected = mode.label,
                            onDismiss = { showDialog = false },
                            onSelect = { label ->
                                val picked = Anime4K.Mode.values()
                                    .firstOrNull { it.label == label } ?: Anime4K.Mode.OFF
                                PlayerSettings.setAnime4KMode(context, picked.key)
                                EngineHost.setAnime4K(picked)
                                showDialog = false
                            },
                        )
                    }
                },
            )
        }
        // v0.27.7：重力感应自动横屏（默认开）
        else -> {
            val gravityRotate by PlayerSettings.gravityRotate.collectAsState()
            SettingRowContent(
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
    }
}

// v1.2.19：缓存占用 helper 从设置页搬到这里——清缓存与视频上限两分支自算占用，行拖到哪都能用，不再依赖设置页的派生值。
/**
 * v0.101.0：设置页缓存清理。
 * - 视频缓存：Media3 SimpleCache，目录为 cacheDir/video_cache（见 PlayerManager.videoCache）。
 * - 图片缓存：Coil 2 默认磁盘缓存目录 cacheDir/image_cache。
 */
internal fun cacheDirs(context: Context): List<File> = listOf(
    File(context.cacheDir, "video_cache"),
    File(context.cacheDir, "image_cache"),
)

internal fun dirBytes(dir: File): Long =
    if (dir.isDirectory) {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } else {
        0L
    }

internal suspend fun refreshCacheSize(context: Context): String =
    withContext(Dispatchers.IO) {
        PluginMarket.formatSize(cacheDirs(context).sumOf { dirBytes(it) }).ifBlank { "0 B" }
    }

/**
 * v1.2.19：WebDAV 云同步配置弹窗从设置页搬到这里（分支自带状态），行拖到哪
 * 弹窗跟到哪；子页面里点开也一样用。冲突确认弹窗嵌在里面，共用 cloudWorking。
 */
@Composable
internal fun WebDavConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cloudWorking by remember { mutableStateOf(false) }
    var lastSyncText by remember { mutableStateOf(CloudSyncManager.lastSyncText(context)) }
    var showCloudConflict by remember { mutableStateOf(false) }
    var cloudConflictText by remember { mutableStateOf("") }
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
        onDismissRequest = { if (!cloudWorking) onDismiss() },
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
                                // v1.3.6：try/finally 保底复位——异常时按钮不再全灰、弹窗关不掉。
                                try {
                                    val err = CloudSyncManager.test(context)
                                    AppToast.show(context, err ?: "连接成功", long = false)
                                } catch (e: Exception) {
                                    AppToast.show(context, "连接失败：${e.message}", long = true)
                                } finally {
                                    cloudWorking = false
                                }
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
                                // v1.3.6：try/finally 保底复位——异常时按钮不再全灰、弹窗关不掉。
                                try {
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
                                } finally {
                                    cloudWorking = false
                                    lastSyncText = CloudSyncManager.lastSyncText(context)
                                }
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
                            // v1.3.6：try/finally 保底复位——异常时按钮不再全灰、弹窗关不掉。
                            try {
                                when (val r = CloudSyncManager.restore(context)) {
                                    is CloudSyncManager.SyncResult.Ok ->
                                        AppToast.show(context, "已从云端恢复", long = false)
                                    is CloudSyncManager.SyncResult.Failed ->
                                        AppToast.show(context, r.reason, long = true)
                                    is CloudSyncManager.SyncResult.Conflict ->
                                        AppToast.show(context, r.reason, long = true)
                                }
                            } finally {
                                cloudWorking = false
                                lastSyncText = CloudSyncManager.lastSyncText(context)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
    }
    // v1.3.6：冲突弹窗必须受 if (showCloudConflict) 守卫——之前无条件渲染，
    // 点取消只改标志位、下一帧又弹出来，关不掉（与 1.2.22 AI 服务商弹窗同病）。
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
                    // v1.3.6：try/finally 保底复位——异常时按钮不再全灰、弹窗关不掉。
                    try {
                        when (val r = CloudSyncManager.backup(context, force = true)) {
                            is CloudSyncManager.SyncResult.Ok ->
                                AppToast.show(context, "备份已上传", long = false)
                            is CloudSyncManager.SyncResult.Failed ->
                                AppToast.show(context, r.reason, long = true)
                            is CloudSyncManager.SyncResult.Conflict ->
                                AppToast.show(context, r.reason, long = true)
                        }
                    } finally {
                        cloudWorking = false
                        lastSyncText = CloudSyncManager.lastSyncText(context)
                    }
                }
            },
        ) {
            Text(cloudConflictText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * v1.2.19：AI 助手设置弹窗从设置页搬到这里（分支自带状态），行拖到哪弹窗跟到哪；
 * 服务商点选弹窗嵌在里面。去对话按钮走 onAiChatClick（宿主屏传导航回调）。
 */
@Composable
internal fun AiSettingsDialog(
    onAiChatClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var aiProviderId by remember { mutableStateOf(AiRepairSettings.provider(context).id) }
    var aiWorking by remember { mutableStateOf(false) }
    var aiTestText by remember { mutableStateOf("") }
    var showAiProviderDialog by remember { mutableStateOf(false) }
    val provider = AiRepairSettings.PRESETS.firstOrNull { it.id == aiProviderId }
        ?: AiRepairSettings.PRESETS.first()
    var keyInput by remember(aiProviderId) { mutableStateOf(AiRepairSettings.apiKey(context)) }
    var modelInput by remember(aiProviderId) { mutableStateOf(AiRepairSettings.model(context)) }
    var baseUrlInput by remember(aiProviderId) { mutableStateOf(AiRepairSettings.baseUrl(context)) }
    var showKey by remember { mutableStateOf(false) }
    // v1.1.12：我的模型支持看图（默认关；豆包方舟接入点 ID 看不出支不支持，全靠这个开关）。
    var visionOverride by remember { mutableStateOf(AiRepairSettings.visionOverride(context)) }
    // v1.1.1：模型点选弹窗（内置模型列表，不用手打记名字）
    var showAiModelDialog by remember { mutableStateOf(false) }
    // v1.1.1：AI 写新源——网址 + 源名（可空），复用上面配好的服务商、Key 与模型
    var writeUrl by remember { mutableStateOf("") }
    var writeName by remember { mutableStateOf("") }
    var writeText by remember { mutableStateOf("") }
    // v1.1.3：写源失败的原因，单独记一份，失败文案下给“去对话里接着改”用。
    var writeFailReason by remember { mutableStateOf<String?>(null) }
    // v1.1.1：写源类型三选，默认 drpy JS；保存位置由类型定
    // v1.1.2：默认改成自动选——不知道选哪个就让 AI 按站结构定类型
    var writeKind by remember { mutableStateOf("auto") }
    TechDialog(
        onDismissRequest = { if (!aiWorking) onDismiss() },
        title = "AI 助手",
        dismissText = "关闭",
        // v1.1.20：一屏放下、不要滚动——弹窗加宽到 94% 屏宽 + 紧凑模式
        // （内边距/标题/底部按钮收紧），内容区同步压间距、压字号、压按钮高。
        widthFraction = 0.94f,
        compact = true,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "源失效时先分清坏在哪：结构变了的才交给 AI 修，站挂了的 AI 也修不了。Key 只存本机，不上传。",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAiProviderDialog = true }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "AI 服务商",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!provider.domestic) {
                Text(
                    "国外服务商在国内网络下可能调不通，需 VPN；Key 也要国外方式获取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            // v1.1.20：显示/隐藏并到输入框同一行，不再单独占一行。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TechTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    placeholder = "API Key",
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = !aiWorking,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    compact = true,
                )
                Text(
                    text = if (showKey) "隐藏" else "显示",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(enabled = !aiWorking) { showKey = !showKey }
                        .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                )
            }
            if (provider.models.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !aiWorking) { showAiModelDialog = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "模型",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = modelInput.ifBlank { provider.defaultModel },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                TechTextField(
                    value = modelInput,
                    onValueChange = { modelInput = it },
                    placeholder = if (provider.id == "doubao") "模型：方舟接入点 ID，如 ep-…" else "模型",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !aiWorking,
                    compact = true,
                )
                // v1.1.2：豆包填模型名会报 404，常驻提示一次，省得再踩
                if (provider.id == "doubao") {
                    Text(
                        "豆包这里填方舟接入点 ID（ep- 开头），填模型名会报 404。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // v1.1.12：我的模型支持看图——只在模型或接入点本身能看图时开；开着就不再
            // 按模型名判断，直接发图。模型本身不支持时服务商会报错，开了不等于一定能看。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "我的模型支持看图",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "豆包方舟接入点 ID 看不出支不支持，靠这个开关认；开着就直接发图，模型本身不支持时服务商会报错。",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppSwitch(
                    checked = visionOverride,
                    onCheckedChange = {
                        visionOverride = it
                        AiRepairSettings.setVisionOverride(context, it)
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            if (provider.id == "custom") {
                TechTextField(
                    value = baseUrlInput,
                    onValueChange = { baseUrlInput = it },
                    placeholder = "请求地址，如 https://api.example.com/v1",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !aiWorking,
                    compact = true,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (aiTestText.isNotBlank()) {
                Text(
                    aiTestText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                AppButton(
                    text = "测试连接",
                    variant = AppButtonVariant.Ghost,
                    enabled = !aiWorking,
                    compact = true,
                    onClick = {
                        AiRepairSettings.setApiKey(context, keyInput)
                        AiRepairSettings.setModel(context, modelInput)
                        if (provider.id == "custom") AiRepairSettings.setBaseUrl(context, baseUrlInput)
                        aiWorking = true
                        aiTestText = "测试中…"
                        scope.launch {
                            val r = LlmClient.testConnection(context)
                            aiWorking = false
                            aiTestText = when (r) {
                                is LlmClient.ChatResult.Ok -> "连接成功（${r.ms}ms）：${r.text}"
                                is LlmClient.ChatResult.Fail -> "连接失败：${r.reason}"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                AppButton(
                    text = "保存",
                    enabled = !aiWorking,
                    compact = true,
                    onClick = {
                        AiRepairSettings.setApiKey(context, keyInput)
                        AiRepairSettings.setModel(context, modelInput)
                        if (provider.id == "custom") AiRepairSettings.setBaseUrl(context, baseUrlInput)
                        AppToast.show(context, "已保存", long = false)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "写新源",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "填一个视频站网址，AI 照着站结构写一条新源；类型不知道就选自动，AI 自己定；先跑探活验证，首页能出数才保存，验证不过不保存。写不了小说与直播源。",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                AppButton(
                    text = "自动",
                    variant = if (writeKind == "auto") AppButtonVariant.Primary else AppButtonVariant.Ghost,
                    enabled = !aiWorking,
                    compact = true,
                    onClick = { writeKind = "auto" },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                AppButton(
                    text = "drpy",
                    variant = if (writeKind == "js") AppButtonVariant.Primary else AppButtonVariant.Ghost,
                    enabled = !aiWorking,
                    compact = true,
                    onClick = { writeKind = "js" },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                AppButton(
                    text = "Python",
                    variant = if (writeKind == "py") AppButtonVariant.Primary else AppButtonVariant.Ghost,
                    enabled = !aiWorking,
                    compact = true,
                    onClick = { writeKind = "py" },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                AppButton(
                    text = "PHP",
                    variant = if (writeKind == "php") AppButtonVariant.Primary else AppButtonVariant.Ghost,
                    enabled = !aiWorking,
                    compact = true,
                    onClick = { writeKind = "php" },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                when (writeKind) {
                    "py" -> "保存到 xiaying/py/"
                    "php" -> "保存到 xiaying/php/t4_demo/"
                    "auto" -> "AI 先定类型，验证过按类型保存"
                    else -> "保存到 xiaying/js/"
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            TechTextField(
                value = writeUrl,
                onValueChange = { writeUrl = it },
                placeholder = "网址，如 https://www.xxx.com",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !aiWorking,
                compact = true,
            )
            Spacer(Modifier.height(4.dp))
            TechTextField(
                value = writeName,
                onValueChange = { writeName = it },
                placeholder = "源名（可空，不填用站名）",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !aiWorking,
                compact = true,
            )
            Spacer(Modifier.height(4.dp))
            if (writeText.isNotBlank()) {
                Text(
                    writeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            // v1.1.3：写源失败时去对话里接着改——网址 + 失败原因种进对话，进 AI TAB 自动发一次。
            if (writeFailReason != null) {
                AppButton(
                    text = "去对话里接着改",
                    variant = AppButtonVariant.Ghost,
                    enabled = !aiWorking,
                    compact = true,
                    onClick = {
                        writeFailReason?.let { reason ->
                            AiChatStore.seedFromWrite(writeUrl, reason)
                            onDismiss()
                            onAiChatClick()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
            }
            AppButton(
                text = if (aiWorking) "写源中…" else "让 AI 写源",
                enabled = !aiWorking,
                compact = true,
                onClick = {
                    AiRepairSettings.setApiKey(context, keyInput)
                    AiRepairSettings.setModel(context, modelInput)
                    if (provider.id == "custom") AiRepairSettings.setBaseUrl(context, baseUrlInput)
                    aiWorking = true
                    writeText = "写源中…先取首页，再让 AI 写，写完跑验证"
                    writeFailReason = null
                    scope.launch {
                        val r = AiRepairEngine.writeSource(context, writeUrl, writeName, writeKind)
                        aiWorking = false
                        writeText = when (r) {
                            is AiRepairEngine.WriteResult.Ok -> {
                                writeFailReason = null
                                val kindName = when (r.kind) {
                                    "py" -> "Python"
                                    "php" -> "PHP"
                                    else -> "drpy JS"
                                }
                                "写好了（$kindName），已保存：${r.fileName}（${r.ms}ms），去源管理里就能看到"
                            }
                            is AiRepairEngine.WriteResult.Fail -> {
                                writeFailReason = r.reason
                                "没写成：${r.reason}"
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (showAiModelDialog) {
        val modelOptions = (provider.models + listOf(modelInput)).distinct().filter { it.isNotBlank() }
        SingleChoiceDialog(
            title = "模型",
            options = modelOptions,
            selected = modelInput.ifBlank { provider.defaultModel },
            onDismiss = { showAiModelDialog = false },
            onSelect = { name ->
                modelInput = name
                showAiModelDialog = false
            },
        )
    }
    // v1.2.22：服务商弹窗必须受 showAiProviderDialog 守卫——之前无条件渲染，
    // 点选/取消只改标志位、弹窗下一帧又弹出来，AI 设置页根本返回不了。
    if (showAiProviderDialog) {
        SingleChoiceDialog(
            title = "AI 服务商",
            options = AiRepairSettings.PRESETS.map { it.name },
            selected = (AiRepairSettings.PRESETS.firstOrNull { it.id == aiProviderId }
                ?: AiRepairSettings.PRESETS.first()).name,
            onDismiss = { showAiProviderDialog = false },
            onSelect = { name ->
                val p = AiRepairSettings.PRESETS.firstOrNull { it.name == name }
                if (p != null) {
                    AiRepairSettings.setProvider(context, p.id)
                    aiProviderId = p.id
                }
                showAiProviderDialog = false
            },
        )
    }
}

/**
 * v1.2.19：备份展开块从设置页抽成共享组件——行搬到哪子行跟到哪，两个宿主屏
 * （设置页卡片行 / 播放设置页细行）都调它；导出、导入 picker 与中断快照状态
 * 都收在里面。isLastRow = 备份行是不是所在容器的最后一行（决定圆角/分隔线）。
 */
@Composable
internal fun BackupExpandBlock(style: SettingRowStyle, isLastRow: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // v0.216.0（C15）：中断恢复快照入口——展开时查一次，恢复动作后刷新。
    var hasPreSnapshot by remember { mutableStateOf(false) }
    // v1.3.25 合并 8：每日自动备份恢复入口——展开时查一次。
    var hasDailyBackup by remember { mutableStateOf(false) }
    var dailyBackupTime by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        hasPreSnapshot = BackupManager.hasPreRestoreSnapshot(context)
        hasDailyBackup = BackupManager.hasDailyBackup(context)
        dailyBackupTime = BackupManager.dailyBackupTime(context)
    }
    val dailyBackupTimeText = remember(dailyBackupTime) {
        if (dailyBackupTime > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(dailyBackupTime))
        } else {
            ""
        }
    }
    fun restoreDailyBackup() {
        scope.launch {
            when (val r = BackupManager.restoreDailyBackup(context)) {
                is BackupManager.RestoreResult.Ok ->
                    AppToast.show(context, "已从每日自动备份恢复", long = false)
                is BackupManager.RestoreResult.Failed ->
                    AppToast.show(context, "恢复失败：${r.reason}", long = true)
            }
        }
    }
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
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (style) {
            SettingRowStyle.Card -> {
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
                    position = if (isLastRow && !hasPreSnapshot && !hasDailyBackup) SegPos.Last else SegPos.Middle,
                    icon = Icons.Outlined.Restore,
                    title = "从文件恢复",
                    subtitle = "从 JSON 备份文件恢复订阅、当前规则、追剧、书架、音乐、直播",
                    onClick = { importLauncher.launch("application/json") },
                )
                // v1.3.25 合并 8：每日自动备份恢复入口（每天静默备一份，单文件覆盖）
                if (hasDailyBackup) {
                    SegItem(
                        position = if (isLastRow && !hasPreSnapshot) SegPos.Last else SegPos.Middle,
                        icon = Icons.Outlined.Schedule,
                        title = "恢复每日自动备份",
                        subtitle = "每天自动备份一次，最新：$dailyBackupTimeText",
                        onClick = { restoreDailyBackup() },
                    )
                }
                // v0.216.0（C15）：上次恢复中断/失败后留下的恢复前快照
                if (hasPreSnapshot) {
                        SegItem(
                            position = if (isLastRow) SegPos.Last else SegPos.Middle,
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
            SettingRowStyle.Plain -> {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = "说明",
                    subtitle = "导出 / 导入 JSON 备份文件：订阅、追剧、书架、音乐、直播（不含插件、下载、播放历史与设置）",
                    onClick = {},
                    clickable = false,
                    divider = true,
                )
                SettingsRow(
                    icon = Icons.Outlined.Backup,
                    title = "备份到文件",
                    subtitle = "导出到 /storage/emulated/0/xiaying/backup/",
                    onClick = { exportBackup() },
                    divider = true,
                )
                SettingsRow(
                    icon = Icons.Outlined.Restore,
                    title = "从文件恢复",
                    subtitle = "从 JSON 备份文件恢复订阅、当前规则、追剧、书架、音乐、直播",
                    onClick = { importLauncher.launch("application/json") },
                    divider = hasDailyBackup || hasPreSnapshot,
                )
                if (hasDailyBackup) {
                    SettingsRow(
                        icon = Icons.Outlined.Schedule,
                        title = "恢复每日自动备份",
                        subtitle = "每天自动备份一次，最新：$dailyBackupTimeText",
                        onClick = { restoreDailyBackup() },
                        divider = hasPreSnapshot,
                    )
                }
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
                        divider = false,
                    )
                }
            }
        }
    }
}
