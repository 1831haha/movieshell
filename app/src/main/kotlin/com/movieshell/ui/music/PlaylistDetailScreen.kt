package com.movieshell.ui.music

import android.widget.Toast
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.MusicApi
import com.movieshell.data.repo.MusicStore
import com.movieshell.data.repo.MusicPlaylist
import com.movieshell.player.MusicPlayer
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechDialog
import kotlinx.coroutines.launch

/**
 * v0.84.0：歌单详情页——播全部 / 逐首移除 / 从歌曲页加进来。
 */
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by MusicPlayer.state.collectAsState()
    var playlist by remember { mutableStateOf<MusicPlaylist?>(null) }
    var loading by remember { mutableStateOf(true) }
    var deleteTarget by remember { mutableStateOf<MusicApi.Track?>(null) }
    // v0.89.0：手动排序 / 多选模式
    var sortingMode by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showDeleteSelected by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            playlist = MusicStore.getPlaylist(context, playlistId)
            loading = false
        }
    }
    LaunchedEffect(playlistId) { reload() }

    fun move(from: Int, to: Int) {
        scope.launch {
            MusicStore.moveTrack(context, playlistId, from, to)
            reload()
        }
    }

    Scaffold(
        topBar = {
            TabTopBar(
                title = (playlist?.name ?: "歌单") + if (sortingMode) "·排序中" else "",
                onBack = onBack,
                actions = {
                    when {
                        sortingMode -> TextButton(onClick = { sortingMode = false }) {
                            Text("完成")
                        }
                        selecting -> TextButton(
                            onClick = { selecting = false; selectedIds = emptySet() },
                        ) { Text("取消") }
                        !playlist?.tracks.isNullOrEmpty() -> {
                            IconButton(onClick = { showSortDialog = true }) {
                                Icon(Icons.Filled.Sort, contentDescription = "排序")
                            }
                            IconButton(
                                onClick = { selecting = true; selectedIds = emptySet() },
                            ) {
                                Icon(Icons.Filled.Checklist, contentDescription = "多选")
                            }
                            IconButton(
                                onClick = {
                                    playlist?.tracks?.let {
                                        MusicPlayer.playList(context, it, 0)
                                    }
                                },
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "播放全部")
                            }
                        }
                    }
                },
            )
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                playlist == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "歌单不存在",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                playlist!!.tracks.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.QueueMusic,
                        title = "歌单是空的",
                        hint = "在歌曲上点 ⁺ 加进来",
                    )
                }
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "共 ${playlist!!.tracks.size} 首",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                playlist?.tracks?.let {
                                    MusicPlayer.playList(context, it, 0)
                                }
                            },
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("播放全部")
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val list = playlist!!.tracks
                        itemsIndexed(list, key = { _, t -> t.id }) { idx, track ->
                            val isCur = playState.track?.id == track.id
                            when {
                                // v0.89.0：多选模式——行首 Checkbox，行点击=切换选中（不触发播放）
                                selecting -> {
                                    val checked = track.id in selectedIds
                                    val toggle = {
                                        selectedIds = if (checked) {
                                            selectedIds - track.id
                                        } else {
                                            selectedIds + track.id
                                        }
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { toggle() },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { toggle() },
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                        // TrackRow 签名不动，外包一层 Box 占 weight
                                        Box(modifier = Modifier.weight(1f)) {
                                            TrackRow(
                                                track = track,
                                                isCurrent = isCur,
                                                isPlaying = isCur && playState.playing,
                                                onClick = { toggle() },
                                            )
                                        }
                                    }
                                }
                                // v0.89.0：手动排序——右侧上/下箭头，点即 moveTrack；行点击不播放防误触
                                sortingMode -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            TrackRow(
                                                track = track,
                                                isCurrent = isCur,
                                                isPlaying = isCur && playState.playing,
                                                onClick = {},
                                            )
                                        }
                                        Column(modifier = Modifier.padding(end = 4.dp)) {
                                            IconButton(
                                                onClick = { move(idx, idx - 1) },
                                                enabled = idx > 0,
                                                modifier = Modifier.size(36.dp),
                                            ) {
                                                Icon(
                                                    Icons.Filled.ArrowUpward,
                                                    contentDescription = "上移",
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                            IconButton(
                                                onClick = { move(idx, idx + 1) },
                                                enabled = idx < list.size - 1,
                                                modifier = Modifier.size(36.dp),
                                            ) {
                                                Icon(
                                                    Icons.Filled.ArrowDownward,
                                                    contentDescription = "下移",
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> TrackRow(
                                    track = track,
                                    isCurrent = isCur,
                                    isPlaying = isCur && playState.playing,
                                    onDeleteClick = { deleteTarget = track },
                                    onClick = { MusicPlayer.playList(context, list, idx) },
                                )
                            }
                        }
                    }
                    // v0.89.0：多选底栏——全选/取消 + 删除所选（n）
                    if (selecting) {
                        val list = playlist!!.tracks
                        val allSelected = list.isNotEmpty() && selectedIds.size == list.size
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    selectedIds = if (allSelected) {
                                        emptySet()
                                    } else {
                                        list.map { it.id }.toSet()
                                    }
                                },
                            ) { Text(if (allSelected) "取消全选" else "全选") }
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = { showDeleteSelected = true },
                                enabled = selectedIds.isNotEmpty(),
                            ) { Text("删除所选（${selectedIds.size}）") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    deleteTarget?.let { t ->
        TechDialog(
            onDismissRequest = { deleteTarget = null },
            title = "移出歌单",
            confirmText = "移除",
            onConfirm = {
                scope.launch {
                    MusicStore.removeFromPlaylist(context, playlistId, t.id)
                    deleteTarget = null
                    reload()
                    Toast.makeText(context, "已移除", Toast.LENGTH_SHORT).show()
                }
            },
        ) {
            Text("把《${t.name}》从歌单里移除？")
        }
    }

    // v0.89.0：排序方式弹窗（TechDialog；取消走自带取消按钮）
    if (showSortDialog) {
        TechDialog(
            onDismissRequest = { showSortDialog = false },
            title = "排序",
            subtitle = "选择歌单内歌曲的排序方式",
            onConfirm = null,
        ) {
            TextButton(
                onClick = { showSortDialog = false; sortingMode = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("手动排序") }
            TextButton(
                onClick = {
                    showSortDialog = false
                    scope.launch {
                        MusicStore.sortByName(context, playlistId)
                        reload()
                        Toast.makeText(context, "已按名称排序", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("按名称排序") }
        }
    }

    // v0.89.0：多选删除二次确认
    if (showDeleteSelected) {
        val n = selectedIds.size
        TechDialog(
            onDismissRequest = { showDeleteSelected = false },
            title = "删除所选",
            subtitle = "确定从歌单中移除选中的 $n 首？",
            confirmText = "删除",
            onConfirm = {
                scope.launch {
                    MusicStore.removeTracks(context, playlistId, selectedIds)
                    showDeleteSelected = false
                    selecting = false
                    selectedIds = emptySet()
                    reload()
                    Toast.makeText(context, "已删除 $n 首", Toast.LENGTH_SHORT).show()
                }
            },
        ) { }
    }
}
