package com.movieshell.ui.follow

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.download.Notify
import com.movieshell.data.repo.FollowItem
import com.movieshell.data.repo.FollowStore
import kotlinx.coroutines.launch

/**
 * 我的追剧（v0.26.0）：海报墙，按最近更新排序。
 *
 * 卡片显示"更新至 X 集" / "看到第 Y 集"；NEW 角标表示有未读更新（点进详情自动清）。
 * 长按取消追剧。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FollowScreen(
    onBack: () -> Unit,
    onVodClick: (FollowItem) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Notify.RequestNotificationPermissionOnce()
    val items by FollowStore.list.collectAsState()
    var confirmUnfollow by remember { mutableStateOf<FollowItem?>(null) }

    if (confirmUnfollow != null) {
        AlertDialog(
            onDismissRequest = { confirmUnfollow = null },
            title = { Text("取消追剧") },
            text = { Text("不再追《${confirmUnfollow!!.name}》？") },
            confirmButton = {
                TextButton(onClick = {
                    val item = confirmUnfollow!!
                    confirmUnfollow = null
                    scope.launch {
                        if (FollowStore.unfollow(context, item.id)) {
                            Toast.makeText(context, "已取消追剧", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("确定") },
            },
            dismissButton = {
                TextButton(onClick = { confirmUnfollow = null }) { Text("取消") },
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的追剧（${items.size}）") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { inner ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "还没有追剧\n在详情页点“追剧”即可跟更",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    FollowCard(
                        item = item,
                        onClick = { onVodClick(item) },
                        onLongClick = { confirmUnfollow = item },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FollowCard(
    item: FollowItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f)),
    ) {
        Column(
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF3A2B63), Color(0xFF1B2A4A)),
                        ),
                    ),
            ) {
                if (item.pic.isNotBlank()) {
                    AsyncImage(
                        model = item.pic,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.55f to Color.Transparent,
                                1f to Color(0xAA000000),
                            ),
                        ),
                )
                if (item.hasNew) {
                    Text(
                        text = "NEW",
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFE53935))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    text = "更新至 ${item.episodeCount} 集",
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                text = item.name,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.watchedEpisode >= 0) {
                Text(
                    text = "看到第 ${item.watchedEpisode + 1} 集",
                    modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
