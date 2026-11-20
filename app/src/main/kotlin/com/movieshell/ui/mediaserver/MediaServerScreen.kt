package com.movieshell.ui.mediaserver

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.source.EmbyApi
import com.movieshell.data.store.EmbyServer
import com.movieshell.data.store.EmbyStore
import com.movieshell.player.HistoryStore
import com.movieshell.player.PlaySession
import com.movieshell.ui.components.AppSwitch
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.theme.StaticGlowBackground
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.TechTextField
import kotlinx.coroutines.launch

/**
 * v1.1.31：Emby / Jellyfin 媒体服务器。
 *
 * 入口在设置 → 播放 · 解析 → 媒体服务器。只做：加服务器、浏览媒体库、搜索、继续观看、点播。
 * 播放走已有播放页（Static=true 原画直链），断点走本机 HistoryStore 预置（播前按服务器的
 * PlaybackPositionTicks 写一条进度，播放页自动从该处续播）。
 * 不做：转码、进度上报服务器、音乐/图片库。
 */
@Composable
fun MediaServerScreen(
    onBack: () -> Unit,
    onPlay: (url: String, title: String, ignoreCert: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { EmbyStore.ensureLoaded(context) }
    val servers by EmbyStore.servers.collectAsState()
    val active = servers.firstOrNull { it.id == EmbyStore.activeId(context) } ?: servers.firstOrNull()

    var stack by remember { mutableStateOf<List<Level>>(listOf(Level.Root)) }
    var views by remember { mutableStateOf<List<EmbyApi.EmbyItem>>(emptyList()) }
    var resumeItems by remember { mutableStateOf<List<EmbyApi.EmbyItem>>(emptyList()) }
    var items by remember { mutableStateOf<List<EmbyApi.EmbyItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var authExpired by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EmbyServer?>(null) }
    var showServerMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var formName by remember { mutableStateOf("") }
    var formType by remember { mutableStateOf("emby") }
    // v1.3.6：单地址框——整段粘贴什么都认（带协议/裸域名/带端口/带路径），
    // 协议与端口路径由 EmbyApi.parseAddress 在后台拆，没写协议先 https 后 http 试。
    var formAddress by remember { mutableStateOf("") }
    var formIgnoreCert by remember { mutableStateOf(false) }
    var formUser by remember { mutableStateOf("") }
    var formPass by remember { mutableStateOf("") }
    var formPassVisible by remember { mutableStateOf(false) }
    var formApiKey by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf("") }
    var formTesting by remember { mutableStateOf(false) }
    // v1.3.6：测试结果弹窗（成功/失败都弹，调用处受 if (showResult) 守卫）。
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultTitle by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf("") }

    val level = stack.last()
    val title = when (level) {
        Level.Root -> "媒体服务器"
        is Level.Folder -> level.name
        is Level.Series -> level.name
        is Level.Season -> level.name
    }

    fun typeOf(server: EmbyServer) =
        if (server.type == "jellyfin") EmbyApi.ServerType.JELLYFIN else EmbyApi.ServerType.EMBY

    fun authOf(server: EmbyServer) = EmbyApi.Auth(server.token, server.userId)

    fun loadRoot(server: EmbyServer) {
        scope.launch {
            loading = true
            error = ""
            authExpired = false
            val type = typeOf(server)
            val deviceId = EmbyStore.deviceId(context)
            val auth = authOf(server)
            if (!EmbyApi.testAuth(server.baseUrl, type, deviceId, auth, server.ignoreCert)) {
                authExpired = true
                loading = false
                return@launch
            }
            views = EmbyApi.views(server.baseUrl, type, deviceId, auth, server.ignoreCert)
                .filter { it.CollectionType == "movies" || it.CollectionType == "tvshows" || it.CollectionType.isNullOrBlank() }
            resumeItems = EmbyApi.items(
                server.baseUrl, type, deviceId, auth,
                types = "Movie,Episode", resuming = true, limit = 50,
                ignoreCert = server.ignoreCert,
            )
            loading = false
        }
    }

    fun loadFolder(server: EmbyServer, viewId: String) {
        scope.launch {
            loading = true
            error = ""
            items = EmbyApi.items(
                server.baseUrl, typeOf(server), EmbyStore.deviceId(context), authOf(server),
                parentId = viewId, ignoreCert = server.ignoreCert,
            )
            loading = false
        }
    }

    fun loadSeries(server: EmbyServer, seriesId: String) {
        scope.launch {
            loading = true
            error = ""
            items = EmbyApi.seasons(
                server.baseUrl, typeOf(server), EmbyStore.deviceId(context), authOf(server), seriesId,
                server.ignoreCert,
            )
            loading = false
        }
    }

    fun loadSeason(server: EmbyServer, seriesId: String, seasonId: String) {
        scope.launch {
            loading = true
            error = ""
            items = EmbyApi.episodes(
                server.baseUrl, typeOf(server), EmbyStore.deviceId(context), authOf(server), seriesId, seasonId,
                server.ignoreCert,
            )
            loading = false
        }
    }

    fun doSearch(server: EmbyServer, q: String) {
        if (q.isBlank()) return
        scope.launch {
            searching = true
            loading = true
            error = ""
            items = EmbyApi.items(
                server.baseUrl, typeOf(server), EmbyStore.deviceId(context), authOf(server),
                search = q.trim(), ignoreCert = server.ignoreCert,
            )
            loading = false
        }
    }

    fun goBack() {
        if (searching) {
            searching = false
            query = ""
        } else if (stack.size > 1) {
            stack = stack.dropLast(1)
        } else {
            onBack()
        }
    }

    BackHandler { goBack() }

    LaunchedEffect(active?.id) {
        stack = listOf(Level.Root)
        searching = false
        query = ""
        views = emptyList()
        resumeItems = emptyList()
        items = emptyList()
        val server = active ?: return@LaunchedEffect
        loadRoot(server)
    }

    fun playItem(server: EmbyServer, item: EmbyApi.EmbyItem) {
        val auth = authOf(server)
        val url = EmbyApi.streamUrl(server.baseUrl, typeOf(server), auth.token, item.Id)
        if (url.isBlank()) {
            AppToast.show(context, "播放地址生成失败", long = false)
            return
        }
        val display = displayTitle(item)
        val resumeMs = EmbyApi.ticksToMs(item.UserData?.PlaybackPositionTicks ?: 0L)
        val durMs = EmbyApi.ticksToMs(item.RunTimeTicks)
        if (resumeMs > 10_000L) {
            HistoryStore.saveProgress(
                context, display, url, resumeMs, durMs,
                kind = "vod", vodId = "emby:${server.id}:${item.Id}",
                sourceType = "emby", sourceKey = server.name,
            )
        }
        // v1.2.13：片头片尾跳过——播前取一次详情拿章节（列表接口不带 Chapters，
        // 200 条全带会把列表回包撑大）；取不到就按无章节照常播，不挡播放。
        scope.launch {
            val detail = EmbyApi.item(
                server.baseUrl, typeOf(server), EmbyStore.deviceId(context), auth, item.Id,
                server.ignoreCert,
            )
            PlaySession.setChapters(detail?.Chapters ?: item.Chapters)
            onPlay(url, display, server.ignoreCert)
        }
    }

    fun openItem(server: EmbyServer, item: EmbyApi.EmbyItem) {
        when (item.Type) {
            "Series" -> {
                stack = stack + Level.Series(item.Id, item.Name)
                loadSeries(server, item.Id)
            }
            "Season" -> {
                val current = stack.lastOrNull()
                val seriesId = item.SeriesId.orEmpty()
                val seriesName = (current as? Level.Series)?.name ?: item.SeriesName.orEmpty()
                stack = stack + Level.Season(seriesId, seriesName, item.Id, item.Name)
                loadSeason(server, seriesId, item.Id)
            }
            else -> playItem(server, item)
        }
    }

    fun openAdd() {
        editing = null
        formName = ""
        formType = "emby"
        formAddress = ""
        formIgnoreCert = false
        formUser = ""
        formPass = ""
        formPassVisible = false
        formApiKey = ""
        formError = ""
        formTesting = false
        showResult = false
        showAdd = true
    }

    fun openEdit(server: EmbyServer) {
        editing = server
        formName = server.name
        formType = server.type
        // v1.3.6：旧的整 URL 拼回单个地址框（双协议坏地址先由 normalizeBase 自愈）。
        val split = EmbyApi.splitBaseUrl(server.baseUrl)
        formAddress = EmbyApi.joinAddress(split.scheme, split.host, split.port, split.path)
        formIgnoreCert = server.ignoreCert
        formUser = server.username
        formPass = ""
        formPassVisible = false
        formApiKey = ""
        formError = ""
        formTesting = false
        showResult = false
        showAdd = true
    }

    fun showResultDialog(success: Boolean, title: String, message: String) {
        resultSuccess = success
        resultTitle = title
        resultMessage = message
        showResult = true
    }

    fun dismissResult() {
        showResult = false
        // 成功才关表单：失败留在表单里改地址账号。
        if (resultSuccess) {
            showAdd = false
            editing = null
        }
    }

    fun saveForm() {
        // v1.3.6：单地址框解析出候选地址表，依次试登录；没写协议先 https 后 http。
        val candidates = EmbyApi.candidateBases(formAddress)
        if (candidates.isEmpty()) {
            formError = "请填写服务器地址"
            showResultDialog(false, "连接失败", "请填写服务器地址")
            return
        }
        // v1.2.14：Emby 允许无密码用户——密码为空也走账号登录，不再强制填密码。
        if (formApiKey.isBlank() && formUser.isBlank()) {
            formError = "请填写用户名，或填写 API Key"
            showResultDialog(false, "连接失败", "请填写用户名，或填写 API Key")
            return
        }
        formTesting = true
        formError = ""
        scope.launch {
            val type = if (formType == "jellyfin") EmbyApi.ServerType.JELLYFIN else EmbyApi.ServerType.EMBY
            val deviceId = EmbyStore.deviceId(context)
            var successAuth: EmbyApi.Auth? = null
            var usedApiKey = false
            var usedBase = ""
            var lastFailure: EmbyApi.LoginFailure? = null
            // 候选逐个试：账号错（401/400）说明服务器通了，不用再换候选；
            // 连不上才试下一个候选（https 不通回落 http）。
            for (base in candidates) {
                var outcome: EmbyApi.LoginOutcome? = null
                var triedApiKey = false
                if (formApiKey.isNotBlank()) {
                    triedApiKey = true
                    outcome = EmbyApi.loginByApiKeyResult(base, type, deviceId, formApiKey.trim(), formIgnoreCert)
                    if (outcome is EmbyApi.LoginOutcome.Success) {
                        successAuth = outcome.auth
                        usedApiKey = true
                        usedBase = base
                        break
                    }
                }
                // v1.1.31 补：API Key 失败但账号密码也填了时，改走账号密码再试一次——
                // API Key 框里误留几个字符不该直接判死刑（2026-11-12 真机实证：框里 3 个字符
                // 导致一直走 API Key 路径拿 401，账号密码一次都没试过）。
                // API Key 连不上（不是 401）时同地址的账号登录也连不上，直接换下一个候选。
                if (formUser.isNotBlank()) {
                    val apiFailed = outcome as? EmbyApi.LoginOutcome.Failed
                    if (!triedApiKey || apiFailed == null ||
                        apiFailed.failure == EmbyApi.LoginFailure.Unauthorized
                    ) {
                        outcome = EmbyApi.loginByPasswordResult(
                            base, type, deviceId, formUser.trim(), formPass, formIgnoreCert,
                        )
                        if (outcome is EmbyApi.LoginOutcome.Success) {
                            successAuth = outcome.auth
                            usedApiKey = false
                            usedBase = base
                            break
                        }
                    }
                }
                val failed = outcome as? EmbyApi.LoginOutcome.Failed
                if (failed != null) {
                    lastFailure = failed.failure
                    // 账号错或回的不是 Emby：服务器通了，换候选也没用，直接停。
                    if (failed.failure != EmbyApi.LoginFailure.Unreachable) break
                }
            }
            formTesting = false
            val auth = successAuth
            if (auth == null) {
                val message = when (lastFailure) {
                    EmbyApi.LoginFailure.Unauthorized -> "用户名或密码不对"
                    EmbyApi.LoginFailure.BadResponse -> "该地址不是 Emby/Jellyfin 服务器"
                    else -> "检查网络或地址是否写对"
                }
                formError = "登录失败：$message"
                showResultDialog(false, "连接失败", message)
                return@launch
            }
            val base = (editing ?: EmbyServer()).copy(
                name = formName.trim().ifBlank { if (type == EmbyApi.ServerType.JELLYFIN) "Jellyfin" else "Emby" },
                type = formType,
                baseUrl = EmbyApi.normalizeBase(usedBase),
                username = formUser.trim(),
                authMode = if (usedApiKey) "apikey" else "password",
                token = auth.token,
                userId = auth.userId,
                remark = editing?.remark.orEmpty(),
                ignoreCert = formIgnoreCert,
            )
            if (editing == null) EmbyStore.add(context, base) else EmbyStore.update(context, base)
            EmbyStore.setActive(context, base.id)
            showResultDialog(true, "连接成功", "服务器已保存：${base.baseUrl}")
        }
    }

    // v1.3.33：二级页静态渐变底（StaticGlowBackground 在 Scaffold 之下，Scaffold 本体透明）。
    Box(Modifier.fillMaxSize()) {
        StaticGlowBackground(Modifier.fillMaxSize())
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TabTopBar(
                    title = title,
                    onBack = { goBack() },
                    actions = {
                        if (active != null) {
                            IconButton(onClick = { showServerMenu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "服务器管理")
                            }
                            DropdownMenu(
                                expanded = showServerMenu,
                                onDismissRequest = { showServerMenu = false },
                            ) {
                                servers.forEach { s ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(s.name)
                                                if (s.remark.isNotBlank()) {
                                                    Text(
                                                        s.remark,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                Text(
                                                    s.baseUrl,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            if (s.id == active.id) {
                                                Icon(Icons.Outlined.Check, contentDescription = "当前服务器")
                                            }
                                        },
                                        onClick = {
                                            showServerMenu = false
                                            EmbyStore.setActive(context, s.id)
                                        },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("添加服务器") },
                                    onClick = { showServerMenu = false; openAdd() },
                                )
                                DropdownMenuItem(
                                    text = { Text("编辑当前服务器") },
                                    onClick = { showServerMenu = false; openEdit(active) },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除当前服务器") },
                                    onClick = { showServerMenu = false; showDeleteConfirm = true },
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                if (servers.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier.size(64.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.Storage, null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("还没有添加媒体服务器", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "支持自建的 Emby / Jellyfin：填地址和账号登录后，" +
                                    "就能浏览媒体库、搜索、断点续播。只直连你自己的服务器。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { openAdd() }) { Text("添加服务器") }
                        }
                    }
                } else if (active != null) {
                    val server = active
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .clickable { showServerMenu = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(8.dp).clip(CircleShape)
                                    .background(Color(0xFF4ADE80)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(server.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            if (server.remark.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    server.remark,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                server.baseUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("切换", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    item {
                        TechTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            placeholder = "搜索影片 / 剧集",
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = "清空")
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { doSearch(server, query) }),
                        )
                    }
                    if (authExpired) {
                        item {
                            Column(
                                // v1.3.33：错误卡只留一层内边距——去掉外层 16dp 间距，内容内边距保留。
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                    .padding(16.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.ErrorOutline, null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("登录已过期，请重新登录", style = MaterialTheme.typography.titleMedium)
                                }
                                Spacer(Modifier.height(12.dp))
                                FilledTonalButton(onClick = { openEdit(server) }) { Text("重新登录") }
                            }
                        }
                    } else if (loading) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "正在加载…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else if (error.isNotBlank()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    Icons.Outlined.ErrorOutline, null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(40.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(error, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(12.dp))
                                FilledTonalButton(onClick = {
                                    when (level) {
                                        Level.Root -> loadRoot(server)
                                        is Level.Folder -> loadFolder(server, level.id)
                                        is Level.Series -> loadSeries(server, level.id)
                                        is Level.Season -> loadSeason(server, level.seriesId, level.seasonId)
                                    }
                                }) { Text("重试") }
                            }
                        }
                    } else if (searching) {
                        if (items.isEmpty()) {
                            item { EmptyState(Icons.Outlined.Search, "没有找到相关内容", "换个关键词试试") }
                        } else {
                            items(items, key = { it.Id }) { item ->
                                EmbyRow(server, item) { openItem(server, item) }
                            }
                        }
                    } else when (level) {
                        Level.Root -> {
                            if (resumeItems.isNotEmpty()) {
                                item { SectionHeader("继续观看", resumeItems.size) }
                                items(resumeItems, key = { it.Id }) { item ->
                                    EmbyRow(server, item) { openItem(server, item) }
                                }
                            }
                            if (views.isNotEmpty()) {
                                item { SectionHeader("媒体库", views.size) }
                                items(views, key = { it.Id }) { view ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable {
                                                stack = stack + Level.Folder(view.Id, view.Name)
                                                loadFolder(server, view.Id)
                                            }
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primaryContainer),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                if (view.CollectionType == "tvshows") Icons.Outlined.Tv else Icons.Outlined.Movie,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(22.dp),
                                            )
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(view.Name, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                if (view.CollectionType == "tvshows") "剧集库" else "电影库",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Icon(
                                            Icons.Outlined.ChevronRight, null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            if (views.isEmpty() && resumeItems.isEmpty()) {
                                item { EmptyState(Icons.Outlined.Movie, "媒体库是空的", "服务器上还没有媒体库") }
                            }
                        }
                        is Level.Folder, is Level.Series, is Level.Season -> {
                            if (items.isEmpty()) {
                                item { EmptyState(Icons.Outlined.Movie, "这里是空的") }
                            } else {
                                items(items, key = { it.Id }) { item ->
                                    EmbyRow(server, item) { openItem(server, item) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        TechDialog(
            onDismissRequest = { if (!formTesting) showAdd = false },
            title = if (editing == null) "添加服务器" else "编辑服务器",
            confirmText = if (formTesting) "正在连接…" else "测试并保存",
            confirmEnabled = !formTesting,
            onConfirm = { if (!formTesting) saveForm() },
            dismissText = "取消",
            // v1.3.6：紧凑模式 + 内容精简到 7 行以内，一屏放下。
            compact = true,
        ) {
            Column(
                modifier = Modifier.width(240.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                TechTextField(
                    value = formName,
                    onValueChange = { formName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "名称（可空）",
                    singleLine = true,
                )
                TechTextField(
                    value = formAddress,
                    onValueChange = { formAddress = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "粘贴地址，如 https://emby.fool.im:443 或 emby.fool.im:8096",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = formType == "emby",
                        onClick = { formType = "emby" },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Emby") }
                    SegmentedButton(
                        selected = formType == "jellyfin",
                        onClick = { formType = "jellyfin" },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Jellyfin") }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "忽略证书验证",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    AppSwitch(
                        checked = formIgnoreCert,
                        onCheckedChange = { formIgnoreCert = it },
                    )
                }
                TechTextField(
                    value = formUser,
                    onValueChange = { formUser = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "用户名",
                    singleLine = true,
                )
                TechTextField(
                    value = formPass,
                    onValueChange = { formPass = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "密码（无密码用户留空）",
                    singleLine = true,
                    visualTransformation = if (formPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { formPassVisible = !formPassVisible }) {
                            Icon(
                                imageVector = if (formPassVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (formPassVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                )
                TechTextField(
                    value = formApiKey,
                    onValueChange = { formApiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "API Key（可空，填了可免密码）",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (formError.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.ErrorOutline, null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(formError, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        // v1.3.6：测试结果弹窗——成功/失败都弹，调用处受 if (showResult) 守卫；
        // 成功点确定才关表单，失败留在表单里改地址账号。
        if (showResult) {
            TechDialog(
                onDismissRequest = { dismissResult() },
                title = resultTitle,
                // v1.3.33：测试结果可能是长错误详情，走宽松模式。
                compact = false,
                confirmText = "确定",
                dismissText = null,
                onConfirm = { dismissResult() },
            ) {
                Text(resultMessage, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (showDeleteConfirm && active != null) {
        val server = active
        TechDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = "删除服务器",
            confirmText = "删除",
            onConfirm = {
                showDeleteConfirm = false
                EmbyStore.remove(context, server.id)
            },
            dismissText = "取消",
        ) {
            Text("确定删除「${server.name}」吗？只删本机的配置，不动服务器上的内容。")
        }
    }
}

private sealed interface Level {
    data object Root : Level
    data class Folder(val id: String, val name: String) : Level
    data class Series(val id: String, val name: String) : Level
    data class Season(val seriesId: String, val seriesName: String, val seasonId: String, val name: String) : Level
}

private fun displayTitle(item: EmbyApi.EmbyItem): String {
    if (item.Type == "Episode") {
        val s = item.ParentIndexNumber
        val e = item.IndexNumber
        val code = if (s != null && e != null) {
            "S" + s.toString().padStart(2, '0') + "E" + e.toString().padStart(2, '0') + " "
        } else {
            ""
        }
        val series = item.SeriesName.orEmpty()
        return (if (series.isNotBlank()) "$series $code" else code) + item.Name
    }
    return item.Name
}

// v1.1.31 精致化：分组标题＋数量角标。
@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text(
                "$count",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// v1.1.31 精致化：空态（图标＋标题＋可选副文案）。
@Composable
private fun EmptyState(icon: ImageVector, title: String, subtitle: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return if (h > 0) "${h}小时${m}分" else "${m}分"
}

@Composable
private fun EmbyRow(
    server: EmbyServer,
    item: EmbyApi.EmbyItem,
    onClick: () -> Unit,
) {
    val auth = EmbyApi.Auth(server.token, server.userId)
    val type = if (server.type == "jellyfin") EmbyApi.ServerType.JELLYFIN else EmbyApi.ServerType.EMBY
    val poster = EmbyApi.imageUrl(server.baseUrl, type, auth.token, item.Id)
    val resumeMs = EmbyApi.ticksToMs(item.UserData?.PlaybackPositionTicks ?: 0L)
    val durMs = EmbyApi.ticksToMs(item.RunTimeTicks)
    val pct = if (durMs > 0) (resumeMs * 100 / durMs).toInt().coerceIn(0, 100) else 0
    var imageFailed by remember(poster) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(width = 56.dp, height = 80.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Movie, null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
            if (!imageFailed) {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = { imageFailed = true },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = displayTitle(item),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            val sub = buildString {
                if (item.ProductionYear != null) append(item.ProductionYear.toString())
                if (durMs > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(formatDuration(durMs))
                }
                if ((item.Type == "Series" || item.Type == "Season") && item.ChildCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${item.ChildCount}集")
                }
            }
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (resumeMs > 10_000L && durMs > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = pct / 100f,
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
                Text(
                    text = "已看到 ${pct}% · 还剩 ${formatDuration(durMs - resumeMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.Outlined.ChevronRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
