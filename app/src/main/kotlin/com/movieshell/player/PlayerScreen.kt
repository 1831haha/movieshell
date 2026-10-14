package com.movieshell.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.media.AudioManager
import android.util.Rational
import android.widget.Toast
import com.movieshell.data.model.PlayLine
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PlayerEngine
import com.movieshell.data.repo.PlayerSettings
import android.view.ViewGroup
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import java.util.Locale
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * @param url 初始播放地址（详情页已解析好的直链）
 * @param title 标题栏显示的标题
 * @param vodId 剧 ID（按剧取片头片尾/字幕）
 * @param onBack 返回回调
 *
 * v0.19.0：线路/选集走 PlaySession（详情页进入前写入），播放器内可切线路；
 * 播放失败自动按线路顺序重试；支持外挂字幕与画中画。
 */
@Composable
fun PlayerScreen(
    url: String,
    title: String,
    vodId: String? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var speedIndex by remember { mutableIntStateOf(2) } // 默认 1.0x
    var resizeIndex by remember { mutableIntStateOf(0) } // 默认适应
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showServiceMissingDialog by remember { mutableStateOf(false) }
    var gestureHint by remember { mutableStateOf<String?>(null) } // 手势临时指示
    var showSkipSettings by remember { mutableStateOf(false) } // v0.16.3：按剧片头片尾设置
    // v0.19.0
    var playUrl by remember { mutableStateOf(url) } // 实际播放地址（切线路/重试时更新）
    var showLines by remember { mutableStateOf(false) } // 线路+选集切换抽屉
    var showSubtitleDialog by remember { mutableStateOf(false) } // 外挂字幕
    var sessionSubtitle by remember { mutableStateOf<String?>(null) } // 本次字幕覆盖（未设置则按剧记忆）
    var pipMode by remember { mutableStateOf(false) } // 画中画
    var autoSwitching by remember { mutableStateOf(false) } // playUrl 变化来自自动重试
    val triedLines = remember { mutableSetOf<Int>() } // 已试过的线路下标
    var lastHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val sessionLines = remember { PlaySession.lines } // 进入前详情页已写入
    val snackbarHostState = remember { SnackbarHostState() }
    // v0.20.0：双引擎——Media3（默认）/ libmpv，记忆选择
    val engine by PlayerSettings.engine.collectAsState()

    BackHandler(onBack = onBack)

    // DisposableEffect 的 onDispose 闭包捕获的是首次组合的值，
    // 用 rememberUpdatedState 保证退出时存的是最新的 url/title。
    val currentPlayUrl by rememberUpdatedState(playUrl)
    val currentTitle by rememberUpdatedState(title)

    // 沉浸式全屏 + 退出时保存进度、清理播放会话
    DisposableEffect(Unit) {
        PlaySession.playerVisible = true
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            savePlayerProgress(context, currentTitle, currentPlayUrl)
            PlaySession.playerVisible = false
            PlaySession.clear()
        }
    }

    // v0.19.0：画中画模式监听，小窗时隐藏自绘 UI（只留播放画面）
    // 签名以编译器为准：Consumer<PictureInPictureModeChangedInfo>
    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity
        if (activity == null) return@DisposableEffect onDispose {}
        pipMode = activity.isInPictureInPictureMode
        val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
            pipMode = info.isInPictureInPictureMode
        }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }

    // 播放 / 切集：先保存上一集进度，再播新地址（带断点续播）
    // playUrl 为空表示暂无可播放地址，不启动播放器
    // magnet: 地址不直接播，走 MagnetPlayer 探测本机转播服务后再起播；
    // 历史记录一律用原始 url（磁力链接本身）做 key，保证磁力也能断点续播
    var lastUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playUrl) {
        if (!autoSwitching) triedLines.clear() else autoSwitching = false
        lastUrl?.let { prev -> savePlayerProgress(context, title, prev) }
        if (playUrl.isNotBlank()) {
            val resumeAt = HistoryStore.getProgress(context, playUrl)
                .takeIf { it > RESUME_THRESHOLD_MS } ?: 0L
            val started = if (MagnetPlayer.isMagnet(playUrl)) {
                MagnetPlayer.playIfReady(
                    context = context,
                    magnet = playUrl,
                    title = title,
                    startPositionMs = resumeAt,
                )
            } else {
                // 网页嗅探命中的直链自带 Referer/UA（SniffPlayHeaders 一次性取出）；
                // 其他来源没有记录则为空 map，行为与之前一致。
                // v0.14.0：片头跳过（非续播时从 skipIntroSec 开始）
                // v0.16.2：按源取片头（siteKey 区分源）
                // v0.20.0：走 EngineHost，按当前内核（Media3/libmpv）起播
                val headers = SniffPlayHeaders.take(playUrl)
                lastHeaders = headers
                val skipIntro = PlayerSettings.getSkipIntroFor(context, vodId)
                val startPos = if (resumeAt > 0) resumeAt
                else if (skipIntro > 0) skipIntro * 1000L else 0L
                // v0.19.0：外挂字幕（本次覆盖优先，否则按剧记忆）
                val subtitleUrl = (sessionSubtitle
                    ?: vodId?.let { PlayerSettings.getSubtitleFor(context, it) }.orEmpty())
                    .ifBlank { null }
                EngineHost.play(
                    context,
                    playUrl,
                    headers = headers,
                    startPositionMs = startPos,
                    subtitleUrl = subtitleUrl,
                )
                true
            }
            showServiceMissingDialog = !started
        }
        lastUrl = playUrl
    }

    /**
     * v0.19.0：切到指定线路/选集（线路抽屉、片尾自动下一集共用）。
     * 解析失败由 resolvePlayUrl 内部 Toast，不切。
     */
    fun switchEpisode(lineIdx: Int, epIdx: Int) {
        val resolver = PlaySession.resolver ?: return
        val ep = PlaySession.lines.getOrNull(lineIdx)?.episodes?.getOrNull(epIdx) ?: return
        scope.launch {
            val link = resolver(context, ep)
            if (link.isNotBlank()) {
                PlaySession.moveTo(lineIdx, epIdx)
                triedLines.clear()
                playUrl = link
            }
        }
    }

    /** v0.19.0：字幕变更后按当前位置重播（字幕需要重建 MediaSource 才能生效）。 */
    fun replayWithSubtitle() {
        val pos = EngineHost.currentPosition()
        val subtitleUrl = (sessionSubtitle
            ?: vodId?.let { PlayerSettings.getSubtitleFor(context, it) }.orEmpty())
            .ifBlank { null }
        EngineHost.play(context, playUrl, headers = lastHeaders, startPositionMs = pos, subtitleUrl = subtitleUrl)
    }

    /**
     * v0.20.0：播放器内一键切换内核（Media3 ↔ libmpv）。
     * 停掉旧引擎、持久化选择、按当前进度重播；AndroidView 用 key(engine) 重建对应画面。
     */
    fun switchEngine() {
        val pos = EngineHost.currentPosition()
        val target = if (engine == PlayerEngine.MEDIA3) PlayerEngine.LIBMPV else PlayerEngine.MEDIA3
        EngineHost.stop()
        PlayerSettings.setEngine(context, target)
        val subtitleUrl = (sessionSubtitle
            ?: vodId?.let { PlayerSettings.getSubtitleFor(context, it) }.orEmpty())
            .ifBlank { null }
        Toast.makeText(
            context,
            "播放内核已切换为 ${if (target == PlayerEngine.MEDIA3) "Media3" else "libmpv"}",
            Toast.LENGTH_SHORT,
        ).show()
        LogBus.i("Player", "切换播放内核 → $target")
        EngineHost.play(context, playUrl, headers = lastHeaders, startPositionMs = pos, subtitleUrl = subtitleUrl)
    }

    // v0.19.0：播放错误 -> 按线路顺序自动重试（同集换线路）；
    // 全部线路试完仍失败才 Snackbar 提示。无 PlaySession（嗅探页直进）时保持原行为。
    // v0.20.0：错误回调走 EngineHost，Media3/libmpv 双引擎统一消息字符串。
    DisposableEffect(Unit) {
        EngineHost.setOnErrorListener { msg ->
            val lines = PlaySession.lines
            val resolver = PlaySession.resolver
            val curLine = PlaySession.lineIndex
            if (resolver != null && lines.size > 1 && curLine !in triedLines) {
                triedLines.add(curLine)
                scope.launch {
                    var next = (curLine + 1) % lines.size
                    var link = ""
                    var targetLine = -1
                    var targetEpIdx = 0
                    var guard = 0
                    while (guard < lines.size && link.isBlank()) {
                        if (next !in triedLines) {
                            triedLines.add(next)
                            val line = lines[next]
                            val epIdx = PlaySession.episodeIndex
                                .coerceIn(0, (line.episodes.size - 1).coerceAtLeast(0))
                            val ep = line.episodes.getOrNull(epIdx)
                            // 磁力不参与自动重试（走 MagnetPlayer 分支，无播放器错误可重试）
                            if (ep != null && !MagnetPlayer.isMagnet(ep.url)) {
                                val resolved = resolver(context, ep)
                                if (resolved.isNotBlank() && resolved != playUrl) {
                                    link = resolved
                                    targetLine = next
                                    targetEpIdx = epIdx
                                }
                            }
                        }
                        next = (next + 1) % lines.size
                        guard++
                    }
                    if (link.isNotBlank()) {
                        PlaySession.moveTo(targetLine, targetEpIdx)
                        Toast.makeText(
                            context,
                            "已自动切换到「${lines[targetLine].name}」",
                            Toast.LENGTH_SHORT,
                        ).show()
                        LogBus.i("Player", "播放失败，自动换线路：${lines[targetLine].name}")
                        autoSwitching = true
                        playUrl = link
                    } else {
                        errorMsg = "所有线路均播放失败"
                    }
                }
            } else {
                errorMsg = msg
            }
        }
        onDispose { EngineHost.setOnErrorListener(null) }
    }
    LaunchedEffect(errorMsg) {
        errorMsg?.let {
            snackbarHostState.showSnackbar(it)
            errorMsg = null
        }
    }

    // v0.14.0：片尾跳过——距离结尾 skipOutroSec 时自动下一集（无下一集则暂停）
    // v0.16.2：按源取片尾；v0.19.0：下一集走 PlaySession（播放器内线路/选集）
    // v0.20.0：进度/时长走 EngineHost，双引擎统一
    LaunchedEffect(playUrl) {
        val skipOutro = PlayerSettings.getSkipOutroFor(context, vodId)
        if (skipOutro > 0) {
            while (true) {
                delay(1000)
                val dur = EngineHost.duration()
                if (dur > 0 && EngineHost.currentPosition() >= dur - skipOutro * 1000L) {
                    val nextIdx = PlaySession.episodeIndex + 1
                    val hasNext = PlaySession.currentLine()?.episodes?.getOrNull(nextIdx) != null
                    if (hasNext) {
                        switchEpisode(PlaySession.lineIndex, nextIdx)
                    } else {
                        EngineHost.pause()
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
        if (playUrl.isBlank()) {
            Text(
                text = "暂无可播放地址",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            // v0.20.0：双引擎渲染二选一——Media3 用 PlayerView，libmpv 用 MpvVideoView（SurfaceView）；
            // key(engine) 保证切换内核时重建画面
            key(engine) {
                AndroidView(
                    factory = { ctx ->
                        if (engine == PlayerEngine.LIBMPV) {
                            MpvPlayerManager.init(ctx)
                            MpvVideoView(ctx)
                        } else {
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
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // v0.19.0：画中画时只留播放画面，全部自绘 UI 隐藏
        if (!pipMode) {
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
                                        // v0.20.0：走 EngineHost，双引擎统一
                                        val end = EngineHost.duration().takeIf { it > 0 } ?: Long.MAX_VALUE
                                        EngineHost.seekTo(
                                            (EngineHost.currentPosition() + dir * 10_000L)
                                                .coerceIn(0L, end)
                                        )
                                        gestureHint =
                                            if (dir > 0) "快进 10 秒 »" else "« 快退 10 秒"
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

        // 顶部栏：返回 + 标题 + 倍速 + 快进快退 + 画面比例 + 线路/选集 + 字幕 + 画中画 + 跳过设置
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
                    EngineHost.setPlaybackSpeed(SPEEDS[speedIndex])
                }
            ) {
                Text("${SPEEDS[speedIndex]}x", color = Color.White)
            }
            // v0.14.0：快退/快进 ±10 秒（v0.20.0 走 EngineHost，双引擎统一）
            IconButton(onClick = {
                EngineHost.seekTo((EngineHost.currentPosition() - 10_000).coerceAtLeast(0))
            }) {
                Icon(Icons.Filled.Replay10, contentDescription = "快退10秒", tint = Color.White)
            }
            IconButton(onClick = {
                EngineHost.seekTo(EngineHost.currentPosition() + 10_000)
            }) {
                Icon(Icons.Filled.Forward10, contentDescription = "快进10秒", tint = Color.White)
            }
            TextButton(
                onClick = {
                    resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size
                    // v0.20.0：Media3 走 PlayerView，libmpv 走 mpv 的画面比例选项
                    if (engine == PlayerEngine.MEDIA3) {
                        playerView?.resizeMode = RESIZE_MODES[resizeIndex].first
                    } else {
                        MpvPlayerManager.setAspectMode(resizeIndex)
                    }
                }
            ) {
                Text(RESIZE_MODES[resizeIndex].second, color = Color.White)
            }
            // v0.20.0：播放器内一键切换内核（Media3 ↔ libmpv），选择会被记住
            TextButton(onClick = { switchEngine() }) {
                Text(
                    if (engine == PlayerEngine.MEDIA3) "Media3" else "libmpv",
                    color = Color.White,
                )
            }
            // v0.19.0：播放器内线路/选集切换（多条线路才显示）
            if (sessionLines.size > 1) {
                TextButton(onClick = { showLines = true }) {
                    Text(
                        PlaySession.currentLine()?.name?.take(4) ?: "线路",
                        color = Color.White,
                    )
                }
            } else if (sessionLines.size == 1 && (sessionLines[0].episodes.size > 1)) {
                TextButton(onClick = { showLines = true }) {
                    Text("选集", color = Color.White)
                }
            }
            // v0.19.0：外挂字幕
            IconButton(onClick = { showSubtitleDialog = true }) {
                Icon(Icons.Filled.Subtitles, contentDescription = "字幕", tint = Color.White)
            }
            // v0.19.0：画中画
            IconButton(onClick = { enterPip(context) }) {
                Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "画中画", tint = Color.White)
            }
            // v0.16.3：按剧片头片尾设置
            IconButton(onClick = { showSkipSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "本剧跳过设置", tint = Color.White)
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        )

        // v0.20.0：libmpv 模式没有 PlayerView 自带控制器，补一个极简底部条（播放/暂停+进度拖动）；
        // 手势与顶部栏功能不受影响
        if (engine == PlayerEngine.LIBMPV && playUrl.isNotBlank()) {
            var mpvPlaying by remember { mutableStateOf(true) }
            var mpvPos by remember { mutableLongStateOf(0L) }
            var mpvDur by remember { mutableLongStateOf(0L) }
            var scrubbing by remember { mutableStateOf(false) }
            var scrubPos by remember { mutableLongStateOf(0L) }
            LaunchedEffect(engine, playUrl) {
                while (true) {
                    delay(500)
                    mpvPlaying = MpvPlayerManager.isPlaying
                    if (!scrubbing) mpvPos = EngineHost.currentPosition()
                    mpvDur = EngineHost.duration()
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (mpvPlaying) EngineHost.pause() else EngineHost.resume()
                }) {
                    Icon(
                        if (mpvPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (mpvPlaying) "暂停" else "播放",
                        tint = Color.White,
                    )
                }
                Text(
                    text = fmtTime(if (scrubbing) scrubPos else mpvPos),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = (if (scrubbing) scrubPos else mpvPos).toFloat(),
                    onValueChange = {
                        scrubPos = it.toLong()
                        scrubbing = true
                    },
                    onValueChangeFinished = {
                        EngineHost.seekTo(scrubPos)
                        scrubbing = false
                    },
                    valueRange = 0f..mpvDur.coerceAtLeast(1L).toFloat(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    text = fmtTime(mpvDur),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // v0.19.0：线路+选集抽屉
        if (showLines) {
            LineSheet(
                lines = sessionLines,
                lineIndex = PlaySession.lineIndex,
                episodeIndex = PlaySession.episodeIndex,
                onDismiss = { showLines = false },
                onPick = { lineIdx, epIdx ->
                    showLines = false
                    switchEpisode(lineIdx, epIdx)
                },
            )
        }

        // v0.19.0：外挂字幕设置
        if (showSubtitleDialog) {
            SubtitleDialog(
                initial = sessionSubtitle
                    ?: vodId?.let { PlayerSettings.getSubtitleFor(context, it) }.orEmpty(),
                onDismiss = { showSubtitleDialog = false },
                onSave = { url ->
                    val clean = url.trim()
                    sessionSubtitle = clean.ifBlank { null }
                    if (vodId != null) PlayerSettings.setSubtitleFor(context, vodId, clean)
                    showSubtitleDialog = false
                    replayWithSubtitle()
                },
            )
        }

        // v0.16.3：按剧片头片尾设置
        if (showSkipSettings && vodId != null) {
            SkipSettingsDialog(
                vodId = vodId,
                onDismiss = { showSkipSettings = false },
            )
        }

        } // if (!pipMode)：画中画时只留播放画面

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

/** 毫秒 → h:mm:ss（v0.20.0：libmpv 底部条用；US locale 避免数字本地化） */
private fun fmtTime(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

/** 读取当前进度并写入历史记录（v0.20.0：走 EngineHost，双引擎统一） */
private fun savePlayerProgress(context: Context, title: String, url: String) {
    val position = EngineHost.currentPosition()
    if (position <= 0) return
    HistoryStore.saveProgress(
        context = context,
        title = title,
        url = url,
        positionMs = position,
        durationMs = EngineHost.duration(),
    )
}

/** v0.19.0：手动进画中画 */
private fun enterPip(context: Context) {
    val activity = context as? Activity ?: return
    if (activity.isInPictureInPictureMode) return
    runCatching {
        activity.enterPictureInPictureMode(
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
        )
        LogBus.i("Player", "手动进入画中画")
    }.onFailure { LogBus.w("Player", "进入画中画失败：${it.message}") }
}

/**
 * v0.19.0：播放器内线路+选集切换抽屉。
 * 上方线路横滑 chips，下方该线路的选集列表；当前播放高亮。
 */
@Composable
private fun LineSheet(
    lines: List<PlayLine>,
    lineIndex: Int,
    episodeIndex: Int,
    onDismiss: () -> Unit,
    onPick: (lineIdx: Int, epIdx: Int) -> Unit,
) {
    var tab by remember { mutableIntStateOf(lineIndex.coerceIn(lines.indices)) }
    val tabEpisodes = lines.getOrNull(tab)?.episodes.orEmpty()
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
                .fillMaxHeight(0.6f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "线路 / 选集",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(lines) { i, line ->
                        FilterChip(
                            selected = i == tab,
                            onClick = { tab = i },
                            label = { Text(line.name) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(tabEpisodes) { i, ep ->
                        val selected = tab == lineIndex && i == episodeIndex
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(tab, i) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Text(
                                text = ep.name.ifBlank { "第${i + 1}集" },
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
 * v0.19.0：外挂字幕设置对话框。填 .srt/.vtt 地址，保存后按当前位置重播生效；
 * 按剧记忆（vodId 为空时仅本次有效）。
 */
@Composable
private fun SubtitleDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var url by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("外挂字幕") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "支持 .srt / .vtt 地址，保存后立即生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("字幕地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(url) }) {
                Text("保存并应用")
            }
        },
        dismissButton = {
            TextButton(onClick = { onSave("") }) {
                Text("清除")
            }
        },
    )
}

/**
 * v0.16.3：按剧片头片尾设置对话框。
 * 每部剧单独记开头/结束跳过秒数。
 */
@Composable
private fun SkipSettingsDialog(
    vodId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var intro by remember { mutableStateOf(PlayerSettings.getSkipIntroFor(context, vodId).toString()) }
    var outro by remember { mutableStateOf(PlayerSettings.getSkipOutroFor(context, vodId).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本剧跳过设置") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "每部剧单独设置，互不影响",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = intro,
                    onValueChange = { intro = it.filter { c -> c.isDigit() } },
                    label = { Text("跳过片头（秒）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = outro,
                    onValueChange = { outro = it.filter { c -> c.isDigit() } },
                    label = { Text("跳过片尾（秒）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val introSec = intro.toIntOrNull()?.coerceIn(0, 600) ?: 0
                    val outroSec = outro.toIntOrNull()?.coerceIn(0, 600) ?: 0
                    PlayerSettings.setSkipIntroFor(context, vodId, introSec)
                    PlayerSettings.setSkipOutroFor(context, vodId, outroSec)
                    LogBus.i("UI", "设置本剧跳过: 片头${introSec}s 片尾${outroSec}s")
                    onDismiss()
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
