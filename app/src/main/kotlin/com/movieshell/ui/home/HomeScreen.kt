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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod as ModelVod
import com.movieshell.data.model.isCmsSite
import com.movieshell.data.model.isJavaSpider
import com.movieshell.data.model.isPySite
import com.movieshell.data.repo.ApiSource
import com.movieshell.data.repo.ApiSourceStore
import com.movieshell.data.repo.DetailSiteHolder
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.HomeCache
import com.movieshell.data.repo.HomeLoadGate
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
import com.movieshell.ui.components.SectionTitle
import com.movieshell.ui.data.Vod
import com.movieshell.ui.theme.FlowingGlowBackground
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    onSearchClick: () -> Unit,
    onVodClick: (vodId: String) -> Unit,
    // v0.24.0：播放历史入口
    onHistoryClick: () -> Unit = {},
    // v0.26.0：我的追剧入口
    onFollowClick: () -> Unit = {},
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
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
        if (s != null) return runCatching { subRepo.category(s, tid, pg).second }
            .getOrDefault(emptyList())
        return runCatching {
            if (SourceStateStore.activeDrpyRule() != null) DrpySourceManager.category(tid, pg)
            else if (SourceStateStore.activePyRule() != null) PySourceManager.category(tid, pg, filter)
            else emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * 进详情：订阅当前源时把站点上下文带给详情页（详情/播放按站走引擎），
     * 否则传 null，详情页走本地规则旧逻辑。
     */
    fun goDetail(vodId: String) {
        LogBus.i("UI", "点击影片卡片 id=$vodId")
        DetailSiteHolder.site = subSite
        onVodClick(vodId)
    }

    var refreshing by remember { mutableStateOf(false) }
    var pullOffset by remember { mutableFloatStateOf(0f) }
    var selectedCategory by rememberSaveable { mutableIntStateOf(0) } // 0 = 全部
    // v0.30.1：二级筛选——(filterKey, filterValue)，null=未选（用规则默认）
    // v0.31.2：remember → rememberSaveable，tab 切回时选中态不丢（NavController saveState 只恢复 rememberSaveable）
    // 修构建注：Pair<String,String>? 的自定义 Saver 在 rememberSaveable 里类型对不上，拆成两个 String? 存，最简单不出错。
    var filterKey by rememberSaveable { mutableStateOf<String?>(null) }
    var filterValue by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedFilter: Pair<String, String>? =
        if (filterKey != null && filterValue != null) filterKey!! to filterValue!! else null
    fun setFilter(f: Pair<String, String>?) { filterKey = f?.first; filterValue = f?.second }
    // v0.31.2：内容世代——每次发起内容加载（首页/分类）+1，异步完成时只应用最新世代的结果，
    // 旧请求的迟到结果直接丢弃。快速连点/首页与分类打架时，"最后一次点击赢"是唯一不变量。
    var contentGen by remember { mutableIntStateOf(0) }
    // v0.31.2：分类加载单飞——快速连点时取消上一次，保证同一时间只有一个分类请求在跑
    var categoryJob by remember { mutableStateOf<Job?>(null) }
    var shuffleSeed by remember { mutableIntStateOf(0) }

    // ---- drpy / PY 规则接入（drpy 优先，PY 兜底） ----
    var drpyActive by remember { mutableStateOf(false) }
    var drpyLoading by remember { mutableStateOf(false) }
    var drpyCats by remember { mutableStateOf(emptyList<CategoryType>()) }
    var drpyList by remember { mutableStateOf<List<ModelVod>?>(null) } // null=未加载/失败
    var homeError by remember { mutableStateOf<String?>(null) } // v0.25.0：加载失败文案
    var catsFailed by remember { mutableStateOf(false) } // v0.29.0：分类失败且为空，给重试入口

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
     */
    suspend fun fetchHomePayload(key: String): HomeLoadGate.HomePayload {
        // 1. 缓存先行
        var cacheVods: List<ModelVod> = emptyList()
        var cacheCats: List<CategoryType> = emptyList()
        var cacheShown = false
        withContext(Dispatchers.IO) { HomeCache.load(context, key) }?.let { (data, fresh) ->
            cacheCats = data.cats
            if (data.vods.isNotEmpty()) {
                cacheVods = data.vods
                cacheShown = true
                LogBus.i("Home", "首页缓存命中（${if (fresh) "新鲜" else "过期"}）：${data.vods.size} 条")
            }
        }
        // 2. 并发刷新（推荐/分类各 25s 熔断，互不取消）
        var netVods: List<ModelVod>? = null
        var netCats: List<CategoryType>? = null
        coroutineScope {
            launch {
                netVods = runCatching {
                    // v0.27.1：推荐抓取给 12s（PY 规则内部 5s×2 次抓取，慢速网络 6s 不够）
                    // v0.31.1：12s→25s，真果鉴这类慢规则要 21 秒才能回来，12s 直接掐死
                    withTimeoutOrNull(25_000) {
                        withContext(Dispatchers.IO) { engineHome() }
                    }
                }.getOrNull()
                if (netVods == null) {
                    LogBus.w("Home", "首页推荐加载超时/失败（25s 熔断）")
                } else if (!netVods.isNullOrEmpty()) {
                    withContext(Dispatchers.IO) { HomeCache.saveVods(context, key, netVods!!) }
                }
            }
            launch {
                netCats = runCatching {
                    // v0.27.7：分类超时 6s→12s，和推荐对齐（真机分类反复熔断）
                    // v0.28.0：PyApi 内 homeContent 一次调用共享，推荐/分类不再排队
                    // v0.31.1：12s→25s，和推荐对齐（真果鉴 21 秒才回来）
                    withTimeoutOrNull(25_000) {
                        withContext(Dispatchers.IO) { engineCats() }
                    }
                }.getOrNull()
                if (netCats == null) {
                    // v0.28.0：超时不清空——缓存里的 cats 继续用，打明确日志
                    LogBus.w("Home", "首页分类加载超时/失败（25s 熔断），保留缓存分类 ${cacheCats.size} 个")
                } else if (netCats!!.isNotEmpty()) {
                    withContext(Dispatchers.IO) { HomeCache.saveCats(context, key, netCats!!) }
                }
            }
        }
        // 3. 算最终 UI 状态（语义与 v0.25.0–v0.29.0 一致）
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
        prevVods: List<ModelVod>?,
        prevCats: List<CategoryType>,
        gen: Int = -1,
    ) {
        // v0.31.2：迟到结果丢弃——用户已经点了别的（分类/首页/筛选），旧 payload 不再应用，
        // 否则会出现"选中红果、内容却是首页"的错位。
        if (gen != -1 && gen != contentGen) return
        drpyLoading = false
        if (payload == null) {
            if (prevVods == null) homeError = "加载失败，点击重试"
            return
        }
        if (payload.resetSelection) {
            selectedCategory = 0
            setFilter(null)
        }
        drpyList = payload.vods ?: prevVods
        val cats = payload.cats.ifEmpty { prevCats }
        drpyCats = cats
        catsFailed = payload.catsFailed && cats.isEmpty()
        homeError = if (drpyList == null) "加载失败，点击重试" else null
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
        // v0.31.2：世代+1——首页加载和分类加载互斥，后发起的赢；同时取消分类在途请求
        contentGen += 1
        val myGen = contentGen
        categoryJob?.cancel()
        // 抓当前展示值，供"全失败且无缓存"判定（语义与旧版一致：之前展示中的也算有数据）
        val prevVods = drpyList
        val prevCats = drpyCats
        drpyLoading = true
        homeError = null

        // v0.31.0：单飞——有在途请求直接复用结果（不重复跑网络、不重复计数）
        HomeLoadGate.flight(key)?.let { inFlight ->
            LogBus.i("Home", "首页加载进行中，复用在途请求")
            scope.launch {
                // 复用方只应用结果，不写缓存、不计数
                applyHomePayload(awaitFlight(inFlight), prevVods, prevCats, myGen)
                onDone()
            }
            return
        }

        // v0.29.0 退避语义保留，挂在按 key 统一的计数上
        val streak = HomeLoadGate.streak(key)
        if (!force && streak >= 3) {
            drpyLoading = false
            LogBus.i("Home", "首页连续失败 $streak 次，退避中（下拉可手动重试）")
            onDone()
            return
        }

        val deferred = scope.async { runCatching { fetchHomePayload(key) }.getOrNull() }
        if (!HomeLoadGate.start(key, deferred)) {
            // 极小竞态：start 前一刻刚有人登记，转复用
            deferred.cancel()
            scope.launch {
                applyHomePayload(HomeLoadGate.flight(key)?.let { f: Deferred<HomeLoadGate.HomePayload?> -> awaitFlight(f) }, prevVods, prevCats, myGen)
                onDone()
            }
            return
        }
        // 执行方收尾（仅挂一次）：统一计数 + 应用结果到自己的状态
        scope.launch {
            val payload = awaitFlight(deferred)
            val ok = payload?.vods != null || prevVods != null
            HomeLoadGate.finish(key, deferred, ok)
            if (!ok) LogBus.w("Home", "首页数据全失败且无缓存（连续 ${HomeLoadGate.streak(key)} 次）")
            applyHomePayload(payload, prevVods, prevCats, myGen)
            onDone()
        }
    }


    fun loadDrpyCategory(index: Int, filter: Pair<String, String>? = null) { // index >= 1，对应 drpyCats[index-1]
        val cat = drpyCats.getOrNull(index - 1) ?: return
        // v0.31.2：单飞——快速连点时取消上一次，同一时间只有一个分类请求在跑
        // （之前每次点击都起一个协程，PY 引擎并发堆积是闪退的主因）。
        // 世代+1：旧请求的迟到结果直接丢弃，"最后一次点击赢"。
        categoryJob?.cancel()
        contentGen += 1
        val myGen = contentGen
        // 选中态立即更新，不等网络（之前是网络回来才设，连点时选中和内容错位，看着像"换了分类内容不变"）
        selectedCategory = index
        setFilter(filter)
        val extend = filter?.let { mapOf(it.first to it.second) }.orEmpty()
        LogBus.i("UI", "点击分类: ${cat.typeName} (id=${cat.typeId}) filter=$extend")
        categoryJob = scope.launch {
            drpyLoading = true
            try {
                // v0.17.3：加超时，避免 PY 卡死导致无响应
                val list = withTimeoutOrNull(30_000) {
                    engineCategory(cat.typeId, 1, extend)
                } ?: emptyList()
                if (myGen != contentGen) return@launch // 已被更新的点击取代，丢弃
                if (list.isEmpty()) {
                    LogBus.w("UI", "分类 ${cat.typeName} 返回空（可能超时或规则异常）")
                } else {
                    LogBus.i("UI", "分类 ${cat.typeName} 加载 ${list.size} 条")
                }
                drpyList = list.ifEmpty { null }
            } finally {
                // 只有最新请求才清 loading，避免旧请求的 finally 把新请求的转圈灭掉
                if (myGen == contentGen) drpyLoading = false
            }
        }
    }

    /** v0.29.0：分类失败重试（分类行空态的"点击重试"用，只拉分类） */
    fun loadCats() {
        val key = homeCacheKey() ?: return
        catsFailed = false
        scope.launch {
            drpyLoading = true
            val cats = runCatching {
                withTimeoutOrNull(25_000) {
                    withContext(Dispatchers.IO) { engineCats() }
                }
            }.getOrNull()
            if (!cats.isNullOrEmpty()) {
                drpyCats = cats
                runCatching { withContext(Dispatchers.IO) { HomeCache.saveCats(context, key, cats) } }
                LogBus.i("Home", "分类重试成功：${cats.size} 个")
            } else {
                catsFailed = true
                LogBus.w("Home", "分类重试仍失败")
            }
            drpyLoading = false
        }
    }

    // ---- 站源切换 BottomSheet（v0.7.1） ----
    var showSourceSheet by remember { mutableStateOf(false) }
    fun refreshDrpyState() {
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
        // v0.27.4：首页不再自动加载推荐流（用户手动下拉刷新才加载）；
        // 源变化时清掉旧数据，不再显示过期缓存
        if (changed || siteChanged) {
            drpyList = null
            drpyCats = emptyList()
            selectedCategory = 0
            setFilter(null)
            homeError = null
            // v0.31.0：失败计数按数据源 key 记在 HomeLoadGate 单例里，切源自然用新 key 的计数，无需手动清零
            catsFailed = false
        }
    }

    /**
     * v0.27.6：tab 切回时 HomeScreen 的 remember 状态已被 dispose（drpyList 回到 null），
     * 静默从 HomeCache 恢复当前源的内容。不转圈、不打网络。
     * v0.28.0 真因修复：之前缓存没有时会调 loadHome() 走网络，而缓存检查是异步的，
     * ON_RESUME/LaunchedEffect 高频触发时几十个协程排队调 loadHome，
     * 全被 homeLoading guard 挡掉（"跳过重复触发"刷屏），且每次超时后 drpyList 仍空，
     * 下一次触发又进循环。现在无缓存就什么都不做——v0.27.4 起首页本来就是干净空态，
     * 用户下拉刷新才会加载，符合"首页不自动加载"的设计。
     */
    fun restoreHomeIfEmpty() {
        // v0.31.0：homeLoading 已并入 drpyLoading（单飞门接管防重入）
        if (drpyList != null || !drpyActive || drpyLoading) return
        val key = homeCacheKey() ?: return
        scope.launch {
            val cached = withContext(Dispatchers.IO) { HomeCache.load(context, key) }
            if (drpyList != null) return@launch // 恢复期间用户已操作，不覆盖
            val data = cached?.first
            if (data != null && (data.vods.isNotEmpty() || data.cats.isNotEmpty())) {
                if (data.cats.isNotEmpty()) drpyCats = data.cats
                // v0.31.2：selectedCategory/selectedFilter 已由 rememberSaveable 恢复。
                // 之前停在某个分类就重载它（保证选中和内容一致），否则才恢复首页缓存。
                if (selectedCategory >= 1 && drpyCats.getOrNull(selectedCategory - 1) != null) {
                    LogBus.i("Home", "返回首页：恢复分类 ${drpyCats[selectedCategory - 1].typeName}")
                    loadDrpyCategory(selectedCategory, selectedFilter)
                } else {
                    if (data.vods.isNotEmpty()) drpyList = data.vods
                }
                LogBus.i("Home", "返回首页：缓存恢复 ${data.vods.size} 条/${data.cats.size} 个分类")
            }
            // 无缓存：保持空态，等用户下拉刷新（不再自动调 loadHome，避免触发风暴）
        }
    }

    // 首次进入也走一遍，保证站源入口文案和数据与持久化状态一致
    // v0.27.6：冷启动保持 v0.27.4 的干净空态（不自动恢复）；只有 tab 切回（重进 composition）才恢复
    LaunchedEffect(Unit) {
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
            loadHome(force = true, onDone = {
                refreshing = false
                pullOffset = 0f
            })
        } else {
            scope.launch {
                delay(1200) // 占位：模拟刷新耗时
                refreshing = false
                pullOffset = 0f
            }
        }
    }

    // 下拉刷新占位：列表在顶部时下拉一段距离触发刷新
    val refreshConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (refreshing || source != NestedScrollSource.Drag) return Offset.Zero
                val atTop = gridState.firstVisibleItemIndex == 0 &&
                    gridState.firstVisibleItemScrollOffset == 0
                if (!atTop) {
                    pullOffset = 0f
                    return Offset.Zero
                }
                pullOffset = when {
                    available.y > 0 -> (pullOffset + available.y * 0.5f).coerceAtMost(180f)
                    available.y < 0 -> 0f
                    else -> pullOffset
                }
                if (pullOffset >= 180f) doRefresh()
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!refreshing) pullOffset = 0f
                return super.onPostFling(consumed, available)
            }
        }
    }

    // v0.27.4：首页不再自动加载，不再用演示数据冒充；无数据时显示干净空态
    val drpyUiList: List<Vod>? = drpyList?.takeIf { it.isNotEmpty() }?.map { it.toUiVod() }
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
    Box(modifier = Modifier.fillMaxSize()) {
        FlowingGlowBackground(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f)),
        ) {
            // 下拉刷新指示条（占位）
            val indicatorH = with(density) { pullOffset.toDp() }.coerceAtMost(64.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (refreshing) 56.dp else indicatorH),
                contentAlignment = Alignment.Center,
            ) {
                if (refreshing || pullOffset > 12f) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                    )
                }
            }
    
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(refreshConnection),
                contentPadding = PaddingValues(SoftDimens.PagePadding),
                verticalArrangement = Arrangement.spacedBy(SoftDimens.CardSpacing),
                horizontalArrangement = Arrangement.spacedBy(SoftDimens.CardSpacing),
            ) {
                // v0.11.8：顶部改成一行——左侧小放大镜（搜索），右侧换源胶囊
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 左侧：小放大镜搜索按钮
                        IconButton(onClick = onSearchClick) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "搜索",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // v0.24.0：播放历史
                        IconButton(onClick = onHistoryClick) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = "播放历史",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // v0.26.0：我的追剧
                        IconButton(onClick = onFollowClick) {
                            Icon(
                                imageVector = Icons.Filled.PlaylistAddCheck,
                                contentDescription = "我的追剧",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // 右侧：换源胶囊（v0.7.1 入口，收窄为右侧胶囊）
                        Surface(
                            shape = RoundedCornerShape(SoftDimens.ChipCorner),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            onClick = { showSourceSheet = true },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = sourceLabel,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = SoftType.CardTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 160.dp),
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
    
                // v0.27.4：首页不再自动加载推荐流；无数据时干净空态（不再转圈/报错/演示数据）
                if (!drpyReady) {
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
                        // v0.30.1：二级筛选行——选中分类有 filters 时显示，取第一组
                        val activeCat = drpyCats.getOrNull(selectedCategory - 1)
                        val filterGroup = activeCat?.filters?.firstOrNull()
                        if (selectedCategory >= 1 && filterGroup != null) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    filterGroup.options.forEach { fv ->
                                        val isSel = selectedFilter?.let { it.first == filterGroup.key && it.second == fv.value } == true
                                        AppChip(
                                            text = fv.name,
                                            selected = isSel,
                                            small = true,
                                            onClick = {
                                                // 点已选中的则取消筛选，回到默认
                                                val next = if (isSel) null else (filterGroup.key to fv.value)
                                                loadDrpyCategory(selectedCategory, next)
                                            },
                                        )
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
    
                    // 分区标题 + 换一换
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionTitle(
                            title = "热门推荐",
                            action = {
                                TextButton(onClick = { shuffleSeed++ }) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("换一换")
                                }
                            },
                        )
                    }
    
                    // 网格海报墙
                    items(filtered, key = { it.id }) { vod ->
                        PosterCard(
                            title = vod.title,
                            subtitle = "${vod.year} · ${vod.rating}分",
                            badge = vod.rating,
                            posterUrl = vod.poster,
                            modifier = Modifier.aspectRatio(3f / 4.6f),
                            onClick = { goDetail(vod.id) },
                        )
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
                        onChanged = { refreshDrpyState() },
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
