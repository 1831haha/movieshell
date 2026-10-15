package com.movieshell.ui.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.repo.FavoriteItem
import com.movieshell.data.repo.FavoriteStore
import com.movieshell.player.HistoryEntry
import com.movieshell.player.HistoryStore
import com.movieshell.ui.components.AppChip
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书架（v0.34.0）：按 mockup 做的影片集合页。
 *
 * 数据来源：FavoriteStore（收藏，有海报和 vodId）＋ HistoryStore（播放历史，有进度）。
 * 四个页签：
 * - 在追：有播放进度、未看完（进度 < 95%）的
 * - 想看：收藏了但没有播放记录的
 * - 已看完：进度 >= 95% 的
 * - 收藏片单：全部收藏
 *
 * 历史记录按标题匹配收藏找海报；匹配不上的用占位。
 * 点击卡片：有 vodId 的进详情。
 */
private enum class ShelfTab(val label: String) {
    Watching("在追"),
    Want("想看"),
    Finished("已看完"),
    AllFav("收藏片单"),
}

/** 书架展示项：收藏信息＋可选的历史进度 */
private data class ShelfItem(
    val title: String,
    val pic: String,
    val vodId: String,
    val sourceName: String,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasProgress: Boolean = false,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** 香槟金标题色（mockup 风格） */
private val ChampagneGold = Color(0xFFD9C39A)

@Composable
fun BookshelfScreen(
    onVodClick: (vodId: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(ShelfTab.Watching) }
    var favorites by remember { mutableStateOf<List<FavoriteItem>>(emptyList()) }
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            val favs = FavoriteStore.list(context)
            val hist = withContext(Dispatchers.IO) { HistoryStore.getHistory(context) }
            favorites = favs
            history = hist
            loaded = true
        }
    }
    LaunchedEffect(Unit) { reload() }

    // 历史按标题找收藏（取海报和 vodId）
    fun findFav(title: String): FavoriteItem? =
        favorites.firstOrNull {
            it.name.isNotBlank() && (
                it.name == title || title.contains(it.name) || it.name.contains(title)
            )
        }

    val items: List<ShelfItem> = remember(tab, favorites, history, loaded) {
        if (!loaded) return@remember emptyList()
        when (tab) {
            ShelfTab.Watching -> history
                .filter { it.kind == "vod" && it.durationMs > 0 &&
                    it.positionMs > 0 && it.positionMs < it.durationMs * 0.95 }
                .map { h ->
                    val f = findFav(h.title)
                    ShelfItem(
                        title = h.title,
                        pic = f?.pic.orEmpty(),
                        vodId = f?.vodId.orEmpty(),
                        sourceName = f?.sourceName.orEmpty(),
                        positionMs = h.positionMs,
                        durationMs = h.durationMs,
                        hasProgress = true,
                    )
                }
            ShelfTab.Finished -> history
                .filter { it.kind == "vod" && it.durationMs > 0 &&
                    it.positionMs >= it.durationMs * 0.95 }
                .map { h ->
                    val f = findFav(h.title)
                    ShelfItem(
                        title = h.title,
                        pic = f?.pic.orEmpty(),
                        vodId = f?.vodId.orEmpty(),
                        sourceName = f?.sourceName.orEmpty(),
                        positionMs = h.positionMs,
                        durationMs = h.durationMs,
                        hasProgress = true,
                    )
                }
            ShelfTab.Want -> {
                val histTitles = history.map { it.title }.toSet()
                favorites.filter { f ->
                    histTitles.none { t ->
                        f.name.isNotBlank() && (t == f.name || t.contains(f.name) || f.name.contains(t))
                    }
                }.map { f ->
                    ShelfItem(title = f.name, pic = f.pic, vodId = f.vodId, sourceName = f.sourceName)
                }
            }
            ShelfTab.AllFav -> favorites.map { f ->
                val h = history.firstOrNull { t ->
                    f.name.isNotBlank() && (t.title == f.name || t.title.contains(f.name) || f.name.contains(t.title))
                }
                ShelfItem(
                    title = f.name,
                    pic = f.pic,
                    vodId = f.vodId,
                    sourceName = f.sourceName,
                    positionMs = h?.positionMs ?: 0L,
                    durationMs = h?.durationMs ?: 0L,
                    hasProgress = h != null && h.durationMs > 0,
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 标题区
        Text(
            "书架",
            style = SoftType.Title,
            color = ChampagneGold,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp),
        )
        Text(
            "管理你的影片集合",
            style = SoftType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        // 页签
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ShelfTab.values()) { t ->
                AppChip(
                    text = t.label,
                    selected = tab == t,
                    onClick = { tab = t },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        // 内容
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…", style = SoftType.Body)
            }
        } else if (items.isEmpty()) {
            EmptyState(
                kind = EmptyKind.Empty,
                message = when (tab) {
                    ShelfTab.Watching -> "还没有在追的剧，去看点什么吧"
                    ShelfTab.Want -> "想看列表是空的"
                    ShelfTab.Finished -> "还没有看完的影片"
                    ShelfTab.AllFav -> "还没有收藏，去首页发现影片吧"
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.title + it.vodId }) { item ->
                    ShelfCard(
                        item = item,
                        onClick = if (item.vodId.isNotBlank()) {
                            { onVodClick(item.vodId) }
                        } else null,
                        onMarkWatched = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    // 在追→已看完：把进度推到 100%
                                    val h = history.firstOrNull { it.title == item.title }
                                    if (h != null) {
                                        HistoryStore.saveProgress(
                                            context, h.title, h.url,
                                            h.durationMs, h.durationMs, h.kind,
                                        )
                                    }
                                }
                                reload()
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 书架卡片：海报＋进度条＋时间/百分比＋标记看过按钮 */
@Composable
private fun ShelfCard(
    item: ShelfItem,
    onClick: (() -> Unit)?,
    onMarkWatched: () -> Unit,
) {
    val shape = RoundedCornerShape(SoftDimens.PosterCorner)
    Column(
        modifier = Modifier
            .clip(shape)
            .background(LocalSoftExtra.current.glass)
            .border(SoftDimens.BorderWidth, LocalSoftExtra.current.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (item.pic.isNotBlank()) {
                AsyncImage(
                    model = item.pic,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                item.title,
                style = SoftType.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.hasProgress) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFF4DD0E1),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        formatMs(item.positionMs),
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${(item.progress * 100).toInt()}%",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // 标记看过：只在有进度且未看完时显示
            if (item.hasProgress && item.progress < 0.95f) {
                Text(
                    "标记看过",
                    style = SoftType.Caption,
                    color = Color(0xFF4DD0E1),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(LocalSoftExtra.current.glass)
                        .border(
                            SoftDimens.BorderWidth,
                            LocalSoftExtra.current.border,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable(onClick = onMarkWatched)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}
