package com.movieshell.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.Context
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod as ModelVod
import com.movieshell.data.model.isSubSite
import com.movieshell.data.repo.ApiSourceStore
import com.movieshell.data.repo.DetailSiteHolder
import com.movieshell.data.repo.SourceStateStore
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SearchHistoryStore
import com.movieshell.data.repo.SiteRepository
import com.movieshell.data.repo.SiteSearchResult
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppChip
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.LoadingSkeleton
import com.movieshell.ui.components.PosterCard
import com.movieshell.ui.data.DemoData
import com.movieshell.ui.data.Vod
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** drpy 影片 -> 界面 Vod */
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

/**
 * 为聚合搜索拼 TVBox 订阅 JSON：
 * - 接口站源：ApiSourceStore 里启用的条目。先判断——能解析出 sites 数组的就是
 *   完整 TVBox 配置（SubSiteStore.ensureExpanded 下载展开），逐站搜
 *   （csp_ 等不支持的站在展开时已剔除）；无 sites 的纯 CMS 地址保持旧逻辑 type=1；
 * - 本地 drpy / PY 规则：有激活规则时各补一条合成站点（走引擎适配器）。
 * 网页源是 URL 直达形态、无搜索接口，不参与聚合搜索。
 */
private suspend fun buildSearchConfigJson(context: Context): String {
    val arr = JSONArray()
    ApiSourceStore.load(context)
        .filter { it.enabled }
        .forEach { s ->
            val cache = SubSiteStore.ensureExpanded(context, s.url)
            if (cache != null) {
                // 完整 TVBox 配置：逐站（不支持的 kind 在展开时已标 unsupported，这里跳过）
                cache.sites
                    .filter { it.enabled && it.kind != SubSiteStore.KIND_UNSUPPORTED }
                    .forEach { rec ->
                        val site = SubSiteStore.toSite(cache.subUrl, rec)
                        arr.put(
                            JSONObject().apply {
                                put("key", site.key)
                                put("name", site.name)
                                put("type", site.type)
                                put("api", site.api)
                                put("searchable", if (rec.searchable) 1 else 0)
                                put("类型", site.typeLabel)
                                put("subUrl", cache.subUrl)
                            }
                        )
                    }
            } else {
                // 纯 CMS 地址（无 sites）：保持旧逻辑
                arr.put(
                    JSONObject().apply {
                        put("key", "cms:${s.url}")
                        put("name", s.name)
                        put("type", 1)
                        put("api", s.url)
                        put("subUrl", s.url)
                    }
                )
            }
        }
    // v0.24.0：被禁用的规则不参与聚合搜索（单点：SourceStateStore）
    if (SourceStateStore.activeDrpyRule() != null) {
        arr.put(
            JSONObject().apply {
                put("key", "drpy:active")
                put("name", "drpy:${DrpySourceManager.activeRuleName().orEmpty()}")
                put("type", 4)
                put("api", "drpy")
            }
        )
    }
    if (SourceStateStore.activePyRule() != null) {
        arr.put(
            JSONObject().apply {
                put("key", "py:active")
                put("name", "py:${PySourceManager.activeRuleName().orEmpty()}")
                put("type", 3)
                put("api", "active.py")
            }
        )
    }
    return JSONObject().put("sites", arr).toString()
}

