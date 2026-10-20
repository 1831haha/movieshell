package com.movieshell.ui.music

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.goldTitleColor
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.MusicApi
import com.movieshell.data.repo.MusicSource
import com.movieshell.data.repo.MusicSourceStore
import com.movieshell.data.repo.MusicStore
import com.movieshell.player.MusicPlayer
import com.movieshell.player.SleepTimer
import com.movieshell.ui.components.TabTopBar
import kotlinx.coroutines.launch

/**
 * v0.83.0：全屏播放页——大封面 / 歌词滚动跟随 / 进度条拖动 / 播放模式。
 * v0.91.0：中间内容区单击在封面模式与歌词模式间切换（网易云式）。
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
    val track = playState.track

    var lyrics by remember { mutableStateOf<List<MusicApi.LyricLine>>(emptyList()) }
    var lyricLoading by remember { mutableStateOf(false) }
    var isFav by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf(0L) }
    // v0.85.0：睡眠定时
    var showSleepDialog by remember { mutableStateOf(false) }
    // v0.91.0：中间内容区单击切换——封面模式 / 歌词模式（网易云式）
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    // v0.108.0 B1：音源切换弹窗
    var showSourceDialog by remember { mutableStateOf(false) }
    val sleepRemaining by SleepTimer.remainingMs.collectAsState()
    val sleepArmed by SleepTimer.afterEpisodeArmed.collectAsState()
    // v0.89.0：封面旋转（只转封面；暂停时 LaunchedEffect key 变化自动取消即停）
    val angle = remember { Animatable(0f) }
    LaunchedEffect(playState.playing) {
        if (playState.playing) {
            while (true) {
                angle.animateTo(angle.value + 360f, tween(9000, easing = LinearEasing))
            }
        }
    }

    LaunchedEffect(track?.id) {
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

    Scaffold(
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
            ) { Text("还没有播放歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            // v0.91.0：中间内容区单击切换封面/歌词（网易云式）；进度条与控制区不在此 Box 内，不冲突
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { showLyrics = !showLyrics },
                contentAlignment = Alignment.Center,
            ) {
                if (!showLyrics) {
                    // 封面模式：大封面（保留旋转动画）+ 歌名/歌手
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 封面（v0.89.0：旋转只作用在这两处；封面地址走 picImageUrl 解析）
                        val artUrl = rememberArtworkUrl(track.picId)
                        // v0.123.0：没自带封面（本地导入歌/picId 为空/解析不出图）时，
                        // 按歌名+歌手搜一次匹配第一条的封面回填，不阻塞播放；
                        // 结果按 track.id 进程内缓存，同一首不重复搜（失败也记空）
                        var matchedArt by remember(track.id) {
                            mutableStateOf(matchedArtCache[track.id].orEmpty())
                        }
                        LaunchedEffect(track.id, artUrl) {
                            if (artUrl.isNotBlank() ||
                                matchedArtCache.containsKey(track.id)
                            ) {
                                return@LaunchedEffect
                            }
                            val q = if (track.artist.isBlank() || track.artist == "本地") {
                                track.name
                            } else {
                                "${track.name} ${track.artist}"
                            }
                            val found = runCatching {
                                val hit = MusicApi.search(q, count = 5)
                                    .firstOrNull { it.picId.isNotBlank() }
                                    ?: return@runCatching ""
                                MusicApi.picImageUrl(hit.picId)
                            }.onFailure {
                                LogBus.w("MusicPlayer", "海报匹配失败：${it.message}")
                            }.getOrDefault("")
                            matchedArtCache[track.id] = found
                            matchedArt = found
                        }
                        val shownArt = artUrl.ifBlank { matchedArt }
                        if (shownArt.isNotBlank()) {
                            AsyncImage(
                                model = shownArt,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(240.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .graphicsLayer { rotationZ = angle.value },
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(240.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .graphicsLayer { rotationZ = angle.value },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(96.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = track.name,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // v0.117.0：当前曲目的真实解析来源与音质（解析成功后才有值）
                        // v0.121.0：改徽标 chips——与列表行同口径，音质按档次着色，更醒目
                        if (playState.resolvedSource.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ResolvedBadge(text = "音源 · ${playState.resolvedSource}")
                                if (playState.resolvedQuality.isNotBlank()) {
                                    ResolvedBadge(
                                        text = playState.resolvedQuality,
                                        tint = musicQualityTint(playState.resolvedQuality),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // 歌词模式：歌词占满中间区域（滚动跟随/当前行高亮保留）
                    when {
                        lyricLoading -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                        lyrics.isEmpty() -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "暂无歌词",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        else -> LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            // v0.89.0：行距 10.dp→14.dp，当前行加粗，呼吸感
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
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 进度条
            val shown = if (dragging) dragPos else pos
            val dur = playState.durationMs.coerceAtLeast(1L)
            Slider(
                value = shown.toFloat().coerceIn(0f, dur.toFloat()),
                onValueChange = {
                    dragging = true
                    dragPos = it.toLong()
                },
                onValueChangeFinished = {
                    dragging = false
                    MusicPlayer.seekTo(dragPos)
                },
                valueRange = 0f..dur.toFloat(),
                modifier = Modifier.fillMaxWidth(),
                // v0.108.0 B2：竖线 pill thumb 改小圆球，跟随主题色
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                },
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = fmtMs(shown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = fmtMs(playState.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            // 控制行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = { MusicPlayer.cycleRepeatMode() }) {
                    Icon(
                        when (playState.repeatMode) {
                            MusicPlayer.RepeatMode.ONE -> Icons.Filled.RepeatOne
                            MusicPlayer.RepeatMode.SHUFFLE -> Icons.Filled.Shuffle
                            MusicPlayer.RepeatMode.ALL -> Icons.Filled.Repeat
                        },
                        contentDescription = "播放模式",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { MusicPlayer.prev(context) }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(36.dp))
                }
                IconButton(
                    onClick = { MusicPlayer.toggle() },
                    modifier = Modifier.size(64.dp).clip(CircleShape),
                ) {
                    if (playState.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    } else {
                        Icon(
                            if (playState.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playState.playing) "暂停" else "播放",
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = { MusicPlayer.next(context) }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一首", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = {
                    scope.launch {
                        isFav = MusicStore.toggleFav(context, track)
                    }
                }) {
                    Icon(
                        if (isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (isFav) Color(0xFFFF6B81)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // v0.85.0：睡眠定时菜单
    if (showSleepDialog) {
        MusicSleepTimerDialog(
            onDismiss = { showSleepDialog = false },
            onPickMinutes = { m ->
                showSleepDialog = false
                SleepTimer.startMinutes(m) {
                    MusicPlayer.fadeOutAndPause()
                    Toast.makeText(context, "定时关闭：音乐已暂停", Toast.LENGTH_SHORT).show()
                }
                Toast.makeText(context, "将在 $m 分钟后停止播放", Toast.LENGTH_SHORT).show()
            },
            onPickAfterTrack = {
                showSleepDialog = false
                SleepTimer.armAfterEpisode()
                Toast.makeText(context, "本首播完后将停止播放", Toast.LENGTH_SHORT).show()
            },
            onCancelTimer = {
                showSleepDialog = false
                SleepTimer.cancel()
                Toast.makeText(context, "定时关闭已取消", Toast.LENGTH_SHORT).show()
            },
        )
    }

    // v0.108.0 B1：音源切换弹窗——点选后仅用该源重解析当前歌曲并播放
    if (showSourceDialog) {
        MusicSourcePickerDialog(
            onDismiss = { showSourceDialog = false },
            onManageSources = {
                showSourceDialog = false
                onOpenMusicSource()
            },
            onPick = { src ->
                showSourceDialog = false
                Toast.makeText(context, "正在用「${src.name}」重新解析", Toast.LENGTH_SHORT).show()
                MusicPlayer.playWithSource(context, src.id)
            },
        )
    }
}

/** v0.108.0 B1：音源选择——列出已启用音乐源；暂无可用源时给空态提示。
 *  v0.117.0：列表页复用本弹窗（internal），新增"自动"选项（showAutoOption）。
 *  v0.121.0：重做为品牌卡片风——每个选项一行：名称 + 一句话人话说明 + 当前选中打勾；
 *  底部加"管理音源与 Cookie →"入口（用户反馈"黑乎乎看不懂、不知道 cookie 在哪"）。 */
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

/** v0.121.0：切换音源选项行——名称 + 人话说明 + 选中打勾（TechDialog 内容区恒深色，用固定浅色系） */
@Composable
private fun SourceOptionRow(
    name: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else Color.White.copy(alpha = 0.04f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = desc,
                fontSize = 12.sp,
                color = Color(0xFF8A93B2),
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

/** v0.123.0：播放页海报匹配缓存（track.id → 封面图地址；空串=匹配过但没找到，不重复搜）。 */
private val matchedArtCache = mutableMapOf<String, String>()

private fun fmtMs(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
