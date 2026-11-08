package com.movieshell.ui.openlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.TabTopBar
import kotlinx.coroutines.launch

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

    LaunchedEffect(currentPath, token) {
        page = 1
        entries = emptyList()
        total = 0
        loadPage(1, append = false)
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
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.path }) { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                if (entry.isDir) {
                                    pathStack = pathStack + entry.path
                                } else if (!gettingUrl) {
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
