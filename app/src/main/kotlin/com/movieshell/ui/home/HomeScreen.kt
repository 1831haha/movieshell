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
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.Dispatchers
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
    var lastSubKey by remember { mutableStateOf<String?>(null) }

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
    suspend fun engineCategory(tid: String, pg: Int): List<ModelVod> {
        val s = subSite
        if (s != null) return runCatching { subRepo.category(s, tid, pg).second }
            .getOrDefault(emptyList())
        return runCatching {
            if (SourceStateStore.activeDrpyRule() != null) DrpySourceManager.category(tid, pg)
            else if (SourceStateStore.activePyRule() != null) PySourceManager.category(tid, pg)
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
    var selectedCategory by remember { mutableIntStateOf(0) } // 0 = 全部
    var shuffleSeed by remember { mutableIntStateOf(0) }

    // ---- drpy / PY 规则接入（drpy 优先，PY 兜底） ----
    var drpyActive by remember { mutableStateOf(false) }
    var drpyLoading by remember { mutableStateOf(false) }
    // v0.27.7：首页加载防重入（ON_RESUME/下拉可能并发触发，互 cancel 导致永远做不完）
    var homeLoading by remember { mutableStateOf(false) }
    var drpyCats by remember { mutableStateOf<List<CategoryType>>(emptyList()) }
    var drpyList by remember { mutableStateOf<List<ModelVod>?>(null) } // null=未加载/失败
    var homeError by remember { mutableStateOf<String?>(null) } // v0.25.0：加载失败文案

    /** v0.25.0：当前数据源的缓存 key（订阅站 key / 本地规则名） */
    fun homeCacheKey(): String? = when {
        subSite != null -> "sub_${subSite?.key}"
        SourceStateStore.activeDrpyRule() != null -> "drpy_${DrpySourceManager.activeRuleName()}"
        SourceStateStore.activePyRule() != null -> "py_${PySourceManager.activeRuleName()}"
        else -> null
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
     * v0.27.7：防重入——ON_RESUME/下拉刷新可能并发触发 loadHome，
     * 新调用会经 latest(slot) 取消旧的在途 PY 请求（真机 "H was cancelled" 刷屏的根因），
     * 请求永远做不完。进行中直接跳过。
     */
    fun loadHome(onDone: () -> Unit = {}) {
        val key = homeCacheKey() ?: return
        if (homeLoading) {
            LogBus.i("Home", "首页加载进行中，跳过重复触发")
            onDone()
            return
        }
        homeLoading = true
        scope.launch {
            try {
                drpyLoading = true
                homeError = null
                var cacheShown = false
                // 1. 缓存先行
                withContext(Dispatchers.IO) { HomeCache.load(context, key) }?.let { (data, fresh) ->
                    if (data.vods.isNotEmpty()) {
                        drpyCats = data.cats
                        drpyList = data.vods
                        cacheShown = true
                        LogBus.i("Home", "首页缓存命中（${if (fresh) "新鲜" else "过期"}）：${data.vods.size} 条")
                    } else if (data.cats.isNotEmpty()) {
                        drpyCats = data.cats
                    }
                }
                // 2. 并发刷新
                coroutineScope {
                    launch {
                        val home = runCatching {
                            // v0.27.1：推荐抓取给 12s（PY 规则内部 5s×2 次抓取，慢速网络 6s 不够）
                            withTimeoutOrNull(12_000) {
                                withContext(Dispatchers.IO) { engineHome() }
                            }
                        }.getOrNull()
                        if (home == null) {
                            LogBus.w("Home", "首页推荐加载超时/失败（12s 熔断）")
                        } else if (home.isNotEmpty() || !cacheShown) {
                            // 有缓存时网络返回空不覆盖，避免缓存闪一下被清空
                            drpyList = home.ifEmpty { null }
                            selectedCategory = 0
                        }
                        if (!home.isNullOrEmpty()) {
                            withContext(Dispatchers.IO) { HomeCache.saveVods(context, key, home) }
                        }
                    }
                    launch {
                        val cats = runCatching {
                            // v0.27.7：分类超时 6s→12s，和推荐对齐（真机分类反复熔断）
                            // v0.28.0：PyApi 内 homeContent 一次调用共享，推荐/分类不再排队
                            withTimeoutOrNull(12_000) {
                                withContext(Dispatchers.IO) { engineCats() }
                            }
                        }.getOrNull()
                        if (cats == null) {
                            // v0.28.0：超时不清空——缓存里的 cats（步骤1已恢复）继续用，打明确日志
                            LogBus.w(
                                "Home",
                                "首页分类加载超时/失败（12s 熔断），保留缓存分类 ${drpyCats.size} 个",
                            )
                        } else if (cats.isNotEmpty()) {
                            drpyCats = cats
                            withContext(Dispatchers.IO) { HomeCache.saveCats(context, key, cats) }
                        }
                    }
                }
                drpyLoading = false
                if (drpyList == null) {
                    homeError = "加载失败，点击重试"
                    LogBus.w("Home", "首页数据全失败且无缓存")
                }
                onDone()
            } finally {
                homeLoading = false
            }
        }
    }

    fun loadDrpyCategory(index: Int) { // index >= 1，对应 drpyCats[index-1]
        val cat = drpyCats.getOrNull(index - 1) ?: return
        LogBus.i("UI", "点击分类: ${cat.typeName} (id=${cat.typeId})")
        scope.launch {
            drpyLoading = true
            // v0.17.3：加超时，避免 PY 卡死导致无响应
            val list = withTimeoutOrNull(30_000) {
                engineCategory(cat.typeId, 1)
            } ?: emptyList()
            if (list.isEmpty()) {
                LogBus.w("UI", "分类 ${cat.typeName} 返回空（可能超时或规则异常）")
            } else {
                LogBus.i("UI", "分类 ${cat.typeName} 加载 ${list.size} 条")
            }
            drpyList = list.ifEmpty { null }
            selectedCategory = index
            drpyLoading = false
        }
    }

    // ---- 站源切换 BottomSheet（v0.7.1） ----
    var showSourceSheet by remember { mutableStateOf(false) }
    fun refreshDrpyState() {
        val cur = currentSubSite()
        val curKey = cur?.key
        val siteChanged = curKey != lastSubKey
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
            homeError = null
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
        if (drpyList != null || !drpyActive || homeLoading) return
        val key = homeCacheKey() ?: return
        scope.launch {
            val cached = withContext(Dispatchers.IO) { HomeCache.load(context, key) }
            if (drpyList != null) return@launch // 恢复期间用户已操作，不覆盖
            val data = cached?.first
            if (data != null && (data.vods.isNotEmpty() || data.cats.isNotEmpty())) {
                if (data.cats.isNotEmpty()) drpyCats = data.cats
                if (data.vods.isNotEmpty()) drpyList = data.vods
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
            loadHome(onDone = {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
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
