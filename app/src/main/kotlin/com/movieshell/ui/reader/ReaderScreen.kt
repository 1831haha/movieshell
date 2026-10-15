package com.movieshell.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.model.Chapter
import com.movieshell.data.model.Novel
import com.movieshell.data.model.ReadingProgress
import com.movieshell.data.repo.NovelStore
import com.movieshell.data.repo.ReadingProgressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.35.0 基础阅读器。
 *
 * - 打开恢复进度（章 + 章内字符偏移估算），无进度从第 0 章起；
 * - 退出（Dispose）时自动保存进度；
 * - 字号 14~24sp、夜间模式切换；目录弹窗可跳转。
 */
@Composable
fun ReaderScreen(
    novelId: String,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var novel by remember { mutableStateOf<Novel?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var chapterIndex by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var fontSize by remember { mutableIntStateOf(18) }
    var nightMode by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var restoreOffset by remember { mutableIntStateOf(0) }

    val scrollState = rememberScrollState()

    // 载入书 + 章节 + 进度
    LaunchedEffect(novelId) {
        val n = withContext(Dispatchers.IO) { NovelStore.get(context, novelId) }
        val cs = withContext(Dispatchers.IO) { NovelStore.chapters(context, novelId) }
        val p = withContext(Dispatchers.IO) { ReadingProgressStore.load(context, novelId) }
        novel = n
        chapters = cs
        if (p != null && p.chapterIndex in cs.indices) {
            chapterIndex = p.chapterIndex
            restoreOffset = p.charOffset
        }
        loaded = true
    }

    val chapter = chapters.getOrNull(chapterIndex)

    // 章节切换后：回到顶部，或按保存的字符偏移估算滚动位置
    LaunchedEffect(chapterIndex, loaded, chapter?.content?.length) {
        if (!loaded) return@LaunchedEffect
        val len = chapter?.content?.length ?: 0
        if (restoreOffset > 0 && len > 0) {
            val approxLine = fontSize * 1.8f // 每行像素粗估
            val charsPerLine = 22 // 按常见行宽粗估
            val targetPx = (restoreOffset / charsPerLine.toFloat() * approxLine).toInt()
            scrollState.scrollTo(targetPx.coerceAtLeast(0))
            restoreOffset = 0 // 只恢复一次
        } else {
            scrollState.scrollTo(0)
        }
    }

    // 退出时保存进度：用滚动比例反推字符偏移（粗估，自洽即可）。
    // 用 rememberUpdatedState 保证 onDispose 拿到最新的章节和滚动值。
    val latestIndex by rememberUpdatedState(chapterIndex)
    val latestLen by rememberUpdatedState(chapter?.content?.length ?: 0)
    DisposableEffect(novelId) {
        onDispose {
            val len = latestLen
            val max = scrollState.maxValue.coerceAtLeast(1)
            val offset = if (len > 0) (scrollState.value.toFloat() / max * len).toInt() else 0
            scope.launch {
                ReadingProgressStore.save(
                    context,
                    ReadingProgress(novelId, latestIndex, offset.coerceIn(0, len)),
                )
                NovelStore.updateProgress(context, novelId, latestIndex)
            }
        }
    }

    fun jumpTo(index: Int) {
        if (index in chapters.indices) {
            // 切章前先把当前进度存了
            val len = chapter?.content?.length ?: 0
            val max = scrollState.maxValue.coerceAtLeast(1)
            val offset = if (len > 0) (scrollState.value.toFloat() / max * len).toInt() else 0
            scope.launch {
                ReadingProgressStore.save(
                    context,
                    ReadingProgress(novelId, chapterIndex, offset.coerceIn(0, len)),
                )
                NovelStore.updateProgress(context, novelId, index)
            }
            chapterIndex = index
            showToc = false
        }
    }

    val bg = if (nightMode) Color(0xFF121212) else Color(0xFFF7F3E8)
    val fg = if (nightMode) Color(0xFFD8D8D8) else Color(0xFF2B2B2B)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg),
    ) {
        // 顶栏：返回 + 书名/章节名
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "返回", tint = fg)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    novel?.title ?: "",
                    color = fg,
                    fontSize = 16.sp,
                    maxLines = 1,
                )
                Text(
                    chapter?.title ?: "",
                    color = fg.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            IconButton(onClick = { nightMode = !nightMode }) {
                Icon(
                    if (nightMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                    "夜间模式",
                    tint = fg,
                )
            }
        }

        // 正文
        Box(modifier = Modifier.weight(1f)) {
            if (!loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("载入中…", color = fg.copy(alpha = 0.6f))
                }
            } else if (chapter == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有章节内容", color = fg.copy(alpha = 0.6f))
                }
            } else {
                Text(
                    text = chapter.content,
                    color = fg,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.8f).sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }

        // 底栏：上一章 / 目录 / 设置 / 下一章
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { jumpTo(chapterIndex - 1) },
                enabled = chapterIndex > 0,
            ) { Text("上一章", color = fg) }
            TextButton(onClick = { showToc = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.List, "目录", tint = fg, modifier = Modifier.padding(end = 4.dp))
                    Text("目录", color = fg)
                }
            }
            TextButton(onClick = { showSettings = true }) { Text("设置", color = fg) }
            TextButton(
                onClick = { jumpTo(chapterIndex + 1) },
                enabled = chapterIndex < chapters.size - 1,
            ) { Text("下一章", color = fg) }
        }
    }

    // 目录弹窗
    if (showToc) {
        AlertDialog(
            onDismissRequest = { showToc = false },
            title = { Text("目录（${chapters.size} 章）") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(chapters) { c ->
                        Text(
                            text = c.title,
                            fontSize = 14.sp,
                            color = if (c.index == chapterIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { jumpTo(c.index) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showToc = false }) { Text("关闭") }
            },
        )
    }

    // 字号设置弹窗
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("字号：${fontSize}sp") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(onClick = { if (fontSize > 14) fontSize-- }) {
                        Icon(Icons.Filled.Remove, "减小")
                    }
                    Slider(
                        value = fontSize.toFloat(),
                        onValueChange = { fontSize = it.toInt() },
                        valueRange = 14f..24f,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { if (fontSize < 24) fontSize++ }) {
                        Icon(Icons.Filled.Add, "增大")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("确定") }
            },
        )
    }
}
