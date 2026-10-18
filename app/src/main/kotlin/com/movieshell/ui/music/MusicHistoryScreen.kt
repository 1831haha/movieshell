package com.movieshell.ui.music

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
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
import androidx.compose.ui.unit.sp
import com.movieshell.data.repo.MusicApi
import com.movieshell.data.repo.MusicHistoryStore
import com.movieshell.player.MusicPlayer
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.TabTopBar
import kotlinx.coroutines.launch

/**
 * v0.84.0：音乐历史页——搜索历史 + 播放历史。
 * 播放历史点即播；两段历史都可单条删 / 一键清空。
 */
@Composable
fun MusicHistoryScreen(
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by MusicPlayer.state.collectAsState()
    var searches by remember { mutableStateOf<List<String>>(emptyList()) }
    var plays by remember { mutableStateOf<List<MusicApi.Track>>(emptyList()) }
    // v0.84.0：加到歌单
    var addToPlaylistTrack by remember { mutableStateOf<MusicApi.Track?>(null) }

    fun reload() {
        scope.launch {
            searches = MusicHistoryStore.searchHistory(context)
            plays = MusicHistoryStore.playHistory(context)
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TabTopBar(title = "历史", onBack = onBack)
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            // ---- 搜索历史 ----
            item(key = "search_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "搜索历史",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (searches.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    MusicHistoryStore.clearSearch(context)
                                    reload()
                                }
                            },
                        ) { Text("清空", fontSize = 12.sp) }
                    }
                }
            }
            if (searches.isEmpty()) {
                item(key = "search_empty") {
                    Text(
                        text = "暂无搜索记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            } else {
                item(key = "search_chips") {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(searches, key = { it }) { h ->
                            AppCard {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(
                                        start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp,
                                    ),
                                ) {
                                    Text(
                                        text = h,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                    )
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                MusicHistoryStore.removeSearch(context, h)
                                                reload()
                                            }
                                        },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "删除",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- 播放历史 ----
            item(key = "play_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "播放历史",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (plays.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    MusicHistoryStore.clearPlay(context)
                                    reload()
                                    Toast.makeText(context, "已清空播放历史", Toast.LENGTH_SHORT)
                                        .show()
                                }
                            },
                        ) { Text("清空", fontSize = 12.sp) }
                    }
                }
            }
            if (plays.isEmpty()) {
                item(key = "play_empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            kind = EmptyKind.Empty,
                            icon = Icons.Filled.History,
                            title = "还没有播放记录",
                            hint = "播过的歌会出现在这里",
                        )
                    }
                }
            } else {
                itemsIndexed(plays, key = { _, t -> t.id }) { idx, track ->
                    TrackRow(
                        track = track,
                        isCurrent = playState.track?.id == track.id,
                        isPlaying = playState.track?.id == track.id && playState.playing,
                        onDeleteClick = {
                            scope.launch {
                                MusicHistoryStore.removePlay(context, track.id)
                                reload()
                            }
                        },
                        onPlaylistClick = { addToPlaylistTrack = track },
                        onClick = { MusicPlayer.playList(context, plays, idx) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    // v0.84.0：加到歌单
    addToPlaylistTrack?.let { t ->
        AddToPlaylistDialog(
            track = t,
            onDismiss = { addToPlaylistTrack = null },
        )
    }
}
