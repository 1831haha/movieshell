package com.movieshell.ui.detail

import com.movieshell.data.source.PluginMarket
import com.movieshell.data.store.PluginStore
import com.movieshell.util.safeIndex

import androidx.compose.ui.draw.alpha
import com.movieshell.ui.theme.ok

import com.movieshell.data.store.FollowItem

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.semantics

import androidx.compose.foundation.layout.widthIn

import androidx.compose.foundation.layout.PaddingValues

import androidx.compose.material3.FilledTonalButton

import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight

import androidx.compose.material.icons.filled.Check

import androidx.compose.material.icons.filled.Tag

import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.FavoriteBorder

import androidx.compose.foundation.lazy.LazyRow

import com.movieshell.ui.theme.V5

import com.movieshell.ui.components.AppToast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.TechTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import coil.compose.AsyncImage
import com.movieshell.data.download.DownloadManager
import com.movieshell.domain.model.Episode
import com.movieshell.domain.model.Site
import com.movieshell.domain.model.Vod
import com.movieshell.domain.model.PlayLine
import com.movieshell.domain.model.VodDetail
import com.movieshell.data.source_mgr.DetailSiteRoute
import com.movieshell.data.store.DownloadSettings
import com.movieshell.data.store.DownloadTask
import com.movieshell.data.source_mgr.DrpySourceManager
import com.movieshell.data.store.FollowStore
import com.movieshell.data.store.PlayerSettings
import com.movieshell.data.store.SearchHistoryStore
import com.movieshell.core.log.LogBus
import com.movieshell.data.source.PhpCliEngine
import com.movieshell.data.store.SourceStateStore
import com.movieshell.data.source_mgr.PhpSourceManager
import com.movieshell.data.source_mgr.PySourceManager
import com.movieshell.data.source_mgr.WebParseSourceManager
import com.movieshell.data.source_mgr.SiteRepository
import com.movieshell.data.source_mgr.SiteSearchResult
import com.movieshell.core.util.StoragePaths
import android.app.Activity
import android.content.Context
import android.animation.ValueAnimator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.pm.ActivityInfo
import com.movieshell.player.MagnetPlayer
import com.movieshell.player.OrientationSensor
import com.movieshell.player.PlayResolveScope
import com.movieshell.player.PlaySession
import com.movieshell.player.PlayerGestureMath
import com.movieshell.player.PlayerManager
import com.movieshell.player.EngineHost
import com.movieshell.player.PicsViewerScreen
import com.movieshell.player.PlayerScreen
import com.movieshell.player.resolvePlayUrl
import com.movieshell.player.VideoQuality
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppSearchBar
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.PlainBlock
import com.movieshell.ui.components.LoadingSkeleton
import com.movieshell.ui.components.V5SectionTitle
import com.movieshell.ui.search.LOCAL_DRPY_PREFIX
import com.movieshell.ui.search.LOCAL_PY_PREFIX
import com.movieshell.ui.search.buildSearchConfigJson
import com.movieshell.ui.search.goDetail
import com.movieshell.ui.home.PosterSeed
import com.movieshell.ui.theme.BrandGradientColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    vodId: String,
    // B1：详情站点跟路由参数走；null 或哨兵值表示老路由，才回退 holder。
    siteKey: String? = null,
    // v0.75.0：继续观看带过来的起播集（-1=不指定，走断点续播/默认逻辑）
    initialEpisode: Int = -1,
    onBack: () -> Unit,
    // v0.87.0：点演员名搜其作品
    onActorClick: (actorName: String) -> Unit = {},
    // v1.0.7：同名剧区点按进详情（与搜索页 onVodClick 同口径）
    onVodClick: (vodId: String, siteKey: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // v0.294.45：详情页原地全屏状态——小窗 ↔ 全屏只是同一个 PlayerScreen
    // 换容器，不跳独立页、不重建画面。真离开详情页（返回、进演员页等）
    // 时 dispose 一律停播、清会话、复位方向，不再用 goingFullscreen 开关
    // 猜“是不是去全屏”——那个开关点一次全屏就再也不复位，之后真离开
    // 也跳过停播，正是“退出了还在响”的根源之一。
    var playerFullscreen by remember(vodId) { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            EngineHost.stop()
            PlaySession.clear()
            PlaySession.playerVisible = false
            // v0.266.0：真离开详情页时复位方向——嵌入播放退出原来
            // 不碰方向（见 PlayerScreen 注释），手动切屏锁/重力锁会
            // 残留到首页，导致“横屏回不到竖屏”。
            OrientationSensor.stop()
            (context as? Activity)?.let {
                if (it.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    // ---- drpy / PY / PHP 规则接入（drpy 优先，其次 PY，再次 PHP CLI） ----
    // B1/Q3：站点只按路由 siteKey 解析（全局 holder 已删除）；嵌入播放器
    // 由下方 PlayerScreen(siteKey = subSite?.key) 显式传入，不再经全局中转。
    val subSite = remember(vodId, siteKey) {
        DetailSiteRoute.resolve(context, siteKey)
    }
    val drpyActive = remember(vodId) {
        DrpySourceManager.hasActiveRule() || PySourceManager.hasActiveRule() ||
            PhpSourceManager.activeRuleName() != null || WebParseSourceManager.hasActiveSite()
    }
    // v0.267.0（T7）：原来 detailLoading 初始 false，首帧
    // detail==null 先落入失败分支，LaunchedEffect 调 reloadDetail
    // 置 true 后才变骨架，首帧先闪一下失败；进页即加载，初始
    // 直接置 true，失败只在请求回来后才出现。
    // v0.294.41：全屏页返回时详情页会重建——先用进程内缓存秒开，不再出骨架、不重查、不重新起播
    val cacheKey = remember(vodId, siteKey) { "${siteKey.orEmpty()}|$vodId" }
    val cached = remember(cacheKey) { DetailCache.get(cacheKey) }
    var detailLoading by remember { mutableStateOf(cached == null) }
    var detail by remember { mutableStateOf<VodDetail?>(cached?.detail) }

    // v1.0.7：同名剧——详情回来后在后台用精确片名在所有源里搜，
    // 流式回填（搜到一个源就出现一个），不卡播放；只认片名一字不差的，
    // 当前源本身不列；失败静默，只显示搜到的。
    var sameTitleGroups by remember { mutableStateOf<List<SiteSearchResult>>(emptyList()) }
    var sameTitleSearching by remember { mutableStateOf(false) }
    // v1.1.20：搜完标记——搜完为空时显示“其他源暂无同名剧”，不再整区隐藏。
    var sameTitleDone by remember { mutableStateOf(false) }
    val sameTitleRepo = remember {
        SiteRepository(
            drpyEngine = DrpySourceManager.drpyEngineAdapter,
            pyEngine = PySourceManager.engineAdapter,
        )
    }
    val sameTitleKey = detail?.vodName?.takeIf { it.isNotBlank() }.orEmpty()
    LaunchedEffect(sameTitleKey) {
        if (sameTitleKey.isBlank()) return@LaunchedEffect
        sameTitleGroups = emptyList()
        sameTitleSearching = true
        sameTitleDone = false
        try {
            sameTitleRepo.loadFromConfig(
                withContext(Dispatchers.IO) { buildSearchConfigJson(context) },
            )
            // 当前源的 key：订阅/CMS 站用 subSite.key，本地规则站用激活规则 key
            val currentKeys = buildSet {
                subSite?.key?.takeIf { it.isNotBlank() }?.let { add(it) }
                DrpySourceManager.activeRuleName()?.let { add("$LOCAL_DRPY_PREFIX$it") }
                PySourceManager.activeRuleName()?.let { add("$LOCAL_PY_PREFIX$it") }
                PhpSourceManager.activeRuleName()?.let { add("php:$it") }
            }
            sameTitleRepo.searchAllFlow(sameTitleKey).collect { group ->
                if (group.site.key in currentKeys) return@collect
                val exact = group.vods.filter { it.vodName.trim() == sameTitleKey.trim() }
                if (exact.isNotEmpty()) {
                    sameTitleGroups = sameTitleGroups + SiteSearchResult(group.site, exact)
                }
            }
        } catch (e: Exception) {
            LogBus.w("DetailSameTitle", "同名剧后台搜索失败：${e.message}")
        } finally {
            sameTitleSearching = false
            sameTitleDone = true
        }
    }
    // v0.27.6：抽成函数，失败态的"重试"按钮可复用
    // v0.47.0：20 秒超时——规则 hang 住时不再无限骨架屏，转失败态可重试
    // v0.171.0：世代号 + 取消旧任务——刷新按钮、失败重试、LaunchedEffect 可并发
    // 起多个 20 秒请求，旧请求迟到时不再覆盖新结果、也不再把 loading 提前置 false
    var detailGen by remember { mutableIntStateOf(0) }
    var detailJob by remember { mutableStateOf<Job?>(null) }
    // v0.260.0：sourceName / followSourceKey / followId 必须在 reloadDetail()
    // 之前声明——v0.259.0 把它们留在 reloadDetail() 之后，局部函数体内
    // 用 followId 做封面回填时编译报 Unresolved reference（局部先声明后调用）。
    // 三者只依赖 subSite / 各规则管理器 / vodId，均已在上方声明，提前无副作用。
    /** v0.21.0：影片信息一键复制 */
    // 当前来源名：追剧时记下来，追剧列表里展示；订阅站同时记 site key 以便回跳
    val sourceName = remember(vodId) {
        when {
            subSite != null -> subSite.name
            DrpySourceManager.hasActiveRule() ->
                DrpySourceManager.activeRuleName()?.removeSuffix(".js")
                    ?.ifBlank { "drpy规则" } ?: "drpy规则"
            PySourceManager.hasActiveRule() ->
                PySourceManager.activeRuleName()?.removeSuffix(".py")
                    ?.ifBlank { "PY规则" } ?: "PY规则"
            PhpSourceManager.activeRuleName() != null ->
                PhpSourceManager.activeRuleName()?.removeSuffix(".php")
                    ?.ifBlank { "PHP规则" } ?: "PHP规则"
            WebParseSourceManager.hasActiveSite() ->
                WebParseSourceManager.activeSiteName() ?: "网页解析源"
            else -> "站源"
        }
    }
    val followSourceKey = remember(subSite, sourceName) {
        subSite?.key.orEmpty().ifBlank {
            DrpySourceManager.activeRuleName().orEmpty()
                .ifBlank { PySourceManager.activeRuleName().orEmpty() }
                .ifBlank { PhpSourceManager.activeRuleName().orEmpty() }
                .ifBlank { WebParseSourceManager.activeSite()?.fileName.orEmpty() }
        }.ifBlank { sourceName }
    }
    val followId = remember(vodId, followSourceKey) { FollowStore.idOf(followSourceKey, vodId) }
    fun reloadDetail() {
        detailJob?.cancel()
        detailGen++
        val myGen = detailGen
        detailJob = scope.launch {
            detailLoading = true
            val result = runCatching {
                withTimeout(20_000) {
                    if (subSite != null) {
                        SiteRepository().detail(subSite, vodId)
                    } else if (drpyActive) {
                        if (DrpySourceManager.hasActiveRule()) DrpySourceManager.detail(vodId)
                        else if (PySourceManager.hasActiveRule()) PySourceManager.detail(vodId)
                        else if (PhpSourceManager.activeRuleName() != null) PhpCliEngine.detail(context, vodId)
                        else if (WebParseSourceManager.hasActiveSite()) WebParseSourceManager.detail(vodId)
                        else VodDetail(vodId = vodId)
                    } else {
                        null
                    }
                }
            }.getOrNull()
            // 只有最新一代才能写回；旧请求即使后完成也直接丢弃
            if (myGen == detailGen) {
                // v0.212.0 审计修复：详情点“刷新”时网络一抖，原来无条件
                // 把 detail 写成 null，整页先抹成失败态、播放也被打断；
                // 刷新失败保留旧内容、只提示，首次加载失败仍走失败态。
                if (result != null) {
                    detail = result
                    DetailCache.putDetail(cacheKey, result)
                    // v0.259.0：封面回填——全屏下首次追剧的记录没有封面，
                    // 详情加载时若库内该条目封面为空则补写，不改其他字段。
                    if (result.vodPic.isNotBlank()) {
                        scope.launch { FollowStore.backfillPic(context, followId, result.vodPic) }
                    }
                } else if (detail != null) {
                    AppToast.show(context, "刷新失败，已保留旧内容", long = false)
                }
                detailLoading = false
            }
        }
    }
    LaunchedEffect(vodId) {
        // v0.294.41：有缓存（从全屏页返回）就直接用，不清空、不重查
        if (cached?.detail != null) return@LaunchedEffect
        // v0.217.0（F12）：换片（组合复用时）先清旧详情，不闪旧片；重查走世代号。
        detail = null
        reloadDetail()
    }
    // 真实选集：vodPlayFrom/vodPlayUrl 对齐
    val episodes: List<Episode> = remember(detail) {
        detail?.let { SiteRepository().parseEpisodes(it) }.orEmpty()
    }
    // 多线路：按 Episode.flag 分组；只有一条线路时 playLines.size==1，不显示 Tab
    val playLines: List<PlayLine> = remember(episodes) {
        episodes.groupBy { it.flag.ifBlank { "默认线路" } }
            .map { (name, eps) -> PlayLine(name, eps) }
    }
    var selectedLine by rememberSaveable(vodId) { mutableIntStateOf(0) }
    val safeLine = if (playLines.isNotEmpty()) selectedLine.safeIndex(playLines.size) else 0
    val lineEpisodes: List<Episode> = playLines.getOrNull(safeLine)?.episodes.orEmpty()

    // v0.218.0（S27）：界面 Vod 已删，详情页直接读 VodDetail——标题／评分／
    // 年份／简介／主演见下面各处，集数读解析出的 episodes；地区原来恒空，
    // 对应的 MetaChip 一并删掉。detail 失败时直接为 null 走失败态。

    var selectedEpisode by rememberSaveable(vodId) { mutableIntStateOf(0) }
    // v0.77.0：跳集弹窗
    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    var showTitleDialog by remember { mutableStateOf(false) } // 2026-11-01：点片名弹完整片名（片名改小 + 弹窗，两件一起做）
    var showMagnetDialog by remember { mutableStateOf(false) }
    var lastMagnetUrl by remember { mutableStateOf("") }
    // v0.21.0：封面点开放大
    var showCoverDialog by remember { mutableStateOf(false) }
    val coverUrl = detail?.vodPic.orEmpty()

    // v0.168.0：收藏已去掉（与追剧重复），片单选择一并删除
    // v0.26.0：追剧
    val followSourceType = remember(subSite) {
        when {
            subSite != null -> "sub"
            DrpySourceManager.hasActiveRule() -> "drpy"
            PySourceManager.hasActiveRule() -> "py"
            PhpSourceManager.activeRuleName() != null -> "php"
            WebParseSourceManager.hasActiveSite() -> "webparse"
            else -> "sub"
        }
    }
    // v0.259.0：追剧开关已移到播放器顶栏齿轮旁，详情页不再自己维护开关；
    // 这里直接读 FollowStore 流，播放器里追/取消后已看角标与断点续播立即同步。
    val followItems by FollowStore.list.collectAsState()
    val following = followItems.any { it.id == followId }
    // v0.71.0：追剧已看标记——看到第几集（-1=没看过）
    var watchedEp by remember { mutableIntStateOf(-1) }
    // v0.73.0：断点续播——已读完"看到第几集"才能决定从哪集起播，自动播放等它
    var resumeReady by remember { mutableStateOf(false) }
    LaunchedEffect(followId, following) {
        watchedEp = if (following) FollowStore.getWatchedEpisode(context, followId) else -1
        resumeReady = true
    }

    // v1.1.19：播放页五 Tab（效果图定版）——简介/追更/剧评/推荐/快搜，各一个专属色；
    // 切 Tab 时内容整体换面板，播放器与播放状态不动。下标存盘（枚举本身进不了 Bundle）。
    var selectedTabIndex by rememberSaveable(vodId) { mutableIntStateOf(0) }
    val selectedTab = DetailTab.values().getOrNull(selectedTabIndex) ?: DetailTab.INTRO
    // v1.1.19：选集视图——网格（3 列）/列表、正序/倒序，按效果图。
    var epViewGrid by rememberSaveable(vodId) { mutableStateOf(true) }
    var epDesc by rememberSaveable(vodId) { mutableStateOf(false) }
    // v1.1.19：胶囊共享状态——倍速与弹幕开关与播放器内同一条流/同一个开关，
    // 任一处改，另一处立即跟上；追更提醒读 FollowStore 的更新检测总开关。
    val currentSpeed by PlayerSettings.speedFlow(context).collectAsState()
    val danmakuEnabled by com.movieshell.player.danmaku.DanmakuSettings.enabled.collectAsState()
    var showRateMenu by remember { mutableStateOf(false) }
    // v1.1.19：真实画面尺寸（PlayerManager 流）——信息卡源名行与顶栏清晰度共用；
    // 拿不到时退线路名推测，不编造。
    val realVideoSize by PlayerManager.videoSize.collectAsState()
    val detailQuality = VideoQuality.label(
        realVideoSize?.width ?: 0, realVideoSize?.height ?: 0,
        playLines.getOrNull(safeLine)?.name,
    )
    // v1.1.19：背景跟随海报——PosterSeed 取种子色（与首页 Hero 同算法），
    // 失败返回 null 时不改背景；换剧时种子色 0.4 秒交叉淡化，减动效时直接切。
    var posterSeed by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(coverUrl) {
        posterSeed = if (coverUrl.isNotBlank()) PosterSeed.extract(context, coverUrl) else null
    }
    // v1.1.19：追更提醒开关——FollowStore 的更新检测总开关（12 小时后台查更新，
    // 有新集发通知）；进页读一次，开关只改这一处。
    var remindEnabled by remember { mutableStateOf(FollowStore.isCheckEnabled(context)) }
    // v1.1.19：推荐——当前源首页数据，第一次进推荐 Tab 才拉，之后不再重复拉。
    // v1.1.20：当前源首页为空时走 fallback（其他源同类热门），recsFallback 记
    // 数据是不是别源顶上的；RecEntry 带站 key，点推荐卡能进它自己的源。
    var recs by remember(vodId) { mutableStateOf<List<RecEntry>?>(null) }
    var recsFallback by remember(vodId) { mutableStateOf(false) }
    // v1.1.19：快搜——本页内搜全部已启用源（复用搜片页同一条 searchAllFlow），
    // 点提交/点热词才搜，不随输入逐字搜；搜过一次的词进搜索历史。
    var quickQuery by remember(vodId) { mutableStateOf("") }
    var quickResults by remember(vodId) { mutableStateOf<List<SiteSearchResult>?>(null) }
    var quickSearching by remember(vodId) { mutableStateOf(false) }
    var quickHistory by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(selectedTab) {
        if (selectedTab == DetailTab.RECS && recs == null) {
            val home = loadCurrentSourceHome(context, subSite)
            if (home.isNotEmpty()) {
                recs = home.map { RecEntry(it, siteKey.orEmpty()) }
                recsFallback = false
            } else {
                recs = loadFallbackRecs(
                    context = context,
                    subSite = subSite,
                    category = detail?.typeName.orEmpty(),
                    currentTitle = detail?.vodName.orEmpty(),
                    currentVodId = vodId,
                )
                recsFallback = true
            }
        }
        if (selectedTab == DetailTab.SEARCH && quickHistory.isEmpty()) {
            quickHistory = SearchHistoryStore.list(context)
        }
    }
    // v1.1.19：减动效——跟系统设置走，Tab 胶囊、背景淡化、简介展开全关动画。
    val reducedMotion = rememberReducedMotion()

    // v0.71.0：带已看角标的选集 chip（追剧且看到该集时右上角标记）
    // v0.292.0 V5：40dp 12 圆角方格——surfaceContainerHigh；当前集剧评紫 + 均衡器图标（跳动，减动效时静止）；已看右上 tertiary 圆点
    @Composable
    fun EpisodeChip(index: Int, name: String, selected: Boolean, onClick: () -> Unit) {
        val v = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(V5.CornerMd))
                .background(if (selected) Color(0xFFA855F7) else v.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    // v1.1.21：选中集均衡器图标轻微上下跳动；减动效开时静止。
                    val eqOffsetY = if (reducedMotion) {
                        0f
                    } else {
                        val eqTransition = rememberInfiniteTransition(label = "eqBounce")
                        eqTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = -2.5f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 400, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse,
                            ),
                            label = "eqBounceY",
                        ).value
                    }
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = "正在播放",
                        tint = v.onPrimary,
                        modifier = Modifier.size(16.dp).offset(y = eqOffsetY.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = episodeLabel(name),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) v.onPrimary else v.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (following && index <= watchedEp && !selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 0.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(v.tertiary)
                        .semantics { contentDescription = "已看" },
                )
            }
        }
    }
    var showDownloadDialog by remember { mutableStateOf(false) }
    // v0.49.0：SubHD 内置，入口常显（v0.30.0 的插件门禁删除）

    /** 统一播放决策（v0.19.0）：解析走公共 resolvePlayUrl；磁力直接起播不进播放器 */
    // v0.65：嵌入播放器状态（详情页顶部 16:9 直接播）
    var embeddedUrl by remember(vodId) { mutableStateOf("") }
    var embeddedTitle by remember(vodId) { mutableStateOf("") }
    // v0.205.0：顶部播放区状态——解析中/失败时盖一层状态，不再只剩封面图
    var embeddedResolving by remember(vodId) { mutableStateOf(false) }
    var embeddedError by remember(vodId) { mutableStateOf<String?>(null) }
    // v0.164.0：pics:// 图片集不走嵌入视频播放器，单独全屏看图（见外层 Box 末尾的覆盖层）
    var picsUrl by remember(vodId) { mutableStateOf("") }
    var picsTitle by remember(vodId) { mutableStateOf("") }
    // v0.65：当前播放的线路/集数（高亮跟随）
    var playingLine by remember(vodId) { mutableIntStateOf(0) }
    var playingEp by remember(vodId) { mutableIntStateOf(0) }
    // v0.217.0（F10）：播放解析世代号，只认最后一次点按
    var playGen by remember(vodId) { mutableIntStateOf(0) }
    // v0.294.9：上一集还在跑的解析任务——点新一集时取消，释放后台嗅探 WebView 名额
    val resolveJobRef = remember(vodId) { arrayOfNulls<kotlinx.coroutines.Job>(1) }

    /**
     * v0.217.0（F11）：“当前规则是否可用”唯一入口——点播、下载共用。
     * 订阅站源不在禁用表里，恒可用；本地规则查禁用表。
     * 只返回判断，不弹提示，提示文案由调用方按入口写（播放／下载说法不同）。
     */
    fun isCurrentRuleEnabled(): Boolean {
        if (subSite != null) return true
        val rule: Pair<String, String?> = when {
            DrpySourceManager.hasActiveRule() -> "js" to DrpySourceManager.activeRuleName()
            PySourceManager.hasActiveRule() -> "py" to PySourceManager.activeRuleName()
            PhpSourceManager.activeRuleName() != null -> "php" to PhpSourceManager.activeRuleName()
            WebParseSourceManager.hasActiveSite() -> "webparse" to WebParseSourceManager.activeSite()?.fileName
            else -> "" to null
        }
        val ruleName = rule.second ?: return true
        return SourceStateStore.isRuleEnabled(rule.first, ruleName)
    }

    fun playEpisode(ep: Episode, lineIndex: Int, episodeIndex: Int) {
        LogBus.i("UI", "点击播放: ${ep.name.ifBlank { "未命名剧集" }} url=${ep.url.take(100)}")
        // v0.215.0：禁用源详情仍可播——点播前查禁用表，禁用的源不解析、只提示
        // （详情页打开时源还启用、进页后被禁用也会拦住；订阅站源不在此表，不受影响）。
        // v0.217.0（F11）：走共用入口 isCurrentRuleEnabled，不再各写各的。
        if (!isCurrentRuleEnabled()) {
            AppToast.show(context, "该源已禁用，启用后才能播放", long = false)
            return
        }
        // v0.217.0（F8）：选中态统一在 playEpisode 入口写——主选集分支点按
        // 原来不写 selectedEpisode，失败后重试读的是旧选中态、播错集；
        // 第二分支原来各写各的，现在入口一处写，两分支与重试、高亮同口径。
        selectedLine = lineIndex
        selectedEpisode = episodeIndex
        // v0.217.0（F10）：播放解析世代号——连点选集时旧的慢解析结果到达
        // 直接丢弃，不再覆盖新一集的播放与会话；只认最后一次点按。
        playGen += 1
        val myGen = playGen
        // v0.106.0：解析走应用级 PlayResolveScope——rememberCoroutineScope 会在切页/
        // 返回时取消，导致 "The coroutine scope left the composition"（16 条线路全灭）
        resolveJobRef[0]?.let { prev ->
            if (prev.isActive) {
                LogBus.i("Detail", "新一集开始解析，取消上一集未完成的解析")
                prev.cancel()
            }
        }
        resolveJobRef[0] = PlayResolveScope.launch {
            embeddedResolving = true
            embeddedError = null
            if (MagnetPlayer.isMagnet(ep.url)) {
                // v0.294.38：原来直接让引擎起播，却没把地址交给嵌入播放器——播放区不显示画面，
                // 只听到声音（Media3 / libmpv 都一样）。改为拿到本机播放地址后照普通直链交给嵌入播放器。
                val r = runCatching { MagnetPlayer.ensureReady(context, ep.url) }
                    .getOrElse { MagnetPlayer.Ready.Fail(it.message ?: "磁力起播失败") }
                if (myGen != playGen) {
                    LogBus.i("Detail", "磁力已就绪，但已点了别的条目，本次不起播")
                    return@launch
                }
                LogBus.i("Detail", "磁力结果：${if (r is MagnetPlayer.Ready.Ok) "就绪，交给小窗播放器" else "失败"}")
                when (r) {
                    is MagnetPlayer.Ready.Ok -> {
                        playingLine = lineIndex
                        playingEp = episodeIndex
                        embeddedTitle = "${detailTitle(detail, vodId)} ${ep.name.ifBlank { "第${episodeIndex + 1}集" }}"
                        embeddedUrl = r.playUrl
                    }
                    is MagnetPlayer.Ready.Fail -> {
                        MagnetPlayer.lastFailMessage = r.message
                        lastMagnetUrl = ep.url; showMagnetDialog = true
                    }
                }
                embeddedResolving = false
            } else {
                val link = try {
                    resolvePlayUrl(context, subSite, ep)
                } catch (t: Throwable) {
                    LogBus.e("Detail", "解析异常：${t.message}")
                    ""
                }
                // 世代已过：迟到结果直接丢弃，不写地址、不记已看、不建会话
                if (myGen != playGen) return@launch
                // v0.171.0：无播放地址先拦截提示，不再静默记已看、不写播放会话、
                // 不进只剩封面的空白播放区（此前 markWatched 与 PlaySession.start
                // 都在空地址判定之前执行，点集没反应还被标成已看）
                if (link.isBlank()) {
                    AppToast.show(context, "该集暂无播放地址", long = false)
                    // v0.217.0（F9）：坏集失败切到错误态——旧 embeddedUrl 必须清掉，
                    // 否则状态层（要求地址为空才渲染）永远出不来，用户既看不到重试、
                    // 也分不清现在播的是哪集；重试读 F8 统一写的选中态，正好是这集。
                    embeddedUrl = ""
                    embeddedError = "该集暂无播放地址"
                    embeddedResolving = false
                    return@launch
                }
                // v0.164.0：pics:// 图片集直接进看图，不写视频播放会话、不进嵌入播放器
                if (com.movieshell.player.isPicsUrl(link)) {
                    picsTitle = "${detailTitle(detail, vodId)} ${ep.name.ifBlank { "第${episodeIndex + 1}集" }}"
                    picsUrl = link
                    embeddedResolving = false
                    return@launch
                }
                // v0.294.35：听书 / 纯音频 → 音乐播放器（各集成播放队列）
                if (com.movieshell.player.AudioRoute.isAudioUrl(link) ||
                    com.movieshell.player.AudioRoute.isAudioCategory(detail?.typeName, subSite?.name)
                ) {
                    com.movieshell.player.AudioRoute.play(
                        context, subSite, detailTitle(detail, vodId), detail?.vodPic.orEmpty(),
                        playLines.getOrNull(lineIndex)?.episodes.orEmpty(), episodeIndex, link,
                    )
                    playingLine = lineIndex
                    playingEp = episodeIndex
                    embeddedUrl = ""
                    embeddedResolving = false
                    return@launch
                }
                // v0.26.0：追剧看到第几集联动（有地址才记）
                if (following) {
                    FollowStore.markWatched(context, followId, episodeIndex)
                    // v0.71.0：已看角标实时跟上
                    watchedEp = maxOf(watchedEp, episodeIndex)
                }
                // v0.19.0：写入播放会话，供播放器内切线路 / 失败自动重试
                PlaySession.start(playLines, lineIndex, episodeIndex) { ctx, e ->
                    resolvePlayUrl(ctx, subSite, e)
                }
                // v0.65：不再跳独立播放页，嵌入播放器直接播
                playingLine = lineIndex
                playingEp = episodeIndex
                embeddedTitle = "${detailTitle(detail, vodId)} ${ep.name.ifBlank { "第${episodeIndex + 1}集" }}"
                embeddedResolving = false
                embeddedUrl = link
            }
        }
    }

    /**
     * v1.1.19：追/取消追剧唯一入口——Tab 胶囊爱心与追更 Tab 的按钮共用，
     * 与播放器顶栏追剧开关读写同一条 FollowStore 记录（id = followId）。
     */
    fun toggleFollow() {
        scope.launch {
            if (following) {
                if (FollowStore.unfollow(context, followId)) {
                    AppToast.show(context, "已取消追剧", long = false)
                } else {
                    AppToast.show(context, "取消追剧失败，请重试", long = false)
                }
            } else {
                val ok = FollowStore.follow(
                    context,
                    FollowItem(
                        id = followId,
                        vodId = vodId,
                        name = detailTitle(detail, vodId),
                        pic = coverUrl,
                        sourceType = followSourceType,
                        sourceKey = followSourceKey,
                        episodeCount = episodes.size,
                    ),
                )
                AppToast.show(
                    context,
                    if (ok) "已追剧，有更新会提醒你" else "追剧失败，请重试",
                    long = false,
                )
            }
        }
    }

    // v1.1.19：倍速——详情页（Tab 胶囊）改速的唯一入口，与播放器齿轮页同一条路：
    // PlayerSettings.setSpeed 落盘＋推 speedFlow，播放器里读流的一侧自动跟上。
    fun applySpeed(v: Float) {
        PlayerSettings.setSpeed(context, v)
    }

    // v1.1.19：快搜——点提交/点热词才搜；搜完把词记进搜索历史（热词跟着变）。
    // 复用搜片页同一条 searchAllFlow 与 sameTitleRepo（已载入搜索配置），
    // 搜到的结果按源分组流式回填，点一行走 goDetail 换到那个源。
    fun doQuickSearch(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        SearchHistoryStore.add(context, query)
        quickHistory = SearchHistoryStore.list(context)
        quickSearching = true
        quickResults = null
        scope.launch {
            val acc = mutableListOf<SiteSearchResult>()
            sameTitleRepo.searchAllFlow(query).collect { group ->
                acc.add(group)
                quickResults = acc.toList()
            }
            quickSearching = false
        }
    }

    // v0.294.35：识别不准时先用视频播放器；Media3 报告没有视频轨就切到音乐播放器
    LaunchedEffect(embeddedUrl, playLines, detail) {
        com.movieshell.player.AudioRoute.noVideo.collect { url ->
            if (embeddedUrl.isNotBlank() && url == embeddedUrl) {
                com.movieshell.player.AudioRoute.play(
                    context, subSite, detailTitle(detail, vodId), detail?.vodPic.orEmpty(),
                    playLines.getOrNull(playingLine)?.episodes.orEmpty(), playingEp, url,
                )
                embeddedUrl = ""
            }
        }
    }
    val safeSelected = if (lineEpisodes.isNotEmpty()) {
        selectedEpisode.safeIndex(lineEpisodes.size)
    } else selectedEpisode
    // v0.65：剧集高亮跟随实际播放（嵌入播放器内切集时同步）
    val highlightEp = if (embeddedUrl.isNotBlank()) playingEp else selectedEpisode
    // v0.62：进详情页自动播放（用户要求，不需要点播放键）
    // v0.73.0：断点续播——追剧的剧从"看到第几集"继续播，而不是永远第1集
    // v0.75.0：继续观看带集数进来时优先用它
    var autoPlayed by remember(vodId) { mutableStateOf(cached != null && EngineHost.isActiveFor(cached.playUrl)) }
    // v0.294.41：重进详情时同一地址仍在播 → 恢复嵌入播放（接续同一播放器），不再自动重新点播
    // v0.294.45：不再 markHandoff——全屏已是原地放大，没有独立页需要接力，
    // 接力标记只会让别的退出路径误判“有人接手”而不停播。
    LaunchedEffect(cacheKey) {
        val c = cached ?: return@LaunchedEffect
        if (c.playUrl.isNotBlank() && EngineHost.isActiveFor(c.playUrl)) {
            autoPlayed = true
            playingLine = c.line
            playingEp = c.ep
            selectedEpisode = c.ep
            embeddedTitle = c.title
            com.movieshell.player.MpvPlayerManager.diagnose("返回小窗")
            embeddedUrl = c.playUrl
            LogBus.i("Detail", "重进详情：恢复正在播放的画面（第 ${c.ep + 1} 集）")
        }
    }
    LaunchedEffect(embeddedUrl, playingLine, playingEp, embeddedTitle) {
        DetailCache.putPlay(cacheKey, embeddedUrl, playingLine, playingEp, embeddedTitle)
    }
    LaunchedEffect(detail, lineEpisodes, resumeReady) {
        if (!autoPlayed && detail != null && lineEpisodes.isNotEmpty() && resumeReady) {
            autoPlayed = true
            val startEp = when {
                initialEpisode >= 0 -> initialEpisode.safeIndex(lineEpisodes.size)
                following && watchedEp >= 0 -> watchedEp.safeIndex(lineEpisodes.size)
                else -> safeSelected
            }
            selectedEpisode = startEp
            playEpisode(lineEpisodes[startEp], safeLine, startEp)
        }
    }
    // v0.156.0：最顶上那一栏整个去掉（标题/追剧/分享都不再占一栏），
    // 追剧/分享收进下面简介卡片海报下方的小图标行；返回只留播放区左上角一个悬浮箭头。
    Scaffold { inner ->
        if ((subSite != null || drpyActive) && detailLoading && detail == null) {
            // 详情加载中：骨架屏占位，不转菊花
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(V5.Gutter),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LoadingSkeleton(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                LoadingSkeleton(
                    modifier = Modifier.fillMaxWidth(0.55f).height(22.dp),
                    shape = RoundedCornerShape(6.dp),
                )
                LoadingSkeleton(
                    modifier = Modifier.fillMaxWidth().height(14.dp),
                    shape = RoundedCornerShape(6.dp),
                )
                LoadingSkeleton(
                    modifier = Modifier.fillMaxWidth(0.85f).height(14.dp),
                    shape = RoundedCornerShape(6.dp),
                )
                LoadingSkeleton(
                    modifier = Modifier.fillMaxWidth(0.7f).height(14.dp),
                    shape = RoundedCornerShape(6.dp),
                )
            }
        } else if (detail == null) {
            // v0.267.0（T8）：无源时 reloadDetail 返回 null，原来
            // 落入同一通用失败态；无源单独给文案，按钮回首页选源，
            // 不再让用户对着“重试”空点——重试也还是无源。
            // v0.27.6：详情加载失败。不再回退 DemoData 假数据（曾冒出"星海追光者"），给真失败态 + 重试
            val noSource = subSite == null && !drpyActive
            EmptyState(
                kind = EmptyKind.Error,
                icon = Icons.Filled.ErrorOutline,
                title = if (noSource) "未启用任何源" else "详情加载失败",
                hint = if (noSource) "请先回首页启用一个站源，再进详情" else "规则返回异常或网络超时",
                actionText = if (noSource) "返回" else "重试",
                onAction = { if (noSource) onBack() else reloadDetail() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
            )
        } else {
            // detail 非空才进内容区
            detail?.let {
                // v0.68：小窗悬浮——播放器滚出屏幕后缩到右下角继续播
                val listState = rememberLazyListState()
                val playerOffScreen by remember {
                    derivedStateOf { listState.firstVisibleItemIndex > 0 }
                }
                val showMiniPlayer = !playerFullscreen && playerOffScreen && embeddedUrl.isNotBlank()
                Box(
                    // v0.261.0：播放器贴边——只取上下内边距，左右内边距忽略，
                    // 否则 Detail Scaffold 的左右 inset + MainActivity 的左右 inset
                    // 叠加后播放器左侧留白（实测约 107/1000 宽），详情卡本身有 PagePadding，不受影响。
                    // v0.294.45：原地全屏时不套任何内边距，播放器真正占满屏；
                    // 同一个 PlayerScreen 只换了外层修饰，画面本体不重建。
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (playerFullscreen) Modifier
                            else Modifier.padding(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding())
                        ),
                ) {
                    // v1.1.19：背景跟随海报——种子色往主题背景兑 45%，顶部深、60% 处淡回主题背景；
                    // 换剧时 0.4 秒交叉淡化，减动效时直接切；取不到种子色时纯主题背景。
                    // 播放区（占位＋播放器画面容器）共用同一个 seedColor，换剧时一起淡化。
                    val seedColor by animateColorAsState(
                        targetValue = posterSeed?.let {
                            blendColor(MaterialTheme.colorScheme.background, Color(it), 0.45f)
                        } ?: MaterialTheme.colorScheme.background,
                        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 400),
                        label = "detailBgSeed",
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to seedColor,
                                        0.6f to MaterialTheme.colorScheme.background,
                                        1.0f to MaterialTheme.colorScheme.background,
                                    ),
                                ),
                            ),
                    )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // v0.65：顶部嵌入播放器（16:9）——详情就绪自动播第一集
                    // v0.70.1：此处只留占位，播放器本体常驻外层 Box（单父容器），
                    // 跟着列表滚动偏移量同步上移，避免 movableContentOf 跨父容器搬家崩溃
                    item {
                        if (embeddedUrl.isNotBlank()) {
                            Spacer(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f),
                            )
                        } else {
                            PlainBlock(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clickable(
                                        enabled = coverUrl.isNotBlank(),
                                        onClick = { showCoverDialog = true },
                                    ),
                                // v1.1.19：无封面时占位也不再是写死的灰块，跟当前剧的种子色走。
                                backgroundColor = seedColor,
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    // 真实封面（Coil，无封面时保留渐变占位）
                                    if (coverUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = coverUrl,
                                            contentDescription = "封面",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                }
                            }
                        }
                    }
    
                    // v1.1.19：Tab 栏——吸顶（随列表滚到顶后钉住）；五 Tab 各一个专属色，
                    // 选中胶囊底＋加粗、滑块 0.38 秒平滑移动；右边胶囊：收藏、弹幕、倍速。
                    stickyHeader {
                        DetailTabBar(
                            selectedTab = selectedTab,
                            onSelect = { tab ->
                                selectedTabIndex = tab.ordinal
                                scope.launch { listState.scrollToItem(0) }
                            },
                            following = following,
                            onToggleFollow = { toggleFollow() },
                            danmakuEnabled = danmakuEnabled,
                            onToggleDanmaku = {
                                com.movieshell.player.danmaku.DanmakuSettings.setEnabled(
                                    context, !danmakuEnabled,
                                )
                            },
                            currentSpeed = currentSpeed,
                            showRateMenu = showRateMenu,
                            onRateMenuChange = { showRateMenu = it },
                            onPickRate = { v -> applySpeed(v) },
                        )
                    }

                    // v1.1.19：Tab 内容——简介分支装原有 header/线路/选集/同名剧；
                    // 其余四 Tab 各一个面板；底部两段 24dp 留白留在 when 之外。
                    when (selectedTab) {
                        DetailTab.INTRO -> {
                    // v0.292.0 V5（design-tokens 5.2 / 02-detail）：信息区——不再套卡片，背景走 surface。
                    // v1.1.19：信息卡——全宽贴边、上下 20dp 渐变带；海报 134dp 宽、2:3、
                    // 与文字栏上下等高（点按续播）；标题 22sp 粗体＋标签 chips＋绿色更新行＋
                    // 简介两行截断＋“详情 >”展开＋源名行（源名 · 动态清晰度）；下载图标
                    // （全页唯一入口）留在标题行右端，追剧按钮已删（由 Tab 胶囊爱心接替）；
                    // 播放线路单独成段放简介下方；主演 chip（点按仍走演员搜索）。
                    item {
                        val v = MaterialTheme.colorScheme
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // v1.1.19：卡上渐变带——透明→卡面色，与上方播放区柔和接缝。
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(20.dp)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color.Transparent, v.surfaceContainer),
                                        ),
                                    ),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(v.surfaceContainer)
                                    .padding(horizontal = V5.Gutter)
                                    .height(IntrinsicSize.Max),
                            ) {
                                if (coverUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = coverUrl,
                                        contentDescription = "海报，点按播放",
                                        modifier = Modifier
                                            .width(134.dp)
                                            .aspectRatio(2f / 3f)
                                            .clip(RoundedCornerShape(V5.CornerLg))
                                            .background(v.surfaceContainerHigh)
                                            .clickable {
                                                // v1.1.13：点海报播放入口，接旧“继续播放”按钮的逻辑原样——
                                                // 已在播 → 回到顶部播放器；未播 → 从断点集续播。
                                                // 海报放大改走顶部播放区点按（showCoverDialog 不动）。
                                                if (embeddedUrl.isNotBlank()) {
                                                    scope.launch { listState.animateScrollToItem(0) }
                                                } else {
                                                    val list = if (lineEpisodes.isNotEmpty()) lineEpisodes else episodes
                                                    val i = highlightEp.coerceIn(0, (list.size - 1).coerceAtLeast(0))
                                                    list.getOrNull(i)?.let { ep ->
                                                        playEpisode(ep, safeLine, i)
                                                        scope.launch { listState.animateScrollToItem(0) }
                                                    }
                                                }
                                            },
                                        contentScale = ContentScale.Crop,
                                    )
                                    Spacer(Modifier.width(20.dp))
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                ) {
                                    // v1.1.19：标题行——片名（点按仍进标题弹窗）＋下载（全页唯一入口，
                                    // 逻辑与可用条件不动）；标题行里的追剧按钮已删，由 Tab 胶囊爱心接替。
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = detailTitle(detail, vodId),
                                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                            color = v.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { showTitleDialog = true },
                                        )
                                        IconButton(
                                            onClick = { showDownloadDialog = true },
                                            enabled = lineEpisodes.isNotEmpty(),
                                            modifier = Modifier.size(44.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.Download,
                                                contentDescription = "下载",
                                                tint = v.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    // v1.1.19：标签——typeName 拆 chips，无则整行隐藏。
                                    val typeTags = detail?.typeName.orEmpty()
                                        .split("/", "、", "，", ",", " ")
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                        .take(3)
                                    if (typeTags.isNotEmpty()) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.padding(top = 6.dp),
                                        ) {
                                            typeTags.forEach { tag ->
                                                Box(
                                                    Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(v.surfaceContainerHigh)
                                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                                ) {
                                                    Text(
                                                        text = tag,
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = v.onSurfaceVariant,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    // v1.1.19：更新信息（绿色）——有备注用备注＋年份，否则用“共 N 集 · 年份”。
                                    val updateLine = when {
                                        detail?.vodRemarks?.isNotBlank() == true -> listOfNotNull(
                                            detail?.vodRemarks,
                                            detail?.vodYear?.takeIf { it.isNotBlank() },
                                        ).joinToString(" · ")
                                        episodes.isNotEmpty() || detail?.vodYear?.isNotBlank() == true -> listOfNotNull(
                                            "${episodes.size}集".takeIf { episodes.isNotEmpty() },
                                            detail?.vodYear?.takeIf { it.isNotBlank() },
                                        ).joinToString(" · ")
                                        else -> ""
                                    }
                                    if (updateLine.isNotBlank()) {
                                        Text(
                                            text = updateLine,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color(0xFF22C55E),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                    // v1.1.19：简介——两行截断，点“详情 >”原地展开、再点收起；
                                    // 字号随内容逐步缩（下限 12sp，只在未展开时、只缩不涨，换剧重置）。
                                    var introExpanded by remember(vodId) { mutableStateOf(false) }
                                    var introFontSize by remember(vodId) { mutableStateOf(14.sp) }
                                    val introText = detail?.vodDesc.orEmpty()
                                    Text(
                                        text = introText.ifBlank { "暂无简介" },
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = introFontSize),
                                        color = v.onSurfaceVariant,
                                        maxLines = if (introExpanded) Int.MAX_VALUE else 2,
                                        overflow = TextOverflow.Ellipsis,
                                        onTextLayout = { layout ->
                                            if (!introExpanded && layout.hasVisualOverflow && introFontSize > 12.sp) {
                                                introFontSize = (introFontSize.value - 1).sp
                                            }
                                        },
                                        modifier = Modifier
                                            .padding(top = 6.dp)
                                            .clickable(enabled = introText.isNotBlank()) {
                                                introExpanded = !introExpanded
                                            },
                                    )
                                    if (introText.isNotBlank()) {
                                        Text(
                                            text = if (introExpanded) "收起" else "详情 >",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = v.primary,
                                            modifier = Modifier
                                                .clickable { introExpanded = !introExpanded }
                                                .padding(top = 4.dp),
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    // v1.1.19：源名行——绿点＋当前源名＋动态清晰度（真实尺寸优先，
                                    // 线路名推测次之，取不到时“自动”）；贴文字栏底部，与海报底边齐平。
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 6.dp),
                                    ) {
                                        Box(
                                            Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF22C55E)),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "$sourceName · $detailQuality",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = v.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            // v1.1.19：卡下渐变带——卡面色→透明，与下方线路标题柔和接缝。
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(20.dp)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(v.surfaceContainer, Color.Transparent),
                                        ),
                                    ),
                            )
                            // v1.1.13：播放线路单独成段，移到简介正下方；只有一条线路时整段隐藏
                            // v1.1.19：标题行上边距归零——卡下 20dp 渐变带就是与线路标题的间距。
                            if (playLines.size > 1) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = V5.Gutter, end = V5.Gutter, bottom = 8.dp),
                                ) {
                                    Text(
                                        "播放线路",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${playLines.size} 条",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val lineRowState = rememberLazyListState()
                                LaunchedEffect(Unit) { if (safeLine > 0) lineRowState.scrollToItem(safeLine) }
                                LazyRow(
                                    state = lineRowState,
                                    contentPadding = PaddingValues(horizontal = V5.Gutter),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(playLines.size) { i ->
                                        // v1.1.19：点线路 chip 切线并播当前集（集号钳制到新线路长度内）；
                                        // 点当前线路不动（已在播）；选中绿底＋绿字（效果图口径）。
                                        LineChip(
                                            text = playLines[i].name,
                                            selected = safeLine == i,
                                            onClick = {
                                                if (safeLine != i) {
                                                    val newEps = playLines[i].episodes
                                                    val epIdx = selectedEpisode.coerceIn(
                                                        0, (newEps.size - 1).coerceAtLeast(0),
                                                    )
                                                    newEps.getOrNull(epIdx)?.let { ep ->
                                                        playEpisode(ep, i, epIdx)
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            // 主演：头像字 + 名字 chip，点按仍走演员搜索
                            val actorNames = detail?.vodActor.orEmpty()
                                .split("、", "/", "，", ",", " ")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                            if (actorNames.isNotEmpty()) {
                                Text(
                                    "主演",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = v.onSurface,
                                    modifier = Modifier.padding(start = V5.Gutter, top = 20.dp, bottom = 10.dp),
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = V5.Gutter),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    val avatarColors = listOf(v.primaryContainer, v.secondaryContainer, v.tertiaryContainer)
                                    val avatarOn = listOf(v.onPrimaryContainer, v.onSecondaryContainer, v.onTertiaryContainer)
                                    actorNames.forEachIndexed { i, name ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .height(40.dp)
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(v.surfaceContainerHigh)
                                                .clickable { onActorClick(name) }
                                                .padding(start = 4.dp, end = 14.dp),
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .background(avatarColors[i % 3]),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    name.take(1),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = avatarOn[i % 3],
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                name,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = v.onSurface,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
    
                    // v0.65：播放按钮行已移除（顶部嵌入播放器自动播，对标目标界面）
                    // v1.1.19：标题行已改成信息卡（海报 134dp＋片名＋下载），续播仍接点海报；
                    // 顶部嵌入播放器自动播行为不变
    
                    // 选集：drpy 真实选集优先；“播放线路”段已移到简介正下方（v1.1.13）
                    when {
                        lineEpisodes.isNotEmpty() -> {
                            item {
                                // v1.1.19：选集标题——“选集 · 第N集”（N = 在播集），右侧工具：
                                // 跳集（>20 集时保留）、视图切换（网格/列表）、正倒序切换。
                                val currentEpNo = if (highlightEp in lineEpisodes.indices) highlightEp + 1 else null
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = V5.Gutter, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = if (currentEpNo != null) "选集 · 第${currentEpNo}集" else "选集 · ${lineEpisodes.size} 集",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (lineEpisodes.size > 20) {
                                        TextButton(onClick = { showJumpDialog = true }) {
                                            Icon(Icons.Filled.Tag, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("跳集", style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                    IconButton(
                                        onClick = { epViewGrid = !epViewGrid },
                                        modifier = Modifier.size(44.dp),
                                    ) {
                                        Icon(
                                            if (epViewGrid) Icons.Filled.GridView else Icons.Filled.List,
                                            contentDescription = if (epViewGrid) "切换到列表视图" else "切换到网格视图",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = { epDesc = !epDesc },
                                        modifier = Modifier.size(44.dp),
                                    ) {
                                        Icon(
                                            if (epDesc) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                                            contentDescription = if (epDesc) "倒序" else "正序",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            item {
                                // v0.68：选集改可滚动网格，自动定位到正在播的集
                                // v1.1.19：三列网格（效果图口径）；列表视图用同一网格的单列渲染整行；
                                // 倒序时展示下标反转、点按映射回真实下标；自动定位跟随展示下标。
                                val cs = MaterialTheme.colorScheme
                                val epGridState = rememberLazyGridState()
                                val displayIndices: List<Int> =
                                    if (epDesc) lineEpisodes.indices.reversed().toList() else lineEpisodes.indices.toList()
                                LaunchedEffect(highlightEp, lineEpisodes.size, epDesc, epViewGrid) {
                                    if (lineEpisodes.isNotEmpty()) {
                                        runCatching {
                                            val pos = displayIndices.indexOf(highlightEp).coerceAtLeast(0)
                                            epGridState.scrollToItem(pos)
                                        }
                                    }
                                }
                                LazyVerticalGrid(
                                    columns = if (epViewGrid) GridCells.Fixed(3) else GridCells.Fixed(1),
                                    state = epGridState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = V5.Gutter)
                                        .heightIn(max = 320.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(
                                        displayIndices.size,
                                        // v0.188.0：按集身份取 key，不用下标；换线路后下标复用不再串位。
                                        // Episode 无 id 字段，用线路名加集名加地址拼身份。
                                        key = { episodeKey(lineEpisodes[displayIndices[it]]) },
                                    ) { pos ->
                                        val i = displayIndices[pos]
                                        val ep = lineEpisodes[i]
                                        if (epViewGrid) {
                                            // v0.71.0：已看角标
                                            EpisodeChip(
                                                index = i,
                                                name = ep.name.ifBlank { "第${i + 1}集" },
                                                // v0.65：高亮跟随实际播放
                                                selected = highlightEp == i,
                                                // v0.65：点选集直接播（对标目标界面）
                                                onClick = { playEpisode(ep, safeLine, i) },
                                            )
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(V5.CornerLg))
                                                    .background(if (highlightEp == i) cs.primaryContainer else cs.surfaceContainer)
                                                    .clickable { playEpisode(ep, safeLine, i) }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                            ) {
                                                Text(
                                                    text = ep.name.ifBlank { "第${i + 1}集" },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = if (highlightEp == i) cs.onPrimaryContainer else cs.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                if (following && i <= watchedEp && highlightEp != i) {
                                                    Box(
                                                        Modifier
                                                            .size(6.dp)
                                                            .clip(CircleShape)
                                                            .background(cs.tertiary),
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                }
                                                Icon(
                                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                    contentDescription = "播放",
                                                    tint = cs.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                        episodes.size > 1 -> {
                            item {
                                // v1.1.19：与第一分支同款标题与工具（在播集、跳集、视图、正倒序）。
                                val currentEpNo = if (highlightEp in episodes.indices) highlightEp + 1 else null
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = V5.Gutter, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = if (currentEpNo != null) "选集 · 第${currentEpNo}集" else "选集 · ${episodes.size} 集",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (episodes.size > 20) {
                                        TextButton(onClick = { showJumpDialog = true }) {
                                            Icon(Icons.Filled.Tag, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("跳集", style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                    IconButton(
                                        onClick = { epViewGrid = !epViewGrid },
                                        modifier = Modifier.size(44.dp),
                                    ) {
                                        Icon(
                                            if (epViewGrid) Icons.Filled.GridView else Icons.Filled.List,
                                            contentDescription = if (epViewGrid) "切换到列表视图" else "切换到网格视图",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = { epDesc = !epDesc },
                                        modifier = Modifier.size(44.dp),
                                    ) {
                                        Icon(
                                            if (epDesc) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                                            contentDescription = if (epDesc) "倒序" else "正序",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            item {
                                // v1.1.19：与第一分支同款三列网格/列表视图；点按按集数取已解析的
                                // 真实选集直拨，取不到说明该源没有给出可播地址，直接 Toast。
                                val cs = MaterialTheme.colorScheme
                                val epGridState = rememberLazyGridState()
                                val displayIndices: List<Int> =
                                    if (epDesc) episodes.indices.reversed().toList() else episodes.indices.toList()
                                LaunchedEffect(highlightEp, episodes.size, epDesc, epViewGrid) {
                                    if (episodes.isNotEmpty()) {
                                        runCatching {
                                            val pos = displayIndices.indexOf(highlightEp).coerceAtLeast(0)
                                            epGridState.scrollToItem(pos)
                                        }
                                    }
                                }
                                val onPickRaw: (Int) -> Unit = { i ->
                                    selectedEpisode = i
                                    val ep = episodes.getOrNull(i)
                                    if (ep != null) {
                                        playEpisode(ep, safeLine, i)
                                    } else {
                                        AppToast.show(context, "该源未返回可播地址", long = false)
                                    }
                                }
                                LazyVerticalGrid(
                                    columns = if (epViewGrid) GridCells.Fixed(3) else GridCells.Fixed(1),
                                    state = epGridState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = V5.Gutter)
                                        .heightIn(max = 320.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(
                                        displayIndices.size,
                                        key = { episodeKey(episodes[displayIndices[it]]) },
                                    ) { pos ->
                                        val i = displayIndices[pos]
                                        val ep = episodes[i]
                                        if (epViewGrid) {
                                            EpisodeChip(
                                                index = i,
                                                name = "第${i + 1}集",
                                                selected = highlightEp == i,
                                                onClick = { onPickRaw(i) },
                                            )
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(V5.CornerLg))
                                                    .background(if (highlightEp == i) cs.primaryContainer else cs.surfaceContainer)
                                                    .clickable { onPickRaw(i) }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                            ) {
                                                Text(
                                                    text = ep.name.ifBlank { "第${i + 1}集" },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = if (highlightEp == i) cs.onPrimaryContainer else cs.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                if (following && i <= watchedEp && highlightEp != i) {
                                                    Box(
                                                        Modifier
                                                            .size(6.dp)
                                                            .clip(CircleShape)
                                                            .background(cs.tertiary),
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                }
                                                Icon(
                                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                    contentDescription = "播放",
                                                    tint = cs.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                        else -> {
                            // v0.171.0：0 集时不再只剩标题加空白，给一句空提示
                            // v1.1.12：光说“没有”没用——给换源入口：点一下滑到下方同名剧区，
                            // 那里列着其他源的同名剧，点开就是换源。0 集时本来就没有线路可换，
                            // 所以入口是换源，不是换线路。
                            item {
                                Column(
                                    modifier = Modifier.padding(
                                        horizontal = V5.Gutter,
                                        vertical = 8.dp,
                                    ),
                                ) {
                                    Text(
                                        text = "暂无可播放剧集",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "这个源没有可播剧集，下滑看看同名剧、换个源试试",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                listState.animateScrollToItem(
                                                    (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0),
                                                )
                                            }
                                        },
                                        contentPadding = PaddingValues(0.dp),
                                    ) {
                                        Text("看看同名剧，换个源")
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                    }

                    // v1.0.7：同名剧——集数下方列出所有源的同名剧（精确片名），
                    // 后台流式搜、搜到一个源就出现一个；点开进那个源的详情页。
                    // v1.1.19：横向可滑海报卡（96dp 宽、2:3 海报＋剧名＋备注/年份＋源名绿点）；
                    // v1.1.20：搜完为空时不再整区隐藏，留一行“其他源暂无同名剧”。
                    if (sameTitleGroups.isNotEmpty() || sameTitleSearching || sameTitleDone) {
                        item {
                            V5SectionTitle(
                                title = if (sameTitleGroups.isNotEmpty()) {
                                    "同名剧 · ${sameTitleGroups.size} 个源"
                                } else {
                                    "同名剧"
                                },
                                modifier = Modifier.padding(
                                    horizontal = V5.Gutter,
                                    vertical = 8.dp,
                                ),
                            )
                        }
                        if (sameTitleSearching && sameTitleGroups.isEmpty()) {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(
                                        horizontal = V5.Gutter,
                                        vertical = 8.dp,
                                    ),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "正在其他源中查找同名剧…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (!sameTitleSearching && sameTitleGroups.isEmpty() && sameTitleDone) {
                            item {
                                Text(
                                    text = "其他源暂无同名剧",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        horizontal = V5.Gutter,
                                        vertical = 8.dp,
                                    ),
                                )
                            }
                        }
                        if (sameTitleGroups.isNotEmpty()) {
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = V5.Gutter),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    sameTitleGroups.forEach { group ->
                                        group.vods.forEach { vod ->
                                            item(key = "${group.site.key}:${vod.vodId}") {
                                                SameNameCard(
                                                    vod = vod,
                                                    siteName = group.site.name,
                                                    onClick = {
                                                        scope.launch {
                                                            val ok = goDetail(group.site, vod.vodId, onVodClick)
                                                            if (!ok) {
                                                                AppToast.show(context, "切源失败，请重试", long = false)
                                                            }
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                        }
                        DetailTab.FOLLOW -> {
                            item {
                                FollowPanel(
                                    title = detailTitle(detail, vodId),
                                    following = following,
                                    onToggleFollow = { toggleFollow() },
                                    vodRemarks = detail?.vodRemarks.orEmpty(),
                                    episodes = lineEpisodes,
                                    watchedEp = watchedEp,
                                    remindEnabled = remindEnabled,
                                    onToggleRemind = { on ->
                                        remindEnabled = on
                                        FollowStore.setCheckEnabled(context, on)
                                    },
                                    onPlayEpisode = { idx ->
                                        lineEpisodes.getOrNull(idx)?.let { ep ->
                                            playEpisode(ep, safeLine, idx)
                                            selectedTabIndex = DetailTab.INTRO.ordinal
                                            scope.launch { listState.scrollToItem(0) }
                                        }
                                    },
                                )
                            }
                        }
                        DetailTab.REVIEWS -> {
                            item { ReviewsPanel() }
                        }
                        DetailTab.RECS -> {
                            item {
                                RecsPanel(
                                    recs = recs,
                                    currentVodId = vodId,
                                    fallback = recsFallback,
                                    onOpen = { recId, recSiteKey -> onVodClick(recId, recSiteKey) },
                                )
                            }
                        }
                        DetailTab.SEARCH -> {
                            item {
                                QuickSearchPanel(
                                    query = quickQuery,
                                    onQueryChange = { quickQuery = it },
                                    onSearch = { doQuickSearch(quickQuery) },
                                    history = quickHistory,
                                    currentTitle = detailTitle(detail, vodId),
                                    onHotWord = { word ->
                                        quickQuery = word
                                        doQuickSearch(word)
                                    },
                                    searching = quickSearching,
                                    results = quickResults,
                                    onOpenResult = { site, id ->
                                        scope.launch {
                                            val ok = goDetail(site, id, onVodClick)
                                            if (!ok) AppToast.show(context, "切源失败，请重试", long = false)
                                        }
                                    },
                                )
                            }
                        }
                    }

                    // v0.58：简介已并入主演卡片，独立简介卡删除
                    item {
                        Spacer(Modifier.height(24.dp))
                        Spacer(Modifier.height(24.dp))
                    }
                }

                // v0.68：悬浮小窗——播放器滚出屏幕后右下角继续播
                // v0.70.1：播放器常驻外层 Box（单父容器），只切换位置/尺寸——
                // 未小窗时盖在列表顶部占位上并跟随滚动偏移同步上移，
                // 不再用 movableContentOf 跨父容器搬家（后者在 Lazy item 被回收时
                // 触发 "Cannot insert ... because it already has a parent" 崩溃）
                if (embeddedUrl.isNotBlank()) {
                    val mini = showMiniPlayer
                    Box(
                        modifier = Modifier
                            .then(
                                if (playerFullscreen) {
                                    // v0.294.45：原地全屏——同一个 PlayerScreen 换到满屏容器，
                                    // 画面本体、引擎、进度全都不动；退出全屏只是换回下面的分支。
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .fillMaxSize()
                                } else if (mini) {
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(16.dp)
                                        .width(200.dp)
                                } else if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                    // v0.264.0：嵌入点播原地切屏的容器——横屏时不再套 16:9 +
                                    // 滚动偏移，否则 PlayerScreen 的 fillMaxSize 会被外层
                                    // 16:9 盒子裁掉、顶栏也跟着偏移；横屏直接占满屏，
                                    // 详情内容在屏下，竖屏切回来恢复 16:9。
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .fillMaxSize()
                                } else {
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .fillMaxWidth()
                                        .offset {
                                            IntOffset(
                                                0,
                                                -listState.firstVisibleItemScrollOffset,
                                            )
                                        }
                                },
                            )
                            .then(
                                if (playerFullscreen) {
                                    Modifier
                                } else if (!mini && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                    Modifier
                                } else {
                                    Modifier.aspectRatio(16f / 9f)
                                }
                            )
                            .clip(RoundedCornerShape(if (mini) 12.dp else 0.dp)),
                    ) {
                        PlayerScreen(
                            url = embeddedUrl,
                            title = embeddedTitle,
                            vodId = vodId,
                            siteKey = subSite?.key,
                            infoName = detailTitle(detail, vodId),
                            infoRemarks = detail?.vodRemarks.orEmpty(),
                            infoYear = detail?.vodYear.orEmpty(),
                            infoDesc = detail?.vodDesc.orEmpty(),
                            // v1.1.19：画面容器底色跟当前剧的种子色（与整页背景同一个
                            // animateColorAsState，换剧时一起 0.4 秒交叉淡化）。
                            backgroundSeed = seedColor,
                            // v0.294.45：原地全屏时返回键先收回容器，不退出详情页。
                            onBack = { if (playerFullscreen) playerFullscreen = false else onBack() },
                            embedded = true,
                            isFullscreen = playerFullscreen,
                            onFullscreen = { playerFullscreen = true },
                            onExitFullscreen = { playerFullscreen = false },
                            onEpisodeChange = { lineIdx, epIdx ->
                                playingLine = lineIdx
                                playingEp = epIdx
                                // v0.175.0：播放器内切线路/选集（手动或自动）
                                // 都要同步详情页的选中态，否则 Tab、源胶囊、
                                // 选集列表还停在旧线路上；之前只写 playingLine
                                // 但它没有任何地方读，等于没同步。
                                selectedLine = lineIdx
                                selectedEpisode = epIdx
                            },
                        )
                        if (mini) {
                            // 回到顶部
                            IconButton(
                                onClick = { scope.launch { listState.scrollToItem(0) } },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .size(32.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                            ) {
                                Icon(
                                    Icons.Filled.ExpandLess,
                                    contentDescription = "回到顶部",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            // 关闭小窗（停止播放）
                            IconButton(
                                onClick = { embeddedUrl = "" },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(32.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "关闭",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
                // v0.205.0：顶部状态层——解析中给转圈、失败给重试，
                // 盖在封面图上，不再静默只剩一张图
                if (embeddedUrl.isBlank() && (embeddedResolving || embeddedError != null)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .offset {
                                IntOffset(
                                    0,
                                    -listState.firstVisibleItemScrollOffset,
                                )
                            }
                            .aspectRatio(16f / 9f)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (embeddedResolving) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("正在解析…", color = Color.White)
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(embeddedError ?: "播放失败", color = Color.White)
                                Spacer(Modifier.height(10.dp))
                                AppButton(
                                    text = "重试",
                                    onClick = {
                                        if (lineEpisodes.isNotEmpty()) {
                                            playEpisode(lineEpisodes[safeSelected], safeLine, safeSelected)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                // v0.156.0：无顶栏后，播放器没起来时（只有封面）左上角留一个悬浮返回，
                // 播放器起来时它自带返回，这里不重复盖一层
                if (embeddedUrl.isBlank()) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .size(44.dp)
                            .background(Color.Black.copy(alpha = 0.32f), CircleShape),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                } // Box：内容区 + 悬浮小窗
            }
        }
    
        // v0.164.0：pics:// 全屏看图覆盖层（在详情内容之上，返回只关看图不退详情）
        if (picsUrl.isNotBlank()) {
            Box(modifier = Modifier.fillMaxSize()) {
                PicsViewerScreen(
                    url = picsUrl,
                    title = picsTitle,
                    onBack = { picsUrl = "" },
                )
            }
        }

        // v0.294.23：磁力转播不可用（自动拉起失败）
        if (showMagnetDialog) {
            // v0.294.32：没装磁力引擎插件 → 一键从官方市场装（迅雷 + BT 两个，互为兜底）
            val needEngine = MagnetPlayer.lastFailMessage?.contains("插件") == true &&
                !com.movieshell.player.EnginePlugins.btInstalled(context) &&
                !com.movieshell.player.EnginePlugins.xlInstalled(context)
            var engineBusy by remember { mutableStateOf<String?>(null) }
            TechDialog(
                onDismissRequest = { if (engineBusy == null) showMagnetDialog = false },
                title = if (needEngine) "需要磁力引擎" else "磁力还不能播",
                confirmText = if (needEngine) (if (engineBusy != null) "安装中…" else "一键安装") else "用其他应用打开",
                confirmEnabled = engineBusy == null,
                onConfirm = {
                    if (!needEngine) {
                        if (lastMagnetUrl.isNotBlank()) MagnetPlayer.openExternally(context, lastMagnetUrl)
                        showMagnetDialog = false
                        return@TechDialog
                    }
                    engineBusy = "正在获取插件市场…"
                    scope.launch {
                        try {
                            val info = PluginMarket.fetch(PluginStore.DEFAULT_MARKET_URL)
                            val ids = listOf(com.movieshell.player.EnginePlugins.XL_ID, com.movieshell.player.EnginePlugins.BT_ID)
                            val msgs = mutableListOf<String>()
                            for (id in ids) {
                                val pl = info.plugins.firstOrNull { it.id == id } ?: continue
                                val (_, m) = com.movieshell.ui.market.PluginUi.installWithProgress(context, pl) { st ->
                                    engineBusy = st?.let { "${it.pluginName}：${it.stage}" }
                                }
                                msgs += m
                            }
                            com.movieshell.ui.market.PluginUi.toast(context,
                                if (msgs.isEmpty()) "市场里没找到磁力引擎插件" else msgs.joinToString("；") + "。请再点一次播放",
                                android.widget.Toast.LENGTH_LONG)
                            showMagnetDialog = false
                        } catch (e: Exception) {
                            com.movieshell.ui.market.PluginUi.toast(context, "安装失败：${e.message}", android.widget.Toast.LENGTH_LONG)
                        } finally {
                            engineBusy = null
                        }
                    }
                },
                dismissText = if (needEngine) "取消" else "知道了",
            ) {
                Text(
                    engineBusy ?: if (needEngine) {
                        "侠影把磁力播放引擎做成了插件（安装包更小）。一键安装「迅雷引擎」和「磁力引擎（BT）」，约 9MB，装完再点播放即可。也可在 设置-插件中心-市场 单独安装。"
                    } else {
                        MagnetPlayer.lastFailMessage ?: "磁力起播失败。请换网络重试，或用其他应用打开。"
                    },
                )
            }
        }

    
        // 2026-11-01：点片名弹完整片名（TechDialog，片名不截断、不顶布局）
        if (showTitleDialog) {
            TechDialog(
                onDismissRequest = { showTitleDialog = false },
                title = "片名",
                confirmText = "知道了",
                onConfirm = { showTitleDialog = false },
                dismissText = null,
            ) {
                Text(text = detailTitle(detail, vodId))
            }
        }

        // v0.26.0：下载选集（多选 → 解析地址 → 入队）
        if (showDownloadDialog) {
            var selected by remember(safeLine) {
                // v0.188.0：按线路记已选，换线路时旧下标勾选不再带到新线路。
                mutableStateOf(lineEpisodes.indices.toSet())
            }
            var resolving by remember { mutableStateOf(false) }
            Dialog(onDismissRequest = { if (!resolving) showDownloadDialog = false }) {
                // v0.294.3：封顶 420dp；选集由整宽勾选行（一屏只见几集）改为多列紧凑方格，点格即选/取消，标题行加全选
                AppCard(modifier = Modifier.widthIn(max = 420.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "选择要下载的剧集",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            val allSelected = selected.size == lineEpisodes.size && lineEpisodes.isNotEmpty()
                            TextButton(
                                onClick = {
                                    selected = if (allSelected) emptySet() else lineEpisodes.indices.toSet()
                                },
                                enabled = !resolving,
                            ) {
                                Text(if (allSelected) "全不选" else "全选", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        val dlLabels = lineEpisodes.mapIndexed { i, ep -> ep.name.ifBlank { "第${i + 1}集" } }
                        val dlLong = dlLabels.any { it.length > 5 }
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (dlLong) 132.dp else 64.dp),
                            modifier = Modifier.heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                lineEpisodes.size,
                                // v0.188.0：按集身份取 key，不用下标；换线路后下标复用不再串位。
                                // Episode 无 id 字段，用线路名加集名加地址拼身份。
                                key = { episodeKey(lineEpisodes[it]) },
                            ) { i ->
                                val ep = lineEpisodes[i]
                                val magnet = MagnetPlayer.isMagnet(ep.url)
                                val on = selected.contains(i)
                                val cs = MaterialTheme.colorScheme
                                Box(
                                    modifier = Modifier
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(V5.CornerSm))
                                        .background(if (on) cs.primaryContainer else cs.surfaceContainerHighest)
                                        .clickable(enabled = !resolving) {
                                            selected = if (on) selected - i else selected + i
                                        }
                                        .padding(horizontal = 6.dp)
                                        .alpha(if (magnet) 0.45f else 1f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        // 磁力不支持下载：格子变灰并标注
                                        text = if (magnet) "${dlLabels[i]}·磁力" else dlLabels[i],
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (on) cs.onPrimaryContainer else cs.onSurface,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 解析中指示：AppButton 无内容插槽，指示条放按钮左侧（功能不变）
                            if (resolving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            AppButton(
                                text = "取消",
                                onClick = { showDownloadDialog = false },
                                variant = AppButtonVariant.Ghost,
                                enabled = !resolving,
                            )
                            Spacer(Modifier.width(8.dp))
                            AppButton(
                                text = "下载选中（${selected.size}）",
                                onClick = {
                                    // v0.217.0（F11）：下载入口同样过禁用检查，不再绕过禁用。
                                    if (!isCurrentRuleEnabled()) {
                                        AppToast.show(context, "该源已禁用，启用后才能下载", long = false)
                                    } else {
                                    resolving = true
                                    // v0.106.0：批量解析走应用级 scope，关弹窗/切页不中断
                                    PlayResolveScope.launch {
                                        val tasks = mutableListOf<DownloadTask>()
                                        selected.sorted().forEach { i ->
                                            val ep = lineEpisodes.getOrNull(i) ?: return@forEach
                                            if (MagnetPlayer.isMagnet(ep.url)) return@forEach
                                            val url = resolvePlayUrl(context, subSite, ep)
                                            if (url.isBlank()) return@forEach
                                            tasks.add(
                                                DownloadTask(
                                                    title = "${detailTitle(detail, vodId)} ${ep.name.ifBlank { "第${i + 1}集" }}",
                                                    pic = detail?.vodPic.orEmpty(),
                                                    url = url,
                                                    kind = if (DownloadManager.isM3u8(url)) "m3u8" else "file",
                                                ),
                                            )
                                        }
                                        resolving = false
                                        showDownloadDialog = false
                                        if (tasks.isEmpty()) {
                                            AppToast.show(context, "没有可下载的剧集", long = false)
                                        } else {
                                            // v0.63.0：空间不足提醒
                                            if (DownloadSettings.lowSpaceWarn(context)) {
                                                val free = StoragePaths.downloadDir(context).usableSpace
                                                if (free < DownloadSettings.LOW_SPACE_BYTES) {
                                                    AppToast.show(context, "存储空间不足（剩余 ${free / 1024 / 1024}MB），可能下不完", long = true)
                                                }
                                            }
                                            DownloadManager.enqueue(context, tasks)
                                            AppToast.show(context, "已加入下载队列（${tasks.size}）", long = false)
                                        }
                                    }
                                }
                                },
                                enabled = !resolving && selected.isNotEmpty(),
                            )
                        }
                    }
                }
            }
        } // vod?.let
    }

    // v0.21.0：封面点开放大（全屏看图，点任意处关闭）
    if (showCoverDialog && coverUrl.isNotBlank()) {
        Dialog(
            onDismissRequest = { showCoverDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { showCoverDialog = false },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = "封面大图",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }

    // v0.77.0：跳集弹窗（集数>20 才有入口）
    if (showJumpDialog) {
        TechDialog(
            onDismissRequest = { showJumpDialog = false; jumpText = "" },
            title = "跳到第几集",
            confirmText = "播放",
            onConfirm = {
                val n = jumpText.toIntOrNull()
                if (n != null && n in 1..lineEpisodes.size) {
                    showJumpDialog = false
                    jumpText = ""
                    val idx = n - 1
                    selectedEpisode = idx
                    playEpisode(lineEpisodes[idx], safeLine, idx)
                }
            },
        ) {
            TechTextField(
                value = jumpText,
                onValueChange = { jumpText = it.filter { c -> c.isDigit() }.take(4) },
                placeholder = "输入集数（1-${lineEpisodes.size}）",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * v0.27.1：详情标题回退展示。
 * - 含 `@@` 的打包串（`tid@@标题@@图片@@作者`）只取标题段；
 * - 纯 URL 的 id 显示"未知标题"，不把裸地址甩给用户看。
 */
private fun displayTitle(vodId: String): String {
    if (vodId.contains("@@")) {
        return vodId.split("@@").getOrNull(1)?.takeIf { it.isNotBlank() } ?: "未知标题"
    }
    if (vodId.startsWith("http://") || vodId.startsWith("https://")) return "未知标题"
    return vodId.ifBlank { "未知标题" }
}

/**
 * v0.218.0（S10）：选集身份 key——原来选集行与下载行各写一遍
 * `"${ep.flag}|${ep.name}|${ep.url}"`，改一处漏一处；现在只此一处。
 */
/** v0.292.0 V5：选集格显示名——“第01集/第1话/1”收成数字，其它原样 */
private val EP_NUM = Regex("^\\s*第?\\s*0*(\\d+)\\s*[集话期回]?\\s*$")
private fun episodeLabel(name: String): String =
    EP_NUM.find(name)?.groupValues?.get(1) ?: name

private fun episodeKey(ep: Episode): String = "${ep.flag}|${ep.name}|${ep.url}"

/**
 * v0.218.0（S10）：详情标题回退——原来三处各写一遍
 * `vod?.title ?: displayTitle(vodId)`；现在只此一处。
 * v0.218.0（S27）：界面 Vod 已删，入参改成 VodDetail，直接读 vodName
 * （空时仍回退 displayTitle，与原来经 Vod 中转的语义一致）。
 */
private fun detailTitle(detail: VodDetail?, vodId: String): String =
    detail?.vodName?.takeIf { it.isNotBlank() } ?: displayTitle(vodId)

// v1.1.19：详情页 Tab——五 Tab 各一个专属色（效果图口径）。
private enum class DetailTab(val label: String, val color: Color) {
    INTRO("简介", Color(0xFF22C55E)),
    FOLLOW("追更", Color(0xFF3B82F6)),
    REVIEWS("剧评", Color(0xFFA855F7)),
    RECS("推荐", Color(0xFFF59E0B)),
    SEARCH("快搜", Color(0xFFEC4899)),
}

// v1.1.19：Tab 栏——五 Tab 等宽占满整行、一行不换行；选中胶囊底（该色 15% 透明度）＋
// 该色加粗 17sp、滑块 0.38 秒平滑移动且颜色同步过渡，未选中灰字；行底一条细分隔线。
// v1.1.20：字号各降一档（选中 16sp、未选中 15sp）；选中胶囊底加深到 30% 透明度；
// 未选中也有边框和各自颜色（描边与文字都用该 Tab 色）；右边胶囊与 Tab 行同高 48dp，
// 倍速文字 15sp 与 Tab 字同大小。
// 右边胶囊：收藏（爱心，实心粉色＝已追剧）、弹幕开关（开时绿色）、倍速（文字按钮，
// 点一下弹六档选单，当前档高亮打勾）。减动效时滑块与颜色过渡 0ms。
@Composable
private fun DetailTabBar(
    selectedTab: DetailTab,
    onSelect: (DetailTab) -> Unit,
    following: Boolean,
    onToggleFollow: () -> Unit,
    danmakuEnabled: Boolean,
    onToggleDanmaku: () -> Unit,
    currentSpeed: Float,
    showRateMenu: Boolean,
    onRateMenuChange: (Boolean) -> Unit,
    onPickRate: (Float) -> Unit,
) {
    val reducedMotion = rememberReducedMotion()
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(cs.background)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = V5.Gutter, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            // 五 Tab 等宽＋滑动胶囊（BoxWithConstraints 直接量 Dp 宽，不用密度换算）
            BoxWithConstraints(Modifier.weight(1f).height(48.dp)) {
                val tabWidth = maxWidth / 5
                val pillOffset by animateDpAsState(
                    targetValue = tabWidth * selectedTab.ordinal,
                    animationSpec = tween(durationMillis = if (reducedMotion) 0 else 380),
                    label = "tabPillOffset",
                )
                val pillColor by animateColorAsState(
                    targetValue = selectedTab.color,
                    animationSpec = tween(durationMillis = if (reducedMotion) 0 else 380),
                    label = "tabPillColor",
                )
                Box(
                    Modifier
                        .offset(x = pillOffset)
                        .width(tabWidth)
                        .fillMaxHeight()
                        .padding(vertical = 6.dp, horizontal = 2.dp)
                        .clip(RoundedCornerShape(50))
                        .background(pillColor.copy(alpha = 0.30f)),
                )
                Row(Modifier.fillMaxSize()) {
                    DetailTab.values().forEach { tab ->
                        val selected = tab == selectedTab
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50))
                                .clickable { onSelect(tab) },
                            contentAlignment = Alignment.Center,
                        ) {
                            // v1.1.20：未选中也有边框和各自颜色——描边与文字都用该 Tab 色，
                            // 边框盒子与滑动胶囊同尺寸（上下 6dp、左右 2dp 内收），选中靠
                            // 滑动胶囊底色区分，不再全灰。
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(vertical = 6.dp, horizontal = 2.dp)
                                    .clip(RoundedCornerShape(50))
                                    .then(
                                        if (selected) Modifier
                                        else Modifier.border(1.dp, tab.color, RoundedCornerShape(50)),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = tab.label,
                                    color = tab.color,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = if (selected) 16.sp else 15.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // 右边胶囊：收藏、弹幕、倍速
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(cs.surfaceContainerHigh)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(50))
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    IconButton(
                        onClick = onToggleFollow,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            if (following) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (following) "取消追剧" else "追剧",
                            tint = if (following) Color(0xFFF45B7A) else cs.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(
                        onClick = onToggleDanmaku,
                        modifier = Modifier.size(40.dp),
                    ) {
                        DanmakuIcon(enabled = danmakuEnabled)
                    }
                    Box(
                        Modifier
                            .sizeIn(minWidth = 48.dp)
                            .height(40.dp)
                            .clip(CircleShape)
                            .clickable { onRateMenuChange(true) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = PlayerGestureMath.speedLabel(currentSpeed),
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                            color = cs.onSurface,
                            maxLines = 1,
                        )
                    }
                }
                DropdownMenu(
                    expanded = showRateMenu,
                    onDismissRequest = { onRateMenuChange(false) },
                ) {
                    listOf(1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f).forEach { v ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = PlayerGestureMath.speedLabel(v),
                                    fontWeight = if (v == currentSpeed) FontWeight.Bold else FontWeight.Normal,
                                    color = if (v == currentSpeed) cs.primary else cs.onSurface,
                                )
                            },
                            onClick = {
                                onPickRate(v)
                                onRateMenuChange(false)
                            },
                            trailingIcon = if (v == currentSpeed) {
                                {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "当前倍速",
                                        tint = cs.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(cs.outlineVariant),
        )
    }
}

// v1.1.19：弹幕图标——圆角框＋两横线（效果图胶囊里的弹幕开关）；开时绿色、关时灰。
@Composable
private fun DanmakuIcon(enabled: Boolean, modifier: Modifier = Modifier) {
    val color = if (enabled) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color = color,
            style = Stroke(width = stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()),
        )
        drawLine(
            color = color,
            start = Offset(5.dp.toPx(), size.height * 0.38f),
            end = Offset(size.width - 5.dp.toPx(), size.height * 0.38f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(5.dp.toPx(), size.height * 0.62f),
            end = Offset(size.width - 5.dp.toPx(), size.height * 0.62f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

// v1.1.19：线路 chip——选中绿底＋绿字（效果图口径）；未选描边＋灰字。
@Composable
private fun LineChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color(0xFF22C55E) else Color.Transparent)
            .border(
                1.dp,
                if (selected) Color(0xFF22C55E) else cs.outlineVariant,
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else cs.onSurface,
            maxLines = 1,
        )
    }
}

// v1.1.19：同名剧卡——96dp 宽、2:3 海报＋剧名＋备注/年份＋源名绿点；无图时渐变占位＋首字。
@Composable
private fun SameNameCard(vod: Vod, siteName: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(V5.CornerLg))
            .background(cs.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        if (vod.vodPic.isNotBlank()) {
            AsyncImage(
                model = vod.vodPic,
                contentDescription = "海报",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(cs.surfaceContainerHigh),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(cs.primaryContainer, cs.secondaryContainer),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = vod.vodName.take(1).ifBlank { "剧" },
                    style = MaterialTheme.typography.titleLarge,
                    color = cs.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = vod.vodName,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val sub = listOf(vod.vodRemarks, vod.vodYear).filter { it.isNotBlank() }.joinToString(" · ")
        if (sub.isNotBlank()) {
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF22C55E)),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = siteName,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// v1.1.19：追更 Tab——追更卡（剧名＋徽章＋追/取消按钮）、更新进度/已看到、
// 最近更新列表（NEW 只标在未看的最新一集上，不写日期——无真实日期数据）、
// 追更提醒开关（FollowStore 更新检测总开关，12 小时后台查更新、有新集发通知）。
@Composable
private fun FollowPanel(
    title: String,
    following: Boolean,
    onToggleFollow: () -> Unit,
    vodRemarks: String,
    episodes: List<Episode>,
    watchedEp: Int,
    remindEnabled: Boolean,
    onToggleRemind: (Boolean) -> Unit,
    onPlayEpisode: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val latest = episodes.indices.toList().takeLast(4).reversed()
    val progressText = when {
        vodRemarks.isNotBlank() -> vodRemarks
        episodes.isNotEmpty() -> "共 ${episodes.size} 集"
        else -> "暂无剧集信息"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = V5.Gutter, vertical = 8.dp)) {
        // 追更卡
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(V5.CornerLg))
                .background(cs.surfaceContainer)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (following) Color(0xFF3B82F6) else cs.surfaceContainerHigh)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (following) "已追更" else "未追更",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (following) Color.White else cs.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "更新进度：$progressText",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (watchedEp >= 0) "已看到：第 ${watchedEp + 1} 集" else "还没开始看",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = onToggleFollow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (following) "取消追更" else "追更这部剧")
            }
        }
        // 最近更新
        if (latest.isNotEmpty()) {
            Text(
                text = "最近更新",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            )
            latest.forEach { idx ->
                val ep = episodes[idx]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(V5.CornerLg))
                        .background(cs.surfaceContainer)
                        .clickable { onPlayEpisode(idx) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = ep.name.ifBlank { "第${idx + 1}集" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // NEW 只标在未看的最新一集上（追更中才有意义）
                    if (following && idx == latest.first() && watchedEp < idx) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF22C55E))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "NEW",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "播放",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        // 追更提醒开关
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "追更提醒",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                )
                Text(
                    text = "有新集时通知你",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Switch(checked = remindEnabled, onCheckedChange = onToggleRemind)
        }
    }
}

// v1.1.19：剧评 Tab——App 没有剧评数据源，诚实空态，不编造假用户/假星级。
@Composable
private fun ReviewsPanel() {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = V5.Gutter, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "暂无剧评",
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "该片源不提供剧评",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
    }
}

// v1.1.19：推荐 Tab——当前源首页数据（订阅站走 home，本地规则走各引擎 home），
// 三列海报网格（3:4 海报＋剧名＋备注），排除当前在看的一部；点一部在当前源打开它。
// v1.1.20：fallback 时标题改“同类热门”，点卡进它自己的源；空态文案也跟着换。
@Composable
private fun RecsPanel(
    recs: List<RecEntry>?,
    currentVodId: String,
    fallback: Boolean,
    onOpen: (vodId: String, siteKey: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val list = recs?.filter { it.vod.vodId != currentVodId }
    Column(Modifier.fillMaxWidth().padding(horizontal = V5.Gutter, vertical = 8.dp)) {
        Text(
            text = if (fallback) "同类热门" else "看了这部剧的人也在看",
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurface,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        when {
            recs == null -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "正在加载推荐…",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            list.isNullOrEmpty() -> {
                Text(
                    text = if (fallback) "暂无同类热门推荐" else "当前源暂无推荐",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list.size, key = { list[it].vod.vodId }) { i ->
                        val entry = list[i]
                        val vod = entry.vod
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(V5.CornerLg))
                                .clickable { onOpen(vod.vodId, entry.siteKey) },
                        ) {
                            if (vod.vodPic.isNotBlank()) {
                                AsyncImage(
                                    model = vod.vodPic,
                                    contentDescription = vod.vodName,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(3f / 4f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(cs.surfaceContainerHigh),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(3f / 4f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            Brush.linearGradient(
                                                listOf(cs.primaryContainer, cs.secondaryContainer),
                                            ),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = vod.vodName.take(1).ifBlank { "剧" },
                                        style = MaterialTheme.typography.titleLarge,
                                        color = cs.onPrimaryContainer,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = vod.vodName,
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (vod.vodRemarks.isNotBlank()) {
                                Text(
                                    text = vod.vodRemarks,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cs.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// v1.1.19：快搜 Tab——本页内搜全部已启用源（复用搜片页同一条 searchAllFlow），
// 点提交/点热词才搜，不随输入逐字搜；热词是真实搜索历史（为空时只放当前片名一枚）。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickSearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    history: List<String>,
    currentTitle: String,
    onHotWord: (String) -> Unit,
    searching: Boolean,
    results: List<SiteSearchResult>?,
    onOpenResult: (Site, String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = V5.Gutter, vertical = 8.dp)) {
        AppSearchBar(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "搜剧名、演员…",
            onSearch = { onSearch() },
            onClear = { onQueryChange("") },
        )
        val hotWords = if (history.isEmpty()) {
            listOf(currentTitle).filter { it.isNotBlank() }
        } else {
            history.take(6)
        }
        if (hotWords.isNotEmpty()) {
            Text(
                text = "热门搜索",
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                hotWords.forEach { word ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(cs.surfaceContainerHigh)
                            .clickable { onHotWord(word) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = word,
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.onSurface,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (searching && results.isNullOrEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 16.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "正在全网搜索…",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        results?.forEach { group ->
            group.vods.forEach { vod ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(V5.CornerLg))
                        .background(cs.surfaceContainer)
                        .clickable { onOpenResult(group.site, vod.vodId) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = vod.vodName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = listOf(vod.vodRemarks, vod.typeName)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                        if (sub.isNotBlank()) {
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22C55E)),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = group.site.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (!searching && results != null && results.all { it.vods.isEmpty() }) {
            Text(
                text = "没有找到相关结果，换个关键词试试",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

// v1.1.19：推荐数据——照抄首页 engineHome 的口径（订阅当前源 > 本地 drpy > 本地 PY >
// 本地 PHP > 通用网页源）；失败返回空列表（面板给“当前源暂无推荐”）。
private suspend fun loadCurrentSourceHome(context: Context, subSite: Site?): List<Vod> {
    val s = subSite
    if (s != null) return runCatching { SiteRepository().home(s) }.getOrDefault(emptyList())
    return runCatching {
        if (SourceStateStore.activeDrpyRule() != null) DrpySourceManager.home()
        else if (SourceStateStore.activePyRule() != null) PySourceManager.home()
        else if (SourceStateStore.activePhpRule() != null) PhpCliEngine.home(context)
        else if (SourceStateStore.activeWebParseRule() != null) WebParseSourceManager.home()
        else emptyList()
    }.getOrDefault(emptyList())
}

// v1.1.20：推荐条目——Vod 本身不带站信息，fallback 的卡必须记自己的站 key，
// 点开才能进它自己的源详情（与同名剧 goDetail 同口径）。
private data class RecEntry(val vod: Vod, val siteKey: String)

// v1.1.20：推荐 fallback——当前源首页为空时，按当前剧分类从其他源取同类热门：
// 先按分类关键词全源搜（searchAllFlow，各源并发、单源 15 秒超时），
// 再试其他订阅站首页（按分类过滤，最多 3 站、每站 8 秒超时）；
// 全是真实数据，没有就返回空列表（面板给“暂无同类热门推荐”）。
// 同名片（片名一字不差）不算同类推荐，直接排除；按 vodId 去重、上限 12 条。
// 注意：取消要透传，不包成空列表（与同名剧搜索同款处理）。
private suspend fun loadFallbackRecs(
    context: Context,
    subSite: Site?,
    category: String,
    currentTitle: String,
    currentVodId: String,
): List<RecEntry> {
    val out = mutableListOf<RecEntry>()
    val seenIds = mutableSetOf<String>()
    fun accept(vod: Vod, siteKey: String) {
        if (out.size >= 12) return
        if (vod.vodId.isBlank() || vod.vodId == currentVodId) return
        if (vod.vodName.trim().isNotEmpty() && vod.vodName.trim() == currentTitle.trim()) return
        if (!seenIds.add(vod.vodId)) return
        out += RecEntry(vod, siteKey)
    }
    val repo = SiteRepository(
        drpyEngine = DrpySourceManager.drpyEngineAdapter,
        pyEngine = PySourceManager.engineAdapter,
    )
    val currentKeys = buildSet {
        subSite?.key?.takeIf { it.isNotBlank() }?.let { add(it) }
        DrpySourceManager.activeRuleName()?.let { add("$LOCAL_DRPY_PREFIX$it") }
        PySourceManager.activeRuleName()?.let { add("$LOCAL_PY_PREFIX$it") }
        PhpSourceManager.activeRuleName()?.let { add("php:$it") }
    }
    try {
        repo.loadFromConfig(withContext(Dispatchers.IO) { buildSearchConfigJson(context) })
        if (category.isNotBlank()) {
            repo.searchAllFlow(category).collect { group ->
                if (group.site.key in currentKeys) return@collect
                for (vod in group.vods) accept(vod, group.site.key)
            }
        }
        if (out.isEmpty()) {
            val others = repo.getEnabledSites().filter { it.key !in currentKeys }.take(3)
            for (site in others) {
                if (out.size >= 12) break
                val home = withTimeoutOrNull(8_000) {
                    runCatching { repo.home(site) }.getOrDefault(emptyList())
                }.orEmpty()
                for (vod in home) {
                    if (category.isNotBlank() && vod.typeName.trim() != category.trim()) continue
                    accept(vod, site.key)
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LogBus.w("DetailRecs", "推荐 fallback 失败：${e.message}")
    }
    return out
}

// v1.1.19：颜色混合——把 from 往 to 兑 ratio（0..1），背景跟随海报用。
private fun blendColor(from: Color, to: Color, ratio: Float): Color {
    val r = ratio.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * r,
        green = from.green + (to.green - from.green) * r,
        blue = from.blue + (to.blue - from.blue) * r,
        alpha = from.alpha + (to.alpha - from.alpha) * r,
    )
}

// v1.1.19：系统减动效——照抄 V5Components.kt 的 rememberReducedMotion（v0.248.0）：
// 用平台标准接口（动画时长缩放为 0 或无障碍移除动画时均返回 false），
// 进页读一次、每次回到前台重读。
@Composable
private fun rememberReducedMotion(): Boolean {
    var reducedMotion by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reducedMotion = !ValueAnimator.areAnimatorsEnabled()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return reducedMotion
}
