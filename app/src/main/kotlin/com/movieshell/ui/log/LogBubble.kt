package com.movieshell.ui.log

import com.movieshell.ui.components.sheetListScrollGuard
import android.content.ClipData
import com.movieshell.ui.components.AppToast
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.AtomicFile
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.LogStore
import com.movieshell.data.repo.StoragePaths
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.V5Chip
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import kotlin.math.roundToInt

private const val PREFS = "movieshell_prefs"
private const val KEY_BUBBLE_X = "log_bubble_x"
private const val KEY_BUBBLE_Y = "log_bubble_y"

private fun loadBubbleOffset(context: Context): Offset? {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (!p.contains(KEY_BUBBLE_X)) return null
    return Offset(p.getFloat(KEY_BUBBLE_X, 0f), p.getFloat(KEY_BUBBLE_Y, 0f))
}

private fun saveBubbleOffset(context: Context, offset: Offset) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putFloat(KEY_BUBBLE_X, offset.x)
        .putFloat(KEY_BUBBLE_Y, offset.y)
        .apply()
}

/** v0.294.8：悬浮按钮尺寸与贴边留白 */
private val BUBBLE_SIZE = 44.dp
private val EDGE_MARGIN = 8.dp

/**
 * v0.273.0（T31）：悬浮球位置钳制——原来拖动无边界，拖出屏就再也点不到。
 * v0.294.8：整颗按钮都留在屏内（贴边吸附后不再半截出屏）。
 */
private fun coerceBubbleOffset(offset: Offset, maxWidthPx: Float, maxHeightPx: Float, bubblePx: Float, marginPx: Float = 0f): Offset {
    return Offset(
        x = offset.x.coerceIn(marginPx, (maxWidthPx - bubblePx - marginPx).coerceAtLeast(marginPx)),
        y = offset.y.coerceIn(marginPx, (maxHeightPx - bubblePx - marginPx).coerceAtLeast(marginPx)),
    )
}

/**
 * v0.294.8：日志悬浮按钮图标——圆角窗口里一个 `>` 提示符 + 光标横线 + 两行日志短线，
 * 线条风格（圆头描边），不再用 Material Terminal 实心图标。
 */
val LogConsoleGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "LogConsoleGlyph",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        val stroke = SolidColor(Color.Black)
        // 窗口外框
        path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(6.5f, 4f); lineTo(17.5f, 4f)
            arcTo(2.5f, 2.5f, 0f, false, true, 20f, 6.5f)
            lineTo(20f, 17.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 17.5f, 20f)
            lineTo(6.5f, 20f)
            arcTo(2.5f, 2.5f, 0f, false, true, 4f, 17.5f)
            lineTo(4f, 6.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 6.5f, 4f)
            close()
        }
        // > 提示符
        path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7.5f, 9f); lineTo(10.5f, 12f); lineTo(7.5f, 15f)
        }
        // 光标横线
        path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(12.5f, 15f); lineTo(16.5f, 15f)
        }
        // 日志短线（细、淡）
        path(stroke = stroke, strokeAlpha = 0.55f, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round) {
            moveTo(13f, 9f); lineTo(16.5f, 9f)
        }
    }.build()
}

