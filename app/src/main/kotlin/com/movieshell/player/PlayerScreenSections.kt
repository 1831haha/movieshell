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
        modifier = modifier
            .size(size)
            // v0.292.0 V5（03-player）：媒体控件圆钮——黑 32% 底，图标 22dp
            .background(Color.Black.copy(alpha = 0.32f), CircleShape),
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
 * v0.19.0：播放器内线路+选集切换抽屉。
 * 上方线路横滑 chips，下方该线路的选集列表；当前播放高亮。
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
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(
                        topStart = V5.CornerMd,
                        topEnd = V5.CornerMd,
                    ),
                ),
            shape = RoundedCornerShape(
                topStart = V5.CornerMd,
                topEnd = V5.CornerMd,
            ),
            // v0.39.0：深色毛玻璃风格（mockup 风格）
            color = Color(0xF21E242F),
        ) {
            Column(Modifier.fillMaxSize().padding(V5.Gutter)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "线路 / 选集",
                        style = MaterialTheme.typography.bodyLarge,
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
                        V5Chip(
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
                            shape = RoundedCornerShape(V5.CornerMd),
                            color = if (selected) {
                                cyan.copy(alpha = 0.22f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Text(
                                text = ep.name.ifBlank { "第${i + 1}集" },
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium,
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
    val cardShape = RoundedCornerShape(V5.CornerMd)
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
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape),
            shape = cardShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(V5.Gutter)) {
                // v0.155.0：标题行右上角常驻关闭键，不用再去底下找取消、也不用硬点遮罩
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
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
        listOf(15, 30, 60, 90).forEach { m ->
            TextButton(
                onClick = { onPickMinutes(m) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "$m 分钟后停止",
                    style = MaterialTheme.typography.bodyMedium,
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        TextButton(
            onClick = onCancelTimer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "关闭定时",
                style = MaterialTheme.typography.bodyMedium,
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
            style = MaterialTheme.typography.bodySmall,
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
