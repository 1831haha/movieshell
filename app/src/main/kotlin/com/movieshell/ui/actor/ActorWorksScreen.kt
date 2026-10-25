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
import androidx.compose.runtime.mutableIntStateOf
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
import com.movieshell.ui.components.AppToast
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
    // v0.217.0（F14）：全部源失败（非“搜到 0 部”）单独成态，走带重试的失败态
    var allFailed by remember { mutableStateOf(false) }
    var works by remember { mutableStateOf<List<Pair<Site, Vod>>>(emptyList()) }
    var sourceCount by remember { mutableStateOf(0) }
    // v0.217.0（F13）：搜索世代号——换演员时旧搜索的迟到结果直接丢弃
    var searchGen by remember { mutableIntStateOf(0) }

    fun doSearch() {
        searchGen += 1
        val myGen = searchGen
        scope.launch {
            searching = true
            failed = false
            allFailed = false
            // 目标站表必须在 loadFromConfig 之后算，否则比的是旧站表，
            // 全失败判定会对不上 searchAll 实际跑的那批源。
            var targets: List<Site> = emptyList()
            val groups = runCatching {
                repo.loadFromConfig(buildSearchConfigJson(context))
                targets = repo.searchTargetSites()
                repo.searchAll(actorName)
            }.getOrNull()
            // 世代已过：新一轮搜索已起，本单不再动任何状态（包括转圈）
            if (myGen != searchGen) return@launch
            if (groups == null) {
                failed = true
                works = emptyList()
            } else {
                sourceCount = groups.size
                // v0.217.0（F14）：空结果分两种——目标源全失败走失败态（带重试），
                // 否则才是真没找到。失败数读 searchAll 刚记的本轮汇总。
                allFailed = groups.isEmpty() && targets.isNotEmpty() &&
                    SiteRepository.lastSearchFailedCount >= targets.size
                // 跨源按"片名+年份"去重，同一部剧只留一张海报
                // v0.217.0（F15）：年份为空时不再仅凭片名去重（同名同年不同片、
                // 或同名片年份缺失时会被误删），此时按源区分、宁可并列展示。
                works = groups
                    .flatMap { g -> g.vods.map { vod -> g.site to vod.toUiVod() } }
                    .distinctBy { (site, vod) ->
                        val year = vod.year.trim()
                        if (year.isEmpty()) {
                            vod.title.trim().lowercase() + "|site:" + site.key
                        } else {
                            (vod.title.trim() + "|" + year).lowercase()
                        }
                    }
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
            failed || allFailed -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        kind = EmptyKind.Error,
                        icon = Icons.Filled.Warning,
                        title = if (allFailed) "全部源搜索失败，请检查网络后重试"
                            else "加载失败，请检查网络后重试",
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
                            onClick = {
                                // v0.217.0（F51）：源已删时不进详情，只提示。
                                if (!goDetail(site, vod.id, onVodClick)) {
                                    AppToast.show(context, "该结果的源已删除或不可用", long = false)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
