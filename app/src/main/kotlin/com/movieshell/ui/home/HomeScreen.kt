package com.movieshell.ui.home

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import androidx.compose.ui.unit.dp
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
import com.movieshell.data.repo.ApiSource
import com.movieshell.data.repo.ApiSourceStore
import com.movieshell.data.repo.DetailSiteHolder
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.HomeCache
import com.movieshell.data.repo.HomeLayout
import com.movieshell.data.repo.HomeLoadGate
import com.movieshell.data.repo.HomeSettings
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SiteRepository
import com.movieshell.data.repo.SourceStateStore
import com.movieshell.data.repo.SubSiteEngines
import com.movieshell.data.repo.SubSiteRecord
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.data.repo.SubCurrent
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppChip
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.PosterCard
import com.movieshell.player.HistoryEntry
import com.movieshell.player.HistoryStore
import com.movieshell.ui.components.SectionTitle
import com.movieshell.ui.settings.SettingsRow
import com.movieshell.ui.data.Vod
import com.movieshell.ui.theme.FlowingGlowBackground
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.basicMarquee
import com.movieshell.data.repo.BackgroundStore

/** drpy 影片 -> 界面 Vod（id=vodId, title=vodName, category=typeName, year=vodYear, rating=vodRemarks） */
private fun ModelVod.toUiVod() = Vod(
    id = vodId,
    title = vodName,
    category = typeName,
    year = vodYear,
    rating = vodRemarks,
    area = "",
    actors = "",
    desc = "",
    episodes = 0,
    poster = vodPic,
)

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
    // v0.75.0：继续观看——带集数进详情页（默认走 onVodClick）
    onContinueWatch: (vodId: String, title: String, episodeIndex: Int) -> Unit =
        { v, t, _ -> onVodClick(v, t) },
) {
    val gridState = rememberLazyGridState()
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

    /** 当前数据源的首页：订阅当前源 > 本地 drpy > 本地 PY */
    suspend fun engineHome(): List<ModelVod> {
        val s = subSite
        if (s != null) return runCatching { subRepo.home(s) }.getOrDefault(emptyList())
        return runCatching {
            if (SourceStateStore.activeDrpyRule() != null) DrpySourceManager.home()
            else if (SourceStateStore.activePyRule() != null) PySourceManager.home()
            else emptyList()
        }.getOrDefault(emptyList())
    }

    /** 当前数据源的分类：订阅 CMS 站走 CMS 分类接口，drpy/PY 站走引擎 categories() */
    suspend fun engineCats(): List<CategoryType> {
        val s = subSite
        if (s != null) {
            if (s.isCmsSite()) return runCatching { subRepo.category(s, "", 1).first }
                .getOrDefault(emptyList())
            if (s.isJavaSpider()) return runCatching { subRepo.category(s, "", 1).first }
                .getOrDefault(emptyList())
            if (s.isPySite()) return runCatching { SubSiteEngines.pyEngineFor(s)?.categories() }
                .getOrNull().orEmpty()
            return runCatching { SubSiteEngines.drpyEngineFor(s)?.categories() }
                .getOrNull().orEmpty()
        }
        return runCatching {
            if (SourceStateStore.activeDrpyRule() != null) DrpySourceManager.categories()
            else if (SourceStateStore.activePyRule() != null) PySourceManager.categories()
            else emptyList()
        }.getOrDefault(emptyList())
    }

    /** 当前数据源的分类列表 */
    suspend fun engineCategory(
        tid: String,
        pg: Int,
        filter: Map<String, String> = emptyMap()
    ): List<ModelVod> {
        val s = subSite
        // v0.44.0：订阅路径也要透传筛选（之前丢了，drpy 筛选点了没效果）
        if (s != null) return runCatching { subRepo.category(s, tid, pg, filter).second }
            .getOrDefault(emptyList())
        return runCatching {
            // v0.44.0：订阅路径也要透传筛选（之前丢了，drpy 筛选点了没效果）
            // v0.48.0：本地 drpy 同样漏传 filter（v0.44.0 宣称生效实际没传），补上
            if (SourceStateStore.activeDrpyRule() != null) DrpySourceManager.category(tid, pg, filter)
            else if (SourceStateStore.activePyRule() != null) PySourceManager.category(tid, pg, filter)
            else emptyList()
        }.getOrDefault(emptyList())
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
    val selectedCategory by vm.selectedCategoryId.collectAsState()
    val drpyCats by vm.categories.collectAsState()
    val drpyList by vm.currentVideos.collectAsState()
    val drpyLoading by vm.isLoading.collectAsState()
    // v0.58：切分类 loading 标记（null=空闲；非 null=正在加载该分类，旧列表保留不闪空）
    val loadingCat by vm.loadingCategoryId.collectAsState()
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

    // ---- drpy / PY 规则接入（drpy 优先，PY 兜底） ----
    // v0.53.0：drpyActive/drpyLoading/drpyCats/drpyList 已搬进 HomeViewModel（见上）。
    var drpyActive by rememberSaveable { mutableStateOf(false) }
    var homeError by remember { mutableStateOf<String?>(null) } // v0.25.0：加载失败文案
    var catsFailed by remember { mutableStateOf(false) } // v0.29.0：分类失败且为空，给重试入口
    var recommendStale by remember { mutableStateOf(false) } // v0.50.0：展示的是过期缓存/8s降级，顶部小字提示
    var recommendEmpty by remember { mutableStateOf(false) } // v0.50.0：8s 首屏无数据，推荐区空态+重试

    // v0.50.0（用户补）：慢请求回来时先确认首页还在前台才刷 UI；在后台只写缓存，防推荐区跳变
    val homeLifecycle = LocalLifecycleOwner.current.lifecycle
    fun isHomeForeground(): Boolean =
        homeLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    // v0.75.0：继续观看——每次回到首页刷新（只留有点播 vodId、没看完的）
    var watchHistory by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    fun reloadWatchHistory() {
        watchHistory = HistoryStore.getHistory(context)
            .filter { e ->
                e.kind == "vod" && e.vodId.isNotBlank() &&
                    !(e.durationMs > 0 && e.positionMs >= e.durationMs * 0.95)
            }
            .take(10)
    }
    DisposableEffect(homeLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadWatchHistory()
        }
        homeLifecycle.addObserver(observer)
        onDispose { homeLifecycle.removeObserver(observer) }
    }

    /** v0.25.0：当前数据源的缓存 key（订阅站 key / 本地规则名） */
    fun homeCacheKey(): String? = when {
        subSite != null -> "sub_${subSite?.key}"
        SourceStateStore.activeDrpyRule() != null -> "drpy_${DrpySourceManager.activeRuleName()}"
        SourceStateStore.activePyRule() != null -> "py_${PySourceManager.activeRuleName()}"
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
     * - B：推荐 8s 首屏降级（stale 缓存/空态），25s 硬上限保留给慢规则，后台回来继续填；
     * - E：缓存先行即时上屏（过期也展示标 stale），返回首页永远有东西；
     * - 用户补：慢请求回来先确认前台才刷 UI，后台只写缓存，防推荐区跳变。
     */
    suspend fun fetchHomePayload(key: String, myGen: Int): HomeLoadGate.HomePayload {
        // 1. 缓存先行
        var cacheVods: List<ModelVod> = emptyList()
        var cacheCats: List<CategoryType> = emptyList()
        var cacheFresh = false
        var cacheShown = false
        withContext(Dispatchers.IO) { HomeCache.load(context, key) }?.let { (data, fresh) ->
            cacheCats = data.cats
            cacheFresh = fresh
            if (data.vods.isNotEmpty()) {
                cacheVods = data.vods
                cacheShown = true
                LogBus.i("Home", "首页缓存先行（${if (fresh) "新鲜" else "过期stale"}）：${data.vods.size} 条")
            }
        }
        // v0.50.0 E：缓存立刻上屏，不等网络
        if ((cacheShown || cacheCats.isNotEmpty()) && myGen == contentGen && isHomeForeground()) {
            if (cacheShown && drpyList == null) {
                vm.setRecommend(cacheVods)
                recommendStale = !cacheFresh
            }
            if (cacheCats.isNotEmpty() && drpyCats.isEmpty()) vm.setCategories(cacheCats)
        }
        // 2. 并发刷新（推荐/分类各 25s 熔断，互不取消）
        var netVods: List<ModelVod>? = null
        var netCats: List<CategoryType>? = null
        coroutineScope {
            launch {
                // v0.50.0 B：8s 首屏计时——推荐没回来先降级，不白屏等 25s
                launch {
                    delay(8_000)
                    if (netVods != null || myGen != contentGen) return@launch
                    // v0.106.0：drpyList 恒不为 null（currentVideos 派生），之前写 != null
                    // 导致 8s 降级恒被跳过——25s 熔断前用户一直看白屏。改判 isNotEmpty()
                    if (drpyList.isNotEmpty() || !isHomeForeground()) return@launch
                    val stale = cacheVods.takeIf { it.isNotEmpty() }
                    if (stale != null) {
                        vm.setRecommend(stale)
                        recommendStale = true
                    } else {
                        recommendEmpty = true
                    }
                    vm.isLoading.value = false
                    LogBus.i("Home", "推荐 8s 未回，首屏降级（${if (stale != null) "stale缓存" else "空态"}）")
                }
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
                    // v0.50.0（用户补）：慢请求回来先看前台；在后台只写缓存，不刷推荐区
                    if (myGen == contentGen && isHomeForeground()) {
                        vm.setRecommend(vods)
                        recommendStale = false
                        recommendEmpty = false
                        LogBus.i("Home", "推荐回来，前台刷新 ${vods.size} 条")
                    } else {
                        LogBus.i("Home", "推荐回来了但首页不在前台，只写缓存不刷 UI")
                    }
                }
            }
            launch {
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
            }
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
        return HomeLoadGate.HomePayload(
            vods = finalVods,
            cats = finalCats,
            catsFailed = finalCatsFailed,
            resetSelection = netOk,
        )
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
    ) {
        // v0.31.2：迟到结果丢弃——用户已经点了别的（分类/首页/筛选），旧 payload 不再应用，
        // 否则会出现"选中红果、内容却是首页"的错位。
        if (gen != -1 && gen != contentGen) return
        vm.isLoading.value = false
        if (payload == null) {
            // v0.53.0：prevVods 非空（currentVideos 恒不为 null），空列表=无数据
            // v0.106.0：homeError 从未被 UI 渲染，改走 recommendEmpty 的重试空态
            if (prevVods.isEmpty()) {
                homeError = "加载失败，点击重试"
                recommendEmpty = true
            }
            return
        }
        if (payload.resetSelection) {
            vm.selectCategory(0)
            setFilters(emptyMap())
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
        // v0.58（用户指正）：只有 payload.cats 非空才覆盖，空就保留分类先行渲染已写入的
        if (payload.cats.isNotEmpty()) {
            vm.setCategories(payload.cats)
        }
        // v0.58 补：c9d2ad8 删掉了 val cats = payload.cats.ifEmpty { prevCats } 却漏改了这一行，
        // 导致 CI 编译失败 Unresolved reference 'cats'。语义=分类失败且当前无任何分类可展示。
        catsFailed = payload.catsFailed && vm.categories.value.isEmpty()
        homeError = if (vm.currentVideos.value.isEmpty()) "加载失败，点击重试" else null
        // v0.106.0：25s 熔断/网络失败且无数据可展示时，给可见的"加载超时，点重试"入口
        //（之前只设 homeError，但它从未被渲染，用户看到的是白屏）
        if (payload.vods == null && vm.currentVideos.value.isEmpty()) recommendEmpty = true
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
    fun loadHome(force: Boolean = false, onDone: () -> Unit = {}) {
        val key = homeCacheKey() ?: return
        // v0.50.0：新触发取消旧的退避重调度
        HomeLoadGate.clearBackoff(key)
        // v0.31.2：世代+1——首页加载和分类加载互斥，后发起的赢；同时取消分类在途请求
        contentGen += 1
        val myGen = contentGen
        categoryJob?.cancel()
        // 抓当前展示值，供"全失败且无缓存"判定（语义与旧版一致：之前展示中的也算有数据）
        val prevVods = drpyList
        vm.isLoading.value = true
        homeError = null
        recommendStale = false
        recommendEmpty = false

        // v0.31.0：单飞——有在途请求直接复用结果（不重复跑网络、不重复计数）
        HomeLoadGate.flight(key)?.let { inFlight ->
            LogBus.i("Home", "首页加载进行中，复用在途请求")
            scope.launch {
                // 复用方只应用结果，不写缓存、不计数
                applyHomePayload(awaitFlight(inFlight), prevVods, myGen)
                onDone()
            }
            return
        }

        // v0.50.0：指数退避重调度——之前只跳过不重试等于静默放弃；现在延迟后自动重试
        //（5s→15s→30s→60s，用户要求；per-key Job 防叠加）
        val streak = HomeLoadGate.streak(key)
        if (!force && streak >= 3) {
            vm.isLoading.value = false
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
                applyHomePayload(HomeLoadGate.flight(key)?.let { f: Deferred<HomeLoadGate.HomePayload?> -> awaitFlight(f) }, prevVods, myGen)
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
                onDone()
                return@launch
            }
            val ok = payload?.vods != null || prevVods.isNotEmpty()
            HomeLoadGate.finish(key, deferred, ok)
            if (!ok) LogBus.w("Home", "首页数据全失败且无缓存（连续 ${HomeLoadGate.streak(key)} 次）")
            applyHomePayload(payload, prevVods, myGen)
            onDone()
        }
    }


    /** v0.44.0：筛选改为多组 Map；切分类时清空（不同分类的筛选组不同）。 */
    fun loadDrpyCategory(index: Int, filters: Map<String, String> = emptyMap()) { // index >= 1，对应 drpyCats[index-1]
        val cat = drpyCats.getOrNull(index - 1) ?: return
        // v0.31.2：单飞——快速连点时取消上一次，同一时间只有一个分类请求在跑
        // （之前每次点击都起一个协程，PY 引擎并发堆积是闪退的主因）。
        // 世代+1：旧请求的迟到结果直接丢弃，"最后一次点击赢"。
        categoryJob?.cancel()
        contentGen += 1
        val myGen = contentGen
        // 选中态立即更新，不等网络（之前是网络回来才设，连点时选中和内容错位，看着像"换了分类内容不变"）
        vm.selectCategory(index)
        setFilters(filters)
        LogBus.i("UI", "点击分类: ${cat.typeName} (id=${cat.typeId}) filter=$filters")
        // v0.44.0：drpy 分类首次选中时懒加载筛选组（PY 的筛选 homeContent 已带，不重复拉）
        if (cat.filters.isEmpty() && isDrpySource()) {
            scope.launch(Dispatchers.IO) {
                val groups = runCatching { fetchDrpyFilters(cat.typeId) }.getOrNull().orEmpty()
                if (groups.isNotEmpty()) {
                    vm.setCategories(drpyCats.map { c ->
                        if (c.typeId == cat.typeId) c.copy(filters = groups) else c
                    })
                    LogBus.i("UI", "分类 ${cat.typeName} 筛选加载：${groups.size} 组")
                }
            }
        }
        // v0.52.0：分类内容缓存先行——详情页返回/重进时秒回，不白屏等网络
        // v0.53.0：写 vm.categoryCache（selectedCategory=index 时 currentVideos 自动派生）
        val cacheKey = homeCacheKey()
        if (cacheKey != null && filters.isEmpty()) {
            scope.launch(Dispatchers.IO) {
                val cached = HomeCache.loadCategoryVods(context, cacheKey, cat.typeId)
                if (cached != null && myGen == contentGen) {
                    vm.setCategoryVideos(index, cached.first)
                    LogBus.i("UI", "分类 ${cat.typeName} 缓存先行：${cached.first.size} 条")
                }
            }
        }
        categoryJob = scope.launch {
            vm.isLoading.value = true
            try {
                // v0.17.3：加超时，避免 PY 卡死导致无响应
                val list = withTimeoutOrNull(30_000) {
                    engineCategory(cat.typeId, 1, filters)
                } ?: emptyList()
                if (myGen != contentGen) return@launch // 已被更新的点击取代，丢弃
                if (list.isEmpty()) {
                    LogBus.w("UI", "分类 ${cat.typeName} 返回空（可能超时或规则异常）")
                } else {
                    LogBus.i("UI", "分类 ${cat.typeName} 加载 ${list.size} 条")
                    // v0.52.0：分类内容落盘，下次秒回
                    if (filters.isEmpty() && cacheKey != null) {
                        withContext(Dispatchers.IO) {
                            HomeCache.saveCategoryVods(context, cacheKey, cat.typeId, list)
                        }
                    }
                }
                // v0.53.0：写 vm.categoryCache；空列表也写（覆盖旧缓存，避免旧内容残留）
                vm.setCategoryVideos(index, list)
            } finally {
                // 只有最新请求才清 loading，避免旧请求的 finally 把新请求的转圈灭掉
                if (myGen == contentGen) vm.isLoading.value = false
            }
        }
    }

    /** v0.29.0：分类失败重试（分类行空态的"点击重试"用，只拉分类） */
    fun loadCats() {
        val key = homeCacheKey() ?: return
        catsFailed = false
        scope.launch {
            vm.isLoading.value = true
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
            vm.isLoading.value = false
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
            SourceStateStore.activeDrpyRule() != null || SourceStateStore.activePyRule() != null
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
            vm.setRecommend(emptyList())
            vm.setCategories(emptyList())
            vm.clearCategoryCache()
            vm.clearDisplay() // v0.58：旧源列表不保留
            vm.selectCategory(0)
            setFilters(emptyMap())
            homeError = null
            // v0.31.0：失败计数按数据源 key 记在 HomeLoadGate 单例里，切源自然用新 key 的计数，无需手动清零
            catsFailed = false
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
            val data = cached?.first
            if (data != null && (data.vods.isNotEmpty() || data.cats.isNotEmpty())) {
                if (data.cats.isNotEmpty()) vm.setCategories(data.cats)
                // v0.31.2/v0.44.0：selectedCategory/filterEntries 已由 SavedStateHandle/rememberSaveable 恢复。
                // 之前停在某个分类就重载它（保证选中和内容一致），否则才恢复首页缓存。
                if (selectedCategory >= 1 && drpyCats.getOrNull(selectedCategory - 1) != null) {
                    LogBus.i("Home", "返回首页：恢复分类 ${drpyCats[selectedCategory - 1].typeName}")
                    loadDrpyCategory(selectedCategory, filterMap)
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
            loadHome(force = true, onDone = { refreshing = false })
        } else {
            scope.launch {
                delay(1200) // 占位：模拟刷新耗时
                refreshing = false
            }
        }
    }

    // v0.27.4：首页不再自动加载，不再用演示数据冒充；无数据时显示干净空态
    // v0.53.0：drpyList 恒不为 null（currentVideos 派生），空列表=无数据
    val drpyUiList: List<Vod>? = drpyList.takeIf { it.isNotEmpty() }?.map { it.toUiVod() }
    val drpyReady = drpyUiList != null
    val chipLabels: List<String> =
        if (drpyCats.isNotEmpty()) listOf("全部") + drpyCats.map { it.typeName }
        else listOf("全部")

    val filtered: List<Vod> = when {
        drpyReady -> remember(shuffleSeed, drpyUiList) {
            val base = drpyUiList!!
            if (shuffleSeed == 0) base
            else base.drop(shuffleSeed % base.size) + base.take(shuffleSeed % base.size)
        }
        else -> emptyList() // 无数据：空列表，UI 显示空态
    }

    // 站源入口行文案：订阅当前源 > 本地 drpy > 本地 PY；都没有 -> 演示数据
    val sourceLabel: String = when {
        subSite != null -> subSite?.name?.ifBlank { "订阅源" } ?: "订阅源"
        SourceStateStore.activeDrpyRule() != null -> "本地·${DrpySourceManager.activeRuleName().orEmpty()}"
        SourceStateStore.activePyRule() != null -> "本地·${PySourceManager.activeRuleName().orEmpty()}"
        else -> "选择站源"
    }

    // v0.32.0 影院黑：流动渐变光斑垫底，内容浮上
    // v0.82.0：用户自定义背景底图（设置页更换）优先
    val customBg by BackgroundStore.customBg.collectAsState()
    LaunchedEffect(Unit) { BackgroundStore.load(context) }
    Box(modifier = Modifier.fillMaxSize()) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f)),
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
                columns = GridCells.Fixed(homeLayout.columns),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(SoftDimens.PagePadding),
                verticalArrangement = Arrangement.spacedBy(SoftDimens.CardSpacing),
                horizontalArrangement = Arrangement.spacedBy(SoftDimens.CardSpacing),
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
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            onClick = { showSourceSheet = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SwapHoriz,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
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
                                    contentDescription = "切换站源",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
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
                                items(watchHistory, key = { it.url }) { h ->
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
                                            )
                                        },
                                        modifier = Modifier.width(180.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = h.title,
                                                style = SoftType.CardTitle,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = if (h.episodeIndex >= 0) {
                                                    "第${h.episodeIndex + 1}集 · 看到${(progress * 100).toInt()}%"
                                                } else {
                                                    "看到${(progress * 100).toInt()}%"
                                                },
                                                style = SoftType.Caption,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            LinearProgressIndicator(
                                                progress = { progress },
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
                // v0.50.0：8s 首屏无数据时给空态+重试（推荐区局部，不整页报错）
                if (recommendEmpty && !drpyReady) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(
                            kind = EmptyKind.Error,
                            icon = Icons.Filled.Refresh,
                            title = "推荐加载失败",
                            hint = "网络超时或规则异常",
                            actionText = "重试",
                            onAction = { loadHome(force = true) },
                        )
                    }
                } else if (!drpyReady && drpyCats.isEmpty()) {
                    // v0.59：分类和视频都没有才显示空态；有分类无视频时走 else 显示分类行
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(
                            kind = EmptyKind.Empty,
                            icon = Icons.Filled.Movie,
                            title = "暂无首页内容",
                            hint = "去站源页管理源",
                        )
                    }
                } else {
                    // 分类行：只在有真实分类时显示（v0.28.1：横向滚动，分类多时可左滑）
                    if (drpyCats.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                chipLabels.forEachIndexed { index, label ->
                                    AppChip(
                                        text = label,
                                        selected = selectedCategory == index,
                                        onClick = {
                                            // v0.12.1：有分类即按站点分类加载（CMS/spider/drpy 通用）
                                            if (index == 0) loadHome() else loadDrpyCategory(index)
                                        },
                                    )
                                }
                            }
                        }
                        // v0.44.0：二级筛选行——选中分类的 filters 全组各一行（之前只取第一组）
                        val activeCat = drpyCats.getOrNull(selectedCategory - 1)
                        val filterGroups = activeCat?.filters.orEmpty()
                        if (selectedCategory >= 1 && filterGroups.isNotEmpty()) {
                            filterGroups.forEach { filterGroup ->
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
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
                                                    loadDrpyCategory(selectedCategory, next)
                                                },
                                            )
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
    
                    // 分区标题 + 换一换（v0.39.0：mockup 风格"今日推荐"）
                    // v0.50.0：stale 时标题旁小字提示"旧内容·刷新中"
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionTitle(
                            title = "今日推荐",
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
                                    TextButton(onClick = { shuffleSeed++ }) {
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
                    items(filtered, key = { it.id }) { vod ->
                        // v0.80.0：rating 已在海报底部跑马灯展示，副标题只留年份，不重复
                        val subtitle = vod.year.takeIf { it.isNotBlank() }
                        when (homeLayout) {
                            HomeLayout.COMPACT_LIST -> HomeCompactRow(
                                vod = vod,
                                subtitle = subtitle,
                                onClick = { goDetail(vod.id, vod.title) },
                            )
                            HomeLayout.TEXT_ONLY -> HomeTextRow(
                                vod = vod,
                                subtitle = subtitle,
                                onClick = { goDetail(vod.id, vod.title) },
                            )
                            else -> PosterCard(
                                title = vod.title,
                                subtitle = subtitle,
                                badge = vod.rating,
                                posterUrl = vod.poster,
                                modifier = Modifier.aspectRatio(3f / 4.6f),
                                onClick = { goDetail(vod.id, vod.title) },
                            )
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
        // v0.11.8：选源弹窗改成居中悬浮 Dialog（原来是底部 BottomSheet）
        if (showSourceSheet) {
            Dialog(onDismissRequest = { showSourceSheet = false }) {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                ) {
                    SourceSwitchSheet(
                        onDismiss = { showSourceSheet = false },
                        onChanged = { refreshDrpyState(fromUserSwitch = true) },
                    )
                }
            }
        }
    } // v0.32.0：闭合 Box（流动光斑垫底）
}
/** 类型徽标：照抄 SourcesScreen 的 KindBadge（私有，故在此复制一份小的）。 */
@Composable
private fun SourceKindBadge(kind: String) {
    val (label, color) = when (kind) {
        SubSiteStore.KIND_CMS -> "CMS" to MaterialTheme.colorScheme.primary
        SubSiteStore.KIND_DRPY -> "drpy" to MaterialTheme.colorScheme.tertiary
        SubSiteStore.KIND_PY -> "PY" to MaterialTheme.colorScheme.secondary
        SubSiteStore.KIND_TYPE4 -> "PHP" to MaterialTheme.colorScheme.tertiary
        SubSiteStore.KIND_SPIDER -> "spider" to MaterialTheme.colorScheme.secondary
        else -> "不支持" to MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text = label,
            color = color,
            style = SoftType.Caption,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 本地规则小徽标。 */
@Composable
private fun LocalBadge() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text = "本地",
            color = color,
            style = SoftType.Caption,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SourceSheetRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    dimmed: Boolean = false,
    badge: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.5f else 1f)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = SoftType.Body,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                badge()
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "当前源",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Spacer(Modifier.size(20.dp))
        }
    }
}

/**
 * 站源切换（v0.7.1）。
 *
 * (a) 本地规则组：DrpySourceManager / PySourceManager 的全部规则，每行标"本地"徽标；
 * (b) 订阅站源组：ApiSourceStore 启用的订阅，按订阅名分组展开站点，带类型徽标，
 *     kind == unsupported 的行置灰，点击 Toast 显示 rec.reason。
 *
 * 选中后回调 onChanged（HomeScreen 用 refreshDrpyState 立即重载）再 onDismiss 关 sheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSwitchSheet(
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    // v0.17.0：异步加载，避免主线程扫文件卡顿
    var cur by remember { mutableStateOf<SubCurrent?>(null) }
    var drpyRules by remember { mutableStateOf<List<DrpySourceManager.RuleInfo>>(emptyList()) }
    var pyRules by remember { mutableStateOf<List<PySourceManager.RuleInfo>>(emptyList()) }
    var drpyActiveName by remember { mutableStateOf<String?>(null) }
    var pyActiveName by remember { mutableStateOf<String?>(null) }
    var subSites by remember { mutableStateOf<List<Pair<ApiSource, SubSiteRecord>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            cur = SubSiteStore.getCurrent(context)
            drpyRules = DrpySourceManager.listRules()
            pyRules = PySourceManager.listRules()
            drpyActiveName = DrpySourceManager.activeRuleName()
            pyActiveName = PySourceManager.activeRuleName()
            subSites = ApiSourceStore.load(context).filter { it.enabled }.flatMap { sub ->
                val sites = SubSiteStore.getCached(context, sub.url)?.sites.orEmpty()
                sites.map { sub to it }
            }
        }
        loading = false
    }

    fun pickLocal(isDrpy: Boolean, name: String) {
        SubSiteStore.clearCurrent(context)
        if (isDrpy) {
            PySourceManager.setActiveRule(null)
            DrpySourceManager.setActiveRule(name)
            SourceStateStore.setRuleEnabled(context, "js", name, true)
        } else {
            DrpySourceManager.setActiveRule(null)
            PySourceManager.setActiveRule(name)
            SourceStateStore.setRuleEnabled(context, "py", name, true)
        }
        onChanged()
        onDismiss()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SoftDimens.PagePadding, vertical = 8.dp),
    ) {
        Text(
            text = "切换站源",
            style = SoftType.Title,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (loading) {
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            Spacer(Modifier.height(16.dp))
            return@Column
        }
        Spacer(Modifier.height(4.dp))

        if (drpyRules.isNotEmpty() || pyRules.isNotEmpty()) {
            Text(
                text = "本地规则",
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.primary,
            )
            drpyRules.forEach { rule ->
                SourceSheetRow(
                    title = rule.title.ifBlank { rule.name },
                    subtitle = rule.name,
                    checked = rule.name == drpyActiveName && cur == null,
                    badge = { LocalBadge() },
                    onClick = { pickLocal(true, rule.name) },
                )
            }
            pyRules.forEach { rule ->
                SourceSheetRow(
                    title = rule.title.ifBlank { rule.name },
                    subtitle = rule.name,
                    checked = rule.name == pyActiveName && cur == null,
                    badge = { LocalBadge() },
                    onClick = { pickLocal(false, rule.name) },
                )
            }
        }

        if (subSites.isNotEmpty()) {
            var lastSubUrl = ""
            subSites.forEach { (sub, rec) ->
                if (sub.url != lastSubUrl) {
                    Text(
                        text = sub.name,
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    lastSubUrl = sub.url
                }
                val unsupported = rec.kind == SubSiteStore.KIND_UNSUPPORTED
                SourceSheetRow(
                    title = rec.name.ifBlank { rec.key },
                    subtitle = null,
                    checked = run {
                        val c = cur
                        c != null && c.subUrl == sub.url && c.siteKey == rec.key
                    },
                    dimmed = unsupported,
                    badge = { SourceKindBadge(rec.kind) },
                    onClick = {
                        if (unsupported) {
                            Toast.makeText(context, rec.reason, Toast.LENGTH_SHORT).show()
                        } else {
                            // 订阅优先：先清空本地规则激活态，再设订阅当前源
                            DrpySourceManager.setActiveRule(null)
                            PySourceManager.setActiveRule(null)
                            SubSiteStore.setCurrent(context, sub.url, rec.key)
                            onChanged()
                            onDismiss()
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** v0.78.3：紧凑列表行——小海报 + 标题 + 副标题 */
@Composable
private fun HomeCompactRow(vod: Vod, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SoftDimens.CardCorner))
            .background(LocalSoftExtra.current.glass)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vod.poster.isNotBlank()) {
            AsyncImage(
                model = vod.poster,
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
                    text = vod.title.take(1),
                    style = SoftType.CardTitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = vod.title,
                style = SoftType.Body,
                // v0.82.0：显示不全跑马灯
                modifier = Modifier.basicMarquee(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (vod.rating.isNotBlank()) {
            Text(
                text = vod.rating,
                style = SoftType.Body,
                color = LocalSoftExtra.current.warning,
            )
        }
    }
}

/** v0.78.3：无图文字行——纯文字，密度最高 */
@Composable
private fun HomeTextRow(vod: Vod, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SoftDimens.CardCorner))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = vod.title,
                style = SoftType.Body,
                // v0.82.0：显示不全跑马灯
                modifier = Modifier.basicMarquee(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (vod.rating.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = vod.rating,
                style = SoftType.Body,
                color = LocalSoftExtra.current.warning,
            )
        }
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
