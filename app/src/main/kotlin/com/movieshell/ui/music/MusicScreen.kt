package com.movieshell.ui.music

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.repo.LogBus
import com.movieshell.ui.components.TabTopBar
import com.movieshell.data.repo.MusicApi
import com.movieshell.player.MusicPlayer
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.launch

/**
 * v0.79.0：音乐 Tab（原实验室 tab 位置）。
 *
 * 在线搜歌（网易云源聚合接口）+ 播放；底部迷你播放条：暂停/续播/上一首/下一首。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<MusicApi.Track>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val playState by MusicPlayer.state.collectAsState()

    fun doSearch() {
        val q = query.trim()
        if (q.isBlank() || searching) return
        keyboard?.hide()
        searching = true
        searched = true
        scope.launch {
            runCatching { MusicApi.search(q) }
                .onSuccess { tracks = it }
                .onFailure {
                    LogBus.e("Music", "搜索失败：${it.message}")
                    tracks = emptyList()
                }
            searching = false
        }
    }

    Scaffold(
        topBar = {
            TabTopBar(title = "音乐")
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = "搜歌名 / 歌手",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { doSearch() }) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                when {
                    searching -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    !searched -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            kind = EmptyKind.Empty,
                            icon = Icons.Filled.MusicNote,
                            title = "搜你想听的歌",
                            hint = "输入歌名或歌手名，回车搜索",
                        )
                    }
                    tracks.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            kind = EmptyKind.Empty,
                            icon = Icons.Filled.MusicNote,
                            title = "没搜到",
                            hint = "换个关键词试试",
                        )
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(tracks, key = { _, t -> t.id }) { idx, track ->
                            TrackRow(
                                track = track,
                                isCurrent = playState.track?.id == track.id,
                                isPlaying = playState.track?.id == track.id && playState.playing,
                                onClick = {
                                    MusicPlayer.playList(context, tracks, idx)
                                },
                            )
                        }
                    }
                }
            }
            // 底部迷你播放条
            val current = playState.track
            if (current != null) {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (MusicApi.picUrl(current.picId).isNotBlank()) {
                            AsyncImage(
                                model = MusicApi.picUrl(current.picId),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = current.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = current.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (playState.loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(onClick = { MusicPlayer.prev(context) }) {
                                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首")
                            }
                            IconButton(
                                onClick = { MusicPlayer.toggle() },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape),
                            ) {
                                Icon(
                                    if (playState.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (playState.playing) "暂停" else "播放",
                                )
                            }
                            IconButton(onClick = { MusicPlayer.next(context) }) {
                                Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: MusicApi.Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (MusicApi.picUrl(track.picId).isNotBlank()) {
                AsyncImage(
                    model = MusicApi.picUrl(track.picId),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${track.artist} · ${track.album}".trim(' ', '·'),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isCurrent) {
                if (isPlaying) {
                    Icon(
                        Icons.Filled.Pause,
                        contentDescription = "正在播放",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "当前",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
