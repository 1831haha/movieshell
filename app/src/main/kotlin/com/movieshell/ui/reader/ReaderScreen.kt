package com.movieshell.ui.reader

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.model.Chapter
import com.movieshell.data.model.Novel
import com.movieshell.data.model.ReadingProgress
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.ChapterMeta
import com.movieshell.data.novel.NovelSourceEngine
import com.movieshell.data.repo.NovelStore
import com.movieshell.data.repo.ReaderSettings
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
    // v0.61.0：设置持久化，打开即读档
    val savedSettings = remember { ReaderSettings.load(context) }
    var fontSize by remember { mutableIntStateOf(savedSettings.fontSize) }
    var nightMode by remember { mutableStateOf(savedSettings.nightMode) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var restoreOffset by remember { mutableIntStateOf(0) }
    // v0.36.0：衬线/无衬线、行距三档、亮度（-1=跟随系统）
    var serifFont by remember { mutableStateOf(savedSettings.serifFont) }
    var lineHeightRatio by remember { mutableStateOf(savedSettings.lineHeightRatio) }
    var brightness by remember { mutableStateOf(savedSettings.brightness) }
    // v0.61.0：沉浸模式（点中央切换）、正文加载中
    var immersive by remember { mutableStateOf(false) }
    var contentLoading by remember { mutableStateOf(false) }

    // v0.61.0：设置变化即存档
    LaunchedEffect(fontSize, nightMode, serifFont, lineHeightRatio, brightness) {
        ReaderSettings.save(
            context,
            ReaderSettings.Settings(fontSize, nightMode, serifFont, lineHeightRatio, brightness),
        )
    }

    val scrollState = rememberScrollState()

    // v0.36.0：亮度 Slider 直接写 Activity 的 Window
    val activity = context as? Activity
    LaunchedEffect(brightness) {
        activity?.let {
            val lp = it.window.attributes
            lp.screenBrightness = brightness
            it.window.attributes = lp
        }
    }
    // 退出阅读器时恢复系统亮度
    DisposableEffect(Unit) {
        onDispose {
            activity?.let {
                val lp = it.window.attributes
                lp.screenBrightness = -1f
                it.window.attributes = lp
            }
        }
    }

    // 载入书 + 章节 + 进度
    LaunchedEffect(novelId) {
        val n = withContext(Dispatchers.IO) { NovelStore.get(context, novelId) }
        val cs = withContext(Dispatchers.IO) { NovelStore.chapters(context, novelId) }
        val p = withContext(Dispatchers.IO) { ReadingProgressStore.load(context, novelId) }
        // v0.61.0：直进阅读器时书源可能还没载入，先载入再按需拉正文
        if (BookSourceManager.sources.value.isEmpty()) {
            BookSourceManager.load(context)
        }
        novel = n
        chapters = cs
        if (p != null && p.chapterIndex in cs.indices) {
            chapterIndex = p.chapterIndex
            restoreOffset = p.charOffset
        }
        loaded = true
        ensureContent(chapterIndex)
    }

    val chapter = chapters.getOrNull(chapterIndex)

    // v0.61.0：在线书源章节正文按需拉取（本地 TXT 导入的 content 本来就有，不走这里）
    fun ensureContent(index: Int) {
        val c = chapters.getOrNull(index) ?: return
        if (c.content.isNotBlank() || c.url.isBlank()) return
        val n = novel ?: return
        val source = BookSourceManager.sources.value.firstOrNull { it.key == n.sourceKey } ?: return
        scope.launch {
            contentLoading = true
            val text = runCatching { NovelSourceEngine.content(source, ChapterMeta(index, c.title, c.url)) }
                .getOrDefault("")
            if (text.isNotBlank()) {
                NovelStore.updateChapterContent(context, n.id, index, text)
                chapters = chapters.map { if (it.index == index) it.copy(content = text) else it }
            }
            contentLoading = false
        }
    }

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
            ensureContent(index)
        }
    }

    // v0.61.0：阅读进度百分比（按章节）
    val progressPct = if (chapters.isNotEmpty()) {
        ((chapterIndex + 1) * 100 / chapters.size).coerceIn(0, 100)
    } else 0

    val bg = if (nightMode) Color(0xFF121212) else Color(0xFFF7F3E8)
    val fg = if (nightMode) Color(0xFFD8D8D8) else Color(0xFF2B2B2B)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg),
    ) {
        // 顶栏：返回 + 书名/章节名 + 进度（v0.61.0：沉浸模式时隐藏）
        if (!immersive) {
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
                        // v0.61.0：章节名后跟阅读进度百分比
                        "${chapter?.title ?: ""}  $progressPct%",
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
        }

        // 正文（v0.61.0：点中央切换沉浸模式；章节标题印在正文顶部）
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable { immersive = !immersive },
        ) {
            if (!loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("载入中…", color = fg.copy(alpha = 0.6f))
                }
            } else if (chapter == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有章节内容", color = fg.copy(alpha = 0.6f))
                }
            } else if (contentLoading && chapter.content.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = fg.copy(alpha = 0.6f))
                        Spacer(Modifier.height(8.dp))
                        Text("正文加载中…", color = fg.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = chapter.title,
                        color = fg,
                        fontSize = (fontSize + 2).sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    Text(
                        text = chapter.content,
                        color = fg,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * lineHeightRatio).sp,
                        fontFamily = if (serifFont) FontFamily.Serif else FontFamily.SansSerif,
                    )
                }
            }
        }

        // 底栏：上一章 / 目录 / 设置 / 下一章（v0.61.0：沉浸模式时隐藏）
        if (!immersive) {
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
    }

    // 目录弹窗（v0.61.0：打开自动滚到当前章节）
    if (showToc) {
        val tocListState = rememberLazyListState()
        LaunchedEffect(showToc) {
            if (chapterIndex in chapters.indices) {
                tocListState.scrollToItem(chapterIndex.coerceAtLeast(0))
            }
        }
        AlertDialog(
            onDismissRequest = { showToc = false },
            title = { Text("目录（${chapters.size} 章）") },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    state = tocListState,
                ) {
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

    // 设置弹窗：字号 / 字体 / 行距 / 亮度
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("阅读设置") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("字号：${fontSize}sp", fontSize = 14.sp)
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
                    Spacer(Modifier.height(8.dp))
                    // v0.36.0：衬线/无衬线
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("字体", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { serifFont = false }) {
                            Text(
                                "无衬线",
                                color = if (!serifFont) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { serifFont = true }) {
                            Text(
                                "衬线",
                                color = if (serifFont) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // v0.36.0：行距三档
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("行距", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        listOf(1.2f to "紧凑", 1.8f to "适中", 2.4f to "疏朗").forEach { (ratio, label) ->
                            TextButton(onClick = { lineHeightRatio = ratio }) {
                                Text(
                                    label,
                                    color = if (lineHeightRatio == ratio) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // v0.36.0：亮度（-1=跟随系统）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (brightness < 0) "亮度：跟随系统" else "亮度：${(brightness * 100).toInt()}%",
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { brightness = -1f }) { Text("自动") }
                    }
                    Slider(
                        value = if (brightness < 0) 0.5f else brightness,
                        onValueChange = { brightness = it.coerceIn(0.05f, 1f) },
                        valueRange = 0.05f..1f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("确定") }
            },
        )
    }
}
