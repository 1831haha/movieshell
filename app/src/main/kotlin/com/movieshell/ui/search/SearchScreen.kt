package com.movieshell.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.Context
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod as ModelVod
import com.movieshell.data.model.isSubSite
import com.movieshell.data.repo.ApiSourceStore
import com.movieshell.data.repo.DetailSiteHolder
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SearchHistoryStore
import com.movieshell.data.repo.SiteRepository
import com.movieshell.data.repo.SiteSearchResult
import com.movieshell.ui.components.GradientBlock
import com.movieshell.ui.data.DemoData
import com.movieshell.ui.data.Vod
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
    if (DrpySourceManager.hasActiveRule()) {
        arr.put(
            JSONObject().apply {
                put("key", "drpy:active")
                put("name", "drpy:${DrpySourceManager.activeRuleName().orEmpty()}")
                put("type", 4)
                put("api", "drpy")
            }
        )
    }
    if (PySourceManager.hasActiveRule()) {
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

@OptIn(ExperimentalMaterial3Api::class)
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
        // 搜索框
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            placeholder = { Text("搜索影片、剧集、演员…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "搜索") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "清空")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch(query) }),
        )

        if (submitted.isBlank()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp),
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
                            style = MaterialTheme.typography.bodyMedium,
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
                                style = MaterialTheme.typography.bodyMedium,
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
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "聚合搜索中…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (groupedResults.isNullOrEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "没有找到「$submitted」相关结果",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { submitted = ""; query = "" }) { Text("返回") }
                }
            } else {
                val groups = groupedResults.orEmpty()
                val total = groups.sumOf { it.vods.size }
                Text(
                    text = "共 $total 个结果 · ${groups.size} 个源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    groups.forEach { group ->
                        // 分组头：源名 + 条数
                        item(key = "head:${group.site.key}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = group.site.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "${group.vods.size} 条",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val uiVods = group.vods.map { it.toUiVod() }
                        items(uiVods, key = { "vod:${group.site.key}:${it.id}" }) { vod ->
                            ListItem(
                                headlineContent = {
                                    Text(vod.title, fontWeight = FontWeight.SemiBold)
                                },
                                supportingContent = {
                                    Text("${vod.category} · ${vod.year} · ${vod.rating}分 · ${vod.actors}")
                                },
                                leadingContent = {
                                    GradientBlock(
                                        key = vod.id,
                                        modifier = Modifier
                                            .size(width = 48.dp, height = 68.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                    ) {
                                        Text(
                                            text = vod.title.first().toString(),
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                },
                                trailingContent = {
                                    IconButton(onClick = { goDetail(group.site, vod.id) }) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = "播放",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                modifier = Modifier.clickable { goDetail(group.site, vod.id) },
                            )
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
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
    }
    Spacer(Modifier.height(10.dp))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
            SuggestionChip(
                onClick = { onPick(word) },
                label = {
                    Text(
                        text = "${index + 1}. $word",
                        color = if (index < 3) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}
