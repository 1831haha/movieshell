package com.movieshell.ui.music

import androidx.compose.animation.core.Animatable
import com.movieshell.ui.components.AppToast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.MusicApi
import com.movieshell.data.repo.MusicSource
import com.movieshell.data.repo.MusicSourceStore
import com.movieshell.data.repo.MusicStore
import com.movieshell.data.repo.MusicSettings
import com.movieshell.player.MusicPlayer
import com.movieshell.player.MusicSleepTimer
import com.movieshell.ui.components.StatusRing
import com.movieshell.ui.components.StatusRingState
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.goldTitleColor
import com.movieshell.ui.theme.LocalBrandGradient
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import kotlinx.coroutines.launch

/**
 * v0.83.0：全屏播放页——大封面 / 歌词滚动跟随 / 进度条拖动 / 播放模式。
 * v0.91.0：中间内容区单击在封面模式与歌词模式间切换（网易云式）。
 * v0.129.0：按主流播放器版式整体重做——封面模糊氛围背景（无封面走品牌
 * 渐变）、近屏宽大封面、歌名炫彩渐变字、歌手行带平台徽标 + 解析来源
 * chip、真实功能操作行（收藏/换源/音质展示/歌词）、细进度条、渐变大圆
 * 播放钮、播放列表弹窗；去掉 v0.125.0 的毛玻璃控制面板盒子。页面恒为
 * 深色氛围底，文字一律固定浅色，不随主题明暗翻转。
 * v0.130.0：恢复 v0.89.0 的封面旋转（9 秒一圈匀速、暂停停在当前角度），
 * 只作用于大封面与无封面占位，氛围背景不转。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(
    onBack: () -> Unit = {},
    // v0.121.0：切换音源弹窗底部「管理音源与 Cookie →」入口
    onOpenMusicSource: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by MusicPlayer.state.collectAsState()
    // v0.125.0 补充A：海报匹配结果由 MusicPlayer 持有（匹配跑在播放器 scope，
    // 页面只读，切歌词视图/离页不再取消匹配）
    val matchedArtMap by MusicPlayer.matchedArt.collectAsState()
    // v0.125.0：歌词颜色（0=跟随主题，MusicSettings 持久化）
    val lyricColorInt by MusicSettings.lyricColor.collectAsState()
    val track = playState.track

    // v0.129.0：封面地址提到组合顶层——氛围背景与大封面共用一份结果
    // （自带封面走 picImageUrl 解析，无自带封面读 MusicPlayer 的匹配回填表）
    val artUrl = rememberArtworkUrl(track?.picId.orEmpty(), track?.source ?: "wy")
    val shownArt = artUrl.ifBlank {
        track?.let { matchedArtMap[it.id] }.orEmpty()
    }

    var lyrics by remember { mutableStateOf<List<MusicApi.LyricLine>>(emptyList()) }
    var lyricLoading by remember { mutableStateOf(false) }
    var isFav by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf(0L) }
    // v0.85.0：睡眠定时
    var showSleepDialog by remember { mutableStateOf(false) }
    // v0.91.0：中间内容区单击切换——封面模式 / 歌词模式（网易云式）
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    // v0.137.0：左滑切歌词/右滑回封面的累计横向位移（像素，阈值 60）
    var swipeAccum by remember { mutableStateOf(0f) }
    // v0.125.0：歌词色板展开态（歌词模式右上角调色板入口）
    var showLyricColors by remember { mutableStateOf(false) }
    // v0.108.0 B1：音源切换弹窗
    var showSourceDialog by remember { mutableStateOf(false) }
    // v0.129.0：播放列表弹窗
    var showQueueDialog by remember { mutableStateOf(false) }
    val sleepRemaining by MusicSleepTimer.remainingMs.collectAsState()
    val sleepArmed by MusicSleepTimer.afterEpisodeArmed.collectAsState()
    // v0.89.0 的封面旋转，v0.130.0 恢复：只转大封面，参数沿用旧实现
    // （9 秒一圈匀速；暂停时 LaunchedEffect key 变化自动取消，停在当前
    // 角度；angle 不按曲目 key，切歌不重置）
    val angle = remember { Animatable(0f) }
    LaunchedEffect(playState.playing) {
        if (playState.playing) {
            while (true) {
                angle.animateTo(angle.value + 360f, tween(9000, easing = LinearEasing))
            }
        }
    }

    // v0.217.0（F29）：歌词 effect 的 key 改成 id + urlId + source——跨平台切歌时
    // id 原样不动、只换 source／urlId／picId，而歌词是按 urlId + source 取的，
    // 原来 key 只有 id，切平台后歌词还停留在旧平台那首。
    LaunchedEffect(track?.id, track?.urlId, track?.source) {
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

    Box(modifier = Modifier.fillMaxSize()) {
        PlayerAtmosphere(artUrl = shownArt)
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TabTopBar(
                    title = "正在播放",
                    onBack = onBack,
                    actions = {
                        // v0.85.0：定时中显示剩余时间小胶囊
                        if (sleepRemaining > 0 || sleepArmed) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = if (sleepRemaining > 0) fmtMs(sleepRemaining) else "播完停",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        // v0.108.0 B1：手动切换音源（已启用音乐源列表，点选重解析当前歌曲）
                        IconButton(onClick = { showSourceDialog = true }) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = "切换音源")
                        }
                        IconButton(onClick = { showSleepDialog = true }) {
                            Icon(Icons.Filled.Timer, contentDescription = "定时关闭")
                        }
                    },
                )
            },
        ) { inner ->
            if (track == null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(inner),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "还没有播放歌曲",
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                return@Scaffold
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(horizontal = 24.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                // v0.91.0：中间内容区单击切换封面/歌词（网易云式）；标题行以下
                // 的固定区不在此 Box 内，不冲突
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // v0.137.0：左滑切歌词、右滑回封面（点按切换保留）
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (swipeAccum < -60f) showLyrics = true
                                    else if (swipeAccum > 60f) showLyrics = false
                                    swipeAccum = 0f
                                },
                                onDragCancel = { swipeAccum = 0f },
                            ) { change, dragAmount ->
                                change.consume()
                                swipeAccum += dragAmount
                            }
                        }
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { showLyrics = !showLyrics },
                    contentAlignment = Alignment.Center,
                ) {
                    if (!showLyrics) {
                        // 封面模式：近屏宽大方封面（边长取内容区宽高较小值）
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            val side = minOf(maxWidth, maxHeight)
                            if (shownArt.isNotBlank()) {
                                AsyncImage(
                                    model = shownArt,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(side)
                                        .clip(CircleShape)
                                        .graphicsLayer { rotationZ = angle.value },
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(side)
                                        .clip(CircleShape)
                                        .graphicsLayer { rotationZ = angle.value }
                                        .background(Color.White.copy(alpha = 0.06f))
                                        .border(
                                            1.dp,
                                            Color.White.copy(alpha = 0.10f),
                                            CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.MusicNote,
                                        contentDescription = null,
                                        modifier = Modifier.size(96.dp),
                                        tint = Color.White.copy(alpha = 0.45f),
                                    )
                                }
                            }
                        }
                    } else {
                        // 歌词模式：歌词占满中间区域（滚动跟随/当前行高亮保留）
                        // v0.125.0：右上角调色板入口换歌词颜色——高亮行=选定色、
                        // 普通行=选定色低透明度；选 0（默认）回落主题色口径。
                        // v0.129.0：页面恒深色氛围底，未选色时普通行改固定浅色，
                        // 不再读随主题变的日间低对比色。
                        val lyricBase = if (lyricColorInt != 0) Color(lyricColorInt) else null
                        Box(modifier = Modifier.fillMaxSize()) {
                            when {
                                lyricLoading -> Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // 歌词区加载圈统一为 StatusRing（跑马灯圆环 + 黄色状态点）
                                    StatusRing(state = StatusRingState.Loading, diameter = 28.dp)
                                }
                                lyrics.isEmpty() -> Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "暂无歌词",
                                        color = Color.White.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                else -> LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    // v0.89.0：行距 14.dp，当前行加粗，呼吸感
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    itemsIndexed(lyrics) { i, line ->
                                        Text(
                                            text = line.text,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center,
                                            fontWeight = if (i == curIdx) FontWeight.Bold else null,
                                            style = if (i == curIdx) {
                                                MaterialTheme.typography.bodyLarge
                                            } else {
                                                MaterialTheme.typography.bodyMedium
                                            },
                                            color = if (i == curIdx) {
                                                lyricBase ?: MaterialTheme.colorScheme.primary
                                            } else {
                                                lyricBase?.copy(alpha = 0.45f)
                                                    ?: Color.White.copy(alpha = 0.55f)
                                            },
                                        )
                                    }
                                }
                            }
                            // 色板入口：子级 clickable 自行消费点击，不触发外层封面/歌词切换
                            Column(
                                modifier = Modifier.align(Alignment.TopEnd).zIndex(1f),
                                horizontalAlignment = Alignment.End,
                            ) {
                                IconButton(onClick = { showLyricColors = !showLyricColors }) {
                                    Icon(
                                        Icons.Filled.Palette,
                                        contentDescription = "歌词颜色",
                                        tint = Color.White.copy(alpha = 0.8f),
                                    )
                                }
                                if (showLyricColors) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(LocalSoftExtra.current.glass)
                                            .border(
                                                SoftDimens.BorderWidth,
                                                LocalSoftExtra.current.border,
                                                RoundedCornerShape(20.dp),
                                            )
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        LYRIC_COLOR_PRESETS.forEach { preset ->
                                            val selected = lyricColorInt == preset
                                            val dotColor = if (preset == 0) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                Color(preset)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .clip(CircleShape)
                                                    .background(dotColor)
                                                    .border(
                                                        width = if (selected) 2.dp else 1.dp,
                                                        color = if (selected) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            Color.Gray.copy(alpha = 0.45f)
                                                        },
                                                        shape = CircleShape,
                                                    )
                                                    .clickable {
                                                        MusicSettings.setLyricColor(
                                                            context, preset,
                                                        )
                                                        showLyricColors = false
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                when {
                                                    selected -> Icon(
                                                        Icons.Filled.Check,
                                                        contentDescription = "当前颜色",
                                                        modifier = Modifier.size(15.dp),
                                                        tint = if (dotColor.luminance() > 0.5f) {
                                                            Color.Black
                                                        } else {
                                                            Color.White
                                                        },
                                                    )
                                                    preset == 0 -> Text(
                                                        "默",
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onPrimary,
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
                Spacer(Modifier.height(14.dp))
                // v0.129.0：歌名炫彩渐变字（主题炫彩渐变 Brush 作文字色，
                // 与 DetailScreen 的 brush 文字同写法）
                Text(
                    text = track.name,
                    style = TextStyle(
                        brush = Brush.horizontalGradient(LocalBrandGradient.current),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                // 歌手行：歌手名 + 平台徽标 + 解析来源 chip（来源读 MusicPlayer
                // 的 resolvedSource——实际出链的音源名，切歌时 playAt 已重置，
                // 解析未成功不残留上一首）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!track.isLocal) {
                        MusicApi.platformLabel(
                            MusicApi.normalizeSource(track.source),
                        )?.let { label ->
                            ResolvedBadge(text = label, tint = PlayerChipTint)
                        }
                    }
                    if (playState.resolvedSource.isNotBlank()) {
                        ResolvedBadge(
                            text = "来源：${playState.resolvedSource}",
                            tint = PlayerChipTint,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                // 操作行：只放真实功能（收藏/换源/音质展示/歌词开关）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    PlayerOpItem(
                        icon = if (isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        label = "收藏",
                        tint = if (isFav) Color(0xFFFF6B81) else PlayerIconTint,
                        onClick = {
                            scope.launch {
                                isFav = MusicStore.toggleFav(context, track)
                            }
                        },
                    )
                    PlayerOpItem(
                        icon = Icons.Filled.SwapHoriz,
                        label = "换源",
                        tint = PlayerIconTint,
                        onClick = { showSourceDialog = true },
                    )
                    // 当前音质：App 无手动音质设置，只展示不点击
                    PlayerOpItem(
                        icon = Icons.Filled.Tune,
                        label = if (playState.resolvedQuality.isNotBlank()) {
                            qualityLabel(playState.resolvedQuality)
                        } else {
                            "音质"
                        },
                        tint = if (playState.resolvedQuality.isNotBlank()) {
                            musicQualityTint(playState.resolvedQuality)
                        } else {
                            PlayerIconTint
                        },
                        onClick = null,
                    )
                    PlayerOpItem(
                        icon = Icons.Filled.Subject,
                        label = "歌词",
                        tint = if (showLyrics) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            PlayerIconTint
                        },
                        onClick = { showLyrics = !showLyrics },
                    )
                }
                Spacer(Modifier.height(14.dp))
                // 进度条：与视频侧 PlayerSeekBar 同款式细条（3dp 线 + 小圆点
                // thumb），已播段走品牌炫彩渐变；两端时间固定浅色
                val shown = if (dragging) dragPos else pos
                val dur = playState.durationMs.coerceAtLeast(1L)
                MusicSeekBar(
                    value = shown.toFloat().coerceIn(0f, dur.toFloat()),
                    valueRange = 0f..dur.toFloat(),
                    onValueChange = {
                        dragging = true
                        dragPos = it.toLong()
                    },
                    onValueChangeFinished = {
                        dragging = false
                        MusicPlayer.seekTo(dragPos)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = fmtMs(shown),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = fmtMs(playState.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                // 控制行：播放模式 / 上一首 / 渐变大圆播放钮 / 下一首 / 播放列表
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = { MusicPlayer.cycleRepeatMode() },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            when (playState.repeatMode) {
                                MusicPlayer.RepeatMode.ONE -> Icons.Filled.RepeatOne
                                MusicPlayer.RepeatMode.SHUFFLE -> Icons.Filled.Shuffle
                                MusicPlayer.RepeatMode.ALL -> Icons.Filled.Repeat
                            },
                            contentDescription = "播放模式",
                            modifier = Modifier.size(24.dp),
                            tint = PlayerIconTint,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { MusicPlayer.prev(context) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "上一首",
                            modifier = Modifier.size(32.dp),
                            tint = Color.White,
                        )
                    }
                    // 主按钮：76dp 主题炫彩渐变实心圆（容器固定，三态不跳动）
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(LocalBrandGradient.current),
                            )
                            .clickable { MusicPlayer.toggle() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (playState.loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(34.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            Icon(
                                if (playState.playing) Icons.Filled.Pause
                                else Icons.Filled.PlayArrow,
                                contentDescription = if (playState.playing) "暂停" else "播放",
                                modifier = Modifier.size(44.dp),
                                tint = Color.White,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { MusicPlayer.next(context) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "下一首",
                            modifier = Modifier.size(32.dp),
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = { showQueueDialog = true },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.Filled.QueueMusic,
                            contentDescription = "播放列表",
                            modifier = Modifier.size(24.dp),
                            tint = PlayerIconTint,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // v0.85.0：睡眠定时菜单
    if (showSleepDialog) {
        MusicSleepTimerDialog(
            onDismiss = { showSleepDialog = false },
            onPickMinutes = { m ->
                showSleepDialog = false
                MusicSleepTimer.startMinutes(m) {
                    MusicPlayer.fadeOutAndPause()
                    AppToast.show(context, "定时关闭：音乐已暂停", long = false)
                }
                AppToast.show(context, "将在 $m 分钟后停止播放", long = false)
            },
            onPickAfterTrack = {
                showSleepDialog = false
                MusicSleepTimer.armAfterEpisode()
                AppToast.show(context, "本首播完后将停止播放", long = false)
            },
            onCancelTimer = {
                showSleepDialog = false
                MusicSleepTimer.cancel()
                AppToast.show(context, "定时关闭已取消", long = false)
            },
        )
    }

    // v0.108.0 B1：音源切换弹窗——点选后仅用该源重解析当前歌曲并播放
    // v0.126.0：同弹窗带平台区（目标=当前歌曲，本地歌不给平台区）
    if (showSourceDialog) {
        MusicSourcePickerDialog(
            onDismiss = { showSourceDialog = false },
            onManageSources = {
                showSourceDialog = false
                onOpenMusicSource()
            },
            platformTrack = track?.takeIf { !it.isLocal },
            onPickPlatform = { code ->
                showSourceDialog = false
                MusicPlayer.playOnPlatform(context, code)
            },
            onPick = { src ->
                showSourceDialog = false
                // v0.213.0：本地歌不走换源解析（playWithSource 亦会拒绝），
                // 这里先拦住，不再先报一句“正在重新解析”误导。
                if (track?.isLocal == true) {
                    AppToast.show(context, "本地歌曲无需换源", long = false)
                } else {
                    AppToast.show(context, "正在用「${src.name}」重新解析", long = false)
                    MusicPlayer.playWithSource(context, src.id)
                }
            },
        )
    }

    // v0.129.0：播放列表弹窗——读 MusicPlayer.queueState，点选跳播
    if (showQueueDialog) {
        MusicQueueDialog(
            onDismiss = { showQueueDialog = false },
            onPick = { i ->
                showQueueDialog = false
                MusicPlayer.playQueueItem(context, i)
            },
        )
    }
}

/** v0.129.0：播放页恒深色氛围底上的固定浅色（图标/次要文字、徽标字色）。 */
private val PlayerIconTint = Color.White.copy(alpha = 0.85f)
private val PlayerChipTint = Color(0xFFE9EBF5)

