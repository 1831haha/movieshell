package com.movieshell.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.movieshell.data.store.ThemeStore
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.V5Segmented
import com.movieshell.ui.theme.isDark
import com.movieshell.ui.theme.seedColorScheme

/**
 * v1.2.6：主题面板从设置页抽出——设置页「主题」只剩一个入口（进主题收藏夹），
 * 模式/配色/炫彩/海报取色在收藏夹页的「自定义配色」行里开这个面板；点选即生效，面板保持打开。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemePanelDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val dark = cs.isDark
    val themeMode by ThemeStore.mode.collectAsState()
    val themeKey by ThemeStore.themeKey.collectAsState()
    TechDialog(
        onDismissRequest = onDismiss,
        title = "主题",
        dismissText = "完成",
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("模式", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            Spacer(Modifier.height(8.dp))
            val modes = listOf(
                "跟随系统" to ThemeStore.MODE_SYSTEM,
                "浅色" to ThemeStore.MODE_LIGHT,
                "深色" to ThemeStore.MODE_DARK,
            )
            V5Segmented(
                options = modes.map { it.first },
                selected = modes.indexOfFirst { it.second == themeMode },
                onSelect = { ThemeStore.setMode(context, modes[it].second) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text("配色", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                run {
                    ThemeSwatch(
                        selected = themeKey == "p:poster",
                        label = "海报取色",
                        onClick = { ThemeStore.setSource(context, ThemeStore.SOURCE_POSTER) },
                    ) {
                        PosterSwatchArt()
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    run {
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
                ThemeStore.COLOR_OPTIONS.forEach { argb ->
                    ThemeSwatch(
                        selected = themeKey == "c:$argb",
                        label = ThemeStore.colorName(argb) ?: "主题色",
                        onClick = { ThemeStore.setColor(context, argb) },
                    ) {
                        SeedSwatchArt(seedColorScheme(argb, dark))
                    }
                }
                ThemeStore.GRADIENT_OPTIONS.forEach { preset ->
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