/**
 * v0.7.2：运行日志悬浮窗（应用内悬浮，不申请 SYSTEM_ALERT_WINDOW）。
 *
 * - 开关由设置页"运行日志悬浮窗"控制（LogStore，默认关）；
 * - v0.294.8 重新设计：44dp 圆角方块色调按钮（primaryContainer 底 + 柔和投影，无黑圆底、无模糊光晕），
 *   自绘线条控制台图标；空闲 5 秒淡到 0.45，按下/拖动/有新日志恢复不透明；
 *   有未看的错误日志时右上角出 error 色计数角标（打开日志即清零）；
 *   松手自动吸附到最近的左右边缘；默认位置在屏幕右侧中部偏上，不压底部播放控制条。
 * - 点开：BottomSheet = 日志列表（倒序、可按级别过滤）+ 导出日志 / 复制日志 / 关闭悬浮窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogBubbleOverlay() {
    val context = LocalContext.current
    val enabled by LogStore.enabled.collectAsState()
    if (!enabled) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val sizePx = with(density) { BUBBLE_SIZE.toPx() }
        val marginPx = with(density) { EDGE_MARGIN.toPx() }
        // v0.294.8：默认右侧、约 38% 高度——原来离底 190dp，正压在音乐/视频播放页进度条右端
        val defaultOffset = remember(maxWidth, maxHeight) {
            Offset(x = wPx - sizePx - marginPx, y = hPx * 0.38f)
        }
        // v0.273.0（T31）：读回旧位置先按当前屏钳制，再当初始位置。
        val initialOffset = remember(maxWidth, maxHeight) {
            val saved = loadBubbleOffset(context)
            if (saved == null) defaultOffset else coerceBubbleOffset(saved, wPx, hPx, sizePx, marginPx)
        }
        var offset by remember { mutableStateOf(initialOffset) }
        // v0.273.0（T31）：转屏/分屏后屏尺寸变了，已存位置可能越界，尺寸一变就重钳制。
        LaunchedEffect(maxWidth, maxHeight) {
            offset = coerceBubbleOffset(offset, wPx, hPx, sizePx, marginPx)
        }
        var showSheet by remember { mutableStateOf(false) }
        var dragging by remember { mutableStateOf(false) }

        // v0.205.0：静默虚化——5 秒无新日志就淡；v0.247.0（F29）条数 + 最新一条双 key
        val logEntries by LogBus.entries.collectAsState()
        var idleFaded by remember { mutableStateOf(false) }
        LaunchedEffect(logEntries.size, logEntries.lastOrNull()) {
            idleFaded = false
            delay(5000)
            idleFaded = true
        }
        // v0.294.8：未看的错误计数（时间戳晚于上次打开日志的 E 级条目）
        var seenAt by remember { mutableStateOf(System.currentTimeMillis()) }
        val unseenErrors = remember(logEntries, seenAt) {
            logEntries.count { it.level == 'E' && it.time > seenAt }
        }
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val active = pressed || dragging || !idleFaded || unseenErrors > 0
        val bubbleAlpha by animateFloatAsState(if (active) 1f else 0.45f, label = "logBubbleAlpha")
        val bubbleScale by animateFloatAsState(if (pressed || dragging) 1.08f else 1f, label = "logBubbleScale")

        Box(
            modifier = Modifier
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .size(BUBBLE_SIZE + 8.dp) // 角标外扩留位
                .alpha(bubbleAlpha)
                .pointerInput(maxWidth, maxHeight) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragCancel = { dragging = false },
                        onDragEnd = {
                            dragging = false
                            // v0.294.8：松手吸附到最近的左右边缘
                            val targetX = if (offset.x + sizePx / 2f < wPx / 2f) marginPx
                            else wPx - sizePx - marginPx
                            scope.launch {
                                animate(offset.x, targetX, animationSpec = spring(dampingRatio = 0.75f, stiffness = 500f)) { v, _ ->
                                    offset = offset.copy(x = v)
                                }
                                saveBubbleOffset(context, offset)
                            }
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        offset = coerceBubbleOffset(offset + dragAmount, wPx, hPx, sizePx, marginPx)
                    }
                },
        ) {
            Surface(
                onClick = {
                    seenAt = System.currentTimeMillis()
                    showSheet = true
                },
                interactionSource = interaction,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                tonalElevation = 2.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(BUBBLE_SIZE)
                    .graphicsLayer { scaleX = bubbleScale; scaleY = bubbleScale },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = LogConsoleGlyph,
                        contentDescription = "运行日志",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (unseenErrors > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .heightIn(min = 18.dp)
                        .widthIn(min = 18.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.error)
                        .border(1.5.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (unseenErrors > 99) "99+" else unseenErrors.toString(),
                        color = MaterialTheme.colorScheme.onError,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }

        if (showSheet) {
            ModalBottomSheet(onDismissRequest = {
                showSheet = false
                seenAt = System.currentTimeMillis()
            }) {
                LogSheetContent(onCloseSheet = { showSheet = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSheetContent(onCloseSheet: () -> Unit) {
    val context = LocalContext.current
    val entries by LogBus.entries.collectAsState()
    var levelFilter by remember { mutableStateOf('A') }

    val shown = remember(entries, levelFilter) {
        val rev = entries.asReversed()
        if (levelFilter == 'A') rev else rev.filter { it.level == levelFilter }
    }
    val listState = rememberLazyListState()
    // 倒序展示：最新在最上面，新日志进来滚到顶
    LaunchedEffect(shown.size) {
        if (shown.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
    ) {
        Text(
            text = "运行日志（${entries.size} 条）",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LevelChip("全部", 'A', levelFilter) { levelFilter = it }
            LevelChip("D", 'D', levelFilter) { levelFilter = it }
            LevelChip("I", 'I', levelFilter) { levelFilter = it }
            LevelChip("W", 'W', levelFilter) { levelFilter = it }
            LevelChip("E", 'E', levelFilter) { levelFilter = it }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                // v0.294.2：列表不在顶时下滑只滚列表，不拖动/收起弹窗
                .sheetListScrollGuard { listState.canScrollBackward },
        ) {
            items(shown.size) { i -> LogLine(shown[i]) }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppButton(
                text = "导出日志",
                onClick = { exportLog(context) },
                modifier = Modifier.weight(1f),
            )
            AppButton(
                text = "复制日志",
                onClick = { copyLog(context) },
                variant = AppButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            AppButton(
                text = "关闭悬浮窗",
                onClick = {
                    LogStore.setEnabled(context, false)
                    onCloseSheet()
                },
                variant = AppButtonVariant.Ghost,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LevelChip(label: String, level: Char, selected: Char, onSelect: (Char) -> Unit) {
    V5Chip(
        text = label,
        selected = selected == level,
        onClick = { onSelect(level) },
    )
}

@Composable
private fun LogLine(e: LogBus.Entry) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
        val color = when (e.level) {
        'E' -> MaterialTheme.colorScheme.error
        'W' -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = "${fmt.format(Date(e.time))} ${e.level}/${e.tag}: ${e.msg}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Default,
        color = color,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

/** 导出日志到 xiaying/log/movieshell_yyyyMMdd_HHmmss.txt */
private fun exportLog(context: Context) {
    try {
        val dir = StoragePaths.logDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "movieshell_$ts.txt")
        // v0.188.0：导出走 AtomicFile（L25）；提示只写相对位置（M31：不贴绝对路径）。
        if (!AtomicFile.writeText(file, LogBus.dumpText())) throw IllegalStateException("日志写盘失败")
        LogBus.i("Log", "日志已导出：${file.name}")
        AppToast.show(context, "日志已导出到 log 目录：${file.name}", long = true)
    } catch (e: Exception) {
        LogBus.e("Log", "日志导出失败：${e.message}")
        AppToast.show(context, "导出失败：${e.message}", long = true)
    }
}

/** 整份日志进剪贴板 */
private fun copyLog(context: Context) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("movieshell_log", LogBus.dumpText()))
        AppToast.show(context, "日志已复制到剪贴板", long = false)
    } catch (e: Exception) {
        AppToast.show(context, "复制失败：${e.message}", long = true)
    }
}
