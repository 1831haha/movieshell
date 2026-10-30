package com.movieshell.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.data.model.ParseConfig
import com.movieshell.data.source.ParseProgressBus
import kotlinx.coroutines.delay

/**
 * v0.294.7（L2 / M7）：全局解析进度条 + 解析失败对话框。
 * - 解析超过 0.8 秒才露出（秒出的不闪一下）：顶部紧凑胶囊「正在尝试 W3 (3/16)…」，可「选线路」「取消」；
 * - 全部失败：解析失败对话框，提供「手动嗅探」（打开网页嗅探页，用户自己点播放抓地址）。
 */
@Composable
fun ParseProgressOverlay(onManualSniff: (String) -> Unit) {
    val state by ParseProgressBus.state.collectAsState()
    val failure by ParseProgressBus.failure.collectAsState()
    val manualReq by ParseProgressBus.manualSniff.collectAsState()
    LaunchedEffect(manualReq) {
        val u = manualReq ?: return@LaunchedEffect
        ParseProgressBus.consumeManualSniff()
        onManualSniff(u)
    }
    var visibleId by remember { mutableStateOf(-1L) }
    var showPicker by remember { mutableStateOf(false) }

    val s = state
    LaunchedEffect(s?.id) {
        if (s == null) { visibleId = -1L; showPicker = false; return@LaunchedEffect }
        val wait = 800L - (System.currentTimeMillis() - s.startedAt)
        if (wait > 0) delay(wait)
        visibleId = s.id
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = s != null && visibleId == s.id,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val cur = s ?: return@AnimatedVisibility
            Surface(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                    .widthIn(max = 420.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (cur.total > 1) "正在尝试 ${cur.current} (${cur.index.coerceAtLeast(1)}/${cur.total})…"
                        else "正在${cur.current}…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (cur.lines.size > 1 && !cur.picked) {
                        TextButton(onClick = { showPicker = true }) { Text("选线路") }
                    }
                    TextButton(onClick = { ParseProgressBus.cancel() }) { Text("取消") }
                }
            }
        }
    }

    if (showPicker && s != null) {
        LinePickerDialog(
            lines = s.lines,
            onDismiss = { showPicker = false },
            onPick = { showPicker = false; ParseProgressBus.pick(it) },
        )
    }

    failure?.let { f ->
        TechDialog(
            onDismissRequest = { ParseProgressBus.clearFailure() },
            title = "解析失败",
            subtitle = f.reason,
            confirmText = "手动嗅探",
            onConfirm = {
                ParseProgressBus.clearFailure()
                onManualSniff(f.pageUrl)
            },
            dismissText = "关闭",
        ) {
            Text(
                "可以打开原网页手动嗅探：在页面里点播放，抓到真实地址后直接播。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                f.pageUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LinePickerDialog(
    lines: List<ParseConfig>,
    onDismiss: () -> Unit,
    onPick: (ParseConfig) -> Unit,
) {
    TechDialog(
        onDismissRequest = onDismiss,
        title = "指定解析线路",
        subtitle = "只用选中的线路解析本集",
        dismissText = "取消",
    ) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            itemsIndexed(lines) { i, p ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(p) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "${i + 1}. ${p.name.ifBlank { "线路" }}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (p.type) { 0 -> "嗅探"; 99 -> "yt-dlp"; else -> "JSON" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
