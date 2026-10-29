package com.movieshell.ui.settings

import com.movieshell.ui.theme.V4
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.ParseStore
import com.movieshell.data.source.ParseApi
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 解析接口子页面（v0.78.2）。
 *
 * 设置页只留一个"解析接口"入口，列表、一键测活、添加都收进这里，
 * 16 条预装线路不再把设置页撑得很长。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParseSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val customParses by ParseStore.custom.collectAsState()
    // v0.188.0：测活状态按 url 存，不按下标；探活进行中删行/刷新，
    // 迟到结果按旧下标贴到新行的窗口就此关闭。
    var probeResults by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var testing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var probingAll by remember { mutableStateOf(false) }
    // v0.272.0（T38）：一键测活总进度——原来只有“测试中…”一行字，不知道跑到第几条。
    var probeDone by remember { mutableStateOf(0) }
    var probeTotal by remember { mutableStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun probeOne(index: Int) {
        val parse = customParses.getOrNull(index) ?: return
        // 下发时记下 url，写回前校验该 url 还在列表里，删行后的迟到结果直接丢弃。
        val url = parse.url
        if (url in testing || probingAll) return
        testing = testing + url
        scope.launch {
            val ok = runCatching { ParseApi().probe(parse) }
                .onFailure { LogBus.w("ParseSettings", "测活异常：${it.message}") }
                .getOrDefault(false)
            if (customParses.any { it.url == url }) {
                probeResults = probeResults + (url to ok)
            }
            testing = testing - url
        }
    }

    fun probeAll() {
        if (probingAll || customParses.isEmpty()) return
        // v0.272.0（T38）：一键测活改并发——原来 forEach 串行，一条 10 秒超时就拖全队；
        // probe 本身是纯 HTTP（IO 线程），并发跑、每条完事即记进度、迟到结果仍按 url 校验。
        val targets = customParses.toList()
        probingAll = true
        testing = targets.map { it.url }.toSet()
        probeDone = 0
        probeTotal = targets.size
        scope.launch {
            val api = ParseApi()
            targets.map { p ->
                async {
                    val ok = runCatching { api.probe(p) }.getOrDefault(false)
                    if (customParses.any { it.url == p.url }) {
                        probeResults = probeResults + (p.url to ok)
                    }
                    testing = testing - p.url
                    probeDone++
                }
            }.forEach { it.await() }
            probingAll = false
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        topBar = {
            TabTopBar(
                title = "解析接口",
                onBack = onBack,
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(SoftDimens.PagePadding),
        ) {
            item {
                SettingsGroup(title = "管理") {
                    SettingsRow(
                        icon = Icons.Filled.Add,
                        title = "添加解析接口",
                        subtitle = "自定义 jx 解析，播放时优先尝试",
                        onClick = { showAddDialog = true },
                    )
                    if (customParses.isNotEmpty()) {
                        SettingsRow(
                            icon = Icons.Filled.NetworkCheck,
                            title = "一键测活",
                            subtitle = if (probingAll) "测试中… 已完成 $probeDone / $probeTotal" else "逐条检测线路是否可用",
                            onClick = { probeAll() },
                        )
                    }
                }
                Spacer(Modifier.height(SoftDimens.CardSpacing))
            }
            if (customParses.isEmpty()) {
                item {
                    Text(
                        text = "暂无自定义解析，播放时只用订阅自带的解析线路",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                item {
                    Text(
                        text = "共 ${customParses.size} 条（点单条测活）",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                itemsIndexed(
                    items = customParses,
                    key = { _, p -> p.url },
                ) { index, parse ->
                    val state = probeResults[parse.url]
                    val isTesting = parse.url in testing
                    SettingsRow(
                        icon = Icons.Filled.Link,
                        title = parse.name.ifBlank { "未命名" },
                        subtitle = buildString {
                            append(when (parse.type) { 1 -> "JSON"; 99 -> "yt-dlp"; else -> "嗅探" })
                            append(" · ${parse.url}")
                            // v0.272.0（T38）：单条测活行上显示“测试中…”——原来 testing
                            // 只用来防重，点完行上没有任何变化，用户不知道测没测。
                            if (isTesting) append(" · 测试中…")
                            else when (state) {
                                true -> append(" · 正常")
                                false -> append(" · 失效")
                                null -> {}
                            }
                        },
                        onClick = { probeOne(index) },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                when (state) {
                                    true -> Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = "正常",
                                        tint = V4.c.ok,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    false -> Icon(
                                        Icons.Filled.Cancel,
                                        contentDescription = "失效",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    null -> {}
                                }
                                IconButton(onClick = {
                                    ParseStore.remove(context, index)
                                    // 按 url 存，删行只清掉该行自己的结果，不再清全部。
                                    probeResults = probeResults - parse.url
                                    testing = testing - parse.url
                                }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    // 添加解析接口对话框（从设置页搬过来）
    if (showAddDialog) {
        var parseName by remember { mutableStateOf("") }
        var parseUrl by remember { mutableStateOf("") }
        var parseType by remember { mutableStateOf(1) }
        var addError by remember { mutableStateOf<String?>(null) }
        TechDialog(
            onDismissRequest = { showAddDialog = false },
            title = "添加解析接口",
            confirmText = "添加",
            onConfirm = {
                // v0.217.0（F62）：加不上时框内报错、不关对话框。
                if (ParseStore.add(context, parseName, parseUrl, parseType)) {
                    showAddDialog = false
                } else {
                    addError = "名称和接口地址不能为空（yt-dlp 通用线路地址可留空）"
                }
            },
        ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AppTextField(
                        value = parseName,
                        onValueChange = { parseName = it },
                        placeholder = "名称",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    AppTextField(
                        value = parseUrl,
                        onValueChange = { parseUrl = it },
                        placeholder = "接口地址（视频地址拼在后面）",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = parseType == 1, onClick = { parseType = 1 })
                        Text(
                            "JSON 接口",
                            style = SoftType.Body,
                            modifier = Modifier.clickable { parseType = 1 },
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        RadioButton(selected = parseType == 0, onClick = { parseType = 0 })
                        Text(
                            "网页嗅探",
                            style = SoftType.Body,
                            modifier = Modifier.clickable { parseType = 0 },
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        RadioButton(selected = parseType == 99, onClick = { parseType = 99 })
                        Text(
                            "yt-dlp 通用",
                            style = SoftType.Body,
                            modifier = Modifier.clickable { parseType = 99 },
                        )
                    }
                    Text(
                        text = "JSON：GET 地址+视频地址，取返回 JSON 的 url 字段；嗅探：用 WebView 打开抓直链；yt-dlp 通用：无规则视频页的通用解析，需先在插件市场安装 yt-dlp 通用解析包，地址栏留空即可（自动填 ytdlp://generic，仅作线路标识）",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    addError?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = err,
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
        }
    }
}
