package com.movieshell.ui.openlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.core.net.OpenListClient
import com.movieshell.data.source.TmdbClient
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.PosterCard
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.V5Segmented
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * OpenList 网盘浏览页（v0.294.55：手机本机服务插件配套）。
 *
 * 入口只在插件中心服务页的 OpenList 卡片上（运行中才给“网盘”按钮），
 * 地址固定为本机服务 http://127.0.0.1:5244，不另开远程服务器配置。
 * 第一版只做三件事：文件夹下钻/返回、只列文件夹和视频文件、点视频拿直链进已有播放页。
 * 不做：下载、离线缓存、跨盘搜索、同文件夹连续选集（播放页目前只接单地址）。
 * 目录密码、加密目录不在第一版内：OpenList 那边给目录设了密码的，本页会直接报接口错误。
 */
@Composable
fun OpenListBrowseScreen(
    onBack: () -> Unit,
    onPlay: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val baseUrl = OpenListClient.LOCAL_BASE_URL

    var pathStack by remember { mutableStateOf(listOf("/")) }
    val currentPath = pathStack.last()
    var token by remember { mutableStateOf(OpenListClient.loadToken(context)) }
    var entries by remember { mutableStateOf<List<OpenListClient.OlEntry>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var needLogin by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var loggingIn by remember { mutableStateOf(false) }
    var gettingUrl by remember { mutableStateOf(false) }
    // v1.2.13：TMDB 海报墙——列表/海报墙切换（默认列表，记住选择）、当前目录视频的匹配结果。
    var posterView by remember { mutableStateOf(TmdbClient.isPosterView(context)) }
    var matches by remember { mutableStateOf<Map<String, TmdbClient.TmdbMatch>>(emptyMap()) }
    val tmdbKey = TmdbClient.apiKey(context)

    fun goBack() {
        if (pathStack.size > 1) {
            pathStack = pathStack.dropLast(1)
        } else {
            onBack()
        }
    }

    suspend fun loadPage(p: Int, append: Boolean) {
        if (loading) return
        loading = true
        error = ""
        try {
            val res = OpenListClient.listDir(baseUrl, token, currentPath, p)
            res.onSuccess { pageData ->
                needLogin = false
                total = pageData.total
                entries = if (append) entries + pageData.content else pageData.content
            }.onFailure { e ->
                val msg = e.message.orEmpty()
                if (msg.contains("code=401")) {
                    needLogin = true
                    error = "这个 OpenList 需要登录后才能看"
                } else {
                    error = msg.ifBlank { "加载失败" }
                }
            }
        } finally {
            loading = false
        }
    }

    // 列表与海报墙共用的播放入口：取直链后走同一个 onPlay。
    fun playFile(entry: OpenListClient.OlEntry) {
        if (gettingUrl) return
        scope.launch {
            gettingUrl = true
            val res = OpenListClient.getRawUrl(baseUrl, token, entry.path)
            gettingUrl = false
            res.onSuccess { url ->
                onPlay(url, entry.name)
            }.onFailure { e ->
                val msg = e.message.orEmpty()
                if (msg.contains("code=401")) {
                    needLogin = true
                    error = "这个 OpenList 需要登录后才能看"
                } else {
                    error = msg.ifBlank { "拿不到播放地址" }
                    AppToast.show(context, error, long = true)
                }
            }
        }
    }

    LaunchedEffect(currentPath, token) {
        page = 1
        entries = emptyList()
        total = 0
        loadPage(1, append = false)
    }

    // v1.2.13：当前目录视频的 TMDB 匹配——先读本地缓存立即显示，没命中的再联网；
    // 同一目录并发最多 4 个请求；key 为空时不联网，只显示占位；文件名解析不出的不请求。
    LaunchedEffect(currentPath, entries) {
        if (tmdbKey.isBlank()) {
            matches = emptyMap()
            return@LaunchedEffect
        }
        val videos = entries.filter { !it.isDir && OpenListClient.isVideoFile(it.name) }
        val cache = TmdbClient.loadCache(context)
        val found = mutableMapOf<String, TmdbClient.TmdbMatch>()
        val missing = mutableListOf<OpenListClient.OlEntry>()
        videos.forEach { entry ->
            val hit = cache[entry.name]
            if (hit != null) found[entry.name] = hit else missing.add(entry)
        }
        matches = found.toMap()
        if (missing.isEmpty()) return@LaunchedEffect
        val semaphore = Semaphore(4)
        val fetched = missing.map { entry ->
            async {
                semaphore.withPermit {
                    val parsed = TmdbClient.parseFileName(entry.name) ?: return@async null
                    val match = TmdbClient.searchMatch(parsed.title, parsed.year, tmdbKey)
                        ?: TmdbClient.TmdbMatch(
                            posterPath = "",
                            title = parsed.title,
                            year = parsed.year,
                            cachedAt = System.currentTimeMillis(),
                            found = false,
                        )
                    entry.name to match
                }
            }
        }.awaitAll().filterNotNull().toMap()
        if (fetched.isNotEmpty()) {
            TmdbClient.putCache(context, fetched)
            matches = found + fetched
        }
    }

    BackHandler { goBack() }

    Scaffold(
        topBar = {
            TabTopBar(
                title = "网盘 · OpenList",
                onBack = { goBack() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "当前目录：$currentPath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // v1.2.13：列表/海报墙分段切换（选中 secondaryContainer 底色一眼可见，
                // 整段 48dp 触摸目标，图标+文字居中对齐；选择即记住偏好）。
                V5Segmented(
                    options = listOf("列表", "海报墙"),
                    icons = listOf(Icons.Filled.List, Icons.Filled.GridView),
                    selected = if (posterView) 1 else 0,
                    onSelect = { i ->
                        val next = i == 1
                        posterView = next
                        TmdbClient.setPosterView(context, next)
                    },
                    // 段内用 weight 等分，必须给定宽（跟直播源分段每格定宽同款写法），
                    // 否则 Row 父容器里会撑满剩余宽度、把“当前目录”标题压没。
                    modifier = Modifier.width(160.dp),
                    height = 48.dp,
                )
                if (pathStack.size > 1) {
                    TextButton(onClick = { goBack() }) { Text("上级") }
                }
            }
            if (needLogin) {
                Text(
                    text = "管理员账号为 admin，输入 OpenList 管理员密码登录一次即可浏览。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("管理员密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(
                        enabled = !loggingIn && password.isNotBlank(),
                        onClick = {
                            scope.launch {
                                loggingIn = true
                                val res = OpenListClient.login(baseUrl, "admin", password)
                                loggingIn = false
                                res.onSuccess { newToken ->
                                    OpenListClient.saveToken(context, newToken)
                                    password = ""
                                    token = newToken
                                }.onFailure { e ->
                                    error = e.message.orEmpty().ifBlank { "登录失败" }
                                }
                            }
                        },
                    ) { Text(if (loggingIn) "登录中…" else "登录") }
                    TextButton(
                        onClick = {
                            needLogin = false
                            OpenListClient.clearToken(context)
                            token = ""
                        },
                    ) { Text("清除凭证") }
                }
                Text(
                    text = "密码只用来换一次登录凭证，存在本 App 私有存储里，和现有 WebDAV 口径相同。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (error.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (loading && entries.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "加载中…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            val shown = entries.sortedWith(
                compareBy<OpenListClient.OlEntry> { !it.isDir }
                    .thenBy { it.name.lowercase() },
            )
            if (posterView) {
                OpenListPosterGrid(
                    shown = shown,
                    matches = matches,
                    loading = loading,
                    error = error,
                    needLogin = needLogin,
                    total = total,
                    shownCount = entries.size,
                    tmdbKeyBlank = tmdbKey.isBlank(),
                    onDirClick = { pathStack = pathStack + it },
                    onPlayFile = { playFile(it) },
                    onLoadMore = {
                        scope.launch {
                            val next = page + 1
                            page = next
                            loadPage(next, append = true)
                        }
                    },
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.path }) { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    if (entry.isDir) {
                                        pathStack = pathStack + entry.path
                                    } else {
                                        playFile(entry)
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (entry.isDir) {
                                        "文件夹"
                                    } else {
                                        "视频 · ${OpenListClient.formatSize(entry.size)}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = if (entry.isDir) "进入" else "播放",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (!loading && entries.isEmpty() && error.isBlank() && !needLogin) {
                        item {
                            Text(
                                text = "这个目录是空的，或里面没有文件夹和视频文件。",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                    }
                    if (total > 0 && entries.size < total) {
                        item {
                            TextButton(
                                enabled = !loading,
                                onClick = {
                                    scope.launch {
                                        val next = page + 1
                                        page = next
                                        loadPage(next, append = true)
                                    }
                                },
                            ) {
                                Text("加载更多（已显示 ${entries.size} / 共 $total）")
                            }
                        }
                    }
                    item {
                        Text(
                            text = "只显示文件夹和视频文件；先在插件中心服务页启动 OpenList，首次管理员密码在服务日志里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * v1.2.13：OpenList 海报墙（3 列）。
 *
 * 文件夹仍占整行点进下钻；视频按海报卡片排，点卡片走与列表完全相同的播放入口。
 * 没匹配到的（搜不到、key 为空、文件名解析不出）用占位块加文件名，不报错。
 */
@Composable
private fun OpenListPosterGrid(
    shown: List<OpenListClient.OlEntry>,
    matches: Map<String, TmdbClient.TmdbMatch>,
    loading: Boolean,
    error: String,
    needLogin: Boolean,
    total: Int,
    shownCount: Int,
    tmdbKeyBlank: Boolean,
    onDirClick: (String) -> Unit,
    onPlayFile: (OpenListClient.OlEntry) -> Unit,
    onLoadMore: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // v1.2.13：没填 key 时海报墙只显示占位，这里提示去设置里填，不报错、不发请求。
        if (tmdbKeyBlank) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "未填 TMDB Key，海报暂不可用——去设置 → 播放设置 → TMDB Key 填写自己的 Key。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        val dirs = shown.filter { it.isDir }
        val videos = shown.filter { !it.isDir }
        dirs.forEach { dir ->
            item(span = { GridItemSpan(maxLineSpan) }, key = dir.path) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onDirClick(dir.path) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = dir.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "文件夹",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "进入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        videos.forEach { entry ->
            item(key = entry.path) {
                val match = matches[entry.name]
                // 占位态各有说法：没填 key（角标“未填 Key”，顶部另有去设置的提示）、
                // 搜到但没海报（“无海报”）、搜过没结果（“未找到”）；还在请求中则无角标、
                // 纯占位（首字+主题色），不闪白。
                val badge = when {
                    match == null && tmdbKeyBlank -> "未填 Key"
                    match != null && match.posterPath.isBlank() && match.found -> "无海报"
                    match != null && match.posterPath.isBlank() && !match.found -> "未找到"
                    else -> null
                }
                PosterCard(
                    title = match?.title?.takeIf { it.isNotBlank() } ?: entry.name,
                    subtitle = match?.year?.toString(),
                    badge = badge,
                    posterUrl = match?.posterPath?.takeIf { it.isNotBlank() }?.let { TmdbClient.posterUrl(it) },
                    posterAspectRatio = 2f / 3f,
                    onClick = { onPlayFile(entry) },
                )
            }
        }
        if (!loading && shown.isEmpty() && error.isBlank() && !needLogin) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "这个目录是空的，或里面没有文件夹和视频文件。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        if (total > 0 && shownCount < total) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(
                    enabled = !loading,
                    onClick = onLoadMore,
                ) {
                    Text("加载更多（已显示 $shownCount / 共 $total）")
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "只显示文件夹和视频文件；先在插件中心服务页启动 OpenList，首次管理员密码在服务日志里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}
