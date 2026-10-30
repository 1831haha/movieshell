package com.movieshell.ui.music

import com.movieshell.ui.theme.V4
import com.movieshell.ui.theme.V4Type
import com.movieshell.ui.theme.artBrush
import com.movieshell.ui.components.V4TopTabs
import com.movieshell.ui.components.V4SectionHeader
import com.movieshell.ui.components.dockClearance
import com.movieshell.ui.components.v4BottomDivider
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.animateFloatAsState
import com.movieshell.ui.components.AppToast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.movieshell.ui.components.StatusRing
import com.movieshell.ui.components.StatusRingState
import com.movieshell.ui.components.CapsuleTabBar
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.MusicApi
import com.movieshell.data.repo.MusicSettings
import com.movieshell.player.MusicDownloader
import com.movieshell.player.MusicPlayer
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.TechTextField
import kotlinx.coroutines.launch
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.movieshell.data.repo.MusicSource
import com.movieshell.data.repo.MusicSourceStore
import com.movieshell.data.repo.MusicStore
import com.movieshell.data.repo.MusicHistoryStore
import com.movieshell.data.repo.MusicPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlaylistAdd
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
    val searchError by vm.searchError.collectAsState()
    // v0.84.0：搜索历史
    val searchHistory by vm.searchHistory.collectAsState()

    // v0.82.0：接受显式 query（历史记录点后直接搜，不等 state 落定）；键盘收起在 composable 侧
    fun doSearch(q0: String = query) {
        keyboard?.hide()
        vm.doSearch(q0)
    }

    var tab by remember { mutableIntStateOf(0) } // v0.81.0：0=在线 1=本地 2=收藏；v0.84.0：3=歌单
    // v0.117.0：列表页切换音源弹窗
    var showSourcePicker by remember { mutableStateOf(false) }
    // v0.128.0：歌单导入入口挪到标题行，状态与导入逻辑随之上提到本屏
    //（原在 OnlineTab 搜索行内）；弹窗仍在本屏底部渲染，逻辑零改动。
    val scope = rememberCoroutineScope()
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var playlistInput by remember { mutableStateOf("") }
    var playlistName by remember { mutableStateOf<String?>(null) }
    var importingPlaylist by remember { mutableStateOf(false) }
    // v0.180.0：音源条显示当前音源名（首选源名，无首选=自动），
    // 切换音源弹窗关上后重新读一次，名字跟着新选择走。
    var musicSources by remember { mutableStateOf<List<MusicSource>>(emptyList()) }
    LaunchedEffect(showSourcePicker) {
        if (!showSourcePicker) {
            musicSources = runCatching { MusicSourceStore.list(context) }
                .getOrDefault(emptyList())
                .filter { it.enabled }
        }
    }
    // v0.213.0：首选源改 StateFlow 后芯片跟 Flow 走——源管理页删/禁
    // 首选源回来时芯片不再拿旧快照说谎；首次进页恢复一次持久化值。
    val preferredSourceId by MusicPlayer.preferredSourceIdFlow.collectAsState()
    // v0.216.0（C4）：本地音乐／历史数据损坏横幅（与音源管理页横幅同款语义）
    // 注意：必须写在下面 LaunchedEffect 之前，写在后面编译找不到。
    var showMusicCorruptBanner by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        MusicPlayer.restorePreferred(context)
        MusicPlayer.restoreQueue(context)
        MusicPlayer.restoreDurations(context)
        showMusicCorruptBanner = MusicStore.hasCorruptBackup(context) ||
            MusicHistoryStore.hasCorruptBackup(context)
    }
    val currentSourceName = preferredSourceId
        ?.let { id -> musicSources.firstOrNull { it.id == id }?.name }
        ?: "自动"

    // v0.82.0：歌单导入共用逻辑
    // v0.268.0（T15）：原来弹窗点导入就立即清输入、关弹窗，
    // 失败也清，用户想改了重贴还得重新粘贴；现在只在成功时
    // 回调 true、失败回调 false，调用方成功再清输入关弹窗，
    // 失败留输入和弹窗、只给失败原因（toast 已给）。
    fun importPlaylist(input: String, onDone: (Boolean) -> Unit = {}) {
        val inp = input.trim()
        if (inp.isBlank() || importingPlaylist) return
        importingPlaylist = true
        scope.launch {
            runCatching { MusicApi.playlist(inp) }
                .onSuccess { (name, list) ->
                    playlistName = name
                    vm.setTracks(list)
                    AppToast.show(context, "已导入歌单「$name」（${list.size} 首）", long = false)
                    onDone(true)
                }
                .onFailure {
                    AppToast.show(context, "导入失败：${it.message}", long = false)
                    onDone(false)
                }
            importingPlaylist = false
        }
    }

    // v0.177.0：音乐页顶部标题栏整行去掉——搜索框/页签直接贴顶（仅留状态栏高度）。
    // v0.181.0：三行并两行——第一行搜索+导入+源管理，第二行页签+音源芯片，功能一个不删。
    Scaffold {
        Column(
            modifier = Modifier
                .fillMaxSize(), // v0.238.0：顶部贴顶，去掉多余的状态栏内边距
        ) {
            // v0.216.0（C4）：本地音乐／历史数据损坏横幅
            if (showMusicCorruptBanner) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "本地音乐数据损坏，已保留备份，写盘已暂停",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        scope.launch {
                            MusicStore.clearCorrupt(context)
                            MusicHistoryStore.clearCorrupt(context)
                            showMusicCorruptBanner = false
                        }
                    }) { Text("重新开始") }
                    TextButton(onClick = { showMusicCorruptBanner = false }) { Text("暂不处理") }
                }
            }
            // v0.181.0：三行并两行——第一行：搜索（仅在线）+ 歌单导入（仅在线）+ 源管理；
            // 非在线时第一行只留右对齐的源管理，顶栏高度切页签时不跳。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = V4.Gutter, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tab == 0) {
                    AppTextField(
                        value = query,
                        onValueChange = { vm.setQuery(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = "搜歌名 / 歌手",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            doSearch(query)
                        }),
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            keyboard?.hide()
                            doSearch(query)
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索", tint = V4.c.t2, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    // v0.239.0：两个入口改胶囊钮——原来是裸蓝色文字链，
                    // 视觉上像没做完的半成品；现跟下方页签胶囊同款（浅灰底＋深字）。
                    MusicPillButton("歌单导入", onClick = { showPlaylistDialog = true })
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(8.dp))
                MusicPillButton("源管理", onClick = onOpenMusicSource)
            }
            // v0.181.0：第二行：页签胶囊 + 当前音源小芯片（点开即切换音源，名字过长截断）；
            // 原 v0.180.0 的音源大卡整行删掉，功能不删，只收进芯片。
            // v0.279.0 V4：四页签改 V4 文字页签（强调色下划线 + 通栏分隔线），
            // 当前音源芯片放在页签行右侧（点开切换音源，功能不变）。
            V4TopTabs(
                titles = listOf("在线", "本地", "收藏", "歌单"),
                selected = tab,
                onSelect = { tab = it },
            ) {
                Row(
                    modifier = Modifier
                        .padding(end = V4.Gutter)
                        .clip(RoundedCornerShape(V4.RPoster))
                        .clickable { showSourcePicker = true }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SwapHoriz,
                        contentDescription = "切换音源",
                        modifier = Modifier.size(14.dp),
                        tint = V4.c.accent,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = currentSourceName,
                        fontSize = 12.sp,
                        color = V4.c.t2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 96.dp),
                    )
                }
            }
            if (tab == 0) {
                OnlineTab(
                    query = query,
                    playlistName = playlistName,
                    onPlaylistNameChange = { playlistName = it },
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
                    onResetToCharts = {
                        vm.resetToCharts()
                        playlistName = null
                    },
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
            // v0.121.0：当前首选源打勾（null=自动）
            selectedId = MusicPlayer.preferredSourceId(),
            onManageSources = {
                showSourcePicker = false
                onOpenMusicSource()
            },
            onPickAuto = {
                showSourcePicker = false
                MusicPlayer.setPreferredSource(context, null)
                // v0.213.0 播错修复：切回自动原来只改首选不重解析，
                // 当前这首仍走旧源播完；在线歌立即用默认顺序重解析，
                // 本地歌不重播（进度不丢）。
                val cur = MusicPlayer.state.value.track
                if (cur != null && !cur.isLocal) {
                    AppToast.show(context, "正在用自动音源重新解析", long = false)
                    MusicPlayer.replayCurrent(context)
                } else {
                    AppToast.show(context, "已切回自动音源", long = false)
                }
            },
            // v0.126.0：平台区目标沿用本弹窗既有语义=当前在播歌曲（无在播/本地歌不渲染）
            platformTrack = MusicPlayer.state.value.track?.takeIf { !it.isLocal },
            onPickPlatform = { code ->
                showSourcePicker = false
                MusicPlayer.playOnPlatform(context, code)
            },
            onPick = { src ->
                showSourcePicker = false
                MusicPlayer.setPreferredSource(context, src.id)
                val cur = MusicPlayer.state.value.track
                if (cur != null && !cur.isLocal) {
                    AppToast.show(context, "正在用「${src.name}」重新解析", long = false)
                    MusicPlayer.playWithSource(context, src.id)
                } else {
                    // 本地歌不重播（playWithSource 亦会拒绝），只记首选下一首生效。
                    AppToast.show(context, "默认音源：${src.name}（下一首生效）", long = false)
                }
            },
        )
    }

    // v0.82.0：歌单导入弹窗；v0.84.0：换科技感 TechDialog（v0.128.0：随入口上提到本屏）
    if (showPlaylistDialog) {
        TechDialog(
            onDismissRequest = { showPlaylistDialog = false },
            title = "歌单导入",
            subtitle = "粘贴网易云歌单链接或 ID，一键导入整单歌曲",
            confirmText = if (importingPlaylist) "导入中…" else "导入",
            onConfirm = {
                importPlaylist(playlistInput) { ok ->
                    if (ok) {
                        playlistInput = ""
                        showPlaylistDialog = false
                    }
                }
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
}

// v0.82.0：推荐歌单（网易云官方榜单，一点即导入）
// v0.127.0：推荐榜单改由 MusicApi.charts(平台) 按所选平台下发（数据层收敛），
// 原写死在本文件的网易四榜已移入 MusicApi.WY_CHARTS。

/**
 * v0.95：榜单小卡片；v0.279.0 V4：去掉每榜一色的渐变底，统一 s1 平铺卡 +
 * 强调色小图标井 + 榜名 + 右侧箭头；按下缩放 0.97，点击行为不变（导入整榜）。
 */
@Composable
private fun ChartCard(
    name: String,
    importing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val v = V4.c
    val shape = RoundedCornerShape(V4.RTile)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        label = "chartCardPress",
    )
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(52.dp)
            .clip(shape)
            .background(v.s1)
            .border(1.dp, v.div, shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(v.accent.copy(alpha = 0.12f), RoundedCornerShape(V4.RField)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.QueueMusic,
                contentDescription = null,
                tint = v.accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = v.t1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (importing) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = v.accent)
        } else {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "导入",
                tint = v.t3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** v0.279.0 V4：音乐页顶部小按钮（歌单导入 / 源管理）——34dp s1 底 + 1px 分隔线描边。 */
@Composable
private fun MusicPillButton(text: String, onClick: () -> Unit) {
    val v = V4.c
    val shape = RoundedCornerShape(V4.RField)
    Box(
        modifier = Modifier
            .heightIn(min = V4.Field)
            .clip(shape)
            .background(v.s1)
            .border(1.dp, v.div2, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.5.sp, color = v.t1, maxLines = 1)
    }
}

/** v0.279.0 V4：平台 / 分组筛选芯片配色（未选 s1+t2，选中 accentA+accent）。 */
@Composable
private fun v4FilterChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = V4.c.s1,
    labelColor = V4.c.t2,
    selectedContainerColor = V4.c.accentA,
    selectedLabelColor = V4.c.accent,
    disabledContainerColor = V4.c.s1.copy(alpha = 0.5f),
    disabledLabelColor = V4.c.t3.copy(alpha = 0.6f),
)

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
 * v0.126.0：带平台参数（LX code），有曲目上下文的调用处传曲目自身平台。
 */
@Composable
internal fun rememberArtworkUrl(picId: String, platform: String = "wy"): String {
    var url by remember(picId, platform) { mutableStateOf("") }
    LaunchedEffect(picId, platform) {
        url = if (picId.isBlank()) "" else withContext(Dispatchers.IO) {
            runCatching { MusicApi.picImageUrl(picId, platform) }.getOrDefault("")
        }
    }
    return url
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnlineTab(
    query: String,
    // v0.128.0：歌单名由 MusicScreen 持有（歌单导入已上提），榜单加载经回调回写
    playlistName: String?,
    onPlaylistNameChange: (String?) -> Unit,
    tracks: List<MusicApi.Track>,
    searching: Boolean,
    searched: Boolean,
    searchHistory: List<String>,
    onHistorySearch: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    onSearch: (String) -> Unit,
    onResetToCharts: () -> Unit,
    onTracksLoaded: (List<MusicApi.Track>) -> Unit,
    onPlayAt: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by MusicPlayer.state.collectAsState()
    val badges by MusicPlayer.badges.collectAsState() // v0.117.0：来源/音质徽标
    var favIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // v0.278.0：下载状态（在线/收藏两页共用 MusicDownloader 的流）
    val downloadingIds by MusicDownloader.downloading.collectAsState()
    val downloadedIds by MusicDownloader.downloaded.collectAsState()
    // v0.128.0：榜单加载忙碌标记（原与歌单导入共用 importingPlaylist，导入上提后本页独立持有）
    var importingChart by remember { mutableStateOf(false) }
    // v0.84.0：加到歌单
    var addToPlaylistTrack by remember { mutableStateOf<MusicApi.Track?>(null) }

    LaunchedEffect(Unit) {
        favIds = withContext(Dispatchers.IO) {
            MusicStore.favList(context).map { it.id }.toSet()
        }
        MusicDownloader.refreshDownloaded(context)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // v0.126.0：搜索平台切换（单选、持久化到 MusicSettings）。
        // v0.127.0：wy 走 GD，其余走 PlatformMusicApi 各家原生接口（逐家实测），
        // 切换后搜索结果与下方榜单都跟随平台刷新，不再一成不变。
        // v0.180.0：平台行不再写死五家五等分——行里列的是音源自报的平台
        // （MusicSearchViewModel.availablePlatforms，音源没加载出来时先按已知
        // 可搜索平台兜底），以后音源多一个平台就多一个 chip，横滑排布，
        // 再多也不会被挤扁；短标签由 MusicApi.platformShortLabel 派生，
        // 音源自带但搜索接口还没接的平台置灰（下面有小字说明原因）。
        val musicPlatform by MusicSettings.musicPlatform.collectAsState()
        val activity = LocalContext.current as ComponentActivity
        val vm: MusicSearchViewModel = viewModel(activity)
        val availablePlatforms by vm.availablePlatforms.collectAsState()
        // v0.213.0 构建修复：searchError 只在 MusicScreen 里 collect 过，
        // OnlineTab 是独立 composable，782/814 行直接用它导致
        // compileReleaseKotlin 报 Unresolved reference；这里跟 vm 走。
        val searchError by vm.searchError.collectAsState()
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = V4.Gutter, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(availablePlatforms, key = { it }) { code ->
                FilterChip(
                    selected = musicPlatform == code,
                    enabled = code in MusicApi.SEARCHABLE_PLATFORMS,
                    shape = RoundedCornerShape(V4.RPoster),
                    colors = v4FilterChipColors(),
                    border = null,
                    modifier = Modifier.height(28.dp),
                    onClick = {
                        MusicSettings.setMusicPlatform(context, code)
                        // v0.146.0：空关键词切平台也要有变化——之前只有关键词非空才重搜，
                        // 空着切平台列表还是上次旧结果、榜单区也被已搜状态藏着，看着像没反应。
                        // 空关键词时回榜单态（榜单跟随平台刷新），有关键词时重搜。
                        if (query.isNotBlank()) onSearch(query) else onResetToCharts()
                    },
                    label = { Text(MusicApi.platformShortLabel(code), maxLines = 1, fontSize = 12.sp) },
                )
            }
        }
        if (availablePlatforms.any { it !in MusicApi.SEARCHABLE_PLATFORMS }) {
            Text(
                text = "置灰的平台是音源自带的，搜索接口还没接上，暂时点不了",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        // v0.127.0：推荐榜单跟随所选平台（MusicApi.charts 分发到各家原生接口）
        var charts by remember { mutableStateOf<List<MusicApi.ChartEntry>>(emptyList()) }
        // v0.213.0 音乐界面误导修复：榜单失败原来 getOrDefault(emptyList())，
        // 空榜单统一渲染“暂无榜单接口”，断网被说成平台不支持；
        // 失败单独记 chartsFailed，UI 按失败说实话并给重试。
        var chartsFailed by remember { mutableStateOf(false) }
        fun loadCharts() {
            scope.launch {
                runCatching { MusicApi.charts(musicPlatform) }
                    .onSuccess { charts = it; chartsFailed = false }
                    .onFailure { chartsFailed = true }
            }
        }
        LaunchedEffect(musicPlatform) { loadCharts() }
        // 点榜单 → 拉该平台榜单曲目并回填列表（与歌单导入同一通路）
        fun loadChart(entry: MusicApi.ChartEntry) {
            if (importingChart) return
            importingChart = true
            scope.launch {
                runCatching { MusicApi.chartTracks(musicPlatform, entry.id) }
                    .onSuccess { list ->
                        if (list.isEmpty()) {
                            AppToast.show(context, "榜单暂时拉不到，稍后再试", long = false)
                        } else {
                            onPlaylistNameChange(entry.name)
                            onTracksLoaded(list)
                        }
                    }
                    .onFailure {
                        AppToast.show(context, "榜单加载失败：${it.message}", long = false)
                    }
                importingChart = false
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
                // v0.133.0：历史词以词本身为 key，文件读侧无去重，渲染前 distinct 兜底防同词撞 key
                items(searchHistory.distinct(), key = { it }) { h ->
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
                ) {
                    // 搜索区加载圈统一为 StatusRing（跑马灯圆环 + 黄色状态点）
                    StatusRing(state = StatusRingState.Loading, diameter = 40.dp)
                }
                // v0.180.0：榜单改竖排满宽列表 + 底部说明卡——以前 2x2 小卡
                // 只占上半截，下半页大片空白看着像没加载完；竖排后榜单、
                // 说明一屏排下来，空白感没了，榜单本身还是点一下导入整榜。
                !searched -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = V4.Gutter, end = V4.Gutter, top = 0.dp,
                        bottom = dockClearance() + 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        V4SectionHeader(
                            title = "推荐榜单",
                            count = if (charts.isNotEmpty()) "${charts.size}" else null,
                        )
                    }
                    if (charts.isEmpty()) {
                        // v0.146.0：咪咕无公开榜单接口，直说接口缺失，别让用户以为读取失败。
                        // v0.213.0：失败态单独分支，带重试，不再谎报“暂无接口”。
                        item {
                            if (chartsFailed) {
                                Column {
                                    Text(
                                        text = "榜单加载失败",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = { loadCharts() }) {
                                        Text("点击重试")
                                    }
                                }
                            } else {
                                Text(
                                    text = if (musicPlatform == "mg")
                                        "${MusicApi.platformShortLabel(musicPlatform)}暂无榜单接口"
                                    else "该平台暂无榜单接口",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(charts, key = { it.id }) { entry ->
                        ChartCard(
                            name = entry.name,
                            importing = importingChart,
                            onClick = { loadChart(entry) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "榜单怎么用",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = V4.c.t1,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "点榜单名导入整榜；切上面平台，搜索和榜单都跟着换。" +
                                        "平台行跟随当前音源走，音源以后多一个平台，上面就多一个选项。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                tracks.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    // v0.213.0：失败不再谎报“没搜到”；失败态给 Error 档 +
                    // 重试按钮，旧结果已由 VM 保留（非空时走列表分支）。
                    if (searchError) {
                        EmptyState(
                            kind = EmptyKind.Error,
                            icon = Icons.Filled.MusicNote,
                            title = "搜索失败",
                            hint = "网络或音源接口异常，换个网络或稍后再试",
                            actionText = "重试",
                            onAction = { vm.doSearch() },
                        )
                    } else {
                        EmptyState(
                            kind = EmptyKind.Empty,
                            icon = Icons.Filled.MusicNote,
                            title = "没搜到",
                            hint = "换个关键词试试",
                        )
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = dockClearance() + 8.dp),
                ) {
                    // v0.213.0：失败时列表是上次的好结果，顶行明说，避免
                    // 用户以为这就是本次搜索结果；另补总数（本地/歌单页有，
                    // 搜索结果原来没有）。
                    item {
                        Column(modifier = Modifier.padding(horizontal = V4.Gutter, vertical = 6.dp)) {
                            Text(
                                text = "共 ${tracks.size} 首",
                                style = V4Type.MonoMeta,
                                color = V4.c.t3,
                            )
                            if (searchError) {
                                Text(
                                    text = "上次搜索失败，显示的是上次的结果",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // v0.133.0：搜索与导入歌单均来自外部数据，同 id 重复曲目可真实出现；
                    // key 用 id 加下标复合（不去重，播放下标须与原列表对齐）
                    itemsIndexed(tracks, key = { idx, t -> "${t.id}_$idx" }) { idx, track ->
                        val isFav = track.id in favIds
                        TrackRow(
                            track = track,
                            badge = badges[MusicPlayer.durationKey(track)],
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
                            onDownloadClick = { MusicDownloader.download(context, track) },
                            isDownloaded = track.id in downloadedIds,
                            isDownloading = track.id in downloadingIds,
                            onClick = { onPlayAt(idx) },
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

/** v0.81.0：本地音乐 tab——导入本地音频文件，列表播放/删除；v0.89.0：按歌手/专辑/文件夹分组浏览。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by MusicPlayer.state.collectAsState()
    val badges by MusicPlayer.badges.collectAsState() // v0.117.0：来源/音质徽标
    var locals by remember { mutableStateOf<List<MusicApi.Track>>(emptyList()) }
    // v0.213.0 音乐界面误导修复：本地/收藏/歌单三页原来初始空列表，
    // 异步 reload 完成前就闪空态；加 loading 旗标，加载中只转圈。
    var localsLoading by remember { mutableStateOf(true) }
    // v0.213.0：删本地歌原来直接执行无确认，联动删三处数据；
    // 改走歌单删除同款确认弹窗。
    var deleteTrackTarget by remember { mutableStateOf<MusicApi.Track?>(null) }
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
            localsLoading = true
            locals = MusicStore.localList(context)
            localsLoading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    val pickAudio = rememberLauncherForActivityResult(
        // v0.246.0：GetContent 改 OpenDocument——GetContent 只给临时授权，
        // App 重启后存下的 content:// URI 读不了（全仓无 takePersistableUriPermission）。
        // OpenDocument + takePersistableUriPermission 拿长期授权，重启后仍可播。
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure {
                LogBus.w("Music", "本地音乐长期授权失败（重启后可能无法播放）：${it.message}")
            }
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
            // v0.188.0：addLocal 返回写盘成败，失败不再谎报已导入。
            val imported = MusicStore.addLocal(context, track)
            reload()
            AppToast.show(context, if (imported) "已导入《$clean》" else "保存失败，请重试", long = false)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = V4.Gutter, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (locals.isEmpty()) "导入手机里的音频文件" else "共 ${locals.size} 首",
                fontSize = 12.sp,
                color = V4.c.t3,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { pickAudio.launch(arrayOf("audio/*")) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("导入音乐")
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            if (localsLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    StatusRing(state = StatusRingState.Loading, diameter = 40.dp)
                }
            } else if (locals.isEmpty()) {
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
                                shape = RoundedCornerShape(V4.RPoster),
                                colors = v4FilterChipColors(),
                                border = null,
                                modifier = Modifier.height(28.dp),
                                label = { Text(GROUP_LABELS[i], fontSize = 12.sp) },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (groupMode == 0) {
                        // 全部：现状平铺
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = dockClearance() + 8.dp),
                        ) {
                            itemsIndexed(locals, key = { _, t -> t.id }) { idx, track ->
                                TrackRow(
                                    track = track,
                                    badge = badges[MusicPlayer.durationKey(track)],
                                    isCurrent = playState.track?.id == track.id,
                                    isPlaying = playState.track?.id == track.id && playState.playing,
                                    onDeleteClick = { deleteTrackTarget = track },
                                    onPlaylistClick = { addToPlaylistTrack = track },
                                    onClick = { MusicPlayer.playList(context, locals, idx) },
                                )
                            }
                        }
                    } else {
                        // v0.89.0：分组视图——sticky 分组头（点头展开/折叠）+ 组内连续播放
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = dockClearance() + 8.dp),
                        ) {
                            groups.forEach { (gname, glist) ->
                                val ckey = "${groupMode}_$gname"
                                stickyHeader(key = "hdr_$ckey") {
                                    val isCollapsed = ckey in collapsed
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(V4.c.bg.copy(alpha = 0.96f))
                                            .clickable {
                                                collapsed = if (isCollapsed) {
                                                    collapsed - ckey
                                                } else {
                                                    collapsed + ckey
                                                }
                                            }
                                            .padding(horizontal = V4.Gutter, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "$gname · ${glist.size} 首",
                                            style = V4Type.Section,
                                            color = V4.c.t1,
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
                                            badge = badges[MusicPlayer.durationKey(track)],
                                            isCurrent = playState.track?.id == track.id,
                                            isPlaying = playState.track?.id == track.id &&
                                                playState.playing,
                                            onDeleteClick = { deleteTrackTarget = track },
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

    deleteTrackTarget?.let { t ->
        TechDialog(
            onDismissRequest = { deleteTrackTarget = null },
            title = "删除本地歌曲",
            confirmText = "删除",
            onConfirm = {
                scope.launch {
                    // v0.188.0 M23：删本地歌即四处联动删（收藏、歌单、播放历史）。
                    val ok = MusicStore.removeTrackEverywhere(context, t.id)
                    deleteTrackTarget = null
                    // v0.213.0：删歌同步播放器，否则删掉的歌播完、上下首还能播到它。
                    if (ok) MusicPlayer.onTrackDeleted(context, t.id)
                    // v0.278.0：下载歌顺带删文件（只删 music_downloads 里的，导入的歌不动）
                    if (ok) MusicDownloader.deleteDownloadedFile(context, t)
                    reload()
                    if (!ok) {
                        AppToast.show(context, "删除失败：保存失败，请重试", long = false)
                    }
                }
            },
        ) {
            Text("确定删除「${t.name}」？收藏、歌单、播放历史里的这首歌也会一起删掉。")
        }
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
    var favsLoading by remember { mutableStateOf(true) }
    // v0.84.0：加到歌单
    var addToPlaylistTrack by remember { mutableStateOf<MusicApi.Track?>(null) }
    // v0.278.0：下载状态
    val downloadingIds by MusicDownloader.downloading.collectAsState()
    val downloadedIds by MusicDownloader.downloaded.collectAsState()

    fun reload() {
        scope.launch {
            favsLoading = true
            favs = MusicStore.favList(context)
            favsLoading = false
        }
    }
    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(Unit) { MusicDownloader.refreshDownloaded(context) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (favsLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                StatusRing(state = StatusRingState.Loading, diameter = 40.dp)
            }
        } else if (favs.isEmpty()) {
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
                contentPadding = PaddingValues(bottom = dockClearance() + 8.dp),
            ) {
                itemsIndexed(favs, key = { _, t -> t.id }) { idx, track ->
                    TrackRow(
                        track = track,
                        badge = badges[MusicPlayer.durationKey(track)],
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
                        onDownloadClick = { MusicDownloader.download(context, track) },
                        isDownloaded = track.id in downloadedIds,
                        isDownloading = track.id in downloadingIds,
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

/** v0.117.0：歌曲来源/音质小徽标；v0.121.0：音质按档次着色（无损/母带=金、高清=蓝、流畅/标准=灰），来源中性 */
@Composable
private fun BadgeChip(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}

/** v0.121.0：音质文本 → 徽标色，明度自适应（浅底深字/深底浅字） */
@Composable
private fun qualityChipColor(quality: String): Color {
    val lightBg = MaterialTheme.colorScheme.background.luminance() > 0.5f
    return when {
        quality.contains("无损") || quality.contains("母带") || quality.contains("超高清") ||
            listOf("flac", "hires", "atmos", "master").any {
                quality.contains(it, ignoreCase = true)
            } -> V4.c.t1
        quality.contains("高清") || quality.contains("320k") ->
            V4.c.accent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** v0.215.0：毫秒转 m:ss（列表时长 chip 用）。 */
private fun formatDurationMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
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
    // v0.278.0：下载（在线歌才传，本地歌不传；已下载/下载中由调用方按状态传）
    onDownloadClick: (() -> Unit)? = null,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    onClick: () -> Unit,
) {
    // v0.89.0：封面走 picImageUrl 解析后的真实地址（接口包一层 {"url":...}）
    val artUrl = rememberArtworkUrl(track.picId, track.source)
    // v0.215.0：播过才有时长（搜索结果本身不带时长），没播过的歌不编时长。
    val durations by MusicPlayer.durations.collectAsState()
    val trackDurationMs = durations[MusicPlayer.durationKey(track)]
    // v0.279.0 V4：卡片列表改平铺曲目行（40dp 圆角封面 + 标题/歌手 + 徽标，底部 1px 分隔线），
    // 右侧操作（收藏 / 删除 / 加歌单 / 下载 / 播放态）全部保留。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .v4BottomDivider(V4.c.div)
            .padding(start = V4.Gutter, end = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (artUrl.isNotBlank()) {
                AsyncImage(
                    model = artUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(V4.RPoster)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(V4.RPoster))
                        .background(V4.c.artBrush(track.name)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = V4.c.t2,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    fontSize = 13.sp,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isCurrent) V4.c.accent else V4.c.t1,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = "${track.artist} · ${track.album}".trim(' ', '·'),
                    fontSize = 11.sp,
                    color = V4.c.t3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // v0.123.0：来源平台 chip 常驻（点播前就有，数据来自 track 自带元信息，
                // 无来源信息不显示、不编造）；与 v0.117.0 的解析徽标（播过才有）并排，
                // 来源中性色在前，解析音源/音质照旧在后
                val sourceLabel = when {
                    track.isLocal -> "本地"
                    else -> MusicApi.platformLabel(track.source)
                }
                if (sourceLabel != null || badge != null || trackDurationMs != null) {
                    Spacer(Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (sourceLabel != null) {
                            BadgeChip(
                                text = sourceLabel,
                                tint = V4.c.t3,
                            )
                        }
                        if (badge != null) {
                            BadgeChip(
                                text = badge.first,
                                tint = V4.c.t3,
                            )
                            if (badge.second.isNotBlank()) {
                                BadgeChip(
                                    text = badge.second,
                                    tint = qualityChipColor(badge.second),
                                )
                            }
                        }
                        if (trackDurationMs != null) {
                            BadgeChip(
                                text = formatDurationMs(trackDurationMs),
                                tint = V4.c.t3,
                            )
                        }
                    }
                }
            }
            onFavClick?.let {
                IconButton(onClick = it) {
                    Icon(
                        if (isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (isFav) "取消收藏" else "收藏",
                        tint = if (isFav) V4.c.live
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            onDeleteClick?.let {
                IconButton(onClick = it) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = V4.c.t3,
                    )
                }
            }
            // v0.84.0：加到歌单
            onPlaylistClick?.let {
                IconButton(onClick = it) {
                    Icon(
                        Icons.Filled.PlaylistAdd,
                        contentDescription = "加到歌单",
                        tint = V4.c.t3,
                    )
                }
            }
            // v0.278.0：下载——在线歌才显示，本地歌不走这里
            if (onDownloadClick != null && !track.isLocal) {
                if (isDownloading) {
                    IconButton(onClick = {}, enabled = false) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    IconButton(
                        onClick = onDownloadClick,
                        enabled = !isDownloaded,
                    ) {
                        Icon(
                            if (isDownloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                            contentDescription = if (isDownloaded) "已下载" else "下载",
                            tint = if (isDownloaded) V4.c.accent
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (isCurrent) {
                if (isPlaying) {
                    Icon(
                        Icons.Filled.Pause,
                        contentDescription = "正在播放",
                        tint = V4.c.accent,
                    )
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "当前",
                        tint = V4.c.t3,
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
    var playlistsLoading by remember { mutableStateOf(true) }
    var showNew by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<MusicPlaylist?>(null) }
    var deleteTarget by remember { mutableStateOf<MusicPlaylist?>(null) }

    fun reload() {
        scope.launch {
            playlistsLoading = true
            playlists = MusicStore.playlistList(context)
            playlistsLoading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = V4.Gutter, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (playlists.isEmpty()) "把喜欢的歌攒成自己的歌单" else "共 ${playlists.size} 个歌单",
                fontSize = 12.sp,
                color = V4.c.t3,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showNew = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("新建歌单")
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            if (playlistsLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    StatusRing(state = StatusRingState.Loading, diameter = 40.dp)
                }
            } else if (playlists.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.QueueMusic,
                        title = "还没有歌单",
                        hint = "点「新建歌单」，或在歌曲旁点歌单按钮加进来",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = dockClearance() + 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // v0.133.0：歌单 id 为毫秒时间戳拼接，非强唯一，渲染前 distinctBy 兜底
                    items(playlists.distinctBy { it.id }, key = { it.id }) { pl ->
                        AppCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = V4.Gutter)
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
                        AppToast.show(context, "已创建歌单「$name」", long = false)
                    } else {
                        // v0.188.0：createPlaylist 返回空有三种原因，先在 UI 侧区分
                        // 空名与重名，剩下的才是写盘失败，不再一律报“为空或已存在”。
                        AppToast.show(context, when {
                                name.isBlank() -> "歌单名为空"
                                playlists.any { it.name == name.trim() } -> "歌单名已存在"
                                else -> "保存失败，请重试"
                            }, long = false)
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
                        // v0.188.0：与新建同理，先区分空名与重名，剩下的才是写盘失败。
                        AppToast.show(context, when {
                                name.isBlank() -> "歌单名为空"
                                playlists.any { it.name == name.trim() && it.id != pl.id } -> "歌单名已存在"
                                else -> "保存失败，请重试"
                            }, long = false)
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
                    // v0.188.0：删歌单写盘失败要提示，成功保持原来静默。
                    val ok = MusicStore.deletePlaylist(context, pl.id)
                    deleteTarget = null
                    reload()
                    if (!ok) {
                        AppToast.show(context, "删除失败：保存失败，请重试", long = false)
                    }
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
            // v0.188.0：addToPlaylist 返回 false 有三种原因（已在、歌单没了、写盘失败），
            // 先用当前列表区分“已在”，剩下的报保存失败，不再一律报已在。
            val already = pl.tracks.any { it.id == track.id }
            val ok = MusicStore.addToPlaylist(context, pl.id, track)
            AppToast.show(context, when {
                    ok -> "已加入「${pl.name}」"
                    already -> "这首已经在「${pl.name}」里了"
                    else -> "保存失败，请重试"
                }, long = false)
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
                        // v0.133.0：同 PlaylistTab，歌单 id 非强唯一，渲染前 distinctBy 兜底
                        items(playlists.distinctBy { it.id }, key = { it.id }) { pl ->
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
                        // v0.188.0：与歌单页新建同理，先区分空名与重名，剩下的才是写盘失败。
                        AppToast.show(context, when {
                                name.isBlank() -> "歌单名为空"
                                playlists.any { it.name == name.trim() } -> "歌单名已存在"
                                else -> "保存失败，请重试"
                            }, long = false)
                    }
                }
            },
        )
    }
}
