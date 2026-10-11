package com.movieshell.ui.home

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Vod as ModelVod
import com.movieshell.data.repo.DrpySourceManager
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

    var refreshing by remember { mutableStateOf(false) }
    var pullOffset by remember { mutableFloatStateOf(0f) }
    var selectedCategory by remember { mutableIntStateOf(0) } // 0 = 全部
    var shuffleSeed by remember { mutableIntStateOf(0) }

    // ---- drpy 接入 ----
    var drpyActive by remember { mutableStateOf(false) }
    var drpyLoading by remember { mutableStateOf(false) }
    var drpyCats by remember { mutableStateOf<List<CategoryType>>(emptyList()) }
    var drpyList by remember { mutableStateOf<List<ModelVod>?>(null) } // null=未加载/失败

    fun loadDrpyHome() {
        scope.launch {
            drpyLoading = true
            val home = runCatching { DrpySourceManager.home() }.getOrDefault(emptyList())
            val cats = runCatching { DrpySourceManager.categories() }.getOrDefault(emptyList())
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
            val list = runCatching { DrpySourceManager.category(cat.typeId, 1) }.getOrDefault(emptyList())
            drpyList = list.ifEmpty { null }
            selectedCategory = index
            drpyLoading = false
        }
    }

    fun refreshDrpyState() {
        val active = DrpySourceManager.hasActiveRule()
        val changed = active != drpyActive
        drpyActive = active
        if (active && (changed || drpyList == null)) loadDrpyHome()
    }

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
                val home = runCatching { DrpySourceManager.home() }.getOrDefault(emptyList())
                val cats = runCatching { DrpySourceManager.categories() }.getOrDefault(emptyList())
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
                    HeroPager(featured = featured, onVodClick = onVodClick)
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
                    onClick = { onVodClick(vod.id) },
                )
            }
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
