package com.movieshell.ui.reader

import android.app.Activity
import android.graphics.Typeface
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.model.Chapter
import com.movieshell.data.model.Novel
import com.movieshell.data.model.ReadingProgress
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.ChapterMeta
import com.movieshell.data.novel.NovelPurifyEngine
import com.movieshell.data.novel.NovelSourceEngine
import com.movieshell.data.novel.PurifyRuleStore
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.repo.Bookmark
import com.movieshell.data.repo.BookmarkStore
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.NovelStore
import com.movieshell.data.repo.ReaderSettings
import com.movieshell.data.repo.ReadingProgressStore
import com.movieshell.data.repo.ReadingStatsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
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

/**
 * v0.244.0：定位目标（目录/书签/滑杆/上下章/恢复共用）。
 * Offset = 章内字符位置；Title = 章首（标题锚点）；LastText = 上一章末页
 * （该章最后一个正文字符所在的全局页）。三者分开定义、分开映射，不混用。
 */
private sealed class LandingTarget {
    abstract val chapter: Int
    data class Offset(override val chapter: Int, val offset: Int) : LandingTarget()
    data class Title(override val chapter: Int) : LandingTarget()
    data class LastText(override val chapter: Int) : LandingTarget()
}

@Composable
fun ReaderScreen(
    novelId: String,
    onBack: () -> Unit = {},
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
    // v0.250.0：失败原因与错误态同进退，错误区标题与 Toast 用同一套短提示。
    var contentLoadReason by remember { mutableStateOf<NovelSourceEngine.ContentFail?>(null) }
    // v0.251.0：会话内失败记忆——自动加载不再重拉失败章，手动重试才解除；离开阅读器即清零。
    val failedChapters = remember { mutableStateMapOf<Int, NovelSourceEngine.ContentFail>() }
    // v0.251.0：净化后空白时的原文暂存——仅本次会话临时查看用，绝不写盘。
    var purifyFallbackText by remember { mutableStateOf<String?>(null) }
    var purifyFallbackIndex by remember { mutableIntStateOf(-1) }
    // v0.72.0：音量键翻页
    var volumePageTurn by remember { mutableStateOf(savedSettings.volumePageTurn) }
    var pageHeight by remember { mutableIntStateOf(0) }
    // v0.89.0：翻页模式分页用正文区宽
    var pageWidth by remember { mutableIntStateOf(0) }

    // v0.244.0：连续阅读——视口状态（两种模式统一）：视口顶部所在章节 + 该章字符偏移。
    // chapterIndex 跟随视口走；进度保存/书签/底栏/顶栏都读这一组，不再各算各的。
    var viewportChapter by remember { mutableIntStateOf(0) }
    var viewportOffset by remember { mutableIntStateOf(0) }
    // v0.244.0：定位请求（目录/书签/滑杆/上下章/恢复共用）：发请求只立 landing，
    // 落点由履行效果在内容与排版就绪、滚动停稳后执行；用户中途换目标直接覆盖。
    var landing by remember { mutableStateOf<LandingTarget?>(null) }
    // v0.244.0：全局分页布局版本——字号/行距/字体/屏宽高/刷新本章时 +1，
    // 分页整体重建，落点按视口章节 + 偏移重新定位。
    var layoutVersion by remember { mutableIntStateOf(0) }
    // v0.244.0：滚动模式布局缓存（非 state，不触发重组）：章内正文布局与标题高度，
    // 供视口换算与精确定位校准用；内容一变 onTextLayout 就会刷新。
    val chapterLayouts = remember { mutableMapOf<Int, TextLayoutResult>() }
    val titleHeights = remember { mutableMapOf<Int, Float>() }
    // v0.244.0：加载中章节集合（去重用）：同一章并发只拉一次。
    val loadingChapters = remember { mutableSetOf<Int>() }
    var locateJob by remember { mutableStateOf<Job?>(null) }

    // v0.89.0：书签
    var showBookmarks by remember { mutableStateOf(false) }
    var bookmarks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    // v0.215.0：章内搜索（本章正文内找词、点结果跳转；滚动模式按恢复同款公式换算像素，翻页模式按页首偏移跳页）。
    var showChapterSearch by remember { mutableStateOf(false) }
    var chapterSearchQuery by remember { mutableStateOf("") }
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
    var saveJob by remember { mutableStateOf<Job?>(null) }
    // 注意：局部 fun 必须先声明后调用，故放在 LaunchedEffect 之前
    /** v0.250.0：正文失败原因→用户提示（短句，诊断细节进日志，不猜限流）。 */
    fun failText(f: NovelSourceEngine.ContentFail): String = when (f) {
        NovelSourceEngine.ContentFail.VERIFY -> "当前章节需要验证，请完成验证后重试"
        NovelSourceEngine.ContentFail.TIMEOUT -> "正文加载超时，请稍后重试"
        NovelSourceEngine.ContentFail.DNS -> "网络连接异常，请检查网络后重试"
        NovelSourceEngine.ContentFail.FORBIDDEN -> "章节暂时无法访问，请稍后重试"
        NovelSourceEngine.ContentFail.PARSE_EMPTY -> "正文解析失败，可重试或反馈问题"
        NovelSourceEngine.ContentFail.PURIFY_EMPTY -> "正文净化后为空，可能是净化规则过宽"
        else -> "正文加载失败，请点击重试"
    }

    /** v0.72.0：拉取章节正文并写库（纯 IO，不碰 UI 状态），返回失败原因，null=成功。
     * v0.250.0：allowGate 区分主动读与预加载——预加载绝不弹验证页。
     * v0.251.0：force 供刷新本章绕过“已有正文跳过”；净化后空白不写盘、不覆盖旧正文。 */
    suspend fun fetchChapter(index: Int, allowGate: Boolean, force: Boolean = false): NovelSourceEngine.ContentFail? {
        val c = chapters.getOrNull(index) ?: return NovelSourceEngine.ContentFail.UNKNOWN
        if ((!force && c.content.isNotBlank()) || c.url.isBlank()) return null
        val n = novel ?: return NovelSourceEngine.ContentFail.UNKNOWN
        val result = runCatching {
            if (n.sourceKey.startsWith(PyBookSourceManager.SOURCE_KEY_PREFIX)) {
                val t = NovelSourceEngine.contentPy(context, ChapterMeta(index, c.title, c.url), n.sourceKey)
                if (t.isNotBlank()) NovelSourceEngine.ContentResult.Ok(t)
                else NovelSourceEngine.ContentResult.Failed(NovelSourceEngine.ContentFail.UNKNOWN)
            } else {
                val source = BookSourceManager.sources.value
                    .firstOrNull { it.key == n.sourceKey }
                    ?: return@runCatching NovelSourceEngine.ContentResult.Failed(NovelSourceEngine.ContentFail.UNKNOWN)
                NovelSourceEngine.content(source, ChapterMeta(index, c.title, c.url), allowGate)
            }
        }.onFailure { if (it is CancellationException) throw it }
            .getOrElse { NovelSourceEngine.ContentResult.Failed(NovelSourceEngine.ContentFail.UNKNOWN) }
        val text = when (result) {
            is NovelSourceEngine.ContentResult.Ok -> result.text
            is NovelSourceEngine.ContentResult.Failed -> return result.reason
        }
        if (text.isNotBlank()) {
            // v0.95.0：正文统一过净化引擎再入库（规则见净化规则管理页）
            val rules = PurifyRuleStore.list(context)
            val clean = NovelPurifyEngine.purify(text, rules, bookUrl = n.id, sourceKey = n.sourceKey)
            // v0.251.0：净化后空白不写盘、不覆盖旧正文——空白写盘是重复拉取循环的根因；
            // 原文只暂存本次会话，查看原文入口用，绝不写回缓存。
            if (clean.isBlank()) {
                LogBus.d("Reader", "净化后空白不写盘 chapter=$index rawLen=${text.length}")
                purifyFallbackText = text
                purifyFallbackIndex = index
                return NovelSourceEngine.ContentFail.PURIFY_EMPTY
            }
            NovelStore.updateChapterContent(context, n.id, index, clean)
            chapters = chapters.map { if (it.index == index) it.copy(content = clean) else it }
            return null
        }
        return NovelSourceEngine.ContentFail.UNKNOWN
    }

    // v0.251.0：force 供刷新本章；onSuccess 供窗口加载在当前章成功后触发邻章预载。
    fun ensureContent(index: Int, force: Boolean = false, onSuccess: (() -> Unit)? = null) {
        val c = chapters.getOrNull(index) ?: return
        if ((!force && c.content.isNotBlank()) || c.url.isBlank()) return
        if (!force && failedChapters.containsKey(index)) return
        if (!loadingChapters.add(index)) return
        scope.launch {
            try {
                contentLoading = true
                contentLoadError = false
                contentLoadReason = null
                // v0.109.0 A7：fetchChapter 返回值别再丢掉——失败时 Toast + 置错误态给重试入口
                val fail = fetchChapter(index, allowGate = true, force = force)
                if (fail != null) {
                    // v0.250.0：任务已取消（用户已离开）时不置错误态、不弹 Toast。
                    if (!isActive) return@launch
                    failedChapters[index] = fail
                    contentLoadError = true
                    contentLoadReason = fail
                    AppToast.show(context, failText(fail), long = false)
                    // v0.244.0：正对着这章的定位请求别让用户空等——退回当前视口位置。
                    if (landing?.chapter == index) {
                        landing = LandingTarget.Offset(viewportChapter, viewportOffset)
                    }
                    return@launch
                }
                failedChapters.remove(index)
                onSuccess?.invoke()
            } finally {
                loadingChapters.remove(index)
                contentLoading = false
            }
        }
    }

    /** v0.244.0：静默拉一章（窗口预加载用）：不碰 contentLoading、不 Toast，失败就失败。
     * v0.251.0：失败章记入会话失败记忆，本次会话不再自动重拉。 */
    fun loadChapterSilent(index: Int) {
        val c = chapters.getOrNull(index) ?: return
        if (c.content.isNotBlank() || c.url.isBlank()) return
        if (failedChapters.containsKey(index)) return
        if (!loadingChapters.add(index)) return
        scope.launch {
            try {
                val fail = fetchChapter(index, allowGate = false)
                if (fail != null) failedChapters[index] = fail else failedChapters.remove(index)
            } finally {
                loadingChapters.remove(index)
            }
        }
    }

    /** v0.251.0：邻章静默预载（失败章、已加载章、加载中章自动跳过）。 */
    fun preloadWindow(center: Int, radius: Int = 2) {
        for (i in (center - radius)..(center + radius)) {
            if (i != center) loadChapterSilent(i)
        }
    }

    /** v0.244.0：窗口加载——当前章走 ensureContent（带失败反馈），前后各 2 章静默预载。
     * v0.251.0：当前章优先——当前章没好之前不启动新的预加载，当前章成功后再预载邻章；
     * 当前章已加载则直接预载邻章；失败章本次会话不再自动重试。 */
    fun ensureContentWindow(center: Int, radius: Int = 2) {
        val c = chapters.getOrNull(center)
        if (c != null && c.content.isNotBlank()) {
            preloadWindow(center, radius)
        } else {
            ensureContent(center) {
                preloadWindow(center, radius)
                // v0.251.0：加载中窗口又变了时，成功后也按最新视口补预载，不依赖过期窗口。
                if (chapterIndex != center) preloadWindow(chapterIndex, radius)
            }
        }
    }

    // v0.242.0：重读本章（图2顶栏/悬浮刷新钮）——强制重拉；页码/滚动位置不动
    // （恢复效果认领过本章会直接放行，不重定位）。本地 TXT 书没有可拉的链接，直接返回。
    // v0.251.0：不再先清正文——失败时保留旧正文＋Toast，不清空正文区。
    fun refreshChapter() {
        val idx = chapterIndex
        val c = chapters.getOrNull(idx) ?: return
        if (c.url.isBlank()) return
        failedChapters.remove(idx)
        contentLoadError = false
        contentLoadReason = null
        // v0.244.0：正文换了，全局分页整体重建（布局版本 +1），落点按视口重定位。
        layoutVersion++
        ensureContent(idx, force = true) { preloadWindow(chapterIndex) }
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
            landing = LandingTarget.Title(jump)
        } else if (p != null && p.chapterIndex in cs.indices) {
            chapterIndex = p.chapterIndex
            // v0.244.0：offset 0 就是章首（标题可见），大于 0 才按字符偏移落点。
            landing = if (p.charOffset <= 0) LandingTarget.Title(p.chapterIndex)
                else LandingTarget.Offset(p.chapterIndex, p.charOffset)
        }
        loaded = true
        ensureContentWindow(chapterIndex)
    }

    val chapter = chapters.getOrNull(chapterIndex)

    // v0.244.0：连续阅读——滚动模式用 LazyColumn 连续流，不再按章切页；
    // 翻页模式用全局分页（见 ContinuousBookLayout），页表来自实测排版。
    val density = LocalDensity.current
    val continuousListState = rememberLazyListState()
    // 正文区内边距与旧分支一致：左右 20dp、上下 12dp。
    val bodyStyle = TextStyle(
        fontSize = fontSize.sp,
        lineHeight = (fontSize * lineHeightRatio).sp,
        fontFamily = readerFont,
    )
    val titleStyle = TextStyle(
        fontSize = (fontSize + 2).sp,
        fontWeight = FontWeight.Bold,
    )
    val titleBottomPx = with(density) { 16.dp.toPx() }
    val innerWidthPx = (pageWidth - with(density) { 40.dp.toPx() }).toInt().coerceAtLeast(0)
    val innerHeightPx = (pageHeight - with(density) { 24.dp.toPx() }).coerceAtLeast(0f)
    // v0.194.0追加：一屏步长（屏高减一行）——音量键与点击分区共用。
    val pageStep = remember(pageHeight, fontSize, lineHeightRatio, density) {
        val linePx = with(density) { (fontSize * lineHeightRatio).sp.toPx() }.toInt()
        (pageHeight - linePx).coerceAtLeast(pageHeight / 2)
    }
    var pagination by remember { mutableStateOf(BookPagination.empty(0)) }
    val pagerState = rememberPagerState(pageCount = { pagination.displayPageCount.coerceAtLeast(1) })
    val textMeasurer = rememberTextMeasurer()
    // v0.244.0：全局分页增量构建（只翻页模式跑）：从 frontier 起每帧排 5 章，
    // 遇到未到正文的章先静默拉取、等正文到了再续；样式/尺寸/布局版本一变
    // 就整体重建（key 对不上时先清零再排，不把新旧样式的页混在一起）。
    var paginationBuiltKey by remember { mutableStateOf("") }
    LaunchedEffect(pageMode, layoutVersion, pageWidth, pageHeight, fontSize, lineHeightRatio, readerFont, chapters) {
        if (pageMode == 0) return@LaunchedEffect
        val key = "$fontSize|$lineHeightRatio|$fontFamily|$pageWidth|$pageHeight|$layoutVersion"
        if (paginationBuiltKey != key) {
            pagination = BookPagination.empty(layoutVersion)
            paginationBuiltKey = key
        }
        while (true) {
            val cur = pagination
            if (cur.fullyDone || innerWidthPx <= 0 || innerHeightPx <= 0f) break
            val next = chapters.getOrNull(cur.frontierChapter) ?: break
            if (next.content.isBlank() && next.url.isNotBlank()) {
                loadChapterSilent(next.index)
                break
            }
            pagination = paginateChapterRange(
                measurer = textMeasurer,
                chapters = chapters,
                base = cur,
                maxChapters = 5,
                bodyStyle = bodyStyle,
                titleStyle = titleStyle,
                titleBottomPx = titleBottomPx,
                innerWidthPx = innerWidthPx,
                innerHeightPx = innerHeightPx,
            )
            withFrameNanos { }
        }
    }
    // v0.247.0（DS20）：翻页模式末页点下一页不再静默吞掉——排版未完
    // 时记下待翻页，页数一长出来就自动翻过去；排版已完（真书末）或
    // 用户已手动翻页时清除，不乱跳。
    // 第 2 轮：currentPage 必须进 key——只在效果内读它不会订阅变化，
    // 手动翻页后待翻页要立刻失效，不能等到别的 key 变化才清除；
    // 自动翻页先把待翻页清零再翻，不会把自己的翻页误判成手动操作。
    // 第 3 轮：isScrollInProgress 也进 key——滚动中效果只提前返回不清除，
    // 滚动结束本身必须触发重跑，否则停在原页且无其他 key 变化时待翻页
    // 永远等不到结算；滚动结束重跑后按页码比对决定清除还是翻页。
    var pendingPageDownFrom by remember { mutableIntStateOf(-1) }
    LaunchedEffect(pageMode, pagination.displayPageCount, pagination.fullyDone, pendingPageDownFrom, pagerState.currentPage, pagerState.isScrollInProgress) {
        if (pageMode == 0 || pendingPageDownFrom < 0) {
            pendingPageDownFrom = -1
            return@LaunchedEffect
        }
        if (pagerState.isScrollInProgress) return@LaunchedEffect
        if (pagerState.currentPage != pendingPageDownFrom) {
            pendingPageDownFrom = -1
            return@LaunchedEffect
        }
        if (pagerState.currentPage < (pagerState.pageCount - 1).coerceAtLeast(0)) {
            pendingPageDownFrom = -1
            pagerState.animateScrollToPage(pagerState.currentPage + 1)
        } else if (pagination.fullyDone) {
            // 排完了仍停在待翻页：真书末，清除
            pendingPageDownFrom = -1
        }
        // 否则仍在末页且排版未完：留着待翻页，等下一批页
    }
    // v0.244.0：显示条件一变（字号/行距/字体/屏尺寸），布局版本 +1——分页
    // 整体重建，落点按视口重新定位（下面的 layoutVersion 效果接管）。
    LaunchedEffect(fontSize, lineHeightRatio, fontFamily, pageWidth, pageHeight) {
        if (loaded) layoutVersion++
    }

    /** v0.244.0：当前阅读位置换算成字符偏移（进度保存/书签用，两种模式统一）。
     * 视口状态就是唯一事实源：滚动模式由连续流视口跟踪写，翻页模式由
     * 全局页切换写，不再用像素比例或单章页码反推。 */
    fun computeOffset(): Int = viewportOffset


    /** v0.244.0：滚动模式章首定位：把目标章顶（标题）带到视口顶。 */
    suspend fun scrollToChapterTop(index: Int) {
        continuousListState.scrollToItem(index)
        var frames = 0
        while (frames < 30) {
            val hasItem = continuousListState.layoutInfo.visibleItemsInfo.any { it.index == index }
            if (hasItem) break
            withFrameNanos { }
            frames++
        }
    }

    // v0.244.0：定位履行——滚动模式：目标章正文未到先拉取，等到了再精确定位。
    // 滚动停稳才落点（241/243 的守门原则保留）；新请求直接覆盖旧请求。
    LaunchedEffect(landing, pageMode, loaded, chapters, continuousListState.isScrollInProgress) {
        val target = landing ?: return@LaunchedEffect
        if (!loaded || pageMode != 0) return@LaunchedEffect
        if (continuousListState.isScrollInProgress) return@LaunchedEffect
        val tc = chapters.getOrNull(target.chapter) ?: return@LaunchedEffect
        if (tc.content.isBlank()) {
            if (tc.url.isNotBlank()) ensureContentWindow(target.chapter)
            return@LaunchedEffect
        }
        locateJob?.cancel()
        locateJob = scope.launch {
            val len = tc.content.length
            val linePx = with(density) { (fontSize * lineHeightRatio).sp.toPx() }
            val ok = when (target) {
                is LandingTarget.Offset -> if (target.offset <= 0) {
                    scrollToChapterTop(target.chapter)
                    true
                } else {
                    calibrateScrollToChar(
                        continuousListState, target.chapter, target.offset, len,
                        { chapterLayouts[it] }, { titleHeights[it] ?: 0f }, 0f,
                    )
                }
                is LandingTarget.Title -> {
                    scrollToChapterTop(target.chapter)
                    true
                }
                is LandingTarget.LastText -> calibrateScrollToChar(
                    continuousListState, target.chapter, len, len,
                    { chapterLayouts[it] }, { titleHeights[it] ?: 0f },
                    (pageHeight - linePx).coerceAtLeast(0f),
                )
            }
            if (ok) landing = null
        }
    }

    // v0.244.0：定位履行——翻页模式：目标页没排出来先等（分页效果在向后排，
    // 目标章正文未到时先拉取）；滚动停稳才跳页；布局版本对不上时等重建。
    LaunchedEffect(landing, pageMode, loaded, pagination.displayPageCount, pagination.frontierChapter, pagination.fullyDone, pagerState.isScrollInProgress) {
        val target = landing ?: return@LaunchedEffect
        if (!loaded || pageMode == 0) return@LaunchedEffect
        if (pagerState.isScrollInProgress) return@LaunchedEffect
        if (pagination.layoutVersion != layoutVersion) return@LaunchedEffect
        val page = when (target) {
            is LandingTarget.Offset -> pagination.pageIndexForPosition(target.chapter, target.offset)
            is LandingTarget.Title -> pagination.titlePage(target.chapter)
            is LandingTarget.LastText -> pagination.lastTextPage(target.chapter)
        }
        if (page == null) {
            val tc = chapters.getOrNull(target.chapter)
            if (tc != null && tc.content.isBlank() && tc.url.isNotBlank()) {
                ensureContentWindow(target.chapter)
            }
            return@LaunchedEffect
        }
        pagerState.scrollToPage(page)
        // 落点后视口直接跟新页走，不依赖页码变化触发跟随效果。
        pagination.displayPages.getOrNull(page)?.firstPosition()?.let { (ch, off) ->
            viewportChapter = ch
            viewportOffset = off
            chapterIndex = ch
        }
        landing = null
    }

    // v0.244.0：布局版本一变（字号/行距/字体/屏尺寸/刷新本章），按视口章节 +
    // 偏移重新定位——分页重建后履行效果按新布局落点，滚动模式同理校准。
    LaunchedEffect(layoutVersion) {
        if (!loaded) return@LaunchedEffect
        if (landing != null) return@LaunchedEffect
        val ch = viewportChapter
        if (ch !in chapters.indices) return@LaunchedEffect
        landing = if (viewportOffset <= 0) LandingTarget.Title(ch)
            else LandingTarget.Offset(ch, viewportOffset)
    }

    // v0.244.0：翻页 2 秒 debounce 自动存进度（只翻页模式）：页一切换就重计时，
    // 存的是视口章节 + 偏移（全局页只是排版结果，不进进度文件）。
    LaunchedEffect(pagerState.currentPage) {
        if (pageMode == 0 || !loaded) return@LaunchedEffect
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2000)
            if (pageMode == 0 || !loaded) return@launch
            ReadingProgressStore.save(
                context,
                ReadingProgress(novelId, viewportChapter, viewportOffset, chapterTitle = chapters.getOrNull(viewportChapter)?.title.orEmpty()),
            )
        }
    }

    // v0.244.0：滚动停止即存（只滚动模式）：isScrollInProgress 一次手势只变两次，
    // 不拿滚动像素当 key，避免每滚一像素就重组。
    LaunchedEffect(continuousListState.isScrollInProgress) {
        if (pageMode != 0 || continuousListState.isScrollInProgress || !loaded || chapter == null) return@LaunchedEffect
        ReadingProgressStore.save(
            context,
            ReadingProgress(novelId, viewportChapter, viewportOffset, chapterTitle = chapters.getOrNull(viewportChapter)?.title.orEmpty()),
        )
    }

    // v0.244.0：翻页模式视口跟随全局页：页一切换，视口章节 + 偏移就取该页
    // 首个片段位置；定位进行中（landing 非空）时不抢，由履行效果统一落点。
    LaunchedEffect(pagerState.currentPage) {
        if (pageMode == 0 || landing != null) return@LaunchedEffect
        val page = pagination.displayPages.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        val (ch, off) = page.firstPosition()
        viewportChapter = ch
        viewportOffset = off
        chapterIndex = ch
    }

    // v0.244.0：视口章节一切换就保证前后 2 章正文在手（滚动模式跟随手指，
    // 翻页模式跟随页码；定位目标章由 locate 另外保证）。
    LaunchedEffect(chapterIndex, loaded) {
        if (!loaded) return@LaunchedEffect
        ensureContentWindow(chapterIndex)
    }

    /** v0.244.0：定位总入口（目录/书签/滑杆/上下章/恢复共用）：只立请求 + 即时存进度，落点归履行效果。 */
    fun locate(target: LandingTarget) {
        if (target.chapter !in chapters.indices) return
        // 即时存进度（旧 jumpTo 同款）：目标章位置先写盘，落点随后跟上。
        val len = chapters.getOrNull(target.chapter)?.content?.length ?: 0
        val off = when (target) {
            is LandingTarget.Offset -> target.offset.coerceIn(0, len)
            is LandingTarget.Title -> 0
            is LandingTarget.LastText -> len
        }
        scope.launch {
            ReadingProgressStore.save(
                context,
                ReadingProgress(novelId, target.chapter, off, chapterTitle = chapters.getOrNull(target.chapter)?.title.orEmpty()),
            )
        }
        landing = target
        showToc = false
        showBookmarks = false
        ensureContentWindow(target.chapter)
    }

    /** offset 0 就是章首（标题可见），大于 0 按字符偏移落点。 */
    fun locateChapterOffset(chapter: Int, offset: Int) {
        if (offset <= 0) locate(LandingTarget.Title(chapter))
        else locate(LandingTarget.Offset(chapter, offset))
    }

    /** 上一章按钮：回上一章末页（233 定论，GPT 第三轮的标题页说法已纠正）。 */
    fun locatePrevChapter() {
        if (chapterIndex > 0) locate(LandingTarget.LastText(chapterIndex - 1))
    }

    /** 下一章按钮：进下一章章首（标题锚点）。 */
    fun locateNextChapter() {
        if (chapterIndex < chapters.size - 1) locate(LandingTarget.Title(chapterIndex + 1))
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


    /** v0.194.0追加：统一翻页动作——音量键与点击分区共用同一套逻辑。 */
    fun pageUp() {
        if (pageMode == 0) {
            val info = continuousListState.layoutInfo
            val first = info.visibleItemsInfo.minByOrNull { it.offset }
            val atTop = first == null || (first.index == 0 && first.offset >= 0)
            if (atTop) {
                locatePrevChapter()
            } else {
                scope.launch { continuousListState.scroll { scrollBy(-pageStep.toFloat()) } }
            }
        } else {
            if (pagerState.currentPage > 0) {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
            }
            // 翻页模式已是整本全局页：第 0 页就是书首，停住，不再跨章跳。
        }
    }

    fun pageDown() {
        if (pageMode == 0) {
            val info = continuousListState.layoutInfo
            val last = info.visibleItemsInfo.maxByOrNull { it.offset }
            val atEnd = last != null && last.index == chapters.size - 1 &&
                last.offset + last.size <= info.viewportSize.height
            if (atEnd) {
                locateNextChapter()
            } else {
                scope.launch { continuousListState.scroll { scrollBy(pageStep.toFloat()) } }
            }
        } else {
            if (pagerState.currentPage < (pagerState.pageCount - 1).coerceAtLeast(0)) {
                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            } else if (!pagination.fullyDone) {
                // 排版未完的末页：这次按压记下待翻页，页一排出来就自动翻，
                // 不再让用户对着没反应的末页空按。
                pendingPageDownFrom = pagerState.currentPage
            }
            // 已到已确认末页且排版已完：全书排完就是书末，停住。
        }
    }

    /** v0.194.0追加：开设置时先把顶底栏叫出来（设置面板本身就是菜单），关时恢复原样。 */
    /** v0.195.0：194 的点击分区已撤掉（点按恢复只出菜单），onContentTap 随之删除。 */
    // v0.251.0：两面板互斥＋只在“都没开→开一个”时保存沉浸态——双面板叠开会覆盖
    // wasImmersiveBeforeSettings，关完恢复不到原来的沉浸态（复核必改条件）。
    fun openSettings() {
        if (!showSettings && !showDisplaySettings) wasImmersiveBeforeSettings = immersive
        showDisplaySettings = false
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
    // v0.251.0：同上，互斥＋只在都没开时保存沉浸态。
    fun openDisplaySettings() {
        if (!showSettings && !showDisplaySettings) wasImmersiveBeforeSettings = immersive
        showSettings = false
        immersive = false
        showDisplaySettings = true
    }
    fun closeDisplaySettings() {
        showDisplaySettings = false
        immersive = wasImmersiveBeforeSettings
    }

    // v0.251.0：面板打开时点正文先收面板，不切换沉浸式栏；面板关了再点才切换。
    fun onContentTap() {
        when {
            showDisplaySettings -> closeDisplaySettings()
            showSettings -> closeSettings()
            else -> immersive = !immersive
        }
    }

    // v0.245.0：悬浮夜间钮已删——夜间只走背景档里的夜间黑/深灰/墨绿，
    // toggleNight 随悬浮钮一并删除，不再留第二条夜间入口。

    // v0.72.0：音量键翻页注册（开着才接管音量键，离开页面/关掉即释放）
    // v0.89.0：翻页模式下音量键改为前后翻页
    // v0.193.0：滚动模式按页滚时留一行重叠（上一页末行 = 下一页首行），
    // 免得翻页后接不上；步长随字号/行距/屏高变，故都进 key。
    // v0.194.0：章末/章首跨章——滚动模式到头/到底进上下章（上一章末页/
    // 下一章章首），翻页模式全局页到头/到底即停；handler 走 rememberUpdatedState，
    // 不用靠一长串 key 重注册。局部函数先声明后调用，故必须放在 pageUp 定义之后。
    // v0.244.0：音量键 handler 改走 rememberUpdatedState——pageUp/pageDown 每帧
    // 都是新闭包，旧写法靠一长串 key 重注册才拿到当前章，新写法注册一次即可。
    val latestPageUp by rememberUpdatedState { pageUp() }
    val latestPageDown by rememberUpdatedState { pageDown() }
    DisposableEffect(volumePageTurn) {
        if (volumePageTurn) {
            VolumeKeyRouter.onVolumeUp = { latestPageUp(); true }
            VolumeKeyRouter.onVolumeDown = { latestPageDown(); true }
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
            // v0.246.0：写盘失败要反馈——原来不管成败都提示“已添加书签”并刷新列表，
            // 失败时用户以为加上了，重启后书签没了。
            val ok = BookmarkStore.add(
                context,
                novelId,
                Bookmark(
                    chapterIndex = chapterIndex,
                    charOffset = computeOffset(),
                    chapterTitle = c.title,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            if (ok) {
                refreshBookmarks()
                AppToast.show(context, "已添加书签", long = false)
            } else {
                AppToast.show(context, "书签保存失败，请重试", long = false)
            }
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
        5 -> Color(0xFFDCE8EF)
        6 -> Color(0xFFD8D4CA)
        7 -> Color(0xFF20242B)
        8 -> Color(0xFFFBE9EC)
        9 -> Color(0xFFE9E4F3)
        10 -> Color(0xFF1E2B26)
        else -> Color(0xFFF7F3E8)
    }
    val fg = when (bgTheme) {
        2 -> Color(0xFF2E3B2E)
        3 -> Color(0xFFD8D8D8)
        4 -> if (followDark) Color(0xFFD8D8D8) else Color(0xFF2B2B2B)
        5 -> Color(0xFF263746)
        6 -> Color(0xFF34322E)
        7 -> Color(0xFFD9DEE7)
        8 -> Color(0xFF4A3238)
        9 -> Color(0xFF38324A)
        10 -> Color(0xFFD6E2D8)
        else -> Color(0xFF2B2B2B)
    }
    // v0.245.0：阅读背景的实际深浅——弹窗/面板跟它走，不跟系统主题走。
    // 深色档：3 夜间黑、7 深灰、10 墨绿；4 跟随系统档按系统深浅算。
    val isReaderDark = bgTheme == 3 || bgTheme == 7 || bgTheme == 10 ||
        (bgTheme == 4 && followDark)
    // 跟阅读背景走的配色副本：深浅与系统一致时原样用系统配色，不一致时
    // 只换底/字/次要字/描边/主按钮这几项，弹窗和面板不再发白或发黑。
    val readerScheme = if (isReaderDark == followDark) {
        MaterialTheme.colorScheme
    } else if (isReaderDark) {
        MaterialTheme.colorScheme.copy(
            background = Color(0xFF171B28),
            surface = Color(0xFF232839),
            surfaceVariant = Color(0xFF2E3342),
            onSurface = Color(0xFFE8EAF0),
            onSurfaceVariant = Color(0xFFB0B7C3),
            outline = Color(0xFF454B56),
            primary = Color(0xFF7AA7F7),
            onPrimary = Color(0xFF18243A),
        )
    } else {
        MaterialTheme.colorScheme.copy(
            background = Color.White,
            surface = Color.White,
            surfaceVariant = Color(0xFFF0F0F0),
            onSurface = Color(0xFF242424),
            onSurfaceVariant = Color(0xFF666666),
            outline = Color(0xFFE0E0E0),
        )
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
                forceDark = isReaderDark,
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
                forceDark = isReaderDark,
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
                                    locateChapterOffset(chapterIndex, off)
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
                            contentLoadReason = null
                            failedChapters.remove(chapterIndex)
                            ensureContent(chapterIndex) { preloadWindow(chapterIndex) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            contentLoadReason?.let { failText(it) } ?: "正文拉取失败",
                            color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (contentLoadReason == NovelSourceEngine.ContentFail.PURIFY_EMPTY &&
                            purifyFallbackIndex == chapterIndex && purifyFallbackText != null
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        contentLoadError = false
                                        contentLoadReason = null
                                        failedChapters.remove(chapterIndex)
                                        ensureContent(chapterIndex) { preloadWindow(chapterIndex) }
                                    },
                                ) {
                                    Text("点击重试", color = fg.copy(alpha = 0.8f), fontSize = 14.sp)
                                }
                                TextButton(
                                    onClick = {
                                        // v0.251.0：临时查看原文——只进内存显示，不写盘。
                                        val raw = purifyFallbackText ?: return@TextButton
                                        chapters = chapters.map {
                                            if (it.index == chapterIndex) it.copy(content = raw) else it
                                        }
                                        contentLoadError = false
                                        contentLoadReason = null
                                    },
                                ) {
                                    Text("查看原文（临时）", color = fg.copy(alpha = 0.8f), fontSize = 14.sp)
                                }
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    contentLoadError = false
                                    contentLoadReason = null
                                    failedChapters.remove(chapterIndex)
                                    ensureContent(chapterIndex) { preloadWindow(chapterIndex) }
                                },
                            ) {
                                Text("点击重试", color = fg.copy(alpha = 0.8f), fontSize = 14.sp)
                            }
                        }
                    }
                }
            } else if (pageMode == 0) {
                ContinuousReaderFlow(
                    chapters = chapters,
                    listState = continuousListState,
                    fontSize = fontSize,
                    lineHeightRatio = lineHeightRatio,
                    readerFont = readerFont,
                    fg = fg,
                    background = bg,
                    onToggleImmersive = { onContentTap() },
                    getBodyLayout = { chapterLayouts[it] },
                    getTitleHeight = { titleHeights[it] ?: 0f },
                    onChapterLayout = { idx, layout, titleH ->
                        chapterLayouts[idx] = layout
                        titleHeights[idx] = titleH
                    },
                    onViewportChanged = { idx, off ->
                        viewportChapter = idx
                        viewportOffset = off
                        chapterIndex = idx
                    },
                )
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onContentTap() })
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
                            // v0.244.0：全局页按片段渲染——标题与正文同属一页时
                            // 自然同页，标题不再只挂在每章第 0 页；定位进行中
                            // （landing 非空）或页表未建好时先显示加载态，别闪旧章。
                            val gpages = pagination.displayPages
                            if (landing != null || gpages.isEmpty()) {
                                Text(
                                    text = "正文加载中…",
                                    color = fg.copy(alpha = 0.6f),
                                    fontSize = 13.sp,
                                )
                            } else {
                                gpages.getOrNull(page)?.fragments?.forEach { frag ->
                                    val fch = chapters.getOrNull(frag.chapterIndex)
                                    if (frag.isTitle) {
                                        Text(
                                            text = fch?.title.orEmpty(),
                                            color = fg,
                                            fontSize = (fontSize + 2).sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(bottom = 16.dp),
                                        )
                                    } else {
                                        val content = fch?.content.orEmpty()
                                        val start = frag.startOffset.coerceIn(0, content.length)
                                        val end = frag.endOffset.coerceIn(start, content.length)
                                        Text(
                                            text = content.substring(start, end),
                                            color = fg,
                                            fontSize = fontSize.sp,
                                            lineHeight = (fontSize * lineHeightRatio).sp,
                                            fontFamily = readerFont,
                                        )
                                    }
                                }
                            }
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
                        val len = chapter?.content?.length ?: 0
                        "本章 " + (if (len > 0) viewportOffset * 100 / len else 0) + "%"
                    } else if (pagination.fullyDone) {
                        "${pagerState.currentPage + 1}/${pagerState.pageCount}  $progressPct%"
                    } else {
                        "${pagerState.currentPage + 1}/计算中  $progressPct%"
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
                chapterFraction = run {
                    val len = chapter?.content?.length ?: 0
                    if (len > 0) (viewportOffset.toFloat() / len).coerceIn(0f, 1f) else 0f
                },
                // v0.233.0：上一章必须回上一章尾页（244 起走 LAST_TEXT 锚点，不再用哨兵）。
                onPrevChapter = { locatePrevChapter() },
                onNextChapter = { locateNextChapter() },
                onChapterFractionCommit = { frac ->
                    val len = chapter?.content?.length ?: 0
                    locateChapterOffset(chapterIndex, (frac * len).toInt())
                },
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
        // v0.245.0：面板跟阅读背景深浅走（readerScheme），不再跟系统主题。
        // 注意：Box 的 align 只能在根 Box 作用域里用，MaterialTheme 不能包
        // 在 Box 外面；所以面板底色直接取 readerScheme.surface，内容再包
        // MaterialTheme，胶囊和文字才跟着深浅走。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.45f).dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(readerScheme.surface),
        ) {
        MaterialTheme(
            colorScheme = readerScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
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
                    // 字体：八档，胶囊字本身就用对应字体渲染
                    ReaderFontRow(fontFamily) { fontFamily = it }
                    // 行距
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("行距", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        listOf(1.2f to "紧凑", 1.8f to "适中", 2.4f to "疏朗").forEachIndexed { i, (ratio, label) ->
                            if (i > 0) Spacer(Modifier.width(4.dp))
                            ReaderPill(label, lineHeightRatio == ratio) { lineHeightRatio = ratio }
                        }
                    }
                    // 背景：十一档，最后一档跟随系统（深浅渐变点）
                    ReaderBgRow(bgTheme) { bgTheme = it }
                }
                // v0.251.0：完成按钮去掉（设置即改即保存，完成只有关闭作用）；
                // 底部留 12dp 呼吸空间，点正文区收面板。
                Spacer(Modifier.height(12.dp))
            }
        }
        }
    }

    // 设置面板（v0.241.0：195 的居中大弹窗改回底部面板——用户要边调
    // 边看正文变化。贴底悬浮、最高只占屏高 45%、无遮罩，正文区
    // 全程可见且实时跟随字号/字体/行距/背景；内容超高时面板内滚，
    // 不再把整页盖住。完成在面板底行，返回键同样关闭。
    // v0.242.0：显示组拆到界面面板，这里只剩阅读组 + 管理组。
    // v0.245.0：面板跟阅读背景深浅走（readerScheme），同界面面板。）
    if (showSettings) {
        BackHandler { closeSettings() }
        // v0.245.0：同界面面板，align 只能在根 Box 作用域里，MaterialTheme
        // 包在 Box 里面，面板底色直接取 readerScheme.surface。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.45f).dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(readerScheme.surface),
        ) {
        MaterialTheme(
            colorScheme = readerScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
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
                    // v0.251.0：净化规则入口移到书架更多菜单，管理组随之整组删除。
                }
                // v0.251.0：完成按钮去掉（设置即改即保存，完成只有关闭作用）；
                // 底部留 12dp 呼吸空间，点正文区收面板。
                Spacer(Modifier.height(12.dp))
            }
        }
        }
    }
    // v0.245.0：左下悬浮圆钮已整体删除——搜索、刷新只留顶栏，夜间只走
    // 背景档；悬浮钮压正文左边缘的问题随之消失，不再留占位。
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
            forceDark = isReaderDark,
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
                                .clickable { locate(LandingTarget.Title(c.index)) }
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
            forceDark = isReaderDark,
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
                                        locateChapterOffset(b.chapterIndex, b.charOffset)
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
                                    // v0.246.0：删失败不从列表里去掉——原来不管成败都先去掉，
                                    // 失败时用户以为删了，刷新后书签又回来。
                                    if (BookmarkStore.remove(context, novelId, b.createdAt)) {
                                        bookmarks = bookmarks.filterNot { it.createdAt == b.createdAt }
                                    } else {
                                        AppToast.show(context, "删除书签失败，请重试", long = false)
                                    }
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

