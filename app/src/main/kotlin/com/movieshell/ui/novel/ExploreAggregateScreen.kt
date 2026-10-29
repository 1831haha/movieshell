package com.movieshell.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.movieshell.data.novel.BookSource
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelSearchResult
import com.movieshell.data.novel.NovelSourceEngine
import com.movieshell.ui.components.TechTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.214.0：发现聚合页（报告第 14 项：发现页原来只能单源钻进去看）。
 * - 顶部是筛选搜索框（只筛已抓到的书名／作者，不另外发搜索请求）；
 * - 下方按源分组：源名标题 + 分类 chips（切分类只重抓该源）+ 横滑书行；
 * - 每行点书走现有入架流程（joinShelf），每组右上角可进该源单源发现页；
 * - 只聚带发现分类的已启用 JSON 书源；PY 源没有发现分类体系，不进聚合。
 */
@Composable
fun ExploreAggregateScreen(
    onBack: () -> Unit = {},
    onOpenExplore: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val searchVm: NovelSearchViewModel = viewModel()
    val joining by searchVm.joining.collectAsState()
    val sources by BookSourceManager.sources.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { BookSourceManager.load(context) }
    }

    val aggSources = sources.filter { it.enabled && it.exploreUrl.isNotBlank() }
    val categoriesByKey = remember(aggSources) {
        aggSources.associate { it.key to NovelSourceEngine.parseExploreCategories(it.exploreUrl) }
    }
    var selectedCat by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // v0.247.0（F14）：结果/加载/失败原来只按源分，换分类就整组“加载中…”、
    // 上个分类的结果也没留住；现在按（源，分类地址）区分——地址即抓取
    // 身份：同地址同内容可共态，同标题不同地址分态（标题在数组形源里
    // 可能重复，地址不会）。切换分类各抓各的、抓过的分类切回去直接显示。
    var resultsByCat by remember { mutableStateOf<Map<Pair<String, String>, List<NovelSearchResult>>>(emptyMap()) }
    var loadingCats by remember { mutableStateOf<Set<Pair<String, String>>>(emptySet()) }
    var failedCats by remember { mutableStateOf<Set<Pair<String, String>>>(emptySet()) }

    fun load(src: BookSource, cat: NovelSourceEngine.ExploreCategory) {
        val key = src.key to cat.url
        if (loadingCats.contains(key)) return
        loadingCats = loadingCats + key
        failedCats = failedCats - key
        scope.launch {
            val list = runCatching {
                NovelSourceEngine.explore(src, cat.url, 1, allowGate = true)
            }.getOrDefault(emptyList())
            resultsByCat = resultsByCat + (key to list)
            if (list.isEmpty()) failedCats = failedCats + key
            loadingCats = loadingCats - key
        }
    }

    // 首屏：每源默认抓第一个分类；源列表变化时只补抓新源，不重抓已抓过的。
    LaunchedEffect(aggSources) {
        aggSources.forEach { src ->
            val cats = categoriesByKey[src.key].orEmpty()
            val first = cats.firstOrNull() ?: return@forEach
            val firstKey = src.key to first.url
            if (resultsByCat.containsKey(firstKey) || loadingCats.contains(firstKey)) return@forEach
            selectedCat = selectedCat + (src.key to first.title)
            load(src, first)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
            Text(
                "发现聚合",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${aggSources.size} 个源",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TechTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "筛选书名 / 作者",
        )
        Spacer(Modifier.height(8.dp))
        when {
            aggSources.isEmpty() -> Text(
                "没有带发现分类的已启用书源：去书源管理启用带发现地址的源。",
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                aggSources.forEach { src ->
                    val cats = categoriesByKey[src.key].orEmpty()
                    val selTitle = selectedCat[src.key] ?: cats.firstOrNull()?.title
                    val selCat = cats.firstOrNull { it.title == selTitle } ?: cats.firstOrNull()
                    val selKey = selCat?.let { src.key to it.url }
                    val raw = selKey?.let { resultsByCat[it] }.orEmpty()
                    val isLoadingCat = selKey != null && loadingCats.contains(selKey)
                    val shown = if (query.isBlank()) raw else raw.filter {
                        it.title.contains(query, ignoreCase = true) ||
                            (it.author?.contains(query, ignoreCase = true) == true)
                    }
                    // 有筛选词且该源筛空时，整组隐藏（不留空标题）。
                    if (query.isBlank() || shown.isNotEmpty() || isLoadingCat) {
                        item(key = "head-${src.key}") {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        src.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { onOpenExplore(src.key) }) {
                                        Text("进发现页", fontSize = 12.sp)
                                    }
                                }
                                if (cats.isNotEmpty()) {
                                    LazyRow {
                                        items(cats, key = { it.title }) { cat ->
                                            FilterChip(
                                                selected = cat.title == selTitle,
                                                onClick = {
                                                    selectedCat = selectedCat + (src.key to cat.title)
                                                    load(src, cat)
                                                },
                                                label = { Text(cat.title) },
                                                modifier = Modifier.padding(end = 6.dp),
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                        when {
                            isLoadingCat -> item(key = "loading-${src.key}") {
                                Text("加载中…", fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                            }
                            selKey != null && failedCats.contains(selKey) && raw.isEmpty() -> item(key = "fail-${src.key}") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "这一页没有结果（可能撞上验证，稍后重试）",
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = {
                                        selCat?.let { load(src, it) }
                                    }) { Text("重试") }
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                            else -> item(key = "row-${src.key}") {
                                LazyRow {
                                    items(shown, key = { it.remoteId }) { book ->
                                        val group = SourceGroup(src.key, src.name, raw)
                                        val isJoining = (group.key + book.remoteId) in joining
                                        Column(
                                            Modifier
                                                .width(110.dp)
                                                .clickable(enabled = !isJoining) {
                                                    searchVm.joinShelf(group, book)
                                                }
                                                .padding(end = 10.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(width = 110.dp, height = 148.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                if (!book.cover.isNullOrBlank()) {
                                                    AsyncImage(
                                                        model = book.cover,
                                                        contentDescription = book.title,
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentScale = ContentScale.Crop,
                                                    )
                                                } else {
                                                    Text(
                                                        book.title.firstOrNull()?.toString() ?: "书",
                                                        fontSize = 24.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                book.title,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (!book.author.isNullOrBlank()) {
                                                Text(
                                                    book.author,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            if (isJoining) {
                                                Text(
                                                    "加入中…",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
