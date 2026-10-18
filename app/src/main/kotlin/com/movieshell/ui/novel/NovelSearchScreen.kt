package com.movieshell.ui.novel

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.Button
import com.movieshell.ui.components.AppCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.model.Chapter
import com.movieshell.data.model.Novel
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelSearchResult
import com.movieshell.data.novel.NovelSourceEngine
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.repo.NovelStore
import com.movieshell.data.repo.SearchHistoryStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * v0.61.0 找书页：多书源并发真搜 → 加入书架（只拉目录，正文阅读时按需拉取）。
 * v0.64.0：JSON 书源 + 本地 PY 书源一起搜。
 * v0.66.0：支持锁定单个 PY 源（pySourceName 非空时只搜该源，用于书源管理点源直达）。
 */
@Composable
fun NovelSearchScreen(
    onBack: () -> Unit = {},
    onManageSources: () -> Unit = {},
    pySourceName: String? = null,
    embedded: Boolean = false, // v0.79.0：嵌入书架子 tab 时隐藏头部
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gold = Color(0xFFD9C39A)

    val sources by BookSourceManager.sources.collectAsState()
    val pyRules by PyBookSourceManager.rules.collectAsState()
    val pyEnabled by PyBookSourceManager.enabled.collectAsState()
    var keyword by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SourceGroup>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf<String?>(null) }
    // v0.67.0：小说搜索历史（独立作用域）
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    fun refreshHistory() { history = SearchHistoryStore.list(context, "novel") }

    LaunchedEffect(Unit) {
        BookSourceManager.load(context)
        PyBookSourceManager.load(context)
        refreshHistory()
    }

    val enabledSources = remember(sources) { sources.filter { it.enabled } }
    val enabledPyRules = remember(pyRules, pyEnabled) { pyRules.filter { it.name in pyEnabled } }
    // v0.66.0：锁定单 PY 源时只搜它
    val lockedPyRule = remember(pyRules, pySourceName) {
        pySourceName?.let { name -> pyRules.find { it.name == name } }
    }
    val totalEnabled = enabledSources.size + enabledPyRules.size

    fun doSearch() {
        val kw = keyword.trim()
        // v0.66.0：锁定模式下只要该源存在即可搜
        val canSearch = if (lockedPyRule != null) true else totalEnabled > 0
        if (kw.isEmpty() || !canSearch) {
            if (!canSearch) {
                Toast.makeText(context, "没有启用的书源，先去书源管理添加", Toast.LENGTH_SHORT).show()
            }
            return
        }
        // v0.67.0：记搜索历史
        SearchHistoryStore.add(context, kw, "novel")
        refreshHistory()
        scope.launch {
            loading = true
            searched = true
            results = coroutineScope {
                // v0.66.0：锁定单 PY 源 → 只搜它
                if (lockedPyRule != null) {
                    NovelSourceEngine.searchAllPy(context, listOf(lockedPyRule), kw)
                        .filter { it.second.isNotEmpty() }
                        .map { (r, books) ->
                            SourceGroup(
                                PyBookSourceManager.SOURCE_KEY_PREFIX + r.name,
                                "${r.title}（本地PY）",
                                books,
                            )
                        }
                } else {
                    val jsonJob = async {
                        NovelSourceEngine.searchAll(enabledSources, kw)
                            .filter { it.second.isNotEmpty() }
                            .map { (s, books) -> SourceGroup(s.key, s.name, books) }
                    }
                    val pyJob = async {
                        NovelSourceEngine.searchAllPy(context, enabledPyRules, kw)
                            .filter { it.second.isNotEmpty() }
                            .map { (r, books) ->
                                SourceGroup(
                                    PyBookSourceManager.SOURCE_KEY_PREFIX + r.name,
                                    "${r.title}（本地PY）",
                                    books,
                                )
                            }
                    }
                    jsonJob.await() + pyJob.await()
                }
            }
            loading = false
        }
    }

    fun joinShelf(group: SourceGroup, item: NovelSearchResult) {
        scope.launch {
            joining = group.key + item.remoteId
            val ok = runCatching {
                val metas = if (group.key.startsWith(PyBookSourceManager.SOURCE_KEY_PREFIX)) {
                    NovelSourceEngine.chaptersPy(context, item)
                } else {
                    val source = enabledSources.firstOrNull { it.key == group.key }
                        ?: return@runCatching false
                    NovelSourceEngine.chapters(source, item)
                }
                if (metas.isEmpty()) return@runCatching false
                val novel = Novel(
                    id = "bs_${group.key.hashCode()}_${item.remoteId.hashCode()}",
                    sourceKey = group.key,
                    title = item.title,
                    author = item.author,
                    cover = item.cover,
                    addedAt = System.currentTimeMillis(),
                )
                // 只存目录（带章节 URL），正文阅读时按需拉取并缓存
                val chapters = metas.map {
                    Chapter(
                        novelId = novel.id,
                        index = it.index,
                        title = it.title,
                        content = "",
                        url = it.url,
                    )
                }
                NovelStore.addNovel(context, novel, chapters)
            }.getOrDefault(false)
            joining = null
            Toast.makeText(context, if (ok) "已加入书架" else "加入失败（目录拉取失败）", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (!embedded) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = gold)
            }
            Text(
                // v0.66.0：锁定单源时标题显示源名
                if (lockedPyRule != null) lockedPyRule.title else "找书",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = gold,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            // v0.66.0：锁定单源时不显示书源管理入口
            if (lockedPyRule == null) {
                TextButton(onClick = onManageSources) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Source, "书源管理", tint = gold, modifier = Modifier.padding(end = 4.dp))
                        Text("书源($totalEnabled)", color = gold, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text("书名 / 作者") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { doSearch() }, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.Filled.Search, "搜索", tint = gold)
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("正在搜索 $totalEnabled 个书源…", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            searched && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没找到，换个关键词或检查书源规则", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            !searched -> {
                // v0.67.0：搜索历史（一点即搜，X 单删）
                if (history.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (totalEnabled == 0) "还没有启用的书源，点右上角去添加"
                            else "输入书名或作者开始搜索",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item(key = "history_header") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "搜索历史",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = gold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    SearchHistoryStore.clear(context, "novel")
                                    refreshHistory()
                                }) { Text("清空", fontSize = 13.sp) }
                            }
                        }
                        items(history, key = { "h_$it" }) { h ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        keyword = h
                                        doSearch()
                                    }
                                    .padding(vertical = 6.dp),
                            ) {
                                Icon(
                                    Icons.Filled.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    h,
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                IconButton(onClick = {
                                    SearchHistoryStore.remove(context, h, "novel")
                                    refreshHistory()
                                }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                results.forEach { group ->
                    item(key = "header_${group.key}") {
                        Text(
                            "来自 ${group.name}（${group.books.size} 本）",
                            fontSize = 13.sp,
                            color = gold,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(group.books, key = { "${group.key}_${it.remoteId}" }) { item ->
                        AppCard {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(
                                        item.author ?: "佚名",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                val busy = joining == group.key + item.remoteId
                                Button(onClick = { joinShelf(group, item) }, enabled = !busy) {
                                    Text(if (busy) "加入中" else "加入书架")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** v0.64.0：找书结果分组（JSON 书源 / 本地 PY 书源统一展示）。 */
private data class SourceGroup(
    val key: String,
    val name: String,
    val books: List<NovelSearchResult>,
)
