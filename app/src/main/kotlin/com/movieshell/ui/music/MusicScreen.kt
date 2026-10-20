package com.movieshell.ui.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.CapsuleTabBar
import com.movieshell.data.repo.MusicApi
import com.movieshell.player.MusicPlayer
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.TechTextField
import com.movieshell.ui.theme.SoftType
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import kotlinx.coroutines.launch
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.movieshell.data.repo.MusicStore
import com.movieshell.data.repo.MusicPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.QueueMusic

/**
 * v0.79.0：音乐 Tab（原实验室 tab 位置）。
 *
 * 在线搜歌（网易云源聚合接口）+ 播放；v0.91.0：迷你播放条已上移到 MainActivity 全局挂载。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
    onOpenPlaylist: (String) -> Unit = {},
    onOpenMusicSource: () -> Unit = {},
) {
    val context = LocalContext.current
    // v0.103.0：activity 级 VM——切后台/切 tab 不销毁，viewModelScope 里的搜索不中断、结果保留
    val activity = LocalContext.current as ComponentActivity
    val vm: MusicSearchViewModel = viewModel(activity)
    val keyboard = LocalSoftwareKeyboardController.current
    val query by vm.query.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val searching by vm.searching.collectAsState()
    val searched by vm.searched.collectAsState()
    // v0.84.0：搜索历史
    val searchHistory by vm.searchHistory.collectAsState()

    // v0.82.0：接受显式 query（风格 chips 点后直接搜，不等 state 落定）；键盘收起在 composable 侧
    fun doSearch(q0: String = query) {
        keyboard?.hide()
        vm.doSearch(q0)
    }

    var tab by remember { mutableIntStateOf(0) } // v0.81.0：0=在线 1=本地 2=收藏；v0.84.0：3=歌单
    // v0.117.0：列表页切换音源弹窗
    var showSourcePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            // v0.95.0：去掉右上角历史按钮（用户要求）
            // v0.100.0：右上角音乐源管理入口
            TabTopBar(
                title = "音乐",
                actions = {
                    // v0.117.0：列表页切换音源（设首选源，下一首生效；有在播歌曲时立即重解析）
                    IconButton(
                        onClick = { showSourcePicker = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SwapHoriz,
                            contentDescription = "切换音源",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = onOpenMusicSource, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = Icons.Filled.LibraryMusic,
                            contentDescription = "音乐源管理",
                            modifier = Modifier.size(20.dp),
                        )
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
            CapsuleTabBar(
                titles = listOf("在线", "本地", "收藏", "歌单"),
                selected = tab,
                onSelect = { tab = it },
            )
            if (tab == 0) {
                OnlineTab(
                    query = query,
                    onQueryChange = { vm.setQuery(it) },
                    tracks = tracks,
                    searching = searching,
                    searched = searched,
                    searchHistory = searchHistory,
                    onHistorySearch = { h ->
                        vm.setQuery(h)
                        doSearch(h)
                    },
                    onHistoryRemove = { h -> vm.removeHistory(h) },
                    onHistoryClear = { vm.clearHistory() },
                    onSearch = { q -> doSearch(q) },
                    onTracksLoaded = { list -> vm.setTracks(list) },
                    onPlayAt = { idx -> MusicPlayer.playList(context, tracks, idx) },
                )
            } else if (tab == 1) {
                LocalTab()
            } else if (tab == 2) {
                FavTab()
            } else {
                PlaylistTab(onOpenPlaylist = onOpenPlaylist)
            }
        }
    }

    // v0.117.0：列表页切换音源——选定后记为首选源（下一首生效）；有在播歌曲时立即重解析
    if (showSourcePicker) {
        MusicSourcePickerDialog(
            onDismiss = { showSourcePicker = false },
            showAutoOption = true,
            onPickAuto = {
                showSourcePicker = false
                MusicPlayer.setPreferredSource(null)
                Toast.makeText(context, "已切回自动音源", Toast.LENGTH_SHORT).show()
            },
            onPick = { src ->
                showSourcePicker = false
                MusicPlayer.setPreferredSource(src.id)
                if (MusicPlayer.state.value.track != null) {
                    Toast.makeText(context, "正在用「${src.name}」重新解析", Toast.LENGTH_SHORT).show()
                    MusicPlayer.playWithSource(context, src.id)
                } else {
                    Toast.makeText(context, "默认音源：${src.name}（下一首生效）", Toast.LENGTH_SHORT).show()
                }
            },
        )
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

// v0.95：榜单专属配色
private val CHART_ACCENTS = mapOf(
    "热歌榜" to Color(0xFFF87171),
    "新歌榜" to Color(0xFF4ADE80),
    "飙升榜" to Color(0xFFC084FC),
    "原创榜" to Color(0xFF60A5FA),
)

/**
 * v0.95：榜单小卡片——横向长方形，榜单配色渐变底 + 图标 + 榜名；
 * 按下缩放到 0.95（animateFloatAsState），涟漪走 clickable 默认 indication，松手回弹。
 * 点击行为与原来一致（importPlaylist）。
 */
