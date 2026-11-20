package com.movieshell.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movieshell.data.store.SettingsLayoutStore
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.theme.V5

/**
 * v1.2.19：数据设置子页面——与播放设置页平行（用户定：播放放播放页、数据拖进数据页）。
 * 数据组 12 项长按拖到设置页“数据设置”入口上松手就搬进来；行按本页细行样式显示
 * （与设置页卡片行是同一项，样式随所在页面自动换），弹窗与备份展开块都随行走，
 * 在这页里点开一样用。拖到顶部“回设置页”条松手就搬回设置页“数据”组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSettingsScreen(
    onBack: () -> Unit,
    onOpenMarketClick: () -> Unit,
    // v1.2.19：数据行“打开对话”用（切到 AI 对话页）
    onAiChatClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val dragState = rememberSettingDragState()
    val layout by SettingsLayoutStore.layout.collectAsState()
    LaunchedEffect(Unit) { SettingsLayoutStore.init(context) }
    LaunchedEffect(layout) { dragState.containers = layout }
    val rows = layout[SettingsLayoutStore.PAGE_DATA].orEmpty()
    // 备份展开态本页自己持有（与设置页互不干扰）；开始拖就收起，
    // 子行不占落点高度，插入线不落在展开块里。
    var backupExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(dragState.isDragging) {
        if (dragState.isDragging) backupExpanded = false
    }
    val dataActions = DataRowActions(
        onAiChatClick = onAiChatClick,
        isBackupExpanded = backupExpanded,
        onBackupToggle = { backupExpanded = !backupExpanded },
    )
    val onDrop: (String, SettingDropTarget) -> Unit = { id, target ->
        when (target) {
            is SettingDropTarget.Insert -> SettingsLayoutStore.move(context, id, target.container, target.index)
            // 这页只住数据行，拖回设置页一律回数据组。
            SettingDropTarget.BackToMain -> SettingsLayoutStore.move(
                context, id, SettingsLayoutStore.MAIN_DATA, Int.MAX_VALUE,
            )
            is SettingDropTarget.EnterPage -> Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            topBar = { TabTopBar(title = "数据设置", onBack = onBack) },
            containerColor = cs.surfaceContainer,
        ) { inner ->
            // v1.4.4：回页条固定在列表上方（SettingBackStripBar），不再做 LazyColumn
            // 的第 0 项——列表滚下去后第 0 项脱离视口不组合，stripRect 记不上，
            // 行就拖不回设置页。
            Column(Modifier.fillMaxSize().padding(inner)) {
                SettingBackStripBar(dragState)
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = V5.Gutter,
                        end = V5.Gutter,
                        top = 8.dp,
                        bottom = 24.dp,
                    ),
                ) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .settingDropContainer(dragState, SettingsLayoutStore.PAGE_DATA),
                        ) {
                            SettingsGroup(title = "数据设置") {
                                if (rows.isEmpty()) {
                                    Text(
                                        "还没有行：去设置页长按一行，拖到“数据设置”入口上松手。",
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
                                            dataActions = dataActions,
                                        )
                                    }
                                    if (id == SettingsLayoutStore.ITEM_DATA_BACKUP) {
                                        DataBackupExpandBlock(
                                            expanded = backupExpanded,
                                            isLastRow = index == rows.size - 1,
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
        }
        SettingDragOverlay(dragState, SettingRowStyle.Plain, onOpenMarketClick, dataActions)
    }
}

// v1.4.4：备份展开块抽成无接收者的 @Composable——AnimatedVisibility 有
// ColumnScope/RowScope/BoxScope 重载，放在 LazyColumn 的 item 里时，外层 Column
// 的 ColumnScope 会让编译器选中 ColumnScope 重载、却够不到这个隐式接收者而
// 编译失败；无接收者的函数里只会选中无接收者的重载。
@Composable
private fun DataBackupExpandBlock(
    expanded: Boolean,
    isLastRow: Boolean,
) {
    AnimatedVisibility(visible = expanded) {
        BackupExpandBlock(
            style = SettingRowStyle.Plain,
            isLastRow = isLastRow,
        )
    }
}
