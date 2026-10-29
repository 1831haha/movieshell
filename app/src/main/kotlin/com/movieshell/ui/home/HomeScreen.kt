package com.movieshell.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import com.movieshell.ui.components.AppToast
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.FilterGroup
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod as ModelVod
import com.movieshell.data.model.isCmsSite
import com.movieshell.data.model.isDrpySite
import com.movieshell.data.model.isJavaSpider
import com.movieshell.data.model.isPySite
import com.movieshell.data.repo.ApiSourceStore
import com.movieshell.data.repo.DetailSiteHolder
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.HomeCache
import com.movieshell.data.repo.HomeLayout
import com.movieshell.data.repo.HomeLoadGate
import com.movieshell.data.repo.HomeSettings
import com.movieshell.data.repo.PhpCliEngine
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SiteRepository
import com.movieshell.data.repo.SourceStateStore
import com.movieshell.data.repo.SubSiteEngines
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppChip
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.data.repo.ThemeStore
import com.movieshell.ui.components.FilterBar
import com.movieshell.ui.components.PosterCard
import android.app.Activity
import android.content.pm.ActivityInfo
import com.movieshell.player.HistoryEntry
import com.movieshell.player.HistoryStore
import com.movieshell.player.OrientationSensor
import com.movieshell.player.PlaySession
import com.movieshell.ui.components.SectionTitle
import com.movieshell.ui.components.StatusRing
import com.movieshell.ui.components.StatusRingState
import com.movieshell.ui.components.color
import com.movieshell.ui.settings.SettingsRow
import com.movieshell.ui.theme.FlowingGlowBackground
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.basicMarquee
import com.movieshell.data.repo.BackgroundStore

// v0.27.6：进程级标记，区分冷启动和 tab 切回（切 tab 时 HomeScreen 被 dispose，重进 composition）。
// 冷启动保持干净空态，切回才静默恢复缓存。
private var homeEnteredOnce = false

