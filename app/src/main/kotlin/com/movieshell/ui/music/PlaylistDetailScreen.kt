package com.movieshell.ui.music

import android.widget.Toast
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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
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
import com.movieshell.data.repo.Playlist
import com.movieshell.player.MusicPlayer
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.TabTopBar
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
    var playlist by remember { mutableStateOf<Playlist?>(null) }
    var loading by remember { mutableStateOf(true) }
    var deleteTarget by remember { mutableStateOf<MusicApi.Track?>(null) }

    fun reload() {
        scope.launch {
            playlist = MusicStore.getPlaylist(context, playlistId)
            loading = false
        }
    }
    LaunchedEffect(playlistId) { reload() }

    Scaffold(
        topBar = {
            TabTopBar(
                title = playlist?.name ?: "歌单",
                onBack = onBack,
                actions = {
                    if (!playlist?.tracks.isNullOrEmpty()) {
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
                            TrackRow(
                                track = track,
                                isCurrent = playState.track?.id == track.id,
                                isPlaying = playState.track?.id == track.id && playState.playing,
                                onDeleteClick = { deleteTarget = track },
                                onClick = { MusicPlayer.playList(context, list, idx) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("移出歌单") },
            text = { Text("把《${t.name}》从歌单里移除？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            MusicStore.removeFromPlaylist(context, playlistId, t.id)
                            deleteTarget = null
                            reload()
                            Toast.makeText(context, "已移除", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}
