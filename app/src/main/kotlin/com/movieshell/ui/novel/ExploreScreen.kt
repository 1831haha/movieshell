package com.movieshell.ui.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.movieshell.data.novel.BookSource
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelSearchResult
import com.movieshell.data.novel.NovelSourceEngine
import kotlinx.coroutines.launch

/**
 * v0.201.0：发现页（书架子结构内入口：书源管理行末「发现」）。
 * 顶部是分类横滑条，点分类抓第一页，底部「加载更多」追加下一页；
 * 点书名走入架流程（复用 NovelSearchViewModel.joinShelf）。
 * 分类来自源的 exploreUrl（数组或对象形），空地址分组标题行跳过。
 */
@Composable
fun ExploreScreen(
    sourceKey: String,
    onBack: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val searchVm: NovelSearchViewModel = viewModel()
    val joining by searchVm.joining.collectAsState()
    val sources by BookSourceManager.sources.collectAsState()
    val source = sources.firstOrNull { it.key == sourceKey }

    val categories = remember(source) {
        source?.let { NovelSourceEngine.parseExploreCategories(it.exploreUrl) }.orEmpty()
    }
    var selected by remember(sourceKey) { mutableStateOf<NovelSourceEngine.ExploreCategory?>(null) }
    var results by remember(sourceKey) { mutableStateOf<List<NovelSearchResult>>(emptyList()) }
    var page by remember(sourceKey) { mutableStateOf(1) }
    var loading by remember(sourceKey) { mutableStateOf(false) }
    var notice by remember(sourceKey) { mutableStateOf<String?>(null) }

    fun load(src: BookSource, cat: NovelSourceEngine.ExploreCategory, p: Int, append: Boolean) {
        if (loading) return
        loading = true
        notice = null
        scope.launch {
            val list = runCatching { NovelSourceEngine.explore(src, cat.url, p, allowGate = true) }.getOrDefault(emptyList())
            results = if (append) (results + list).distinctBy { it.remoteId } else list
            if (list.isEmpty() && !append) notice = "这一页没有结果（可能撞上验证，稍后重试）"
            loading = false
        }
    }

    LaunchedEffect(categories.firstOrNull()) {
        val first = categories.firstOrNull() ?: return@LaunchedEffect
        if (selected == null) {
            selected = first
            page = 1
            source?.let { load(it, first, 1, false) }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
            Text(
                "发现 · ${source?.name.orEmpty()}",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        when {
            source == null -> Text("书源不存在或已删除。", modifier = Modifier.padding(top = 16.dp))
            categories.isEmpty() -> Text("该源没有发现分类。", modifier = Modifier.padding(top = 16.dp))
            else -> {
                LazyRow {
                    items(categories, key = { it.title }) { cat ->
                        val isSel = selected?.title == cat.title
                        FilterChip(
                            selected = isSel,
                            onClick = {
                                val src = source ?: return@FilterChip
                                selected = cat
                                page = 1
                                load(src, cat, 1, false)
                            },
                            label = { Text(cat.title) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val group = source?.let { SourceGroup(it.key, it.name, results) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.remoteId }) { item ->
                        val isJoining = group != null && joining == group.key + item.remoteId
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !isJoining) {
                                    if (group != null) searchVm.joinShelf(group, item)
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(item.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!item.author.isNullOrBlank()) {
                                    Text(
                                        item.author,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (isJoining) {
                                    Text("加入中…", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    if (notice != null) {
                        item { Text(notice.orEmpty(), fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                    }
                    if (results.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                if (loading) {
                                    Text("加载中…", fontSize = 13.sp)
                                } else {
                                    Button(onClick = {
                                        val src = source ?: return@Button
                                        val cat = selected ?: return@Button
                                        val next = page + 1
                                        page = next
                                        load(src, cat, next, true)
                                    }) { Text("加载更多") }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = {
                                        val src = source ?: return@TextButton
                                        val cat = selected ?: return@TextButton
                                        page = 1
                                        load(src, cat, 1, false)
                                    }) { Text("刷新") }
                                }
                            }
                        }
                    } else if (loading) {
                        item { Text("加载中…", fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                    }
                }
            }
        }
    }
}
