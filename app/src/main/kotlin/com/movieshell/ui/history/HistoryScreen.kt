package com.movieshell.ui.history

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.player.HistoryEntry
import com.movieshell.player.HistoryStore
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.CapsuleTabBar
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun dayLabel(ts: Long): String {
    val cal = Calendar.getInstance()
    val today = dayFmt.format(Date())
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = dayFmt.format(cal.time)
    val d = dayFmt.format(Date(ts))
    return when (d) {
        today -> "今天"
        yesterday -> "昨天"
        else -> d
    }
}

private fun progressText(e: HistoryEntry): String? {
    if (e.kind == "live") return "直播"
    if (e.durationMs <= 0 || e.positionMs <= 0) return null
    val pct = (e.positionMs * 100 / e.durationMs).toInt().coerceIn(0, 100)
    return "已看 $pct%"
}

/**
 * 播放历史独立页（v0.24.0；v0.27.0 Soft Tech 换皮）。
 *
 * 复用 HistoryStore（断点续播同一份数据）：按天分组倒序、搜索标题、
 * 长按删单条、顶部一键清空、点击卡片续播（live 走直播播放，vod 直进播放器自动续播）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onPlay: (HistoryEntry) -> Unit,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<HistoryEntry?>(null) }
    var showClearAll by remember { mutableStateOf(false) }
    // v0.86.0：子 tab（0=全部 1=点播 2=直播），切 tab 不丢
    var tab by rememberSaveable { mutableIntStateOf(0) }

    fun reload() {
        entries = HistoryStore.getHistory(context)
    }

    LaunchedEffect(Unit) { reload() }

    // v0.86.0：先按 tab 过滤 kind，再按搜索词过滤
    val kindFiltered = remember(entries, tab) {
        when (tab) {
            1 -> entries.filter { it.kind != "live" }
            2 -> entries.filter { it.kind == "live" }
            else -> entries
        }
    }
    val filtered = remember(kindFiltered, query) {
        if (query.isBlank()) kindFiltered
        else kindFiltered.filter { it.title.contains(query, ignoreCase = true) }
    }
    val groups = remember(filtered) {
        // 分组前全局按 url 去重：同 url 重复条目即使跨天分组，lazy key 也会撞车
        filtered.distinctBy { it.url }.groupBy { dayFmt.format(Date(it.timestamp)) }
            .toSortedMap(compareByDescending { it })
    }

    Scaffold(
        topBar = {
            TabTopBar(
                title = "播放历史",
                onBack = onBack,
                actions = {
                                if (kindFiltered.isNotEmpty()) {
                                    TextButton(onClick = { showClearAll = true }) { Text("清空") }
                                }
            
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            // v0.86.0：子 tab——全部 / 点播 / 直播；v0.91.0：换胶囊 tab，风格统一
            CapsuleTabBar(
                titles = listOf("全部", "点播", "直播"),
                selected = tab,
                onSelect = { tab = it },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SoftDimens.PagePadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                AppTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索标题",
                    modifier = Modifier.weight(1f),
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "清空")
                    }
                }
            }
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.History,
                        title = when {
                            entries.isEmpty() -> "还没有播放记录"
                            query.isNotBlank() -> "没有匹配的结果"
                            tab == 1 -> "暂无点播记录"
                            tab == 2 -> "暂无直播记录"
                            else -> "没有匹配的结果"
                        },
                    )
                }
                return@Column
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                groups.forEach { (day, list) ->
                    item(key = "d:$day") {
                        Text(
                            text = dayLabel(list.first().timestamp),
                            style = SoftType.CardTitle,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                horizontal = SoftDimens.PagePadding,
                                vertical = 8.dp,
                            ),
                        )
                    }
                    items(list, key = { it.url }) { e ->
                        HistoryRow(
                            entry = e,
                            onClick = { onPlay(e) },
                            onLongClick = { deleteTarget = e },
                        )
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    deleteTarget?.let { e ->
        TechDialog(
            onDismissRequest = { deleteTarget = null },
            title = "删除记录",
            confirmText = "删除",
            onConfirm = {
                HistoryStore.delete(context, e.url)
                deleteTarget = null
                reload()
            },
        ) {
            Text("删除「${e.title}」这条播放记录吗？")
        }
    }

    if (showClearAll) {
        // v0.86.0：按当前 tab 清空（全部=清所有，点播/直播只清该类）
        val tabName = when (tab) { 1 -> "点播"; 2 -> "直播"; else -> "全部" }
        TechDialog(
            onDismissRequest = { showClearAll = false },
            title = "清空历史",
            confirmText = "清空",
            onConfirm = {
                if (tab == 0) {
                    HistoryStore.clear(context)
                } else {
                    kindFiltered.forEach { HistoryStore.delete(context, it.url) }
                }
                showClearAll = false
                reload()
                Toast.makeText(context, "已清空播放历史", Toast.LENGTH_SHORT).show()
            },
        ) {
            Text("确定清空${tabName}的 ${kindFiltered.size} 条播放记录吗？此操作不可恢复。")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SoftDimens.PagePadding, vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(SoftDimens.CardSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.kind == "live") {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "直播",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = SoftType.Caption,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = entry.title.ifBlank { "未知标题" },
                        style = SoftType.CardTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timeFmt.format(Date(entry.timestamp)),
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    progressText(entry)?.let { pt ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = pt,
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            IconButton(onClick = onLongClick) {
                Icon(
                    Icons.Filled.Delete, contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
