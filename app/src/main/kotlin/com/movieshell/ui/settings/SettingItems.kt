package com.movieshell.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movieshell.data.ai.AiRepairSettings
import com.movieshell.data.download.DownloadManager
import com.movieshell.data.download.FollowCheckWorker
import com.movieshell.data.download.SubRefreshWorker
import com.movieshell.data.source.SniffConfig
import com.movieshell.data.source.TmdbClient
import com.movieshell.data.source.subtitle.SubtitleLanguage
import com.movieshell.data.store.DownloadStore
import com.movieshell.data.store.FollowStore
import com.movieshell.data.store.PlayerSettings
import com.movieshell.data.store.SettingsLayoutStore
import com.movieshell.data.store.SubRefreshStore
import com.movieshell.data.sync.CloudSyncManager
import com.movieshell.domain.model.PlayerEngine
import com.movieshell.player.EnginePlugins
import com.movieshell.player.MagnetCache
import com.movieshell.player.MagnetRemote
import com.movieshell.player.MpvPluginLoader
import com.movieshell.player.TrackLanguage
import com.movieshell.player.VideoCacheSettings
import com.movieshell.ui.components.AppSwitch
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.TechDialog
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
 * 在主页面互拖；其中云同步与 AI 助手两个大弹窗仍归设置页管（动作里只
 * 传“打开弹窗”的回调），其余行自带状态、点开即用。
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
 * v1.2.16：数据行里必须回设置页拿的东西（大弹窗、跨页跳转、备份展开态、
 * 两处共用的缓存占用）。其余状态各分支自带（读 store、本地 remember），
 * 行被拖进播放组照样能用；子页面不传这份动作，数据行进不了子页面。
 */
data class DataRowActions(
    val cacheSize: String = "计算中…",
    val videoSize: String = "计算中…",
    val onAiChatClick: () -> Unit = {},
    val isBackupExpanded: Boolean = false,
    val onBackupToggle: () -> Unit = {},
    val onShowCloudDialog: () -> Unit = {},
    val onShowAiDialog: () -> Unit = {},
    val onShowClearCacheDialog: () -> Unit = {},
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
            // v0.51.0：WebDAV 云同步；配置弹窗仍在设置页（170 行左右，不搬）。
            val cloudConfigured = CloudSyncManager.isConfigured(context)
            SettingRowContent(
                icon = Icons.Outlined.CloudSync,
                title = "WebDAV 云同步",
                subtitle = if (cloudConfigured) "已配置 · " + CloudSyncManager.lastSyncText(context) else "未配置 · 坚果云/Alist/群晖",
                onClick = dataActions.onShowCloudDialog,
            )
        }
        SettingsLayoutStore.ITEM_DATA_AI -> {
            // v1.0.9：AI 助手——先在这里选 AI、填 Key；设置弹窗仍在设置页。
            val aiProvider = AiRepairSettings.provider(context)
            val aiConfigured = AiRepairSettings.apiKey(context).isNotBlank()
            SettingRowContent(
                icon = Icons.Outlined.AutoFixHigh,
                title = "AI 助手",
                subtitle = if (aiConfigured) "${aiProvider.name} · 已填 Key" else "未配置 · DeepSeek / 豆包 / 千问…",
                onClick = dataActions.onShowAiDialog,
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
            // 备份与恢复——点按展开/收起，子行（说明/备份/恢复/中断快照）由设置页
            // 跟在行后渲染，行搬到哪子行跟到哪。
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
            // 清理缓存——确认弹窗仍在设置页（清完要同时刷新视频缓存占用）。
            SettingRowContent(
                icon = Icons.Outlined.CleaningServices,
                title = "清理缓存",
                subtitle = "${dataActions.cacheSize} · 视频分片 + 图片缓存",
                onClick = dataActions.onShowClearCacheDialog,
            )
        }
        SettingsLayoutStore.ITEM_DATA_VIDEO_CAP -> {
            // v1.2.14：第 6 项——视频缓存上限可调（默认 500MB；点按循环切换档位，改动下次播放生效，不用重启）。
            var videoCapMb by remember { mutableStateOf(VideoCacheSettings.capMb(context)) }
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
                subtitle = "已用 ${dataActions.videoSize} · 上限 ${VideoCacheSettings.formatCapMb(videoCapMb)} · 改动下次播放生效",
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
