package com.movieshell.ui.live

import android.content.ContentResolver
import com.movieshell.ui.components.AppToast
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.provider.OpenableColumns
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.repo.EpgFetcher
import com.movieshell.data.repo.LiveChannel
import com.movieshell.data.repo.LiveSource
import com.movieshell.data.repo.LiveStore
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.TrashStore
import com.movieshell.player.EngineHost
import com.movieshell.player.PlaySession
import com.movieshell.player.PlayerScreen
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

// v0.160.0：直播选中状态放内存单例，不放 remember——切 tab 离页重组、聚合刷新
// 都不会丢选中频道和按台名选的源；聚合刷新本身只在 selected==null 时才取首台，
// 所以 TRAE 说的“聚合一刷新就重置”在原代码里并不成立，真正丢状态的是离页重组
private object LiveSelection {
    var channelId: String? = null
    val sourceByName = mutableMapOf<String, String>()
    // v0.171.0：分组与源行选中态也进单例，切 tab 离页重组不再回首组/全部
    var selectedGroup: String? = null
    var selectedLiveSource: String? = null
}

/** 直播 Tab：DsPlayer 式布局（v0.80.0 重做）——顶部 16:9 播放器 + 节目信息卡 + 两列（分组/频道）+ 播放器下方源行 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LiveScreen(
    onFullscreenChannel: (LiveChannel) -> Unit,
    onManageClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val channels by LiveStore.channels.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var detailChannel by remember { mutableStateOf<LiveChannel?>(null) }
    // v0.176.0：顶栏已去掉，设置入口改到节目卡齿轮 → DS 式直播设置弹层
    var showLiveSettings by remember { mutableStateOf(false) }
    // v0.79.0：顶部播放器当前频道；进场自动选中第一个
    // v0.160.0：初值从单例恢复，离页回来也不回首台
    var selected by remember { mutableStateOf<LiveChannel?>(null) }
    // v0.80.0：左侧分组选中态
    // v0.171.0：初值从单例恢复，切 tab 回来不回首组
    var selectedGroup by remember { mutableStateOf(LiveSelection.selectedGroup) }
    // v0.158.0：直播页内换源（null=全部，DsPlayer 切换直播源对标）；
    // 直播失败已试过的同名源 id（自动切源防循环，手动点频道/源时清空）
    // v0.171.0：初值从单例恢复，切 tab 回来不回"全部"
    var selectedLiveSource by remember { mutableStateOf(LiveSelection.selectedLiveSource) }
    var liveTriedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // v0.159.0：按台名记住上次选的源（源1/源2）——中列点台不再永远取同名第一条（源1）
    var liveSourceByName by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val globalEpg by LiveStore.globalEpg.collectAsState()

    // v0.219.0：切 tab 真离开时停播的开关——照详情页 goingFullscreen 的套路：
    // 跳全屏独立播放页／直播管理页是页内跳转（会话延续），先置 true，
    // dispose 时跳过停播；其他离开路径（切 tab 等）为 false，dispose 时
    // 停引擎、清会话、关可见标记。否则切到小说页后直播还在后台响。
    // plain remember 不随 saveState 恢复，回页重组自动变回 false，不用手动复位。
    var goingToLivePage by remember { mutableStateOf(false) }
    // v0.96.2：横屏全屏播放（手动横屏按钮 / 物理横屏）且有在播频道时，播放器占满全屏
    val fullscreenPlayer = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        selected != null
    // v0.96.2：离页时恢复系统方向——手动横屏后切 tab / 跳独立播放页，不能把整个 App 锁在横屏
    // v0.219.0：同 effect 内补真离开停播（见 goingToLivePage 注释）。
    DisposableEffect(Unit) {
        onDispose {
            if (!goingToLivePage) {
                EngineHost.stop()
                PlaySession.clear()
                PlaySession.playerVisible = false
            }
            (context as? Activity)?.let {
                if (it.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    fun doRefresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            runCatching { LiveStore.refresh(context) }
                // v0.107.0 P0-9：订阅 0 频道不再静默，给可见提示
                .onSuccess { empties ->
                    if (empties.isNotEmpty()) {
                        AppToast.show(context, "以下订阅未解析出频道：${empties.joinToString("、")}", long = true)
                    }
                }
                .onFailure { AppToast.show(context, "刷新失败：${it.message}", long = true) }
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        if (channels.isEmpty()) doRefresh()
    }
    // 频道列表就绪后自动选中第一个（无选中时）；已有选中时按 id 换成刷新后的新对象，
    // 不回首台——聚合刷新不重置选中，离页重组靠单例恢复
    LaunchedEffect(channels) {
        if (channels.isNotEmpty()) {
            val restored = LiveSelection.channelId?.let { id ->
                channels.firstOrNull { it.id == id }
            }
            if (selected == null) {
                selected = restored ?: channels.first()
                restored?.let { LiveSelection.channelId = it.id }
            } else if (channels.none { it.id == selected?.id }) {
                selected = restored ?: channels.first()
            } else {
                // v0.217.0（F19）：选中台还在列表里时，换成刷新后的新对象——
                // 否则顶部卡拿着旧拷贝，收藏／过期状态刷新后不同步。
                channels.firstOrNull { it.id == selected?.id }?.let { selected = it }
            }
            // 按台名选的源从单例恢复一次（切 tab 回来不丢源2）
            if (liveSourceByName.isEmpty() && LiveSelection.sourceByName.isNotEmpty()) {
                liveSourceByName = LiveSelection.sourceByName.toMap()
            }
        }
    }

    fun playChannel(ch: LiveChannel) {
        // v0.160.0：同台重复点不重建播放器（2 秒连点 5 台是不同台，不拦；同台才拦）
        if (selected?.id == ch.id) return
        liveTriedIds = emptySet()
        liveSourceByName = liveSourceByName + (ch.name to ch.id)
        LiveSelection.sourceByName[ch.name] = ch.id
        LiveSelection.channelId = ch.id
        selected = ch
    }

    val filtered = remember(channels, query, selectedLiveSource) {
        channels.filter {
            (selectedLiveSource == null || it.sourceKey == selectedLiveSource) &&
                (query.isBlank() || it.name.contains(query, ignoreCase = true))
        }
    }
    val sourceKeys = remember(channels) { channels.map { it.sourceKey }.distinct() }
    // v0.158.0：换源后自动播该源第一个频道（DsPlayer 切源即播该源）；全部则不动当前播放
    LaunchedEffect(selectedLiveSource) {
        if (selectedLiveSource != null) {
            val first = filtered.firstOrNull()
            if (first != null && selected?.sourceKey != selectedLiveSource) {
                liveTriedIds = emptySet()
                LiveSelection.channelId = first.id
                selected = first
            }
        }
    }
    val groups = remember(filtered) {
        val sorted = filtered.groupBy { it.group.ifBlank { "未分组" } }.toSortedMap()
        // v0.153.0：央视频道排最前（原 toSortedMap 按字序把卫视频道排在前面），其余仍按原排序
        val ordered = linkedMapOf<String, List<LiveChannel>>()
        sorted.filterKeys { it.contains("央视") }.forEach { (k, v) -> ordered[k] = v }
        sorted.filterKeys { !it.contains("央视") }.forEach { (k, v) -> ordered[k] = v }
        // v0.176.0：收藏改成 DS 式一级分组——左列第一项“我的收藏”，
        // 不再是顶栏星标开关；无收藏时不出现，选中态丢了自动回首组。
        val withFav = linkedMapOf<String, List<LiveChannel>>()
        val favs = filtered.filter { it.favorite }
        if (favs.isNotEmpty()) withFav["我的收藏"] = favs
        withFav.putAll(ordered)
        withFav
    }
    val groupNames = remember(groups) { groups.keys.toList() }
    // 分组选中态：保留旧选（若仍在），否则取首个真实分组——
    // v0.176.0：“我的收藏”只排第一项，不当默认分组（对标 DS 默认选中卫视频道，
    // 不是我的收藏），只有用户自己点过它才留。
    LaunchedEffect(groupNames) {
        if (selectedGroup !in groupNames) {
            selectedGroup = groupNames.firstOrNull { it != "我的收藏" }
                ?: groupNames.firstOrNull()
        }
    }
    // v0.80.1：selectedGroup 初始为 null（LaunchedEffect 异步才赋值），TreeMap.get(null)
    // 会直接 NPE 闪退；首帧先兜底取首个真实分组（同上，不默认我的收藏）
    val groupChannels = remember(groups, selectedGroup) {
        val g = selectedGroup
            ?: groups.keys.firstOrNull { it != "我的收藏" }
            ?: groups.keys.firstOrNull()
        if (g == null) emptyList() else groups[g].orEmpty()
    }
    // v0.159.0：中列按台名去重，每台只显示一条——显示的是该台上次选的源，
    // 没选过才取第一条；否则中列下面会再出现一遍同名台，点它又回源1
    val groupChannelsDistinct = remember(groupChannels, liveSourceByName) {
        groupChannels.groupBy { it.name }.values.map { list ->
            liveSourceByName[list.first().name]?.let { id ->
                list.firstOrNull { it.id == id }
            } ?: list.first()
        }
    }
    // 同名多线路 = 源1..N（v0.160.0 起放播放器下方源行，原右列已删）
    val sources = remember(channels, selected) {
        val name = selected?.name
        if (name.isNullOrBlank()) emptyList()
        else channels.filter { it.name == name }
    }

    // EPG：当前节目 + 下一档（节目信息卡用）；
    // v0.158.0：本源无 epg 时用全局兜底（用户自填，不内置源），仍无则 null
    var epg by remember { mutableStateOf<Pair<EpgFetcher.Program?, EpgFetcher.Program?>?>(null) }
    LaunchedEffect(selected, globalEpg) {
        val ch = selected
        val src = ch?.epgUrl?.ifBlank { globalEpg }.orEmpty()
        epg = if (ch != null && src.isNotBlank()) {
            EpgFetcher.nowAndNext(src, ch.name)
        } else {
            null
        }
    }

    // v0.176.0：直播页顶栏整行去掉——播放器直接贴顶（系统状态栏下方第一行），
    // 不再留 48dp“直播”标题栏；顶栏四个功能不删，全部移位：
    // 搜索/收藏/设置 → 节目卡右侧三个图标（对标 DS 频道头齿轮+爱心），
    // 刷新 → 直播设置弹层右上角；收藏另加左列“我的收藏”分组。
    Scaffold {
        Column(
            // v0.124.0：横屏全屏时不套状态栏内边距——窗口层全出血在
            // PlayerScreen 的 applyImmersiveFullscreen 一并处理；
            // 竖屏只留状态栏高度，播放器贴在状态栏正下方，不压系统时间电量。
            modifier = if (fullscreenPlayer) Modifier.fillMaxSize()
            else Modifier.fillMaxSize().statusBarsPadding(),
        ) {
            // 顶部 16:9 播放器；v0.96.2：横屏全屏时占满（PlayerScreen 调用点唯一，不重建播放器）
            val playing = selected
            Box(
                modifier = (if (fullscreenPlayer) Modifier.fillMaxSize()
                            else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    .background(Color.Black),
            ) {
                if (playing != null) {
                    PlayerScreen(
                        url = playing.url,
                        title = playing.name,
                        isLive = true,
                        onBack = {},
                        embedded = true,
                        onFullscreen = {
                            goingToLivePage = true
                            onFullscreenChannel(playing)
                        },
                        // v0.158.0：最终失败自动切同名源（同内核重拉+换内核已在播放器内走完）；
                        // 切到其他源时把换源过滤放回全部，避免列表与在播频道对不上
                        onLiveError = {
                            val cur = selected
                            val same = if (cur == null) emptyList()
                            else channels.filter { it.name == cur.name }
                            val tried = liveTriedIds + (cur?.id.orEmpty())
                            val next = same.firstOrNull { it.id !in tried }
                            if (next == null) {
                                AppToast.show(context, "同名源均播放失败", long = false)
                            } else {
                                liveTriedIds = tried
                                if (selectedLiveSource != null && next.sourceKey != selectedLiveSource) {
                                    selectedLiveSource = null
                                }
                                AppToast.show(context, "当前源播放失败，已自动切到源${same.indexOf(next) + 1}", long = false)
                                liveSourceByName = liveSourceByName + (next.name to next.id)
                                LiveSelection.sourceByName[next.name] = next.id
                                LiveSelection.channelId = next.id
                                selected = next
                            }
                        },
                        // v0.160.0：频道自带 ua 才传（cctv-live 目前 ua 为空，传空 map，不编 header）
                        liveHeaders = playing.ua.takeIf { it.isNotBlank() }
                            ?.let { mapOf("User-Agent" to it) } ?: emptyMap(),
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(color = Color.White)
                        } else {
                            Text(
                                "选择下方频道开始播放",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            // v0.96.2：横屏全屏只留播放器，其他 UI 隐藏
            if (!fullscreenPlayer) {
            // 节目信息卡：台标 + 正在播/下一档（对标 DsPlayer）
            ProgramInfoCard(
                channel = playing,
                epg = epg,
                hasEpgSource = (playing?.epgUrl?.isNotBlank() == true || globalEpg.isNotBlank()),
                isFavorite = channels.firstOrNull { it.id == playing?.id }?.favorite == true,
                onSearchClick = { showSearch = !showSearch },
                onFavoriteClick = {
                    playing?.let { LiveStore.toggleFavorite(context, it.id) }
                },
                onSettingsClick = { showLiveSettings = true },
            )
            // v0.160.0：同名源行移到播放器下方（原右列删掉）——源1/源2是当前台的全局切换，
            // 不该在每个频道卡片里重复出现；卡片右上角只留星标
            if (sources.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "线路",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    sources.forEachIndexed { idx, ch ->
                        SourceButton(
                            label = "源${idx + 1}",
                            selected = selected?.id == ch.id,
                            onClick = { playChannel(ch) },
                        )
                    }
                }
            }
            // v0.158.0：直播页内换源条（DsPlayer 切换直播源对标）——全部 + 各源一键切
            if (sourceKeys.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SourceButton(
                        label = "全部",
                        selected = selectedLiveSource == null,
                        onClick = {
                            selectedLiveSource = null
                            LiveSelection.selectedLiveSource = null
                        },
                    )
                    sourceKeys.forEach { key ->
                        SourceButton(
                            label = liveSourceLabel(key),
                            selected = selectedLiveSource == key,
                            onClick = {
                                selectedLiveSource = key
                                LiveSelection.selectedLiveSource = key
                            },
                        )
                    }
                }
            }
            // 搜索行（默认收起，点顶栏放大镜展开）
            if (showSearch) {
                AppTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    placeholder = "搜索频道",
                )
            }
            if (refreshing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在拉取直播源…", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (filtered.isEmpty() && !refreshing) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.Tv,
                        title = "还没有直播频道",
                        // v0.96.1：插件更新/重装瞬间聚合会短暂为 0，给明确指引而不是干等
                        hint = "插件更新或重装时请稍候，在直播设置里点刷新重试；\n或在直播设置里进订阅管理添加 M3U 订阅、去插件市场安装 live 插件",
                    )
                }
                return@Column
            }
            // v0.160.0：两列：分组 | 频道（右列源已移到播放器下方）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // 左列：分组
                LazyColumn(
                    modifier = Modifier
                        .width(96.dp)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(groupNames, key = { it }) { g ->
                        GroupButton(
                            name = g,
                            count = groups[g]?.size ?: 0,
                            selected = g == selectedGroup,
                            onClick = {
                                selectedGroup = g
                                LiveSelection.selectedGroup = g
                            },
                        )
                    }
                }
                // 中列：频道（序号 + 台标 + 名称）
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // v0.133.0：频道 id 由 sourceKey 与 url 拼接，同源重复条目或同名订阅可撞，加下标复合
                    items(
                        items = groupChannelsDistinct.withIndex().toList(),
                        key = { "${it.index}_${it.value.id}" },
                    ) { (idx, ch) ->
                        ChannelCell(
                            index = idx + 1,
                            channel = ch,
                            isPlaying = selected?.name == ch.name,
                            onClick = { playChannel(ch) },
                            onLongClick = { detailChannel = ch },
                            onFavorite = { LiveStore.toggleFavorite(context, ch.id) },
                        )
                    }
                }
            }
            } // v0.96.2 if (!fullscreenPlayer)
        }
    }

    // v0.176.0：DS 式直播设置弹层（顶栏设置+刷新移进来，订阅管理仍走原整页）
    if (showLiveSettings) {
        LiveSettingsSheet(
            sourceKeys = sourceKeys,
            selectedLiveSource = selectedLiveSource,
            globalEpg = globalEpg,
            onDismiss = { showLiveSettings = false },
            onRefresh = { doRefresh() },
            onSelectLiveSource = { key ->
                selectedLiveSource = key
                LiveSelection.selectedLiveSource = key
            },
            onManageClick = {
                showLiveSettings = false
                goingToLivePage = true
                onManageClick()
            },
        )
    }

    // 频道详情（长按）：台标/信息/EPG 当前+后续/收藏/删除
    detailChannel?.let { ch ->
        ChannelDetailSheet(
            channel = ch,
            onDismiss = { detailChannel = null },
            onPlay = { playChannel(ch); detailChannel = null },
            onFavorite = { LiveStore.toggleFavorite(context, ch.id) },
            onDelete = {
                detailChannel = null
                TrashStore.trashLiveManual(context, ch)
                LiveStore.removeManual(context, ch.id)
                scope.launch { LiveStore.refresh(context) }
                AppToast.show(context, "已移入回收站", long = true)
            },
        )
    }
}

/**
 * v0.176.0：DS 式直播设置弹层——对标 DS“直播设置”底部弹层：
 * 标题右上角刷新、两胶囊“切换直播源 / EPG 设置”、源单选列表
 * （手动/接口标签、“切回接口默认直播源”）。订阅的增删、本地导入、
 * 手动加频道仍走原“直播订阅管理”整页，本弹层只给入口，不重复造。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveSettingsSheet(
    sourceKeys: List<String>,
    selectedLiveSource: String?,
    globalEpg: String,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSelectLiveSource: (String?) -> Unit,
    onManageClick: () -> Unit,
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var epgText by remember(globalEpg) { mutableStateOf(globalEpg) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(
            topStart = SoftDimens.CardCorner,
            topEnd = SoftDimens.CardCorner,
        ),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "直播设置",
                    style = SoftType.CardTitle,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新直播源")
                }
            }
            Text(
                text = "切换生效直播源 / 配置 EPG 节目单兜底源",
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceButton(
                    label = "切换直播源",
                    selected = tab == 0,
                    onClick = { tab = 0 },
                )
                SourceButton(
                    label = "EPG 设置",
                    selected = tab == 1,
                    onClick = { tab = 1 },
                )
            }
            Spacer(Modifier.height(12.dp))
            if (tab == 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectLiveSource(null) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "切回接口默认直播源",
                        style = SoftType.Body,
                        fontWeight = if (selectedLiveSource == null) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedLiveSource == null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (sourceKeys.isEmpty()) {
                    Text(
                        text = "暂无直播源",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                sourceKeys.forEach { key ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectLiveSource(key) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedLiveSource == key,
                            onClick = { onSelectLiveSource(key) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = liveSourceLabel(key),
                                style = SoftType.Body,
                                fontWeight = if (selectedLiveSource == key) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = if (key == "manual") "手动" else "接口",
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(
                    text = "全局节目单（兜底）",
                    style = SoftType.CardTitle,
                )
                Text(
                    text = "本源无节目单时用它查正在播 / 下一档；不填则不兜底，不内置任何源",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                AppTextField(
                    value = epgText,
                    onValueChange = { epgText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "https://…/xxx.xml",
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(
                        text = "保存",
                        onClick = {
                            LiveStore.setGlobalEpgUrl(context, epgText)
                            AppToast.show(context, if (epgText.isBlank()) "已清除全局节目单地址" else "已保存全局节目单地址", long = false)
                        },
                        variant = AppButtonVariant.Primary,
                    )
                    AppButton(
                        text = "清除",
                        onClick = {
                            epgText = ""
                            LiveStore.setGlobalEpgUrl(context, "")
                        },
                        variant = AppButtonVariant.Secondary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            AppButton(
                text = "直播订阅管理",
                onClick = onManageClick,
                variant = AppButtonVariant.Secondary,
            )
            Text(
                text = "添加/删除订阅、导入本地、手动加频道走整页",
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * v0.80.0：节目信息卡（对标 DsPlayer 播放器下方那张卡）。
 * 台标 + 正在播 {节目} {开始-结束} / 下一档 {时间} {节目}；无 EPG 时显示频道名。
 */
