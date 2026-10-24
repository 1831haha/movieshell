package com.movieshell.ui.reader

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    // v0.36.0：行距三档、亮度（-1=跟随系统）
    // v0.194.0追加：字体五档（0=系统默认 1=无衬线 2=衬线 3=等宽 4=手写），
    // 旧版衬线开关迁移见 ReaderSettings.load；胶囊字本身用对应字体渲染。
    var fontFamily by remember { mutableIntStateOf(savedSettings.fontFamily) }
    val readerFont = readerFontFamilyOf(fontFamily)
    var wasImmersiveBeforeSettings by remember { mutableStateOf(false) }
    var lineHeightRatio by remember { mutableStateOf(savedSettings.lineHeightRatio) }
    var brightness by remember { mutableStateOf(savedSettings.brightness) }
    // v0.61.0：沉浸模式（点按正文切换顶底栏；195 恢复，194 的点击分区已撤掉）
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
    LaunchedEffect(fontSize, bgTheme, fontFamily, lineHeightRatio, brightness, volumePageTurn, pageMode) {
        ReaderSettings.save(
            context,
            ReaderSettings.Settings(
                fontSize = fontSize,
                nightMode = bgTheme == 3,
                fontFamily = fontFamily,
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
    // v0.193.0：每行字符数（与上面同口径），翻页模式少排一行、滚动模式算步长都用它。
    val charsPerLine = remember(pageWidth, fontSize, density, chapter?.content) {
        val fontPx = with(density) { fontSize.sp.toPx() }
        val innerW = (pageWidth - with(density) { 40.dp.toPx() }).coerceAtLeast(0f)
        NovelPaginator.charsPerLine(fontPx, innerW, chapter?.content.orEmpty())
    }
    // v0.193.0：音量翻页开着时，翻页模式分页少排一行，给页首重叠行腾位置；
    // 关着或滚动模式时分页原样不动。
    val pagingCharsPerPage = if (pageMode != 0 && volumePageTurn) {
        (charsPerPage - charsPerLine).coerceAtLeast(200)
    } else charsPerPage
    val pages = remember(chapter?.content, pagingCharsPerPage) {
        NovelPaginator.paginate(chapter?.content.orEmpty(), pagingCharsPerPage)
    }
    // v0.194.0追加：一屏步长（屏高减一行，翻页留重叠行）——音量键与点击分区共用。
    val pageStep = remember(pageHeight, fontSize, lineHeightRatio, density) {
        val linePx = with(density) { (fontSize * lineHeightRatio).sp.toPx() }.toInt()
        (pageHeight - linePx).coerceAtLeast(pageHeight / 2)
    }
    // v0.193.0：首尾行重叠——翻页模式下分页先少排一行，显示时把上一页
    // 末行贴到下一页页首；分页本身仍是原文的无重叠切分，进度偏移计算
    // 继续用 pages，不受显示层重叠影响。
    val displayPages = remember(pages, charsPerLine, pageMode, volumePageTurn) {
        if (pageMode != 0 && volumePageTurn && pages.size > 1) {
            pages.mapIndexed { i, p ->
                if (i == 0) p else pages[i - 1].takeLast(charsPerLine) + "\n" + p
            }
        } else pages
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
                // v0.188.0 M25：进度只写 ReadingProgressStore 一处；书架 lastReadChapter
                // 由 NovelStore 读侧派生，不再双写 index，中间崩溃不再两处各说各话。
                ReadingProgressStore.save(
                    context,
                    ReadingProgress(novelId, latestIndex, latestOffset.coerceIn(0, len)),
                )
                ReadingStatsStore.recordSession(context, statStart, statEnd, statChapters)
            }
        }
    }

    fun jumpTo(index: Int, restoreTo: Int = 0) {
        if (index in chapters.indices) {
            // v0.188.0 M25：切章即把目标章位置写进唯一事实源（旧写法存旧章偏移
            // 却写新章 index，两处自相矛盾；现只写进度，书架派生跟随）。
            scope.launch {
                ReadingProgressStore.save(
                    context,
                    ReadingProgress(novelId, index, restoreTo),
                )
            }
            restoreOffset = restoreTo
            chapterIndex = index
            showToc = false
            showBookmarks = false
            ensureContent(index)
        }
    }


    /** v0.194.0追加：统一翻页动作——音量键与点击分区共用同一套逻辑。 */
    fun pageUp() {
        if (pageMode == 0) {
            if (scrollState.value > 0) {
                scope.launch { scrollState.animateScrollTo((scrollState.value - pageStep).coerceAtLeast(0)) }
            } else if (chapterIndex > 0) {
                jumpTo(chapterIndex - 1, 1_000_000_000)
            }
        } else {
            if (pagerState.currentPage > 0) {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
            } else if (chapterIndex > 0) {
                jumpTo(chapterIndex - 1, 1_000_000_000)
            }
        }
    }

    fun pageDown() {
        if (pageMode == 0) {
            if (scrollState.value < scrollState.maxValue) {
                scope.launch {
                    scrollState.animateScrollTo(
                        (scrollState.value + pageStep).coerceAtMost(scrollState.maxValue),
                    )
                }
            } else if (chapterIndex < chapters.size - 1) {
                jumpTo(chapterIndex + 1)
            }
        } else {
            if (pagerState.currentPage < (pagerState.pageCount - 1).coerceAtLeast(0)) {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            } else if (chapterIndex < chapters.size - 1) {
                jumpTo(chapterIndex + 1)
            }
        }
    }

    /** v0.194.0追加：开设置时先把顶底栏叫出来（设置面板本身就是菜单），关时恢复原样。 */
    /** v0.195.0：194 的点击分区已撤掉（点按恢复只出菜单），onContentTap 随之删除。 */
    fun openSettings() {
        wasImmersiveBeforeSettings = immersive
        immersive = false
        showSettings = true
    }

    fun closeSettings() {
        showSettings = false
        immersive = wasImmersiveBeforeSettings
    }

    // v0.195.0：滑动跨章——滚动模式下手指滑到章末继续上滑进下一章、
    // 滑到章首继续下滑回上一章（之前只有音量键能跨章，手指滑到头就停住）。
    // 用 NestedScroll 拿手指剩下的滑动量：已到边界、且本手势还没跳过章时才跳；
    // 手势结束（isScrollInProgress 变 false）后重置，避免短章一次滑过连跳多章。
    var boundaryJumped by remember { mutableStateOf(false) }
    LaunchedEffect(scrollState.isScrollInProgress) {
        if (!scrollState.isScrollInProgress) boundaryJumped = false
    }
    val boundaryScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!boundaryJumped) {
                    if (available.y < 0 && scrollState.value >= scrollState.maxValue &&
                        chapterIndex < chapters.size - 1
                    ) {
                        boundaryJumped = true
                        jumpTo(chapterIndex + 1)
                    } else if (available.y > 0 && scrollState.value <= 0 && chapterIndex > 0) {
                        boundaryJumped = true
                        jumpTo(chapterIndex - 1, 1_000_000_000)
                    }
                }
                return Offset.Zero
            }
        }
    }

    // v0.72.0：音量键翻页注册（开着才接管音量键，离开页面/关掉即释放）
    // v0.89.0：翻页模式下音量键改为前后翻页
    // v0.193.0：滚动模式按页滚时留一行重叠（上一页末行 = 下一页首行），
    // 免得翻页后接不上；步长随字号/行距/屏高变，故都进 key。
    // v0.194.0：章末/章首跨章——原来翻到章末就停住，进不了下一章（用户原话）；
    // 章末按下一页键进下一章、章首按上一页键回上一章末页；chapterIndex
    // 进 key，重注册时 handler 拿到的是当前章。本块调用 jumpTo，局部函数
    // 先声明后调用，故必须放在 jumpTo 定义之后。
    DisposableEffect(volumePageTurn, pageMode, fontSize, lineHeightRatio, pageHeight, density, chapterIndex, chapters.size) {
        if (volumePageTurn) {
            VolumeKeyRouter.onVolumeUp = { pageUp(); true }
            VolumeKeyRouter.onVolumeDown = { pageDown(); true }
        }
        onDispose {
            VolumeKeyRouter.onVolumeUp = null
            VolumeKeyRouter.onVolumeDown = null
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
    // v0.193.0：4=跟随系统——之前背景是写死的米黄/白，手机切深浅色主题时
    // 阅读页纹丝不动（用户原话“为什么都不变化”）；跟随档取 App 主题的
    // 实际深浅（App 主题默认就是跟随系统），深→夜间黑、浅→白。
    val followDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bg = when (bgTheme) {
        0 -> Color(0xFFFFFFFF)
        2 -> Color(0xFFE8F0E4)
        3 -> Color(0xFF121212)
        4 -> if (followDark) Color(0xFF121212) else Color(0xFFFFFFFF)
        else -> Color(0xFFF7F3E8)
    }
    val fg = when (bgTheme) {
        2 -> Color(0xFF2E3B2E)
        3 -> Color(0xFFD8D8D8)
        4 -> if (followDark) Color(0xFFD8D8D8) else Color(0xFF2B2B2B)
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
                        // v0.188.0 M25：只写进度事实源，不再双写 index（派生读跟随）。
                        ReadingProgressStore.save(
                            context,
                            ReadingProgress(novelId, newIndex, 0),
                        )
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
                        .nestedScroll(boundaryScroll)
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
                        fontFamily = readerFont,
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
                                text = displayPages.getOrNull(page).orEmpty(),
                                color = fg,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * lineHeightRatio).sp,
                                fontFamily = readerFont,
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

        // 底栏：阅读式底菜单（v0.197.0）——第一行 上一章 / 进度条 / 下一章，
        // 第二行 目录 / 界面 / 设置；右上悬浮钮 书签 / 亮度 / 刷新
        // （沉浸模式时隐藏；v0.61.0 的沉浸规则不变）。
        // 注意：界面与设置目前同指一个设置面板（咱的设置本就是一页全量、无滚动），
        // 等阅读反编译稿到了再按阅读的面板分拆；朗读按用户定不做，故不占位。
        // 章内搜索（阅读悬浮钮里的搜索）咱没有对应功能，不摆空壳。
        if (!immersive) {
            // 拖动进度条只跟手，松手才跳章，避免拖动中反复加载
            var sliderPos by remember { mutableStateOf<Float?>(null) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        SmallFloatingActionButton(onClick = { showBookmarks = true }) {
                            Icon(Icons.Filled.Bookmark, "书签")
                        }
                        SmallFloatingActionButton(onClick = { openSettings() }) {
                            Icon(Icons.Filled.BrightnessMedium, "亮度")
                        }
                        SmallFloatingActionButton(onClick = { jumpTo(chapterIndex) }) {
                            Icon(Icons.Filled.Refresh, "刷新")
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { jumpTo(chapterIndex - 1) },
                        enabled = chapterIndex > 0,
                    ) { Text("上一章", color = fg) }
                    Slider(
                        value = sliderPos ?: chapterIndex.toFloat(),
                        onValueChange = { sliderPos = it },
                        onValueChangeFinished = {
                            sliderPos?.toInt()?.let { jumpTo(it) }
                            sliderPos = null
                        },
                        valueRange = 0f..(chapters.size - 1).coerceAtLeast(1).toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { jumpTo(chapterIndex + 1) },
                        enabled = chapterIndex < chapters.size - 1,
                    ) { Text("下一章", color = fg) }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { showToc = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.List, "目录", tint = fg, modifier = Modifier.padding(end = 4.dp))
                            Text("目录", color = fg)
                        }
                    }
                    TextButton(onClick = { openSettings() }) { Text("界面", color = fg) }
                    TextButton(onClick = { openSettings() }) { Text("设置", color = fg) }
                }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        // v0.193.0：原来没限高，34 章直接把弹窗撑满屏；限高后
                        // 内部滚动，弹窗只到约半屏。
                        // v0.194.0：弹窗体跟随主题，非当前章改回主题 onSurface
                        // （深浅主题都可读，193 写死浅色已废止）。
                        .heightIn(max = 420.dp),
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
                    // v0.133.0：createdAt 是毫秒时间戳非强唯一，key 复合章节与偏移兜底防同毫秒撞车
                    items(bookmarks, key = { "${it.createdAt}_${it.chapterIndex}_${it.charOffset}" }) { b ->
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

    // 设置弹窗：v0.195.0恢复悬浮弹窗（194 的底部面板改回），内容按组分层
    if (showSettings) {
        TechDialog(
            onDismissRequest = { closeSettings() },
            title = "阅读设置",
            confirmText = "完成",
            onConfirm = { closeSettings() },
        ) {
            // v0.196.0：紧凑一页版——不滚动、不用原生 Slider/Switch，全部走胶囊与步进器；
            // 标签 12sp、组标题 11sp、行距 6dp，九行设置 + 预览 + 四组标题塞进一页。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 预览：当前字体/字号/行距实时效果（单行）
                Text(
                    "山重水复疑无路，柳暗花明又一村。",
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * lineHeightRatio).sp,
                    fontFamily = readerFont,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ReaderSectionHeader("显示")
                // 字号：步进器（去滑杆）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("字号", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    ReaderStepper(
                        valueText = "${fontSize}sp",
                        onMinus = { if (fontSize > 14) fontSize-- },
                        onPlus = { if (fontSize < 24) fontSize++ },
                    )
                }
                // 字体：五档，胶囊字本身就用对应字体渲染
                ReaderFontRow(fontFamily) { fontFamily = it }
                // 行距
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("行距", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    listOf(1.2f to "紧凑", 1.8f to "适中", 2.4f to "疏朗").forEachIndexed { i, (ratio, label) ->
                        if (i > 0) Spacer(Modifier.width(4.dp))
                        ReaderPill(label, lineHeightRatio == ratio) { lineHeightRatio = ratio }
                    }
                }
                // 背景：五档，最后一档跟随系统（深浅渐变点）
                ReaderBgRow(bgTheme) { bgTheme = it }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                ReaderSectionHeader("阅读")
                // 翻页模式
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("翻页", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    listOf(0 to "上下滚动", 1 to "左右覆盖", 2 to "仿真翻页").forEachIndexed { i, (mode, label) ->
                        if (i > 0) Spacer(Modifier.width(4.dp))
                        ReaderPill(label, pageMode == mode) { pageMode = mode }
                    }
                }
                // 音量键翻页
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("音量键翻页", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    ReaderPill("开", volumePageTurn) { volumePageTurn = true }
                    Spacer(Modifier.width(4.dp))
                    ReaderPill("关", !volumePageTurn) { volumePageTurn = false }
                }
                // 亮度（-1=跟随系统）：自动档 + 步进器（去滑杆）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (brightness < 0) "亮度：跟随系统" else "亮度",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    ReaderPill("自动", brightness < 0, accent = true) { brightness = -1f }
                    Spacer(Modifier.width(4.dp))
                    ReaderStepper(
                        valueText = if (brightness < 0) "—" else "${(brightness * 100).toInt()}%",
                        onMinus = {
                            brightness = (if (brightness < 0) 0.5f else brightness)
                                .minus(0.05f).coerceIn(0.05f, 1f)
                        },
                        onPlus = {
                            brightness = (if (brightness < 0) 0.5f else brightness)
                                .plus(0.05f).coerceIn(0.05f, 1f)
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                ReaderSectionHeader("章节")
                // 章节快捷：上一章 / 目录 / 下一章
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("章节", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    ReaderPill("上一章", false) { if (chapterIndex > 0) { closeSettings(); jumpTo(chapterIndex - 1) } }
                    Spacer(Modifier.width(4.dp))
                    ReaderPill("目录", false, accent = true) { closeSettings(); showToc = true }
                    Spacer(Modifier.width(4.dp))
                    ReaderPill("下一章", false) { if (chapterIndex < chapters.size - 1) { closeSettings(); jumpTo(chapterIndex + 1) } }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                ReaderSectionHeader("管理")
                // 净化规则快捷入口
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "净化规则" +
                            if (purifyCount >= 0) "（$purifyCount 条生效）" else "",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    ReaderPill("管理", false, accent = true) {
                        closeSettings()
                        onOpenPurifyRules()
                    }
                }
            }
        }
    }
    }

