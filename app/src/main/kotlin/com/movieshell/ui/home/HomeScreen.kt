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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod as ModelVod
import com.movieshell.data.model.isCmsSite
import com.movieshell.data.model.isPySite
import com.movieshell.data.repo.ApiSource
import com.movieshell.data.repo.ApiSourceStore
import com.movieshell.data.repo.DetailSiteHolder
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SiteRepository
import com.movieshell.data.repo.SubSiteEngines
import com.movieshell.data.repo.SubSiteRecord
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.ui.components.GradientBlock
import com.movieshell.ui.components.PosterCard
import com.movieshell.ui.data.DemoData
import com.movieshell.ui.data.Vod
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onSearchClick: () -> Unit,
    onVodClick: (vodId: String) -> Unit,
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
            if (DrpySourceManager.hasActiveRule()) DrpySourceManager.home()
            else if (PySourceManager.hasActiveRule()) PySourceManager.home()
            else emptyList()
        }.getOrDefault(emptyList())
    }

    /** 当前数据源的分类：订阅 CMS 站走 CMS 分类接口，drpy/PY 站走引擎 categories() */
    suspend fun engineCats(): List<CategoryType> {
        val s = subSite
        if (s != null) {
            if (s.isCmsSite()) return runCatching { subRepo.category(s, "", 1).first }
                .getOrDefault(emptyList())
            if (s.isPySite()) return runCatching { SubSiteEngines.pyEngineFor(s)?.categories() }
                .getOrNull().orEmpty()
            return runCatching { SubSiteEngines.drpyEngineFor(s)?.categories() }
                .getOrNull().orEmpty()
        }
        return runCatching {
            if (DrpySourceManager.hasActiveRule()) DrpySourceManager.categories()
            else if (PySourceManager.hasActiveRule()) PySourceManager.categories()
            else emptyList()
        }.getOrDefault(emptyList())
    }

    /** 当前数据源的分类列表 */
    suspend fun engineCategory(tid: String, pg: Int): List<ModelVod> {
        val s = subSite
        if (s != null) return runCatching { subRepo.category(s, tid, pg).second }
            .getOrDefault(emptyList())
        return runCatching {
            if (DrpySourceManager.hasActiveRule()) DrpySourceManager.category(tid, pg)
            else if (PySourceManager.hasActiveRule()) PySourceManager.category(tid, pg)
            else emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * 进详情：订阅当前源时把站点上下文带给详情页（详情/播放按站走引擎），
     * 否则传 null，详情页走本地规则旧逻辑。
     */
    fun goDetail(vodId: String) {
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
    var drpyCats by remember { mutableStateOf<List<CategoryType>>(emptyList()) }
    var drpyList by remember { mutableStateOf<List<ModelVod>?>(null) } // null=未加载/失败

    fun loadDrpyHome() {
        scope.launch {
            drpyLoading = true
            val home = engineHome()
            val cats = engineCats()
            drpyCats = cats
            drpyList = home.ifEmpty { null }
            selectedCategory = 0
            drpyLoading = false
        }
    }

    fun loadDrpyCategory(index: Int) { // index >= 1，对应 drpyCats[index-1]
        val cat = drpyCats.getOrNull(index - 1) ?: return
        scope.launch {
            drpyLoading = true
            val list = engineCategory(cat.typeId, 1)
            drpyList = list.ifEmpty { null }
            selectedCategory = index
            drpyLoading = false
        }
    }

    // ---- 站源切换 BottomSheet（v0.7.1） ----
    var showSourceSheet by remember { mutableStateOf(false) }
    val sourceSheetState = rememberModalBottomSheetState()

    fun refreshDrpyState() {
        val cur = currentSubSite()
        val curKey = cur?.key
        val siteChanged = curKey != lastSubKey
        subSite = cur
        lastSubKey = curKey
        val active = cur != null ||
            DrpySourceManager.hasActiveRule() || PySourceManager.hasActiveRule()
        val changed = active != drpyActive
        drpyActive = active
        if (active && (changed || siteChanged || drpyList == null)) loadDrpyHome()
    }

    // 首次进入也走一遍，保证站源入口文案和数据与持久化状态一致
    LaunchedEffect(Unit) { refreshDrpyState() }

    // 底部 tab 切换回来（saveState 恢复时）也要重新检查激活状态，
    // 否则在站源页设了当前规则后首页还停留在演示数据
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshDrpyState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun doRefresh() {
        if (refreshing) return
        refreshing = true
        if (drpyActive) {
            // 下拉刷新：重调 home
            scope.launch {
                val home = engineHome()
                val cats = engineCats()
                drpyCats = cats
                drpyList = home.ifEmpty { null }
                selectedCategory = 0
                refreshing = false
                pullOffset = 0f
            }
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

    // drpy 数据就绪：用真实数据；失败/为空 -> 回退演示数据
    val drpyUiList: List<Vod>? = drpyList?.takeIf { it.isNotEmpty() }?.map { it.toUiVod() }
    val drpyReady = drpyActive && drpyUiList != null
    val chipLabels: List<String> =
        if (drpyReady && drpyCats.isNotEmpty()) listOf("全部") + drpyCats.map { it.typeName }
        else listOf("全部") + DemoData.categories

    val filtered: List<Vod> = when {
        drpyReady -> remember(shuffleSeed, drpyUiList) {
            val base = drpyUiList!!
            if (shuffleSeed == 0) base
            else base.drop(shuffleSeed % base.size) + base.take(shuffleSeed % base.size)
        }
        else -> remember(selectedCategory, shuffleSeed) {
            val base = if (selectedCategory == 0) {
                DemoData.vods
            } else {
                val cat = DemoData.categories[selectedCategory - 1]
                DemoData.vods.filter { it.category == cat }
            }
            if (shuffleSeed == 0) base else base.drop(shuffleSeed % base.size) + base.take(shuffleSeed % base.size)
        }
    }

    val featured: List<Vod> = (if (drpyReady) drpyUiList else DemoData.vods).orEmpty().take(3)

    // 站源入口行文案：订阅当前源 > 本地 drpy > 本地 PY；都没有 -> 演示数据
    val sourceLabel: String = when {
        subSite != null -> subSite?.name?.ifBlank { "订阅源" } ?: "订阅源"
        DrpySourceManager.hasActiveRule() -> "本地·${DrpySourceManager.activeRuleName().orEmpty()}"
        PySourceManager.hasActiveRule() -> "本地·${PySourceManager.activeRuleName().orEmpty()}"
        else -> "演示数据"
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(refreshConnection),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 站源切换入口（v0.7.1）：显示当前源，点击弹 BottomSheet
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = { showSourceSheet = true },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = sourceLabel,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = "切换站源",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 顶部搜索栏（点击跳到搜索页）
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = onSearchClick,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "搜索",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "搜索影片、剧集、综艺…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // drpy 首次加载中
            if (drpyActive && drpyLoading && drpyList == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            // 推荐位横滑大卡片
            if (featured.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    HeroPager(featured = featured, onVodClick = ::goDetail)
                }
            }

            // 分类行：drpy 真实分类；失败回退演示分类
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chipLabels.forEachIndexed { index, label ->
                        FilterChip(
                            selected = selectedCategory == index,
                            onClick = {
                                if (drpyReady && drpyCats.isNotEmpty()) {
                                    if (index == 0) loadDrpyHome() else loadDrpyCategory(index)
                                } else {
                                    selectedCategory = index
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }

            // 分区标题 + 换一换
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "热门推荐",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
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
            }

            // 网格海报墙
            items(filtered, key = { it.id }) { vod ->
                PosterCard(
                    title = vod.title,
                    subtitle = "${vod.year} · ${vod.rating}分",
                    badge = vod.rating,
                    modifier = Modifier.aspectRatio(3f / 4.6f),
                    onClick = { goDetail(vod.id) },
                )
            }
        }
    }

    // 站源切换 BottomSheet（v0.7.1）
    if (showSourceSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSourceSheet = false },
            sheetState = sourceSheetState,
        ) {
            SourceSwitchSheet(
                onDismiss = { showSourceSheet = false },
                onChanged = { refreshDrpyState() },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroPager(
    featured: List<Vod>,
    onVodClick: (vodId: String) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { featured.size })
    Column {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 4.dp),
            pageSpacing = 12.dp,
        ) { page ->
            val vod = featured[page]
            GradientBlock(
                key = vod.id,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onVodClick(vod.id) },
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    0f to Color(0xB3000000),
                                    0.6f to Color.Transparent,
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                    ) {
                        Text(
                            text = "本周推荐",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = vod.title,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${vod.category} · ${vod.year} · ${vod.rating}分",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 20.dp)
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(0xCC000000))
                            .clickable { onVodClick(vod.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "播放",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(featured.size) { i ->
                val selected = pagerState.currentPage == i
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(width = if (selected) 20.dp else 6.dp, height = 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                        )
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
            style = MaterialTheme.typography.labelSmall,
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
            style = MaterialTheme.typography.labelSmall,
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
                    style = MaterialTheme.typography.bodyMedium,
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
                    style = MaterialTheme.typography.labelSmall,
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
 * 站源切换 BottomSheet（v0.7.1）。
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
    val cur = remember { SubSiteStore.getCurrent(context) }
    val drpyRules = remember { DrpySourceManager.listRules() }
    val pyRules = remember { PySourceManager.listRules() }
    val drpyActiveName = DrpySourceManager.activeRuleName()
    val pyActiveName = PySourceManager.activeRuleName()
    // 启用的订阅 × 解析出的站点，按订阅分组
    val subSites: List<Pair<ApiSource, SubSiteRecord>> = remember {        ApiSourceStore.load(context).filter { it.enabled }.flatMap { sub ->
            val sites = SubSiteStore.getCached(context, sub.url)?.sites.orEmpty()
            sites.map { sub to it }
        }
    }

    fun pickLocal(isDrpy: Boolean, name: String) {
        SubSiteStore.clearCurrent(context)
        if (isDrpy) {
            PySourceManager.setActiveRule(null)
            DrpySourceManager.setActiveRule(name)
        } else {
            DrpySourceManager.setActiveRule(null)
            PySourceManager.setActiveRule(name)
        }
        onChanged()
        onDismiss()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "切换站源",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))

        if (drpyRules.isNotEmpty() || pyRules.isNotEmpty()) {
            Text(
                text = "本地规则",
                style = MaterialTheme.typography.labelLarge,
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
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    lastSubUrl = sub.url
                }
                val unsupported = rec.kind == SubSiteStore.KIND_UNSUPPORTED
                SourceSheetRow(
                    title = rec.name.ifBlank { rec.key },
                    subtitle = null,
                    checked = cur != null && cur.subUrl == sub.url && cur.siteKey == rec.key,
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
