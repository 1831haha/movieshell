package com.movieshell.ui.reader

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.model.Chapter
import com.movieshell.data.model.Novel
import com.movieshell.data.model.ReadingProgress
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.ChapterMeta
import com.movieshell.data.novel.NovelPaginator
import com.movieshell.data.novel.NovelPurifyEngine
import com.movieshell.data.novel.NovelSourceEngine
import com.movieshell.data.novel.PurifyRuleStore
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.repo.Bookmark
import com.movieshell.data.repo.BookmarkStore
import com.movieshell.data.repo.NovelStore
import com.movieshell.data.repo.ReaderSettings
import com.movieshell.data.repo.ReadingProgressStore
import com.movieshell.data.repo.ReadingStatsStore
import com.movieshell.ui.components.TechDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue
import androidx.compose.material.icons.filled.SwapHoriz

/**
 * v0.35.0 基础阅读器。
 *
 * - 打开恢复进度（章 + 章内字符偏移估算），无进度从第 0 章起；
 * - 退出（Dispose）时自动保存进度；
 * - 字号 14~24sp、夜间模式切换；目录弹窗可跳转。
 *
 * v0.89.0：背景主题四档（白/米黄/护眼绿/夜间黑，替代旧夜间开关）；
 * 翻页模式三档（上下滚动/左右覆盖/仿真翻页，默认左右覆盖）；书签。
 */