/** v0.245.0：悬浮圆钮已随去重删除，此处不再留占位组件。 */

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

/** v0.194.0追加：字体档位 → Compose 字体（系统自带五种，不用外挂字体文件）。
 * v0.245.0追加：5/6/7 走 Android 系统字体名（FontFamily(Typeface)，
 * ui-text 1.7.2  sources 已核实该构造存在），仍不打包字体文件；
 * 中文字形在部分机型上可能回退到默认中文字体，以真机为准。 */
private fun readerFontFamilyOf(v: Int): FontFamily = when (v) {
    0 -> FontFamily.Default
    2 -> FontFamily.Serif
    3 -> FontFamily.Monospace
    4 -> FontFamily.Cursive
    5 -> FontFamily(Typeface.create("sans-serif-light", Typeface.NORMAL))
    6 -> FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
    7 -> FontFamily(Typeface.create("sans-serif-medium", Typeface.NORMAL))
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
            listOf(
                0 to "系统默认", 1 to "无衬线", 2 to "衬线", 3 to "等宽", 4 to "手写",
                5 to "细体", 6 to "紧凑", 7 to "中粗",
            ).forEach { (v, label) ->
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
            ReaderPill("雾蓝", selected == 5, dotColor = Color(0xFFDCE8EF)) { onSelect(5) }
            ReaderPill("暖灰", selected == 6, dotColor = Color(0xFFD8D4CA)) { onSelect(6) }
            ReaderPill("深灰", selected == 7, dotColor = Color(0xFF20242B)) { onSelect(7) }
            ReaderPill("樱粉", selected == 8, dotColor = Color(0xFFFBE9EC)) { onSelect(8) }
            ReaderPill("淡紫", selected == 9, dotColor = Color(0xFFE9E4F3)) { onSelect(9) }
            ReaderPill("墨绿", selected == 10, dotColor = Color(0xFF1E2B26)) { onSelect(10) }
        }
    }
}

