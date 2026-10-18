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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.sp
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
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import com.movieshell.data.repo.MusicHistoryStore
import com.movieshell.data.repo.MusicStore
import com.movieshell.data.repo.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.QueueMusic

/**
 * v0.79.0：音乐 Tab（原实验室 tab 位置）。
 *
 * 在线搜歌（网易云源聚合接口）+ 播放；底部迷你播放条：暂停/续播/上一首/下一首。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
    onOpenPlayer: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenPlaylist: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<MusicApi.Track>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val playState by MusicPlayer.state.collectAsState()
    // v0.84.0：搜索历史
    var searchHistory by remember { mutableStateOf<List<String>>(emptyList()) }

    fun reloadHistory() {
        scope.launch { searchHistory = MusicHistoryStore.searchHistory(context) }
    }
    LaunchedEffect(Unit) { reloadHistory() }

    // v0.82.0：接受显式 query（风格 chips 点后直接搜，不等 state 落定）
    fun doSearch(q0: String = query) {
        val q = q0.trim()
        if (q.isBlank() || searching) return
        keyboard?.hide()
        searching = true
        searched = true
        scope.launch {
            // v0.84.0：记搜索历史
            MusicHistoryStore.addSearch(context, q)
            searchHistory = MusicHistoryStore.searchHistory(context)
            runCatching { MusicApi.search(q) }
                .onSuccess { tracks = it }
                .onFailure {
                    LogBus.e("Music", "搜索失败：${it.message}")
                    tracks = emptyList()
                }
            searching = false
        }
    }

    var tab by remember { mutableIntStateOf(0) } // v0.81.0：0=在线 1=本地 2=收藏；v0.84.0：3=歌单

    Scaffold(
        topBar = {
            // v0.84.0：历史入口
            TabTopBar(
                title = "音乐",
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = "历史")
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
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("在线") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("本地") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("收藏") })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("歌单") })
            }
            if (tab == 0) {
                OnlineTab(
                    query = query,
                    onQueryChange = { query = it },
                    tracks = tracks,
                    searching = searching,
                    searched = searched,
                    searchHistory = searchHistory,
                    onHistorySearch = { h ->
                        query = h
                        doSearch(h)
                    },
                    onHistoryRemove = { h ->
                        scope.launch {
                            MusicHistoryStore.removeSearch(context, h)
                            reloadHistory()
                        }
                    },
                    onHistoryClear = {
                        scope.launch {
                            MusicHistoryStore.clearSearch(context)
                            reloadHistory()
                        }
                    },
                    onSearch = { q -> doSearch(q) },
                    onTracksLoaded = { list ->
                        tracks = list
                        searched = true
                    },
                    onPlayAt = { idx -> MusicPlayer.playList(context, tracks, idx) },
                )
            } else if (tab == 1) {
                LocalTab()
            } else if (tab == 2) {
                FavTab()
            } else {
                PlaylistTab(onOpenPlaylist = onOpenPlaylist)
            }
            // 底部迷你播放条（三个 tab 共用；v0.83.0：点击进全屏播放页）
            MiniPlayer(
                playState = playState,
                onOpenPlayer = { onOpenPlayer() },
            )
        }
    }
}

// v0.82.0：风格快捷
private val GENRE_CHIPS = listOf("古风", "流行", "民谣", "摇滚", "纯音乐", "DJ")

// v0.82.0：推荐歌单（网易云官方榜单，一点即导入）
private val RECOMMENDED_PLAYLISTS = listOf(
    "热歌榜" to "3778678",
    "新歌榜" to "3779629",
    "飙升榜" to "19723756",
    "原创榜" to "2884035",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnlineTab(
    query: String,
    onQueryChange: (String) -> Unit,
    tracks: List<MusicApi.Track>,
    searching: Boolean,
    searched: Boolean,
    searchHistory: List<String>,
    onHistorySearch: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    onSearch: (String) -> Unit,
    onTracksLoaded: (List<MusicApi.Track>) -> Unit,
    onPlayAt: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val playState by MusicPlayer.state.collectAsState()
    var favIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // v0.82.0：歌单导入
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var playlistInput by remember { mutableStateOf("") }
    var playlistName by remember { mutableStateOf<String?>(null) }
    var importingPlaylist by remember { mutableStateOf(false) }
    // v0.84.0：加到歌单
    var addToPlaylistTrack by remember { mutableStateOf<MusicApi.Track?>(null) }

    // v0.82.0：歌单导入共用逻辑
    fun importPlaylist(input: String) {
        val inp = input.trim()
        if (inp.isBlank() || importingPlaylist) return
        importingPlaylist = true
        scope.launch {
            runCatching { MusicApi.playlist(inp) }
                .onSuccess { (name, list) ->
                    playlistName = name
                    onTracksLoaded(list)
                    Toast.makeText(
                        context, "已导入歌单「$name」（${list.size} 首）",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .onFailure {
                    Toast.makeText(
                        context, "导入失败：${it.message}", Toast.LENGTH_SHORT,
                    ).show()
                }
            importingPlaylist = false
        }
    }

    LaunchedEffect(Unit) {
        favIds = withContext(Dispatchers.IO) {
            MusicStore.favList(context).map { it.id }.toSet()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = "搜歌名 / 歌手",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    onSearch(query)
                }),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                keyboard?.hide()
                onSearch(query)
            }) {
                Icon(Icons.Filled.Search, contentDescription = "搜索")
            }
            // v0.82.0：歌单导入
            IconButton(onClick = { showPlaylistDialog = true }) {
                Icon(Icons.Filled.PlaylistAdd, contentDescription = "歌单导入")
            }
        }
        // v0.82.0：风格快捷搜索
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(GENRE_CHIPS) { genre ->
                FilterChip(
                    selected = false,
                    onClick = {
                        onQueryChange(genre)
                        keyboard?.hide()
                        onSearch(genre)
                    },
                    label = { Text(genre) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // v0.84.0：搜索历史（未输入时展示，点即搜）
        if (query.isBlank() && !searched && searchHistory.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "搜索历史",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onHistoryClear) {
                    Text("清空", fontSize = 12.sp)
                }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(searchHistory, key = { it }) { h ->
                    AppCard(
                        modifier = Modifier.clickable { onHistorySearch(h) },
                    ) {
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
                                onClick = { onHistoryRemove(h) },
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
            Spacer(Modifier.height(4.dp))
        }
        playlistName?.let { pn ->
            Text(
                text = "歌单：$pn",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            when {
                searching -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                !searched -> Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "推荐歌单",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(RECOMMENDED_PLAYLISTS) { (name, id) ->
                            AppCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .clickable { importPlaylist(id) },
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.QueueMusic,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (importingPlaylist) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.ChevronRight,
                                            contentDescription = "导入",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
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
                        val isFav = track.id in favIds
                        TrackRow(
                            track = track,
                            isCurrent = playState.track?.id == track.id,
                            isPlaying = playState.track?.id == track.id && playState.playing,
                            isFav = isFav,
                            onFavClick = {
                                scope.launch {
                                    val nowFav = MusicStore.toggleFav(context, track)
                                    favIds = if (nowFav) favIds + track.id else favIds - track.id
                                }
                            },
                            onPlaylistClick = { addToPlaylistTrack = track },
                            onClick = { onPlayAt(idx) },
                        )
                    }
                }
            }
        }
    }

    // v0.82.0：歌单导入弹窗
    if (showPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showPlaylistDialog = false },
            title = { Text("歌单导入") },
            text = {
                Column {
                    Text(
                        "粘贴网易云歌单链接或 ID，一键导入整单歌曲",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = playlistInput,
                        onValueChange = { playlistInput = it },
                        placeholder = { Text("歌单链接或 ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        importPlaylist(playlistInput)
                        playlistInput = ""
                        showPlaylistDialog = false
                    },
                    enabled = !importingPlaylist,
                ) { Text(if (importingPlaylist) "导入中…" else "导入") }
            },
            dismissButton = {
                TextButton(onClick = { showPlaylistDialog = false }) { Text("取消") }
            },
        )
    }

    // v0.84.0：加到歌单
    addToPlaylistTrack?.let { t ->
        AddToPlaylistDialog(
            track = t,
            onDismiss = { addToPlaylistTrack = null },
        )
    }
}

/** v0.81.0：本地音乐 tab——导入本地音频文件，列表播放/删除。 */
@Composable
private fun LocalTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by MusicPlayer.state.collectAsState()
    var locals by remember { mutableStateOf<List<MusicApi.Track>>(emptyList()) }
    // v0.84.0：加到歌单
    var addToPlaylistTrack by remember { mutableStateOf<MusicApi.Track?>(null) }

    fun reload() {
        scope.launch {
            locals = MusicStore.localList(context)
        }
    }
    LaunchedEffect(Unit) { reload() }

    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                    }
                }.getOrNull()
            } ?: "本地音乐"
            val clean = name.substringBeforeLast(".")
            val track = MusicApi.Track(
                id = "local_" + uri.toString().hashCode().toString(),
                name = clean,
                artist = "本地",
                album = "",
                picId = "",
                urlId = "",
                localUri = uri.toString(),
            )
            MusicStore.addLocal(context, track)
            reload()
            Toast.makeText(context, "已导入《$clean》", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (locals.isEmpty()) "导入手机里的音频文件" else "共 ${locals.size} 首",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { pickAudio.launch("audio/*") }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("导入音乐")
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            if (locals.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.MusicNote,
                        title = "还没有本地音乐",
                        hint = "点右上角「导入音乐」从手机里选音频文件",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(locals, key = { _, t -> t.id }) { idx, track ->
                        TrackRow(
                            track = track,
                            isCurrent = playState.track?.id == track.id,
                            isPlaying = playState.track?.id == track.id && playState.playing,
                            onDeleteClick = {
                                scope.launch {
                                    MusicStore.removeLocal(context, track.id)
                                    reload()
                                }
                            },
                            onPlaylistClick = { addToPlaylistTrack = track },
                            onClick = { MusicPlayer.playList(context, locals, idx) },
                        )
                    }
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

/** v0.81.0：收藏 tab。 */
@Composable
private fun FavTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by MusicPlayer.state.collectAsState()
    var favs by remember { mutableStateOf<List<MusicApi.Track>>(emptyList()) }
    // v0.84.0：加到歌单
    var addToPlaylistTrack by remember { mutableStateOf<MusicApi.Track?>(null) }

    fun reload() {
        scope.launch { favs = MusicStore.favList(context) }
    }
    LaunchedEffect(Unit) { reload() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (favs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    kind = EmptyKind.Empty,
                    icon = Icons.Filled.Favorite,
                    title = "还没有收藏",
                    hint = "在在线搜索结果上点 ♥ 收藏歌曲",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(favs, key = { _, t -> t.id }) { idx, track ->
                    TrackRow(
                        track = track,
                        isCurrent = playState.track?.id == track.id,
                        isPlaying = playState.track?.id == track.id && playState.playing,
                        isFav = true,
                        onFavClick = {
                            scope.launch {
                                MusicStore.toggleFav(context, track)
                                reload()
                            }
                        },
                        onPlaylistClick = { addToPlaylistTrack = track },
                        onClick = { MusicPlayer.playList(context, favs, idx) },
                    )
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

/** v0.81.0：底部迷你播放条（三个 tab 共用）。 */
@Composable
private fun MiniPlayer(
    playState: MusicPlayer.State,
    onOpenPlayer: () -> Unit = {},
) {
    val context = LocalContext.current
    val current = playState.track ?: return
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onOpenPlayer),
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

/** v0.84.0：改为 internal，供歌单详情页/历史页复用。 */
@Composable
internal fun TrackRow(
    track: MusicApi.Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFav: Boolean = false,
    onFavClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
    onPlaylistClick: (() -> Unit)? = null, // v0.84.0：加到歌单
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
            onFavClick?.let {
                IconButton(onClick = it) {
                    Icon(
                        if (isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (isFav) "取消收藏" else "收藏",
                        tint = if (isFav) Color(0xFFFF6B81)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            onDeleteClick?.let {
                IconButton(onClick = it) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // v0.84.0：加到歌单
            onPlaylistClick?.let {
                IconButton(onClick = it) {
                    Icon(
                        Icons.Filled.PlaylistAdd,
                        contentDescription = "加到歌单",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

/** v0.84.0：歌单 tab——自建歌单列表，新建/重命名/删除，点进详情。 */
@Composable
private fun PlaylistTab(
    onOpenPlaylist: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var showNew by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }

    fun reload() {
        scope.launch { playlists = MusicStore.playlistList(context) }
    }
    LaunchedEffect(Unit) { reload() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (playlists.isEmpty()) "把喜欢的歌攒成自己的歌单" else "共 ${playlists.size} 个歌单",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showNew = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("新建歌单")
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            if (playlists.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.QueueMusic,
                        title = "还没有歌单",
                        hint = "点「新建歌单」，或在歌曲上点 ⁺ 加进来",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(playlists, key = { it.id }) { pl ->
                        AppCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .clickable { onOpenPlaylist(pl.id) },
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.QueueMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = pl.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${pl.tracks.size} 首",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { renameTarget = pl }) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = "重命名",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { deleteTarget = pl }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNew) {
        NewPlaylistDialog(
            onDismiss = { showNew = false },
            onConfirm = { name ->
                scope.launch {
                    val pl = MusicStore.createPlaylist(context, name)
                    showNew = false
                    if (pl != null) {
                        reload()
                        Toast.makeText(context, "已创建歌单「$name」", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "歌单名为空或已存在", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    renameTarget?.let { pl ->
        NewPlaylistDialog(
            title = "重命名歌单",
            initial = pl.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                scope.launch {
                    val ok = MusicStore.renamePlaylist(context, pl.id, name)
                    renameTarget = null
                    if (ok) {
                        reload()
                    } else {
                        Toast.makeText(context, "歌单名为空或已存在", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    deleteTarget?.let { pl ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除歌单") },
            text = { Text("确定删除歌单「${pl.name}」（${pl.tracks.size} 首）？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            MusicStore.deletePlaylist(context, pl.id)
                            deleteTarget = null
                            reload()
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

/** v0.84.0：新建/重命名歌单弹窗（共用）。 */
@Composable
private fun NewPlaylistDialog(
    title: String = "新建歌单",
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("歌单名字") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** v0.84.0：加到歌单弹窗——列出歌单点选，底部可直接新建。 */
@Composable
internal fun AddToPlaylistDialog(
    track: MusicApi.Track,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var showNew by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch { playlists = MusicStore.playlistList(context) }
    }
    LaunchedEffect(Unit) { reload() }

    fun addTo(pl: Playlist) {
        scope.launch {
            val ok = MusicStore.addToPlaylist(context, pl.id, track)
            Toast.makeText(
                context,
                if (ok) "已加入「${pl.name}」" else "这首已经在「${pl.name}」里了",
                Toast.LENGTH_SHORT,
            ).show()
            if (ok) onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加到歌单") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "《${track.name}》",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.height(240.dp)) {
                    if (playlists.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "还没有歌单，先新建一个",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                            )
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(playlists, key = { it.id }) { pl ->
                                AppCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { addTo(pl) },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.QueueMusic,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = pl.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${pl.tracks.size} 首",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showNew = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("新建歌单")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    if (showNew) {
        NewPlaylistDialog(
            onDismiss = { showNew = false },
            onConfirm = { name ->
                scope.launch {
                    val pl = MusicStore.createPlaylist(context, name)
                    showNew = false
                    if (pl != null) {
                        reload()
                        addTo(pl)
                    } else {
                        Toast.makeText(context, "歌单名为空或已存在", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }
}