@Composable
fun ReaderScreen(
    novelId: String,
    onBack: () -> Unit = {},
    // v0.96.1：净化规则快捷入口（设置面板里进管理页）
    onOpenPurifyRules: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var novel by remember { mutableStateOf<Novel?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    // v0.84.0：换源
    var showChangeSource by remember { mutableStateOf(false) }
    var sourceVersion by remember { mutableIntStateOf(0) }
    var chapterIndex by remember { mutableIntStateOf(0) }
    // v0.98.0：阅读统计——会话开始时间 + 看过的章节集合（退出时记时长/章节数）
    val sessionStartMs = remember(novelId) { System.currentTimeMillis() }
    val chaptersSeen = remember(novelId) { mutableSetOf<Int>() }
    LaunchedEffect(chapterIndex) { chaptersSeen.add(chapterIndex) }
    var loaded by remember { mutableStateOf(false) }
    // v0.61.0：设置持久化，打开即读档
    val savedSettings = remember { ReaderSettings.load(context) }
    var fontSize by remember { mutableIntStateOf(savedSettings.fontSize) }
    // v0.89.0：背景主题 0=白 1=米黄 2=护眼绿 3=夜间黑（替代旧 nightMode 开关）
    var bgTheme by remember { mutableIntStateOf(savedSettings.bgTheme) }
    // v0.89.0：翻页模式 0=上下滚动 1=左右覆盖 2=仿真翻页
    var pageMode by remember { mutableIntStateOf(savedSettings.pageMode) }
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
    // v0.109.0 A7：正文拉取失败给可见反馈——Toast + 正文区重试入口，不许静默空白页
    var contentLoadError by remember { mutableStateOf(false) }
    // v0.72.0：音量键翻页
    var volumePageTurn by remember { mutableStateOf(savedSettings.volumePageTurn) }
    var pageHeight by remember { mutableIntStateOf(0) }
    // v0.89.0：翻页模式分页用正文区宽
    var pageWidth by remember { mutableIntStateOf(0) }
    // v0.89.0：书签
    var showBookmarks by remember { mutableStateOf(false) }
    var bookmarks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    // v0.96.1：净化规则生效数（设置面板入口展示；打开面板时刷新）
    var purifyCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(showSettings) {
        if (showSettings) {
            purifyCount = runCatching {
                PurifyRuleStore.list(context).count { it.enable }
            }.getOrDefault(-1)
        }
    }

    // v0.61.0：设置变化即存档（v0.89.0：加 bgTheme/pageMode；nightMode 按 bgTheme 回填做兼容）
    LaunchedEffect(fontSize, bgTheme, serifFont, lineHeightRatio, brightness, volumePageTurn, pageMode) {
        ReaderSettings.save(
            context,
            ReaderSettings.Settings(
                fontSize = fontSize,
                nightMode = bgTheme == 3,
                serifFont = serifFont,
                lineHeightRatio = lineHeightRatio,
                brightness = brightness,
                volumePageTurn = volumePageTurn,
                bgTheme = bgTheme,
                pageMode = pageMode,
            ),
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

    // v0.61.0：在线书源章节正文按需拉取（本地 TXT 导入的 content 本来就有，不走这里）
    // v0.64.0：本地 PY 书源的书（sourceKey 以 "pybook:" 开头）走 PY 链路
    // v0.99.0 P1-8/P1-9：预加载任务与翻页自动存档任务，翻章时取消上一次
    var preloadJob by remember { mutableStateOf<Job?>(null) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    // 注意：局部 fun 必须先声明后调用，故放在 LaunchedEffect 之前
    /** v0.72.0：拉取章节正文并写库（纯 IO，不碰 UI 状态），返回是否拿到正文。 */
    suspend fun fetchChapter(index: Int): Boolean {
        val c = chapters.getOrNull(index) ?: return false
        if (c.content.isNotBlank() || c.url.isBlank()) return true
        val n = novel ?: return false
        val text = runCatching {
            if (n.sourceKey.startsWith(PyBookSourceManager.SOURCE_KEY_PREFIX)) {
                NovelSourceEngine.contentPy(context, ChapterMeta(index, c.title, c.url), n.sourceKey)
            } else {
                val source = BookSourceManager.sources.value
                    .firstOrNull { it.key == n.sourceKey }
                    ?: return@runCatching ""
                NovelSourceEngine.content(source, ChapterMeta(index, c.title, c.url))
            }
        }.getOrDefault("")
        if (text.isNotBlank()) {
            // v0.95.0：正文统一过净化引擎再入库（规则见净化规则管理页）
            val rules = PurifyRuleStore.list(context)
            val clean = NovelPurifyEngine.purify(text, rules, bookUrl = n.id, sourceKey = n.sourceKey)
            NovelStore.updateChapterContent(context, n.id, index, clean)
            chapters = chapters.map { if (it.index == index) it.copy(content = clean) else it }
            return true
        }
        return false
    }

    fun ensureContent(index: Int) {
        val c = chapters.getOrNull(index) ?: return
        if (c.content.isNotBlank() || c.url.isBlank()) return
        scope.launch {
            contentLoading = true
            contentLoadError = false
            // v0.109.0 A7：fetchChapter 返回值别再丢掉——失败时 Toast + 置错误态给重试入口
            val ok = fetchChapter(index)
            contentLoading = false
            if (!ok) {
                contentLoadError = true
                Toast.makeText(context, "正文拉取失败，点击正文区重试", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // v0.72.0：预加载下一章（后台静默，不碰 contentLoading）
            // v0.95.0：同时预加载上一章（回看不转圈）
            // v0.99.0 P1-8：取消上一次预加载，快速翻章不产生请求风暴
            preloadJob?.cancel()
            preloadJob = scope.launch {
                fetchChapter(index + 1)
                fetchChapter(index - 1)
            }
        }
    }

    // 载入书 + 章节 + 进度（v0.84.0：换源后 sourceVersion 递增触发重载）
    LaunchedEffect(novelId, sourceVersion) {
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

    // v0.95.0：真分页引擎（段落感知，见 NovelPaginator）
    val density = LocalDensity.current
    val charsPerPage = remember(pageWidth, pageHeight, fontSize, lineHeightRatio, density, chapter?.content) {
        val fontPx = with(density) { fontSize.sp.toPx() }
        val innerW = (pageWidth - with(density) { 40.dp.toPx() }).coerceAtLeast(0f)
        val innerH = (pageHeight - with(density) { 24.dp.toPx() }).coerceAtLeast(0f)
        // v0.102.0 P2-1（降级）：按正文中英文混排比例微调字符宽度系数
        NovelPaginator.charsPerPage(fontPx, lineHeightRatio, innerW, innerH, chapter?.content.orEmpty())
    }
    val pages = remember(chapter?.content, charsPerPage) {
        NovelPaginator.paginate(chapter?.content.orEmpty(), charsPerPage)
    }
    val pagerState = rememberPagerState(pageCount = { pages.size.coerceAtLeast(1) })

    /** v0.89.0：当前阅读位置换算成字符偏移（进度保存/书签用，两种模式统一）。 */
    fun computeOffset(): Int {
        val len = chapter?.content?.length ?: 0
        return if (pageMode == 0) {
            val max = scrollState.maxValue.coerceAtLeast(1)
            if (len > 0) (scrollState.value.toFloat() / max * len).toInt().coerceIn(0, len) else 0
        } else {
            // v0.95.0：翻页模式用分页引擎的页首偏移（段落感知分页下更精确）
            NovelPaginator.pageStartOffset(pages, pagerState.currentPage).coerceIn(0, len)
        }
    }

    // v0.72.0：音量键翻页注册（开着才接管音量键，离开页面/关掉即释放）
    // v0.89.0：翻页模式下音量键改为前后翻页
    DisposableEffect(volumePageTurn, pageMode) {
        if (volumePageTurn) {
            VolumeKeyRouter.onVolumeUp = {
                scope.launch {
                    if (pageMode == 0) {
                        scrollState.animateScrollTo((scrollState.value - pageHeight).coerceAtLeast(0))
                    } else {
                        pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                    }
                }
                true
            }
            VolumeKeyRouter.onVolumeDown = {
                scope.launch {
                    if (pageMode == 0) {
                        scrollState.animateScrollTo(
                            (scrollState.value + pageHeight).coerceAtMost(scrollState.maxValue),
                        )
                    } else {
                        pagerState.animateScrollToPage(
                            (pagerState.currentPage + 1)
                                .coerceAtMost((pagerState.pageCount - 1).coerceAtLeast(0)),
                        )
                    }
                }
                true
            }
        }
        onDispose {
            VolumeKeyRouter.onVolumeUp = null
            VolumeKeyRouter.onVolumeDown = null
        }
    }

    // 章节切换后：回到顶部，或按保存的字符偏移恢复位置
    LaunchedEffect(chapterIndex, loaded, chapter?.content?.length) {
        if (!loaded) return@LaunchedEffect
        val len = chapter?.content?.length ?: 0
        if (pageMode == 0) {
            if (restoreOffset > 0 && len > 0) {
                val approxLine = fontSize * 1.8f // 每行像素粗估
                val charsPerLine = 22 // 按常见行宽粗估
                val targetPx = (restoreOffset / charsPerLine.toFloat() * approxLine).toInt()
                scrollState.scrollTo(targetPx.coerceAtLeast(0))
                restoreOffset = 0 // 只恢复一次
            } else {
                scrollState.scrollTo(0)
            }
        } else {
            // v0.95.0：翻页模式按字符偏移定位页；正文还没到时等下一次 content 长度变化再恢复
            if (restoreOffset > 0 && len > 0 && pages.isNotEmpty()) {
                pagerState.scrollToPage(NovelPaginator.pageForOffset(pages, restoreOffset))
                restoreOffset = 0 // 只恢复一次
            } else {
                pagerState.scrollToPage(0)
            }
        }
    }

    // v0.95.0：字号/行距/窗口变化导致重分页时，按字符偏移尽量保持阅读位置
    var lastCpp by remember { mutableIntStateOf(0) }
    var lastPageOffset by remember { mutableIntStateOf(0) }
    val latestChapterIndex by rememberUpdatedState(chapterIndex)
    LaunchedEffect(pagerState.currentPage, pages) {
        lastPageOffset = NovelPaginator.pageStartOffset(pages, pagerState.currentPage)
        // v0.99.0 P1-9：翻页 2 秒 debounce 自动存进度，App 崩溃/被杀不丢进度
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2000)
            val idx = latestChapterIndex
            val off = NovelPaginator.pageStartOffset(pages, pagerState.currentPage)
            ReadingProgressStore.save(context, ReadingProgress(novelId, idx, off))
        }
    }
    LaunchedEffect(charsPerPage) {
        if (lastCpp > 0 && lastCpp != charsPerPage && pages.isNotEmpty() && pageMode != 0) {
            pagerState.scrollToPage(NovelPaginator.pageForOffset(pages, lastPageOffset))
        }
        lastCpp = charsPerPage
    }

    // 退出时保存进度。用 rememberUpdatedState 保证 onDispose 拿到最新的章节和偏移值。
    val latestIndex by rememberUpdatedState(chapterIndex)
    val latestOffset by rememberUpdatedState(computeOffset())
    val latestLen by rememberUpdatedState(chapter?.content?.length ?: 0)
    DisposableEffect(novelId) {
        onDispose {
            val len = latestLen
            // v0.98.0：阅读统计（时长按天切分，章节记去重后的看过数）
            val statStart = sessionStartMs
            val statChapters = chaptersSeen.size
            val statEnd = System.currentTimeMillis()
            scope.launch {
                ReadingProgressStore.save(
                    context,
                    ReadingProgress(novelId, latestIndex, latestOffset.coerceIn(0, len)),
                )
                NovelStore.updateProgress(context, novelId, latestIndex)
                ReadingStatsStore.recordSession(context, statStart, statEnd, statChapters)
            }
        }
    }

    fun jumpTo(index: Int, restoreTo: Int = 0) {
        if (index in chapters.indices) {
            // 切章前先把当前进度存了
            val offset = computeOffset()
            scope.launch {
                ReadingProgressStore.save(
                    context,
                    ReadingProgress(novelId, chapterIndex, offset),
                )
                NovelStore.updateProgress(context, novelId, index)
            }
            restoreOffset = restoreTo
            chapterIndex = index
            showToc = false
            showBookmarks = false
            ensureContent(index)
        }
    }

    // v0.89.0：书签
    fun refreshBookmarks() {
        scope.launch { bookmarks = BookmarkStore.list(context, novelId) }
    }

    fun addBookmark() {
        val c = chapter ?: return
        scope.launch {
            BookmarkStore.add(
                context,
                novelId,
                Bookmark(
                    chapterIndex = chapterIndex,
                    charOffset = computeOffset(),
                    chapterTitle = c.title,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            refreshBookmarks()
            Toast.makeText(context, "已添加书签", Toast.LENGTH_SHORT).show()
        }
    }

    // v0.61.0：阅读进度百分比（按章节）
    val progressPct = if (chapters.isNotEmpty()) {
        ((chapterIndex + 1) * 100 / chapters.size).coerceIn(0, 100)
    } else 0

    // v0.89.0：背景主题配色 0=白 1=米黄 2=护眼绿 3=夜间黑
    val bg = when (bgTheme) {
        0 -> Color(0xFFFFFFFF)
        2 -> Color(0xFFE8F0E4)
        3 -> Color(0xFF121212)
        else -> Color(0xFFF7F3E8)
    }
    val fg = when (bgTheme) {
        2 -> Color(0xFF2E3B2E)
        3 -> Color(0xFFD8D8D8)
        else -> Color(0xFF2B2B2B)
    }

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
                // v0.84.0：换源（本地 TXT 书没有源可换）
                if (novel?.sourceKey != "local") {
                    IconButton(onClick = { showChangeSource = true }) {
                        Icon(Icons.Filled.SwapHoriz, "换源", tint = fg)
                    }
                }
                // v0.89.0：书签（加当前进度为书签 / 打开书签列表）
                IconButton(onClick = { addBookmark() }) {
                    Icon(Icons.Filled.BookmarkBorder, "添加书签", tint = fg)
                }
                TextButton(onClick = { showBookmarks = true }) { Text("书签", color = fg) }
            }
        }

        // v0.84.0：换源弹窗
        val curNovel = novel
        if (showChangeSource && curNovel != null) {
            ChangeSourceDialog(
                novel = curNovel,
                currentChapterTitle = chapter?.title.orEmpty(),
                currentChapterIndex = chapterIndex,
                onDismiss = { showChangeSource = false },
                onSwitched = { newIndex ->
                    showChangeSource = false
                    // v0.84.0：先存新进度再重载，否则重载会被旧进度覆盖
                    scope.launch {
                        ReadingProgressStore.save(
                            context,
                            ReadingProgress(novelId, newIndex, 0),
                        )
                        NovelStore.updateProgress(context, novelId, newIndex)
                        chapterIndex = newIndex
                        sourceVersion++
                    }
                },
            )
        }

        // 正文（v0.61.0：点中央切换沉浸模式；章节标题印在正文顶部）
        // v0.89.0：翻页模式 1/2 用 HorizontalPager，点按中央切换沉浸（drag 留给 pager）
        Box(
            modifier = Modifier
                .weight(1f)
                // v0.72.0：记下可视宽高（音量键按页滚 / 翻页模式粗分页）
                .onSizeChanged {
                    pageHeight = it.height
                    pageWidth = it.width
                },
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
            // v0.109.0 A7：正文拉取失败——可见错误态 + 点任意处重试，不许静默空白页
            } else if (contentLoadError && chapter.content.isBlank()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable {
                            contentLoadError = false
                            ensureContent(chapterIndex)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("正文拉取失败", color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                contentLoadError = false
                                ensureContent(chapterIndex)
                            },
                        ) {
                            Text("点击重试", color = fg.copy(alpha = 0.8f), fontSize = 14.sp)
                        }
                    }
                }
            } else if (pageMode == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .clickable { immersive = !immersive }
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
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { immersive = !immersive })
                        },
                ) { page ->
                    val pageOffset =
                        (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (pageMode == 2) {
                                    // v0.89.0 仿真翻页：书页翻动旋转
                                    Modifier.graphicsLayer {
                                        rotationY = -30f * pageOffset
                                        cameraDistance = 12f
                                    }
                                } else Modifier,
                            ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            if (page == 0) {
                                Text(
                                    text = chapter.title,
                                    color = fg,
                                    fontSize = (fontSize + 2).sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 16.dp),
                                )
                            }
                            Text(
                                text = pages.getOrNull(page).orEmpty(),
                                color = fg,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * lineHeightRatio).sp,
                                fontFamily = if (serifFont) FontFamily.Serif else FontFamily.SansSerif,
                            )
                        }
                        // v0.89.0 仿真翻页：翻页中的页面叠一层随偏移渐变的黑色遮罩
                        if (pageMode == 2 && pageOffset != 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Color.Black.copy(
                                            alpha = (0.3f * pageOffset.absoluteValue)
                                                .coerceIn(0f, 0.3f),
                                        ),
                                    ),
                            )
                        }
                    }
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
        TechDialog(
            onDismissRequest = { showToc = false },
            title = "目录（${chapters.size} 章）",
            dismissText = "关闭",
        ) {
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
            }
        }

    // v0.89.0：书签列表（TechDialog）
    if (showBookmarks) {
        val dateFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
        LaunchedEffect(showBookmarks) { refreshBookmarks() }
        TechDialog(
            onDismissRequest = { showBookmarks = false },
            title = "书签",
            subtitle = novel?.title,
            dismissText = "关闭",
        ) {
            Button(
                onClick = { addBookmark() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("＋ 添加当前进度") }
            Spacer(Modifier.height(8.dp))
            if (bookmarks.isEmpty()) {
                Text(
                    "暂无书签",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                ) {
                    items(bookmarks, key = { it.createdAt }) { b ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        showBookmarks = false
                                        jumpTo(b.chapterIndex, b.charOffset)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                            ) {
                                Text(b.chapterTitle, fontSize = 14.sp, maxLines = 1)
                                Text(
                                    dateFmt.format(Date(b.createdAt)),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    BookmarkStore.remove(context, novelId, b.createdAt)
                                    bookmarks = bookmarks.filterNot { it.createdAt == b.createdAt }
                                }
                            }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    "删除书签",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 设置弹窗：字号 / 字体 / 行距 / 亮度
    if (showSettings) {
        TechDialog(
            onDismissRequest = { showSettings = false },
            title = "阅读设置",
            confirmText = "确定",
            onConfirm = { showSettings = false },
        ) {
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
                    // v0.89.0：背景主题四档（替代旧夜间开关）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("背景", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        listOf(0 to "白", 1 to "米黄", 2 to "护眼绿", 3 to "夜间黑").forEach { (theme, label) ->
                            TextButton(onClick = { bgTheme = theme }) {
                                Text(
                                    label,
                                    color = if (bgTheme == theme) MaterialTheme.colorScheme.primary
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
                    Spacer(Modifier.height(8.dp))
                    // v0.72.0：音量键翻页
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("音量键翻页", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { volumePageTurn = !volumePageTurn }) {
                            Text(
                                if (volumePageTurn) "开" else "关",
                                color = if (volumePageTurn) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // v0.89.0：翻页模式三档
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("翻页", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        listOf(0 to "上下滚动", 1 to "左右覆盖", 2 to "仿真翻页").forEach { (mode, label) ->
                            TextButton(onClick = { pageMode = mode }) {
                                Text(
                                    label,
                                    color = if (pageMode == mode) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // v0.96.1：净化规则快捷入口（v0.95 底层已就绪，这里补界面入口）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "净化规则" +
                                if (purifyCount >= 0) "（$purifyCount 条生效）" else "",
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            showSettings = false
                            onOpenPurifyRules()
                        }) {
                            Text("管理", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
