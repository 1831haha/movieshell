package com.movieshell.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movieshell.data.store.SettingsLayoutStore
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.theme.V5

/**
 * v1.2.6：播放设置子页面——播放器内核 / 直播自动播放 / 去广告 / 同时下载数。
 * v1.2.7：活布局——这页里的行按布局表渲染，长按可拖动：组内调顺序、
 * 拖到顶部“回设置页”条松手就搬回设置页“播放 · 解析”组；行按本页细行
 * 样式显示（与设置页卡片行是同一项，样式随所在页面自动换）。
 * v1.2.19：这页只住播放行（用户定：播放放播放页、数据拖进数据页）；
 * 数据行进数据设置页，不再进这页，备份展开块与 AI 对话回调随之撤走。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSettingsScreen(
    onBack: () -> Unit,
    onOpenMarketClick: () -> Unit,
    // v1.2.14：TMDB 行点进独立页
    onTmdbSettingsClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val dragState = rememberSettingDragState()
    val layout by SettingsLayoutStore.layout.collectAsState()
    LaunchedEffect(Unit) { SettingsLayoutStore.init(context) }
    LaunchedEffect(layout) { dragState.containers = layout }
    val rows = layout[SettingsLayoutStore.PAGE_PLAYBACK].orEmpty()
    val onDrop: (String, SettingDropTarget) -> Unit = { id, target ->
        when (target) {
            is SettingDropTarget.Insert -> SettingsLayoutStore.move(context, id, target.container, target.index)
            // 这页只住播放行，拖回设置页一律回“播放 · 解析”组。
            SettingDropTarget.BackToMain -> SettingsLayoutStore.move(
                context, id, SettingsLayoutStore.MAIN_PLAYBACK, Int.MAX_VALUE,
            )
            is SettingDropTarget.EnterPage -> Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            topBar = { TabTopBar(title = "播放设置", onBack = onBack) },
            containerColor = cs.surfaceContainer,
        ) { inner ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentPadding = PaddingValues(
                    start = V5.Gutter,
                    end = V5.Gutter,
                    top = 8.dp,
                    bottom = 24.dp,
                ),
            ) {
                // 拖动时才出现：拖到这里松手，行搬回设置页“播放 · 解析”组。
                if (dragState.isDragging) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .settingBackStrip(dragState)
                                .background(cs.primaryContainer, RoundedCornerShape(V5.CornerMd))
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "拖到这里，移回设置页",
                                style = MaterialTheme.typography.labelLarge,
                                color = cs.onPrimaryContainer,
                            )
                        }
                    }
                }
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .settingDropContainer(dragState, SettingsLayoutStore.PAGE_PLAYBACK),
                    ) {
                        SettingsGroup(title = "播放设置") {
                            if (rows.isEmpty()) {
                                Text(
                                    "还没有行：去设置页长按一行，拖到“播放设置”入口上松手。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            rows.forEachIndexed { index, id ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .settingDraggable(dragState, id, onDrop)
                                        .then(if (dragState.draggedId == id) Modifier.alpha(0.3f) else Modifier),
                                ) {
                                    MovableSettingRow(
                                        id = id,
                                        style = SettingRowStyle.Plain,
                                        cardPosition = SegPos.Single,
                                        divider = index < rows.size - 1,
                                        onOpenMarketClick = onOpenMarketClick,
                                        onTmdbSettingsClick = onTmdbSettingsClick,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    SettingsGroup(title = "布局") {
                        SettingsRow(
                            icon = Icons.Outlined.Restore,
                            title = "恢复默认布局",
                            subtitle = "所有可移行回默认分组",
                            onClick = { SettingsLayoutStore.reset(context) },
                            divider = false,
                        )
                    }
                }
            }
        }
        SettingDragOverlay(dragState, SettingRowStyle.Plain, onOpenMarketClick)
    }
}
