package com.movieshell.player

import android.app.Activity
import com.movieshell.ui.components.AppToast
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import com.movieshell.data.model.PlayLine
import com.movieshell.data.repo.DetailSiteHolder
import com.movieshell.data.repo.DownloadSettings
import com.movieshell.data.repo.DownloadStore
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.FollowItem
import com.movieshell.data.repo.FollowStore
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PhpSourceManager
import com.movieshell.data.repo.PlayerEngine
import com.movieshell.data.repo.PlayerSettings
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SubtitleStyle
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.V4Chip
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.TechDialog
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.movieshell.ui.theme.V4
import com.movieshell.ui.theme.V4Type

private val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

// v0.65：循环模式（对标目标界面"顺序播放"按钮）
private val PLAY_MODES = listOf("顺序播放", "单集循环", "列表循环")

// v0.125.0：下标即持久化档位（PlayerSettings.resizeMode），顺序与菜单文案一致——
// 0=适应 FIT / 1=裁剪 ZOOM / 2=拉伸 FILL；libmpv 的 setAspectMode 同下标同语义
private val RESIZE_MODES = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "适应",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "裁剪",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "拉伸",
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
/**
 * v0.204.0：小圆控制钮——对标参考播放器的小巧圆润风格：40.dp 圆底 + 20.dp 白图标。
 * 直播与点播共用 PlayerScreen，改这一处两边全变。
 */
@Composable
private fun RoundControlButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    // v0.228.0：底部控制行 9 个钮挤不下时可传 36dp 瘦身，默认 40dp 不动。
    size: Dp = 40.dp,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class) // v0.84.0：Slider 自定义 track 用到实验性 SliderState API；v0.255.0：标题跑马灯 basicMarquee
