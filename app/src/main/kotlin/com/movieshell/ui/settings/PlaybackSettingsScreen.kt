package com.movieshell.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.movieshell.data.download.DownloadManager
import com.movieshell.data.source.SniffConfig
import com.movieshell.data.store.DownloadStore
import com.movieshell.data.store.PlayerSettings
import com.movieshell.domain.model.PlayerEngine
import com.movieshell.player.MpvPluginLoader
import com.movieshell.ui.components.AppSwitch
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.theme.V5

/**
 * v1.2.6：播放设置子页面——播放器内核 / 直播自动播放 / 去广告 / 同时下载数
 * 四个不常用的开关从设置页收进这一个入口，逻辑与原来逐行一字未动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSettingsScreen(
    onBack: () -> Unit,
    onOpenMarketClick: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    // v0.20.0：播放器内核（Media3 默认，libmpv 可选，记忆选择）
    val engine by PlayerSettings.engine.collectAsState()
    var showEngineDialog by remember { mutableStateOf(false) }
    // v1.1.31：去广告总开关（SniffConfig.adFilterEnabled，默认开；Media3 与 libmpv 共用）
    var adFilterOn by remember { mutableStateOf(SniffConfig.adFilterEnabled) }
    val liveAutoplay by PlayerSettings.liveAutoplay.collectAsState()
    var showLiveAutoplayMenu by remember { mutableStateOf(false) }
    var maxParallel by remember { mutableStateOf(DownloadStore.maxParallel(context)) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        topBar = {
            TabTopBar(
                title = "播放设置",
                onBack = onBack,
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(V5.Gutter),
        ) {
            item {
                SettingsGroup(title = "播放设置") {
                    // v0.20.0：播放器内核——Media3（默认）/ libmpv；v0.29.0：libmpv 插件化，未安装时标注
                    SettingsRow(
                        icon = Icons.Outlined.SmartDisplay,
                        title = "播放器内核",
                        subtitle = when {
                            engine == PlayerEngine.MEDIA3 -> "Media3（默认）"
                            MpvPluginLoader.isInstalled(context) -> "libmpv · 插件内核"
                            else -> "libmpv · 插件未安装"
                        },
                        onClick = { showEngineDialog = true },
                    )
                    // v0.294.5：直播自动播放——始终 / 仅 Wi‑Fi / 关闭；点整行弹紧凑下拉选
                    val labels = listOf("始终", "仅 Wi‑Fi", "关闭")
                    SettingsRow(
                        icon = Icons.Outlined.LiveTv,
                        title = "直播自动播放",
                        subtitle = when (liveAutoplay) {
                            PlayerSettings.LIVE_AUTOPLAY_WIFI -> "仅 Wi‑Fi 下进直播页自动起播"
                            PlayerSettings.LIVE_AUTOPLAY_OFF -> "关闭 · 点频道才播"
                            else -> "始终 · 进直播页自动起播"
                        },
                        onClick = { showLiveAutoplayMenu = true },
                        trailing = {
                            Box {
                                Text(
                                    labels.getOrElse(liveAutoplay) { labels[0] },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = cs.primary,
                                )
                                DropdownMenu(
                                    expanded = showLiveAutoplayMenu,
                                    onDismissRequest = { showLiveAutoplayMenu = false },
                                ) {
                                    labels.forEachIndexed { i, label ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                showLiveAutoplayMenu = false
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
                    // v1.1.31：去广告总开关（SniffConfig.adFilterEnabled，默认开；
                    // 管 m3u8 分片过滤 + 嗅探候选排除 / JS 注入 / 域名拦截；Media3 与 libmpv 共用）
                    SettingsRow(
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
                    SettingsRow(
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
                        divider = false,
                    )
                }
            }
        }
    }

    // v0.20.0：播放器内核选择（持久化，播放器内一键切换走同一偏好）
    // v0.29.0：libmpv 插件化——未安装时选 libmpv 提示去插件市场，不切换
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
}