@Composable
private fun ProgramInfoCard(
    channel: LiveChannel?,
    epg: Pair<EpgFetcher.Program?, EpgFetcher.Program?>?,
    hasEpgSource: Boolean = true,
    // v0.176.0：顶栏去掉后，搜索/收藏/设置移到这张卡右侧（对标 DS 频道头齿轮+爱心）
    isFavorite: Boolean = false,
    onSearchClick: () -> Unit = {},
    onFavoriteClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
) {
    val (now, next) = epg ?: (null to null)
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 台标
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (!channel?.logo.isNullOrBlank()) {
                    AsyncImage(
                        model = channel?.logo,
                        contentDescription = channel?.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        Icons.Filled.Tv, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (now != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "正在播",
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = now.title,
                            style = SoftType.CardTitle,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${timeFmt.format(Date(now.startMs))}-${timeFmt.format(Date(now.stopMs))}",
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (next != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "下一档 ${timeFmt.format(Date(next.startMs))} ${next.title}",
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = channel?.name ?: "暂无频道",
                        style = SoftType.CardTitle,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (!hasEpgSource) "暂无节目单，可在直播设置 EPG 里填全局节目单地址" else "节目单加载中…",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // v0.176.0：顶栏移过来的三个入口，无频道时只留搜索和设置，收藏无台可收
            IconButton(onClick = onSearchClick, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Search, contentDescription = "搜索频道", modifier = Modifier.size(20.dp))
            }
            if (channel != null) {
                IconButton(onClick = onFavoriteClick, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (isFavorite) "取消收藏" else "收藏",
                        tint = if (isFavorite) LocalSoftExtra.current.warning
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconButton(onClick = onSettingsClick, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = "直播设置", modifier = Modifier.size(20.dp))
            }
        }
    }
}

// v0.158.0：直播源显示名（m3u:订阅名 / plugin:文件名 / 手动添加）
private fun liveSourceLabel(key: String): String = when {
    key == "manual" -> "手动添加"
    key.startsWith("m3u:") -> key.removePrefix("m3u:")
    key.startsWith("plugin:") -> key.removePrefix("plugin:")
    else -> key
}

/** v0.80.0：左列分组按钮（选中=主题色药丸）。 */
@Composable
private fun GroupButton(
    name: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 0) "$name" else name,
            style = SoftType.Body,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** v0.80.0：中列频道格（序号 + 台标 + 名称 + 直播角标 + 收藏星）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCell(
    index: Int,
    channel: LiveChannel,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val soft = LocalSoftExtra.current
    val shape = RoundedCornerShape(14.dp)
    // v0.160.0：卡片压紧——上下内边距 8→4dp，台标 36→30dp，卡片右上角只留星标
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else LocalSoftExtra.current.glass,
            )
            .border(
                SoftDimens.BorderWidth,
                if (isPlaying) MaterialTheme.colorScheme.primary else soft.border,
                shape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$index",
            style = SoftType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp),
        )
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.logo.isNotBlank()) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = channel.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    Icons.Filled.Tv, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = SoftType.CardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isPlaying) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            if (channel.group.isNotBlank()) {
                Text(
                    text = channel.group,
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (channel.stale) {
                Text(
                    text = "过期",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(soft.errorSoft.copy(alpha = 0.18f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            Text("直播", color = soft.errorSoft, style = SoftType.Caption)
        }
        IconButton(onClick = onFavorite, modifier = Modifier.size(28.dp)) {
            Icon(
                if (channel.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (channel.favorite) "取消收藏" else "收藏",
                tint = if (channel.favorite) soft.warning
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** v0.80.0：右列源按钮（源1/源2…，选中=主题色）。 */
@Composable
private fun SourceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = SoftType.Body,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelDetailSheet(
    channel: LiveChannel,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var epg by remember(channel.id) { mutableStateOf<Pair<EpgFetcher.Program?, EpgFetcher.Program?>?>(null) }
    var epgLoading by remember(channel.id) { mutableStateOf(false) }
    val globalEpgDetail by LiveStore.globalEpg.collectAsState()

    LaunchedEffect(channel.id, globalEpgDetail) {
        val src = channel.epgUrl.ifBlank { globalEpgDetail }
        if (src.isNotBlank()) {
            epgLoading = true
            epg = EpgFetcher.nowAndNext(src, channel.name)
            epgLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(
            topStart = SoftDimens.CardCorner,
            topEnd = SoftDimens.CardCorner,
        ),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (channel.logo.isNotBlank()) {
                        AsyncImage(
                            model = channel.logo,
                            contentDescription = channel.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Icon(Icons.Filled.Tv, contentDescription = null)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        channel.name,
                        style = SoftType.CardTitle,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        (channel.group.ifBlank { "未分组" }) + " · " +
                            when {
                                channel.sourceKey.startsWith("manual") -> "手动添加"
                                channel.sourceKey.startsWith("m3u:") -> "订阅：" + channel.sourceKey.removePrefix("m3u:")
                                else -> "本地源"
                            },
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (channel.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "收藏",
                        tint = if (channel.favorite) LocalSoftExtra.current.warning
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // EPG：当前 + 后续（尽力展示）
            if (channel.epgUrl.isNotBlank() || globalEpgDetail.isNotBlank()) {
                Text(
                    "节目单",
                    style = SoftType.CardTitle,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                when {
                    epgLoading -> Text("加载中…", style = SoftType.Caption)
                    epg == null || (epg?.first == null && epg?.second == null) ->
                        Text("暂无节目数据", style = SoftType.Caption)
                    else -> {
                        epg?.first?.let { p ->
                            Text(
                                "正在播：${p.title}（${timeFmt.format(Date(p.startMs))}–${timeFmt.format(Date(p.stopMs))}）",
                                style = SoftType.Body,
                            )
                        }
                        epg?.second?.let { p ->
                            Text(
                                "接下来：${p.title}（${timeFmt.format(Date(p.startMs))}）",
                                style = SoftType.Body,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(
                    text = "播放",
                    onClick = onPlay,
                    modifier = Modifier.weight(1f),
                    variant = AppButtonVariant.Primary,
                )
                AppButton(
                    text = "复制地址",
                    onClick = {
                        clipboard.setText(AnnotatedString(channel.url))
                        AppToast.show(context, "播放地址已复制", long = false)
                    },
                    variant = AppButtonVariant.Secondary,
                )
                if (channel.manual) {
                    AppButton(
                        text = "删除",
                        onClick = onDelete,
                        variant = AppButtonVariant.Ghost,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 读 content Uri 的显示文件名，失败返回 null */
private fun displayNameOf(resolver: ContentResolver, uri: Uri): String? {
    resolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
    }
    return null
}

/** 直播订阅管理：M3U 订阅 URL / 本地 M3U 导入 / 手动添加频道 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveManageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by LiveStore.sources.collectAsState()
    var showAddSub by remember { mutableStateOf(false) }
    var showAddChannel by remember { mutableStateOf(false) }
    // v0.158.0：全局节目单兜底地址（用户自填，不内置源）
    var globalEpgText by remember { mutableStateOf(LiveStore.globalEpgUrl(context)) }
    var refreshing by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            refreshing = true
            val ok = try {
                val name = displayNameOf(context.contentResolver, uri)
                    ?.substringBeforeLast('.')?.ifBlank { null } ?: "本地M3U"
                val tmp = File(context.cacheDir, "m3u_import_${System.currentTimeMillis()}.m3u")
                val ins = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法读取文件")
                ins.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val done = LiveStore.importLocalFile(context, tmp, name)
                tmp.delete()
                done
            } catch (e: Exception) {
                LogBus.e("Live", "导入本地 M3U 失败: ${e.message}")
                false
            }
            AppToast.show(context, if (ok) "已导入本地 M3U" else "导入失败", long = false)
            refreshing = false
        }
    }

    Scaffold(
        topBar = {
            TabTopBar(
                title = "直播订阅管理",
                onBack = onBack,
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
        ) {
            item {
                // v0.174.0：11 条一键导入入口已删（用户要求：本地导入本来就有，
                // 不需要这个定制入口）。已导入的订阅不动，仍在下面列表里逐条可删。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppButton(
                        text = "添加订阅",
                        onClick = { showAddSub = true },
                        modifier = Modifier.weight(1f),
                        variant = AppButtonVariant.Primary,
                    )
                    // v0.174.0：三按钮统一四字，单行不换行。之前“导入本地 M3U”
                    // （7 字）和“手动加频道”（5 字）在三分之一宽的按钮里被
                    // AppButton 默认内边距挤成两行（截图里“手动加频/道”断行），
                    // “添加订阅”四字本来就是单行；改短后三格齐平，含义不变
                    // （点开仍是本地 M3U 文件导入 / 手动添加频道弹窗）。
                    AppButton(
                        text = "导入本地",
                        onClick = { filePicker.launch("*/*") },
                        modifier = Modifier.weight(1f),
                        variant = AppButtonVariant.Secondary,
                    )
                    AppButton(
                        text = "手动添加",
                        onClick = { showAddChannel = true },
                        modifier = Modifier.weight(1f),
                        variant = AppButtonVariant.Secondary,
                    )
                }
                if (refreshing) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                }
            }
            item {
                // v0.158.0：全局节目单兜底——本源无节目单时用它查正在播/下一档；
                // 填 XMLTV 地址，.xml.gz 尽力解压，解析失败显示暂无节目数据
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "全局节目单（兜底）",
                            style = SoftType.CardTitle,
                        )
                        Text(
                            "本源无节目单时用它查正在播 / 下一档；不填则不兜底，不内置任何源",
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        AppTextField(
                            value = globalEpgText,
                            onValueChange = { globalEpgText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = "https://…/xxx.xml",
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton(
                                text = "保存",
                                onClick = {
                                    LiveStore.setGlobalEpgUrl(context, globalEpgText)
                                    AppToast.show(context, if (globalEpgText.isBlank()) "已清除全局节目单地址" else "已保存全局节目单地址", long = false)
                                },
                                variant = AppButtonVariant.Primary,
                            )
                            AppButton(
                                text = "清除",
                                onClick = {
                                    globalEpgText = ""
                                    LiveStore.setGlobalEpgUrl(context, "")
                                },
                                variant = AppButtonVariant.Secondary,
                            )
                        }
                    }
                }
            }
            if (sources.isEmpty()) {
                item {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.Tv,
                        title = "暂无订阅",
                        hint = "可添加 M3U 订阅地址（如公开 IPTV 源）",
                    )
                }
            }
            // v0.133.0：订阅源写侧按 url 查重，但持久化文件读侧无去重，渲染前兜底防同 url 撞 key
            items(sources.distinctBy { it.url }, key = { it.url }) { s ->
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                s.name,
                                style = SoftType.CardTitle,
                            )
                            Text(
                                if (s.type == LiveSource.LOCAL_FILE) "本地文件" else s.url,
                                style = SoftType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            LiveStore.removeSource(context, s.url)
                            scope.launch { LiveStore.refresh(context) }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除订阅")
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showAddSub) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var err by remember { mutableStateOf(false) }
        TechDialog(
            onDismissRequest = { showAddSub = false },
            title = "添加 M3U 订阅",
            confirmText = "确定",
            onConfirm = {
                val u = url.trim()
                if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                    err = true
                    return@TechDialog
                }
                if (LiveStore.addSource(context, name.ifBlank { "M3U订阅" }, LiveSource.M3U_URL, u)) {
                    showAddSub = false
                    scope.launch { LiveStore.refresh(context) }
                } else {
                    AppToast.show(context, "该订阅已存在", long = false)
                }
            },
        ) {
                Column {
                    Text(
                        text = "名称",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = name, onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "订阅地址",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = url,
                        onValueChange = { url = it; err = false },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "https://…/xxx.m3u",
                    )
                    if (err) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "请输入 http(s) 开头的地址",
                            style = SoftType.Caption,
                            color = LocalSoftExtra.current.errorSoft,
                        )
                    }
                }
        }
    }

    if (showAddChannel) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var logo by remember { mutableStateOf("") }
        var err by remember { mutableStateOf(false) }
        TechDialog(
            onDismissRequest = { showAddChannel = false },
            title = "手动添加频道",
            confirmText = "确定",
            onConfirm = {
                val u = url.trim()
                if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                    err = true
                    return@TechDialog
                }
                if (LiveStore.addManual(context, name.ifBlank { "未命名频道" }, u, logo)) {
                    showAddChannel = false
                    scope.launch { LiveStore.refresh(context) }
                } else {
                    AppToast.show(context, "该频道已存在", long = false)
                }
            },
        ) {
                Column {
                    Text(
                        text = "频道名",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = name, onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "播放地址",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = url,
                        onValueChange = { url = it; err = false },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "https://…/xxx.m3u8",
                    )
                    if (err) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "请输入 http(s) 开头的地址",
                            style = SoftType.Caption,
                            color = LocalSoftExtra.current.errorSoft,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "台标地址（可选）",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = logo, onValueChange = { logo = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
        }
    }
}