@Composable
fun PlayerScreen(
    url: String,
    title: String,
    vodId: String? = null,
    isLive: Boolean = false,
    onBack: () -> Unit,
    // v0.65：嵌入模式——详情页顶部 16:9 直接播；onFullscreen 供嵌入时点全屏跳独立播放页
    embedded: Boolean = false,
    onFullscreen: () -> Unit = {},
    // v0.65：集数变化回调（嵌入时详情页高亮跟随）
    onEpisodeChange: (lineIndex: Int, episodeIndex: Int) -> Unit = { _, _ -> },
    // v0.259.0：简介弹窗字段（详情页嵌入时传真实值；独立全屏页传空，
    // 空行在弹窗里隐藏，只留片名与影片 ID，不编造备注/年份/简介）
    // v0.260.0：补 infoName 参数——v0.259.0 只加了其余三字段，漏了片名参数，
    // 导致 PlayerScreen 内 infoName 与详情页调用处双双编译失败。
    infoName: String = "",
    infoRemarks: String = "",
    infoYear: String = "",
    infoDesc: String = "",
    // v0.158.0：直播最终失败回调（同内核重拉 2 次 + 换内核之后才到这里，
    // 直播页用它自动切同名源；null 时走原错误对话框，不改点播行为）
    onLiveError: (() -> Unit)? = null,
    // v0.160.0：直播频道自带的请求头（插件 lives 里 ua 字段，有才传；没有不编 UA/Referer）
    liveHeaders: Map<String, String> = emptyMap(),
) {
    val context = LocalContext.current
    // v0.186.0：本播放会话的源 key（字幕与片头片尾新 key 用）。
    // 口径与详情页追剧用的 sourceKey 完全一致：订阅站取站点 key，否则取当前激活的
    // drpy、PY、PHP 规则名；全空时为 null（新 key 里按空源处理，不编显示名冒充）。
    // 只在进入播放页时快照一次：源在一次播放会话里不会变，快照保证同一会话内
    // 读与写永远走同一个 key；集数不快照，每次调用点现取 PlaySession.episodeIndex，
    // 切集后读写的就是新集的 key。
    val playSourceKey = remember {
        DetailSiteHolder.site?.key?.takeIf { it.isNotBlank() }
            ?: DrpySourceManager.activeRuleName()?.takeIf { it.isNotBlank() }
            ?: PySourceManager.activeRuleName()?.takeIf { it.isNotBlank() }
            ?: PhpSourceManager.activeRuleName()?.takeIf { it.isNotBlank() }
    }
    // v0.189.0：与 playSourceKey 同一次快照的源类型（sub/drpy/py/php），
    // 口径与详情页追剧的 followSourceType 一致；无源时为 ""，不冒充 sub。
    // 只在进入播放页时快照一次，退出保存进度时传快照，不现取已换过的激活规则。
    val playSourceType = remember {
        when {
            DetailSiteHolder.site != null -> "sub"
            DrpySourceManager.hasActiveRule() -> "drpy"
            PySourceManager.hasActiveRule() -> "py"
            PhpSourceManager.activeRuleName() != null -> "php"
            else -> ""
        }
    }
    val scope = rememberCoroutineScope()
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    // v0.109.0 A2：双引擎画面 View 引用（keepScreenOn 用，Media3/libmpv 统一）
    var videoView by remember { mutableStateOf<View?>(null) }
    // v0.74.0：倍速记忆——从持久化恢复上次的倍速
    var speedIndex by remember {
        val saved = PlayerSettings.getSpeed(context)
        mutableIntStateOf(SPEEDS.indexOf(saved).takeIf { it >= 0 } ?: 2) // 默认 1.0x
    }
    var playModeIndex by remember { mutableIntStateOf(0) } // v0.65：循环模式，默认顺序播放
    // v0.125.0：全屏画面比例偏好（持久化恢复；v0.255.0 起默认适应，见 PlayerSettings）
    var resizeIndex by remember { mutableIntStateOf(PlayerSettings.getResizeMode(context)) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showServiceMissingDialog by remember { mutableStateOf(false) }
    var gestureHint by remember { mutableStateOf<String?>(null) } // 手势临时指示
    // v0.124.0：本播放器是否已把窗口切进沉浸式全出血（恢复时凭它守卫，没动过不恢复）
    var immersiveApplied by remember { mutableStateOf(false) }
    var showSkipSettings by remember { mutableStateOf(false) } // v0.16.3：按剧片头片尾设置
    // v0.19.0
    var playUrl by remember { mutableStateOf(url) } // 实际播放地址（切线路/重试时更新）
    val triedLines = remember { mutableSetOf<Int>() } // 已试过的线路下标（声明前置：LaunchedEffect(url) 里引用）
    // v0.109.0 A10：切集代际 guard 的持有量（声明前置：switchEpisode 里引用）
    var resolveGen by remember { mutableIntStateOf(0) }
    var resolveJob by remember { mutableStateOf<Job?>(null) }
    // v0.65：嵌入模式下外部换地址（如详情页点选集）→ 跟随切过去，走已有 LaunchedEffect(playUrl) 起播
    LaunchedEffect(url) {
        if (url.isNotBlank() && url != playUrl) {
            triedLines.clear()
            playUrl = url
        }
    }
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
    // v0.40.0：锁屏模式（锁定后只留解锁按钮，防误触）+ 右上角齿轮
    var locked by remember { mutableStateOf(false) }
    var showGearSheet by remember { mutableStateOf(false) } // v0.259.0：齿轮底部页，替代三个点菜单
    var showInfoDialog by remember { mutableStateOf(false) } // v0.259.0：简介弹窗
    // v0.259.0：追剧开关状态直接读 FollowStore 流，播放器与详情页天然同步；
    // 源 key 为空时不建“|vodId”这种不可靠条目，开关直接隐藏。
    val followList by FollowStore.list.collectAsState()
    val followPlayerKey = playSourceKey?.takeIf { it.isNotBlank() }
    val playerFollowing = followPlayerKey != null && vodId != null &&
        followList.any { it.id == FollowStore.idOf(followPlayerKey, vodId) }
    var autoSwitching by remember { mutableStateOf(false) } // playUrl 变化来自自动重试
    // v0.21.0：同地址已试过的内核（防 Media3↔libmpv 乒乓）
    val triedEngines = remember { mutableSetOf<PlayerEngine>() }
    // v0.123.0：同一内核重拉流重试计数（补充B：IO 错误先同内核自愈，≤2 次）
    val sameEngineRetries = remember { mutableIntStateOf(0) }
    var lastHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // v0.96.1：横屏不断流——翻转前快照播放状态，方向变化后若停播则无缝恢复（双引擎通用）
    var rotateWasPlaying by remember { mutableStateOf(false) }
    var rotatePosMs by remember { mutableLongStateOf(0L) }
    var lastSensorLandscape by remember { mutableStateOf<Boolean?>(null) }
    var lastCfgOrientation by remember { mutableStateOf<Int?>(null) }

    /** v0.96.1：横屏/竖屏切换前快照（供方向变化后恢复用）。 */
    fun snapshotForRotation() {
        rotateWasPlaying = EngineHost.isPlayingNow()
        rotatePosMs = EngineHost.currentPosition()
    }
    var showSleepDialog by remember { mutableStateOf(false) } // v0.21.0：定时关闭
    val sessionLines = remember { PlaySession.lines } // 进入前详情页已写入
    val snackbarHostState = remember { SnackbarHostState() }
    // v0.20.0：双引擎——Media3（默认）/ libmpv，记忆选择
    // v0.21.0：engineOverride 为自动换内核的临时覆盖（静默，不持久化），优先于用户设置
    val engineSetting by PlayerSettings.engine.collectAsState()
    val engineOverride by EngineHost.engineOverride.collectAsState()
    val engine = engineOverride ?: engineSetting
    // v0.21.0：定时关闭状态
    val sleepRemaining by VideoSleepTimer.remainingMs.collectAsState()
    val sleepArmed by VideoSleepTimer.afterEpisodeArmed.collectAsState()

    BackHandler(onBack = onBack)

    // DisposableEffect 的 onDispose 闭包捕获的是首次组合的值，
    // 用 rememberUpdatedState 保证退出时存的是最新的 url/title。
    val currentPlayUrl by rememberUpdatedState(playUrl)
    val currentTitle by rememberUpdatedState(title)

    /**
     * v0.124.0：全屏真全出血的窗口层所有权（黑边第二轮真凶定位）——v0.123.0 只把
     * resizeMode 换成 ZOOM，但容器没占满物理屏：①窗口从没设 edge-to-edge/cutout
     * 模式，横屏时窗口被挖孔与系统栏 insets 挤出黑带；②嵌入直播横屏从不
     * 隐藏系统栏；③LiveScreen 的 Scaffold inner padding 又在容器外圈套了 insets
     * （该处在 LiveScreen 改）。这里统一：进入全屏（独立页恒全屏 / 嵌入横屏）时
     * decor 不避让系统栏 + cutout SHORT_EDGES + 隐藏系统栏，一次设齐；退出恢复。
     * 由 fullscreenNow 的 LaunchedEffect 驱动，本函数只管"设"，恢复走 restorePlayerWindow。
     */
    fun applyImmersiveFullscreen() {
        val window = (context as? Activity)?.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        immersiveApplied = true
    }

    /** v0.124.0：恢复窗口默认状态；没进过沉浸式（immersiveApplied=false）时是 no-op。 */
    fun restorePlayerWindow() {
        // B4：手势调过的亮度不跟沉浸式守卫走——只要调过窗口，退出/切出全屏
        // 就先复位到系统亮度（-1f），否则整个 App 一直停在调过的亮度。
        (context as? Activity)?.window?.let {
            it.attributes = it.attributes.apply {
                screenBrightness = -1f
            }
        }
        if (!immersiveApplied) return
        immersiveApplied = false
        val window = (context as? Activity)?.window ?: return
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    // 退出时保存进度、清理播放会话（沉浸式窗口驱动见 fullscreenNow 的 LaunchedEffect；
    // 离页时 restorePlayerWindow 有守卫——嵌入竖屏从没动过窗口，不会重现 v0.84.0
    // 「切 tab 把手机底部导航栏弹出来」的问题）
    // v0.217.0（F16）：嵌入模式的退出不做会话／引擎清理——跳全屏时独立播放页
    // 正是同一会话的延续，清掉会导致切集／换线路失效、甚至新页黑屏；
    // 真离开详情页的停播由详情页自己的 dispose 负责（见 DetailScreen goingFullscreen）。
    DisposableEffect(Unit) {
        PlaySession.playerVisible = true
        onDispose {
            restorePlayerWindow()
            savePlayerProgress(
                context, currentTitle, currentPlayUrl, isLive,
                vodId, PlaySession.episodeIndex,
                playSourceType, playSourceKey.orEmpty(),
            )
            // v0.63.0：看完自动删——本地下载文件播到 95% 以上且开关开着，删文件+清任务
            maybeAutoDeleteWatched(context, currentPlayUrl, isLive)
            if (!embedded) {
                PlaySession.playerVisible = false
                PlaySession.clear()
                // v0.78.1：退出播放页必须停播放器，否则退到后台还在响
                EngineHost.stop()
            }
            // v0.27.7：退出播放器恢复系统默认方向 + 停重力感应（仅全屏页；嵌入不碰）
            if (!embedded) {
                OrientationSensor.stop()
                (context as? Activity)?.let {
                    if (it.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                        it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
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
    // v0.84.0：嵌入模式（直播页顶部）不启动重力感应——之前在直播页转手机会被强制全 App 横屏
    LaunchedEffect(gravityRotate, embedded) {
        if (embedded) return@LaunchedEffect
        if (gravityRotate) OrientationSensor.start(context) else OrientationSensor.stop()
    }
    LaunchedEffect(sensorLandscape, gravityRotate, embedded) {
        if (embedded || !gravityRotate) return@LaunchedEffect
        // v0.96.1：sensor 首次读数只记录不下发——进页时手机本来就横着属于"现状"而非"翻转"，
        // 之前进页 0.5 秒就"切横屏"锁方向是一次误触发
        val prev = lastSensorLandscape
        lastSensorLandscape = sensorLandscape
        // v0.261.0：手动强制切换后，重力感应不再抢方向（手动锁优先于 sensor 翻转）。
        if (OrientationSensor.manualLocked) return@LaunchedEffect
        if (prev == null || prev == sensorLandscape) return@LaunchedEffect
        // v0.96.1：真翻转才锁方向，锁之前快照播放状态
        snapshotForRotation()
        val activity = context as? Activity ?: return@LaunchedEffect
        activity.requestedOrientation = if (sensorLandscape) {
            LogBus.i("Player", "重力感应：切横屏")
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            LogBus.i("Player", "重力感应：切竖屏")
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /** v0.27.7：手动横竖屏切换（控制条全屏按钮）。 */
    fun toggleOrientation() {
        val activity = context as? Activity ?: return
        // v0.96.1：手动切换也先快照，保证不断流
        // v0.261.0：手动 = 强制，设锁后 sensor 不再抢回去。
        OrientationSensor.manualLocked = true
        snapshotForRotation()
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
     * v0.96.2：嵌入模式手动横竖屏切换（直播页控制条横屏按钮）。
     * 嵌入模式不启动重力感应（v0.84.0），手动按钮是直播页唯一的旋转控制，
     * 不会和 sensor 打架；切之前先快照，复用 v0.96.1 的不断流恢复逻辑。
     * 方向走 Activity 显式锁定，双引擎统一走 EngineHost。
     */
    fun toggleEmbeddedLandscape() {
        val activity = context as? Activity ?: return
        // v0.96.1：手动切换先快照，保证不断流
        // v0.261.0：手动 = 强制，设锁后 sensor 不再抢回去。
        OrientationSensor.manualLocked = true
        snapshotForRotation()
        val landscape =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        activity.requestedOrientation = if (landscape) {
            LogBus.i("Player", "手动：嵌入切回竖屏")
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            LogBus.i("Player", "手动：嵌入切横屏")
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    /**
     * v0.261.0：嵌入点播的横竖屏切换——原来只调 onFullscreen() 跳独立页，
     * 不改方向，手机竖着就还是竖屏，用户反馈“点击还是竖屏”。
     * 先强制锁横屏、设手动锁，再跳页，独立页进页就是横屏。
     */
    /**
     * v0.264.0（GPT 对齐后）：纯全屏——只跳独立页，不改方向、不锁方向。
     * 竖屏点全屏进独立页仍是竖屏，横屏点全屏进独立页仍是横屏；快照只保不断流，
     * 不设 manualLocked，退出独立页时 OrientationSensor.stop() 本就会清锁复位。
     * 旧的 forceLandscapeAndFullscreen()（强制横屏+跳页）已删除：全网已无调用，
     * 留着就是死代码，顶栏/底栏切屏与全屏从此分开。
     */
    fun goFullscreenOnly() {
        snapshotForRotation()
        LogBus.i("Player", "手动：纯全屏跳页，不改方向")
        onFullscreen()
    }

    /**
     * v0.21.0：当前字幕地址（含时间偏移处理）。双引擎统一走 SubtitleKit 单入口：
     * 偏移为 0 直接返回原地址；非 0 则下载移位后返回 file:// 本地地址。
     * 含网络 IO，必须在协程里调用。
     */
    suspend fun currentSubtitleUrl(): String? {
        val raw = (sessionSubtitle
            ?: vodId?.let {
                PlayerSettings.getSubtitleFor(context, playSourceKey, it, PlaySession.episodeIndex)
            }.orEmpty())
            .ifBlank { null }
        return SubtitleKit.prepare(
            context, raw, lastHeaders, PlayerSettings.subtitleStyle.value.offsetMs
        )
    }

    /**
     * v0.96.1：方向变化后若在播状态丢了→无缝恢复（双引擎通用）。
     * v0.121.0：直播走 resume-only 策略——直播没有进度概念，旋转时 Media3 只是
     *   surface 重建短暂缓冲，老逻辑 1.6s 内没恢复就 EngineHost.play 重拉流，
     *   这就是真机"横屏断流"（同一 m3u8 被重播 4 次）的触发点。直播绝不重拉流，
     *   resume 后等 3s；真停了留给用户手动点（点播的无缝重播逻辑不动）。
     */
    fun restoreAfterRotation() {
        if (!rotateWasPlaying || EngineHost.isPlayingNow()) return
        scope.launch {
            EngineHost.resume()
            if (isLive) {
                repeat(6) {
                    delay(500)
                    if (EngineHost.isPlayingNow()) {
                        LogBus.i("Player", "横屏后直播 resume 恢复成功")
                        return@launch
                    }
                }
                LogBus.w("Player", "横屏后直播仍未恢复（未重拉流），等待用户手动操作")
                return@launch
            }
            delay(900)
            if (EngineHost.isPlayingNow()) {
                LogBus.i("Player", "横屏后 resume 恢复成功")
                return@launch
            }
            // 真停了（IDLE）→ 按原地址/原进度无缝重播；磁力链路不走 EngineHost，只到 resume 为止
            if (MagnetPlayer.isMagnet(playUrl)) {
                LogBus.w("Player", "横屏后磁力链路未恢复，请手动点播")
                return@launch
            }
            val sub = runCatching { currentSubtitleUrl() }.getOrNull()
            val ok = EngineHost.play(
                context, playUrl, headers = lastHeaders,
                startPositionMs = rotatePosMs, subtitleUrl = sub,
            )
            if (ok) {
                EngineHost.applySubtitleStyle(PlayerSettings.subtitleStyle.value, playerView)
                LogBus.i("Player", "横屏后无缝重播成功")
            } else {
                LogBus.w("Player", "横屏后恢复播放失败")
            }
        }
    }

    // v0.96.1：系统方向变化后做一次"在播连续性"检查——翻转导致停播则无缝恢复；
    // 首次组合只记录不动作。v0.96.2：嵌入模式也参与（直播页手动横屏按钮要不断流），
    // sensor 本身依然只在独立页跑（上面的 LaunchedEffect 有 embedded 守卫）。
    val cfgOrientation = LocalConfiguration.current.orientation
    LaunchedEffect(cfgOrientation) {
        val prev = lastCfgOrientation
        lastCfgOrientation = cfgOrientation
        if (prev == null || prev == cfgOrientation) return@LaunchedEffect
        LogBus.i("Player", "屏幕方向变化：$prev → $cfgOrientation")
        delay(700)
        restoreAfterRotation()
    }

    // v0.125.0：全屏画面档位——应用值 = 全屏时用用户偏好 resizeIndex（持久化，
    // v0.255.0 起默认适应 FIT：拉伸会把竖屏剧竖屏拉长、横屏拉宽，横屏更吓人；
    // v0.123/124 无条件 ZOOM 裁掉上下约两成被反馈"卡边"，所以裁剪只留给用户
    // 手动选，不做默认），非全屏（竖屏小窗）恒适应 FIT。resizeIndex 只由
    // 「画面比例」菜单修改并落盘，本效应只负责应用，不再覆盖用户选择。
    val fullscreenNow = !embedded || cfgOrientation == Configuration.ORIENTATION_LANDSCAPE
    // v0.124.0：全屏状态驱动窗口沉浸式（全出血），与上面的方向/恢复逻辑互不干涉
    LaunchedEffect(fullscreenNow) {
        if (fullscreenNow) applyImmersiveFullscreen() else restorePlayerWindow()
    }
    LaunchedEffect(fullscreenNow, playerView, engine, resizeIndex) {
        val idx = if (fullscreenNow) resizeIndex else 0
        if (engine == PlayerEngine.MEDIA3) {
            playerView?.resizeMode = RESIZE_MODES[idx].first
        } else {
            MpvPlayerManager.setAspectMode(idx)
        }
    }

    // 播放 / 切集：先保存上一集进度，再播新地址（带断点续播）
    // playUrl 为空表示暂无可播放地址，不启动播放器
    // magnet: 地址不直接播，走 MagnetPlayer 探测本机转播服务后再起播；
    // 历史记录一律用原始 url（磁力链接本身）做 key，保证磁力也能断点续播
    var lastUrl by remember { mutableStateOf<String?>(null) }
    // v0.75.0：存 prev 进度时用旧集数（PlaySession.episodeIndex 已先更新到新集）
    var lastEpIdx by remember { mutableIntStateOf(PlaySession.episodeIndex) }
    // v0.25.0：起播链路顶层兜底——任何未预期异常转 Toast，绝不崩
    // v0.54：弹幕开关变化时按需加载/清空（不重进播放器也能开关）
    LaunchedEffect(danmakuEnabled) {
        if (danmakuEnabled && title.isNotBlank() && !isLive && danmakuList.isEmpty()) {
            danmakuList = com.movieshell.player.danmaku.DanmakuSource.fetchMerged(title)
        } else if (!danmakuEnabled) {
            danmakuList = emptyList()
        }
    }

    // v0.107.0 P0-4：重试统一入口——magnet: 地址不直接喂 EngineHost（ExoPlayer 必失败），
    // 重走 MagnetPlayer.playIfReady 探测本机转播服务链路。
    // 注意声明位置：LaunchedEffect(playUrl) 的初始失败与错误回调都要调它，
    // 局部函数没有提升，必须放在这两个调用点之前。
    suspend fun playCurrentUrl(startPositionMs: Long): Boolean {
        val sub = runCatching { currentSubtitleUrl() }.getOrNull()
        return if (MagnetPlayer.isMagnet(playUrl)) {
            MagnetPlayer.playIfReady(
                context, magnet = playUrl, title = title,
                startPositionMs = startPositionMs,
            )
        } else {
            EngineHost.play(
                context, playUrl, headers = lastHeaders,
                startPositionMs = startPositionMs, subtitleUrl = sub,
            )
        }
    }

    /**
     * v0.19.0：播放错误 → 按线路顺序自动重试（同集换线路）。
     * v0.25.0：抽成独立函数，自动换内核失败时复用；全部试完仍失败才置 errorMsg。
     * 注意声明位置：同 playCurrentUrl，初始失败链与错误回调都要调它。
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
                    // v0.175.0：自动换线路也要通知外部——之前只改了 PlaySession，
                    // 详情页的选中线路没收到通知，Tab 和源胶囊还停在旧线路上。
                    onEpisodeChange(targetLine, targetEpIdx)
                    AppToast.show(context, "已自动切换到「${lines[targetLine].name}」", long = false)
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

    /**
     * v0.186.0 第 5 组：播放失败统一自动链，初始起播失败与播放中错误回调共用。
     * 顺序固定：同内核重拉（≤2 次）→ 换内核 → 换线路，链耗尽才置 errorMsg
     * （点播走错误对话框，直播经 errorMsg 效应走原 onLiveError 切源，语义不变）。
     * 起播提交失败（playCurrentUrl 返回 false）同样计一次同内核重试，
     * 不得直接跳步到换线路；换内核后新内核重新给同内核重试机会，
     * 但已试过的内核不再回切。
     * 换内核三步固定：旧引擎 currentPosition() 取断点 → stop() → 改覆盖 →
     * 新引擎 play(pos)，不得改覆盖后才读新引擎位置当断点。
     */
    fun handlePlayFailure(msg: String) {
        if (sameEngineRetries.intValue < 2) {
            sameEngineRetries.intValue++
            LogBus.i(
                "Player",
                "播放失败，当前内核重拉重试 ${sameEngineRetries.intValue}/2：$msg",
            )
            scope.launch {
                delay(600)
                val ok = playCurrentUrl(
                    if (isLive) 0L else EngineHost.currentPosition()
                )
                if (!ok) handlePlayFailure(msg)
            }
            return
        }
        val cur = EngineHost.engine()
        val other =
            if (cur == PlayerEngine.MEDIA3) PlayerEngine.LIBMPV else PlayerEngine.MEDIA3
        // v0.186.0 第 5 组：RTMP 与 RTP 只能走 libmpv，与 EngineHost 强制路由一致。
        // Media3 在此不是可用目标，直接跳过，不走“切到 Media3 又被强制改回
        // libmpv”的空转；此时 other 视为不可用，落到换线路。
        val isRtmpOrRtp = playUrl.trim().startsWith("rtmp://", true) ||
            playUrl.trim().startsWith("rtp://", true)
        val otherUsable = other != PlayerEngine.MEDIA3 || !isRtmpOrRtp
        val otherUsableWithInstall = otherUsable && (other == PlayerEngine.MEDIA3 ||
            (MpvPluginLoader.isInstalled(context) && !MpvPlayerManager.initFailed))
        if (!otherUsableWithInstall) {
            LogBus.w("Player", "跳过自动换内核：$other 不可用（未安装、初始化失败过或 RTMP/RTP 不走 Media3）")
        }
        val alreadyTried = triedEngines + EngineHost.fallbackTriedEngines()
        if (otherUsableWithInstall && other !in alreadyTried) {
            triedEngines.add(cur)
            triedEngines.add(other)
            try {
                LogBus.i("Player", "播放失败，自动换内核重试：$cur → $other")
                // 三步固定：先取旧引擎断点，再停旧引擎，再改覆盖，最后新引擎起播。
                val pos = EngineHost.currentPosition()
                EngineHost.stop()
                EngineHost.setEngineOverride(other)
                sameEngineRetries.intValue = 0 // 新内核重新给同内核重试机会
                scope.launch {
                    val ok = playCurrentUrl(pos)
                    if (ok) {
                        EngineHost.applySubtitleStyle(
                            PlayerSettings.subtitleStyle.value, playerView
                        )
                    } else {
                        handlePlayFailure(msg)
                    }
                }
            } catch (t: Throwable) {
                LogBus.e(
                    "Player",
                    "自动换内核异常：${t::class.java.simpleName}: ${t.message}",
                )
                AppToast.show(context, "切换内核失败，尝试换线路", long = false)
                tryNextLine(msg)
            }
            return
        }
        tryNextLine(msg)
    }

    LaunchedEffect(playUrl) {
        // v0.188.0 M03：换地址先清上一集残留，再拉新——上一集手动选的
        // 字幕覆盖（sessionSubtitle）与旧弹幕列表，否则同标题切集时旧
        // 字幕/旧弹幕会带到下一集。安全说明：字幕选择走 replayWithSubtitle，
        // 它只重播当前 playUrl、不改 playUrl，本效应不会因此重跑，
        // 刚选的字幕不会被这里清掉；清掉后本效应内仍按标题拉新弹幕。
        sessionSubtitle = null
        danmakuList = emptyList()
        // v0.54：弹幕预加载（按标题搜，不阻塞播放；失败静默无弹幕）
        if (danmakuEnabled && title.isNotBlank() && !isLive) {
            launch {
                danmakuList = com.movieshell.player.danmaku.DanmakuSource.fetchMerged(title)
            }
        } else if (!danmakuEnabled) {
            danmakuList = emptyList()
        }
        try {
            if (!autoSwitching) triedLines.clear() else autoSwitching = false
            // v0.186.0 第 5 组：先保存上一地址进度，再清自动换内核覆盖。
            // savePlayerProgress 经 EngineHost.currentPosition() 按当前覆盖
            // 路由读位置，先清覆盖会读成用户设置引擎的位置而非实际在播
            // 的覆盖引擎位置，所以顺序不得颠倒。
            lastUrl?.let { prev ->
                savePlayerProgress(
                    context, title, prev, isLive,
                    vodId, lastEpIdx,
                    playSourceType, playSourceKey.orEmpty(),
                )
            }
            // v0.21.0：换地址清除自动换内核覆盖，回到用户持久化选择
            EngineHost.setEngineOverride(null)
            triedEngines.clear()
            EngineHost.clearFallbackTriedEngines()
            sameEngineRetries.intValue = 0
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
                    // v0.160.0：直播频道自带 ua 先打底，嗅探头优先覆盖；无 ua 时不编
                    val headers = liveHeaders + SniffPlayHeaders.take(playUrl)
                    lastHeaders = headers
                    val skipIntro = PlayerSettings.getSkipIntroFor(
                        context, playSourceKey, vodId, PlaySession.episodeIndex,
                    )
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
                        // v0.74.0：应用记忆的倍速
                        EngineHost.setPlaybackSpeed(SPEEDS[speedIndex])
                    } else {
                        // v0.186.0 第 5 组：初始起播失败同样走完整自动链
                        // （同内核重拉 → 换内核 → 换线路），错误对话框只在
                        // 链耗尽后由 tryNextLine 置 errorMsg 时出现；直播
                        // 链耗尽后仍经 errorMsg 效应走原 onLiveError 切源。
                        handlePlayFailure("播放启动失败，请检查地址或切换线路")
                    }
                }
            }
            lastUrl = playUrl
            lastEpIdx = PlaySession.episodeIndex
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            LogBus.e("Player", "起播链路异常：${t::class.java.simpleName}: ${t.message}")
            AppToast.show(context, "播放启动失败，请重试", long = false)
        }
    }

    /**
     * v0.19.0：切到指定线路/选集（线路抽屉、片尾自动下一集共用）。
     * 解析失败由 resolvePlayUrl 内部 Toast，不切。
     */
    fun switchEpisode(lineIdx: Int, epIdx: Int) {
        val resolver = PlaySession.resolver ?: return
        val ep = PlaySession.lines.getOrNull(lineIdx)?.episodes?.getOrNull(epIdx) ?: return
        // v0.109.0 A10：切集代际 guard——快速连点不同选集，慢的旧解析后完成会覆盖新选择。
        // 先 cancel 旧 Job；不合作的解析器再靠代际计数兜底，过期结果直接丢弃（v0.106.0 的
        // 应用级 scope 本意是"切页不中断解析"，切集时旧解析该停——cancel 只针对上一次切集）
        resolveJob?.cancel()
        val myGen = ++resolveGen
        resolveJob = PlayResolveScope.launch {
            val link = resolver(context, ep)
            if (myGen != resolveGen) return@launch // 已被更新的切集取代，丢弃
            if (link.isNotBlank()) {
                PlaySession.moveTo(lineIdx, epIdx)
                triedLines.clear()
                playUrl = link
                // v0.65：通知外部（嵌入时详情页高亮跟随）
                onEpisodeChange(lineIdx, epIdx)
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
            // v0.158.0：直播页接管最终失败（自动切源），不弹原错误框；
            // 直播页切完仍失败时由它自己给“同名源均播放失败”提示。
            if (isLive && onLiveError != null) {
                errorMsg = null
                onLiveError()
            } else {
                playError = PlayError.classify(it)
                errorMsg = null
            }
        }
    }
    // v0.160.0：直播 8 秒无画面切源——黑屏挂起不报错时 158 的自动切源永远触发不了，
    // 这里只看“自起播以来有没有真正播过”：播过后用户手动暂停不触发，换地址后计时重置
    var liveEverPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(playUrl) {
        liveEverPlayed = false
        if (isLive && onLiveError != null && playUrl.isNotBlank()) {
            kotlinx.coroutines.delay(8000)
            if (!liveEverPlayed) {
                LogBus.i("Player", "直播 8 秒无画面，触发自动切源")
                errorMsg = "直播 8 秒无画面"
            }
        }
    }
    // v0.57：错误对话框的"重试"——按当前进度重播当前地址
    // v0.186.0 第 5 组：playCurrentUrl 已前移至 LaunchedEffect(playUrl) 之前，
    // 初始失败与错误回调共用同一 handlePlayFailure 链，此处不再重复定义。
    fun retryCurrent() {
        scope.launch {
            try {
                val pos = EngineHost.currentPosition()
                val ok = playCurrentUrl(pos)
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
                else AppToast.show(context, "字幕应用失败", long = false)
            } catch (t: Throwable) {
                LogBus.e("Player", "重播异常：${t.message}")
                AppToast.show(context, "操作失败，请重试", long = false)
            }
        }
    }

    // v0.25.0：切换中防抖（快速连点不叠请求）
    var switchingEngine by remember { mutableStateOf(false) }
    /**
     * v0.209.0：播放器内点选切换内核（菜单直选 Exo/mpv，点谁切谁）。
     * v0.255.0：底部引擎/清晰度菜单已收进顶部更多菜单，旧的两个显隐状态删掉。
     * 停掉旧引擎、持久化选择、按当前进度重播；AndroidView 用 key(engine) 重建对应画面。
     * v0.21.0：手动切换先清掉自动换内核覆盖；字幕走 SubtitleKit。
     * v0.25.0：try/catch 兜底 + 防抖，切换失败 Toast 不崩。
     */
    fun switchToEngine(target: PlayerEngine) {
        if (switchingEngine) return
        switchingEngine = true
        scope.launch {
            try {
                val pos = EngineHost.currentPosition()
                // v0.123.0：手动切 libmpv 前先查插件在不在，没装别把用户带进黑屏
                if (target == PlayerEngine.LIBMPV && !MpvPluginLoader.isInstalled(context)) {
                    AppToast.show(context, "libmpv 插件未安装，请先到插件市场安装", long = true)
                    return@launch
                }
                EngineHost.stop()
                EngineHost.setEngineOverride(null)
                PlayerSettings.setEngine(context, target)
                AppToast.show(context, "播放内核已切换为 ${if (target == PlayerEngine.MEDIA3) "Media3" else "libmpv"}", long = false)
                LogBus.i("Player", "切换播放内核 → $target")
                val sub = runCatching { currentSubtitleUrl() }.getOrNull()
                val ok = EngineHost.play(
                    context, playUrl, headers = lastHeaders,
                    startPositionMs = pos, subtitleUrl = sub,
                )
                if (ok) EngineHost.applySubtitleStyle(PlayerSettings.subtitleStyle.value, playerView)
                else AppToast.show(context, "切换后起播失败", long = false)
            } catch (t: Throwable) {
                LogBus.e("Player", "手动切换内核异常：${t::class.java.simpleName}: ${t.message}")
                AppToast.show(context, "切换内核失败", long = false)
            } finally {
                switchingEngine = false
            }
        }
    }

    // v0.19.0：播放错误 -> 按线路顺序自动重试（同集换线路）；
    // 全部线路试完仍失败才 Snackbar 提示。无 PlaySession（嗅探页直进）时保持原行为。
    // v0.20.0：错误回调走 EngineHost，Media3/libmpv 双引擎统一消息字符串。
    // v0.123.0（补充B）：恢复顺序重排——①当前内核重拉同一地址（≤2 次，直播 IO
    // 抖动多半自愈，别一出错就换内核）②换内核（目标不可用直接跳过：libmpv 未安装/
    // 初始化失败过不许切，v0.122.0 真机实锤切过去后黑屏死胡同）③换线路。
    DisposableEffect(Unit) {
        // v0.186.0 第 5 组：播放中错误回调复用 handlePlayFailure 统一链，
        // 与初始起播失败同一顺序、同一记账，不再另写一套链。
        EngineHost.setOnErrorListener { msg ->
            handlePlayFailure(msg)
        }
        onDispose { EngineHost.setOnErrorListener(null) }
    }
    playError?.let { err ->
        TechDialog(
            onDismissRequest = { playError = null },
            title = err.title,
            confirmText = "重试",
            onConfirm = {
                playError = null
                // 重试当前地址
                scope.launch { retryCurrent() }
            },
            dismissText = "关闭",
        ) {
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
                    // v0.149 DS 案例 4：源侧 Referer 导致换流后播不了时，给一条
                    // "Referer 全链路清零"的验证式重试——只在嗅探直链带 Referer 时出现；
                    // 点后本次会话的后续重试也沿用无 Referer 请求头（lastHeaders 已改写）
                    if (!MagnetPlayer.isMagnet(playUrl) && lastHeaders.containsKey("Referer")) {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                playError = null
                                scope.launch {
                                    try {
                                        val pos = EngineHost.currentPosition()
                                        val noRefHeaders = lastHeaders - "Referer"
                                        lastHeaders = noRefHeaders
                                        LogBus.i("Player", "清除 Referer 后重试：$playUrl")
                                        val sub = runCatching { currentSubtitleUrl() }.getOrNull()
                                        val ok = EngineHost.play(
                                            context, playUrl, headers = noRefHeaders,
                                            startPositionMs = pos, subtitleUrl = sub,
                                        )
                                        if (!ok) playError = PlayError.classify("重试失败")
                                    } catch (t: Throwable) {
                                        LogBus.e("Player", "无 Referer 重试异常：${t.message}")
                                        playError = PlayError.classify(t.message.orEmpty())
                                    }
                                }
                            },
                        ) {
                            androidx.compose.material3.Text("清除 Referer 后重试")
                        }
                    }
                }
            }
    }

    // v0.14.0：片尾跳过——距离结尾 skipOutroSec 时自动下一集（无下一集则暂停）
    // v0.16.2：按源取片尾；v0.19.0：下一集走 PlaySession（播放器内线路/选集）
    // v0.20.0：进度/时长走 EngineHost，双引擎统一
    // v0.21.0：定时关闭「播完本集」布防时，集尾暂停而非切下一集
    // v0.150.0：v0.65 的循环模式按钮此前只换文案、从不生效，本版让它真正管事；
    // 同时补上 DS 的 autoNextEpisode：没设片尾跳过时，自然播完也自动下一集，
    // 不再播完就停在黑屏上。定时关闭优先级最高，任何模式都不越过它。
    LaunchedEffect(playUrl, sleepArmed, playModeIndex) {
        // v0.24.0：直播无“集”概念，跳过片尾下一集/播完暂停逻辑
        if (isLive) return@LaunchedEffect
        val skipOutro = PlayerSettings.getSkipOutroFor(
            context, playSourceKey, vodId, PlaySession.episodeIndex,
        )
        while (true) {
            delay(1000)
            val dur = EngineHost.duration()
            if (dur <= 0) continue
            val pos = EngineHost.currentPosition()
            if (sleepArmed && pos >= dur - 2000) {
                if (VideoSleepTimer.consumeAfterEpisode()) {
                    EngineHost.pause()
                    AppToast.show(context, "定时关闭：本集播完已暂停", long = false)
                    LogBus.i("Player", "定时关闭触发：播完本集暂停")
                }
                break
            }
            // 有片尾跳过时提前触发，否则自然播完（留 1 秒余量，防时长抖动）才触发
            val endAt = if (skipOutro > 0) dur - skipOutro * 1000L else dur - 1000L
            if (pos < endAt) continue
            val nextIdx = PlaySession.episodeIndex + 1
            val hasNext = PlaySession.currentLine()?.episodes?.getOrNull(nextIdx) != null
            when (playModeIndex) {
                1 -> {
                    // 单集循环：回到开头继续播，不切集、不 break——下次到结尾会再触发
                    EngineHost.seekTo(0L)
                    EngineHost.resume()
                    LogBus.i("Player", "单集循环：回到开头重播")
                    delay(2000)
                }
                2 -> {
                    // 列表循环：有下一集就切，没有就回到第一集
                    if (hasNext) switchEpisode(PlaySession.lineIndex, nextIdx)
                    else if (PlaySession.currentLine()?.episodes?.isNotEmpty() == true) {
                        switchEpisode(PlaySession.lineIndex, 0)
                    } else {
                        EngineHost.pause()
                    }
                    break
                }
                else -> {
                    // 顺序播放：有下一集就切，没有就暂停
                    if (hasNext) switchEpisode(PlaySession.lineIndex, nextIdx)
                    else EngineHost.pause()
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

    // v0.65：嵌入模式用 16:9 盒子（详情页顶部），独立模式全屏；
    // v0.96.2：嵌入横屏时占满（直播页手动横屏按钮 / 物理横屏），cfgOrientation 上面已读
    Box(
        modifier = (if (embedded && cfgOrientation != Configuration.ORIENTATION_LANDSCAPE)
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        else Modifier.fillMaxSize()).background(Color.Black)
    ) {
        if (playUrl.isBlank()) {
            Text(
                text = "暂无可播放地址",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = V4Type.Body,
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
                            MpvVideoView(ctx).also { videoView = it }
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
                                videoView = it
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // v0.109.0 A2：播放中屏幕常亮（keepScreenOn=true），暂停/切内核/退出时恢复 false，
                // 否则视频播放时按系统息屏设置熄屏；key videoView 保证切内核时先清旧画面再置新画面
                DisposableEffect(isPlaying, videoView) {
                    val v = videoView
                    v?.keepScreenOn = isPlaying
                    onDispose { v?.keepScreenOn = false }
                }
            }
            // v0.54：弹幕覆盖层（视频画面之上，手势层之下）
            // v0.272.0（T9）：暂停时帧时钟停转，传 isPlaying 进去。
            com.movieshell.player.danmaku.DanmakuOverlay(
                danmakus = danmakuList,
                positionMs = EngineHost.currentPosition(),
                enabled = danmakuEnabled && !playUrl.isBlank(),
                isPlaying = isPlaying,
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
                        // v0.256.0：竖屏全屏底部上滑切下一集——只认独立播放页竖屏、
                        // 非直播、有下一集、起点在底部 140dp 内；该区域手势不再
                        // 走亮度/音量分支，起点区域本身就是区分依据，不再另设
                        // 速度阈值（慢拖 100dp 松手也是明确意图，速度门槛只会
                        // 让慢拖用户觉得“滑了没反应”）。
                        val swipeNextCandidate = !embedded && !isLive && !locked &&
                            cfgOrientation == Configuration.ORIENTATION_PORTRAIT &&
                            PlaySession.currentLine()?.episodes
                                ?.getOrNull(PlaySession.episodeIndex + 1) != null &&
                            down.position.y > size.height - 140.dp.toPx()
                        var swipeUpPx = 0f
                        val swipeThresholdPx = 100.dp.toPx()
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
                                    if (swipeNextCandidate) {
                                        // 净上滑距离（起点 y 减当前 y）；先上后下会
                                        // 抵消，不到阈值松手回弹，提示只说意图，
                                        // 不在拖动中切集。
                                        swipeUpPx = down.position.y - last.y
                                        gestureHint = if (swipeUpPx >= swipeThresholdPx)
                                            "松手切下一集" else "继续上滑切下一集"
                                    } else {
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
                            }
                            change.consume()
                        } while (change.pressed)
                        // v0.256.0：上滑切集只在松手时判定一次——达到阈值走
                        // 现有 switchEpisode（保存当前进度、不套用到下一集
                        // 由 switchEpisode/起播链路处理），不到阈值只清提示
                        // 回弹，不切集；同一次拖动绝不触发两次。
                        if (mode == 2 && swipeNextCandidate) {
                            gestureHint = null
                            if (swipeUpPx >= swipeThresholdPx) {
                                switchEpisode(
                                    PlaySession.lineIndex,
                                    PlaySession.episodeIndex + 1,
                                )
                            }
                        }
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
                        RoundedCornerShape(V4.RPoster),
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(hint, color = Color.White, style = V4Type.Meta)
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
                    .zIndex(1f)
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        // v0.40.0：顶部栏按目标界面重做——返回 + 标题 + 画中画 + 投屏 + 齿轮
        // 锁定时隐藏顶栏（只留解锁按钮防误触）
        // v0.65：加时钟 + 分辨率行（对标目标界面）
        var clockText by remember { mutableStateOf("") }
        var streamInfo by remember { mutableStateOf("") }
        LaunchedEffect(controlsVisible) {
            if (!controlsVisible) return@LaunchedEffect
            while (true) {
                clockText = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(java.util.Date())
                val vs = PlayerManager.player?.videoSize
                streamInfo = if (vs != null && vs.width > 0) "${vs.width}×${vs.height}" else ""
                kotlinx.coroutines.delay(10_000)
            }
        }
        if (controlsVisible && !locked) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    // v0.123.0：控制层显式置于手势层之上（zIndex 分层），防横屏时 tap 被
                    // 全屏手势层会话抢走、按钮点了没反应（用户反馈横屏返回/暂停失效）
                    .zIndex(1f)
                    .let { if (embedded) it else it.statusBarsPadding() }
                    .background(Color.Black),
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // v0.69.1：嵌入模式收窄上下黑边——顶栏只留一行，纵向 padding 置 0
                    .padding(horizontal = 4.dp, vertical = if (embedded) 0.dp else 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundControlButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    desc = "返回",
                    onClick = {
                        // v0.123.0：嵌入直播横屏时返回 = 退出横屏（LiveScreen 传的 onBack 是
                        // 空实现，横屏下点了一点反应都没有，正是"返回键失效"的实锤之一）
                        if (embedded && isLive &&
                            cfgOrientation == Configuration.ORIENTATION_LANDSCAPE
                        ) {
                            toggleEmbeddedLandscape()
                        } else {
                            onBack()
                        }
                    },
                )
                Text(
                    text = title,
                    color = Color.White,
                    style = V4Type.RowTitle,
                    maxLines = 1,
                    softWrap = false,
                    // v0.255.0：标题超长跑马灯（basicMarquee 只在溢出时滚，短标题不动；
                    // weight 在顶栏 Row 里有明确宽度，溢出判断才准）
                    modifier = Modifier.weight(1f).basicMarquee(),
                )
                // v0.259.0：清晰度只读标签“自动”放标题旁（目前只有自动档位，
                // 不做可点开的假分辨率列表）
                if (!isLive) {
                    Text(
                        text = "自动",
                        color = Color.White.copy(alpha = 0.7f),
                        style = V4Type.Meta,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                // v0.69.1：嵌入模式分辨率并入标题行（单独的分辨率行只在全屏保留）
                if (embedded && streamInfo.isNotBlank()) {
                    Text(
                        text = streamInfo,
                        color = Color.White.copy(alpha = 0.7f),
                        style = V4Type.Meta,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                // v0.24.0：直播角标（v0.27.0：柔和错误色胶囊）
                if (isLive) {
                    Surface(
                        shape = RoundedCornerShape(V4.RPoster),
                        color = V4.c.live,
                    ) {
                        Text(
                            text = "直播",
                            // v0.109.0 A9：errorSoft 亮色主题下是深红 #DC2626，黑字在其上约 2.9:1；
                            // 按底色明度自适应（暗底黑字、深红底白字）
                            color = if (V4.c.live.luminance() > 0.18f)
                                Color.Black else Color.White,
                            style = V4Type.Meta,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                // v0.65：时钟（对标目标界面右上角时间）
                if (clockText.isNotBlank()) {
                    Text(
                        text = clockText,
                        color = Color.White,
                        style = V4Type.Meta,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                // 画中画
                RoundControlButton(
                    icon = Icons.Filled.PictureInPictureAlt,
                    desc = "画中画",
                    onClick = { enterPip(context) },
                )
                // F1：投屏是占位按钮（v0.40.0 起只弹“开发中”），未实现前隐藏，
                // 不占顶栏位置；真要做时接 DLNA 或 Cast 再加回。
                // v0.121.0：嵌入模式横屏入口——用户反馈详情页"没有切横屏的按钮"（底栏全屏键
                // 在窄屏上会被挤出可视区）。点播跳独立播放页，直播原地旋转（与底栏逻辑一致）。
                // v0.264.0：顶栏是切屏，不是全屏——嵌入点播与直播都只原地翻转，
                // 不跳页；嵌入点播原地横屏的容器问题由 DetailScreen 横屏分支处理
                // （横屏时嵌入容器改 fillMaxSize、不再套 16:9 + 滚动偏移）。
                if (embedded) {
                    RoundControlButton(
                        icon = Icons.Filled.ScreenRotation,
                        desc = "切屏",
                        onClick = { toggleEmbeddedLandscape() },
                    )
                } else {
                    // v0.264.0：独立页顶栏补切屏按钮——旋转图标、按方向给目标文案，
                    // 只调 toggleOrientation()，不跳页、不退出全屏。
                    RoundControlButton(
                        icon = Icons.Filled.ScreenRotation,
                        desc = if (cfgOrientation == Configuration.ORIENTATION_LANDSCAPE) "切换竖屏" else "切换横屏",
                        onClick = { toggleOrientation() },
                    )
                }
                // v0.259.0：三个点菜单整体删除，改成信息 + 追剧开关 + 齿轮；
                // 追剧开关紧邻齿轮左侧，齿轮点开底部页（六组），信息点开简介弹窗。
                // 音轨/视轨无现有选择能力，不做假按钮；片头/片尾在齿轮播放组里
                // 用跳过设置管理，不在顶栏单独造开关。
                RoundControlButton(
                    icon = Icons.Filled.Info,
                    desc = "简介",
                    onClick = { showInfoDialog = true },
                )
                if (!isLive && followPlayerKey != null && vodId != null) {
                    RoundControlButton(
                        icon = if (playerFollowing) Icons.Filled.PlaylistAddCheck
                        else Icons.Filled.PlaylistAdd,
                        desc = if (playerFollowing) "已追剧" else "追剧",
                        tint = if (playerFollowing) Color(0xFF4DD0E1) else Color.White,
                        onClick = {
                            val key = followPlayerKey
                            val id = vodId
                            scope.launch {
                                if (playerFollowing) {
                                    if (FollowStore.unfollow(context, FollowStore.idOf(key, id))) {
                                        AppToast.show(context, "已取消追剧", long = false)
                                    } else {
                                        AppToast.show(context, "取消追剧失败，请重试", long = false)
                                    }
                                } else {
                                    // 封面为空是允许的：FollowStore.follow 会保留库内已有封面，
                                    // 全新记录无封面时下次进详情页由详情加载回填，不编造封面。
                                    val ok = FollowStore.follow(
                                        context,
                                        FollowItem(
                                            id = FollowStore.idOf(key, id),
                                            vodId = id,
                                            name = title,
                                            pic = "",
                                            sourceType = playSourceType,
                                            sourceKey = key,
                                            episodeCount = sessionLines.sumOf { it.episodes.size },
                                        ),
                                    )
                                    AppToast.show(
                                        context,
                                        if (ok) "已追剧，有更新会提醒你" else "追剧失败，请重试",
                                        long = false,
                                    )
                                }
                            }
                        },
                    )
                }
                // v0.261.0：横屏不需要齿轮按钮——常用功能已在横屏底栏，
                // 不常用的回竖屏点齿轮调；竖屏才显示齿轮。
                if (cfgOrientation != Configuration.ORIENTATION_LANDSCAPE) {
                    RoundControlButton(
                        icon = Icons.Filled.Settings,
                        desc = "设置",
                        onClick = { showGearSheet = true },
                    )
                }
            }
            // v0.65：分辨率行（对标目标界面 3840x1920 · 码率）
            // v0.69.1：嵌入模式已并入标题行，这里只在全屏显示
            if (streamInfo.isNotBlank() && !embedded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = streamInfo,
                        color = Color.White.copy(alpha = 0.85f),
                        style = V4Type.Meta,
                    )
                }
            }
            } // Column：顶栏区
        } // if (controlsVisible && !locked)：顶部栏

        // v0.40.0：悬浮按钮——锁定 + 截图（目标界面：圆形半透明黑底）
        // v0.65：移到右侧（对标目标界面）
        // 锁定时只显示解锁按钮（常显，不跟随控制层自动隐藏）
        if (locked) {
            RoundControlButton(
                icon = Icons.Filled.LockOpen,
                desc = "解锁",
                onClick = {
                    locked = false
                    controlsVisible = true
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .zIndex(1f)
                    .padding(end = 16.dp),
            )
        } else if (controlsVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .zIndex(1f)
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RoundControlButton(
                    icon = Icons.Filled.Lock,
                    desc = "锁定",
                    onClick = { locked = true },
                )
                RoundControlButton(
                    icon = Icons.Filled.CameraAlt,
                    desc = "截图",
                    onClick = {
                        scope.launch {
                            // v0.261.0：相机闪退根因——原来在 Dispatchers.IO 里调
                            // EngineHost.currentPosition()，Media3 Player 要求主线程访问，
                            // 抛 IllegalStateException: Player is accessed on the wrong thread
                            //（DefaultDispatcher-worker-5）。先在主线程取进度，再切 IO 只做落盘。
                            val pos = EngineHost.currentPosition()
                            val file = runCatching {
                                withContext(Dispatchers.IO) {
                                    EngineHost.screenshot(
                                        context, title, playUrl, lastHeaders, pos,
                                    )
                                }
                            }.getOrNull()
                            AppToast.show(context, if (file != null) "截图已保存：${file.name}" else "截图失败", long = false)
                        }
                    },
                )
            }
        }

        // v0.27.0：播放错误提示走柔和错误色，不用刺眼红
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
            snackbar = { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(V4.RField),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = V4.c.live,
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
                if (isPlaying) liveEverPlayed = true
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
                    .zIndex(1f)
                    .fillMaxWidth()
                    // v0.84.0：半透明，少遮挡画面；用户反馈再调低一点
                    .background(Color.Black.copy(alpha = 0.3f))
                    // v0.69.1：嵌入模式收窄底部黑边
                    .padding(horizontal = 12.dp, vertical = if (embedded) 0.dp else 2.dp),
            ) {
                // 第一行：当前时间 + 进度条 + 总时长；v0.86.0：直播没有进度可言，直接隐藏
                if (!isLive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = fmtTime(if (scrubbing) scrubPos else pos),
                        color = Color.White,
                        style = V4Type.Meta,
                    )
                    // v0.125.0：进度条抽成 PlayerSeekBar 组件（内部关掉 48dp 最小
                    // 交互高度、行高压到 28dp），进度线贴到按钮行正上方、落在视频
                    // 底边附近（用户反馈"进度条太高卡视角"）；拖动行为不变。
                    PlayerSeekBar(
                        value = (if (scrubbing) scrubPos else pos).toFloat(),
                        valueRange = 0f..dur.coerceAtLeast(1L).toFloat(),
                        onValueChange = {
                            scrubPos = it.toLong()
                            scrubbing = true
                        },
                        onValueChangeFinished = {
                            EngineHost.seekTo(scrubPos)
                            scrubbing = false
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(
                        text = fmtTime(dur),
                        color = Color.White,
                        style = V4Type.Meta,
                    )
                }
                }
                // 第二行：暂停 + 上一集/下一集 + 倍速 + 选集 + 自动 + 引擎 + 全屏（固定右端）
                // v0.255.0：外层不滚动、左区可滚动、右区只放全屏——v0.221 的整行
                // 横向滚动 + Spacer(weight) 结构在窄屏上把全屏推出屏外（得横滑才
                // 找得到），且 weight 在滚动容器里剩余空间分配不可靠，正是小窗
                // 切全屏回来图标看着变形的嫌疑点；顺序播放/自动/引擎收进顶部
                // 更多菜单，底部只留高频项，全屏 36dp 图标 20dp 全程固定尺寸。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                ) {
                    RoundControlButton(
                        icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        desc = if (isPlaying) "暂停" else "播放",
                        onClick = { EngineHost.togglePlayPause() },
                        size = 36.dp,
                    )
                    // v0.228.0：第二行时间文本删掉——进度行已有当前/总时长，
                    // 重复占约 80dp，正是尾巴（自动、引擎、全屏）被推出屏外的
                    // 主因之一；嵌入与直播本来就不显示它。
                    if (!isLive) {
                        val hasPrev = PlaySession.episodeIndex > 0
                        val hasNext = PlaySession.currentLine()?.episodes
                            ?.getOrNull(PlaySession.episodeIndex + 1) != null
                        RoundControlButton(
                            icon = Icons.Filled.SkipPrevious,
                            desc = "上一集",
                            onClick = {
                                if (hasPrev) switchEpisode(
                                    PlaySession.lineIndex,
                                    PlaySession.episodeIndex - 1,
                                )
                            },
                            tint = if (hasPrev) Color.White else Color.White.copy(alpha = 0.3f),
                            size = 36.dp,
                        )
                        RoundControlButton(
                            icon = Icons.Filled.SkipNext,
                            desc = "下一集",
                            onClick = {
                                if (hasNext) switchEpisode(
                                    PlaySession.lineIndex,
                                    PlaySession.episodeIndex + 1,
                                )
                            },
                            tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.3f),
                            size = 36.dp,
                        )
                    }
                    // v0.121.0：直播隐藏倍速（用户反馈"直播不需要倍速"；直播流也不允许变速）
                    if (!isLive) {
                        TextButton(
                            onClick = {
                                speedIndex = (speedIndex + 1) % SPEEDS.size
                                EngineHost.setPlaybackSpeed(SPEEDS[speedIndex])
                                // v0.74.0：倍速记忆
                                PlayerSettings.setSpeed(context, SPEEDS[speedIndex])
                            },
                            modifier = Modifier.height(48.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            // 目标界面样式：1.0X（大写 X）
                            Text(
                                "${SPEEDS[speedIndex]}X",
                                color = Color.White,
                                style = V4Type.Body,
                            )
                        }
                    }
                    if (!isLive) {
                        TextButton(
                            onClick = { showLines = true },
                            modifier = Modifier.height(48.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            // v0.228.0：选集图标去掉只留文字——图标+文字占 72dp，
                            // “选集”两字自明，省 20dp 给尾巴的按钮。
                            Text("选集", color = Color.White, style = V4Type.Body)
                        }
                    }
                    // v0.259.0：横屏完整条、竖屏精简条。竖屏只留暂停/上一集/
                    // 下一集/倍速/选集（上面已渲染），引擎/画面比例/刷新/
                    // 循环/字幕/片头/片尾收进齿轮；横屏在选集后追加这些项。
                    // “自动”已移到顶栏标题旁只读显示，底部不再重复放；
                    // 底部“刷新”定义为重播当前集（retryCurrent，按当前进度
                    // 重拉流），不重抓详情——详情刷新按钮已砍掉，不换名复活。
                    if (!isLive && cfgOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                        TextButton(
                            onClick = {
                                switchToEngine(
                                    if (engine == PlayerEngine.MEDIA3) PlayerEngine.LIBMPV
                                    else PlayerEngine.MEDIA3
                                )
                            },
                            modifier = Modifier.height(48.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text(
                                if (engine == PlayerEngine.MEDIA3) "Media3" else "libmpv",
                                color = Color.White, style = V4Type.Body,
                            )
                        }
                        TextButton(
                            onClick = {
                                resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size
                                PlayerSettings.setResizeMode(context, resizeIndex)
                            },
                            modifier = Modifier.height(48.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text(RESIZE_MODES[resizeIndex].second, color = Color.White, style = V4Type.Body)
                        }
                        RoundControlButton(
                            icon = Icons.Filled.Refresh,
                            desc = "刷新",
                            onClick = { retryCurrent() },
                            size = 36.dp,
                        )
                        TextButton(
                            onClick = { playModeIndex = (playModeIndex + 1) % PLAY_MODES.size },
                            modifier = Modifier.height(48.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text(PLAY_MODES[playModeIndex], color = Color.White, style = V4Type.Body)
                        }
                        TextButton(
                            onClick = { showSubtitleDialog = true },
                            modifier = Modifier.height(48.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text("字幕", color = Color.White, style = V4Type.Body)
                        }
                        if (vodId != null) {
                            TextButton(
                                onClick = { showSkipSettings = true },
                                modifier = Modifier.height(48.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp),
                            ) {
                                Text("片头", color = Color.White, style = V4Type.Body)
                            }
                            TextButton(
                                onClick = { showSkipSettings = true },
                                modifier = Modifier.height(48.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp),
                            ) {
                                Text("片尾", color = Color.White, style = V4Type.Body)
                            }
                        }
                    }
                }
                // v0.264.0：底栏是全屏，不是切屏——嵌入时恒为“全屏”（Fullscreen 图标，
                // 只跳独立页、不改方向、不锁方向）；独立时恒为“退出全屏”
                // （FullscreenExit 图标，只调 onBack 回详情页、不改方向）。
                // 切屏只在顶栏（ScreenRotation 图标），两者图标、文案、onClick 全部分开。
                if (embedded) {
                    RoundControlButton(
                        icon = Icons.Filled.Fullscreen,
                        desc = "全屏",
                        onClick = { goFullscreenOnly() },
                        size = 36.dp,
                    )
                } else {
                    RoundControlButton(
                        icon = Icons.Filled.FullscreenExit,
                        desc = "退出全屏",
                        onClick = { onBack() },
                        size = 36.dp,
                    )
                }
                }
                // v0.259.0：清晰度“自动”已移到顶栏标题旁只读显示；
                // 引擎等横屏完整条项只在横屏追加，竖屏收进齿轮。
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

        // v0.259.0：齿轮底部页（六组），替代三个点菜单，是播放器唯一设置入口；
        // 音轨/视轨无现有选择能力，不做假按钮，只在轨道组里明示；
        // 时间/标题/进度已在顶栏与进度条显示，屏显组只留真实画中画开关。
        // v0.262.0：齿轮页必须落在屏幕下半部分（参考图2）——原来是 PlayerScreen
        // 内部的内联 Box，嵌入详情页时它的 fillMaxSize 只是顶部 16:9 播放盒子，
        // 齿轮页就贴在播放盒子底部、压住控制条，根本不到屏幕下半部分。
        // 改成 Dialog（usePlatformDefaultWidth=false），Dialog 自带全屏窗口，
        // 不受父盒子尺寸限制，页体才能真正贴屏幕底部、占下半屏。
        // v0.263.0：这页不能是半成品——①页体颜色原来写死深色，浅色主题下组标题
        // 是深色字、贴深底根本看不见，改跟主题 surface 走；②倍速/缩放/循环原来是
        // LazyRow 横向藏着，2.0X 等选项要横滑才看得到，改 FlowRow 一次全露；
        // ③窄屏下轨道/其它行的 Row 会溢出，改 FlowRow 自动换行；④补顶部拖拽条
        // 与关闭键的正常着色，页体不再是“只有一排芯片、标题看不见”的样子。
        if (showGearSheet) {
            Dialog(
                onDismissRequest = { showGearSheet = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(onClick = { showGearSheet = false })
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.75f)
                        .border(
                            1.dp,
                            V4.c.div,
                            RoundedCornerShape(
                                topStart = V4.RField,
                                topEnd = V4.RField,
                            ),
                        ),
                    shape = RoundedCornerShape(
                        topStart = V4.RField,
                        topEnd = V4.RField,
                    ),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(V4.Gutter)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(36.dp, 4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "设置",
                                style = V4Type.RowTitle,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { showGearSheet = false },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "关闭",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("倍速", style = V4Type.Body, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SPEEDS.forEachIndexed { i, v ->
                                V4Chip(
                                    text = "${v}X",
                                    selected = i == speedIndex,
                                    onClick = {
                                        speedIndex = i
                                        EngineHost.setPlaybackSpeed(v)
                                        PlayerSettings.setSpeed(context, v)
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("缩放", style = V4Type.Body, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RESIZE_MODES.forEachIndexed { i, mode ->
                                V4Chip(
                                    text = mode.second,
                                    selected = i == resizeIndex,
                                    onClick = {
                                        resizeIndex = i
                                        PlayerSettings.setResizeMode(context, i)
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("播放", style = V4Type.Body, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            V4Chip(
                                text = "Media3",
                                selected = engine == PlayerEngine.MEDIA3,
                                onClick = { switchToEngine(PlayerEngine.MEDIA3) },
                            )
                            V4Chip(
                                text = "libmpv",
                                selected = engine == PlayerEngine.LIBMPV,
                                onClick = { switchToEngine(PlayerEngine.LIBMPV) },
                            )
                        }
                        if (!isLive && vodId != null) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "片头 ${PlayerSettings.getSkipIntroFor(context, playSourceKey, vodId, PlaySession.episodeIndex)} 秒 / " +
                                        "片尾 ${PlayerSettings.getSkipOutroFor(context, playSourceKey, vodId, PlaySession.episodeIndex)} 秒",
                                    style = V4Type.Meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                AppButton(
                                    text = "跳过设置",
                                    onClick = { showSkipSettings = true },
                                    variant = AppButtonVariant.Ghost,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("轨道", style = V4Type.Body, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            AppButton(
                                text = "字幕设置",
                                onClick = { showSubtitleDialog = true },
                                variant = AppButtonVariant.Ghost,
                            )
                            AppButton(
                                text = "在线字幕",
                                onClick = { showOnlineSubtitle = true },
                                variant = AppButtonVariant.Ghost,
                            )
                        }
                        Text(
                            text = "当前片源暂无可切换的音轨 / 视轨",
                            style = V4Type.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("屏显", style = V4Type.Body, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        AppButton(
                            text = "进入画中画",
                            onClick = { enterPip(context) },
                            variant = AppButtonVariant.Ghost,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("其它", style = V4Type.Body, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            PLAY_MODES.forEachIndexed { i, mode ->
                                V4Chip(
                                    text = mode,
                                    selected = i == playModeIndex,
                                    onClick = { playModeIndex = i },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            AppButton(
                                text = if (sleepRemaining > 0 || sleepArmed) "定时关闭（进行中）" else "定时关闭",
                                onClick = { showSleepDialog = true },
                                variant = AppButtonVariant.Ghost,
                            )
                            AppButton(
                                text = if (danmakuEnabled) "关闭弹幕" else "打开弹幕",
                                onClick = {
                                    com.movieshell.player.danmaku.DanmakuSettings.setEnabled(
                                        context, !danmakuEnabled
                                    )
                                },
                                variant = AppButtonVariant.Ghost,
                            )
                            if (danmakuEnabled) {
                                AppButton(
                                    text = "弹幕设置",
                                    onClick = { showDanmakuSettings = true },
                                    variant = AppButtonVariant.Ghost,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
            }
        }

        // v0.259.0：简介弹窗（五字段，详情页与播放器共用 InfoPopupDialog）
        if (showInfoDialog) {
            InfoPopupDialog(
                name = infoName.ifBlank { title },
                remarks = infoRemarks,
                year = infoYear,
                desc = infoDesc,
                vodId = vodId,
                onDismiss = { showInfoDialog = false },
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
            val lineHeightIdx by ds.lineHeight.collectAsState()
            TechDialog(
                onDismissRequest = { showDanmakuSettings = false },
                title = "弹幕设置",
                confirmText = "确定",
                onConfirm = { showDanmakuSettings = false },
            ) {
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
                        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                        // 行高（v0.150.0：对标 DS danmakuLineHeight）
                        androidx.compose.material3.Text("行高", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                        androidx.compose.foundation.layout.Row {
                            listOf("紧凑", "标准", "宽松").forEachIndexed { i, label ->
                                androidx.compose.material3.FilterChip(
                                    selected = lineHeightIdx == i,
                                    onClick = { ds.setLineHeight(context, i) },
                                    label = { androidx.compose.material3.Text(label) },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        }
                    }
                }
        }

        if (showSubtitleDialog) {
            SubtitleDialog(
                initial = sessionSubtitle
                    ?: vodId?.let {
                        PlayerSettings.getSubtitleFor(
                            context, playSourceKey, it, PlaySession.episodeIndex,
                        )
                    }.orEmpty(),
                initialStyle = PlayerSettings.subtitleStyle.value,
                onDismiss = { showSubtitleDialog = false },
                onSave = { url, style ->
                    val clean = url.trim()
                    sessionSubtitle = clean.ifBlank { null }
                    if (vodId != null) PlayerSettings.setSubtitleFor(
                        context, playSourceKey, vodId, PlaySession.episodeIndex, clean,
                    )
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
                    VideoSleepTimer.startMinutes(m) {
                        EngineHost.pause()
                        AppToast.show(context, "定时关闭：已暂停播放", long = false)
                        LogBus.i("Player", "定时关闭触发：${m}分钟到，暂停")
                    }
                    AppToast.show(context, "将在 $m 分钟后停止播放", long = false)
                },
                onPickAfterEpisode = {
                    showSleepDialog = false
                    VideoSleepTimer.armAfterEpisode()
                    AppToast.show(context, "本集播完后将停止播放", long = false)
                },
                onCancelTimer = {
                    showSleepDialog = false
                    VideoSleepTimer.cancel()
                    AppToast.show(context, "定时关闭已取消", long = false)
                },
            )
        }

        // v0.16.3：按剧片头片尾设置
        if (showSkipSettings && vodId != null) {
            SkipSettingsDialog(
                vodId = vodId,
                sourceKey = playSourceKey,
                episodeIndex = PlaySession.episodeIndex,
                onDismiss = { showSkipSettings = false },
            )
        }

        } // if (!pipMode)：画中画时只留播放画面

        // 磁力转播服务未启动提示（v0.27.0：统一卡片容器，标题柔和错误色）
        if (showServiceMissingDialog) {
            PlayerDialogCard(
                title = "磁力转播服务未启动",
                titleColor = V4.c.live,
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
                    "重试 3 次仍未检测到本机磁力转播服务（127.0.0.1:17890），请先在 DsPlayer 的服务页启动 magnet-stream，启动后再点一次播放",
                    style = V4Type.Body,
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

/**
 * v0.125.0：播放器进度条——抽成组件是为了在内部关掉 Material3 的 48dp 最小
 * 交互高度（LocalMinimumInteractiveComponentSize），把行高压到 28dp，让进度线
 * 贴近视频底边（用户反馈"进度条太高卡视角"）。weight 等 RowScope 修饰由调用
 * 方通过 modifier 传进来，组件内部不需要 RowScope。绘制沿用 v0.84.0 的
 * 3dp 细线 + 主题色小圆点 thumb，拖动行为与旧内联实现一致（28dp 仍可正常拖）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSeekBar(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            modifier = modifier.height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            track = { sliderState ->
                val fraction = sliderState.valueRange.let { r ->
                    val span = r.endInclusive - r.start
                    if (span <= 0f) 0f
                    else ((sliderState.value - r.start) / span).coerceIn(0f, 1f)
                }
                // Canvas 的 onDraw 不是 @Composable 上下文，主题色必须在外层先取出来。
                val trackPrimary = MaterialTheme.colorScheme.primary
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
                        drawLine(
                            color = trackPrimary.copy(alpha = 0.9f),
                            start = Offset(0f, y),
                            end = Offset(size.width * fraction, y),
                            strokeWidth = w,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            },
            // v0.108.0 B2：thumb 小圆球跟随主题色（之前硬编码白色）
            thumb = {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            },
        )
    }
}

/** 读取当前进度并写入历史记录（v0.20.0：走 EngineHost，双引擎统一） */
private fun savePlayerProgress(
    context: Context,
    title: String,
    url: String,
    isLive: Boolean = false,
    vodId: String? = null,
    episodeIndex: Int = -1,
    sourceType: String = "",
    sourceKey: String = "",
) {
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
        // v0.75.0：继续观看用——记下剧 ID 和集数
        vodId = vodId.orEmpty(),
        episodeIndex = episodeIndex,
        // v0.189.0：继续观看跳源用——记下本会话快照的源类型和源 key
        sourceType = sourceType,
        sourceKey = sourceKey,
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
                    1.dp,
                    V4.c.div,
                    RoundedCornerShape(
                        topStart = V4.RField,
                        topEnd = V4.RField,
                    ),
                ),
            shape = RoundedCornerShape(
                topStart = V4.RField,
                topEnd = V4.RField,
            ),
            // v0.39.0：深色毛玻璃风格（mockup 风格）
            color = Color(0xF21E242F),
        ) {
            Column(Modifier.fillMaxSize().padding(V4.Gutter)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "线路 / 选集",
                        style = V4Type.RowTitle,
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
                        V4Chip(
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
                            shape = RoundedCornerShape(V4.RPoster),
                            color = if (selected) {
                                cyan.copy(alpha = 0.22f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Text(
                                text = ep.name.ifBlank { "第${i + 1}集" },
                                maxLines = 1,
                                style = V4Type.Body,
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
    // v0.156.0：字幕面板要一页完整、不许拖动才看全，传 false 关掉内容区滚动；
    // 其他面板默认仍可滚动，不受影响
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardShape = RoundedCornerShape(V4.RField)
    // AlertDialog 时代按返回先关面板：内联卡片后补回同样的返回拦截
    BackHandler(onBack = onDismiss)
    // v0.155.0：横屏全屏时屏高只有约 360dp，旧卡片（标题+400dp 内容+按钮）要 520dp+，
    // 底下取消/保存被裁到屏外、遮罩也点不到，看着像铺满全屏还关不掉。
    // 内容区按屏高算上限（竖屏仍封顶 400dp 不变），卡片总高不再超屏，按钮始终可见。
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val maxContentHeight = (screenHeightDp * 0.88f - 120.dp)
        .coerceAtLeast(140.dp).coerceAtMost(400.dp)
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
                .border(1.dp, V4.c.div, cardShape),
            shape = cardShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(V4.Gutter)) {
                // v0.155.0：标题行右上角常驻关闭键，不用再去底下找取消、也不用硬点遮罩
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = V4Type.RowTitle,
                        color = titleColor,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = if (scrollable) {
                        Modifier
                            .heightIn(max = maxContentHeight)
                            .verticalScroll(rememberScrollState())
                    } else {
                        // 不滚动：一页完整，内容本身必须压进 maxContentHeight，压不进就宁可被裁也不许偷偷变滚动
                        Modifier.heightIn(max = maxContentHeight)
                    },
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


/**
 * v0.259.0：简介弹窗（参考图三的五字段结构，详情页与播放器共用同一套）。
 * 字段顺序：片名、备注、年份、简介、影片 ID；空行整行隐藏，片名与影片 ID
 * 有值时恒显；每可见行右上角复制图标，复制的是该字段原始文本；
 * 占位文本不进复制（空行不显示，也就不会被复制）。
 */
@Composable
internal fun InfoPopupDialog(
    name: String,
    remarks: String,
    year: String,
    desc: String,
    vodId: String?,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    PlayerDialogCard(
        title = name.ifBlank { "影片信息" },
        onDismiss = onDismiss,
        buttons = {
            AppButton(
                text = "关闭",
                onClick = onDismiss,
                variant = AppButtonVariant.Ghost,
            )
        },
    ) {
        InfoFieldRow("片名", name) {
            clipboard.setText(AnnotatedString(name))
            AppToast.show(context, "已复制", long = false)
        }
        if (remarks.isNotBlank()) {
            InfoFieldRow("备注", remarks) {
                clipboard.setText(AnnotatedString(remarks))
                AppToast.show(context, "已复制", long = false)
            }
        }
        if (year.isNotBlank()) {
            InfoFieldRow("年份", year) {
                clipboard.setText(AnnotatedString(year))
                AppToast.show(context, "已复制", long = false)
            }
        }
        if (desc.isNotBlank()) {
            InfoFieldRow("简介", desc) {
                clipboard.setText(AnnotatedString(desc))
                AppToast.show(context, "已复制", long = false)
            }
        }
        if (!vodId.isNullOrBlank()) {
            InfoFieldRow("影片 ID", vodId) {
                clipboard.setText(AnnotatedString(vodId))
                AppToast.show(context, "已复制", long = false)
            }
        }
    }
}

@Composable
private fun InfoFieldRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = V4Type.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = value,
            style = V4Type.Body,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = "复制$label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
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
    // v0.27.0：统一卡片容器 + V4Chip 多选 + AppTextField
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
        scrollable = false,
    ) {
        // v0.156.0：一页完整、不许拖动——每组改成标签+控件同一行，不再标签一行、控件一行堆高；
        // 横屏（宽>高）再左右分两列，左列地址/搜索/大小/颜色，右列描边/位置/时间偏移，高度压进内容区上限。
        val isLandscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
        @Composable
        fun Label(text: String) {
            Text(
                text = text,
                style = V4Type.Body,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(64.dp),
                maxLines = 1,
            )
        }
        @Composable
        fun SizeRow() {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("大小")
                Slider(
                    value = size.toFloat(),
                    onValueChange = { size = it.toInt() },
                    valueRange = 12f..32f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${size}sp",
                    style = V4Type.Body,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(48.dp),
                )
            }
        }
        @Composable
        fun ColorRow() {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("颜色")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUB_COLORS.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    width = if (c == color) 3.dp else 1.dp,
                                    color = if (c == color) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        V4.c.div
                                    },
                                    shape = CircleShape,
                                )
                                .clickable { color = c },
                        )
                    }
                }
            }
        }
        @Composable
        fun EdgeRow() {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("描边")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        SubtitleStyle.EDGE_NONE to "无",
                        SubtitleStyle.EDGE_OUTLINE to "描边",
                        SubtitleStyle.EDGE_SHADOW to "阴影",
                    ).forEach { (v, label) ->
                        V4Chip(
                            text = label,
                            selected = edge == v,
                            onClick = { edge = v },
                            small = true,
                        )
                    }
                }
            }
        }
        @Composable
        fun PositionRow() {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("位置")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    V4Chip(
                        text = "底部",
                        selected = position == SubtitleStyle.POS_BOTTOM,
                        onClick = { position = SubtitleStyle.POS_BOTTOM },
                        small = true,
                    )
                    V4Chip(
                        text = "顶部",
                        selected = position == SubtitleStyle.POS_TOP,
                        onClick = { position = SubtitleStyle.POS_TOP },
                        small = true,
                    )
                }
            }
        }
        @Composable
        fun TimeRow() {
            val offsetLabel = when {
                offsetSec > 0 -> "+${offsetSec}s"
                offsetSec < 0 -> "${offsetSec}s"
                else -> "0s"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("时间偏移")
                Slider(
                    value = offsetSec.toFloat(),
                    onValueChange = { offsetSec = it.toInt() },
                    valueRange = -10f..10f,
                    steps = 19,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = offsetLabel,
                    style = V4Type.Body,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(44.dp),
                    maxLines = 1,
                )
            }
        }
        Text(
            text = "支持 .srt / .vtt / .ass 地址，保存后立即生效",
            style = V4Type.Meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        if (isLandscape) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    AppTextField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = "字幕地址",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppButton(
                        text = "在线搜索字幕",
                        onClick = onOnlineSearch,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    SizeRow()
                    Spacer(Modifier.height(6.dp))
                    ColorRow()
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    EdgeRow()
                    Spacer(Modifier.height(6.dp))
                    PositionRow()
                    Spacer(Modifier.height(6.dp))
                    TimeRow()
                }
            }
        } else {
            AppTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = "字幕地址",
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                text = "在线搜索字幕",
                onClick = onOnlineSearch,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Spacer(Modifier.height(6.dp))
            SizeRow()
            Spacer(Modifier.height(6.dp))
            ColorRow()
            Spacer(Modifier.height(6.dp))
            EdgeRow()
            Spacer(Modifier.height(6.dp))
            PositionRow()
            Spacer(Modifier.height(6.dp))
            TimeRow()
        }
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
        // v0.268.0（T14 同 lane）：视频定时与音乐定时共用同一
        // SleepTimerLane 互斥语义，选新的会取消旧的，这里同样
        // 明说，不改语义。
        Text(
            "定时和播完本集只能二选一，选新的会取消旧的",
            style = V4Type.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(15, 30, 60, 90).forEach { m ->
            TextButton(
                onClick = { onPickMinutes(m) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "$m 分钟后停止",
                    style = V4Type.Body,
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
                style = V4Type.Body,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        TextButton(
            onClick = onCancelTimer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "关闭定时",
                style = V4Type.Body,
                color = V4.c.live,
            )
        }
    }
}

/**
 * v0.16.3：按剧片头片尾设置对话框。
 * 每部剧单独记开头/结束跳过秒数。
 */
@Composable
private fun SkipSettingsDialog(
    vodId: String,
    sourceKey: String?,
    episodeIndex: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var intro by remember {
        mutableStateOf(
            PlayerSettings.getSkipIntroFor(context, sourceKey, vodId, episodeIndex).toString(),
        )
    }
    var outro by remember {
        mutableStateOf(
            PlayerSettings.getSkipOutroFor(context, sourceKey, vodId, episodeIndex).toString(),
        )
    }

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
                    PlayerSettings.setSkipIntroFor(
                        context, sourceKey, vodId, episodeIndex, introSec,
                    )
                    PlayerSettings.setSkipOutroFor(
                        context, sourceKey, vodId, episodeIndex, outroSec,
                    )
                    LogBus.i("UI", "设置本剧跳过: 片头${introSec}s 片尾${outroSec}s")
                    onDismiss()
                },
            )
        },
    ) {
        Text(
            text = "每部剧单独设置，互不影响",
            style = V4Type.Meta,
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
