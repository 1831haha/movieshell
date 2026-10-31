package com.movieshell.ui.detail

import androidx.compose.ui.draw.alpha
import com.movieshell.ui.theme.ok

import com.movieshell.data.repo.FollowItem

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.semantics

import androidx.compose.foundation.layout.widthIn

import androidx.compose.foundation.layout.PaddingValues

import androidx.compose.material3.FilledTonalButton

import androidx.compose.material3.Button

import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight

import androidx.compose.material.icons.filled.PlayArrow

import androidx.compose.material.icons.filled.Add

import androidx.compose.material.icons.filled.Check

import androidx.compose.material.icons.filled.Tag

import androidx.compose.material.icons.filled.GraphicEq

import androidx.compose.foundation.lazy.LazyRow

import androidx.compose.foundation.border

import com.movieshell.ui.theme.V5

import com.movieshell.ui.components.AppToast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
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
import coil.compose.AsyncImage
import com.movieshell.data.download.DownloadManager
import com.movieshell.data.model.Episode
import com.movieshell.data.model.PlayLine
import com.movieshell.data.model.VodDetail
import com.movieshell.data.repo.DetailSiteRoute
import com.movieshell.data.repo.DownloadSettings
import com.movieshell.data.repo.DownloadTask
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.FollowStore
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PhpCliEngine
import com.movieshell.data.repo.SourceStateStore
import com.movieshell.data.repo.PhpSourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SiteRepository
import com.movieshell.data.repo.StoragePaths
import android.app.Activity
import android.content.pm.ActivityInfo
import com.movieshell.player.MagnetPlayer
import com.movieshell.player.OrientationSensor
import com.movieshell.player.PlayResolveScope
import com.movieshell.player.PlaySession
import com.movieshell.player.EngineHost
import com.movieshell.player.PicsViewerScreen
import com.movieshell.player.InfoPopupDialog
import com.movieshell.player.PlayerScreen
import com.movieshell.player.resolvePlayUrl
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.V5Chip
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.PlainBlock
import com.movieshell.ui.components.LoadingSkeleton
import com.movieshell.ui.components.V5SectionTitle
import com.movieshell.ui.theme.BrandGradientColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    vodId: String,
    // B1：详情站点跟路由参数走；null 或哨兵值表示老路由，才回退 holder。
    siteKey: String? = null,
    // v0.75.0：继续观看带过来的起播集（-1=不指定，走断点续播/默认逻辑）
    initialEpisode: Int = -1,
    onBack: () -> Unit,
    onPlay: (playUrl: String, title: String) -> Unit = { _, _ -> },
    // v0.87.0：点演员名搜其作品
    onActorClick: (actorName: String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // v0.217.0（F16）：详情页真离开时停播的开关——点全屏跳独立播放页时
    // 先置 true，dispose 时跳过停播（独立页接管同一会话）；其他离开路径
    // （返回、进演员页等）为 false，dispose 时停引擎、清会话、关可见标记。
    var goingFullscreen by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            if (!goingFullscreen) {
                EngineHost.stop()
                PlaySession.clear()
                PlaySession.playerVisible = false
                // v0.266.0：真离开详情页时复位方向——嵌入播放退出原来
                // 不碰方向（见 PlayerScreen 注释），手动切屏锁/重力锁会
                // 残留到首页，导致“横屏回不到竖屏”；跳全屏时不复位，
                // 独立页接管同一会话、由它自己退出时复位。
                OrientationSensor.stop()
                (context as? Activity)?.let {
                    if (it.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                        it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
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
            PhpSourceManager.activeRuleName() != null
    }
    // v0.267.0（T7）：原来 detailLoading 初始 false，首帧
    // detail==null 先落入失败分支，LaunchedEffect 调 reloadDetail
    // 置 true 后才变骨架，首帧先闪一下失败；进页即加载，初始
    // 直接置 true，失败只在请求回来后才出现。
    var detailLoading by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<VodDetail?>(null) }
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
            else -> "站源"
        }
    }
    val followSourceKey = remember(subSite, sourceName) {
        subSite?.key.orEmpty().ifBlank {
            DrpySourceManager.activeRuleName().orEmpty()
                .ifBlank { PySourceManager.activeRuleName().orEmpty() }
                .ifBlank { PhpSourceManager.activeRuleName().orEmpty() }
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
    val safeLine = if (playLines.isNotEmpty()) selectedLine.coerceIn(playLines.indices) else 0
    val lineEpisodes: List<Episode> = playLines.getOrNull(safeLine)?.episodes.orEmpty()

    // v0.218.0（S27）：界面 Vod 已删，详情页直接读 VodDetail——标题／评分／
    // 年份／简介／主演见下面各处，集数读解析出的 episodes；地区原来恒空，
    // 对应的 MetaChip 一并删掉。detail 失败时直接为 null 走失败态。

    var selectedEpisode by rememberSaveable(vodId) { mutableIntStateOf(0) }
    // v0.77.0：跳集弹窗
    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    // v0.47.0：简介默认折叠
    var showDetailInfo by remember { mutableStateOf(false) } // v0.259.0：详情简介入口，点开简介弹窗
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

    // v0.71.0：带已看角标的选集 chip（追剧且看到该集时右上角标记）
    // v0.292.0 V5：48dp 12 圆角方格——surfaceContainerHigh；当前集 primary + 均衡器图标；已看右上 tertiary 圆点
    @Composable
    fun EpisodeChip(index: Int, name: String, selected: Boolean, onClick: () -> Unit) {
        val v = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(V5.CornerMd))
                .background(if (selected) v.primary else v.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = "正在播放",
                        tint = v.onPrimary,
                        modifier = Modifier.size(16.dp),
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
                val ok = runCatching {
                    MagnetPlayer.playIfReady(context, ep.url, detailTitle(detail, vodId))
                }.getOrDefault(false)
                // 世代已过：新一集已在解析，本单不再动任何状态
                if (myGen != playGen) return@launch
                // 服务通则已直接起播；不通则弹窗提示
                if (!ok) { lastMagnetUrl = ep.url; showMagnetDialog = true }
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

    val safeSelected = if (lineEpisodes.isNotEmpty()) {
        selectedEpisode.coerceIn(0, lineEpisodes.size - 1)
    } else selectedEpisode
    // v0.65：剧集高亮跟随实际播放（嵌入播放器内切集时同步）
    val highlightEp = if (embeddedUrl.isNotBlank()) playingEp else selectedEpisode
    // v0.62：进详情页自动播放（用户要求，不需要点播放键）
    // v0.73.0：断点续播——追剧的剧从"看到第几集"继续播，而不是永远第1集
    // v0.75.0：继续观看带集数进来时优先用它
    var autoPlayed by remember(vodId) { mutableStateOf(false) }
    LaunchedEffect(detail, lineEpisodes, resumeReady) {
        if (!autoPlayed && detail != null && lineEpisodes.isNotEmpty() && resumeReady) {
            autoPlayed = true
            val startEp = when {
                initialEpisode >= 0 -> initialEpisode.coerceIn(lineEpisodes.indices)
                following && watchedEp >= 0 -> watchedEp.coerceIn(lineEpisodes.indices)
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
                val showMiniPlayer = playerOffScreen && embeddedUrl.isNotBlank()
                Box(
                    // v0.261.0：播放器贴边——只取上下内边距，左右内边距忽略，
                    // 否则 Detail Scaffold 的左右 inset + MainActivity 的左右 inset
                    // 叠加后播放器左侧留白（实测约 107/1000 宽），详情卡本身有 PagePadding，不受影响。
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding()),
                ) {
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
    
                    // v0.292.0 V5（design-tokens 5.2 / 02-detail）：信息区——不再套卡片，背景走 surface。
                    // 海报 16dp 圆角 + 更新标签（labelLarge primary）+ headlineMedium 片名 + 元信息 chip + 线路 chip；
                    // 主操作：继续播放（56dp 28 圆角 filled）+ 追剧（tonal，接 FollowStore 原有追/取消逻辑，
                    // 与播放器顶栏追剧开关读写同一条记录）；简介卡（点开原简介弹窗）；主演 chip（点按仍走演员搜索）。
                    item {
                        val v = MaterialTheme.colorScheme
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                modifier = Modifier.padding(horizontal = V5.Gutter),
                            ) {
                                if (coverUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = coverUrl,
                                        contentDescription = "海报",
                                        modifier = Modifier
                                            .size(width = 112.dp, height = 158.dp)
                                            .clip(RoundedCornerShape(V5.CornerLg))
                                            .background(v.surfaceContainerHigh)
                                            .clickable { showCoverDialog = true },
                                        contentScale = ContentScale.Crop,
                                    )
                                    Spacer(Modifier.width(16.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    if (detail?.vodRemarks?.isNotBlank() == true) {
                                        Text(
                                            text = detail?.vodRemarks.orEmpty(),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = v.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                    }
                                    Text(
                                        text = detailTitle(detail, vodId),
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = v.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    // v0.121.0：空字段不渲染 chip
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    ) {
                                        MetaChip("${episodes.size}集")
                                        detail?.vodYear?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                                    }
                                    // v0.84.0：线路胶囊（当前线路名 + 切换图标），点按展开线路选择——
                                    // 下方“播放线路”FilterChip 同步
                                    if (playLines.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .height(32.dp)
                                                .clip(RoundedCornerShape(V5.CornerSm))
                                                .border(1.dp, v.outlineVariant, RoundedCornerShape(V5.CornerSm))
                                                .padding(horizontal = 10.dp),
                                        ) {
                                            Box(Modifier.size(6.dp).clip(CircleShape).background(v.ok))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = playLines[safeLine].name,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = v.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 140.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Filled.SwapHoriz,
                                                contentDescription = "切换源",
                                                tint = v.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                            // 主操作行
                            Row(
                                modifier = Modifier.padding(horizontal = V5.Gutter),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val canPlay = lineEpisodes.isNotEmpty() || episodes.isNotEmpty()
                                val playLabel = when {
                                    !canPlay -> "暂无可播放剧集"
                                    embeddedUrl.isNotBlank() || (following && watchedEp >= 0) ->
                                        "继续播放 · 第 ${highlightEp + 1} 集"
                                    else -> "立即播放"
                                }
                                Button(
                                    onClick = {
                                        if (embeddedUrl.isNotBlank()) {
                                            // 已在顶部播放：回到播放器
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
                                    enabled = canPlay,
                                    shape = RoundedCornerShape(V5.CornerXl),
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(V5.ButtonProminent),
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(playLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                }
                                FilledTonalButton(
                                    onClick = {
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
                                    },
                                    shape = RoundedCornerShape(V5.CornerXl),
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    modifier = Modifier.height(V5.ButtonProminent),
                                ) {
                                    Icon(
                                        if (following) Icons.Filled.Check else Icons.Filled.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (following) "已追剧" else "追剧", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            // 简介卡：点开原简介弹窗（v0.259.0 InfoPopupDialog）
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = V5.Gutter)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(V5.CornerLgI))
                                    .background(v.surfaceContainer)
                                    .clickable { showDetailInfo = true }
                                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "简介",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = v.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = "查看简介弹窗",
                                        tint = v.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = detail?.vodDesc?.ifBlank { "暂无简介" }.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = v.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
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
                    // v0.292.0 V5：主操作行回到信息区（继续播放/追剧），顶部嵌入播放器自动播行为不变
    
                    // 选集：drpy 真实选集优先；多条线路时上方显示“播放线路”FilterChip 行
                    when {
                        lineEpisodes.isNotEmpty() -> {
                            if (playLines.size > 1) {
                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = V5.Gutter, end = V5.Gutter, top = 12.dp, bottom = 8.dp),
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
                                            V5Chip(
                                                text = playLines[i].name,
                                                selected = safeLine == i,
                                                onClick = {
                                                    selectedLine = i
                                                    selectedEpisode = 0
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            item {
                                V5SectionTitle(
                                    title = "选集 · 共 ${lineEpisodes.size} 集",
                                    modifier = Modifier.padding(
                                        horizontal = V5.Gutter,
                                        vertical = 8.dp,
                                    ),
                                    // v0.77.0：集数多时给"跳集"入口，短剧 60-80 集不用手翻
                                    action = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (lineEpisodes.size > 20) {
                                                TextButton(onClick = { showJumpDialog = true }) {
                                                    Icon(Icons.Filled.Tag, contentDescription = null, modifier = Modifier.size(18.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("跳集", style = MaterialTheme.typography.labelLarge)
                                                }
                                            }
                                            // v0.259.0：下载唯一入口移到选集区标题旁，
                                            // 点开现有选多集入队弹窗，逻辑不动；播放器选集抽屉不放。
                                            IconButton(
                                                onClick = { showDownloadDialog = true },
                                                enabled = lineEpisodes.isNotEmpty(),
                                            ) {
                                                Icon(
                                                    Icons.Filled.Download,
                                                    contentDescription = "下载",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                            item {
                                // v0.68：选集改可滚动网格，自动定位到正在播的集
                                val epGridState = rememberLazyGridState()
                                LaunchedEffect(highlightEp, lineEpisodes.size) {
                                    if (lineEpisodes.isNotEmpty()) {
                                        runCatching {
                                            epGridState.scrollToItem(highlightEp.coerceIn(0, lineEpisodes.size - 1))
                                        }
                                    }
                                }
                                // v0.292.0 V5：短集名（纯数字/第N集）走 5 列 48dp 方格，长集名退回自适应宽格
                                val shortNames = lineEpisodes.all { episodeLabel(it.name).length <= 4 }
                                LazyVerticalGrid(
                                    columns = if (shortNames) GridCells.Fixed(5) else GridCells.Adaptive(minSize = 96.dp),
                                    state = epGridState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = V5.Gutter)
                                        .heightIn(max = 320.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(
                                        lineEpisodes.size,
                                        // v0.188.0：按集身份取 key，不用下标；换线路后下标复用不再串位。
                                        // Episode 无 id 字段，用线路名加集名加地址拼身份。
                                        key = { episodeKey(lineEpisodes[it]) },
                                    ) { i ->
                                        val ep = lineEpisodes[i]
                                        // v0.71.0：已看角标
                                        EpisodeChip(
                                            index = i,
                                            name = ep.name.ifBlank { "第${i + 1}集" },
                                            // v0.65：高亮跟随实际播放
                                            selected = highlightEp == i,
                                            // v0.65：点选集直接播（对标目标界面）
                                            onClick = { playEpisode(ep, safeLine, i) },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                        episodes.size > 1 -> {
                            item {
                                V5SectionTitle(
                                    title = "选集 · 共 ${episodes.size} 集",
                                    modifier = Modifier.padding(
                                        horizontal = V5.Gutter,
                                        vertical = 8.dp,
                                    ),
                                )
                            }
                            item {
                                FlowRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = V5.Gutter),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    repeat(episodes.size) { i ->
                                        // v0.71.0：已看角标
                                        Box(Modifier.width(64.dp)) {
                                        EpisodeChip(
                                            index = i,
                                            name = "第${i + 1}集",
                                            // v0.65：高亮跟随实际播放
                                            selected = highlightEp == i,
                                            // v0.186.0：第二分支此前点按只改选中态、毫无反馈。
                                            // 先按集数取已解析的真实选集、复用第一分支的
                                            // playEpisode 直拨；取不到说明该源没有给
                                            // 出可播地址，直接 Toast，绝不凭空编地址。
                                            onClick = {
                                                selectedEpisode = i
                                                val ep = episodes.getOrNull(i)
                                                if (ep != null) {
                                                    playEpisode(ep, safeLine, i)
                                                } else {
                                                    AppToast.show(context, "该源未返回可播地址", long = false)
                                                }
                                            },
                                        )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                        else -> {
                            // v0.171.0：0 集时不再只剩标题加空白，给一句空提示
                            item {
                                Text(
                                    text = "暂无可播放剧集",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        horizontal = V5.Gutter,
                                        vertical = 8.dp,
                                    ),
                                )
                            }
                            item { Spacer(Modifier.height(16.dp)) }
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
                                if (mini) {
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
                                if (!mini && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
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
                            onBack = onBack,
                            embedded = true,
                            onFullscreen = {
                                goingFullscreen = true
                                onPlay(embeddedUrl, embeddedTitle)
                            },
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
            TechDialog(
                onDismissRequest = { showMagnetDialog = false },
                title = "磁力还不能播",
                confirmText = "用其他应用打开",
                onConfirm = {
                    if (lastMagnetUrl.isNotBlank()) MagnetPlayer.openExternally(context, lastMagnetUrl)
                    showMagnetDialog = false
                },
                dismissText = "知道了",
            ) {
                Text(
                    MagnetPlayer.lastFailMessage
                        ?: "本机磁力转播服务未就绪。请到「插件中心 → 服务」安装并启动「磁力转播服务」，或用其他应用打开。",
                )
            }
        }

    
        // v0.259.0：详情简介弹窗，与播放器信息图标共用同一套五字段规则
        if (showDetailInfo) {
            InfoPopupDialog(
                name = detailTitle(detail, vodId),
                remarks = detail?.vodRemarks.orEmpty(),
                year = detail?.vodYear.orEmpty(),
                desc = detail?.vodDesc.orEmpty(),
                vodId = vodId,
                onDismiss = { showDetailInfo = false },
            )
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

@Composable
private fun MetaChip(text: String) {
    // v0.292.0 V5：元信息 chip——28dp、8 圆角、surfaceContainerHigh
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(V5.CornerSm))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
