package com.movieshell.ui.novel

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.movieshell.ui.components.TechDialog
import com.movieshell.data.novel.BookSource
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.NovelSearchResult
import com.movieshell.data.novel.NovelSourceEngine
import kotlinx.coroutines.launch

/**
 * v0.201.0：发现页（书架子结构内入口：书源管理行末「发现」）。
 * v0.210.0：默认按原网页直渲——顶部仍是分类横滑条，下方直接加载分类
 * 对应的原网页（封面、版式都和网站一致）；点进书的详情页后，底部
 * 「加入书架」走现有入架流程（标题取当前页标题，去掉站名后缀）。
 * 原先的规则解析文字列表留作切换入口（右上角「列表」），原网页不
 * 适合看的源还能切回去；切回列表时才抓第一页，不浪费一次请求。
 * 返回键先退 WebView 历史，退到底才回上一页。
 */
@Composable
fun ExploreScreen(
    sourceKey: String,
    // v0.248.0：发现页胶囊点进来时预选该分类；空则沿用原来默认首分类。
    initialCategory: String? = null,
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
    // v0.246.0：加载代际——切分类时旧请求结果不许覆盖新分类
    var loadGen by remember(sourceKey) { mutableStateOf(0) }
    var notice by remember(sourceKey) { mutableStateOf<String?>(null) }
    var webMode by remember(sourceKey) { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageUrl by remember(sourceKey) { mutableStateOf("") }
    var pageTitle by remember(sourceKey) { mutableStateOf("") }
    var webLoading by remember(sourceKey) { mutableStateOf(false) }
    // v0.215.0：列表行详情预览——点行先看预览（封面／书名／作者／来源），确认才入架。
    var previewItem by remember(sourceKey) { mutableStateOf<NovelSearchResult?>(null) }

    val categoryUrl = source?.let { src ->
        selected?.let { NovelSourceEngine.explorePageUrl(src, it.url) }
    }.orEmpty()

    fun load(src: BookSource, cat: NovelSourceEngine.ExploreCategory, p: Int, append: Boolean) {
        // v0.246.0：代际保护——原来 loading 时新请求直接 return，
        // 切分类后旧请求完成仍把旧分类结果写进 results（selected 已是新分类）。
        // 改每次 load 递增代际，写回时只认最新一代；旧请求结果直接丢弃。
        val myGen = ++loadGen
        loading = true
        notice = null
        scope.launch {
            val list = runCatching { NovelSourceEngine.explore(src, cat.url, p, allowGate = true) }.getOrDefault(emptyList())
            if (myGen != loadGen) return@launch
            results = if (append) (results + list).distinctBy { it.remoteId } else list
            if (list.isEmpty() && !append) notice = "这一页没有结果（可能撞上验证，稍后重试）"
            loading = false
        }
    }

    // v0.248.0：effect key 带上 initialCategory——同页内初始分类变化时也能重匹配；
    // 已选过分类时守卫（selected != null）拦住，不会覆盖用户点的分类。
    LaunchedEffect(categories.firstOrNull(), initialCategory) {
        val first = categories.firstOrNull() ?: return@LaunchedEffect
        if (selected == null) {
            selected = categories.firstOrNull { it.title == initialCategory } ?: first
            page = 1
        }
    }

    LaunchedEffect(selected, webMode) {
        if (webMode) {
            pageUrl = categoryUrl
            pageTitle = ""
            webLoading = categoryUrl.isNotBlank()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (webMode && webView?.canGoBack() == true) {
                    webView?.goBack()
                } else {
                    onBack()
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
            Text(
                "发现 · ${source?.name.orEmpty()}",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val toList = webMode
                webMode = !webMode
                if (toList) {
                    val src = source ?: return@TextButton
                    val cat = selected ?: return@TextButton
                    page = 1
                    if (results.isEmpty()) load(src, cat, 1, false)
                }
            }) { Text(if (webMode) "列表" else "原网页") }
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
                                if (!webMode) load(src, cat, 1, false)
                            },
                            label = { Text(cat.title) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val group = source?.let { SourceGroup(it.key, it.name, results) }
                if (webMode) {
                    if (webLoading) {
                        Text("加载中…", fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            pageTitle,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        val canJoin = pageUrl.isNotBlank() &&
                            pageUrl.trimEnd('/') != categoryUrl.trimEnd('/')
                        val isJoining = group != null && (group.key + pageUrl) in joining
                        Button(
                            enabled = canJoin && !isJoining,
                            onClick = {
                                val g = group ?: return@Button
                                searchVm.joinShelf(
                                    g,
                                    NovelSearchResult(
                                        sourceKey = g.key,
                                        remoteId = pageUrl,
                                        title = cleanPageTitle(pageTitle),
                                        author = null,
                                        cover = null,
                                    ),
                                )
                            },
                        ) { Text(if (isJoining) "加入中…" else "加入书架") }
                    }
                    Spacer(Modifier.height(8.dp))
                    key(categoryUrl) {
                        AndroidView(
                            modifier = Modifier.weight(1f),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    source?.headerUa?.ifBlank { null }?.let { settings.userAgentString = it }
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView, url: String) {
                                            pageUrl = url
                                            pageTitle = view.title.orEmpty()
                                            webLoading = false
                                        }
                                    }
                                    loadUrl(categoryUrl)
                                    webView = this
                                }
                            },
                            onRelease = {
                                it.destroy()
                                if (webView == it) webView = null
                            },
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(results, key = { it.remoteId }) { item ->
                            val isJoining = group != null && (group.key + item.remoteId) in joining
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = !isJoining) {
                                        previewItem = item
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

    // v0.215.0：列表行详情预览弹窗（确认才入架；取消不丢列表）。
    previewItem?.let { item ->
        val group = source?.let { SourceGroup(it.key, it.name, results) }
        val isJoining = group != null && (group.key + item.remoteId) in joining
        TechDialog(
            onDismissRequest = { if (!isJoining) previewItem = null },
            title = item.title,
            subtitle = listOfNotNull(
                item.author?.takeIf { it.isNotBlank() },
                source?.name,
            ).joinToString(" · ").takeIf { it.isNotBlank() },
            confirmText = if (isJoining) "加入中…" else "加入书架",
            confirmEnabled = !isJoining,
            onConfirm = {
                if (group != null) searchVm.joinShelf(group, item)
                previewItem = null
            },
        ) {
            if (!item.cover.isNullOrBlank()) {
                AsyncImage(
                    model = item.cover,
                    contentDescription = item.title,
                    modifier = Modifier
                        .size(width = 110.dp, height = 148.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                "加入书架后，书籍会立即保存到书架，目录将在后台获取。若获取失败，可在书架中重试；来源：${source?.name.orEmpty()}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 详情页标题常带站名后缀（「书名 - 站名」），入架只取书名一段。 */
private fun cleanPageTitle(raw: String): String {
    var t = raw.trim()
    for (sep in listOf(" - ", " | ")) {
        val i = t.indexOf(sep)
        if (i > 0) t = t.substring(0, i).trim()
    }
    return t
}
