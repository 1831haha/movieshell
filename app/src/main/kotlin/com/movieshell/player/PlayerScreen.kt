package com.movieshell.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.util.Rational
import android.widget.Toast
import com.movieshell.data.model.PlayLine
import com.movieshell.data.repo.DownloadSettings
import com.movieshell.data.repo.DownloadStore
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PlayerEngine
import com.movieshell.data.repo.PlayerSettings
import com.movieshell.data.repo.SubtitleStyle
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppChip
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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

/** v0.21.0：手势——按住超过此时长且未滑动视为长按，进 2x 快放 */
private const val LONG_PRESS_MS = 400L

/**
 * 全屏播放器界面。
 *
 * @param url 初始播放地址（详情页已解析好的直链）
 * @param title 标题栏显示的标题
 * @param vodId 剧 ID（按剧取片头片尾/字幕）
 * @param isLive v0.24.0：直播模式——隐藏线路/选集/按剧跳过等点播控件，标题旁加“直播”角标
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
    isLive: Boolean = false,
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
    var showOnlineSubtitle by remember { mutableStateOf(false) } // v0.28.0：在线字幕搜索
    var sessionSubtitle by remember { mutableStateOf<String?>(null) } // 本次字幕覆盖（未设置则按剧记忆）
    var pipMode by remember { mutableStateOf(false) } // 画中画
    // v0.54：弹幕（只看不发；用户明确发送功能永远不做）
    val danmakuEnabled by com.movieshell.player.danmaku.DanmakuSettings.enabled.collectAsState()
    var danmakuList by remember { mutableStateOf(emptyList<com.movieshell.player.danmaku.Danmaku>()) }
    var showDanmakuSettings by remember { mutableStateOf(false) } // v0.56
    // v0.27.7：自绘控制层显隐（Media3 原生控制器已关闭，双引擎统一）；播放状态从引擎取
    var controlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    // v0.40.0：锁屏模式（锁定后只留解锁按钮，防误触）+ 右上角更多菜单
    var locked by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var autoSwitching by remember { mutableStateOf(false) } // playUrl 变化来自自动重试
    val triedLines = remember { mutableSetOf<Int>() } // 已试过的线路下标
    // v0.21.0：同地址已试过的内核（防 Media3↔libmpv 乒乓）
    val triedEngines = remember { mutableSetOf<PlayerEngine>() }
    var lastHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showSleepDialog by remember { mutableStateOf(false) } // v0.21.0：定时关闭
    val sessionLines = remember { PlaySession.lines } // 进入前详情页已写入
    val snackbarHostState = remember { SnackbarHostState() }
    // v0.20.0：双引擎——Media3（默认）/ libmpv，记忆选择
    // v0.21.0：engineOverride 为自动换内核的临时覆盖（静默，不持久化），优先于用户设置
    val engineSetting by PlayerSettings.engine.collectAsState()
    val engineOverride by EngineHost.engineOverride.collectAsState()
    val engine = engineOverride ?: engineSetting
    // v0.21.0：定时关闭状态
    val sleepRemaining by SleepTimer.remainingMs.collectAsState()
    val sleepArmed by SleepTimer.afterEpisodeArmed.collectAsState()

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
            savePlayerProgress(context, currentTitle, currentPlayUrl, isLive)
            // v0.63.0：看完自动删——本地下载文件播到 95% 以上且开关开着，删文件+清任务
            maybeAutoDeleteWatched(context, currentPlayUrl, isLive)
            PlaySession.playerVisible = false
            PlaySession.clear()
            // v0.27.7：退出播放器恢复系统默认方向 + 停重力感应
            OrientationSensor.stop()
            (context as? Activity)?.let {
                if (it.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
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

    // v0.27.7：重力感应自动横屏（设置→播放→开关，默认开）。sensor 监听收敛在
    // OrientationSensor 单例里，这里只消费方向变化。
    val gravityRotate by PlayerSettings.gravityRotate.collectAsState()
    val sensorLandscape by OrientationSensor.isLandscape.collectAsState()
    LaunchedEffect(gravityRotate) {
        if (gravityRotate) OrientationSensor.start(context) else OrientationSensor.stop()
    }
    LaunchedEffect(sensorLandscape, gravityRotate) {
        if (!gravityRotate) return@LaunchedEffect
        val activity = context as? Activity ?: return@LaunchedEffect
        activity.requestedOrientation = if (sensorLandscape) {
            LogBus.i("Player", "重力感应：切横屏")
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /** v0.27.7：手动横竖屏切换（控制条全屏按钮）。 */
    fun toggleOrientation() {
        val activity = context as? Activity ?: return
        val landscape =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        activity.requestedOrientation = if (landscape) {
            LogBus.i("Player", "手动：切竖屏")
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            LogBus.i("Player", "手动：切横屏")
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    /**
     * v0.21.0：当前字幕地址（含时间偏移处理）。双引擎统一走 SubtitleKit 单入口：
     * 偏移为 0 直接返回原地址；非 0 则下载移位后返回 file:// 本地地址。
     * 含网络 IO，必须在协程里调用。
     */
    suspend fun currentSubtitleUrl(): String? {
        val raw = (sessionSubtitle
            ?: vodId?.let { PlayerSettings.getSubtitleFor(context, it) }.orEmpty())
            .ifBlank { null }
        return SubtitleKit.prepare(
            context, raw, lastHeaders, PlayerSettings.subtitleStyle.value.offsetMs
        )
    }

    // 播放 / 切集：先保存上一集进度，再播新地址（带断点续播）
    // playUrl 为空表示暂无可播放地址，不启动播放器
    // magnet: 地址不直接播，走 MagnetPlayer 探测本机转播服务后再起播；
    // 历史记录一律用原始 url（磁力链接本身）做 key，保证磁力也能断点续播
    var lastUrl by remember { mutableStateOf<String?>(null) }
    // v0.25.0：起播链路顶层兜底——任何未预期异常转 Toast，绝不崩
    // v0.54：弹幕开关变化时按需加载/清空（不重进播放器也能开关）
    LaunchedEffect(danmakuEnabled) {
        if (danmakuEnabled && title.isNotBlank() && !isLive && danmakuList.isEmpty()) {
            danmakuList = com.movieshell.player.danmaku.DanmakuSource.fetch(title)
        } else if (!danmakuEnabled) {
            danmakuList = emptyList()
        }
    }

    LaunchedEffect(playUrl) {
        // v0.54：弹幕预加载（按标题搜，不阻塞播放；失败静默无弹幕）
        if (danmakuEnabled && title.isNotBlank() && !isLive) {
            launch {
                danmakuList = com.movieshell.player.danmaku.DanmakuSource.fetch(title)
            }
        } else if (!danmakuEnabled) {
            danmakuList = emptyList()
        }
        try {
            if (!autoSwitching) triedLines.clear() else autoSwitching = false
            // v0.21.0：换地址清除自动换内核覆盖，回到用户持久化选择
            EngineHost.setEngineOverride(null)
            triedEngines.clear()
            lastUrl?.let { prev -> savePlayerProgress(context, title, prev, isLive) }
            if (playUrl.isNotBlank()) {
                val resumeAt = HistoryStore.getProgress(context, playUrl)
                    .takeIf { it > RESUME_THRESHOLD_MS } ?: 0L
                if (MagnetPlayer.isMagnet(playUrl)) {
                    // v0.25.0：服务缺失对话框只属于磁力分支，不与普通起播失败混用
                    showServiceMissingDialog = !MagnetPlayer.playIfReady(
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
                    // v0.21.0：走 SubtitleKit（含时间偏移），双引擎统一
                    // v0.25.0：字幕是独立子系统，坏了不影响主链路（无字幕起播）
                    val subtitleUrl = runCatching { currentSubtitleUrl() }
                        .onFailure {
                            LogBus.w("Player", "字幕准备失败，不挂字幕：${it.message}")
                        }
                        .getOrNull()
                    val ok = EngineHost.play(
                        context,
                        playUrl,
                        headers = headers,
                        startPositionMs = startPos,
                        subtitleUrl = subtitleUrl,
                    )
                    // v0.21.0：应用字幕样式
                    if (ok) {
                        EngineHost.applySubtitleStyle(
                            PlayerSettings.subtitleStyle.value, playerView
                        )
                    } else {
                        errorMsg = "播放启动失败，请检查地址或切换线路"
                    }
                }
            }
            lastUrl = playUrl
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            LogBus.e("Player", "起播链路异常：${t::class.java.simpleName}: ${t.message}")
            Toast.makeText(context, "播放启动失败，请重试", Toast.LENGTH_SHORT).show()
        }
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

    /**
     * v0.19.0：字幕变更后按当前位置重播（字幕需要重建 MediaSource 才能生效）。
     * v0.21.0：含时间偏移处理 + 样式应用，走协程（SubtitleKit 可能下载）。
     * v0.25.0：try/catch 兜底，失败 Toast 不崩。
     */
    // v0.57：播放失败改走友好错误对话框（原来只 Snackbar 一闪而过，看不清也点不了）
    // 注意：状态声明必须放在引用它的局部函数（retryCurrent）之前，Kotlin 局部函数看不到后声明的局部变量
    var playError by remember { mutableStateOf<PlayError?>(null) }
    LaunchedEffect(errorMsg) {
        errorMsg?.let {
            playError = PlayError.classify(it)
            errorMsg = null
        }
    }
    // v0.57：错误对话框的"重试"——按当前进度重播当前地址
    fun retryCurrent() {
        scope.launch {
            try {
                val pos = EngineHost.currentPosition()
                val sub = runCatching { currentSubtitleUrl() }.getOrNull()
                val ok = EngineHost.play(
                    context, playUrl, headers = lastHeaders,
                    startPositionMs = pos, subtitleUrl = sub,
                )
                if (!ok) {
                    playError = PlayError.classify("重试失败")
                }
            } catch (t: Throwable) {
                LogBus.e("Player", "重试异常：${t.message}")
                playError = PlayError.classify(t.message.orEmpty())
            }
        }
    }

    fun replayWithSubtitle() {
        scope.launch {
            try {
                val pos = EngineHost.currentPosition()
                val sub = runCatching { currentSubtitleUrl() }.getOrNull()
                val ok = EngineHost.play(
                    context, playUrl, headers = lastHeaders,
                    startPositionMs = pos, subtitleUrl = sub,
                )
                if (ok) EngineHost.applySubtitleStyle(PlayerSettings.subtitleStyle.value, playerView)
                else Toast.makeText(context, "字幕应用失败", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                LogBus.e("Player", "重播异常：${t.message}")
                Toast.makeText(context, "操作失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // v0.25.0：切换中防抖（快速连点不叠请求）
    var switchingEngine by remember { mutableStateOf(false) }

    /**
     * v0.20.0：播放器内一键切换内核（Media3 ↔ libmpv）。
     * 停掉旧引擎、持久化选择、按当前进度重播；AndroidView 用 key(engine) 重建对应画面。
     * v0.21.0：手动切换先清掉自动换内核覆盖；字幕走 SubtitleKit。
     * v0.25.0：try/catch 兜底 + 防抖，切换失败 Toast 不崩。
     */
    fun switchEngine() {
        if (switchingEngine) return
        switchingEngine = true
        scope.launch {
            try {
                val pos = EngineHost.currentPosition()
                val target =
                    if (EngineHost.engine() == PlayerEngine.MEDIA3) PlayerEngine.LIBMPV
                    else PlayerEngine.MEDIA3
                EngineHost.stop()
                EngineHost.setEngineOverride(null)
                PlayerSettings.setEngine(context, target)
                Toast.makeText(
                    context,
                    "播放内核已切换为 ${if (target == PlayerEngine.MEDIA3) "Media3" else "libmpv"}",
                    Toast.LENGTH_SHORT,
                ).show()
                LogBus.i("Player", "切换播放内核 → $target")
                val sub = runCatching { currentSubtitleUrl() }.getOrNull()
                val ok = EngineHost.play(
                    context, playUrl, headers = lastHeaders,
                    startPositionMs = pos, subtitleUrl = sub,
                )
                if (ok) EngineHost.applySubtitleStyle(PlayerSettings.subtitleStyle.value, playerView)
                else Toast.makeText(context, "切换后起播失败", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                LogBus.e("Player", "手动切换内核异常：${t::class.java.simpleName}: ${t.message}")
                Toast.makeText(context, "切换内核失败", Toast.LENGTH_SHORT).show()
            } finally {
                switchingEngine = false
            }
        }
    }

    /**
     * v0.19.0：播放错误 → 按线路顺序自动重试（同集换线路）。
     * v0.25.0：抽成独立函数，自动换内核失败时复用；全部试完仍失败才 Snackbar。
     */
    fun tryNextLine(fallbackMsg: String) {
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
            errorMsg = fallbackMsg
        }
    }

    // v0.19.0：播放错误 -> 按线路顺序自动重试（同集换线路）；
    // 全部线路试完仍失败才 Snackbar 提示。无 PlaySession（嗅探页直进）时保持原行为。
    // v0.20.0：错误回调走 EngineHost，Media3/libmpv 双引擎统一消息字符串。
    DisposableEffect(Unit) {
        EngineHost.setOnErrorListener { msg ->
            // v0.21.0：播放失败先自动换内核（同地址只换一次，静默不持久化）；
            // 双内核都失败才走下面的线路重试。
            // v0.25.0：换内核本身抛异常 → Toast 降级走线路重试，绝不崩。
            val cur = EngineHost.engine()
            val other =
                if (cur == PlayerEngine.MEDIA3) PlayerEngine.LIBMPV else PlayerEngine.MEDIA3
            if (other !in triedEngines) {
                triedEngines.add(cur)
                triedEngines.add(other)
                try {
                    LogBus.i("Player", "播放失败，自动换内核重试：$cur → $other")
                    EngineHost.stop()
                    EngineHost.setEngineOverride(other)
                    val pos = EngineHost.currentPosition()
                    scope.launch {
                        val sub = runCatching { currentSubtitleUrl() }.getOrNull()
                        val ok = EngineHost.play(
                            context, playUrl, headers = lastHeaders,
                            startPositionMs = pos, subtitleUrl = sub,
                        )
                        if (ok) {
                            EngineHost.applySubtitleStyle(
                                PlayerSettings.subtitleStyle.value, playerView
                            )
                        } else {
                            tryNextLine(msg)
                        }
                    }
                } catch (t: Throwable) {
                    LogBus.e(
                        "Player",
                        "自动换内核异常：${t::class.java.simpleName}: ${t.message}",
                    )
                    Toast.makeText(context, "切换内核失败，尝试换线路", Toast.LENGTH_SHORT).show()
                    tryNextLine(msg)
                }
                return@setOnErrorListener
            }
            tryNextLine(msg)
        }
        onDispose { EngineHost.setOnErrorListener(null) }
    }
    playError?.let { err ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { playError = null },
            title = { androidx.compose.material3.Text(err.title) },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(err.reason)
                    androidx.compose.foundation.layout.Spacer(
                        Modifier.size(8.dp)
                    )
                    err.suggestions.forEach { s ->
                        androidx.compose.material3.Text(
                            text = "• $s",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        playError = null
                        // 重试当前地址
                        scope.launch { retryCurrent() }
                    }
                ) {
                    androidx.compose.material3.Text("重试")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { playError = null }) {
                    androidx.compose.material3.Text("关闭")
                }
            },
        )
    }

    // v0.14.0：片尾跳过——距离结尾 skipOutroSec 时自动下一集（无下一集则暂停）
    // v0.16.2：按源取片尾；v0.19.0：下一集走 PlaySession（播放器内线路/选集）
    // v0.20.0：进度/时长走 EngineHost，双引擎统一
    // v0.21.0：定时关闭「播完本集」布防时，集尾暂停而非切下一集
    LaunchedEffect(playUrl, sleepArmed) {
        // v0.24.0：直播无“集”概念，跳过片尾下一集/播完暂停逻辑
        if (isLive) return@LaunchedEffect
        val skipOutro = PlayerSettings.getSkipOutroFor(context, vodId)
        if (skipOutro <= 0 && !sleepArmed) return@LaunchedEffect
        while (true) {
            delay(1000)
            val dur = EngineHost.duration()
            if (dur <= 0) continue
            val pos = EngineHost.currentPosition()
            if (sleepArmed && pos >= dur - 2000) {
                if (SleepTimer.consumeAfterEpisode()) {
                    EngineHost.pause()
                    Toast.makeText(context, "定时关闭：本集播完已暂停", Toast.LENGTH_SHORT).show()
                    LogBus.i("Player", "定时关闭触发：播完本集暂停")
                }
                break
            }
            if (skipOutro > 0 && pos >= dur - skipOutro * 1000L) {
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = SoftType.Body,
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
                                // v0.27.7：关闭 Media3 原生控制器，走自绘统一控制层（中央播放键+底部条），
                                // 双引擎 UI 一致，之前用户反馈找不到暂停/播放
                                useController = false
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
            // v0.54：弹幕覆盖层（视频画面之上，手势层之下）
            com.movieshell.player.danmaku.DanmakuOverlay(
                danmakus = danmakuList,
                positionMs = EngineHost.currentPosition(),
                enabled = danmakuEnabled && !playUrl.isBlank(),
                modifier = Modifier.fillMaxSize(),
            )
        }

        // v0.19.0：画中画时只留播放画面，全部自绘 UI 隐藏
        if (!pipMode) {
        // 手势层：左右滑动=±10 秒快进/快退；左半屏上下=亮度；右半屏上下=音量。
        // v0.27.7：轻点切换自绘控制层显隐（Media3 原生控制器已关闭，不再透传）。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var mode = 0 // 0=未定 1=水平 2=垂直
                        // v0.21.0：400ms 内无滑动视为长按 → 2x 快放，松手恢复原倍速
                        var longPressed = false
                        val slopChange = try {
                            withTimeout(LONG_PRESS_MS) {
                                awaitTouchSlopOrCancellation(down.id) { change, _ ->
                                    val dx = change.position.x - down.position.x
                                    val dy = change.position.y - down.position.y
                                    mode = if (abs(dx) > abs(dy)) 1 else 2
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            longPressed = true
                            null
                        }
                        if (longPressed) {
                            if (playUrl.isNotBlank()) {
                                EngineHost.setPlaybackSpeed(2.0f)
                                gestureHint = "2x 快放中…"
                                // 等待松手（消费事件，避免透传点中控制器）
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val c = event.changes.firstOrNull { it.id == down.id }
                                    c?.consume()
                                    if (c == null || !c.pressed) break
                                }
                                EngineHost.setPlaybackSpeed(SPEEDS[speedIndex])
                                gestureHint = null
                            }
                            return@awaitEachGesture
                        }
                        if (slopChange == null || mode == 0) {
                            // v0.27.7：轻点切换自绘控制层显隐（Media3 原生控制器已关闭）
                            // v0.40.0：锁定时轻点不切换控制层（防误触，只留解锁按钮）
                            if (!locked) controlsVisible = !controlsVisible
                            return@awaitEachGesture
                        }
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

        // 手势指示条：中央临时小胶囊（半透明黑底 + 12sp 白字）
        gestureHint?.let { hint ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        RoundedCornerShape(SoftDimens.PosterCorner),
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(hint, color = Color.White, style = SoftType.Caption)
            }
        }

        // v0.27.7：播放中 4 秒无操作自动隐藏控制层（暂停时保持显示）
        // v0.40.0：锁定时不自动隐藏（解锁按钮常显）
        LaunchedEffect(controlsVisible, isPlaying, locked) {
            if (controlsVisible && isPlaying && !locked) {
                delay(4000)
                controlsVisible = false
            }
        }

        // v0.27.7：中央大播放键——暂停时显示；点后恢复播放并带出控制层
        // v0.40.0：锁定时隐藏（防误触）
        if (!isPlaying && playUrl.isNotBlank() && !locked) {
            IconButton(
                onClick = {
                    EngineHost.togglePlayPause()
                    controlsVisible = true
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(76.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }
        }

        // v0.40.0：顶部栏按目标界面重做——返回 + 标题 + 画中画 + 投屏 + 更多菜单
        // 锁定时隐藏顶栏（只留解锁按钮防误触）
        if (controlsVisible && !locked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(Color.Black)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Text(
                    text = title,
                    color = Color.White,
                    style = SoftType.CardTitle,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                // v0.24.0：直播角标（v0.27.0：柔和错误色胶囊）
                if (isLive) {
                    Surface(
                        shape = RoundedCornerShape(SoftDimens.ChipCorner),
                        color = LocalSoftExtra.current.errorSoft,
                    ) {
                        Text(
                            text = "直播",
                            color = Color.Black,
                            style = SoftType.Caption,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                // 画中画
                IconButton(onClick = { enterPip(context) }) {
                    Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "画中画", tint = Color.White)
                }
                // 投屏（v0.40.0：占位，功能开发中）
                IconButton(onClick = {
                    Toast.makeText(context, "投屏功能开发中，敬请期待", Toast.LENGTH_SHORT).show()
                    LogBus.i("Player", "投屏按钮：功能开发中")
                }) {
                    Icon(Icons.Filled.Cast, contentDescription = "投屏", tint = Color.White)
                }
                // 更多菜单：字幕/选集/定时/跳过设置/画面比例收敛到这里
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("字幕设置") },
                            onClick = { showMoreMenu = false; showSubtitleDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("在线字幕") },
                            onClick = { showMoreMenu = false; showOnlineSubtitle = true },
                        )
                        // v0.54：弹幕开关（只看不发）
                        DropdownMenuItem(
                            text = { Text(if (danmakuEnabled) "关闭弹幕" else "打开弹幕") },
                            onClick = {
                                showMoreMenu = false
                                com.movieshell.player.danmaku.DanmakuSettings.setEnabled(
                                    context, !danmakuEnabled
                                )
                            },
                        )
                        // v0.56：弹幕设置
                        if (danmakuEnabled) {
                            DropdownMenuItem(
                                text = { Text("弹幕设置") },
                                onClick = { showMoreMenu = false; showDanmakuSettings = true },
                            )
                        }
                        if (!isLive) {
                            DropdownMenuItem(
                                text = { Text("选集 / 线路") },
                                onClick = { showMoreMenu = false; showLines = true },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (sleepRemaining > 0 || sleepArmed) "定时关闭（进行中）"
                                    else "定时关闭"
                                )
                            },
                            onClick = { showMoreMenu = false; showSleepDialog = true },
                        )
                        if (!isLive && vodId != null) {
                            DropdownMenuItem(
                                text = { Text("本剧跳过设置") },
                                onClick = { showMoreMenu = false; showSkipSettings = true },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("画面比例：${RESIZE_MODES[resizeIndex].second}") },
                            onClick = {
                                showMoreMenu = false
                                resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size
                                if (engine == PlayerEngine.MEDIA3) {
                                    playerView?.resizeMode = RESIZE_MODES[resizeIndex].first
                                } else {
                                    MpvPlayerManager.setAspectMode(resizeIndex)
                                }
                            },
                        )
                    }
                }
            }
        } // if (controlsVisible && !locked)：顶部栏

        // v0.40.0：左侧悬浮按钮——锁定 + 截图（目标界面：圆形半透明黑底）
        // 锁定时只显示解锁按钮（常显，不跟随控制层自动隐藏）
        if (locked) {
            IconButton(
                onClick = {
                    locked = false
                    controlsVisible = true
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .size(56.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            ) {
                Icon(
                    Icons.Filled.LockOpen,
                    contentDescription = "解锁",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        } else if (controlsVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { locked = true },
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = "锁定",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                EngineHost.screenshot(
                                    context, title, playUrl, lastHeaders,
                                    EngineHost.currentPosition(),
                                )
                            }
                            Toast.makeText(
                                context,
                                if (file != null) "截图已保存：${file.name}" else "截图失败",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                ) {
                    Icon(
                        Icons.Filled.CameraAlt,
                        contentDescription = "截图",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }

        // v0.27.0：播放错误提示走柔和错误色，不用刺眼红
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
            snackbar = { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(SoftDimens.ButtonCorner),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = LocalSoftExtra.current.errorSoft,
                    actionColor = MaterialTheme.colorScheme.primary,
                )
            },
        )

        // v0.27.7：双引擎统一底部控制条（播放/暂停 + 进度拖动 + 横竖屏切换）。
        // 之前只有 libmpv 有自绘条，Media3 靠原生控制器；用户反馈找不到暂停/播放，
        // 现关闭原生控制器，双引擎共用这一套。轮询放条件外，显隐切换不丢进度。
        var pos by remember { mutableLongStateOf(0L) }
        var dur by remember { mutableLongStateOf(0L) }
        var scrubbing by remember { mutableStateOf(false) }
        var scrubPos by remember { mutableLongStateOf(0L) }
        LaunchedEffect(engine, playUrl) {
            while (true) {
                delay(500)
                isPlaying = EngineHost.isPlayingNow()
                if (!scrubbing) pos = EngineHost.currentPosition()
                dur = EngineHost.duration()
            }
        }
        // v0.40.0：底部控制条按目标界面重做——两行式：进度行 + 控制行
        // 锁定时隐藏（只留解锁按钮）
        if (playUrl.isNotBlank() && controlsVisible && !locked) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                // 第一行：当前时间 + 进度条 + 总时长
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = fmtTime(if (scrubbing) scrubPos else pos),
                        color = Color.White,
                        style = SoftType.Caption,
                    )
                    Slider(
                        value = (if (scrubbing) scrubPos else pos).toFloat(),
                        onValueChange = {
                            scrubPos = it.toLong()
                            scrubbing = true
                        },
                        onValueChangeFinished = {
                            EngineHost.seekTo(scrubPos)
                            scrubbing = false
                        },
                        valueRange = 0f..dur.coerceAtLeast(1L).toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFF2196F3),
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                    )
                    Text(
                        text = fmtTime(dur),
                        color = Color.White,
                        style = SoftType.Caption,
                    )
                }
                // 第二行：暂停 + 时间 + 上一集/下一集 + 倍速 + 选集 + 引擎 + 全屏
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(onClick = { EngineHost.togglePlayPause() }) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = "${fmtTime(if (scrubbing) scrubPos else pos)} / ${fmtTime(dur)}",
                        color = Color.White,
                        style = SoftType.Caption,
                    )
                    if (!isLive) {
                        val hasPrev = PlaySession.episodeIndex > 0
                        val hasNext = PlaySession.currentLine()?.episodes
                            ?.getOrNull(PlaySession.episodeIndex + 1) != null
                        IconButton(
                            onClick = {
                                if (hasPrev) switchEpisode(
                                    PlaySession.lineIndex,
                                    PlaySession.episodeIndex - 1,
                                )
                            },
                        ) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = "上一集",
                                tint = if (hasPrev) Color.White else Color.White.copy(alpha = 0.3f),
                            )
                        }
                        IconButton(
                            onClick = {
                                if (hasNext) switchEpisode(
                                    PlaySession.lineIndex,
                                    PlaySession.episodeIndex + 1,
                                )
                            },
                        ) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = "下一集",
                                tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.3f),
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            speedIndex = (speedIndex + 1) % SPEEDS.size
                            EngineHost.setPlaybackSpeed(SPEEDS[speedIndex])
                        },
                        modifier = Modifier.height(SoftDimens.TouchTarget),
                    ) {
                        // 目标界面样式：1.0X（大写 X）
                        Text(
                            "${SPEEDS[speedIndex]}X",
                            color = Color.White,
                            style = SoftType.Body,
                        )
                    }
                    if (!isLive) {
                        TextButton(
                            onClick = { showLines = true },
                            modifier = Modifier.height(SoftDimens.TouchTarget),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.List,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(2.dp))
                                Text("选集", color = Color.White, style = SoftType.Body)
                            }
                        }
                    }
                    // 引擎切换（目标界面显示 Exo，蓝色）
                    TextButton(
                        onClick = { switchEngine() },
                        modifier = Modifier.height(SoftDimens.TouchTarget),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (engine == PlayerEngine.MEDIA3) "Exo" else "mpv",
                                color = Color(0xFF2196F3),
                                style = SoftType.Body,
                            )
                            Icon(
                                Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                tint = Color(0xFF2196F3),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { toggleOrientation() }) {
                        Icon(
                            Icons.Filled.Fullscreen,
                            contentDescription = "全屏",
                            tint = Color.White,
                        )
                    }
                }
            }
        }

        // v0.19.0：线路+选集抽屉；v0.24.0：直播不显示
        if (showLines && !isLive) {
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
        // v0.21.0：升级为样式面板（大小/颜色/描边/位置）+ 时间偏移 ±10s
        // v0.56：弹幕设置对话框
        if (showDanmakuSettings) {
            val ds = com.movieshell.player.danmaku.DanmakuSettings
            val sizeIdx by ds.textSize.collectAsState()
            val alphaV by ds.alpha.collectAsState()
            val speedIdx by ds.speed.collectAsState()
            val areaIdx by ds.area.collectAsState()
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDanmakuSettings = false },
                title = { androidx.compose.material3.Text("弹幕设置") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        // 字号
                        androidx.compose.material3.Text("字号", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                        androidx.compose.foundation.layout.Row {
                            listOf("小", "中", "大").forEachIndexed { i, label ->
                                androidx.compose.material3.FilterChip(
                                    selected = sizeIdx == i,
                                    onClick = { ds.setTextSize(context, i) },
                                    label = { androidx.compose.material3.Text(label) },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                        // 透明度
                        androidx.compose.material3.Text("透明度：$alphaV%", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                        androidx.compose.material3.Slider(
                            value = alphaV.toFloat(),
                            onValueChange = { ds.setAlpha(context, it.toInt()) },
                            valueRange = 30f..100f,
                        )
                        // 速度
                        androidx.compose.material3.Text("速度", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                        androidx.compose.foundation.layout.Row {
                            listOf("慢", "中", "快").forEachIndexed { i, label ->
                                androidx.compose.material3.FilterChip(
                                    selected = speedIdx == i,
                                    onClick = { ds.setSpeed(context, i) },
                                    label = { androidx.compose.material3.Text(label) },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                        // 区域
                        androidx.compose.material3.Text("显示区域", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                        androidx.compose.foundation.layout.Row {
                            listOf("全屏", "上半屏").forEachIndexed { i, label ->
                                androidx.compose.material3.FilterChip(
                                    selected = areaIdx == i,
                                    onClick = { ds.setArea(context, i) },
                                    label = { androidx.compose.material3.Text(label) },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showDanmakuSettings = false }) {
                        androidx.compose.material3.Text("确定")
                    }
                },
            )
        }

        if (showSubtitleDialog) {
            SubtitleDialog(
                initial = sessionSubtitle
                    ?: vodId?.let { PlayerSettings.getSubtitleFor(context, it) }.orEmpty(),
                initialStyle = PlayerSettings.subtitleStyle.value,
                onDismiss = { showSubtitleDialog = false },
                onSave = { url, style ->
                    val clean = url.trim()
                    sessionSubtitle = clean.ifBlank { null }
                    if (vodId != null) PlayerSettings.setSubtitleFor(context, vodId, clean)
                    PlayerSettings.setSubtitleStyle(context, style)
                    showSubtitleDialog = false
                    replayWithSubtitle()
                },
                onOnlineSearch = { showOnlineSubtitle = true },
            )
        }

        // v0.28.0：在线字幕搜索（搜当前标题，下载后自动应用+按剧记忆）
        if (showOnlineSubtitle) {
            com.movieshell.ui.subtitle.OnlineSubtitleSearchDialog(
                keyword = title,
                vodId = vodId,
                onPick = { url ->
                    sessionSubtitle = url
                    showOnlineSubtitle = false
                    showSubtitleDialog = false
                    replayWithSubtitle()
                },
                onDismiss = { showOnlineSubtitle = false },
            )
        }

        // v0.21.0：定时关闭
        if (showSleepDialog) {
            SleepTimerDialog(
                onDismiss = { showSleepDialog = false },
                onPickMinutes = { m ->
                    showSleepDialog = false
                    SleepTimer.startMinutes(m) {
                        EngineHost.pause()
                        Toast.makeText(context, "定时关闭：已暂停播放", Toast.LENGTH_SHORT).show()
                        LogBus.i("Player", "定时关闭触发：${m}分钟到，暂停")
                    }
                    Toast.makeText(context, "将在 $m 分钟后停止播放", Toast.LENGTH_SHORT).show()
                },
                onPickAfterEpisode = {
                    showSleepDialog = false
                    SleepTimer.armAfterEpisode()
                    Toast.makeText(context, "本集播完后将停止播放", Toast.LENGTH_SHORT).show()
                },
                onCancelTimer = {
                    showSleepDialog = false
                    SleepTimer.cancel()
                    Toast.makeText(context, "定时关闭已取消", Toast.LENGTH_SHORT).show()
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

        // 磁力转播服务未启动提示（v0.27.0：统一卡片容器，标题柔和错误色）
        if (showServiceMissingDialog) {
            PlayerDialogCard(
                title = "磁力转播服务未启动",
                titleColor = LocalSoftExtra.current.errorSoft,
                onDismiss = { showServiceMissingDialog = false },
                buttons = {
                    AppButton(
                        text = "知道了",
                        onClick = { showServiceMissingDialog = false },
                        variant = AppButtonVariant.Ghost,
                    )
                },
            ) {
                Text(
                    "未检测到本机磁力转播服务（127.0.0.1:17890），请先在 DsPlayer 的服务页启动 magnet-stream 后再试",
                    style = SoftType.Body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
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
private fun savePlayerProgress(context: Context, title: String, url: String, isLive: Boolean = false) {
    val position = EngineHost.currentPosition()
    // v0.24.0：直播无进度概念也记一条（position 0），kind=live 供历史页分流
    if (!isLive && position <= 0) return
    HistoryStore.saveProgress(
        context = context,
        title = title,
        url = url,
        positionMs = if (isLive) 0L else position,
        durationMs = EngineHost.duration(),
        kind = if (isLive) "live" else "vod",
    )
}

/**
 * v0.63.0：看完自动删。播放的是本地下载文件、播到 95% 以上、开关开着 → 删文件并移除任务。
 * 跑在 IO 线程，不阻塞退出。
 */
private fun maybeAutoDeleteWatched(context: Context, url: String, isLive: Boolean) {
    if (isLive || !url.startsWith("file://")) return
    if (!DownloadSettings.autoDeleteWatched(context)) return
    val duration = EngineHost.duration()
    val position = EngineHost.currentPosition()
    if (duration <= 0 || position < duration * 0.95) return
    val localPath = url.removePrefix("file://")
    val task = DownloadStore.findByLocalPath(localPath) ?: return
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        DownloadStore.remove(context.applicationContext, task.id, deleteFiles = true)
        android.util.Log.i("Download", "看完自动删：${task.title}")
    }
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
                .fillMaxHeight(0.6f)
                .border(
                    SoftDimens.BorderWidth,
                    LocalSoftExtra.current.border,
                    RoundedCornerShape(
                        topStart = SoftDimens.CardCorner,
                        topEnd = SoftDimens.CardCorner,
                    ),
                ),
            shape = RoundedCornerShape(
                topStart = SoftDimens.CardCorner,
                topEnd = SoftDimens.CardCorner,
            ),
            // v0.39.0：深色毛玻璃风格（mockup 风格）
            color = Color(0xF21E242F),
        ) {
            Column(Modifier.fillMaxSize().padding(SoftDimens.PagePadding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "线路 / 选集",
                        style = SoftType.CardTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(lines) { i, line ->
                        AppChip(
                            text = line.name,
                            selected = i == tab,
                            onClick = { tab = i },
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
                        // v0.39.0：选中集青色高光（mockup 风格）
                        val cyan = Color(0xFF4DD0E1)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(tab, i) },
                            shape = RoundedCornerShape(SoftDimens.PosterCorner),
                            color = if (selected) {
                                cyan.copy(alpha = 0.22f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Text(
                                text = ep.name.ifBlank { "第${i + 1}集" },
                                maxLines = 1,
                                style = SoftType.Body,
                                color = if (selected) {
                                    cyan
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
 * v0.27.0：播放器内对话框统一容器（Soft Tech）——遮罩 + 居中卡片
 *（surface 底 + 1dp 描边 + 20dp 圆角，标题 15sp CardTitle）。
 * 字幕设置 / 定时关闭 / 本剧跳过 / 磁力服务提示四个面板共用。
 */
@Composable
/** v0.28.0：开放给在线字幕对话框复用（原 private）。 */
internal fun PlayerDialogCard(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable RowScope.() -> Unit,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardShape = RoundedCornerShape(SoftDimens.CardCorner)
    // AlertDialog 时代按返回先关面板：内联卡片后补回同样的返回拦截
    BackHandler(onBack = onDismiss)
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
                .align(Alignment.Center)
                .fillMaxWidth(0.88f)
                .widthIn(max = 480.dp)
                .border(SoftDimens.BorderWidth, LocalSoftExtra.current.border, cardShape),
            shape = cardShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(SoftDimens.PagePadding)) {
                Text(title, style = SoftType.CardTitle, color = titleColor)
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    content()
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    buttons()
                }
            }
        }
    }
}

/** v0.21.0：字幕颜色候选 */
private val SUB_COLORS = listOf(
    0xFFFFFFFF.toInt(),
    0xFFFFFF00.toInt(),
    0xFF00E5FF.toInt(),
    0xFF69F0AE.toInt(),
    0xFFFF8A80.toInt(),
)

/**
 * v0.19.0：外挂字幕设置对话框。填字幕地址，保存后按当前位置重播生效；
 * 按剧记忆（vodId 为空时仅本次有效）。
 * v0.21.0：升级为样式面板——大小/颜色/描边/位置 + 时间偏移 ±10s（双引擎通用，全局生效）。
 */
@Composable
private fun SubtitleDialog(
    initial: String,
    initialStyle: SubtitleStyle,
    onDismiss: () -> Unit,
    onSave: (String, SubtitleStyle) -> Unit,
    onOnlineSearch: () -> Unit,
) {
    // v0.49.0：SubHD 内置，在线搜索常显（v0.30.0 的插件门禁删除）
    var url by remember { mutableStateOf(initial) }
    var size by remember { mutableIntStateOf(initialStyle.sizeSp) }
    var color by remember { mutableIntStateOf(initialStyle.color) }
    var edge by remember { mutableIntStateOf(initialStyle.edge) }
    var position by remember { mutableIntStateOf(initialStyle.position) }
    var offsetSec by remember { mutableIntStateOf((initialStyle.offsetMs / 1000).toInt()) }
    // v0.27.0：统一卡片容器 + AppChip 多选 + AppTextField
    PlayerDialogCard(
        title = "字幕设置",
        onDismiss = onDismiss,
        buttons = {
            AppButton(
                text = "清除",
                onClick = { onSave("", initialStyle) },
                variant = AppButtonVariant.Ghost,
            )
            AppButton(text = "取消", onClick = onDismiss, variant = AppButtonVariant.Ghost)
            AppButton(
                text = "保存并应用",
                onClick = {
                    onSave(
                        url,
                        SubtitleStyle(size, color, edge, position, offsetSec * 1000L),
                    )
                },
            )
        },
    ) {
        Text(
            text = "支持 .srt / .vtt / .ass 地址，保存后立即生效",
            style = SoftType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        AppTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = "字幕地址",
            modifier = Modifier.fillMaxWidth(),
        )
        // v0.28.0：在线搜索字幕（v0.49.0：SubHD 内置，常显）
        AppButton(
            text = "在线搜索字幕",
            onClick = onOnlineSearch,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "大小 ${size}sp",
            style = SoftType.Body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = size.toFloat(),
            onValueChange = { size = it.toInt() },
            valueRange = 12f..32f,
        )
        Text(
            "颜色",
            style = SoftType.Body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SUB_COLORS.forEach { c ->
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(
                            width = if (c == color) 3.dp else 1.dp,
                            color = if (c == color) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalSoftExtra.current.border
                            },
                            shape = CircleShape,
                        )
                        .clickable { color = c },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "描边",
            style = SoftType.Body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                SubtitleStyle.EDGE_NONE to "无",
                SubtitleStyle.EDGE_OUTLINE to "描边",
                SubtitleStyle.EDGE_SHADOW to "阴影",
            ).forEach { (v, label) ->
                AppChip(
                    text = label,
                    selected = edge == v,
                    onClick = { edge = v },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "位置",
            style = SoftType.Body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppChip(
                text = "底部",
                selected = position == SubtitleStyle.POS_BOTTOM,
                onClick = { position = SubtitleStyle.POS_BOTTOM },
            )
            AppChip(
                text = "顶部",
                selected = position == SubtitleStyle.POS_TOP,
                onClick = { position = SubtitleStyle.POS_TOP },
            )
        }
        Spacer(Modifier.height(8.dp))
        val offsetLabel = when {
            offsetSec > 0 -> "+${offsetSec}s（字幕延后）"
            offsetSec < 0 -> "${offsetSec}s（字幕提前）"
            else -> "0s（关闭偏移）"
        }
        Text(
            "时间偏移 $offsetLabel",
            style = SoftType.Body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = offsetSec.toFloat(),
            onValueChange = { offsetSec = it.toInt() },
            valueRange = -10f..10f,
            steps = 19,
        )
    }
}

/** v0.21.0：定时关闭选项（15/30/60/90 分钟 / 播完本集 / 关闭定时）。 */
@Composable
private fun SleepTimerDialog(
    onDismiss: () -> Unit,
    onPickMinutes: (Int) -> Unit,
    onPickAfterEpisode: () -> Unit,
    onCancelTimer: () -> Unit,
) {
    // v0.27.0：统一卡片容器
    PlayerDialogCard(
        title = "定时关闭",
        onDismiss = onDismiss,
        buttons = {
            AppButton(text = "取消", onClick = onDismiss, variant = AppButtonVariant.Ghost)
        },
    ) {
        listOf(15, 30, 60, 90).forEach { m ->
            TextButton(
                onClick = { onPickMinutes(m) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "$m 分钟后停止",
                    style = SoftType.Body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        TextButton(
            onClick = onPickAfterEpisode,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "播完本集停止",
                style = SoftType.Body,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        TextButton(
            onClick = onCancelTimer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "关闭定时",
                style = SoftType.Body,
                color = LocalSoftExtra.current.errorSoft,
            )
        }
    }
}

/** v0.21.0：定时关闭剩余时间 → m:ss */
private fun fmtSleep(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
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

    // v0.27.0：统一卡片容器 + AppTextField
    PlayerDialogCard(
        title = "本剧跳过设置",
        onDismiss = onDismiss,
        buttons = {
            AppButton(text = "取消", onClick = onDismiss, variant = AppButtonVariant.Ghost)
            AppButton(
                text = "保存",
                onClick = {
                    val introSec = intro.toIntOrNull()?.coerceIn(0, 600) ?: 0
                    val outroSec = outro.toIntOrNull()?.coerceIn(0, 600) ?: 0
                    PlayerSettings.setSkipIntroFor(context, vodId, introSec)
                    PlayerSettings.setSkipOutroFor(context, vodId, outroSec)
                    LogBus.i("UI", "设置本剧跳过: 片头${introSec}s 片尾${outroSec}s")
                    onDismiss()
                },
            )
        },
    ) {
        Text(
            text = "每部剧单独设置，互不影响",
            style = SoftType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        AppTextField(
            value = intro,
            onValueChange = { intro = it.filter { c -> c.isDigit() } },
            placeholder = "跳过片头（秒）",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = outro,
            onValueChange = { outro = it.filter { c -> c.isDigit() } },
            placeholder = "跳过片尾（秒）",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
