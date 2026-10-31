package com.movieshell.ui.market

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.ui.components.AppCard

/**
 * v0.294.19：紧凑插件行——40dp 分类型图标（色调底 + Material 图标，不再用白圆占位图）、
 * 标题与版本同行、彩色类型/状态小徽标、简介最多两行、动作按钮同行尾随。
 * 插件 / 服务 / 市场三个页签共用。
 */
enum class PluginStatus { None, Installed, Updatable, Unsupported, Running }

data class PluginTypeStyle(val icon: ImageVector, val label: String, val container: Color, val content: Color)

/** 按 type/engine/tags 选图标与配色（固定色相，深浅色主题都清晰可辨）。 */
fun pluginTypeStyle(type: String, engine: String = "", tags: List<String> = emptyList()): PluginTypeStyle {
    val t = type.lowercase()
    val radio = tags.any { it.contains("电台") || it.contains("广播") }
    return when {
        t == "live" && radio -> PluginTypeStyle(Icons.Filled.Radio, "电台", Color(0xFFE8DEF8), Color(0xFF5B3E96))
        t == "live" -> PluginTypeStyle(Icons.Filled.LiveTv, "直播", Color(0xFFFFDAD6), Color(0xFFB3261E))
        t == "source" || t == "py" || t.contains("jar") -> PluginTypeStyle(Icons.Filled.Search, if (engine.equals("py", true) || t == "py") "PY 源" else "源", Color(0xFFD3E3FD), Color(0xFF0B57D0))
        t == "server" -> PluginTypeStyle(Icons.Filled.Terminal, "服务", Color(0xFFC4EED0), Color(0xFF146C2E))
        t == "runtime" -> PluginTypeStyle(Icons.Filled.Memory, "运行时", Color(0xFFFFE08A), Color(0xFF7A5900))
        t == "env" -> PluginTypeStyle(Icons.Filled.Inventory2, "依赖", Color(0xFFFFDBC8), Color(0xFF8B4A00))
        t == "player" -> PluginTypeStyle(Icons.Filled.PlayCircle, "播放器", Color(0xFFCCE8EA), Color(0xFF00696F))
        t == "subtitle" -> PluginTypeStyle(Icons.Filled.Subtitles, "字幕", Color(0xFFE0E3E8), Color(0xFF3F4850))
        t == "setup" -> PluginTypeStyle(Icons.Filled.Build, "配置", Color(0xFFF2DAFF), Color(0xFF7B2C9C))
        else -> PluginTypeStyle(Icons.Filled.Extension, type.ifBlank { "插件" }, Color(0xFFE0E3E8), Color(0xFF3F4850))
    }
}

@Composable
fun PluginTypeIcon(style: PluginTypeStyle, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(style.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(style.icon, contentDescription = style.label, tint = style.content, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun PluginBadge(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(container)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text, fontSize = 10.sp, lineHeight = 14.sp, color = content, maxLines = 1)
    }
}

@Composable
fun PluginStatusBadge(status: PluginStatus) {
    when (status) {
        PluginStatus.Installed -> PluginBadge("已安装", Color(0xFFC4EED0), Color(0xFF146C2E))
        PluginStatus.Updatable -> PluginBadge("可更新", Color(0xFFFFDBC8), Color(0xFFA14300))
        PluginStatus.Running -> PluginBadge("运行中", Color(0xFFD3E3FD), Color(0xFF0B57D0))
        PluginStatus.Unsupported -> PluginBadge("不支持", Color(0xFFE0E3E8), Color(0xFF5F6368))
        PluginStatus.None -> {}
    }
}

/** 同行尾随的小号色调按钮。 */
@Composable
fun PluginCompactAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 56.dp),
    ) { Text(text, fontSize = 12.sp, maxLines = 1) }
}

@Composable
fun CompactPluginRow(
    name: String,
    version: String,
    desc: String,
    style: PluginTypeStyle,
    status: PluginStatus,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    extraBadge: String? = null,
    dimmed: Boolean = false,
    busy: Boolean = false,
    action: @Composable () -> Unit = {},
) {
    AppCard(modifier = modifier.fillMaxWidth().alpha(if (dimmed) 0.55f else 1f), onClick = onClick) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PluginTypeIcon(style)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name, style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (version.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "v$version", fontSize = 11.sp, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PluginBadge(style.label, style.container, style.content)
                    PluginStatusBadge(status)
                    if (extraBadge != null) PluginBadge(extraBadge, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (desc.isNotBlank()) {
                    Text(
                        desc, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (busy) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp) else action()
        }
    }
}

/** 市场条目的尾随动作：一个按钮（安装/更新/卸载/置灰），卸载在详情页也能做。 */
@Composable
fun MarketCompactAction(
    supported: Boolean,
    needApp: Boolean,
    installed: Boolean,
    hasUpdate: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    when {
        !supported -> PluginCompactAction("不支持", {}, enabled = false)
        needApp && !installed -> PluginCompactAction("需新版", {}, enabled = false)
        !installed -> PluginCompactAction("安装", onInstall)
        hasUpdate && !needApp -> PluginCompactAction("更新", onInstall)
        else -> PluginCompactAction("卸载", onUninstall)
    }
}
