package com.movieshell.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import com.movieshell.data.repo.PlayerSettings
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlinx.coroutines.delay

private val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private val RESIZE_MODES = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "适应",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "填充",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "裁剪",
)

/** 断点续播阈值：上次进度超过 10 秒才续播 */
private const val RESUME_THRESHOLD_MS = 10_000L

/** 手势：水平滑动每走这么多像素触发一次 ±10 秒快进/快退 */
private const val SEEK_STEP_PX = 60f

/** 手势：垂直滑动每走这么多像素触发一次亮度/音量调节 */
private const val ADJUST_STEP_PX = 50f

/** 手势：亮度每步调节量（0~1） */
private const val BRIGHTNESS_STEP = 0.05f

/**
 * 全屏播放器界面。
 *
 * @param url 当前播放地址（变化时自动切集并保存旧进度）
 * @param title 标题栏显示的标题
 * @param episodes 选集列表（为空则不显示选集按钮）
 * @param currentEpisode 当前集标识，用于在选集中高亮
 * @param onEpisodeClick 点击选集回调（调用方负责切 url）
 * @param onBack 返回回调
 */
@Composable
fun PlayerScreen(
    url: String,
    title: String,
    vodId: String? = null,
    episodes: List<String> = emptyList(),
    currentEpisode: String? = null,
    onEpisodeClick: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var speedIndex by remember { mutableIntStateOf(2) } // 默认 1.0x
    var resizeIndex by remember { mutableIntStateOf(0) } // 默认适应
    var showEpisodes by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showServiceMissingDialog by remember { mutableStateOf(false) }
    var gestureHint by remember { mutableStateOf<String?>(null) } // 手势临时指示
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(onBack = onBack)

    // DisposableEffect 的 onDispose 闭包捕获的是首次组合的值，
    // 用 rememberUpdatedState 保证退出时存的是最新的 url/title。
    val currentUrl by rememberUpdatedState(url)
    val currentTitle by rememberUpdatedState(title)

    // 沉浸式全屏 + 退出时保存进度
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            savePlayerProgress(context, currentTitle, currentUrl)
        }
    }

    // 播放 / 切集：先保存上一集进度，再播新地址（带断点续播）
    // url 为空表示暂无可播放地址（演示数据阶段），不启动播放器
    // magnet: 地址不直接播，走 MagnetPlayer 探测本机转播服务后再起播；
    // 历史记录一律用原始 url（磁力链接本身）做 key，保证磁力也能断点续播
    var lastUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        lastUrl?.let { prev -> savePlayerProgress(context, title, prev) }
        if (url.isNotBlank()) {
            val resumeAt = HistoryStore.getProgress(context, url)
                .takeIf { it > RESUME_THRESHOLD_MS } ?: 0L
            val started = if (MagnetPlayer.isMagnet(url)) {
                MagnetPlayer.playIfReady(
                    context = context,
                    magnet = url,
                    title = title,
                    startPositionMs = resumeAt,
                )
            } else {
                PlayerManager.init(context)
                // 网页嗅探命中的直链自带 Referer/UA（SniffPlayHeaders 一次性取出）；
                // 其他来源没有记录则为空 map，行为与之前一致。
                // v0.14.0：片头跳过（非续播时从 skipIntroSec 开始）
                // v0.16.2：按源取片头（siteKey 区分源）
                val skipIntro = PlayerSettings.getSkipIntroFor(context, vodId)
                val startPos = if (resumeAt > 0) resumeAt
                else if (skipIntro > 0) skipIntro * 1000L else 0L
                PlayerManager.play(url, headers = SniffPlayHeaders.take(url), startPositionMs = startPos)
                true
            }
            showServiceMissingDialog = !started
        }
        lastUrl = url
    }

    // 播放错误 -> Snackbar 提示
    DisposableEffect(Unit) {
        PlayerManager.setOnErrorListener { e ->
            errorMsg = e.message ?: "播放出错"
        }
        onDispose { PlayerManager.setOnErrorListener(null) }
    }
    LaunchedEffect(errorMsg) {
        errorMsg?.let {
            snackbarHostState.showSnackbar(it)
            errorMsg = null
        }
    }

    // v0.14.0：片尾跳过——距离结尾 skipOutroSec 时自动下一集（无下一集则暂停）
    // v0.16.2：按源取片尾
    LaunchedEffect(url) {
        val skipOutro = PlayerSettings.getSkipOutroFor(context, vodId)
        if (skipOutro > 0) {
            while (true) {
                delay(1000)
                val p = PlayerManager.player ?: break
                val dur = p.duration
                if (dur > 0 && p.currentPosition >= dur - skipOutro * 1000L) {
                    val idx = episodes.indexOf(currentEpisode)
                    if (idx >= 0 && idx + 1 < episodes.size) {
                        onEpisodeClick(episodes[idx + 1])
                    } else {
                        p.pause()
                    }
                    break
                }
            }
        }
    }

    // 手势指示条：显示约 0.9 秒后自动隐藏
    LaunchedEffect(gestureHint) {
        if (gestureHint != null) {
            delay(900)
            gestureHint = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (url.isBlank()) {
            Text(
                text = "暂无可播放地址",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }.also {
                        it.player = PlayerManager.player
                        playerView = it
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 手势层：左右滑动=±10 秒快进/快退；左半屏上下=亮度；右半屏上下=音量。
        // 轻点不消费事件，透传给 PlayerView 自带控制器，不影响现有控制 UI。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var mode = 0 // 0=未定 1=水平 2=垂直
                        val slopChange = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            mode = if (abs(dx) > abs(dy)) 1 else 2
                        }
                        if (slopChange == null || mode == 0) return@awaitEachGesture // 轻点：不消费
                        var last = slopChange.position
                        var accX = 0f
                        var accY = 0f
                        val leftHalf = down.position.x < size.width / 2
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            val dx = change.position.x - last.x
                            val dy = change.position.y - last.y
                            last = change.position
                            when (mode) {
                                1 -> {
                                    accX += dx
                                    if (abs(accX) >= SEEK_STEP_PX) {
                                        val dir = if (accX > 0) 1 else -1
                                        accX = 0f
                                        PlayerManager.player?.let { p ->
                                            val end = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                            p.seekTo(
                                                (p.currentPosition + dir * 10_000L)
                                                    .coerceIn(0L, end)
                                            )
                                            gestureHint =
                                                if (dir > 0) "快进 10 秒 »" else "« 快退 10 秒"
                                        }
                                    }
                                }
                                2 -> {
                                    accY += dy
                                    if (abs(accY) >= ADJUST_STEP_PX) {
                                        val up = accY < 0
                                        accY = 0f
                                        gestureHint = if (leftHalf) {
                                            val pct = adjustBrightness(context, up)
                                            if (pct >= 0) "亮度 $pct%" else null
                                        } else {
                                            val pct = adjustVolume(context, up)
                                            if (pct >= 0) "音量 $pct%" else null
                                        }
                                    }
                                }
                            }
                            change.consume()
                        } while (change.pressed)
                    }
                },
        )

        // 手势指示条：中央临时提示
        gestureHint?.let { hint ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(hint, color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }

        // 顶部栏：返回 + 标题 + 倍速 + 画面比例 + 选集
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    speedIndex = (speedIndex + 1) % SPEEDS.size
                    PlayerManager.player?.setPlaybackSpeed(SPEEDS[speedIndex])
                }
            ) {
                Text("${SPEEDS[speedIndex]}x", color = Color.White)
            }
            // v0.14.0：快退/快进 ±10 秒
            IconButton(onClick = {
                PlayerManager.player?.let {
                    it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0))
                }
            }) {
                Icon(Icons.Filled.Replay10, contentDescription = "快退10秒", tint = Color.White)
            }
            IconButton(onClick = {
                PlayerManager.player?.let {
                    it.seekTo(it.currentPosition + 10_000)
                }
            }) {
                Icon(Icons.Filled.Forward10, contentDescription = "快进10秒", tint = Color.White)
            }
            TextButton(
                onClick = {
                    resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size
                    playerView?.resizeMode = RESIZE_MODES[resizeIndex].first
                }
            ) {
                Text(RESIZE_MODES[resizeIndex].second, color = Color.White)
            }
            if (episodes.isNotEmpty()) {
                IconButton(onClick = { showEpisodes = true }) {
                    Icon(Icons.Filled.PlaylistPlay, contentDescription = "选集", tint = Color.White)
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        )

        if (showEpisodes) {
            EpisodeSheet(
                episodes = episodes,
                currentEpisode = currentEpisode,
                onDismiss = { showEpisodes = false },
                onEpisodeClick = {
                    showEpisodes = false
                    onEpisodeClick(it)
                },
            )
        }

        // 磁力转播服务未启动提示
        if (showServiceMissingDialog) {
            AlertDialog(
                onDismissRequest = { showServiceMissingDialog = false },
                title = { Text("磁力转播服务未启动") },
                text = {
                    Text("未检测到本机磁力转播服务（127.0.0.1:17890），请先在 DsPlayer 的服务页启动 magnet-stream 后再试")
                },
                confirmButton = {
                    TextButton(onClick = { showServiceMissingDialog = false }) {
                        Text("知道了")
                    }
                },
            )
        }
    }
}

