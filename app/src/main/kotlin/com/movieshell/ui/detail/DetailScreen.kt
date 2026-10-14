package com.movieshell.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.movieshell.data.repo.DownloadTask
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.FavoriteItem
import com.movieshell.data.repo.FavoriteStore
import com.movieshell.data.repo.FollowItem
import com.movieshell.data.repo.FollowStore
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SiteRepository
import com.movieshell.player.MagnetPlayer
import com.movieshell.player.PlaySession
import com.movieshell.player.resolvePlayUrl
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppChip
import com.movieshell.ui.components.GradientBlock
import com.movieshell.ui.components.LoadingSkeleton
import com.movieshell.ui.components.SectionTitle
import com.movieshell.ui.data.DemoData
import com.movieshell.ui.data.Vod
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    vodId: String,
    onBack: () -> Unit,
    onPlay: (playUrl: String, title: String) -> Unit = { _, _ -> },
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
    LaunchedEffect(vodId) {
        if (subSite != null) {
            detailLoading = true
            detail = runCatching { SiteRepository().detail(subSite, vodId) }.getOrNull()
            detailLoading = false
        } else if (drpyActive) {
            detailLoading = true
            detail = runCatching {
                if (DrpySourceManager.hasActiveRule()) DrpySourceManager.detail(vodId)
                else if (PySourceManager.hasActiveRule()) PySourceManager.detail(vodId)
                else VodDetail(vodId = vodId)
            }.getOrNull()
            detailLoading = false
        }
    }
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

    val vod: Vod = remember(detail) {
        detail?.let {
            Vod(
                id = it.vodId.ifBlank { vodId },
                title = it.vodName.ifBlank { vodId },
                category = it.typeName,
                year = it.vodYear,
                rating = it.vodRemarks,
                area = "",
                actors = it.vodActor.ifBlank { "未知" },
                desc = it.vodDesc.ifBlank { "暂无简介" },
                episodes = episodes.size,
            )
        } ?: DemoData.find(vodId)
    }

    var selectedEpisode by remember { mutableIntStateOf(0) }
    var showMagnetDialog by remember { mutableStateOf(false) }
    // v0.21.0：封面点开放大
    var showCoverDialog by remember { mutableStateOf(false) }
    val coverUrl = detail?.vodPic.orEmpty()

    /** v0.21.0：影片信息一键复制 */
    fun copyVodInfo() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) return
        val text = buildString {
            appendLine(vod.title)
            appendLine(
                listOf(vod.year, vod.category, vod.area)
                    .filter { it.isNotBlank() }.joinToString(" · ")
            )
            appendLine("主演：${vod.actors}")
            append("简介：${vod.desc}")
        }
        cm.setPrimaryClip(ClipData.newPlainText("影片信息", text))
        Toast.makeText(context, "影片信息已复制", Toast.LENGTH_SHORT).show()
    }

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
    var showDownloadDialog by remember { mutableStateOf(false) }

    /** 统一播放决策（v0.19.0）：解析走公共 resolvePlayUrl；磁力直接起播不进播放器 */
    fun playEpisode(ep: Episode, lineIndex: Int, episodeIndex: Int) {
        LogBus.i("UI", "点击播放: ${ep.name.ifBlank { "未命名剧集" }} url=${ep.url.take(100)}")
        // v0.26.0：追剧看到第几集联动
        if (following) {
            scope.launch { FollowStore.markWatched(context, followId, episodeIndex) }
        }
        scope.launch {
            if (MagnetPlayer.isMagnet(ep.url)) {
                val ok = runCatching {
                    MagnetPlayer.playIfReady(context, ep.url, vod.title)
                }.getOrDefault(false)
                // 服务通则已直接起播；不通则弹窗提示
                if (!ok) showMagnetDialog = true
            } else {
                val link = resolvePlayUrl(context, subSite, ep)
                // v0.19.0：写入播放会话，供播放器内切线路 / 失败自动重试
                PlaySession.start(playLines, lineIndex, episodeIndex) { ctx, e ->
                    resolvePlayUrl(ctx, subSite, e)
                }
                // link 为空 -> 播放页保持"暂无可播地址"逻辑
                onPlay(link, "${vod.title} ${ep.name.ifBlank { "第${episodeIndex + 1}集" }}")
            }
        }
    }

    val safeSelected = if (lineEpisodes.isNotEmpty()) {
        selectedEpisode.coerceIn(0, lineEpisodes.size - 1)
    } else selectedEpisode
    val playLabel = when {
        lineEpisodes.isNotEmpty() ->
            "播放 ${lineEpisodes[safeSelected].name.ifBlank { "第${safeSelected + 1}集" }}"
        vod.episodes > 1 -> "播放 第${selectedEpisode + 1}集"
        else -> "立即播放"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vod.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            if (favorite) {
                                FavoriteStore.remove(context, vodId, sourceName)
                                Toast.makeText(context, "已取消收藏", Toast.LENGTH_SHORT)
                                    .show()
                            } else {
                                FavoriteStore.add(
                                    context,
                                    FavoriteItem(
                                        vodId = vodId,
                                        name = vod.title,
                                        pic = detail?.vodPic.orEmpty(),
                                        sourceName = sourceName,
                                        savedAt = System.currentTimeMillis(),
                                        subSiteKey = subSite?.key.orEmpty(),
                                    ),
                                )
                                Toast.makeText(context, "已收藏", Toast.LENGTH_SHORT).show()
                            }
                            favorite = FavoriteStore.isFavorite(context, vodId, sourceName)
                        }
                    }) {
                        Icon(
                            imageVector = if (favorite) Icons.Filled.Favorite
                            else Icons.Filled.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = if (favorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    // v0.26.0：追剧
                    IconButton(onClick = {
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
                                        name = vod.title,
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
                    }) {
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
                        Icon(Icons.Filled.Share, contentDescription = "分享")
                    }
                },
            )
        },
    ) { inner ->
        if (drpyActive && detailLoading && detail == null) {
            // drpy 详情加载中：骨架屏占位，不转菊花
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
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
            ) {
                // 海报头图（v0.21.0：有真实封面时展示，点击放大）
                item {
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
                            // 文案区压暗（文字可读性，非页面底渐变）
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0.4f to Color.Transparent,
                                            1f to Color(0xAA000000),
                                        )
                                    )
                            )
                            // 居中播放按钮
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "播放",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(40.dp),
                                )
                            }
                            Text(
                                text = vod.title,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(SoftDimens.PagePadding),
                                color = Color.White,
                                style = SoftType.Title,
                            )
                        }
                    }
                }

                // 标题与元信息
                item {
                    Column(modifier = Modifier.padding(SoftDimens.PagePadding)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = LocalSoftExtra.current.warning,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = vod.rating,
                                style = SoftType.Body,
                                color = LocalSoftExtra.current.warning,
                            )
                            Spacer(Modifier.width(12.dp))
                            MetaChip(vod.year)
                            MetaChip(vod.category)
                            MetaChip(vod.area)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "主演：${vod.actors}",
                            style = SoftType.Body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        // v0.21.0：简介标题 + 一键复制影片信息
                        SectionTitle(
                            title = "简介",
                            action = {
                                IconButton(onClick = { copyVodInfo() }) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = "复制影片信息",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = vod.desc,
                            style = SoftType.Body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 22.sp,
                        )
                    }
                }

                // 播放按钮（v0.13.0：无播放源时禁用并明示，不再静默传空地址）
                // v0.26.0：右侧加下载按钮（选集多选 → 加入下载队列）
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SoftDimens.PagePadding),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppButton(
                            text = if (lineEpisodes.isNotEmpty()) playLabel
                            else "暂无可播地址",
                            onClick = {
                                if (lineEpisodes.isNotEmpty()) {
                                    playEpisode(lineEpisodes[safeSelected], safeLine, safeSelected)
                                }
                            },
                            enabled = lineEpisodes.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { showDownloadDialog = true },
                            enabled = lineEpisodes.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = "下载")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // 选集：drpy 真实选集优先；多条线路时上方显示线路 TabRow
                when {
                    lineEpisodes.isNotEmpty() -> {
                        if (playLines.size > 1) {
                            item {
                                ScrollableTabRow(
                                    selectedTabIndex = safeLine,
                                    edgePadding = SoftDimens.PagePadding,
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
                                lineEpisodes.forEachIndexed { i, ep ->
                                    AppChip(
                                        text = ep.name.ifBlank { "第${i + 1}集" },
                                        selected = safeSelected == i,
                                        onClick = { selectedEpisode = i },
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
                                    AppChip(
                                        text = "第${i + 1}集",
                                        selected = selectedEpisode == i,
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
            }
        }
    }

    // 磁力转播服务未启动提示
    if (showMagnetDialog) {
        AlertDialog(
            onDismissRequest = { showMagnetDialog = false },
            title = { Text("磁力转播服务未启动") },
            text = {
                Text("未检测到本机磁力转播服务（127.0.0.1:17890），请先在 DsPlayer 的服务页启动 magnet-stream 后再试")
            },
            confirmButton = {
                TextButton(onClick = { showMagnetDialog = false }) { Text("知道了") }
            },
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
                                                title = "${vod.title} ${ep.name.ifBlank { "第${i + 1}集" }}",
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