/** v0.196.0：设置弹窗组标题——紧凑一页版，11sp。 */
@Composable
private fun ReaderSectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * v0.193.0：设置页胶囊选项——替代原来一排系统味 TextButton（只变字色、无选中态，
 * 用户原话“还是系统的界面、太丑了”）。选中：主题色底 + 白字加粗；未选中：
 * 半透明浅底 + 浅灰紫字；accent 档（管理/自动）：主题色淡底 + 主题色字。
 * v0.194.0追加：font 参数——字体档的胶囊字本身用对应字体渲染，所见即所得。
 */
@Composable
private fun ReaderPill(
    text: String,
    selected: Boolean,
    accent: Boolean = false,
    dotColor: Color? = null,
    dotFollowSystem: Boolean = false,
    font: FontFamily = FontFamily.Default,
    onClick: () -> Unit,
) {
    val bg = when {
        selected -> MaterialTheme.colorScheme.primary
        accent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        accent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotColor != null || dotFollowSystem) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .then(
                        if (dotFollowSystem) {
                            Modifier.background(Brush.horizontalGradient(listOf(Color.White, Color(0xFF121212))))
                        } else {
                            Modifier.background(dotColor ?: Color.Transparent)
                        },
                    )
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            fontSize = 12.sp,
            color = fg,
            fontFamily = font,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** v0.194.0追加：字体档位 → Compose 字体（系统自带五种，不用外挂字体文件）。 */
private fun readerFontFamilyOf(v: Int): FontFamily = when (v) {
    0 -> FontFamily.Default
    2 -> FontFamily.Serif
    3 -> FontFamily.Monospace
    4 -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}

/** v0.196.0：字体行——标签定宽 + 胶囊右对齐单行排，窄屏自动折行（仍在一页内，不滚动）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderFontRow(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "字体",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp).padding(top = 5.dp),
        )
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(0 to "系统默认", 1 to "无衬线", 2 to "衬线", 3 to "等宽", 4 to "手写").forEach { (v, label) ->
                ReaderPill(label, selected == v, font = readerFontFamilyOf(v)) { onSelect(v) }
            }
        }
    }
}

/**
 * v0.196.0：步进器——圆形描边加减钮 + 居中数值，替代原生 Slider（字号/亮度共用）。
 * 滑杆拖动换成了点按步进，弹窗里不再有任何原生控件。
 */
@Composable
private fun ReaderStepper(
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ReaderRoundIconButton(Icons.Filled.Remove, "减小", onMinus)
        Text(
            valueText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(48.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1,
        )
        ReaderRoundIconButton(Icons.Filled.Add, "增大", onPlus)
    }
}

@Composable
private fun ReaderRoundIconButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

/**
 * v0.196.0：背景行——标签定宽 + 胶囊右对齐单行排，窄屏自动折行（仍在一页内，不滚动）。
 * 跟随系统档带深浅渐变点，一眼认得出。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderBgRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "背景",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(40.dp)
                .padding(top = 5.dp),
        )
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ReaderPill("白", selected == 0, dotColor = Color.White) { onSelect(0) }
            ReaderPill("米黄", selected == 1, dotColor = Color(0xFFF7F3E8)) { onSelect(1) }
            ReaderPill("护眼绿", selected == 2, dotColor = Color(0xFFE8F0E4)) { onSelect(2) }
            ReaderPill("夜间黑", selected == 3, dotColor = Color(0xFF121212)) { onSelect(3) }
            ReaderPill("跟随系统", selected == 4, dotFollowSystem = true) { onSelect(4) }
        }
    }
}
