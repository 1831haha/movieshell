package com.movieshell.player

/**
 * Q1 播放器拆分（由 q1q3-muse 移植到 ui-v5，纯搬移、行为不变）：
 * 原来 PlayerScreen.kt 底部的全部独立组件与工具函数（小圆控制钮、
 * 进度条、线路抽屉、简介弹窗、字幕/定时/跳过对话框、亮度音量调节、
 * 进度保存、画中画入口）搬到这里；PlayerScreen.kt 只剩 PlayerScreen()
 * 本体与文件级常量。被 PlayerScreen 本体调用的函数由 private 改为
 * internal（同包可见，调用点不动）；仅新文件内部用的 InfoFieldRow、
 * SUB_COLORS、BRIGHTNESS_STEP 仍保持 private。组件本体取 V5 现版。
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.coerceIn
import com.movieshell.ui.theme.XiaYingDarkScheme
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.media.AudioManager
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.movieshell.data.model.PlayLine
import com.movieshell.data.repo.DownloadSettings
import com.movieshell.data.repo.DownloadStore
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PlayerSettings
import com.movieshell.data.repo.SubtitleStyle
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.AppToast
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.movieshell.ui.theme.V5
import com.movieshell.ui.components.V5Chip

/** 手势：亮度每步调节量（0~1） */
private const val BRIGHTNESS_STEP = 0.05f

/**
 * v0.204.0：小圆控制钮——对标参考播放器的小巧圆润风格：40.dp 圆底 + 20.dp 白图标。
 * 直播与点播共用 PlayerScreen，改这一处两边全变。
 */
@Composable
internal fun RoundControlButton(
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
        // v0.292.0 V5（03-player）：媒体控件圆钮，图标 22dp。
        // v0.294.2：去掉每个图标外一圈黑 32% 圆底（用户：“把播放界面的图标一圈的黑边去掉”），
        // 只留纯白图标；顶/底栏的渐变压暗是单独一层，可读性不受影响。
        modifier = modifier.size(size),
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class) // v0.84.0：Slider 自定义 track 用到实验性 SliderState API；v0.255.0：标题跑马灯 basicMarquee

/** 左半屏上下滑动：调节屏幕亮度。返回调节后的亮度百分比，失败返回 -1 */
internal fun adjustBrightness(context: Context, up: Boolean): Int {
    val activity = context as? Activity ?: return -1
    val lp = activity.window.attributes
    val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
    val next = (cur + if (up) BRIGHTNESS_STEP else -BRIGHTNESS_STEP).coerceIn(0.01f, 1f)
    lp.screenBrightness = next
    activity.window.attributes = lp
    return (next * 100).toInt()
}

/** 右半屏上下滑动：调节媒体音量。返回调节后的音量百分比，失败返回 -1 */
internal fun adjustVolume(context: Context, up: Boolean): Int {
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
internal fun fmtTime(ms: Long): String {
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
internal fun PlayerSeekBar(
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
                // v0.294.13：轨道容器与 Slider 同高，与 thumb 共用垂直中线
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp),
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
            // v0.294.13：thumb 与轨道同高盒子垂直居中
            thumb = {
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .width(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            },
        )
    }
}