/**
 * v0.129.0：氛围背景——当前封面放大高斯模糊 + 深色蒙版压出层次；
 * 无封面时用品牌炫彩渐变（低透明度）铺在深色基底上，再罩同一层蒙版，
 * 保证标题区文字对比度。模糊在低版本系统上自动退化为原图 + 蒙版。
 */
@Composable
private fun PlayerAtmosphere(artUrl: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07070D)),
    ) {
        if (artUrl.isNotBlank()) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.25f
                        scaleY = 1.25f
                    }
                    .blur(48.dp),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            LocalBrandGradient.current.map { it.copy(alpha = 0.32f) },
                        ),
                    ),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.38f),
                            Color.Black.copy(alpha = 0.68f),
                            Color.Black.copy(alpha = 0.88f),
                        ),
                    ),
                ),
        )
    }
}

/**
 * v0.129.0：音乐细进度条——与视频侧 PlayerScreen 的 PlayerSeekBar 同款式
 * （关掉 Material3 的 48dp 最小交互高度、3dp 圆头细线、12dp 小圆点 thumb），
 * 已播段改走品牌炫彩渐变。视频侧那份是 private 且色板为视频定制，故此处
 * 按同规格独立实现，不互相牵连。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicSeekBar(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brand = LocalBrandGradient.current
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            modifier = modifier.height(28.dp),
            track = { sliderState ->
                val fraction = sliderState.valueRange.let { r ->
                    val span = r.endInclusive - r.start
                    if (span <= 0f) 0f
                    else ((sliderState.value - r.start) / span).coerceIn(0f, 1f)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                    ) {
                        val y = size.height / 2f
                        val w = 3.dp.toPx()
                        drawLine(
                            color = Color.White.copy(alpha = 0.25f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = w,
                            cap = StrokeCap.Round,
                        )
                        if (fraction > 0f) {
                            drawLine(
                                brush = Brush.horizontalGradient(brand),
                                start = Offset(0f, y),
                                end = Offset(size.width * fraction, y),
                                strokeWidth = w,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
            },
            thumb = {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            },
        )
    }
}

/** v0.129.0：操作行单项——图标 + 小字标签；onClick 为 null 时纯展示（如当前音质）。 */
@Composable
private fun PlayerOpItem(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: (() -> Unit)?,
) {
    // v0.213.0 音乐界面误导修复：onClick 为 null 的项原来外观与可点项
    // 完全一致（如音质只展示不点击），看着能点、点了没反应；
    // 不可点项整体置灰，一眼可辨。
    val opAlpha = if (onClick == null) 0.45f else 1f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            )
            .alpha(opAlpha)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = tint,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = tint,
            maxLines = 1,
        )
    }
}

