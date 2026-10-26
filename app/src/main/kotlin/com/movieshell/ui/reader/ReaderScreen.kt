package com.movieshell.ui.reader

import android.app.Activity
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.movieshell.ui.components.AppToast
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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.TechTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
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
import kotlin.math.roundToInt
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
// v0.211.0 审计修复：不随组合销毁的写盘作用域（退出保存用）。
private val readerIoScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
)

/** v0.211.0 审计修复：详情目录点某章要直达该章——路由只带
 *  书 id、不带章节参数，原来目录点击只能按旧进度打开。书架点
 *  目录时先把目标章写进这个一次性请求，阅读器载入时消费它
 *  （消费后立即清零，别的入口进阅读器不受影响）。 */
object ReaderJumpRequest {
    var chapter: Int? = null
}

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

    // v0.214.0：顶栏来源名（JSON 查名、PY 查标题、本地直显；查不到回落 key）。
    val jsonSources by BookSourceManager.sources.collectAsState()
    val pyRules by PyBookSourceManager.rules.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            BookSourceManager.load(context)
            PyBookSourceManager.load(context)
        }
    }
    val readerSourceName = when (val k = novel?.sourceKey) {
        null -> ""
        "local" -> "本地"
        else -> jsonSources.firstOrNull { it.key == k }?.name
            ?: pyRules.firstOrNull { it.name == k.removePrefix(PyBookSourceManager.SOURCE_KEY_PREFIX) }?.title
            ?: k
    }
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
    // v0.215.0：章内搜索（本章正文内找词、点结果跳转；滚动模式按恢复同款公式换算像素，翻页模式按页首偏移跳页）。
    var showChapterSearch by remember { mutableStateOf(false) }
    var chapterSearchQuery by remember { mutableStateOf("") }
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
                AppToast.show(context, "正文拉取失败，点击正文区重试", long = false)
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

    // v0.242.0：重读本章（图2顶栏/悬浮刷新钮）——清掉本章正文缓存
    // 再重拉；页码/滚动位置不动（恢复效果认领过本章会直接放行，
    // 不重定位）。本地 TXT 书没有可拉的链接，直接返回。
    fun refreshChapter() {
        val idx = chapterIndex
        val c = chapters.getOrNull(idx) ?: return
        if (c.url.isBlank()) return
        chapters = chapters.map { if (it.index == idx) it.copy(content = "") else it }
        contentLoadError = false
        ensureContent(idx)
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
        // v0.211.0 审计修复：目录直达——有一次性跳转请求时优先用它，
        // 而不是按旧进度恢复；无请求时走原有进度恢复。
        val jump = ReaderJumpRequest.chapter
        ReaderJumpRequest.chapter = null
        if (jump != null && jump in cs.indices) {
            chapterIndex = jump
            restoreOffset = 0
        } else if (p != null && p.chapterIndex in cs.indices) {
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
    // v0.240.0：恢复逻辑重写——原来效果键里有正文长度，正文一到就
    // 无条件滚一次（没到先滚顶、到了滚偏移、之后正文再变又滚顶），
    // 回上一章末页的哨兵常被后到的一次长度变化洗成章首，手指越界
    // 回上一章看着就是“直接到了上一章开头”。现改按章认领：
    // 同一章只恢复一次；正文没到时等着不滚；滑到底前等一帧让新
    // 正文完成布局，否则 maxValue 还是旧值/0，照样滑到顶。
    var restoredForChapter by remember { mutableIntStateOf(-1) }
    // v0.241.0：跨章落点认领——240 只修了恢复效果本身，但翻页模式下
    // 还有两路会把刚落好的末页洗回章首：①重分页效果（charsPerPage
    // 一变就跑）用旧章页码对应的陈旧偏移重定位，用户从旧章第 0 页
    // 回来时那个偏移就是 0；②手指越界手势未结束时 pager 的回弹
    // settling 也会把页码拽回 0。故换章时立 pending：恢复效果只在
    // 手势结束且分页就绪时落点，落到目标才清 pending；重分页效果
    // 在换章当次直接跳过，落点只归恢复效果管。
    var landingChapter by remember { mutableIntStateOf(-1) }
    var landingPending by remember { mutableStateOf(false) }
    LaunchedEffect(chapterIndex, loaded, chapter?.content?.length, pagerState.isScrollInProgress) {
        if (!loaded) return@LaunchedEffect
        if (restoredForChapter == chapterIndex && !(landingPending && landingChapter == chapterIndex)) return@LaunchedEffect
        val len = chapter?.content?.length ?: 0
        if (len <= 0) return@LaunchedEffect // 正文没到：等着，不滚顶
        // v0.241.0：手指越界手势还没结束时先不落点——pager 回弹会把
        // 页码拽回 0；等手势结束（isScrollInProgress 变 false 触发重跑）
        // 再落点，落点才是最终位置。按钮换章无手势，不受影响。
        if (pageMode != 0 && landingPending && pagerState.isScrollInProgress) return@LaunchedEffect
        if (pageMode == 0) {
            if (restoreOffset > 0) {
                // v0.211.0 审计修复：保存用“滚动像素/最大值×全文长度”，
                // 恢复原来却用写死的每行 22 字、行高 1.8，两公式不互逆，
                // 同一位置每次重进都漂移。改用与保存同口径的真实值：
                // 每行字数用 charsPerLine（屏宽/字号算出），行高用
                // 字号×行距，与 computeOffset 的像素比例对应。
                val linePx = with(density) { (fontSize * lineHeightRatio).sp.toPx() }
                val cpl = charsPerLine.coerceAtLeast(1)
                if (restoreOffset >= len) {
                    // 末页哨兵（回上一章末页）：等布局出 maxValue 再滑到底
                    var frames = 0
                    while (scrollState.maxValue == 0 && frames < 10) {
                        withFrameNanos { }
                        frames++
                    }
                    scrollState.scrollTo(Int.MAX_VALUE)
                } else {
                    val targetPx = (restoreOffset / cpl.toFloat() * linePx).toInt()
                    scrollState.scrollTo(targetPx.coerceAtLeast(0))
                }
            } else {
                scrollState.scrollTo(0)
            }
        } else {
            // v0.95.0：翻页模式按字符偏移定位页；正文还没到时等下一次 content 长度变化再恢复
            // v0.241.0：分页未就绪时不消费哨兵——否则末页落点被吞掉，
            // 恢复效果又已认领过本章，之后再无重跑机会，必然停章首。
            if (pages.isEmpty()) return@LaunchedEffect
            if (restoreOffset > 0) {
                pagerState.scrollToPage(NovelPaginator.pageForOffset(pages, restoreOffset))
            } else {
                pagerState.scrollToPage(0)
            }
        }
        restoredForChapter = chapterIndex
        restoreOffset = 0 // 只恢复一次
        landingPending = false
    }

    // v0.95.0：字号/行距/窗口变化导致重分页时，按字符偏移尽量保持阅读位置
    var lastCpp by remember { mutableIntStateOf(0) }
    var lastPageOffset by remember { mutableIntStateOf(0) }
    // v0.241.0：重分页效果上次处理过的章节——换章当次它若跑起来，
    // 就会用旧章页码对应的陈旧偏移把恢复效果刚落好的末页盖回章首。
    var cppChapter by remember { mutableIntStateOf(-1) }
    val latestChapterIndex by rememberUpdatedState(chapterIndex)
    LaunchedEffect(pagerState.currentPage, pages) {
        // v0.211.0 回归修复（静态审计）：这路翻页保存原来没有 pageMode
        // 守卫，滚动模式下字号/章节一变引起 pages 重算，2 秒后就用
        // 翻页页码算出的偏移覆盖滚动停存的正确位置；只在翻页模式跑。
        if (pageMode == 0) return@LaunchedEffect
        lastPageOffset = NovelPaginator.pageStartOffset(pages, pagerState.currentPage)
        // v0.99.0 P1-9：翻页 2 秒 debounce 自动存进度，App 崩溃/被杀不丢进度
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2000)
            val idx = latestChapterIndex
            val off = NovelPaginator.pageStartOffset(pages, pagerState.currentPage)
            ReadingProgressStore.save(context, ReadingProgress(novelId, idx, off, chapterTitle = chapters.getOrNull(idx)?.title.orEmpty()))
        }
    }
    // v0.211.0：上下滚动模式以前没有这个——滚动时进度只在退出阅读页
    // 那一刻存一次，App 被划掉/崩溃就丢到上次切章的位置。补上滚动停止
    // 即存：手指一停（拖动/惯性都结束）就存当前滚动位置，和翻页模式
    // 的“翻页后自动存”对齐。只在滚动模式生效，翻页模式不走这条。
    // 注意不用 scrollState.value 当 key——那样每滚一个像素就重组整页，
    // 改用 isScrollInProgress（一次手势只变两次），滚动停下才存一次。
    LaunchedEffect(scrollState.isScrollInProgress) {
        // v0.211.0 回归修复（流程审计 H1）：首帧 isScrollInProgress 本来就是
        // false，效果一进页就跑一次，那时进度还没载入（loaded=false、
        // chapterIndex 仍是初始 0），会把进度写成第 0 章覆盖真进度。
        // 加 loaded/chapter 守卫：没载入完不写盘；载入完后 key 没变、
        // 效果不会重跑，第一次真正滚动停下时才存，不丢也不误写。
        if (pageMode != 0 || scrollState.isScrollInProgress || !loaded || chapter == null) return@LaunchedEffect
        ReadingProgressStore.save(
            context,
            ReadingProgress(novelId, latestChapterIndex, computeOffset(), chapterTitle = chapters.getOrNull(latestChapterIndex)?.title.orEmpty()),
        )
    }
    LaunchedEffect(charsPerPage) {
        // v0.241.0：换章当次（或落点未定）直接跳过——换章落点只归
        // 恢复效果管；这里若跑，旧章页码对应的陈旧偏移（用户从旧章
        // 第 0 页回来时就是 0）会把刚落好的末页盖回章首。同章内
        // 改字号/行距/转屏仍走原逻辑，按偏移保持位置。
        if (landingPending || cppChapter != chapterIndex) {
            cppChapter = chapterIndex
            lastCpp = charsPerPage
            return@LaunchedEffect
        }
        if (lastCpp > 0 && lastCpp != charsPerPage && pages.isNotEmpty() && pageMode != 0) {
            pagerState.scrollToPage(NovelPaginator.pageForOffset(pages, lastPageOffset))
        }
        lastCpp = charsPerPage
    }

    // 退出时保存进度。用 rememberUpdatedState 保证 onDispose 拿到最新的章节和偏移值。
    val latestIndex by rememberUpdatedState(chapterIndex)
    val latestTitle by rememberUpdatedState(chapter?.title.orEmpty())
    val latestOffset by rememberUpdatedState(computeOffset())
    val latestLen by rememberUpdatedState(chapter?.content?.length ?: 0)
    // v0.216.0（C7）：退出保存沿用滚动停存同款守卫——没载入完、或 chapter
    // 为空（书坏了／被删了）时不写盘，否则一次空打开就把真进度洗成第 0 章。
    val latestLoaded by rememberUpdatedState(loaded)
    val latestChapter by rememberUpdatedState(chapter)
    DisposableEffect(novelId) {
        onDispose {
            val len = latestLen
            // v0.98.0：阅读统计（时长按天切分，章节记去重后的看过数）
            val statStart = sessionStartMs
            val statChapters = chaptersSeen.size
            val statEnd = System.currentTimeMillis()
            // v0.211.0 审计修复：退出保存原来用随组合销毁的 scope，
            // 组合销毁时该 scope 可能已被取消，正常退出也可能丢最后
            // 一段进度；改走不随组合销毁的应用级作用域。
            readerIoScope.launch {
                // v0.188.0 M25：进度只写 ReadingProgressStore 一处；书架 lastReadChapter
                // 由 NovelStore 读侧派生，不再双写 index，中间崩溃不再两处各说各话。
                if (latestLoaded && latestChapter != null) {
                    ReadingProgressStore.save(
                        context,
                        ReadingProgress(novelId, latestIndex, latestOffset.coerceIn(0, len), chapterTitle = latestTitle),
                    )
                }
                ReadingStatsStore.recordSession(context, statStart, statEnd, statChapters)
            }
        }
    }

    fun jumpTo(index: Int, restoreTo: Int = 0) {
        if (index in chapters.indices) {
            // v0.188.0 M25：切章即把目标章位置写进唯一事实源（旧写法存旧章偏移
            // 却写新章 index，两处自相矛盾；现只写进度，书架派生跟随）。
            // v0.211.0 审计修复：回上一章末页用的 1_000_000_000 哨兵
            // 原来原样落盘，进度文件里长期存着超章长的非法偏移；
            // 本次会话的恢复仍用哨兵（恢复处会钳制到末页），落盘
            // 时按当前章长钳制，未拉到正文时先存章首。
            val len = chapters.getOrNull(index)?.content?.length ?: 0
            val safeOffset = if (len > 0) restoreTo.coerceIn(0, len) else 0
            scope.launch {
                ReadingProgressStore.save(
                    context,
                    ReadingProgress(novelId, index, safeOffset, chapterTitle = chapters.getOrNull(index)?.title.orEmpty()),
                )
            }
            restoreOffset = restoreTo
            // v0.241.0：只有真换章才立落点认领——目录点当前章、
            // 书签跳本章时 index 不变，恢复效果不会重跑，pending
            // 若立了就再也清不掉，会一直压住重分页定位。
            if (index != chapterIndex) {
                landingChapter = index
                landingPending = true
            }
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

    // v0.242.0：界面面板（图2底栏“界面”）与设置面板分开——界面只放
    // 显示组（字号/字体/行距/背景），设置只放阅读组+管理组；两个面板
    // 各自不到半屏，互不重复入口。
    var showDisplaySettings by remember { mutableStateOf(false) }
    fun openDisplaySettings() {
        wasImmersiveBeforeSettings = immersive
        immersive = false
        showDisplaySettings = true
    }
    fun closeDisplaySettings() {
        showDisplaySettings = false
        immersive = wasImmersiveBeforeSettings
    }

    // v0.242.0：夜间切换（图2悬浮月亮钮）——切走时记住当前背景，
    // 再点回原来的背景（跟随系统档也照此恢复）。
    var lastNonNightBg by remember { mutableIntStateOf(if (bgTheme != 3) bgTheme else 1) }
    fun toggleNight() {
        if (bgTheme == 3) bgTheme = lastNonNightBg
        else {
            lastNonNightBg = bgTheme
            bgTheme = 3
        }
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

    // v0.233.0：翻页模式手指越界跨章——滚动模式 195 已有 boundaryScroll，
    // 翻页模式的 HorizontalPager 一直没有越界处理，滑到章末/章首就
    // 弹回原地、进不了上下章，只能靠按钮/音量键。复用同款 NestedScroll
    // 机制：pager 在边界吃不下的横向余量会走到 onPostScroll 的 available.x
    //（左滑为负→下一章、右滑为正→上一章尾页），一手势只跳一次。
    var pagerBoundaryJumped by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) pagerBoundaryJumped = false
    }
    val pagerBoundaryScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!pagerBoundaryJumped) {
                    if (available.x < 0 && pagerState.currentPage >= pagerState.pageCount - 1 &&
                        chapterIndex < chapters.size - 1
                    ) {
                        pagerBoundaryJumped = true
                        jumpTo(chapterIndex + 1)
                    } else if (available.x > 0 && pagerState.currentPage <= 0 && chapterIndex > 0) {
                        pagerBoundaryJumped = true
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
            AppToast.show(context, "已添加书签", long = false)
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

    // v0.241.0：根布局改 Box 包 Column——设置面板要贴底悬浮且不遮罩
    // （边调边看正文实时变化），Column 里贴不了底，改由 Box 承接。
    Box(modifier = Modifier.fillMaxSize()) {
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
                    // v0.214.0：顶栏补来源名（报告中项：阅读页看不到当前来源）。
                    if (readerSourceName.isNotBlank()) {
                        Text(
                            readerSourceName,
                            color = fg.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                }
                // v0.84.0：换源（本地 TXT 书没有源可换）
                if (novel?.sourceKey != "local") {
                    IconButton(onClick = { showChangeSource = true }) {
                        Icon(Icons.Filled.SwapHoriz, "换源", tint = fg)
                    }
                }
                // v0.211.0：书签只留一个入口——点图标开书签列表，列表里已有
                // “＋ 添加当前进度”，顶栏不再同时摆“加书签图标 + 书签文字”。
                IconButton(onClick = { showBookmarks = true }) {
                    Icon(Icons.Filled.Bookmark, "书签", tint = fg)
                }
                // v0.215.0：章内搜索入口。
                IconButton(onClick = { showChapterSearch = true }) {
                    Icon(Icons.Filled.Search, "章内搜索", tint = fg)
                }
                // v0.242.0：刷新（图2顶栏 ↻）——重读本章，本地书无链接可拉的不摆。
                if (novel?.sourceKey != "local") {
                    IconButton(onClick = { refreshChapter() }) {
                        Icon(Icons.Filled.Refresh, "刷新", tint = fg)
                    }
                }
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
                        // v0.217.0（F44）：标题按新目录算——原来拿旧目录列表取
                        // newIndex 处的标题，换源后书架行显示的是旧源章名；
                        // 新目录刚由换源写盘，从盘上取最准。
                        val newTitle = runCatching { NovelStore.chapters(context, novelId) }
                            .getOrNull()?.getOrNull(newIndex)?.title.orEmpty()
                        // v0.188.0 M25：只写进度事实源，不再双写 index（派生读跟随）。
                        ReadingProgressStore.save(
                            context,
                            ReadingProgress(novelId, newIndex, 0, chapterTitle = newTitle),
                        )
                        chapterIndex = newIndex
                        sourceVersion++
                    }
                },
            )
        }

        // v0.215.0：章内搜索弹窗——本章正文内找词，点结果跳转（不跨章）。
        if (showChapterSearch) {
            val content = chapter?.content.orEmpty()
            val matches = remember(chapterSearchQuery, content) {
                val q = chapterSearchQuery.trim()
                if (q.isEmpty() || content.isEmpty()) emptyList()
                else {
                    val hits = mutableListOf<Int>()
                    var from = 0
                    while (true) {
                        val i = content.indexOf(q, from, ignoreCase = true)
                        if (i < 0) break
                        hits += i
                        from = i + q.length.coerceAtLeast(1)
                    }
                    hits
                }
            }
            TechDialog(
                onDismissRequest = { showChapterSearch = false },
                title = "章内搜索",
                subtitle = chapter?.title.orEmpty(),
            ) {
                TechTextField(
                    value = chapterSearchQuery,
                    onValueChange = { chapterSearchQuery = it },
                    placeholder = "输入要找的词",
                )
                Spacer(Modifier.height(8.dp))
                if (chapterSearchQuery.isNotBlank()) {
                    Text(
                        if (matches.isEmpty()) "本章没有找到" else "找到 ${matches.size} 处，点一条跳过去",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    matches.take(50).forEachIndexed { mi, off ->
                        val q = chapterSearchQuery.trim()
                        val start = (off - 12).coerceAtLeast(0)
                        val end = (off + q.length + 12).coerceAtMost(content.length)
                        Text(
                            "…${content.substring(start, end).replace("\n", " ")}…",
                            fontSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showChapterSearch = false
                                    scope.launch {
                                        if (pageMode == 0) {
                                            val linePx = with(density) { (fontSize * lineHeightRatio).sp.toPx() }
                                            val cpl = charsPerLine.coerceAtLeast(1)
                                            scrollState.scrollTo((off / cpl.toFloat() * linePx).toInt().coerceAtLeast(0))
                                        } else {
                                            pagerState.scrollToPage(NovelPaginator.pageForOffset(pages, off))
                                        }
                                    }
                                    AppToast.show(context, "第 ${mi + 1} / ${matches.size} 处", long = false)
                                }
                                .padding(vertical = 6.dp),
                        )
                    }
                    if (matches.size > 50) {
                        Text("只列出前 50 处", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
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
                        .nestedScroll(pagerBoundaryScroll)
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

        // v0.242.0：沉浸页脚（对标图 1 底部）——沉浸时正文底下只留
        // 一行小字：章节名 + 页码/全书进度；点一下正文就出图 2 那套
        // chrome（顶栏 + 悬浮圆钮 + 底栏），再点收回。
        if (immersive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    chapter?.title ?: "",
                    color = fg.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (pageMode == 0) {
                        val max = scrollState.maxValue
                        "本章 " + (if (max > 0) scrollState.value * 100 / max else 0) + "%"
                    } else {
                        "${pagerState.currentPage + 1}/${pagerState.pageCount}  $progressPct%"
                    },
                    color = fg.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }

        // 底栏：阅读式底菜单（v0.211.0 去重版，v0.242.0 对标图 2 改为
        // 第一行 上一章 / 进度条 / 下一章，第二行 目录 / 界面 / 设置；
        // 211 删掉的悬浮钮按图 2 加回来，改白色圆钮贴左下竖排）。
        // 朗读按用户定不做，故不占位；亮度仍在设置面板里调。
        if (!immersive) {
            ReaderBottomBar(
                fg = fg,
                chapterIndex = chapterIndex,
                chapterCount = chapters.size,
                pageMode = pageMode,
                scrollState = scrollState,
                pagerState = pagerState,
                // v0.233.0：上一章必须回上一章尾页——音量键/滚动越界两路早已用
                // 1_000_000_000 哨兵，唯独这个按钮漏了（一直落章首），补齐。
                onPrevChapter = { jumpTo(chapterIndex - 1, 1_000_000_000) },
                onNextChapter = { jumpTo(chapterIndex + 1) },
                onShowToc = { showToc = true },
                onOpenSettings = { openSettings() },
                onOpenDisplaySettings = { openDisplaySettings() },
            )
        }
    }

    // 界面面板（v0.242.0：图 2 底栏“界面”）——只放显示组（字号/
    // 字体/行距/背景），与设置面板分开；同样贴底、最高占屏高 45%、
    // 无遮罩，改动实时映在上面的正文里。
    if (showDisplaySettings) {
        BackHandler { closeDisplaySettings() }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.45f).dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp, 4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val previewText = chapter?.content
                        ?.split("\n")
                        ?.firstOrNull { it.isNotBlank() }
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: chapter?.title.orEmpty()
                    Text(
                        previewText.ifBlank { "暂无正文预览" },
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
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { closeDisplaySettings() }) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { closeDisplaySettings() }) {
                        Text("完成")
                    }
                }
            }
        }
    }

    // 设置面板（v0.241.0：195 的居中大弹窗改回底部面板——用户要边调
    // 边看正文变化。贴底悬浮、最高只占屏高 45%、无遮罩，正文区
    // 全程可见且实时跟随字号/字体/行距/背景；内容超高时面板内滚，
    // 不再把整页盖住。完成/取消都在面板底行，返回键同样关闭。
    // v0.242.0：显示组拆到界面面板，这里只剩阅读组 + 管理组。）
    if (showSettings) {
        BackHandler { closeSettings() }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.45f).dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp, 4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { closeSettings() }) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { closeSettings() }) {
                        Text("完成")
                    }
                }
            }
        }
    }
    // 悬浮圆钮（v0.242.0：对标图 2 左侧竖排白圆钮）——只在 chrome
    // 展开且无面板时出现，贴左下、在底栏之上：章内搜索、刷新、夜间。
    // 亮度仍在设置面板里调，不另起入口；本地书无链接可拉，不摆刷新。
    if (!immersive && !showSettings && !showDisplaySettings) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReaderFloatButton(Icons.Filled.Search, "章内搜索") { showChapterSearch = true }
            if (novel?.sourceKey != "local") {
                ReaderFloatButton(Icons.Filled.Refresh, "刷新") { refreshChapter() }
            }
            ReaderFloatButton(Icons.Filled.DarkMode, "夜间") { toggleNight() }
        }
    }
    } // v0.241.0：根 Box 闭合

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

    }