@Composable
private fun ChartCard(
    name: String,
    importing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(SoftDimens.CardCorner)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        label = "chartCardPress",
    )
    val accent = CHART_ACCENTS[name] ?: MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .border(SoftDimens.BorderWidth, LocalSoftExtra.current.border, shape)
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        shape = shape,
        color = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.30f), LocalSoftExtra.current.glass),
                    ),
                )
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.QueueMusic,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (importing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
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

// v0.89.0：本地音乐分组维度
private val GROUP_LABELS = listOf("全部", "歌手", "专辑", "文件夹")

/**
 * v0.89.0："歌手 - 歌名" 拆成 (歌手, 歌名)；无 " - " 分隔或任一半为空返回 null。
 * 导入解析与展示解析共用这一份，不写两遍。
 */
private fun splitArtistTitle(raw: String): Pair<String, String>? {
    if (" - " !in raw) return null
    val a = raw.substringBefore(" - ").trim()
    val n = raw.substringAfter(" - ").trim()
    return if (a.isBlank() || n.isBlank()) null else a to n
}

/** v0.89.0：展示用歌手名——artist 为空或 "本地" 的老数据，从歌名再拆一次，仍拆不出归"未知歌手"。 */
private fun displayArtist(t: MusicApi.Track): String {
    val a = t.artist.trim()
    if (a.isNotBlank() && a != "本地") return a
    return splitArtistTitle(t.name)?.first ?: "未知歌手"
}

/**
 * v0.89.0：本地音频所在文件夹。
 * 如实实现：GetContent 拿到的 content:// URI 经 MediaStore 授权，拿不到真实文件夹，
 * 不硬猜，统一归"手机导入"；file:// 取父目录名；其他含 "/" 的取倒数第二段；取不到归"手机导入"。
 */
private fun folderOf(t: MusicApi.Track): String {
    val uri = t.localUri
    if (uri.isBlank() || "/" !in uri || uri.startsWith("content://")) return "手机导入"
    return uri.substringBeforeLast("/").substringAfterLast("/").ifBlank { "手机导入" }
}

/**
 * v0.89.0：封面真实地址（pic 接口返回 {"url":...} 包一层，需异步解析一次，MusicApi 侧带内存缓存）。
 * 解析中/失败返回 ""，调用处回退 MusicNote 占位。
 */
@Composable
internal fun rememberArtworkUrl(picId: String): String {
    var url by remember(picId) { mutableStateOf("") }
    LaunchedEffect(picId) {
        url = if (picId.isBlank()) "" else withContext(Dispatchers.IO) {
            runCatching { MusicApi.picImageUrl(picId) }.getOrDefault("")
        }
    }
    return url
}

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
    val badges by MusicPlayer.badges.collectAsState() // v0.117.0：来源/音质徽标
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
                    // v0.95：四榜改 2x2 长方形小卡片（横向，宽>高），按压缩放回弹
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RECOMMENDED_PLAYLISTS.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { (name, id) ->
                                    ChartCard(
                                        name = name,
                                        importing = importingPlaylist,
                                        onClick = { importPlaylist(id) },
                                        modifier = Modifier.weight(1f),
                                    )
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
                            badge = badges[track.urlId],
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

    // v0.82.0：歌单导入弹窗；v0.84.0：换科技感 TechDialog
    if (showPlaylistDialog) {
        TechDialog(
            onDismissRequest = { showPlaylistDialog = false },
            title = "歌单导入",
            subtitle = "粘贴网易云歌单链接或 ID，一键导入整单歌曲",
            confirmText = if (importingPlaylist) "导入中…" else "导入",
            onConfirm = {
                importPlaylist(playlistInput)
                playlistInput = ""
                showPlaylistDialog = false
            },
            confirmEnabled = !importingPlaylist,
        ) {
            TechTextField(
                value = playlistInput,
                onValueChange = { playlistInput = it },
                placeholder = "歌单链接或 ID",
                modifier = Modifier.fillMaxWidth(),
            )
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

/** v0.81.0：本地音乐 tab——导入本地音频文件，列表播放/删除；v0.89.0：按歌手/专辑/文件夹分组浏览。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by MusicPlayer.state.collectAsState()
    val badges by MusicPlayer.badges.collectAsState() // v0.117.0：来源/音质徽标
    var locals by remember { mutableStateOf<List<MusicApi.Track>>(emptyList()) }
    // v0.84.0：加到歌单
    var addToPlaylistTrack by remember { mutableStateOf<MusicApi.Track?>(null) }
    // v0.89.0：分组维度（0=全部 1=歌手 2=专辑 3=文件夹），rememberSaveable 记住选择；折叠组 key 带维度前缀
    var groupMode by rememberSaveable { mutableIntStateOf(0) }
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }

    // v0.89.0：分组列表（全部模式不分组）
    val groups = remember(groupMode, locals) {
        when (groupMode) {
            1 -> locals.groupBy { displayArtist(it) }
            2 -> locals.groupBy { it.album.trim().ifBlank { "未知专辑" } }
            3 -> locals.groupBy { folderOf(it) }
            else -> emptyMap()
        }.toList().sortedBy { it.first }
    }

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
            // v0.89.0：文件名含 " - " 拆成歌手+歌名（如"周杰伦 - 晴天"），只影响新导入，老数据不动
            val parsed = splitArtistTitle(clean)
            val track = MusicApi.Track(
                id = "local_" + uri.toString().hashCode().toString(),
                name = parsed?.second ?: clean,
                artist = parsed?.first ?: "本地",
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
                Column(modifier = Modifier.fillMaxSize()) {
                    // v0.89.0：分组维度 chips
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(GROUP_LABELS.size) { i ->
                            FilterChip(
                                selected = groupMode == i,
                                onClick = { groupMode = i },
                                label = { Text(GROUP_LABELS[i]) },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (groupMode == 0) {
                        // 全部：现状平铺
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(locals, key = { _, t -> t.id }) { idx, track ->
                                TrackRow(
                                    track = track,
                                    badge = badges[track.urlId],
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
                    } else {
                        // v0.89.0：分组视图——sticky 分组头（点头展开/折叠）+ 组内连续播放
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            groups.forEach { (gname, glist) ->
                                val ckey = "${groupMode}_$gname"
                                stickyHeader(key = "hdr_$ckey") {
                                    val isCollapsed = ckey in collapsed
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                            )
                                            .clickable {
                                                collapsed = if (isCollapsed) {
                                                    collapsed - ckey
                                                } else {
                                                    collapsed + ckey
                                                }
                                            }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "$gname · ${glist.size} 首",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Icon(
                                            if (isCollapsed) Icons.Filled.ExpandMore
                                            else Icons.Filled.ExpandLess,
                                            contentDescription = if (isCollapsed) "展开" else "折叠",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (ckey !in collapsed) {
                                    itemsIndexed(glist, key = { _, t -> t.id }) { idx, track ->
                                        TrackRow(
                                            track = track,
                                            badge = badges[track.urlId],
                                            isCurrent = playState.track?.id == track.id,
                                            isPlaying = playState.track?.id == track.id &&
                                                playState.playing,
                                            onDeleteClick = {
                                                scope.launch {
                                                    MusicStore.removeLocal(context, track.id)
                                                    reload()
                                                }
                                            },
                                            onPlaylistClick = { addToPlaylistTrack = track },
                                            onClick = {
                                                MusicPlayer.playList(context, glist, idx)
                                            },
                                        )
                                    }
                                }
                            }
                        }
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
    val badges by MusicPlayer.badges.collectAsState() // v0.117.0：来源/音质徽标
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
                        badge = badges[track.urlId],
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

/** v0.117.0：歌曲来源/音质小徽标——灰底小字，跟内容色自适应（禁硬编码） */
@Composable
private fun BadgeChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** v0.84.0：改为 internal，供歌单详情页/历史页复用。 */
@Composable
internal fun TrackRow(
    track: MusicApi.Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFav: Boolean = false,
    // v0.117.0：已解析徽标（音源名 to 音质档）；未解析过的歌为 null 不显示
    badge: Pair<String, String>? = null,
    onFavClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
    onPlaylistClick: (() -> Unit)? = null, // v0.84.0：加到歌单
    onClick: () -> Unit,
) {
    // v0.89.0：封面走 picImageUrl 解析后的真实地址（接口包一层 {"url":...}）
    val artUrl = rememberArtworkUrl(track.picId)
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
            if (artUrl.isNotBlank()) {
                AsyncImage(
                    model = artUrl,
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
                // v0.117.0：真实解析来源与音质徽标（播过才有，数据来自 MusicPlayer.badges）
                if (badge != null) {
                    Spacer(Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BadgeChip(text = badge.first)
                        if (badge.second.isNotBlank()) BadgeChip(text = badge.second)
                    }
                }
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
    var playlists by remember { mutableStateOf<List<MusicPlaylist>>(emptyList()) }
    var showNew by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<MusicPlaylist?>(null) }
    var deleteTarget by remember { mutableStateOf<MusicPlaylist?>(null) }

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
        TechDialog(
            onDismissRequest = { deleteTarget = null },
            title = "删除歌单",
            confirmText = "删除",
            onConfirm = {
                scope.launch {
                    MusicStore.deletePlaylist(context, pl.id)
                    deleteTarget = null
                    reload()
                }
            },
        ) {
            Text("确定删除歌单「${pl.name}」（${pl.tracks.size} 首）？")
        }
    }
}

/** v0.84.0：新建/重命名歌单弹窗（共用）；科技感 TechDialog。 */
@Composable
private fun NewPlaylistDialog(
    title: String = "新建歌单",
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    TechDialog(
        onDismissRequest = onDismiss,
        title = title,
        onConfirm = { onConfirm(name) },
    ) {
        TechTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = "歌单名字",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** v0.84.0：加到歌单弹窗——列出歌单点选，底部可直接新建。 */
@Composable
internal fun AddToPlaylistDialog(
    track: MusicApi.Track,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<MusicPlaylist>>(emptyList()) }
    var showNew by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch { playlists = MusicStore.playlistList(context) }
    }
    LaunchedEffect(Unit) { reload() }

    fun addTo(pl: MusicPlaylist) {
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

    // v0.84.0：换科技感 TechDialog
    TechDialog(
        onDismissRequest = onDismiss,
        title = "加到歌单",
        subtitle = "《${track.name}》",
        onConfirm = null,
        content = {
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
