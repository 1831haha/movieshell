package com.movieshell.ui.detail

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.TechTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.movieshell.data.download.DownloadManager
import com.movieshell.data.model.Episode
import com.movieshell.data.model.PlayLine
import com.movieshell.data.model.VodDetail
import com.movieshell.data.repo.DetailSiteHolder
import com.movieshell.data.repo.DownloadSettings
import com.movieshell.data.repo.DownloadTask
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.FavoriteItem
import com.movieshell.data.repo.FavoriteStore
import com.movieshell.data.repo.Playlist
import com.movieshell.data.repo.PlaylistStore
import com.movieshell.data.repo.FollowItem
import com.movieshell.data.repo.FollowStore
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SiteRepository
import com.movieshell.data.repo.StoragePaths
import com.movieshell.player.MagnetPlayer
import com.movieshell.player.PlaySession
import com.movieshell.player.PlayerScreen
import com.movieshell.player.resolvePlayUrl
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppChip
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.GradientBlock
import com.movieshell.ui.components.LoadingSkeleton
import com.movieshell.ui.components.SectionTitle
import com.movieshell.ui.data.Vod
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    vodId: String,
    // v0.47.0：列表页带过来的标题，加载中顶栏先显示它，不再显示裸 vodId（如 50767）
    initialTitle: String = "",
    // v0.75.0：继续观看带过来的起播集（-1=不指定，走断点续播/默认逻辑）
    initialEpisode: Int = -1,
    onBack: () -> Unit,
    onPlay: (playUrl: String, title: String) -> Unit = { _, _ -> },
    // v0.87.0：点演员名搜其作品
    onActorClick: (actorName: String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- drpy / PY 规则接入（drpy 优先，其次 PY） ----
    // v0.5.1：从订阅站源点进来时带站点上下文（DetailSiteHolder），详情/播放按站走引擎；
    // 否则走本地规则旧逻辑。
    val subSite = remember { DetailSiteHolder.site }
    val drpyActive = remember {
        DrpySourceManager.hasActiveRule() || PySourceManager.hasActiveRule()
    }
    var detailLoading by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<VodDetail?>(null) }
    // v0.27.6：抽成函数，失败态的"重试"按钮可复用
    // v0.47.0：20 秒超时——规则 hang 住时不再无限骨架屏，转失败态可重试
    fun reloadDetail() {
        scope.launch {
            detailLoading = true
            detail = runCatching {
                withTimeout(20_000) {
                    if (subSite != null) {
                        SiteRepository().detail(subSite, vodId)
                    } else if (drpyActive) {
                        if (DrpySourceManager.hasActiveRule()) DrpySourceManager.detail(vodId)
                        else if (PySourceManager.hasActiveRule()) PySourceManager.detail(vodId)
                        else VodDetail(vodId = vodId)
                    } else {
                        null
                    }
                }
            }.getOrNull()
            detailLoading = false
        }
    }
    LaunchedEffect(vodId) { reloadDetail() }
    // 真实选集：vodPlayFrom/vodPlayUrl 对齐
    val episodes: List<Episode> = remember(detail) {
        detail?.let { SiteRepository().parseEpisodes(it) }.orEmpty()
    }
    // 多线路：按 Episode.flag 分组；只有一条线路时 playLines.size==1，不显示 Tab
    val playLines: List<PlayLine> = remember(episodes) {
        episodes.groupBy { it.flag.ifBlank { "默认线路" } }
            .map { (name, eps) -> PlayLine(name, eps) }
    }
    var selectedLine by remember { mutableIntStateOf(0) }
    val safeLine = if (playLines.isNotEmpty()) selectedLine.coerceIn(playLines.indices) else 0
    val lineEpisodes: List<Episode> = playLines.getOrNull(safeLine)?.episodes.orEmpty()

    // v0.27.6：detail 失败时不再回退 DemoData 假数据（曾冒出"星海追光者"），直接为 null 走失败态
    val vod: Vod? = remember(detail) {
        detail?.let {
            Vod(
                id = it.vodId.ifBlank { vodId },
                // v0.27.1：detail 失败/回退时不展示裸 `@@` 打包串和纯 URL
                title = it.vodName.ifBlank { displayTitle(vodId) },
                category = it.typeName,
                year = it.vodYear,
                rating = it.vodRemarks,
                area = "",
                actors = it.vodActor.ifBlank { "未知" },
                desc = it.vodDesc.ifBlank { "暂无简介" },
                episodes = episodes.size,
            )
        }
    }

    var selectedEpisode by remember { mutableIntStateOf(0) }
    // v0.77.0：跳集弹窗
    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    // v0.47.0：简介默认折叠
    var descExpanded by remember { mutableStateOf(false) }
    var showMagnetDialog by remember { mutableStateOf(false) }
    // v0.21.0：封面点开放大
    var showCoverDialog by remember { mutableStateOf(false) }
    val coverUrl = detail?.vodPic.orEmpty()

    /** v0.21.0：影片信息一键复制 */
    // 当前来源名：收藏时记下来，收藏列表里展示；订阅站同时记 site key 以便回跳
    val sourceName = remember {
        when {
            subSite != null -> subSite.name
            DrpySourceManager.hasActiveRule() ->
                DrpySourceManager.activeRuleName()?.removeSuffix(".js")
                    ?.ifBlank { "drpy规则" } ?: "drpy规则"
            PySourceManager.hasActiveRule() ->
                PySourceManager.activeRuleName()?.removeSuffix(".py")
                    ?.ifBlank { "PY规则" } ?: "PY规则"
            else -> "站源"
        }
    }
    var favorite by remember { mutableStateOf(false) }
    LaunchedEffect(vodId, sourceName) {
        favorite = FavoriteStore.isFavorite(context, vodId, sourceName)
    }
    // v0.69.0：收藏时选片单
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var pickerPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

    /** v0.69.0：收藏进指定片单（playlistId null = 未分组）。 */
    suspend fun favoriteAddToPlaylist(ctx: Context, playlistId: String?) {
        FavoriteStore.add(
            ctx,
            FavoriteItem(
                vodId = vodId,
                name = vod?.title ?: displayTitle(vodId),
                pic = detail?.vodPic.orEmpty(),
                sourceName = sourceName,
                savedAt = System.currentTimeMillis(),
                subSiteKey = subSite?.key.orEmpty(),
                playlistId = playlistId,
            ),
        )
        Toast.makeText(ctx, "已收藏", Toast.LENGTH_SHORT).show()
        favorite = FavoriteStore.isFavorite(ctx, vodId, sourceName)
    }

    // v0.26.0：追剧
    val followSourceType = remember(subSite) {
        when {
            subSite != null -> "sub"
            DrpySourceManager.hasActiveRule() -> "drpy"
            PySourceManager.hasActiveRule() -> "py"
            else -> "sub"
        }
    }
    val followSourceKey = remember(subSite, sourceName) {
        subSite?.key.orEmpty().ifBlank {
            DrpySourceManager.activeRuleName().orEmpty()
                .ifBlank { PySourceManager.activeRuleName().orEmpty() }
        }.ifBlank { sourceName }
    }
    val followId = remember(vodId, followSourceKey) { FollowStore.idOf(followSourceKey, vodId) }
    var following by remember { mutableStateOf(false) }
    LaunchedEffect(followId) { following = FollowStore.isFollowing(context, followId) }
    // v0.71.0：追剧已看标记——看到第几集（-1=没看过）
    var watchedEp by remember { mutableIntStateOf(-1) }
    // v0.73.0：断点续播——已读完"看到第几集"才能决定从哪集起播，自动播放等它
    var resumeReady by remember { mutableStateOf(false) }
    LaunchedEffect(followId, following) {
        watchedEp = if (following) FollowStore.getWatchedEpisode(context, followId) else -1
        resumeReady = true
    }

    // v0.71.0：带已看角标的选集 chip（追剧且看到该集时右上角 ✓）
    @Composable
    fun EpisodeChip(index: Int, name: String, selected: Boolean, onClick: () -> Unit) {
        Box {
            AppChip(text = name, selected = selected, onClick = onClick)
            if (following && index <= watchedEp) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "已看",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 5.dp, y = (-5).dp)
                        .size(15.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
    }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showOnlineSubtitle by remember { mutableStateOf(false) } // v0.28.0：在线字幕搜索
    // v0.49.0：SubHD 内置，入口常显（v0.30.0 的插件门禁删除）

    /** 统一播放决策（v0.19.0）：解析走公共 resolvePlayUrl；磁力直接起播不进播放器 */
    // v0.65：嵌入播放器状态（详情页顶部 16:9 直接播）
    var embeddedUrl by remember { mutableStateOf("") }
    var embeddedTitle by remember { mutableStateOf("") }
    // v0.65：当前播放的线路/集数（高亮跟随）
    var playingLine by remember { mutableIntStateOf(0) }
    var playingEp by remember { mutableIntStateOf(0) }

    fun playEpisode(ep: Episode, lineIndex: Int, episodeIndex: Int) {
        LogBus.i("UI", "点击播放: ${ep.name.ifBlank { "未命名剧集" }} url=${ep.url.take(100)}")
        // v0.26.0：追剧看到第几集联动
        if (following) {
            scope.launch {
                FollowStore.markWatched(context, followId, episodeIndex)
                // v0.71.0：已看角标实时跟上
                watchedEp = maxOf(watchedEp, episodeIndex)
            }
        }
        scope.launch {
            if (MagnetPlayer.isMagnet(ep.url)) {
                val ok = runCatching {
                    MagnetPlayer.playIfReady(context, ep.url, vod?.title ?: displayTitle(vodId))
                }.getOrDefault(false)
                // 服务通则已直接起播；不通则弹窗提示
                if (!ok) showMagnetDialog = true
            } else {
                val link = resolvePlayUrl(context, subSite, ep)
                // v0.19.0：写入播放会话，供播放器内切线路 / 失败自动重试
                PlaySession.start(playLines, lineIndex, episodeIndex) { ctx, e ->
                    resolvePlayUrl(ctx, subSite, e)
                }
                // v0.65：不再跳独立播放页，嵌入播放器直接播
                playingLine = lineIndex
                playingEp = episodeIndex
                embeddedTitle = "${vod?.title ?: displayTitle(vodId)} ${ep.name.ifBlank { "第${episodeIndex + 1}集" }}"
                embeddedUrl = link
            }
        }
    }

    val safeSelected = if (lineEpisodes.isNotEmpty()) {
        selectedEpisode.coerceIn(0, lineEpisodes.size - 1)
    } else selectedEpisode
    // v0.65：剧集高亮跟随实际播放（嵌入播放器内切集时同步）
    val highlightEp = if (embeddedUrl.isNotBlank()) playingEp else selectedEpisode
    // v0.62：进详情页自动播放（用户要求，不需要点播放键）
    // v0.73.0：断点续播——追剧的剧从"看到第几集"继续播，而不是永远第1集
    // v0.75.0：继续观看带集数进来时优先用它
    var autoPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(detail, lineEpisodes, resumeReady) {
        if (!autoPlayed && detail != null && lineEpisodes.isNotEmpty() && resumeReady) {
            autoPlayed = true
            val startEp = when {
                initialEpisode >= 0 -> initialEpisode.coerceIn(lineEpisodes.indices)
                following && watchedEp >= 0 -> watchedEp.coerceIn(lineEpisodes.indices)
                else -> safeSelected
            }
            selectedEpisode = startEp
            playEpisode(lineEpisodes[startEp], safeLine, startEp)
        }
    }
    val playLabel = when {
        lineEpisodes.isNotEmpty() ->
            "播放 ${lineEpisodes[safeSelected].name.ifBlank { "第${safeSelected + 1}集" }}"
        vod?.episodes?.let { it > 1 } == true -> "播放 第${selectedEpisode + 1}集"
        else -> "立即播放"
    }

    Scaffold(
        topBar = {
            // v0.91.0：顶栏统一为 TabTopBar（与各 tab 一致；收藏已在信息区，顶栏只留追剧 + 分享占位）
            TabTopBar(
                title = vod?.title ?: initialTitle.ifBlank { displayTitle(vodId) },
                onBack = onBack,
                actions = {
                    // v0.65：追剧开关
                    IconButton(
                        onClick = {
                            scope.launch {
                                if (following) {
                                    if (FollowStore.unfollow(context, followId)) {
                                        following = false
                                        Toast.makeText(context, "已取消追剧", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    val ok = FollowStore.follow(
                                        context,
                                        FollowItem(
                                            id = followId,
                                            vodId = vodId,
                                            name = vod?.title ?: displayTitle(vodId),
                                            pic = detail?.vodPic.orEmpty(),
                                            sourceType = followSourceType,
                                            sourceKey = followSourceKey,
                                            episodeCount = episodes.size,
                                        ),
                                    )
                                    if (ok) {
                                        following = true
                                        Toast.makeText(context, "已追剧，有更新会提醒你", Toast.LENGTH_SHORT)
                                            .show()
                                    }
                                }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (following) Icons.Filled.PlaylistAddCheck
                            else Icons.Filled.PlaylistAdd,
                            contentDescription = "追剧",
                            tint = if (following) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    // 占位：分享
                    IconButton(onClick = { }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = "分享",
                        )
                    }
                },
            )
        },
    ) { inner ->
        if ((subSite != null || drpyActive) && detailLoading && detail == null) {
            // 详情加载中：骨架屏占位，不转菊花
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(SoftDimens.PagePadding),
                verticalArrangement = Arrangement.spacedBy(SoftDimens.CardSpacing),
            ) {
                LoadingSkeleton(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                LoadingSkeleton(
                    modifier = Modifier.fillMaxWidth(0.55f).height(22.dp),
                    shape = RoundedCornerShape(6.dp),
                )
                LoadingSkeleton(
                    modifier = Modifier.fillMaxWidth().height(14.dp),
                    shape = RoundedCornerShape(6.dp),
                )
                LoadingSkeleton(
                    modifier = Modifier.fillMaxWidth(0.85f).height(14.dp),
                    shape = RoundedCornerShape(6.dp),
                )
                LoadingSkeleton(
                    modifier = Modifier.fillMaxWidth(0.7f).height(14.dp),
                    shape = RoundedCornerShape(6.dp),
                )
            }
        } else if (detail == null) {
            // v0.27.6：详情加载失败。不再回退 DemoData 假数据（曾冒出"星海追光者"），给真失败态 + 重试
            EmptyState(
                kind = EmptyKind.Error,
                icon = Icons.Filled.ErrorOutline,
                title = "详情加载失败",
                hint = "规则返回异常或网络超时",
                actionText = "重试",
                onAction = { reloadDetail() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
            )
        } else {
            // vod 非空才进内容区；内层 vod 遮蔽外层可空引用，调用点零改动
            vod?.let { vod ->
                // v0.68：小窗悬浮——播放器滚出屏幕后缩到右下角继续播
                val listState = rememberLazyListState()
                val playerOffScreen by remember {
                    derivedStateOf { listState.firstVisibleItemIndex > 0 }
                }
                val showMiniPlayer = playerOffScreen && embeddedUrl.isNotBlank()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner),
                ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // v0.65：顶部嵌入播放器（16:9）——详情就绪自动播第一集
                    // v0.70.1：此处只留占位，播放器本体常驻外层 Box（单父容器），
                    // 跟着列表滚动偏移量同步上移，避免 movableContentOf 跨父容器搬家崩溃
                    item {
                        if (embeddedUrl.isNotBlank()) {
                            Spacer(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f),
                            )
                        } else {
                            GradientBlock(
                                key = vod.id,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clickable(
                                        enabled = coverUrl.isNotBlank(),
                                        onClick = { showCoverDialog = true },
                                    ),
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    // 真实封面（Coil，无封面时保留渐变占位）
                                    if (coverUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = coverUrl,
                                            contentDescription = "封面",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                }
                            }
                        }
                    }
    
                    // 标题与元信息（v0.39.0：毛玻璃卡片化，mockup 风格）
                    // v0.65：信息区按目标界面重构——海报+标题+标签+收藏 / 源pill / 简介 / 操作行
                    // v0.78.2：卡片与播放器同宽（去横向边距），顶部圆角 20dp 做衔接过渡
                    item {
                        AppCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        ) {
                            Column(modifier = Modifier.padding(SoftDimens.PagePadding)) {
                                // 第一行：海报缩略图 + 标题 + 标签 + 收藏
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (coverUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = coverUrl,
                                            contentDescription = "海报",
                                            modifier = Modifier
                                                .size(width = 64.dp, height = 88.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { showCoverDialog = true },
                                            contentScale = ContentScale.Crop,
                                        )
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = vod.title,
                                            style = SoftType.CardTitle,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            MetaChip("${vod.episodes}集")
                                            MetaChip(vod.year)
                                            MetaChip(vod.area)
                                        }
                                        // v0.84.0：源胶囊上移到缩略图右边（紧凑版）
                                        if (playLines.isNotEmpty()) {
                                            Spacer(Modifier.height(6.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.padding(
                                                            horizontal = 8.dp,
                                                            vertical = 3.dp,
                                                        ),
                                                    ) {
                                                        Text(
                                                            text = playLines[safeLine].name,
                                                            style = SoftType.Caption,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        )
                                                        Spacer(Modifier.width(3.dp))
                                                        Icon(
                                                            Icons.Filled.SwapHoriz,
                                                            contentDescription = "切换源",
                                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            modifier = Modifier.size(13.dp),
                                                        )
                                                    }
                                                }
                                                if (vod.rating.isNotBlank()) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Icon(
                                                        imageVector = Icons.Filled.Star,
                                                        contentDescription = null,
                                                        tint = LocalSoftExtra.current.warning,
                                                        modifier = Modifier.size(13.dp),
                                                    )
                                                    Spacer(Modifier.width(3.dp))
                                                    Text(
                                                        text = vod.rating,
                                                        style = SoftType.Caption,
                                                        color = LocalSoftExtra.current.warning,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    // 收藏（从顶栏移到这里，对标目标界面右上角心形）
                                    IconButton(onClick = {
                                        scope.launch {
                                            if (favorite) {
                                                FavoriteStore.remove(context, vodId, sourceName)
                                                Toast.makeText(context, "已取消收藏", Toast.LENGTH_SHORT).show()
                                                favorite = FavoriteStore.isFavorite(context, vodId, sourceName)
                                            } else {
                                                // v0.69.0：有片单时先选片单，否则直接收藏进未分组
                                                pickerPlaylists = PlaylistStore.list(context)
                                                if (pickerPlaylists.isEmpty()) {
                                                    favoriteAddToPlaylist(context, null)
                                                } else {
                                                    showPlaylistPicker = true
                                                }
                                            }
                                        }
                                    }) {
                                        Icon(
                                            imageVector = if (favorite) Icons.Filled.Favorite
                                            else Icons.Filled.FavoriteBorder,
                                            contentDescription = "收藏",
                                            tint = if (favorite) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                // v0.84.0：源 pill 已上移到缩略图右边，这里删除
                                // 第三行：主演（v0.87.0：演员名可点，跳搜索找其作品）+ 简介（可展开）；v0.84.0：收窄
                                if (vod.actors.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    val actorNames = vod.actors.split("、", "/", "，", ",", " ")
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                    val actorText = buildAnnotatedString {
                                        append("主演：")
                                        actorNames.forEachIndexed { i, name ->
                                            if (i > 0) append("、")
                                            pushStringAnnotation(tag = "actor", annotation = name)
                                            withStyle(
                                                SpanStyle(color = MaterialTheme.colorScheme.primary),
                                            ) { append(name) }
                                            pop()
                                        }
                                    }
                                    ClickableText(
                                        text = actorText,
                                        style = SoftType.Body.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        onClick = { offset ->
                                            actorText.getStringAnnotations("actor", offset, offset)
                                                .firstOrNull()?.let { onActorClick(it.item) }
                                        },
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                // v0.86.0：简介标签与内容同一行（"简介："加粗前缀+正文流排），点击展开/收起
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier.clickable { descExpanded = !descExpanded },
                                ) {
                                    Text(
                                        text = buildAnnotatedString {
                                            withStyle(
                                                SpanStyle(fontWeight = FontWeight.Bold),
                                            ) { append("简介：") }
                                            append(vod.desc)
                                        },
                                        style = SoftType.Body,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 19.sp, // v0.84.0：收窄行距
                                        maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        imageVector = if (descExpanded) Icons.Filled.ExpandLess
                                        else Icons.Filled.ExpandMore,
                                        contentDescription = if (descExpanded) "收起" else "展开",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                // 第四行：操作图标（复制链接 / 下载 / 刷新 / 字幕）
                                Spacer(Modifier.height(8.dp))
                                // v0.69.1：操作图标下方加炫彩小字
                                val rainbowBrush = remember {
                                    Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF22D3EE),
                                            Color(0xFF818CF8),
                                            Color(0xFFE879F9),
                                            Color(0xFFFBBF24),
                                        ),
                                    )
                                }

                                @Composable
                                fun ActionIcon(
                                    label: String,
                                    onClick: () -> Unit,
                                    enabled: Boolean = true,
                                    icon: @Composable () -> Unit,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        IconButton(onClick = onClick, enabled = enabled) {
                                            icon()
                                        }
                                        Text(
                                            text = label,
                                            style = TextStyle(
                                                brush = rainbowBrush,
                                                fontSize = 10.sp,
                                            ),
                                            maxLines = 1,
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    ActionIcon(label = "复制链接", onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as android.content.ClipboardManager
                                        cm.setPrimaryClip(
                                            android.content.ClipData.newPlainText(
                                                "播放链接", embeddedUrl.ifBlank { playLabel }
                                            )
                                        )
                                        Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Filled.Link, contentDescription = "复制链接")
                                    }
                                    ActionIcon(
                                        label = "下载",
                                        onClick = { showDownloadDialog = true },
                                        enabled = lineEpisodes.isNotEmpty(),
                                    ) {
                                        Icon(Icons.Filled.Download, contentDescription = "下载")
                                    }
                                    ActionIcon(label = "刷新", onClick = { reloadDetail() }) {
                                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                                    }
                                    ActionIcon(label = "字幕", onClick = { showOnlineSubtitle = true }) {
                                        Icon(Icons.Filled.Subtitles, contentDescription = "在线字幕")
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
    
                    // v0.65：播放按钮行已移除（顶部嵌入播放器自动播，对标目标界面）
    
                    // 选集：drpy 真实选集优先；多条线路时上方显示线路 TabRow
                    when {
                        lineEpisodes.isNotEmpty() -> {
                            if (playLines.size > 1) {
                                item {
                                    // v0.86.0："1/8"集数徽标去掉（多余且用户嫌丑；集数信息由"选集（共N集）"+高亮集承担）
                                    ScrollableTabRow(
                                        selectedTabIndex = safeLine,
                                        edgePadding = SoftDimens.PagePadding,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        playLines.forEachIndexed { i, line ->
                                            Tab(
                                                selected = safeLine == i,
                                                onClick = {
                                                    selectedLine = i
                                                    selectedEpisode = 0
                                                },
                                                text = { Text(line.name) },
                                            )
                                        }
                                    }
                                }
                            }
                            item {
                                SectionTitle(
                                    title = "选集（共 ${lineEpisodes.size} 集）",
                                    modifier = Modifier.padding(
                                        horizontal = SoftDimens.PagePadding,
                                        vertical = 8.dp,
                                    ),
                                    // v0.77.0：集数多时给"跳集"入口，短剧 60-80 集不用手翻
                                    action = if (lineEpisodes.size > 20) {
                                        {
                                            TextButton(onClick = { showJumpDialog = true }) {
                                                Text("跳集", fontSize = 13.sp)
                                            }
                                        }
                                    } else null,
                                )
                            }
                            item {
                                // v0.68：选集改可滚动网格，自动定位到正在播的集
                                val epGridState = rememberLazyGridState()
                                LaunchedEffect(highlightEp, lineEpisodes.size) {
                                    if (lineEpisodes.isNotEmpty()) {
                                        runCatching {
                                            epGridState.scrollToItem(highlightEp.coerceIn(0, lineEpisodes.size - 1))
                                        }
                                    }
                                }
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 84.dp),
                                    state = epGridState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = SoftDimens.PagePadding)
                                        .heightIn(max = 320.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(lineEpisodes.size) { i ->
                                        val ep = lineEpisodes[i]
                                        // v0.71.0：已看角标
                                        EpisodeChip(
                                            index = i,
                                            name = ep.name.ifBlank { "第${i + 1}集" },
                                            // v0.65：高亮跟随实际播放
                                            selected = highlightEp == i,
                                            // v0.65：点选集直接播（对标目标界面）
                                            onClick = { playEpisode(ep, safeLine, i) },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                        vod.episodes > 1 -> {
                            item {
                                SectionTitle(
                                    title = "选集（共 ${vod.episodes} 集）",
                                    modifier = Modifier.padding(
                                        horizontal = SoftDimens.PagePadding,
                                        vertical = 8.dp,
                                    ),
                                )
                            }
                            item {
                                FlowRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = SoftDimens.PagePadding),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    repeat(vod.episodes) { i ->
                                        // v0.71.0：已看角标
                                        EpisodeChip(
                                            index = i,
                                            name = "第${i + 1}集",
                                            // v0.65：高亮跟随实际播放
                                            selected = highlightEp == i,
                                            onClick = { selectedEpisode = i },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                        else -> {
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                    }

                    // v0.58：简介已并入主演卡片，独立简介卡删除
                    item {
                        Spacer(Modifier.height(24.dp))
                        Spacer(Modifier.height(24.dp))
                    }
                }

                // v0.68：悬浮小窗——播放器滚出屏幕后右下角继续播
                // v0.70.1：播放器常驻外层 Box（单父容器），只切换位置/尺寸——
                // 未小窗时盖在列表顶部占位上并跟随滚动偏移同步上移，
                // 不再用 movableContentOf 跨父容器搬家（后者在 Lazy item 被回收时
                // 触发 "Cannot insert ... because it already has a parent" 崩溃）
                if (embeddedUrl.isNotBlank()) {
                    val mini = showMiniPlayer
                    Box(
                        modifier = Modifier
                            .then(
                                if (mini) {
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(16.dp)
                                        .width(200.dp)
                                } else {
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .fillMaxWidth()
                                        .offset {
                                            IntOffset(
                                                0,
                                                -listState.firstVisibleItemScrollOffset,
                                            )
                                        }
                                },
                            )
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(if (mini) 12.dp else 0.dp)),
                    ) {
                        PlayerScreen(
                            url = embeddedUrl,
                            title = embeddedTitle,
                            vodId = vodId,
                            onBack = onBack,
                            embedded = true,
                            onFullscreen = { onPlay(embeddedUrl, embeddedTitle) },
                            onEpisodeChange = { lineIdx, epIdx ->
                                playingLine = lineIdx
                                playingEp = epIdx
                            },
                        )
                        if (mini) {
                            // 回到顶部
                            IconButton(
                                onClick = { scope.launch { listState.scrollToItem(0) } },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .size(32.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                            ) {
                                Icon(
                                    Icons.Filled.ExpandLess,
                                    contentDescription = "回到顶部",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            // 关闭小窗（停止播放）
                            IconButton(
                                onClick = { embeddedUrl = "" },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(32.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "关闭",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
                } // Box：内容区 + 悬浮小窗
            }
        }
    
        // 磁力转播服务未启动提示
        if (showMagnetDialog) {
            TechDialog(
                onDismissRequest = { showMagnetDialog = false },
                title = "磁力转播服务未启动",
                confirmText = "知道了",
                onConfirm = { showMagnetDialog = false },
            ) {
                Text("未检测到本机磁力转播服务（127.0.0.1:17890），请先在 DsPlayer 的服务页启动 magnet-stream 后再试")
            }
        }
    
        // v0.28.0：在线字幕搜索（下载后按剧记忆，播放时自动挂载）
        if (showOnlineSubtitle) {
            com.movieshell.ui.subtitle.OnlineSubtitleSearchDialog(
                keyword = vod?.title.orEmpty().ifBlank { displayTitle(vodId) },
                vodId = vodId,
                onPick = {
                    showOnlineSubtitle = false
                    android.widget.Toast.makeText(
                        context,
                        "字幕已保存，播放时自动挂载",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
                onDismiss = { showOnlineSubtitle = false },
            )
        }

        // v0.26.0：下载选集（多选 → 解析地址 → 入队）
        if (showDownloadDialog) {
            var selected by remember { mutableStateOf(lineEpisodes.indices.toSet()) }
            var resolving by remember { mutableStateOf(false) }
            Dialog(onDismissRequest = { if (!resolving) showDownloadDialog = false }) {
                AppCard {
                    Column(modifier = Modifier.padding(SoftDimens.PagePadding)) {
                        Text(
                            text = "选择要下载的剧集",
                            style = SoftType.CardTitle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(lineEpisodes.size) { i ->
                                val ep = lineEpisodes[i]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !resolving) {
                                            selected = if (selected.contains(i)) selected - i else selected + i
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = selected.contains(i),
                                        onCheckedChange = null,
                                        enabled = !resolving,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = ep.name.ifBlank { "第${i + 1}集" },
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (MagnetPlayer.isMagnet(ep.url)) {
                                        Text(
                                            "磁力不支持",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 解析中指示：AppButton 无内容插槽，指示条放按钮左侧（功能不变）
                            if (resolving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            AppButton(
                                text = "取消",
                                onClick = { showDownloadDialog = false },
                                variant = AppButtonVariant.Ghost,
                                enabled = !resolving,
                            )
                            Spacer(Modifier.width(8.dp))
                            AppButton(
                                text = "下载选中（${selected.size}）",
                                onClick = {
                                    resolving = true
                                    scope.launch {
                                        val tasks = mutableListOf<DownloadTask>()
                                        selected.sorted().forEach { i ->
                                            val ep = lineEpisodes.getOrNull(i) ?: return@forEach
                                            if (MagnetPlayer.isMagnet(ep.url)) return@forEach
                                            val url = resolvePlayUrl(context, subSite, ep)
                                            if (url.isBlank()) return@forEach
                                            tasks.add(
                                                DownloadTask(
                                                    title = "${vod?.title.orEmpty()} ${ep.name.ifBlank { "第${i + 1}集" }}",
                                                    pic = detail?.vodPic.orEmpty(),
                                                    url = url,
                                                    kind = if (DownloadManager.isM3u8(url)) "m3u8" else "file",
                                                ),
                                            )
                                        }
                                        resolving = false
                                        showDownloadDialog = false
                                        if (tasks.isEmpty()) {
                                            Toast.makeText(context, "没有可下载的剧集", Toast.LENGTH_SHORT).show()
                                        } else {
                                            // v0.63.0：空间不足提醒
                                            if (DownloadSettings.lowSpaceWarn(context)) {
                                                val free = StoragePaths.downloadDir(context).usableSpace
                                                if (free < DownloadSettings.LOW_SPACE_BYTES) {
                                                    Toast.makeText(
                                                        context,
                                                        "存储空间不足（剩余 ${free / 1024 / 1024}MB），可能下不完",
                                                        Toast.LENGTH_LONG,
                                                    ).show()
                                                }
                                            }
                                            DownloadManager.enqueue(context, tasks)
                                            Toast.makeText(
                                                context,
                                                "已加入下载队列（${tasks.size}）",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                },
                                enabled = !resolving && selected.isNotEmpty(),
                            )
                        }
                    }
                }
            }
        } // vod?.let
    }

    // v0.21.0：封面点开放大（全屏看图，点任意处关闭）
    if (showCoverDialog && coverUrl.isNotBlank()) {
        Dialog(
            onDismissRequest = { showCoverDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { showCoverDialog = false },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = "封面大图",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }

    // v0.69.0：收藏时选片单
    if (showPlaylistPicker) {
        TechDialog(
            onDismissRequest = { showPlaylistPicker = false },
            title = "收藏到片单",
        ) {
                Column {
                    TextButton(
                        onClick = {
                            scope.launch {
                                favoriteAddToPlaylist(context, null)
                                showPlaylistPicker = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("未分组", modifier = Modifier.fillMaxWidth()) }
                    pickerPlaylists.forEach { p ->
                        TextButton(
                            onClick = {
                                scope.launch {
                                    favoriteAddToPlaylist(context, p.id)
                                    showPlaylistPicker = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(p.name, modifier = Modifier.fillMaxWidth()) }
                    }
                }
        }
    }

    // v0.77.0：跳集弹窗（集数>20 才有入口）
    if (showJumpDialog) {
        TechDialog(
            onDismissRequest = { showJumpDialog = false; jumpText = "" },
            title = "跳到第几集",
            confirmText = "播放",
            onConfirm = {
                val n = jumpText.toIntOrNull()
                if (n != null && n in 1..lineEpisodes.size) {
                    showJumpDialog = false
                    jumpText = ""
                    val idx = n - 1
                    selectedEpisode = idx
                    playEpisode(lineEpisodes[idx], safeLine, idx)
                }
            },
        ) {
            TechTextField(
                value = jumpText,
                onValueChange = { jumpText = it.filter { c -> c.isDigit() }.take(4) },
                placeholder = "输入集数（1-${lineEpisodes.size}）",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(SoftDimens.ChipCorner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = SoftType.Caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * v0.27.1：详情标题回退展示。
 * - 含 `@@` 的打包串（`tid@@标题@@图片@@作者`）只取标题段；
 * - 纯 URL 的 id 显示"未知标题"，不把裸地址甩给用户看。
 */
private fun displayTitle(vodId: String): String {
    if (vodId.contains("@@")) {
        return vodId.split("@@").getOrNull(1)?.takeIf { it.isNotBlank() } ?: "未知标题"
    }
    if (vodId.startsWith("http://") || vodId.startsWith("https://")) return "未知标题"
    return vodId.ifBlank { "未知标题" }
}