/** v0.242.0：悬浮圆钮（图 2 左侧白圆钮）——白底细边圆钮，贴左下竖排。 */
@Composable
private fun ReaderFloatButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
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
            .padding(horizontal = 7.dp, vertical = 4.dp),
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
            modifier = Modifier.width(44.dp),
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
            .size(24.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
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

/**
 * 阅读器底栏——第一行 上一章 / 进度条 / 下一章，第二行 目录 / 设置。
 * v0.240.0：滑杆改回本章进度——v0.239.0 把它改成全本位置，
 * 是理解错了用户原话（用户要的是本章进度，不是全本）。
 * 本版：滑杆即本章进度（滚动模式取滚动比例，翻页模式取当前页比例），
 * 拖动松手只在本章内定位，不跨章；换章仍走上一章/下一章按钮。
 * 单抽成独立 composable：章内滚动每像素都变 scrollState.value，只重组底栏这一小块，
 * 不触发整页重组。
 */
@Composable
private fun ReaderBottomBar(
    fg: Color,
    chapterIndex: Int,
    chapterCount: Int,
    pageMode: Int,
    scrollState: ScrollState,
    pagerState: PagerState,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onShowToc: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDisplaySettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 拖动进度条只跟手，松手才定位，避免拖动中反复加载
    var sliderPos by remember { mutableStateOf<Float?>(null) }
    val chapterFraction = if (pageMode == 0) {
        val max = scrollState.maxValue
        if (max > 0) scrollState.value.toFloat() / max else 0f
    } else {
        val pc = pagerState.pageCount
        if (pc > 1) pagerState.currentPage.toFloat() / (pc - 1) else 0f
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        // v0.240.0：底栏精细化——原来是两排裸文字按钮加一根深色粗滑杆，
        // 糊在正文底下没分隔，看着生硬；现加顶部细分隔线、滑杆改柔和
        // 轨道色、上下章加小箭头、设置补图标，底栏自成一块。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(fg.copy(alpha = 0.08f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onPrevChapter,
                enabled = chapterIndex > 0,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ChevronLeft, "上一章", tint = fg, modifier = Modifier.size(18.dp))
                    Text("上一章", color = fg)
                }
            }
            Slider(
                value = sliderPos ?: chapterFraction,
                onValueChange = { sliderPos = it },
                onValueChangeFinished = {
                    sliderPos?.let { frac ->
                        scope.launch {
                            if (pageMode == 0) {
                                val max = scrollState.maxValue
                                scrollState.scrollTo((frac * max).toInt().coerceIn(0, max))
                            } else {
                                val pc = pagerState.pageCount
                                if (pc > 1) pagerState.scrollToPage((frac * (pc - 1)).roundToInt().coerceIn(0, pc - 1))
                            }
                        }
                    }
                    sliderPos = null
                },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = fg.copy(alpha = 0.15f),
                ),
            )
            TextButton(
                onClick = onNextChapter,
                enabled = chapterIndex < chapterCount - 1,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("下一章", color = fg)
                    Icon(Icons.Filled.ChevronRight, "下一章", tint = fg, modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onShowToc) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.List, "目录", tint = fg, modifier = Modifier.padding(end = 4.dp))
                    Text("目录", color = fg)
                }
            }
            // v0.242.0：图 2 底栏第二排是 目录 / 朗读 / 界面 / 设置；
            // 朗读按用户定不做，界面用 Aa 字样（与图 2 一致），开显示面板。
            TextButton(onClick = onOpenDisplaySettings) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Aa", color = fg, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))
                    Text("界面", color = fg)
                }
            }
            TextButton(onClick = onOpenSettings) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Settings, "设置", tint = fg, modifier = Modifier.padding(end = 4.dp))
                    Text("设置", color = fg)
                }
            }
        }
    }
}