/** 左半屏上下滑动：调节屏幕亮度。返回调节后的亮度百分比，失败返回 -1 */
private fun adjustBrightness(context: Context, up: Boolean): Int {
    val activity = context as? Activity ?: return -1
    val lp = activity.window.attributes
    val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
    val next = (cur + if (up) BRIGHTNESS_STEP else -BRIGHTNESS_STEP).coerceIn(0.01f, 1f)
    lp.screenBrightness = next
    activity.window.attributes = lp
    return (next * 100).toInt()
}

/** 右半屏上下滑动：调节媒体音量。返回调节后的音量百分比，失败返回 -1 */
private fun adjustVolume(context: Context, up: Boolean): Int {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return -1
    am.adjustStreamVolume(
        AudioManager.STREAM_MUSIC,
        if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
        0,
    )
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return -1
    return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
}

/** 读取当前进度并写入历史记录 */
private fun savePlayerProgress(context: Context, title: String, url: String) {
    val player = PlayerManager.player ?: return
    val position = player.currentPosition
    if (position <= 0) return
    HistoryStore.saveProgress(
        context = context,
        title = title,
        url = url,
        positionMs = position,
        durationMs = player.duration.coerceAtLeast(0L),
    )
}

/** 底部弹出的选集抽屉 */
@Composable
private fun EpisodeSheet(
    episodes: List<String>,
    currentEpisode: String?,
    onDismiss: () -> Unit,
    onEpisodeClick: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // 半透明遮罩，点击关闭
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onDismiss)
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.55f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "选集（${episodes.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(episodes) { _, episode ->
                        val selected = episode == currentEpisode
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEpisodeClick(episode) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Text(
                                text = episode,
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * v0.16.3：按剧片头片尾设置对话框。
 * 每部剧单独记开头/结束跳过秒数。
 */
@Composable