/**
 * 阅读器底栏——第一行 上一章 / 进度条 / 下一章，第二行 目录 / 设置。
 * v0.240.0：滑杆改回本章进度——v0.239.0 把它改成全本位置，
 * 是理解错了用户原话（用户要的是本章进度，不是全本）。
 * 本版：滑杆即本章进度（视口章节内比例，两种模式统一），
 * 拖动松手只在本章内定位，不跨章；换章仍走上一章/下一章按钮。
 * 单抽成独立 composable：视口状态只传给底栏这一小块，不触发整页重组。
 * v0.245.0：进度条改细轨道 + 轨道上小球——Material3 1.3.1 的默认 Slider
 * 是 16dp 粗轨道 + 4×44dp 竖线滑块（截图里那根竖线就是它），且带末尾
 * 圆点；现改走自定义 thumb/track：轨道 4dp、无刻度无末尾
 * 圆点、滑块 12dp 圆球居中在轨道上；拖动松手才定位的语义不变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderBottomBar(
    fg: Color,
    chapterIndex: Int,
    chapterCount: Int,
    chapterFraction: Float,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onChapterFractionCommit: (Float) -> Unit,
    onShowToc: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDisplaySettings: () -> Unit,
) {
    // 拖动进度条只跟手，松手才定位，避免拖动中反复加载。
    // v0.245.0 修复：SliderState.isDragging 是 Material3 内部属性，外部访问
    // 编译失败；改用公开的 value 版 Slider，自己记拖动值：拖动时显示
    // 拖动值、不被外部视口进度拽回去，松手才提交定位，语义与原版一致。
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val sliderValue = dragValue ?: chapterFraction
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
                value = sliderValue,
                onValueChange = { dragValue = it },
                onValueChangeFinished = {
                    dragValue?.let { onChapterFractionCommit(it) }
                    dragValue = null
                },
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = fg.copy(alpha = 0.15f),
                ),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                },
                track = { state ->
                    val frac = state.value.coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(fg.copy(alpha = 0.15f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(frac)
                                .height(4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                },
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