@Composable
fun SearchScreen(onVodClick: (vodId: String) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // 聚合搜索 repo：drpy / PY 走当前激活规则的引擎适配器
    val repo = remember {
        SiteRepository(
            drpyEngine = DrpySourceManager.drpyEngineAdapter,
            pyEngine = PySourceManager.engineAdapter,
        )
    }
    var searching by remember { mutableStateOf(false) }
    var groupedResults by remember { mutableStateOf<List<SiteSearchResult>?>(null) } // null=尚未搜过

    // 搜索历史：进入页面读一次，切回页面时刷新
    fun refreshHistory() {
        history.clear()
        history.addAll(SearchHistoryStore.list(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        refreshHistory()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshHistory()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun doSearch(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        submitted = t
        SearchHistoryStore.add(context, t)
        refreshHistory()
        focusManager.clearFocus()
        searching = true
        groupedResults = null
        scope.launch {
            repo.loadFromConfig(buildSearchConfigJson(context))
            groupedResults = repo.searchAll(t)
            searching = false
        }
    }

    /**
     * 进详情：订阅子站点 / CMS 订阅站把站点上下文带给详情页（详情/播放按站走引擎）；
     * 本地规则合成站（drpy:active / py:active）传 null，详情页走当前激活规则旧逻辑。
     */
    fun goDetail(site: Site, vodId: String) {
        DetailSiteHolder.site = when {
            site.isSubSite() -> site
            site.key.startsWith("cms:") -> site
            else -> null
        }
        onVodClick(vodId)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 搜索框（v0.27.0：AppTextField；左右配搜索/清空按钮，点放大镜即搜）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SoftDimens.PagePadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { doSearch(query) }) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索影片、剧集、演员…",
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch(query) }),
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(Icons.Filled.Clear, contentDescription = "清空")
                }
            }
        }

        if (submitted.isBlank()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = SoftDimens.PagePadding),
            ) {
                // 热搜词
                item {
                    SectionHeader(icon = Icons.Filled.TrendingUp, title = "热搜")
                    HotSearchFlow(
                        words = DemoData.hotSearches,
                        onPick = { query = it; doSearch(it) },
                    )
                    Spacer(Modifier.height(20.dp))
                }
                // 搜索历史
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SectionHeader(icon = Icons.Filled.History, title = "搜索历史")
                        if (history.isNotEmpty()) {
                            TextButton(onClick = {
                                SearchHistoryStore.clear(context)
                                refreshHistory()
                            }) { Text("清空") }
                        }
                    }
                }
                if (history.isEmpty()) {
                    item {
                        Text(
                            text = "暂无搜索历史",
                            style = SoftType.Body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    items(history, key = { it }) { h ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { query = h; doSearch(h) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = h,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 10.dp),
                                style = SoftType.Body,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            IconButton(onClick = {
                                history.remove(h)
                                // 同步回持久化：删掉该条后重写
                                SearchHistoryStore.clear(context)
                                history.forEach { SearchHistoryStore.add(context, it) }
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "删除",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // 聚合搜索结果：按源分组
            if (searching || groupedResults == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(SoftDimens.PagePadding),
                    verticalArrangement = Arrangement.spacedBy(SoftDimens.CardSpacing),
                ) {
                    Text(
                        text = "聚合搜索中…",
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
            } else if (groupedResults.isNullOrEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.Search,
                        title = "没有找到「$submitted」相关结果",
                        actionText = "返回",
                        onAction = { submitted = ""; query = "" },
                    )
                }
            } else {
                val groups = groupedResults.orEmpty()
                val total = groups.sumOf { it.vods.size }
                Text(
                    text = "共 $total 个结果 · ${groups.size} 个源",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = SoftDimens.PagePadding,
                        vertical = 4.dp,
                    ),
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    groups.forEach { group ->
                        // 分组头：源名 + 条数
                        item(key = "head:${group.site.key}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = SoftDimens.PagePadding,
                                        vertical = 10.dp,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = group.site.name,
                                    style = SoftType.CardTitle,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "${group.vods.size} 条",
                                    style = SoftType.Caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val uiVods = group.vods.map { it.toUiVod() }
                        items(uiVods, key = { "vod:${group.site.key}:${it.id}" }) { vod ->
                            AppCard(
                                onClick = { goDetail(group.site, vod.id) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = SoftDimens.PagePadding,
                                        vertical = 4.dp,
                                    ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(SoftDimens.CardSpacing),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PosterCard(
                                        title = vod.title,
                                        posterUrl = vod.poster,
                                        modifier = Modifier
                                            .width(72.dp)
                                            .height(104.dp),
                                    )
                                    Spacer(Modifier.width(SoftDimens.CardSpacing))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${vod.category} · ${vod.year}",
                                            style = SoftType.Body,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "${vod.rating}分 · ${vod.actors}",
                                            style = SoftType.Caption,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(onClick = { goDetail(group.site, vod.id) }) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = "播放",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = SoftType.CardTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(10.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HotSearchFlow(
    words: List<String>,
    onPick: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        words.forEachIndexed { index, word ->
            AppChip(
                text = "${index + 1}. $word",
                selected = index < 3,
                onClick = { onPick(word) },
            )
        }
    }
}
