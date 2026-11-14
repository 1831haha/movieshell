package com.movieshell.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.movieshell.data.download.DownloadManager
import com.movieshell.data.source.SniffConfig
import com.movieshell.data.store.DownloadStore
import com.movieshell.data.store.PlayerSettings
import com.movieshell.data.store.SettingsLayoutStore
import com.movieshell.domain.model.PlayerEngine
import com.movieshell.player.MpvPluginLoader
import com.movieshell.ui.components.AppSwitch

/**
 * v1.2.7：可拖动的设置项注册表。
 *
 * 每一项只定义一次（图标 / 标题 / 副标题 / 点按动作 / 尾部控件 / 弹窗），
 * 住在哪由 SettingsLayoutStore 说了算；渲染时按所在页面自动换样式——
 * 设置页走 SegItem 卡片行，子页面走 SettingsRow 细行，内容与动作完全一样。
 * 新行要加入拖动：在 settingRowContent 里加一个分支，再往
 * SettingsLayoutStore.ALL_ITEMS 与 DEFAULT_LAYOUT 里登记。
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

@Composable
fun MovableSettingRow(
    id: String,
    style: SettingRowStyle,
    cardPosition: SegPos,
    divider: Boolean,
    onOpenMarketClick: () -> Unit,
) {
    val c = settingRowContent(id, onOpenMarketClick)
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
private fun settingRowContent(id: String, onOpenMarketClick: () -> Unit): SettingRowContent {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    return when (id) {
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