/** v0.129.0：播放列表弹窗；v0.194.0 起弹窗体跟随主题，文字跟主题色走。 */
@Composable
private fun MusicQueueDialog(
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val queueState by MusicPlayer.queueState.collectAsState()
    // v0.213.0 音乐界面误导修复：队列弹窗原来长队列永远停在顶部，
    // 当前歌曲在下面时用户找不到自己在听哪首；打开即滚到当前项。
    val queueListState = rememberLazyListState()
    LaunchedEffect(queueState.currentIndex) {
        val cur = queueState.currentIndex
        if (cur in queueState.tracks.indices) queueListState.scrollToItem(cur)
    }
    // v0.215.0：队列弹窗行内删除与清空（原来队列只能加不能减）。
    val queueContext = LocalContext.current
    TechDialog(
        onDismissRequest = onDismiss,
        title = "播放列表",
    ) {
        if (queueState.tracks.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "共 ${queueState.tracks.size} 首",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { MusicPlayer.clearQueue() }) {
                    Text("清空", color = Color(0xFFE53935))
                }
            }
        }
        if (queueState.tracks.isEmpty()) {
            Text(
                "队列是空的",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn(
                state = queueListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                itemsIndexed(queueState.tracks) { i, t ->
                    val current = i == queueState.currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (current) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .clickable { onPick(i) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = t.name,
                                fontSize = 14.sp,
                                fontWeight = if (current) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                                color = if (current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = t.artist,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (current) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = "正在播放",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        // v0.215.0：行内删除（删当前歌停播，其余修正下标）。
                        IconButton(onClick = { MusicPlayer.removeQueueItem(queueContext, i) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "从队列删除",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** v0.108.0 B1：音源选择——列出已启用音乐源；暂无可用源时给空态提示。
 *  v0.117.0：列表页复用本弹窗（internal），新增"自动"选项（showAutoOption）。
 *  v0.121.0：重做为品牌卡片风——每个选项一行：名称 + 一句话人话说明 + 当前选中打勾；
 *  底部加"管理音源与 Cookie →"入口（用户反馈"黑乎乎看不懂、不知道 cookie 在哪"）。
 *  v0.126.0：新增「平台」区（与音源=脚本维度并存）——platformTrack 非空时渲染五家
 *  平台单选行，当前歌曲平台打勾；点选回调 onPickPlatform 由调用方接
 *  MusicPlayer.playOnPlatform 做跨平台同曲匹配。本地歌曲不传（无平台可切）。 */
@Composable
internal fun MusicSourcePickerDialog(
    onDismiss: () -> Unit,
    onPick: (MusicSource) -> Unit,
    showAutoOption: Boolean = false,
    onPickAuto: () -> Unit = {},
    // v0.121.0：当前选中（null=自动；播放页一次性选择不持久化，传 null 即无打勾）
    selectedId: String? = null,
    // v0.121.0："管理音源与 Cookie →"入口回调
    onManageSources: () -> Unit = {},
    // v0.126.0：平台区的目标歌曲（null=不渲染平台区）与平台点选回调
    platformTrack: MusicApi.Track? = null,
    onPickPlatform: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var sources by remember { mutableStateOf<List<MusicSource>>(emptyList()) }
    LaunchedEffect(Unit) {
        sources = runCatching { MusicSourceStore.list(context) }
            .onFailure { LogBus.w("MusicPlayer", "读取音乐源失败：${it.message}") }
            .getOrDefault(emptyList())
            .filter { it.enabled }
    }
    TechDialog(
        onDismissRequest = onDismiss,
        title = "切换音源",
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (showAutoOption) {
                SourceOptionRow(
                    name = "自动",
                    desc = "按音源管理里的启用顺序逐个试",
                    selected = selectedId == null,
                    onClick = onPickAuto,
                )
                Spacer(Modifier.height(6.dp))
            }
            if (sources.isEmpty()) {
                Text(
                    "暂无可用的音乐源",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                sources.forEach { s ->
                    SourceOptionRow(
                        name = s.name,
                        desc = if (s.builtin) "内置免费线路，免配置"
                        else "你的 LX 脚本，可在音源管理里配 Cookie 解锁更高音质",
                        selected = s.id == selectedId,
                        onClick = { onPick(s) },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (platformTrack != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "平台（当前歌曲）",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(6.dp))
                val curPlatform = MusicApi.normalizeSource(platformTrack.source)
                    .ifBlank { "wy" }
                // v0.180.0：同曲换平台只列可搜索平台（数据层单点口径，UI 不写死五家）。
                MusicApi.searchablePlatformCodes().forEach { code ->
                    SourceOptionRow(
                        name = MusicApi.platformLabel(code) ?: code,
                        desc = if (code == curPlatform) {
                            "当前歌曲的来源平台"
                        } else {
                            "按歌名+歌手匹配同一首歌，用该平台解析"
                        },
                        selected = code == curPlatform,
                        onClick = { onPickPlatform(code) },
                        enabled = code != curPlatform,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            TextButton(
                onClick = onManageSources,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "管理音源与 Cookie →",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** v0.121.0：切换音源选项行——名称 + 人话说明 + 选中打勾；v0.194.0 起跟主题色走。 */
// v0.137.0：enabled=false 时置灰且不可点（当前平台行用，点了没反应不如明示）
@Composable
private fun SourceOptionRow(
    name: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = desc,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "当前选中",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** v0.121.0：播放页来源/音质徽标（与列表行同口径：来源中性、音质按档次着色） */
@Composable
private fun ResolvedBadge(
    text: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}

/** v0.121.0：音质文本 → 徽标色（与 MusicScreen.kt qualityChipColor 同口径，明度自适应） */
@Composable
private fun musicQualityTint(quality: String): Color {
    val lightBg = MaterialTheme.colorScheme.background.luminance() > 0.5f
    return when {
        quality.contains("无损") || quality.contains("母带") || quality.contains("超高清") ||
            listOf("flac", "hires", "atmos", "master").any {
                quality.contains(it, ignoreCase = true)
            } -> goldTitleColor()
        quality.contains("高清") || quality.contains("320k") ->
            if (lightBg) Color(0xFF1D4ED8) else Color(0xFF60A5FA)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** v0.85.0：音乐睡眠定时——15/30/60/90 分钟、播完本首停止、取消。 */
@Composable
private fun MusicSleepTimerDialog(
    onDismiss: () -> Unit,
    onPickMinutes: (Int) -> Unit,
    onPickAfterTrack: () -> Unit,
    onCancelTimer: () -> Unit,
) {
    TechDialog(
        onDismissRequest = onDismiss,
        title = "定时关闭",
    ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                listOf(15, 30, 60, 90).forEach { m ->
                    TextButton(
                        onClick = { onPickMinutes(m) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("$m 分钟后停止") }
                }
                TextButton(
                    onClick = onPickAfterTrack,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("播完本首停止") }
                TextButton(
                    onClick = onCancelTimer,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("关闭定时", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

/** v0.125.0：歌词预设色（0=默认跟随主题色，其余为 ARGB；与 MusicSettings.lyricColor 对应）。 */
private val LYRIC_COLOR_PRESETS = listOf(
    0,
    0xFFFFFFFF.toInt(), // 纯白
    0xFFFFD54F.toInt(), // 暖黄
    0xFF4DD0E1.toInt(), // 青蓝
    0xFFF48FB1.toInt(), // 粉红
    0xFF81C784.toInt(), // 青绿
)

private fun fmtMs(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
