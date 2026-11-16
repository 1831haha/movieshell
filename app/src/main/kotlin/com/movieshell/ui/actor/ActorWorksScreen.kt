package com.movieshell.ui.actor

import com.movieshell.ui.theme.V5

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
import com.movieshell.domain.model.Site
import com.movieshell.domain.model.Vod as ModelVod
import com.movieshell.data.source_mgr.DrpySourceManager
import com.movieshell.data.source_mgr.PySourceManager
import com.movieshell.data.source_mgr.SiteRepository
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.LoadingSkeleton
import com.movieshell.ui.components.PosterCard
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.search.buildSearchConfigJson
import com.movieshell.ui.search.goDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.88.0：演员作品页。详情页点演员名进入，聚合搜索该演员的作品海报墙。
 * 搜索链路复用 ui.search 的共享函数（buildSearchConfigJson / SiteRepository.searchAllFlow / goDetail），
 * 不另起炉灶；点海报进详情走同一 goDetail（站点上下文照带）。
 * v1.2.20：由等全量的 searchAll 改走流式 searchAllFlow——搜片页 v0.191.0 已证实
 * 旧路会让用户对着骨架屏干等几分钟（日志里早回来的源一个都看不到），
 * 本页之前因“跨源去重需一次性全量”被特意留在旧路；现改为流式增量去重
 * （先到者胜，键口径不变），快源几秒内就出墙，页顶显示已搜进度。
 */
@Composable
fun ActorWorksScreen(
    actorName: String,
    onBack: () -> Unit,
    onVodClick: (vodId: String, siteKey: String) -> Unit,
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
    var works by remember { mutableStateOf<List<Pair<Site, ModelVod>>>(emptyList()) }
    var sourceCount by remember { mutableStateOf(0) }
    // v1.2.20：流式进度——已回分组数 / 目标源数，搜完前就显示，不再干等全量。
    var searchedCount by remember { mutableStateOf(0) }
    var targetCount by remember { mutableStateOf(0) }
    // v0.217.0（F13）：搜索世代号——换演员时旧搜索的迟到结果直接丢弃
    var searchGen by remember { mutableIntStateOf(0) }
    // v1.2.20：上一轮搜索任务——新一轮起搜时直接取消旧流，不再让两轮并行写状态。
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun doSearch() {
        searchJob?.cancel()
        searchGen += 1
        val myGen = searchGen
        searchJob = scope.launch {
            searching = true
            failed = false
            allFailed = false
            works = emptyList()
            sourceCount = 0
            searchedCount = 0
            targetCount = 0
            // 目标站表必须在 loadFromConfig 之后算，否则比的是旧站表，
            // 全失败判定会对不上 searchAllFlow 实际跑的那批源。
            var targets: List<Site> = emptyList()
            // v1.2.20：流式去重——先到者胜（不再等全量按配置顺序取首个），
            // 键口径与旧 distinctBy 一致：有年份按“片名|年份”跨源去重，
            // 年份为空时按“片名|源”并列展示，宁可多留不误删。
            val seenKeys = mutableSetOf<String>()
            var nonEmptyGroups = 0
            try {
                repo.loadFromConfig(withContext(Dispatchers.IO) { buildSearchConfigJson(context) })
                targets = repo.searchTargetSites()
                targetCount = targets.size
                repo.searchAllFlow(actorName).collect { group ->
                    if (myGen != searchGen) return@collect
                    searchedCount += 1
                    if (group.vods.isEmpty()) return@collect
                    nonEmptyGroups += 1
                    val fresh = group.vods
                        .map { vod -> group.site to vod }
                        .filter { (site, vod) -> seenKeys.add(actorDedupKey(site, vod)) }
                    if (fresh.isNotEmpty()) works = works + fresh
                }
            } catch (e: CancellationException) {
                // 新一轮已起、旧流被取消：不是失败，直接透传取消。
                throw e
            } catch (e: Exception) {
                if (myGen != searchGen) return@launch
                failed = true
                works = emptyList()
                searching = false
                return@launch
            }
            // 世代已过：新一轮搜索已起，本单不再动任何状态（包括转圈）
            if (myGen != searchGen) return@launch
            sourceCount = nonEmptyGroups
            // v0.217.0（F14）：空结果分两种——目标源全失败走失败态（带重试），
            // 否则才是真没找到。失败数读 searchAllFlow 刚记的本轮汇总
            // （collect 返回时 joinAll 已完成，计数已落定）。
            allFailed = works.isEmpty() && targets.isNotEmpty() &&
                SiteRepository.lastSearchFailedCount >= targets.size
            searching = false
        }
    }
    LaunchedEffect(actorName) { doSearch() }

    Column(modifier = Modifier.fillMaxSize()) {
        TabTopBar(title = actorName, onBack = onBack)
        when {
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
            // v1.2.20：流式——有结果就直接出墙，不再等全部源跑完才从骨架屏切过来；
            // 只有“还在搜且一张没回”时才放骨架屏。
            works.isEmpty() && searching -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(V5.Gutter),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "正在搜索「$actorName」的作品…",
                        style = MaterialTheme.typography.bodySmall,
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
                    text = if (searching) "共 ${works.size} 部作品 · 正在搜索…已搜 ${searchedCount}/${targetCount} 个源"
                        else "共 ${works.size} 部作品 · $sourceCount 个源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = V5.Gutter,
                        vertical = 4.dp,
                    ),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(V5.Gutter),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(works, key = { (site, vod) -> "${site.key}:${vod.vodId}" }) { (site, vod) ->
                        val subtitle = vod.vodYear.ifBlank { vod.typeName }.takeIf { it.isNotBlank() }
                        PosterCard(
                            title = vod.vodName,
                            subtitle = subtitle,
                            badge = vod.vodRemarks.ifBlank { null },
                            posterUrl = vod.vodPic,
                            modifier = Modifier.aspectRatio(3f / 4.6f),
                            onClick = {
                                // v0.217.0（F51）：源已删时不进详情，只提示。
                                // v0.271.0（T25）：goDetail 已是 suspend，等切完再进详情。
                                scope.launch {
                                    if (!goDetail(site, vod.vodId, onVodClick)) {
                                        AppToast.show(context, "该结果的源已删除或不可用", long = false)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * v1.2.20：演员页流式增量去重的键（纯函数，单测见 ActorWorksDedupTest）。
 * 口径与旧 distinctBy 一致：有年份按“片名|年份”跨源去重，同一部剧只留一张；
 * 年份为空时按“片名|源”并列展示（同名同年不同片、或年份缺失时宁可多留不误删）。
 */
internal fun actorDedupKey(site: Site, vod: ModelVod): String {
    val year = vod.vodYear.trim()
    return if (year.isEmpty()) {
        vod.vodName.trim().lowercase() + "|site:" + site.key
    } else {
        (vod.vodName.trim() + "|" + year).lowercase()
    }
}