// v0.32.1：分类列表的 rememberSaveable 存取器已移除——restoreHomeIfEmpty 会从缓存重载，
// 不需要自定义 Saver（之前 Saver 的 MutableState 包裹类型导致编译失败，教训）。

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onSearchClick: () -> Unit,
    onVodClick: (vodId: String, title: String) -> Unit,
    // v0.24.0：播放历史入口
    onHistoryClick: () -> Unit = {},
    // v0.26.0：我的追剧入口
    onFollowClick: () -> Unit = {},
    // v0.35.0：小说书架入口
    onBookshelfClick: () -> Unit = {},
    // v0.139.0：首页空态“去添加站源”入口（站源管理在设置页）
    onManageSources: () -> Unit = {},
    // v0.75.0：继续观看——带集数进详情页（默认走 onVodClick）
    // v0.189.0：带源类型和源 key，进详情前先恢复到原来那个源
    onContinueWatch: (vodId: String, title: String, episodeIndex: Int, sourceType: String, sourceKey: String) -> Unit =
        { v, t, _, _, _ -> onVodClick(v, t) },
) {
    // v0.212.0 审计修复：原来普通 remember，详情返回/切 tab 后
    // 海报滚动位置每次回顶。改用官方 LazyGridState.Saver 存进
    // SaveableState，返回时恢复到上次浏览位置。
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // ---- 订阅当前源（v0.5.1）：站源页"设为点播页当前源"指定的订阅子站点 ----
    val subRepo = remember { SiteRepository() }
    var subSite by remember { mutableStateOf<Site?>(null) }
    var lastSubKey by rememberSaveable { mutableStateOf<String?>(null) }

    /** 读持久化的订阅当前源；记录被删/被标不支持/订阅被关时返回 null */
    fun currentSubSite(): Site? {
        val cur = SubSiteStore.getCurrent(context) ?: return null
        val subEnabled =
            ApiSourceStore.load(context).find { it.url == cur.subUrl }?.enabled ?: false
        if (!subEnabled) return null
        val cache = SubSiteStore.getCached(context, cur.subUrl) ?: return null
        val rec = cache.sites.firstOrNull { it.key == cur.siteKey } ?: return null
        if (!rec.enabled || rec.kind == SubSiteStore.KIND_UNSUPPORTED) return null
        return SubSiteStore.toSite(cur.subUrl, rec)
    }

    /** 当前数据源的首页：订阅当前源 > 本地 drpy > 本地 PY > 本地 PHP（CLI） */
    suspend fun engineHome(): List<ModelVod> {
        val s = subSite
        if (s != null) return runCatching { subRepo.home(s) }.getOrDefault(emptyList())
        return runCatching {
            if (SourceStateStore.activeDrpyRule() != null) DrpySourceManager.home()
            else if (SourceStateStore.activePyRule() != null) PySourceManager.home()
            else if (SourceStateStore.activePhpRule() != null) PhpCliEngine.home(context)
            else emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * 当前数据源的分类：订阅 CMS 站走 CMS 分类接口，drpy/PY 站走引擎 categories()。
     * v0.217.0（F2）：照抄 engineCategory 的可空口径——异常返回 null（调用方判失败，
     * 给可见反馈+重试），不再吞成 emptyList；空列表只表示规则真没分类。
     */
    suspend fun engineCats(): List<CategoryType>? {
        val s = subSite
        if (s != null) {
            if (s.isCmsSite()) return runCatching { subRepo.category(s, "", 1).first }
                .getOrNull()
            if (s.isJavaSpider()) return runCatching { subRepo.category(s, "", 1).first }
                .getOrNull()
            if (s.isPySite()) return runCatching { SubSiteEngines.pyEngineFor(s)?.categories() }
                .getOrNull()
            return runCatching { SubSiteEngines.drpyEngineFor(s)?.categories() }
                .getOrNull()
        }
        return runCatching {
            if (SourceStateStore.activeDrpyRule() != null) DrpySourceManager.categories()
            else if (SourceStateStore.activePyRule() != null) PySourceManager.categories()
            else if (SourceStateStore.activePhpRule() != null) PhpCliEngine.categories(context)
            else emptyList()
        }.getOrNull()
    }

    /**
     * 当前数据源的分类内容。
     * v0.110.0：异常返回 null（调用方判失败，给可见反馈+重试），不再吞成 emptyList；
     * 空列表=规则真没内容（非失败）。唯一调用方 loadDrpyCategory。
     */
    suspend fun engineCategory(
        tid: String,
        pg: Int,
        filter: Map<String, String> = emptyMap()
    ): List<ModelVod>? {
        val s = subSite
        // v0.44.0：订阅路径也要透传筛选（之前丢了，drpy 筛选点了没效果）
        if (s != null) return runCatching { subRepo.category(s, tid, pg, filter).second }
            .getOrNull()
        return runCatching {
            // v0.44.0：订阅路径也要透传筛选（之前丢了，drpy 筛选点了没效果）
            // v0.48.0：本地 drpy 同样漏传 filter（v0.44.0 宣称生效实际没传），补上
            if (SourceStateStore.activeDrpyRule() != null) DrpySourceManager.category(tid, pg, filter)
            else if (SourceStateStore.activePyRule() != null) PySourceManager.category(tid, pg, filter)
            else if (SourceStateStore.activePhpRule() != null) PhpCliEngine.category(context, tid, pg)
            else emptyList()
        }.getOrNull()
    }

    /** v0.44.0：当前源是否为 drpy（订阅站或本地规则）。 */
    fun isDrpySource(): Boolean {
        val s = subSite
        if (s != null) return s.isDrpySite()
        return SourceStateStore.activeDrpyRule() != null
    }

    /** v0.44.0：拉取某 drpy 分类的筛选组（订阅走 SiteRepository，本地走 DrpySourceManager）。 */
    suspend fun fetchDrpyFilters(tid: String): List<FilterGroup> {
        val s = subSite
        return if (s != null) {
            runCatching { subRepo.filters(s, tid) }.getOrNull().orEmpty()
        } else {
            runCatching { DrpySourceManager.filters(tid) }.getOrNull().orEmpty()
        }
    }

    /**
     * 进详情：订阅当前源时把站点上下文带给详情页（详情/播放按站走引擎），
     * 否则传 null，详情页走本地规则旧逻辑。
     */
    fun goDetail(vodId: String, title: String = "") {
        LogBus.i("UI", "点击影片卡片 id=$vodId")
        DetailSiteHolder.site = subSite
        onVodClick(vodId, title)
    }

    var refreshing by remember { mutableStateOf(false) }
    // v0.45.0：下拉刷新换官方 Material3 PullToRefreshBox（手搓的 NestedScrollConnection
    // 要拖 360px 才触发，手感太钝，删掉）。
    val pullRefreshState = rememberPullToRefreshState()
    // v0.53.0：三点核心——选中/分类/列表/加载态全在 HomeViewModel 里。
    // detail pop 回来时 VM 在 back stack 里不重建，第一帧直接出缓存。
    // v0.217.0（F1）：selectedCategory 存的是 typeId（""=推荐），序号只做界面展示：
    // selectedPosition 由 typeId 到分类列表里现算，分类顺序一变也不错位。
    val selectedCategory by vm.selectedCategoryId.collectAsState()
    val drpyCats by vm.categories.collectAsState()
    val selectedPosition = if (selectedCategory == "") 0
        else drpyCats.indexOfFirst { it.typeId == selectedCategory } + 1
    val drpyList by vm.currentVideos.collectAsState()
    val drpyLoading by vm.isLoading.collectAsState()
    // v0.58：切分类 loading 标记（null=空闲；非 null=正在加载该分类，旧列表保留不闪空）
    val loadingCat by vm.loadingCategoryId.collectAsState()
    // v0.110.0：分类内容加载失败标记（超时/规则异常），UI 给内联错误+重试
    val failedCat by vm.failedCategoryId.collectAsState()
    // v0.217.0（F4）：到底标记跟 VM 走（按 typeId），切 tab 回来不复活
    val noMoreCats by vm.categoryNoMore.collectAsState()
    // v0.44.0：二级筛选升级为多组 Map（key->value），空=未选（用规则默认）。
    // v0.31.2 的教训：rememberSaveable 存 Map 有坑，用 List<String> 存 "key\u0001value" 编码。
    // tab 切回时选中态不丢；切分类时清空（不同分类的筛选组不同）。
    var filterEntries by rememberSaveable { mutableStateOf(listOf<String>()) }
    fun decodeFilter(s: String): Pair<String, String> =
        s.substringBefore("\u0001") to s.substringAfter("\u0001")
    val filterMap: Map<String, String> = filterEntries.map(::decodeFilter).toMap()
    fun setFilters(m: Map<String, String>) {
        filterEntries = m.map { (k, v) -> "$k\u0001$v" }
    }
    // v0.31.2：内容世代——每次发起内容加载（首页/分类）+1，异步完成时只应用最新世代的结果，
    // 旧请求的迟到结果直接丢弃。快速连点/首页与分类打架时，"最后一次点击赢"是唯一不变量。
    var contentGen by remember { mutableIntStateOf(0) }
    // v0.31.2：分类加载单飞——快速连点时取消上一次，保证同一时间只有一个分类请求在跑
    var categoryJob by remember { mutableStateOf<Job?>(null) }
    var shuffleSeed by remember { mutableIntStateOf(0) }
    // v0.215.0：分类分页——加载更多进行态（切分类时重置）；页码与到底标记已搬进 VM（F4）。
    var catLoadingMore by remember { mutableStateOf(false) }

    // ---- drpy / PY 规则接入（drpy 优先，PY 兜底） ----
    // v0.53.0：drpyActive/drpyLoading/drpyCats/drpyList 已搬进 HomeViewModel（见上）。
    var drpyActive by rememberSaveable { mutableStateOf(false) }
    var homeError by remember { mutableStateOf<String?>(null) } // v0.25.0：加载失败文案
    var phpHomeError by remember { mutableStateOf<String?>(null) } // v0.152.0：PHP 首页失败原因，透出到推荐空态
    var catsFailed by remember { mutableStateOf(false) } // v0.29.0：分类失败且为空，给重试入口
    var recommendStale by remember { mutableStateOf(false) } // v0.50.0：展示的是过期缓存/3s降级，顶部小字提示
    var recommendEmpty by remember { mutableStateOf(false) } // v0.50.0：3s 首屏无数据，推荐区空态+重试

    // v0.50.0（用户补）：慢请求回来时先确认首页还在前台才刷 UI；在后台只写缓存，防推荐区跳变
    val homeLifecycle = LocalLifecycleOwner.current.lifecycle
    fun isHomeForeground(): Boolean =
        homeLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    // v0.75.0：继续观看——每次回到首页刷新（只留有点播 vodId、没看完的）
    // v0.266.0：首页继续观看只留最新 5 张（原来 10 张）；历史页与
    // HistoryStore（最多 50 条）按以前模式不变，只改首页展示条数。
    var watchHistory by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    fun reloadWatchHistory() {
        watchHistory = HistoryStore.getHistory(context)
            .filter { e ->
                e.kind == "vod" && e.vodId.isNotBlank() &&
                    !(e.durationMs > 0 && e.positionMs >= e.durationMs * 0.95)
            }
            .take(5)
    }
    DisposableEffect(homeLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // v0.266.0：回到首页且没有播放器在前台时，复位方向锁——
                // 嵌入播放退出（DetailScreen dispose）不碰方向、看图页不处理
                // 方向时，手动锁/重力感应锁会一路带到首页，导致“横屏回不到
                // 竖屏”；首页本就该跟手机转，这里兜底复位到系统默认。
                if (!PlaySession.playerVisible) {
                    OrientationSensor.stop()
                    (context as? Activity)?.let {
                        if (it.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }
                }
                reloadWatchHistory()
            }
        }
        homeLifecycle.addObserver(observer)
        onDispose { homeLifecycle.removeObserver(observer) }
    }

    /** v0.25.0：当前数据源的缓存 key（订阅站 key / 本地规则名） */
    fun homeCacheKey(): String? = when {
        subSite != null -> "sub_${subSite?.key}"
        SourceStateStore.activeDrpyRule() != null -> "drpy_${DrpySourceManager.activeRuleName()}"
        SourceStateStore.activePyRule() != null -> "py_${PySourceManager.activeRuleName()}"
        SourceStateStore.activePhpRule() != null -> "php_${SourceStateStore.activePhpRule()}"
        else -> null
    }

    /**
     * 等待单飞结果。异常（含取消）一律按 null 处理、调用方按失败态展示：
     * 这里是 fire-and-forget 的收尾路径，宁可把清理（finish）和错误态做完，
     * 也不让取消导致转圈卡死或登记泄漏。
     */
    suspend fun awaitFlight(d: Deferred<HomeLoadGate.HomePayload?>): HomeLoadGate.HomePayload? =
        try {
            d.await()
        } catch (e: Exception) {
            null
        }

    /**
     * v0.31.0：单飞执行方的实际工作——缓存先行 + 并发拉网络，算好最终 UI 状态，
     * 打包成 HomePayload。缓存落盘只在这里做一次，复用方不重复写。
     *
     * v0.50.0：三处大改——
     * - A：分类拿到立刻渲染，不等推荐（之前 coroutineScope 等两路齐才 apply）；
     * - B：推荐 3s 首屏降级（stale 缓存/空态）；首屏结算 5s 截断，25s 硬上限保留给慢规则后台回填；
     * - E：缓存先行即时上屏（过期也展示标 stale），返回首页永远有东西；
     * - 用户补：慢请求回来先确认前台才刷 UI，后台只写缓存，防推荐区跳变。
     */
    suspend fun fetchHomePayload(key: String, myGen: Int): HomeLoadGate.HomePayload {
        // 1. 缓存先行
        var cacheVods: List<ModelVod> = emptyList()
        var cacheCats: List<CategoryType> = emptyList()
        var cacheFresh = false
        var cacheShown = false
        // v0.217.0（F7）：推荐的新鲜度只看推荐自己的保存时间，不跟分类共用。
        withContext(Dispatchers.IO) { HomeCache.load(context, key) }?.let { result ->
            val data = result.data
            cacheCats = data.cats
            cacheFresh = result.vodsFresh
            if (data.vods.isNotEmpty()) {
                cacheVods = data.vods
                cacheShown = true
                LogBus.i("Home", "首页缓存先行（推荐${if (result.vodsFresh) "新鲜" else "过期stale"}／分类${if (result.catsFresh) "新鲜" else "过期stale"}）：${data.vods.size} 条")
            }
        }
        // v0.50.0 E：缓存立刻上屏，不等网络
        if ((cacheShown || cacheCats.isNotEmpty()) && myGen == contentGen && isHomeForeground()) {
            // v0.109.0：drpyList 恒不为 null（currentVideos 派生），之前写 == null
            // 导致缓存先行即时上屏恒被跳过——冷启动无数据时白等网络。改判 isEmpty()
            if (cacheShown && drpyList.isEmpty()) {
                vm.setRecommend(cacheVods)
                recommendStale = !cacheFresh
            }
            if (cacheCats.isNotEmpty() && drpyCats.isEmpty()) vm.setCategories(cacheCats)
        }
        // 2. 并发刷新（推荐/分类各 25s 熔断，互不取消；首屏结算只等 5s）
        var netVods: List<ModelVod>? = null
        var netCats: List<CategoryType>? = null
        // v0.166.0：首屏等待队列 5s 截断——两路挂外层 scope，结算最多等 5s；
        // 慢路不取消，25s 上限内回来照样前台补填（v0.31.1 慢规则保留）。
        // 降级计时与推荐路平级（不当子协程），免得子协程拖住推荐路的 join。
        val timerJob = scope.launch {
            try {
                // v0.166.0：降级 3s——推荐没回来先降级，不白屏等 5s
                delay(3_000)
                if (netVods != null || myGen != contentGen) return@launch
                // v0.106.0：drpyList 恒不为 null（currentVideos 派生），之前写 != null
                // 导致降级恒被跳过——熔断前用户一直看白屏。改判 isNotEmpty()
                if (drpyList.isNotEmpty() || !isHomeForeground()) return@launch
                val stale = cacheVods.takeIf { it.isNotEmpty() }
                if (stale != null) {
                    vm.setRecommend(stale)
                    recommendStale = true
                } else {
                    recommendEmpty = true
                }
                vm.setLoading(false)
                LogBus.i("Home", "推荐 3s 未回，首屏降级（${if (stale != null) "stale缓存" else "空态"}）")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBus.w("Home", "降级计时异常 ${e::class.simpleName}: ${e.message}")
            }
        }
        val recJob = scope.launch {
            try {
                netVods = try {
                    // v0.31.1：25s 硬上限保留（真果鉴 21s）；取消单独透出不计失败
                    withTimeoutOrNull(25_000) {
                        withContext(Dispatchers.IO) { engineHome() }
                    }
                } catch (e: CancellationException) {
                    LogBus.i("Home", "推荐请求被取消（世代更替），不计失败")
                    throw e
                } catch (e: Exception) {
                    LogBus.w("Home", "推荐异常 ${e::class.simpleName}: ${e.message}")
                    null
                }
                val vods = netVods
                if (vods == null) {
                    LogBus.w("Home", "首页推荐加载超时/失败（25s 熔断）")
                } else {
                    if (vods.isNotEmpty()) {
                        withContext(Dispatchers.IO) { HomeCache.saveVods(context, key, vods) }
                    }
                    // v0.166.0：空结果不覆盖已渲染列表（22:28:09 回空刷掉 20 条的教训）
                    if (vods.isEmpty()) {
                        LogBus.i("Home", "推荐回空，保留已渲染列表")
                    } else if (myGen == contentGen && isHomeForeground()) {
                        // v0.50.0（用户补）：慢请求回来先看前台；在后台只写缓存，不刷推荐区
                        vm.setRecommend(vods)
                        recommendStale = false
                        recommendEmpty = false
                        LogBus.i("Home", "推荐回来，前台刷新 ${vods.size} 条")
                    } else {
                        LogBus.i("Home", "推荐回来了但首页不在前台，只写缓存不刷 UI")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBus.w("Home", "推荐路异常 ${e::class.simpleName}: ${e.message}")
            }
        }
        val catJob = scope.launch {
            try {
                // v0.50.0：打耗时+条数，区分"规则返回空"和"真超时"（之前只报超时，查不清）
                val t0 = android.os.SystemClock.elapsedRealtime()
                netCats = try {
                    // v0.31.1：25s，和推荐对齐；取消单独透出不计失败
                    withTimeoutOrNull(25_000) {
                        withContext(Dispatchers.IO) { engineCats() }
                    }
                } catch (e: CancellationException) {
                    LogBus.i("Home", "分类请求被取消（世代更替），不计失败")
                    throw e
                } catch (e: Exception) {
                    LogBus.w("Home", "分类异常 ${e::class.simpleName}: ${e.message}")
                    null
                }
                val ms = android.os.SystemClock.elapsedRealtime() - t0
                LogBus.i("Home", "engineCats 回来：${netCats?.size?.let { "$it 条" } ?: "超时/异常"}（${ms}ms）")
                val cats = netCats
                if (cats.isNullOrEmpty()) {
                    if (cats == null) LogBus.w("Home", "首页分类加载超时/失败（25s 熔断）")
                    else LogBus.i("Home", "规则返回分类为空（非失败）")
                    // 超时且有缓存：立刻顶上，不等推荐
                    val cached = cacheCats.takeIf { it.isNotEmpty() }
                    if (cached != null && myGen == contentGen && isHomeForeground() && drpyCats.isEmpty()) {
                        vm.setCategories(cached)
                        LogBus.i("Home", "分类用缓存先行：${cached.size} 个")
                    }
                } else {
                    withContext(Dispatchers.IO) { HomeCache.saveCats(context, key, cats) }
                    // v0.50.0 A：分类先行渲染，不等推荐回来
                    if (myGen == contentGen && isHomeForeground()) {
                        vm.setCategories(cats)
                        catsFailed = false
                        LogBus.i("Home", "分类先行渲染：${cats.size} 个（推荐未回）")
                    } else if (myGen == contentGen) {
                        LogBus.i("Home", "分类回来了但首页不在前台，只写缓存不刷 UI")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBus.w("Home", "分类路异常 ${e::class.simpleName}: ${e.message}")
            }
        }
        // 首屏结算最多等 5s；超时按快照结算（未回的路记 null，走缓存/失败分支），慢路回来照样补填。
        // v0.218.0（S4）：三路挂外层 scope 是历史遗留——deferred 被取消
        // （如 start 竞态）时它们不跟着停，全靠世代号人工兜底；现在把
        // 取消信号接过来：deferred 取消时三路一并取消；正常 5s 结算
        // （cause 为 null）不触动，慢路仍可在 25s 上限内后台回填。
        currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                timerJob.cancel()
                recJob.cancel()
                catJob.cancel()
            }
        }
        withTimeoutOrNull(5_000) {
            recJob.join()
            catJob.join()
        }
        // 3. 算最终 UI 状态（语义与之前一致；early-apply 已做，这里幂等收尾）
        val netOk = netVods != null && (netVods!!.isNotEmpty() || !cacheShown)
        val finalVods: List<ModelVod>? = when {
            netOk -> netVods!!.ifEmpty { null }
            cacheVods.isNotEmpty() -> cacheVods
            else -> null
        }
        val finalCats: List<CategoryType>
        val finalCatsFailed: Boolean
        when {
            !netCats.isNullOrEmpty() -> {
                finalCats = netCats!!
                finalCatsFailed = false
            }
            cacheCats.isNotEmpty() -> {
                finalCats = cacheCats
                finalCatsFailed = false
            }
            netCats == null -> {
                // 分类超时且无缓存：UI 给重试入口
                finalCats = emptyList()
                finalCatsFailed = true
            }
            else -> {
                // 规则本来就没分类，不是失败
                finalCats = emptyList()
                finalCatsFailed = false
            }
        }
        // v0.152.0：PHP 失败原因只在"真没数据可展示"时透出；有缓存顶上时不拿失败去盖缓存。
        val phpErrorVal = if (SourceStateStore.activePhpRule() != null &&
            netVods.isNullOrEmpty() && netCats.isNullOrEmpty() &&
            cacheVods.isEmpty() && cacheCats.isEmpty()
        ) PhpCliEngine.lastErrorFor(null)?.take(140) else null
        return HomeLoadGate.HomePayload(
            vods = finalVods,
            cats = finalCats,
            catsFailed = finalCatsFailed,
            resetSelection = netOk,
            phpError = phpErrorVal,
        )
    }

    // v0.145.0 修：从下方挪到 applyHomePayload 之前——Kotlin 局部函数必须先声明后调用（首轮构建即挂于此）。
    /** v0.44.0：筛选改为多组 Map；切分类时清空（不同分类的筛选组不同）。 */
    fun loadDrpyCategory(index: Int, filters: Map<String, String> = emptyMap()) { // index >= 1，对应 drpyCats[index-1]
        // v0.145.0：分类一律读 VM 真值，不读组合快照（换源/刚写分类后快照还滞后，143 同类教训）。
        val cat = vm.categories.value.getOrNull(index - 1) ?: return
        // v0.217.0（F4）：切分类重置分页（第 1 页起，到底标记清掉），页码按 typeId 记在 VM 里。
        vm.setCategoryPage(cat.typeId, 1)
        vm.setCategoryNoMore(cat.typeId, false)
        catLoadingMore = false
        // v0.31.2：单飞——快速连点时取消上一次，同一时间只有一个分类请求在跑
        // （之前每次点击都起一个协程，PY 引擎并发堆积是闪退的主因）。
        // 世代+1：旧请求的迟到结果直接丢弃，"最后一次点击赢"。
        categoryJob?.cancel()
        contentGen += 1
        val myGen = contentGen
        // 选中态立即更新，不等网络（之前是网络回来才设，连点时选中和内容错位，看着像"换了分类内容不变"）
        // v0.217.0（F1）：选中态存 typeId，不再存序号。
        vm.selectCategory(cat.typeId)
        vm.clearCategoryFailed(cat.typeId) // v0.110.0：新一轮加载先清旧的失败标记
        setFilters(filters)
        LogBus.i("UI", "点击分类: ${cat.typeName} (id=${cat.typeId}) filter=$filters")
        // v0.44.0：drpy 分类首次选中时懒加载筛选组（PY 的筛选 homeContent 已带，不重复拉）
        if (cat.filters.isEmpty() && isDrpySource()) {
            // v0.212.0 审计修复：筛选组请求原来无世代/无源校验，迟到
            // 结果会把旧源的筛选贴到新源同 typeId 分类上；只认发起时
            // 的世代与源 key，对不上直接丢弃。
            val filterGen = myGen
            val filterKey = homeCacheKey()
            scope.launch(Dispatchers.IO) {
                val groups = runCatching { fetchDrpyFilters(cat.typeId) }.getOrNull().orEmpty()
                if (groups.isNotEmpty() && filterGen == contentGen && filterKey == homeCacheKey()) {
                    vm.setCategories(vm.categories.value.map { c ->
                        if (c.typeId == cat.typeId) c.copy(filters = groups) else c
                    })
                    LogBus.i("UI", "分类 ${cat.typeName} 筛选加载：${groups.size} 组")
                }
            }
        }
        // v0.52.0：分类内容缓存先行——详情页返回/重进时秒回，不白屏等网络
        // v0.53.0：写 vm.categoryCache（selectedCategory=typeId 时 currentVideos 自动派生）
        val cacheKey = homeCacheKey()
        if (cacheKey != null && filters.isEmpty()) {
            scope.launch(Dispatchers.IO) {
                val cached = HomeCache.loadCategoryVods(context, cacheKey, cat.typeId)
                if (cached != null && myGen == contentGen) {
                    vm.setCategoryVideos(cat.typeId, cached.first)
                    LogBus.i("UI", "分类 ${cat.typeName} 缓存先行：${cached.first.size} 条")
                }
            }
        }
        categoryJob = scope.launch {
            vm.setLoading(true)
            try {
                // v0.17.3：加超时，避免 PY 卡死导致无响应
                // v0.110.0：null=超时/规则异常（失败，给可见反馈）；emptyList=规则真没内容（非失败）
                val netList = withTimeoutOrNull(30_000) {
                    engineCategory(cat.typeId, 1, filters)
                }
                if (myGen != contentGen) return@launch // 已被更新的点击取代，丢弃
                val list = netList ?: emptyList()
                if (netList == null) {
                    // v0.110.0：失败只记日志+标失败态，不再静默写空缓存让转圈卡死（审查报告第 10 条）
                    // v0.212.0 审计修复：失败原来仍走下面的 setCategoryVideos
                    // 写空列表，把上次成功的内容从内存缓存里抹掉；失败只标
                    // 失败态、不碰缓存，切走再切回仍能秒回旧内容。
                    LogBus.w("UI", "分类 ${cat.typeName} 加载失败（超时/规则异常），给可见重试")
                    vm.markCategoryFailed(cat.typeId)
                } else {
                    if (list.isEmpty()) {
                        LogBus.i("UI", "分类 ${cat.typeName} 规则返回空（非失败）")
                    } else {
                        LogBus.i("UI", "分类 ${cat.typeName} 加载 ${list.size} 条")
                    }
                    // v0.218.0（S7）：成功空列表也落盘——原来只存非空，
                    // 空分类每次点进来都要重新走网络；现在与内存口径对齐。
                    if (filters.isEmpty() && cacheKey != null) {
                        withContext(Dispatchers.IO) {
                            HomeCache.saveCategoryVods(context, cacheKey, cat.typeId, list)
                        }
                    }
                    // v0.53.0：写 vm.categoryCache；成功空列表也写（覆盖旧缓存，
                    // 避免旧内容残留），VM 收集器按“有条目”展示空态、不再转圈。
                    vm.setCategoryVideos(cat.typeId, list)
                }
            } finally {
                // 只有最新请求才清 loading，避免旧请求的 finally 把新请求的转圈灭掉
                // v0.212.0 审计修复：finally 原来只清 isLoading，从不碰
                // loadingCategoryId，空结果/取消路径下分类转圈永久粘滞；
                // 最新请求收尾时一并清掉本分类的 loading。
                if (myGen == contentGen) {
                    vm.setLoading(false)
                    vm.clearLoadingCategory(cat.typeId)
                }
            }
        }
    }

    /**
     * v0.215.0：分类加载更多——下一页追加（按 id 去重），空页=到底；
     * 切分类/换筛选期间不追加（选中分类变了就丢弃迟到结果）；无筛选时合并后落盘。
     * v0.217.0（F3/F4）：收尾做快照三项校验——选中 typeId、筛选条件、起手页码
     * 任一变了就丢弃本页，不合并、不推进页码；页码按 typeId 记在 VM 里。
     */
    fun loadMoreCategory() {
        val typeId = vm.selectedCategoryId.value
        if (typeId.isEmpty()) return
        if (catLoadingMore || vm.isCategoryNoMore(typeId)) return
        val cat = vm.categories.value.firstOrNull { it.typeId == typeId } ?: return
        val startPage = vm.categoryPage(typeId)
        val nextPage = startPage + 1
        val curFilters = filterMap
        val cacheKey = homeCacheKey()
        catLoadingMore = true
        scope.launch {
            val page = withTimeoutOrNull(30_000) {
                engineCategory(cat.typeId, nextPage, curFilters)
            }
            // 快照校验：选中、筛选、页码三者任一变了，本页作废。
            // 注意：filterMap 是组合快照，协程里读它永远等于起手值，必须读
            // live 的 filterEntries 现算，否则筛选变化校验不到。
            val liveFilters = filterEntries.map(::decodeFilter).toMap()
            if (vm.selectedCategoryId.value != typeId ||
                liveFilters != curFilters ||
                vm.categoryPage(typeId) != startPage
            ) {
                catLoadingMore = false
                return@launch
            }
            if (page == null) {
                AppToast.show(context, "加载更多失败，请重试", long = false)
            } else if (page.isEmpty()) {
                vm.setCategoryNoMore(typeId, true)
            } else {
                val merged = (vm.currentVideos.value + page)
                    .distinctBy { it.vodId.ifBlank { "noid:${it.vodName}" } }
                vm.setCategoryVideos(typeId, merged)
                vm.setCategoryPage(typeId, nextPage)
                if (curFilters.isEmpty() && cacheKey != null) {
                    withContext(Dispatchers.IO) {
                        HomeCache.saveCategoryVods(context, cacheKey, cat.typeId, merged)
                    }
                }
            }
            catLoadingMore = false
        }
    }

    /**
     * v0.217.0（F1）：按 typeId 重载分类（下拉刷新后保持当前分类用）。
     * 分类已不在列表里时什么都不做，不拿旧序号硬套新列表。
     */
    fun loadDrpyCategoryByTypeId(typeId: String, filters: Map<String, String> = emptyMap()) {
        val pos = vm.categories.value.indexOfFirst { it.typeId == typeId } + 1
        if (pos >= 1) loadDrpyCategory(pos, filters)
    }

    /**
     * 把单飞结果应用到当前 composition 的状态（执行方/复用方共用）。
     * - vods 为 null：网络失败且无缓存，drpyList 保持现状（prev）；
     * - cats 为空但之前有展示：保留之前的，下拉刷新失败不清空已展示的分类；
     * - 错误态按"最终有无可展示数据"统一给。
     */
    fun applyHomePayload(
        payload: HomeLoadGate.HomePayload?,
        prevVods: List<ModelVod>,
        gen: Int = -1,
        // v0.212.0 审计修复：下拉刷新原来和首次加载共用 resetSelection，
        // 成功就被拽回分类 1；下拉时传 keepSelection=true，位置不动。
        keepSelection: Boolean = false,
    ) {
        // v0.31.2：迟到结果丢弃——用户已经点了别的（分类/首页/筛选），旧 payload 不再应用，
        // 否则会出现"选中红果、内容却是首页"的错位。
        if (gen != -1 && gen != contentGen) return
        vm.setLoading(false)
        // v0.152.0：payload 自带 PHP 失败原因时透出；成功 payload（phpError=null）则清掉旧原因。
        if (payload != null) phpHomeError = payload.phpError
        if (payload == null) {
            // v0.53.0：prevVods 非空（currentVideos 恒不为 null），空列表=无数据
            // v0.106.0：homeError 从未被 UI 渲染，改走 recommendEmpty 的重试空态
            if (prevVods.isEmpty()) {
                homeError = "加载失败，点击重试"
                recommendEmpty = true
            }
            return
        }
        // v0.58（用户指正）：只有 payload.cats 非空才覆盖，空就保留分类先行渲染已写入的。
        // 注意顺序：先写分类，默认选分类才找得到第 1 个（VM 真值，不读组合快照）。
        if (payload.cats.isNotEmpty()) {
            vm.setCategories(payload.cats)
        }
        // v0.145.0：首页默认进第一个分类，不再回推荐流（推荐流不是源的分类）。
        // reset 或当前无选中（0）且有分类 → 进第 1 个分类；reset 且无分类 → 回推荐流兜底；
        // 非 reset 且已在某分类 → 保持浏览（下拉刷新不把用户拽出当前分类）。
        val effectiveReset = payload.resetSelection && !keepSelection
        if (effectiveReset || vm.selectedCategoryId.value == "") {
            setFilters(emptyMap())
            if (payload.cats.isNotEmpty()) {
                // 离开推荐流视图，3s 降级立起的推荐空态一并清掉，失败改走分类内联重试。
                recommendEmpty = false
                loadDrpyCategory(1)
            } else if (effectiveReset) {
                vm.selectCategory("")
            }
        }
        // v0.50.0（用户补）：最终应用时也在前台才刷推荐区；后台回来的只留缓存（上游已写），防跳变
        // v0.53.0：写 vm.recommendCache（selectedCategory=0 时 currentVideos 自动派生）
        if (payload.vods != null && isHomeForeground()) {
            recommendStale = false
            recommendEmpty = false
            vm.setRecommend(payload.vods)
        } else {
            if (payload.vods != null) LogBus.i("Home", "最终 payload 应用：首页不在前台，推荐不刷 UI")
            vm.setRecommend(prevVods)
        }
        // v0.58 补：c9d2ad8 删掉了 val cats = payload.cats.ifEmpty { prevCats } 却漏改了这一行，
        // 导致 CI 编译失败 Unresolved reference 'cats'。语义=分类失败且当前无任何分类可展示。
        catsFailed = payload.catsFailed && vm.categories.value.isEmpty()
        homeError = if (vm.currentVideos.value.isEmpty()) "加载失败，点击重试" else null
        // v0.106.0：25s 熔断/网络失败且无数据可展示时，给可见的"加载超时，点重试"入口
        //（之前只设 homeError，但它从未被渲染，用户看到的是白屏）
        // v0.145.0：只在推荐流视图（选中 0）才给推荐空态；在分类视图失败走分类内联重试。
        if (payload.vods == null && vm.currentVideos.value.isEmpty() && vm.selectedCategoryId.value == "") recommendEmpty = true
    }

    /**
     * v0.25.0：首页加载单入口（原 loadDrpyHome / doRefresh 的重复逻辑合并于此）。
     * - 缓存先行：IO 读盘，主线程即时渲染，冷启动不白屏；
     * - 并发：推荐 + 分类同时拉，哪个先回先显示，不等最慢的；
     * - 推荐/分类各走独立 slot（PySourceManager.homeSlot/categoriesSlot），互不取消；
     * - 单路 12 秒熔断（withTimeoutOrNull），超时/异常记 LogBus；
     * - 网络与引擎调用全在 IO 线程，不阻塞主线程；
     * - 全失败且无缓存 → 错误态（homeError），UI 给重试按钮。
     *
     * v0.31.0：单飞锁系统性修复失败风暴——
     * - 同一数据源 key 同时只允许一次在途请求（HomeLoadGate），后到的 await 同一个
     *   Deferred，复用执行方算好的 HomePayload，不各跑各的；
     * - 失败计数按 key 统一记在单例里，只有真正执行的那一次才计数，复用方不重复记；
     * - v0.29.0 退避语义保留（非手动连续 3 次全失败后跳过），挂在统一计数上。
     * （旧的 homeLoading 布尔防重入 + remember 内 homeFailStreak 是按 composition
     * 实例维度的，多个实例各自计数、各自触发，退避形同虚设，已删除。）
     */
    fun loadHome(force: Boolean = false, onDone: () -> Unit = {}, keepSelection: Boolean = false) {
        val key = homeCacheKey() ?: return
        // v0.50.0：新触发取消旧的退避重调度
        HomeLoadGate.clearBackoff(key)
        // v0.31.2：世代+1——首页加载和分类加载互斥，后发起的赢；同时取消分类在途请求
        contentGen += 1
        val myGen = contentGen
        categoryJob?.cancel()
        // v0.143.0：抓当前展示值，供"全失败且无缓存"判定（语义与旧版一致：之前展示中的也算有数据）。
        // 一定读 VM 的真值，不要读 drpyList——drpyList 是上次组合的快照：换源时
        // refreshDrpyState 已同步清掉 VM（clearDisplay），但组合还没重组，快照里还是旧源内容；
        // 新源一旦返回空/失败，最终 apply 就会把旧源内容"复活"回首页，造成"换了源内容没换"
        // 且无任何失败提示（旧内容非空，homeError/recommendEmpty 全不触发、状态环还是绿灯）。
        // 同源下拉刷新时 VM 真值就是本源内容，失败保留展示的语义不变。
        val prevVods = vm.currentVideos.value
        vm.setLoading(true)
        homeError = null
        phpHomeError = null
        recommendStale = false
        recommendEmpty = false

        // v0.31.0：单飞——有在途请求直接复用结果（不重复跑网络、不重复计数）
        HomeLoadGate.flight(key)?.let { inFlight ->
            LogBus.i("Home", "首页加载进行中，复用在途请求")
            scope.launch {
                // 复用方只应用结果，不写缓存、不计数
                applyHomePayload(awaitFlight(inFlight), prevVods, myGen, keepSelection)
                onDone()
            }
            return
        }

        // v0.50.0：指数退避重调度——之前只跳过不重试等于静默放弃；现在延迟后自动重试
        //（5s→15s→30s→60s，用户要求；per-key Job 防叠加）
        val streak = HomeLoadGate.streak(key)
        if (!force && streak >= 3) {
            vm.setLoading(false)
            val waitMs = HomeLoadGate.backoffMs(key)
            LogBus.i("Home", "首页连续失败 $streak 次，${waitMs / 1000}s 后自动重试（下拉可手动重试）")
            HomeLoadGate.setBackoffJob(key, scope.launch {
                delay(waitMs)
                if (key == homeCacheKey()) {
                    LogBus.i("Home", "退避结束，自动重试首页")
                    loadHome(force = true, onDone = onDone)
                } else {
                    onDone()
                }
            })
            return
        }

        // v0.50.0：CancellationException 必须透出（不能被 runCatching 吞掉），否则取消会被记成失败
        val deferred = scope.async {
            try {
                fetchHomePayload(key, myGen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBus.w("Home", "fetchHomePayload 异常 ${e::class.simpleName}: ${e.message}")
                null
            }
        }
        if (!HomeLoadGate.start(key, deferred)) {
            // 极小竞态：start 前一刻刚有人登记，转复用
            deferred.cancel()
            scope.launch {
                applyHomePayload(HomeLoadGate.flight(key)?.let { f: Deferred<HomeLoadGate.HomePayload?> -> awaitFlight(f) }, prevVods, myGen, keepSelection)
                onDone()
            }
            return
        }
        // 执行方收尾（仅挂一次）：统一计数 + 应用结果到自己的状态
        scope.launch {
            val payload = awaitFlight(deferred)
            // v0.50.0：被取消（世代更替/切 tab）不计入失败——之前 "I was cancelled" 也被记成失败
            if (deferred.isCancelled) {
                HomeLoadGate.abort(key, deferred)
                LogBus.i("Home", "首页请求被取消，不计入失败")
                // v0.212.0 审计修复：取消分支原来不走 applyHomePayload，
                // isLoading 没人清，离页中断后回首页长期黄灯；这里清掉。
                vm.setLoading(false)
                onDone()
                return@launch
            }
            val ok = payload?.vods != null || prevVods.isNotEmpty()
            HomeLoadGate.finish(key, deferred, ok)
            if (!ok) LogBus.w("Home", "首页数据全失败且无缓存（连续 ${HomeLoadGate.streak(key)} 次）")
            applyHomePayload(payload, prevVods, myGen, keepSelection)
            onDone()
        }
    }


    /** v0.29.0：分类失败重试（分类行空态的"点击重试"用，只拉分类） */
    fun loadCats() {
        val key = homeCacheKey() ?: return
        catsFailed = false
        scope.launch {
            vm.setLoading(true)
            val cats = runCatching {
                withTimeoutOrNull(25_000) {
                    withContext(Dispatchers.IO) { engineCats() }
                }
            }.getOrNull()
            if (!cats.isNullOrEmpty()) {
                vm.setCategories(cats)
                runCatching { withContext(Dispatchers.IO) { HomeCache.saveCats(context, key, cats) } }
                LogBus.i("Home", "分类重试成功：${cats.size} 个")
            } else {
                catsFailed = true
                LogBus.w("Home", "分类重试仍失败")
            }
            vm.setLoading(false)
        }
    }

    // ---- 站源切换 BottomSheet（v0.7.1） ----
    var showSourceSheet by remember { mutableStateOf(false) }
    // v0.78.3：首页布局切换
    val homeLayout by HomeSettings.layout.collectAsState()
    var showLayoutPicker by remember { mutableStateOf(false) }
    /**
     * v0.46.0：换源自动刷新。fromUserSwitch=true 表示用户在换源入口显式切换。
     * 真换源 = 显式切换，或持久化源标识变了（homeCacheKey，订阅/本地规则通吃，
     * 跨 tab/进程也准）。真换源→清旧数据→直接 loadHome(force=true)，不用再手动下拉。
     * 首次记账（lastLoaded==null，新装/升级）只记不加载，冷启动保持 v0.27.4 干净空态。
     */
    fun refreshDrpyState(fromUserSwitch: Boolean = false) {
        val cur = currentSubSite()
        val curKey = cur?.key
        // v0.33.1：lastSubKey 为 null（记不住上次）时不算换源，避免切 tab 回来误重置。
        // 只有明确记得上次的 key 且变了，才是用户真的换了源。
        val siteChanged = lastSubKey != null && curKey != lastSubKey
        subSite = cur
        lastSubKey = curKey
        val active = cur != null ||
            SourceStateStore.activeDrpyRule() != null || SourceStateStore.activePyRule() != null ||
            SourceStateStore.activePhpRule() != null
        val changed = active != drpyActive
        drpyActive = active
        val curCacheKey = homeCacheKey()
        val lastLoaded = SubSiteStore.lastLoadedSource(context)
        val switched = curCacheKey != null && (
            fromUserSwitch && curCacheKey != lastLoaded ||
                lastLoaded != null && curCacheKey != lastLoaded
            )
        if (lastLoaded == null && curCacheKey != null) {
            SubSiteStore.setLastLoadedSource(context, curCacheKey)
        }
        // v0.27.4：首页不再自动加载推荐流（用户手动下拉刷新才加载）；
        // 源变化时清掉旧数据，不再显示过期缓存
        // v0.53.0：清 VM 的推荐/分类/分类内存缓存（换源了旧缓存不能留）
        if (changed || siteChanged || switched) {
            // v0.218.0（S1）：换源清空走 VM 单一入口，不再由调用方调齐 5 个方法。
            vm.clearForSourceSwitch()
            setFilters(emptyMap())
            homeError = null
            // v0.31.0：失败计数按数据源 key 记在 HomeLoadGate 单例里，切源自然用新 key 的计数，无需手动清零
            catsFailed = false
            // v0.217.0（F5）：同一源禁用再启用时，key 没变故 switched 为假，
            // 但上面刚清完数据、源又可用、且以前加载过（lastLoaded 非空）——
            // 不补一次加载首页就晾在空白页。冷启动（lastLoaded 本来为空）不走这里，
            // 保持 v0.27.4 干净空态；真换源走下面的 switched 分支，不重复加载。
            if (!switched && active && lastLoaded != null) {
                loadHome(force = true)
            }
        }
        if (switched) {
            SubSiteStore.setLastLoadedSource(context, curCacheKey!!)
            loadHome(force = true)
        }
    }

    /**
     * v0.27.6：tab 切回时 HomeScreen 的 remember 状态已被 dispose，静默从缓存恢复。
     * v0.53.0（三点之3）：detail pop 回来时 VM 在 back stack 里没重建，
     * currentVideos 第一帧就是缓存好的分类列表——vm.needRefreshOnReturn() 为 false
     * 直接返回，什么都不做；只有 VM 也空（进程被杀等）才走磁盘缓存恢复。
     * v0.28.0 真因修复：无缓存就什么都不做——v0.27.4 起首页本来就是干净空态，
     * 用户下拉刷新才会加载，符合"首页不自动加载"的设计。
     */
    fun restoreHomeIfEmpty() {
        // v0.53.0：VM 里有数据（detail 返回的常见情况）直接返回，不折腾
        if (!vm.needRefreshOnReturn() || !drpyActive || drpyLoading) return
        val key = homeCacheKey() ?: return
        scope.launch {
            val cached = withContext(Dispatchers.IO) { HomeCache.load(context, key) }
            if (!vm.needRefreshOnReturn()) return@launch // 恢复期间用户已操作，不覆盖
            val data = cached?.data
            if (data != null && (data.vods.isNotEmpty() || data.cats.isNotEmpty())) {
                if (data.cats.isNotEmpty()) vm.setCategories(data.cats)
                // v0.31.2/v0.44.0：selectedCategory/filterEntries 已由 SavedStateHandle/rememberSaveable 恢复。
                // 之前停在某个分类就重载它（保证选中和内容一致）；原选是推荐时只恢复推荐，
                // 不强制进第一个分类（v0.170.0 修复：之前推荐用户会被悄悄切到分类 1）。
                // v0.145.0：选中与分类一律读 VM 真值（刚写完分类后组合快照还滞后）。
                // v0.217.0（F1）：选中存 typeId，恢复时先按 typeId 找当前位置，找不到再退回第一个分类。
                val curSel = vm.selectedCategoryId.value
                val curPos = if (curSel == "") 0
                    else vm.categories.value.indexOfFirst { it.typeId == curSel } + 1
                if (curPos >= 1) {
                    LogBus.i("Home", "返回首页：恢复分类 ${vm.categories.value[curPos - 1].typeName}")
                    loadDrpyCategory(curPos, filterMap)
                } else if (curSel == "") {
                    if (data.vods.isNotEmpty()) vm.setRecommend(data.vods)
                } else if (data.cats.isNotEmpty()) {
                    // 原选分类已不在列表里，退回第一个分类
                    loadDrpyCategory(1)
                } else {
                    if (data.vods.isNotEmpty()) vm.setRecommend(data.vods)
                }
                LogBus.i("Home", "返回首页：缓存恢复 ${data.vods.size} 条/${data.cats.size} 个分类")
            }
            // 无缓存：保持空态，等用户下拉刷新（不再自动调 loadHome，避免触发风暴）
        }
    }

    // 首次进入也走一遍，保证站源入口文案和数据与持久化状态一致
    // v0.27.6：冷启动保持 v0.27.4 的干净空态（不自动恢复）；只有 tab 切回（重进 composition）才恢复
    LaunchedEffect(Unit) {
        HomeSettings.load(context) // v0.78.3：首页布局偏好
        refreshDrpyState()
        if (homeEnteredOnce) restoreHomeIfEmpty()
        homeEnteredOnce = true
    }

    // 底部 tab 切换回来（saveState 恢复时）也要重新检查激活状态，
    // 否则在站源页设了当前规则后首页还停留在演示数据
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshDrpyState()
                restoreHomeIfEmpty()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun doRefresh() {
        if (refreshing) return
        refreshing = true
        if (drpyActive) {
            // v0.25.0：复用 loadHome 单入口（含缓存/并发/超时），结束收指示条
            // v0.29.0：手动下拉 force=true，绕过失败退避
            // v0.212.0 审计修复：下拉原来成功就拽回分类 1，且推荐一失败
            // 当前分类根本不刷新；下拉保持当前分类，完事后只刷当前分类。
            val selBefore = vm.selectedCategoryId.value
            loadHome(force = true, keepSelection = true, onDone = {
                refreshing = false
                // 下拉前已在某分类才刷该分类；推荐视图下拉时 apply 已按
                // 默认进第一个分类，不在这里再重复刷一次。
                // v0.217.0（F1）：selBefore 存的是 typeId，按 typeId 重载。
                if (selBefore != "") loadDrpyCategoryByTypeId(selBefore)
            })
        } else {
            // v0.215.0：无源时下拉原来空转 1.2 秒假装刷新——没源可刷，直接收指示条
            // 并打开选源（与长按首页 tab 无源时同一口径），不演刷新。
            refreshing = false
            showSourceSheet = true
        }
    }

    // v0.27.4：首页不再自动加载，不再用演示数据冒充；无数据时显示干净空态
    // v0.53.0：drpyList 恒不为 null（currentVideos 派生），空列表=无数据
    // v0.218.0（S27）：界面 Vod 已删，首页直接用数据层 Vod，不再映射一遍；
    // drpyReady 即“推荐流非空”，filtered 直接对 drpyList 去重洗牌。
    val drpyReady = drpyList.isNotEmpty()
    // v0.144.0：首格"全部"是 App 拼的（index==0 走首页推荐流，与规则无关），
    // 用户要求整个删掉：分类行只剩规则自己的分类；推荐流仍是默认首页
    // （selectedCategory=="" 时无格高亮），进分类后下拉刷新回推荐。
    val chipLabels: List<String> = drpyCats.map { it.typeName }

    val filtered: List<ModelVod> = when {
        drpyReady -> remember(shuffleSeed, drpyList) {
            // 同一站首页/分类数据无去重保证，重复 vodId 会让网格 lazy key 撞车，先按 id 去重；
            // v0.212.0 审计修复：vodId 为空的多条原来会被合并成一条，去重键用标题兜底。
            val base = drpyList.distinctBy { it.vodId.ifBlank { "noid:${it.vodName}" } }
            // v0.212.0 审计修复：“换一换”原来只是循环移位，点几次就回到
            // 原样；改成按种子真随机洗牌（种子 0 仍是原顺序）。
            if (shuffleSeed == 0) base
            else base.shuffled(kotlin.random.Random(shuffleSeed.toLong()))
        }
        else -> emptyList() // 无数据：空列表，UI 显示空态
    }

    // 站源入口行文案：订阅当前源 > 本地 drpy > 本地 PY > 本地 PHP；都没有 -> 演示数据
    val sourceLabel: String = when {
        subSite != null -> subSite?.name?.ifBlank { "订阅源" } ?: "订阅源"
        SourceStateStore.activeDrpyRule() != null -> "本地·${DrpySourceManager.activeRuleName().orEmpty()}"
        SourceStateStore.activePyRule() != null -> "本地·${PySourceManager.activeRuleName().orEmpty()}"
        SourceStateStore.activePhpRule() != null -> "本地·${SourceStateStore.activePhpRule().orEmpty()}"
        else -> "选择站源"
    }

    // v0.32.0 影院黑：流动渐变光斑垫底，内容浮上
    // v0.82.0：用户自定义背景底图（设置页更换）优先
    val customBg by BackgroundStore.customBg.collectAsState()
    LaunchedEffect(Unit) { BackgroundStore.load(context) }
    // v0.237.0 浅色高密度：浅色下不要光斑、自定义底图、半透明罩，
    // 直接纯白底，内容密度优先；深色保持原样（与 MainActivity 同款模式判定）。
    val themeMode by ThemeStore.mode.collectAsState()
    val darkTheme = when (themeMode) {
        ThemeStore.MODE_LIGHT -> false
        ThemeStore.MODE_DARK -> true
        else -> isSystemInDarkTheme()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (darkTheme) {
            if (customBg != null) {
                AsyncImage(
                    model = customBg,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                // 深色罩：保证文字可读
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                )
            } else {
                FlowingGlowBackground(modifier = Modifier.fillMaxSize())
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (darkTheme) MaterialTheme.colorScheme.background.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.background
                ),
        ) {
            // v0.45.0：官方 Material3 下拉刷新（替换手搓版）
            // v0.58：切分类 loading 覆盖层——Box 包一层，顶部细条/骨架屏
            Box(modifier = Modifier.fillMaxSize()) {
            PullToRefreshBox(
                state = pullRefreshState,
                isRefreshing = refreshing,
                onRefresh = { doRefresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
            LazyVerticalGrid(
                // v0.78.3：列数跟布局走（海报网格 3 列/大海报 2 列/列表模式 1 列）
                // v0.237.0：浅色下边距与间距收紧（12/8dp），一屏多放内容
                columns = GridCells.Fixed(homeLayout.columns),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                // v0.238.0：顶部贴顶——网格顶边距归零，顶栏贴到内容顶
                contentPadding = if (darkTheme) PaddingValues(start = SoftDimens.PagePadding, top = 0.dp, end = SoftDimens.PagePadding, bottom = SoftDimens.PagePadding)
                else PaddingValues(start = 12.dp, top = 0.dp, end = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (darkTheme) SoftDimens.CardSpacing else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(if (darkTheme) SoftDimens.CardSpacing else 8.dp),
            ) {
                // v0.60：顶栏合一——搜索收成放大镜图标，历史/追剧/换源放一行（用户要求）
                // v0.62：历史/追剧按内容自适应（不许被挤竖排），换源胶囊弹性收窄
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // v0.82.0：历史/追剧胶囊已移除（设置页快捷入口已有）；
                        // 搜索改胶囊，与换源胶囊统一样式
                        HomeCapsule(
                            icon = Icons.Filled.Search,
                            text = "搜索",
                            onClick = onSearchClick,
                        )
                        // v0.78.3：首页布局切换（对标 DsPlayer）
                        IconButton(onClick = { showLayoutPicker = true }) {
                            Icon(
                                imageVector = homeLayout.icon,
                                contentDescription = "切换布局",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 换源胶囊（v0.7.1 入口，v0.60 搬进顶栏一行；v0.62：弹性宽度，源名过长省略）
                        // v0.170.0：环判定以 drpyReady（有数据）优先于 contentGen == 0——切 tab 重组后
                        // contentGen 归零、纯推荐恢复又不 bump 世代时，有数据也不应判灰。
                        // 外圈裹住整个换源胶囊（状态点本身不变），颜色语义不变：加载中黄 / 失败红 / 完成绿。
                        val sourceRingState = when {
                            drpyLoading || loadingCat != null -> StatusRingState.Loading
                            drpyReady -> StatusRingState.Ready
                            sourceLabel == "选择站源" || contentGen == 0 -> StatusRingState.Idle
                            else -> StatusRingState.Failed
                        }
                        // v0.174.0：换源胶囊长按刷新当前源。点按仍是打开换源表；
                        // 长按走首页下拉刷新同一入口 doRefresh（force=true，绕过
                        // 失败退避），不用再下拉整页。Surface 本身不带 onClick，
                        // 点击/长按全由 combinedClickable 接管，避免双层点击。
                        // v0.184.0：首页顶栏站源 pill 收窄——原来 weight(1f) 填满整行，
                        // 名字短如“选择站源”时 pill 也撑得老宽；改成外面包一个
                        // weight(1f) 的 Box 靠右放 pill，pill 只按内容宽，
                        // 名字长时顶到 Box 宽再省略，内边距和状态环也同步收一点。
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.5.dp, sourceRingState.color()),
                            modifier = Modifier.combinedClickable(
                                onClick = { showSourceSheet = true },
                                onLongClick = {
                                    if (drpyActive) doRefresh() else showSourceSheet = true
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                // v0.141.0：换源图标改状态环（跑马灯＋状态点），与首页加载态联动：
                                // 加载中→黄灯旋转；已选源且有数据→绿；已选源但空→红；未选源/未加载过→灰
                                StatusRing(
                                    state = sourceRingState,
                                    diameter = 18.dp,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = sourceLabel,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = SoftType.CardTitle,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(
                                    imageVector = Icons.Filled.ExpandMore,
                                    contentDescription = if (drpyActive) "切换站源，长按刷新当前源" else "切换站源",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        }
                    }
                }
                // v0.75.0：继续观看——有未看完的点播记录时，顶栏下方横滑一排
                if (watchHistory.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            SectionTitle(title = "继续观看")
                            Spacer(Modifier.height(8.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(end = 4.dp),
                            ) {
                                // v0.212.0 审计修复：url 为空的多条老记录原来按空 url
                                // 去重合并成一条；空 url 用 vodId 兜底去重。
                                items(watchHistory.distinctBy { it.url.ifBlank { "vod:${it.vodId}" } }, key = { it.url.ifBlank { "vod:${it.vodId}" } }) { h ->
                                    val progress = if (h.durationMs > 0) {
                                        (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f)
                                    } else 0f
                                    Surface(
                                        shape = RoundedCornerShape(SoftDimens.CardCorner),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        onClick = {
                                            onContinueWatch(
                                                h.vodId, h.title,
                                                h.episodeIndex.coerceAtLeast(0),
                                                h.sourceType, h.sourceKey,
                                            )
                                        },
                                        modifier = Modifier.width(180.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = h.title,
                                                    style = SoftType.CardTitle,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                // v0.215.0：继续观看单条删除（原来只能去历史页清全部）。
                                                // v0.261.0：叉的触摸区 22dp 太小，点 4-5 下才中（多半点到
                                                // 卡片本身）——改成 48dp 标准触摸区，图标 20dp，一下关掉。
                                                IconButton(
                                                    onClick = {
                                                        // v0.261.0：逻辑反应慢才是主因，不是点/图标大小——
                                                        // 原来点叉后要等 delete + 全量 getHistory 解码 + 整页重组
                                                        // 才消失，用户以为没点中才连点 4-5 下。
                                                        // v0.265.0：乐观删除掩盖了写盘失败——原来先把卡片从
                                                        // 列表拿掉、再 apply() 写盘，写盘丢了（立刻杀进程）
                                                        // 或同剧同集还有另一条 url 记录时，下次 ON_RESUME
                                                        // reload 又把卡片带回来，看起来就是“删了又回来”。
                                                        // 改成：先按卡片身份（剧 + 集）commit() 同步删盘，
                                                        // 再从盘里 reload 一遍，UI 以盘为准，不再只信乐观状态。
                                                        HistoryStore.deleteContinueWatch(context, h)
                                                        reloadWatchHistory()
                                                    },
                                                    modifier = Modifier.size(48.dp),
                                                ) {
                                                    Icon(
                                                        Icons.Filled.Close,
                                                        contentDescription = "删除这条记录",
                                                        modifier = Modifier.size(20.dp),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                            // v0.212.0 审计修复：时长未知（durationMs==0）
                                            // 原来恒算 0%，卡片写“看到 0%”其实是未知不是没看；
                                            // 未知时长不显示百分比，只显示集数。
                                            Text(
                                                text = buildString {
                                                    if (h.episodeIndex >= 0) append("第${h.episodeIndex + 1}集")
                                                    if (h.durationMs > 0) {
                                                        if (isNotEmpty()) append(" · ")
                                                        append("看到${(progress * 100).toInt()}%")
                                                    }
                                                }.ifBlank { "继续观看" },
                                                style = SoftType.Caption,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            // v0.215.0：继续观看来源名（源 key 去扩展名；无源信息不显示）。
                                            val continueSource = h.sourceKey
                                                .removeSuffix(".js").removeSuffix(".py")
                                                .takeIf { it.isNotBlank() }
                                            if (continueSource != null) {
                                                Text(
                                                    text = "来源：$continueSource",
                                                    style = SoftType.Caption,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            LinearProgressIndicator(
                                                progress = { progress },
                                                // v0.212.0 审计修复：进度条原来走主题 primary
                                                // 系统紫，与青色/香槟金体系冲突；显式指定青色。
                                                color = Color(0xFF4DD0E1),
                                                trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(4.dp)
                                                    .clip(RoundedCornerShape(2.dp)),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // v0.59：流动状态条——顶部和正片之间，加载时流动动画表示状态
                if (drpyLoading || loadingCat != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                        )
                    }
                }
    
                // v0.27.4：首页不再自动加载推荐流；无数据时干净空态（不再转圈/报错/演示数据）
                // v0.50.0：3s 首屏无数据时给空态+重试（推荐区局部，不整页报错）
                if (recommendEmpty && !drpyReady) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(
                            kind = EmptyKind.Error,
                            icon = Icons.Filled.Refresh,
                            // v0.152.0：PHP 失败时标题/提示给真实原因，不再只说"网络超时或规则异常"。
                            title = if (phpHomeError != null) "PHP 运行失败" else "推荐加载失败",
                            hint = phpHomeError ?: "网络超时或规则异常",
                            actionText = "重试",
                            onAction = { loadHome(force = true) },
                        )
                    }
                } else if (!drpyReady && drpyCats.isEmpty() && !drpyLoading && loadingCat == null) {
                    // v0.59：分类和视频都没有才显示空态；有分类无视频时走 else 显示分类行
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(
                            kind = EmptyKind.Empty,
                            icon = Icons.Filled.Movie,
                            title = "暂无首页内容",
                            hint = "先添加并启用站源，首页就有推荐了",
                            actionText = "去添加站源",
                            onAction = onManageSources,
                        )
                    }
                } else {
                    // 分类行：只在有真实分类时显示（v0.28.1：横向滚动，分类多时可左滑）
                    if (drpyCats.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            // 全量重做：分类行收成共享 FilterBar（DaoBox filter_bar）。
                            // v0.144.0：首格"全部"已删，格下标与分类序号错一位：
                            // 第 index 格对应第 index+1 个分类（loadDrpyCategory 按 1 计）；
                            // 在“推荐”时 selectedPosition==0，selectedIndex==-1，都不选中。
                            FilterBar(
                                labels = chipLabels,
                                selectedIndex = selectedPosition - 1,
                                onSelect = { index ->
                                    // v0.12.1：有分类即按站点分类加载（CMS/spider/drpy 通用）
                                    loadDrpyCategory(index + 1)
                                },
                            )
                        }
                        // v0.110.0：分类内容加载失败（超时/规则异常）给内联错误+重试，
                        // 口径照抄 v0.29.0 分类行的"分类加载失败，点击重试"；
                        // 之前失败后顶部细条转圈卡死、无任何反馈（审查报告第 10 条）
                        if (failedCat != null && failedCat == selectedCategory) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                TextButton(onClick = { loadDrpyCategory(selectedPosition, filterMap) }) {
                                    Text("该分类加载失败，点击重试")
                                }
                            }
                        }
                        // v0.44.0：二级筛选行——选中分类的 filters 全组各一行（之前只取第一组）
                        val activeCat = drpyCats.firstOrNull { it.typeId == selectedCategory }
                        val filterGroups = activeCat?.filters.orEmpty()
                        if (selectedCategory != "" && filterGroups.isNotEmpty()) {
                            filterGroups.forEach { filterGroup ->
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    // v0.212.0 审计修复：筛选行原来只画 chips，组名
                                    // （年份/地区/类型）一行都没画，几组叠在一起分不清
                                    // 哪行筛什么；组名固定在行首不随横滑走。
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (filterGroup.name.isNotBlank()) {
                                            Text(
                                                text = filterGroup.name,
                                                style = SoftType.Caption,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                modifier = Modifier.padding(end = 6.dp),
                                            )
                                        }
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                        filterGroup.options.forEach { fv ->
                                            val isSel = filterMap[filterGroup.key] == fv.value
                                            AppChip(
                                                text = fv.name,
                                                selected = isSel,
                                                small = true,
                                                onClick = {
                                                    // 点已选中的则取消本组筛选，回到默认；多组之间互不干扰
                                                    val next = if (isSel) filterMap - filterGroup.key
                                                    else filterMap + (filterGroup.key to fv.value)
                                                    loadDrpyCategory(selectedPosition, next)
                                                },
                                            )
                                        }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (catsFailed) {
                        // v0.29.0：分类加载失败且为空，给重试入口（之前静默隐藏）
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = { loadCats() }) {
                                Text("分类加载失败，点击重试")
                            }
                        }
                    }
    
                    // v0.237.0 浅色高密度：首页 Hero 大卡撤掉——一屏少一条大卡，
                    // 网格直接三列密排；HeroCard 组件仍留给书架继续读用。

                    // 分区标题 + 换一换（v0.39.0：mockup 风格"今日推荐"）
                    // v0.50.0：stale 时标题旁小字提示"旧内容·刷新中"
                    // v0.145.0：进分类后标题跟当前分类走（选中 0 的推荐流视图才叫今日推荐）。
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionTitle(
                            title = drpyCats.firstOrNull { it.typeId == selectedCategory }?.typeName ?: "今日推荐",
                            action = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (recommendStale) {
                                        Text(
                                            text = "旧内容·刷新中",
                                            style = SoftType.Caption,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    TextButton(onClick = {
                                        shuffleSeed++
                                        // v0.212.0 审计修复：换一换原来不回顶，滑到底部
                                        // 点换一换还停在底部看同一批；换批即回顶。
                                        scope.launch { gridState.scrollToItem(0) }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Filled.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("换一换")
                                    }
                                }
                            },
                        )
                    }
    
                    // 网格海报墙 / 列表（v0.78.3：布局切换）
                    items(filtered, key = { it.vodId.ifBlank { "noid:${it.vodName}" } }) { vod ->
                        // v0.80.0：rating 已在海报底部跑马灯展示，副标题只留年份，不重复
                        // v0.212.0 审计修复：分类（typeName）明明带回来了，首页三种
                        // 布局却全程不显示；副标题改成“年份 · 分类”两段式。
                        val subtitle = listOfNotNull(
                            vod.vodYear.takeIf { it.isNotBlank() },
                            vod.typeName.takeIf { it.isNotBlank() },
                        ).joinToString(" · ").takeIf { it.isNotBlank() }
                        when (homeLayout) {
                            HomeLayout.COMPACT_LIST -> HomeCompactRow(
                                vod = vod,
                                subtitle = subtitle,
                                onClick = { goDetail(vod.vodId, vod.vodName) },
                            )
                            HomeLayout.TEXT_ONLY -> HomeTextRow(
                                vod = vod,
                                subtitle = subtitle,
                                onClick = { goDetail(vod.vodId, vod.vodName) },
                            )
                            else -> PosterCard(
                                title = vod.vodName,
                                subtitle = subtitle,
                                badge = vod.vodRemarks,
                                posterUrl = vod.vodPic,
                                modifier = Modifier.aspectRatio(3f / 4.6f),
                                onClick = { goDetail(vod.vodId, vod.vodName) },
                            )
                        }
                    }
                    // v0.215.0：分类分页脚——推荐视图（分类 0）不分页，只分类视图有“加载更多”；
                    // 空页后显示“没有更多了”，不再重复请求。
                    if (selectedCategory != "" && filtered.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    catLoadingMore -> Text(
                                        "加载中…",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    selectedCategory in noMoreCats -> Text(
                                        "没有更多了",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    else -> Button(onClick = { loadMoreCategory() }) {
                                        Text("加载更多")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            } // v0.45.0：闭合 PullToRefreshBox
            // v0.58：切分类时——有旧数据：顶部细条 loading；无旧数据：骨架屏（不闪"暂无内容"空态）
            if (loadingCat != null) {
                if (drpyList.isNotEmpty()) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                    )
                } else {
                    // 骨架屏：简单灰色占位块
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(SoftDimens.PagePadding),
                        verticalArrangement = Arrangement.spacedBy(SoftDimens.CardSpacing),
                    ) {
                        repeat(6) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        RoundedCornerShape(12.dp),
                                    ),
                            )
                        }
                    }
                }
            }
            } // v0.58：闭合切分类 loading Box
        } // 闭合 Column
    
        // v0.78.3：首页布局切换
        if (showLayoutPicker) {
            Dialog(onDismissRequest = { showLayoutPicker = false }) {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    Column(modifier = Modifier.padding(SoftDimens.PagePadding)) {
                        Text(
                            text = "首页布局",
                            style = SoftType.CardTitle,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        HomeLayout.entries.forEach { entry ->
                            SettingsRow(
                                icon = entry.icon,
                                title = entry.label,
                                subtitle = when (entry) {
                                    HomeLayout.GRID_3 -> "一屏看更多"
                                    HomeLayout.GRID_2 -> "海报更大"
                                    HomeLayout.COMPACT_LIST -> "小图 + 文字"
                                    HomeLayout.TEXT_ONLY -> "密度最高"
                                },
                                onClick = {
                                    HomeSettings.setLayout(context, entry)
                                    showLayoutPicker = false
                                },
                                trailing = {
                                    if (entry == homeLayout) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = "当前",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // 站源切换（v0.7.1）
        // v0.109.0 B7：换源器改 DsPlayer 式小图标模式——居中 Dialog 换成底部弹窗，
        // 内含标题/搜索/类型筛选/4 列图标网格（ui/home/SourcePickerSheet.kt）。
        if (showSourceSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSourceSheet = false },
                shape = RoundedCornerShape(
                    topStart = SoftDimens.CardCorner,
                    topEnd = SoftDimens.CardCorner,
                ),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                SourcePickerSheet(
                    onDismiss = { showSourceSheet = false },
                    onChanged = { refreshDrpyState(fromUserSwitch = true) },
                )
            }
        }
    } // v0.32.0：闭合 Box（流动光斑垫底）
}

/** v0.78.3：紧凑列表行——小海报 + 标题 + 副标题 */
/** v0.247.0（DS27）：两行共用的标题+副标题块——原来两处逐字重复；字号/颜色/跑马灯/省略全一致，只差副标题前有无 4dp 间隔。 */
/** v0.247.1 修复：抽成独立函数后 RowScope 丢失，weight(1f) 编译失败——改为 RowScope 扩展，两处调用点都在 Row 内。 */
@Composable
private fun RowScope.HomeRowTexts(vod: ModelVod, subtitle: String?, subtitleGap: Boolean) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = vod.vodName,
            style = SoftType.Body,
            // v0.82.0：显示不全跑马灯
            modifier = Modifier.basicMarquee(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (subtitle != null) {
            if (subtitleGap) Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** v0.247.0（DS27）：两行共用的备注角标——文字行前面多一个 8dp 间隔。 */
@Composable
private fun HomeRowRemarks(remarks: String, leadingSpacer: Boolean) {
    if (remarks.isBlank()) return
    if (leadingSpacer) Spacer(Modifier.width(8.dp))
    Text(
        text = remarks,
        style = SoftType.Body,
        color = LocalSoftExtra.current.warning,
    )
}

@Composable
private fun HomeCompactRow(vod: ModelVod, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SoftDimens.CardCorner))
            .background(LocalSoftExtra.current.glass)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vod.vodPic.isNotBlank()) {
            AsyncImage(
                model = vod.vodPic,
                contentDescription = null,
                modifier = Modifier
                    .size(width = 48.dp, height = 68.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 68.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = vod.vodName.take(1),
                    style = SoftType.CardTitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        HomeRowTexts(vod, subtitle, subtitleGap = true)
        HomeRowRemarks(vod.vodRemarks, leadingSpacer = false)
    }
}

/** v0.78.3：无图文字行——纯文字，密度最高 */
@Composable
private fun HomeTextRow(vod: ModelVod, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SoftDimens.CardCorner))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeRowTexts(vod, subtitle, subtitleGap = false)
        HomeRowRemarks(vod.vodRemarks, leadingSpacer = true)
    }
}

/** v0.82.0：首页胶囊按钮（搜索用；与换源胶囊统一样式）。 */
@Composable
private fun HomeCapsule(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = SoftType.CardTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
