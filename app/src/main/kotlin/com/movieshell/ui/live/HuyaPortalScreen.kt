package com.movieshell.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.repo.HuyaApi
import com.movieshell.player.PlayerScreen
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.launch

/**
 * v0.79.0：虎牙总站（分类→直播间列表→选房间在顶部播放）。
 *
 * 房间列表走虎牙公开列表接口，播放走 ottiptv 网关（实时生成防盗链 token）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuyaPortalScreen(
    onBack: () -> Unit = {},
    onFullscreenRoom: (HuyaApi.Room) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var catIdx by remember { mutableIntStateOf(0) }
    var rooms by remember { mutableStateOf<List<HuyaApi.Room>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<HuyaApi.Room?>(null) }

    fun load(reset: Boolean) {
        if (loading) return
        if (reset) {
            page = 1
            hasMore = true
        }
        if (!hasMore && !reset) return
        loading = true
        val cat = HuyaApi.CATEGORIES[catIdx]
        scope.launch {
            runCatching {
                HuyaApi.fetchRooms(cat.gameId, if (reset) 1 else page)
            }.onSuccess { list ->
                rooms = if (reset) list else rooms + list
                hasMore = list.size >= 100
                if (!reset) page++
                if (reset && selected == null && list.isNotEmpty()) selected = list[0]
            }.onFailure {
                // 失败保持旧列表，不清空
            }
            loading = false
        }
    }

    LaunchedEffect(catIdx) { load(reset = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("虎牙直播", style = SoftType.Title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            // 顶部 16:9 播放器
            val playing = selected
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (playing != null) {
                    PlayerScreen(
                        url = HuyaApi.playUrl(playing.roomId),
                        title = "虎牙-${playing.nick}",
                        isLive = true,
                        onBack = {},
                        embedded = true,
                        onFullscreen = { onFullscreenRoom(playing) },
                    )
                } else if (loading) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    Text(
                        "选择下方直播间开始播放",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            // 分类
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(HuyaApi.CATEGORIES.size) { i ->
                    FilterChip(
                        selected = i == catIdx,
                        onClick = { catIdx = i },
                        label = { Text(HuyaApi.CATEGORIES[i].name) },
                    )
                }
            }
            // 直播间网格
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rooms, key = { it.roomId }) { room ->
                    HuyaRoomCard(
                        room = room,
                        isPlaying = selected?.roomId == room.roomId,
                        onClick = { selected = room },
                    )
                }
                if (loading && rooms.isNotEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HuyaRoomCard(
    room: HuyaApi.Room,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    AppCard(
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Column {
            Box {
                AsyncImage(
                    model = room.cover,
                    contentDescription = room.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    contentScale = ContentScale.Crop,
                )
                if (room.viewers.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "${room.viewers}人",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = room.title.ifBlank { room.nick },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = room.nick,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
