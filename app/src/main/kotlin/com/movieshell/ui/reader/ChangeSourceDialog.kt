package com.movieshell.ui.reader

import androidx.compose.foundation.clickable
import com.movieshell.ui.components.AppToast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.data.model.Chapter
import com.movieshell.data.model.Novel
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelSourceEngine
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.repo.NovelStore
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.StatusRing
import com.movieshell.ui.components.StatusRingState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * v0.83.0：换源弹窗——用书名在所有启用书源重搜，选一条替换当前书的目录。
 *
 * 进度尽量保留：按当前章节标题在新目录里找同名章节，找不到就按原下标兜底。
 * onSwitched(newChapterIndex)：切源成功后阅读器跳到该章节并重载。
 */
@Composable
fun ChangeSourceDialog(
    novel: Novel,
    currentChapterTitle: String,
    currentChapterIndex: Int,
    onDismiss: () -> Unit,
    onSwitched: (Int) -> Unit,
    /** v0.245.0：阅读页传阅读背景深浅，其他调用方不传、跟系统主题。 */
    forceDark: Boolean? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var keyword by remember { mutableStateOf(novel.title) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<ChangeSourceGroup>>(emptyList()) }
    var switching by remember { mutableStateOf<String?>(null) }

    fun doSearch() {
        val kw = keyword.trim()
        if (kw.isBlank() || searching) return
        keyboard?.hide()
        searching = true
        searched = true
        scope.launch {
            // v0.217.0（F38）：读管理器实时值，不读组合快照——载入完成到组合
            // 重组有延迟，快照里还是空表，搜出来永远是空。
            val enabledSources = BookSourceManager.sources.value.filter { it.enabled }
            val enabledPy = PyBookSourceManager.rules.value.filter {
                it.name in PyBookSourceManager.enabled.value
            }
            val result = coroutineScope {
                val jsonJob = async {
                    NovelSourceEngine.searchAll(enabledSources, kw, allowGate = true)
                        .filter { it.second.isNotEmpty() }
                        .map { (s, books) -> ChangeSourceGroup(s.key, s.name, books) }
                }
                val pyJob = async {
                    NovelSourceEngine.searchAllPy(context, enabledPy, kw)
                        .filter { it.second.isNotEmpty() }
                        .map { (r, books) ->
                            ChangeSourceGroup(
                                PyBookSourceManager.SOURCE_KEY_PREFIX + r.name,
                                "${r.title}（本地PY）",
                                books,
                            )
                        }
                }
                jsonJob.await() + pyJob.await()
            }
            // 当前源排最前
            groups = result.sortedByDescending { it.key == novel.sourceKey }
            searching = false
        }
    }

    fun switchTo(group: ChangeSourceGroup, item: com.movieshell.data.novel.NovelSearchResult) {
        if (switching != null) return
        switching = group.key + item.remoteId
        scope.launch {
            val ok = runCatching {
                val metas = if (group.key.startsWith(PyBookSourceManager.SOURCE_KEY_PREFIX)) {
                    NovelSourceEngine.chaptersPy(context, item)
                } else {
                    val source = BookSourceManager.sources.value.firstOrNull { it.key == group.key }
                        ?: return@runCatching -1
                    NovelSourceEngine.chapters(source, item)
                }
                if (metas.isEmpty()) return@runCatching -1
                val chapters = metas.map {
                    Chapter(novelId = novel.id, index = it.index, title = it.title, url = it.url)
                }
                val updated = NovelStore.updateSource(context, novel.id, group.key, chapters)
                if (!updated) return@runCatching -1
                // 章节对齐：同名优先，其次原下标兜底
                val titles = chapters.map { it.title }
                val byName = titles.indexOfFirst { it == currentChapterTitle }
                    .takeIf { it >= 0 }
                    ?: titles.indexOfFirst {
                        it.contains(currentChapterTitle) || currentChapterTitle.contains(it)
                    }.takeIf { it >= 0 }
                    ?: currentChapterIndex.coerceIn(chapters.indices)
                byName
            }.getOrDefault(-1)
            switching = null
            if (ok >= 0) {
                AppToast.show(context, "已换源", long = false)
                onSwitched(ok)
            } else {
                AppToast.show(context, "换源失败（目录拉取失败）", long = false)
            }
        }
    }

    // v0.217.0（F38）：载入与首搜串成一个协程——原来两个 LaunchedEffect(Unit)
    // 并发跑，搜索读的是载入前的空快照，第一次必定搜空；现在先 load 再搜。
    LaunchedEffect(Unit) {
        BookSourceManager.load(context)
        PyBookSourceManager.load(context)
        doSearch()
    }

    TechDialog(
        onDismissRequest = onDismiss,
        title = "换源",
        forceDark = forceDark,
    ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        modifier = Modifier.weight(1f),
                        placeholder = "书名",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                    )
                    IconButton(onClick = { doSearch() }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                    when {
                        searching -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            // 整区加载圈统一为 StatusRing（跑马灯圆环 + 黄色状态点）
                            StatusRing(state = StatusRingState.Loading, diameter = 40.dp)
                        }
                        !searched || groups.isEmpty() -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (searched) "没搜到，换个关键词" else "",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            groups.forEach { group ->
                                item(key = "h_${group.key}") {
                                    Text(
                                        text = (if (group.key == novel.sourceKey) "● " else "") +
                                            group.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (group.key == novel.sourceKey) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                items(group.books.distinctBy { it.remoteId }, key = { "b_${group.key}_${it.remoteId}" }) { book ->
                                    val key = group.key + book.remoteId
                                    AppCard(
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable { switchTo(group, book) },
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = book.title,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                if (!book.author.isNullOrBlank()) {
                                                    Text(
                                                        text = book.author!!,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                            if (switching == key) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp,
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
}

private data class ChangeSourceGroup(
    val key: String,
    val name: String,
    val books: List<com.movieshell.data.novel.NovelSearchResult>,
)
