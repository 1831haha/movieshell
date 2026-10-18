package com.movieshell.ui.actor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movieshell.data.model.Site
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SiteRepository
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.LoadingSkeleton
import com.movieshell.ui.components.PosterCard
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.data.Vod
import com.movieshell.ui.search.buildSearchConfigJson
import com.movieshell.ui.search.goDetail
import com.movieshell.ui.search.toUiVod
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.launch

/**
 * v0.88.0：演员作品页。详情页点演员名进入，聚合搜索该演员的作品海报墙。
 * 搜索链路复用 ui.search 的共享函数（buildSearchConfigJson / SiteRepository.searchAll / goDetail），
 * 不另起炉灶；点海报进详情走同一 goDetail（站点上下文照带）。
 */
@Composable
fun ActorWorksScreen(
    actorName: String,
    onBack: () -> Unit,
    onVodClick: (vodId: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 聚合搜索 repo：drpy / PY 走当前激活规则的引擎适配器（与搜索页同一套）
    val repo = remember {
        SiteRepository(
            drpyEngine = DrpySourceManager.drpyEngineAdapter,
            pyEngine = PySourceManager.engineAdapter,
        )
    }
    var searching by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var works by remember { mutableStateOf<List<Pair<Site, Vod>>>(emptyList()) }
    var sourceCount by remember { mutableStateOf(0) }

    fun doSearch() {
        scope.launch {
            searching = true
            failed = false
            val groups = runCatching {
                repo.loadFromConfig(buildSearchConfigJson(context))
                repo.searchAll(actorName)
            }.getOrNull()
            if (groups == null) {
                failed = true
                works = emptyList()
            } else {
                sourceCount = groups.size
                // 跨源按"片名+年份"去重，同一部剧只留一张海报
                works = groups
                    .flatMap { g -> g.vods.map { vod -> g.site to vod.toUiVod() } }
                    .distinctBy { (_, vod) -> (vod.title.trim() + "|" + vod.year.trim()).lowercase() }
            }
            searching = false
        }
    }
    LaunchedEffect(actorName) { doSearch() }

    Column(modifier = Modifier.fillMaxSize()) {
        TabTopBar(title = actorName, onBack = onBack)
        when {
            searching -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(SoftDimens.PagePadding),
                    verticalArrangement = Arrangement.spacedBy(SoftDimens.CardSpacing),
                ) {
                    Text(
                        text = "正在搜索「$actorName」的作品…",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    repeat(4) {
                        LoadingSkeleton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(112.dp),
                        )
                    }
                }
            }
            failed -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        kind = EmptyKind.Error,
                        icon = Icons.Filled.Warning,
                        title = "加载失败，请检查网络后重试",
                        actionText = "重试",
                        onAction = { doSearch() },
                    )
                }
            }
            works.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.Search,
                        title = "没有找到「$actorName」相关作品",
                    )
                }
            }
            else -> {
                Text(
                    text = "共 ${works.size} 部作品 · $sourceCount 个源",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = SoftDimens.PagePadding,
                        vertical = 4.dp,
                    ),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(SoftDimens.PagePadding),
                    verticalArrangement = Arrangement.spacedBy(SoftDimens.CardSpacing),
                    horizontalArrangement = Arrangement.spacedBy(SoftDimens.CardSpacing),
                ) {
                    items(works, key = { (site, vod) -> "${site.key}:${vod.id}" }) { (site, vod) ->
                        val subtitle = vod.year.ifBlank { vod.category }.takeIf { it.isNotBlank() }
                        PosterCard(
                            title = vod.title,
                            subtitle = subtitle,
                            badge = vod.rating.ifBlank { null },
                            posterUrl = vod.poster,
                            modifier = Modifier.aspectRatio(3f / 4.6f),
                            onClick = { goDetail(site, vod.id, onVodClick) },
                        )
                    }
                }
            }
        }
    }
}
