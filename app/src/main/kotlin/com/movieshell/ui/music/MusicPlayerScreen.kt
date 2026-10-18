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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.MusicApi
import com.movieshell.data.repo.MusicStore
import com.movieshell.player.MusicPlayer
import com.movieshell.ui.components.TabTopBar
import kotlinx.coroutines.launch

/**
 * v0.83.0：全屏播放页——大封面 / 歌词滚动跟随 / 进度条拖动 / 播放模式。
 */
@Composable
fun MusicPlayerScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by MusicPlayer.state.collectAsState()
    val track = playState.track

    var lyrics by remember { mutableStateOf<List<MusicApi.LyricLine>>(emptyList()) }
    var lyricLoading by remember { mutableStateOf(false) }
    var isFav by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf(0L) }

    LaunchedEffect(track?.id) {
        val t = track ?: return@LaunchedEffect
        isFav = MusicStore.isFav(context, t.id)
        lyricLoading = true
        lyrics = runCatching { MusicApi.lyric(t) }
            .onFailure { LogBus.w("MusicPlayer", "歌词拉取失败：${it.message}") }
            .getOrDefault(emptyList())
        lyricLoading = false
    }

    // 当前歌词行
    val pos = playState.positionMs
    val curIdx = remember(pos, lyrics) {
        var idx = -1
        for (i in lyrics.indices) {
            if (lyrics[i].timeMs <= pos) idx = i else break
        }
        idx
    }
    val listState = rememberLazyListState()
    LaunchedEffect(curIdx) {
        if (curIdx >= 0) {
            runCatching {
                listState.animateScrollToItem((curIdx - 2).coerceAtLeast(0))
            }
        }
    }

    Scaffold(
        topBar = {
            TabTopBar(
                title = "正在播放",
                onBack = onBack,
            )
        },
    ) { inner ->
        if (track == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center,
            ) { Text("还没有播放歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            // 封面
            if (MusicApi.picUrl(track.picId).isNotBlank()) {
                AsyncImage(
                    model = MusicApi.picUrl(track.picId),
                    contentDescription = null,
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = track.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            // 歌词区
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    lyricLoading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                    lyrics.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "暂无歌词",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(lyrics) { i, line ->
                            Text(
                                text = line.text,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = if (i == curIdx) {
                                    MaterialTheme.typography.bodyLarge
                                } else {
                                    MaterialTheme.typography.bodyMedium
                                },
                                color = if (i == curIdx) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 进度条
            val shown = if (dragging) dragPos else pos
            val dur = playState.durationMs.coerceAtLeast(1L)
            Slider(
                value = shown.toFloat().coerceIn(0f, dur.toFloat()),
                onValueChange = {
                    dragging = true
                    dragPos = it.toLong()
                },
                onValueChangeFinished = {
                    dragging = false
                    MusicPlayer.seekTo(dragPos)
                },
                valueRange = 0f..dur.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = fmtMs(shown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = fmtMs(playState.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            // 控制行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = { MusicPlayer.cycleRepeatMode() }) {
                    Icon(
                        when (playState.repeatMode) {
                            MusicPlayer.RepeatMode.ONE -> Icons.Filled.RepeatOne
                            MusicPlayer.RepeatMode.SHUFFLE -> Icons.Filled.Shuffle
                            MusicPlayer.RepeatMode.ALL -> Icons.Filled.Repeat
                        },
                        contentDescription = "播放模式",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { MusicPlayer.prev(context) }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(36.dp))
                }
                IconButton(
                    onClick = { MusicPlayer.toggle() },
                    modifier = Modifier.size(64.dp).clip(CircleShape),
                ) {
                    if (playState.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    } else {
                        Icon(
                            if (playState.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playState.playing) "暂停" else "播放",
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = { MusicPlayer.next(context) }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一首", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = {
                    scope.launch {
                        isFav = MusicStore.toggleFav(context, track)
                    }
                }) {
                    Icon(
                        if (isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (isFav) Color(0xFFFF6B81)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun fmtMs(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