/** 读取当前进度并写入历史记录（v0.20.0：走 EngineHost，双引擎统一） */
internal fun savePlayerProgress(
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
internal fun maybeAutoDeleteWatched(context: Context, url: String, isLive: Boolean) {
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
internal fun enterPip(context: Context) {
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
 * v0.294.3：播放器面板统一容器（线路/选集、齿轮设置共用）。
 * - 横屏（宽>高）：右侧抽屉——约 40% 屏宽（300–440dp）、满高、从右滑入，左侧圆角；
 *   视频左半边仍露出来，不再是铺满整宽的底部大板盖住画面。
 * - 竖屏：底部面板，高度 [portraitHeightFraction]，与原来一致。
 * - 点遮罩关闭；配色恒走深色（播放器是黑底，浅色主题下原来写死深底 + 跟主题的深色字会看不清）。
 */
@Composable
internal fun PlayerPanel(
    onDismiss: () -> Unit,
    portraitHeightFraction: Float = 0.6f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cfg = LocalConfiguration.current
    val landscape = cfg.screenWidthDp > cfg.screenHeightDp
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < 0.5f
    val scheme = if (dark) base
    else XiaYingDarkScheme.copy(primary = base.inversePrimary, onPrimary = base.onPrimaryContainer)
    val enter = remember { MutableTransitionState(false).apply { targetState = true } }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (landscape) 0.35f else 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
            )
            val shape = if (landscape) {
                RoundedCornerShape(topStart = V5.CornerLgI, bottomStart = V5.CornerLgI)
            } else {
                RoundedCornerShape(topStart = V5.CornerLgI, topEnd = V5.CornerLgI)
            }
            AnimatedVisibility(
                visibleState = enter,
                enter = if (landscape) slideInHorizontally { it } + fadeIn()
                else slideInVertically { it } + fadeIn(),
                modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter),
            ) {
                Surface(
                    modifier = if (landscape) {
                        Modifier
                            .fillMaxHeight()
                            .width((cfg.screenWidthDp * 0.4f).dp.coerceIn(300.dp, 440.dp))
                    } else {
                        Modifier.fillMaxWidth().fillMaxHeight(portraitHeightFraction)
                    }.clickable(
                        // 吞掉面板内空白处的点击，免得穿透到遮罩/播放器
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                    shape = shape,
                    color = scheme.surfaceContainer.copy(alpha = 0.96f),
                    contentColor = scheme.onSurface,
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .then(if (landscape) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical)) else Modifier)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

/** v0.294.3：面板标题行（标题 + 右侧关闭）。 */
@Composable
internal fun PlayerPanelHeader(title: String, onDismiss: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing()
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "关闭",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * v0.19.0：播放器内线路+选集切换抽屉。上方线路横滑 chips，下方该线路的选集；当前播放高亮。
 * v0.294.3：横屏改右侧抽屉（见 [PlayerPanel]）；选集由整宽高行（一屏只见两三集）改为多列紧凑
 * 方格（40dp 高，集名短时自适应约 64dp 一格，集名长时两列/宽格省略号）；打开即滚到当前集。
 */
@Composable
internal fun LineSheet(
    lines: List<PlayLine>,
    lineIndex: Int,
    episodeIndex: Int,
    onDismiss: () -> Unit,
    onPick: (lineIdx: Int, epIdx: Int) -> Unit,
) {
    var tab by remember { mutableIntStateOf(lineIndex.coerceIn(lines.indices)) }
    val tabEpisodes = lines.getOrNull(tab)?.episodes.orEmpty()
    val labels = remember(tabEpisodes) {
        tabEpisodes.mapIndexed { i, ep -> ep.name.ifBlank { "第${i + 1}集" } }
    }
    // 集名都短（如 01、第12集）走小方格；有长名（如「第1集 重逢」）走宽格省略号
    val longNames = remember(labels) { labels.any { it.length > 5 } }
    val gridState = rememberLazyGridState()
    LaunchedEffect(tab) {
        if (tab == lineIndex && episodeIndex in tabEpisodes.indices) {
            gridState.scrollToItem(episodeIndex)
        } else {
            gridState.scrollToItem(0)
        }
    }
    PlayerPanel(onDismiss = onDismiss) {
        PlayerPanelHeader(
            title = "线路 / 选集",
            onDismiss = onDismiss,
            trailing = {
                Text(
                    text = "共 ${tabEpisodes.size} 集",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
            },
        )
        if (lines.size > 1) {
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(lines) { i, line ->
                    V5Chip(
                        text = line.name,
                        selected = i == tab,
                        onClick = { tab = i },
                        small = true,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(if (longNames) 132.dp else 64.dp),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            itemsIndexed(labels) { i, label ->
                val selected = tab == lineIndex && i == episodeIndex
                val cs = MaterialTheme.colorScheme
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(V5.CornerSm))
                        .background(if (selected) cs.primaryContainer else cs.surfaceContainerHighest)
                        .then(
                            if (selected) Modifier.border(1.dp, cs.primary, RoundedCornerShape(V5.CornerSm))
                            else Modifier
                        )
                        .clickable { onPick(tab, i) }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) cs.onPrimaryContainer else cs.onSurface,
                    )
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
    // v0.294.3：卡片最大宽度——原来 88% 屏宽封顶 480dp，横屏下两个输入框也撑成一大块；
    // 默认收到 420dp，跳过设置等小面板传 360dp，字幕两列面板传 560dp。
    maxWidth: Dp = 420.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardShape = RoundedCornerShape(V5.CornerLgI)
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
                .fillMaxWidth(0.92f)
                .widthIn(max = maxWidth)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape),
            shape = cardShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                // v0.155.0：标题行右上角常驻关闭键，不用再去底下找取消、也不用硬点遮罩
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
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
                Spacer(Modifier.height(4.dp))
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
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
internal fun SubtitleDialog(
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
    // v0.27.0：统一卡片容器 + V5Chip 多选 + AppTextField
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
        maxWidth = 560.dp,
    ) {
        // v0.156.0：一页完整、不许拖动——每组改成标签+控件同一行，不再标签一行、控件一行堆高；
        // 横屏（宽>高）再左右分两列，左列地址/搜索/大小/颜色，右列描边/位置/时间偏移，高度压进内容区上限。
        val isLandscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
        @Composable
        fun Label(text: String) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
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
                    style = MaterialTheme.typography.bodyMedium,
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
                                        MaterialTheme.colorScheme.outlineVariant
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
                        V5Chip(
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
                    V5Chip(
                        text = "底部",
                        selected = position == SubtitleStyle.POS_BOTTOM,
                        onClick = { position = SubtitleStyle.POS_BOTTOM },
                        small = true,
                    )
                    V5Chip(
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
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(44.dp),
                    maxLines = 1,
                )
            }
        }
        Text(
            text = "支持 .srt / .vtt / .ass 地址，保存后立即生效",
            style = MaterialTheme.typography.bodySmall,
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SleepTimerDialog(
    onDismiss: () -> Unit,
    onPickMinutes: (Int) -> Unit,
    onPickAfterEpisode: () -> Unit,
    onCancelTimer: () -> Unit,
) {
    // v0.27.0：统一卡片容器
    PlayerDialogCard(
        title = "定时关闭",
        onDismiss = onDismiss,
        maxWidth = 380.dp,
        buttons = {
            AppButton(text = "取消", onClick = onDismiss, variant = AppButtonVariant.Ghost)
        },
    ) {
        // v0.268.0（T14 同 lane）：视频定时与音乐定时共用同一
        // SleepTimerLane 互斥语义，选新的会取消旧的，这里同样
        // 明说，不改语义。
        Text(
            "定时和播完本集只能二选一，选新的会取消旧的",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // v0.294.3：原来 6 个整宽文字按钮竖着堆满一屏，改成一组紧凑 chip。
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(15, 30, 60, 90).forEach { m ->
                V5Chip(text = "$m 分钟", selected = false, onClick = { onPickMinutes(m) })
            }
            V5Chip(text = "播完本集", selected = false, onClick = onPickAfterEpisode)
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onCancelTimer) {
            Text(
                "关闭定时",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * v0.16.3：按剧片头片尾设置对话框。
 * 每部剧单独记开头/结束跳过秒数。
 */
@Composable
internal fun SkipSettingsDialog(
    vodId: String,
    sourceKey: String?,
    episodeIndex: Int,
    onDismiss: () -> Unit,
    // v0.294.3：“用当前进度”快捷填——片头取当前播放位置，片尾取剩余时长；拿不到（<=0）就不显示按钮
    currentPositionMs: () -> Long = { 0L },
    durationMs: () -> Long = { 0L },
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
    val pos = remember { currentPositionMs().coerceAtLeast(0L) }
    val dur = remember { durationMs().coerceAtLeast(0L) }

    // v0.27.0：统一卡片容器；v0.294.3：收窄到 360dp、两个框带「片头」「片尾」标签与“秒”单位并排一行
    PlayerDialogCard(
        title = "本剧跳过设置",
        onDismiss = onDismiss,
        maxWidth = 360.dp,
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
            text = "每部剧单独设置，互不影响（0 为不跳过，最多 600 秒）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SkipField(
                label = "片头",
                value = intro,
                onValueChange = { intro = it.filter { c -> c.isDigit() }.take(3) },
                quickLabel = if (pos > 0) "用当前进度" else null,
                onQuick = { intro = (pos / 1000).coerceIn(0, 600).toString() },
                modifier = Modifier.weight(1f),
            )
            SkipField(
                label = "片尾",
                value = outro,
                onValueChange = { outro = it.filter { c -> c.isDigit() }.take(3) },
                quickLabel = if (dur > 0 && pos in 1 until dur) "用剩余时长" else null,
                onQuick = { outro = ((dur - pos) / 1000).coerceIn(0, 600).toString() },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SkipField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    quickLabel: String?,
    onQuick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        AppTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "0",
            suffix = "秒",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        if (quickLabel != null) {
            TextButton(
                onClick = onQuick,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Text(quickLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
