package com.movieshell.ui.log

import android.content.ClipData
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Code
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
import androidx.compose.ui.draw.blur
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
import com.movieshell.ui.components.AppChip
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
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

/**
 * v0.7.2：运行日志悬浮窗（应用内悬浮，不申请 SYSTEM_ALERT_WINDOW）。
 *
 * - 开关由设置页"运行日志悬浮窗"控制（LogStore，默认关）；
 * - 小泡：48dp 圆形，虚化风格（半透明底 + blur），可拖到任意位置（位置记住）；
 * v0.205.0：图标改 Code（</>，更像日志控制台）；静默 5 秒无新日志后整体虚化到 0.35，有新日志恢复。
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
        val defaultOffset = remember(maxWidth, maxHeight) {
            with(density) {
                Offset(
                    x = maxWidth.toPx() - 64.dp.toPx(),
                    y = maxHeight.toPx() - 190.dp.toPx(),
                )
            }
        }
        var offset by remember { mutableStateOf(loadBubbleOffset(context) ?: defaultOffset) }
        var showSheet by remember { mutableStateOf(false) }

        // v0.205.0：静默虚化——5 秒无新日志就淡到 0.35，有新日志恢复
        val logEntries by LogBus.entries.collectAsState()
        var idleFaded by remember { mutableStateOf(false) }
        LaunchedEffect(logEntries.size) {
            idleFaded = false
            delay(5000)
            idleFaded = true
        }
        val bubbleAlpha by animateFloatAsState(if (idleFaded) 0.35f else 1f, label = "logBubbleAlpha")

        Box(
            modifier = Modifier
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .size(48.dp)
                .alpha(bubbleAlpha)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { saveBubbleOffset(context, offset) },
                    ) { change, dragAmount ->
                        change.consume()
                        offset += dragAmount
                    }
                }
                .clip(CircleShape)
                .border(SoftDimens.BorderWidth, LocalSoftExtra.current.border, CircleShape)
                .clickable { showSheet = true },
        ) {
            // 虚化底层：半透明 + 模糊（v0.27.0：primaryContainer 配色 + 描边）
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(14.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)),
            )
            Icon(
                imageVector = Icons.Filled.Code,
                contentDescription = "运行日志",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(24.dp),
            )
        }

        if (showSheet) {
            ModalBottomSheet(onDismissRequest = { showSheet = false }) {
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
            style = SoftType.Title,
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
                .heightIn(max = 380.dp),
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
    AppChip(
        text = label,
        selected = selected == level,
        onClick = { onSelect(level) },
    )
}

@Composable
private fun LogLine(e: LogBus.Entry) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val extra = LocalSoftExtra.current
    val color = when (e.level) {
        'E' -> extra.errorSoft
        'W' -> extra.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = "${fmt.format(Date(e.time))} ${e.level}/${e.tag}: ${e.msg}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
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
        Toast.makeText(context, "日志已导出到 log 目录：${file.name}", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        LogBus.e("Log", "日志导出失败：${e.message}")
        Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
    }
}

/** 整份日志进剪贴板 */
private fun copyLog(context: Context) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("movieshell_log", LogBus.dumpText()))
        Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "复制失败：${e.message}", Toast.LENGTH_LONG).show()
    }
}
