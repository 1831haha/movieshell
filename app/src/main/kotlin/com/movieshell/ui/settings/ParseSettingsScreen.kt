package com.movieshell.ui.settings

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
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
    // 测活状态：index -> true 存活 / false 失效；testing 记录正在测的
    var probeResults by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }
    var testing by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var probingAll by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun probeOne(index: Int) {
        val parse = customParses.getOrNull(index) ?: return
        if (index in testing || probingAll) return
        testing = testing + index
        scope.launch {
            val ok = runCatching { ParseApi().probe(parse) }
                .onFailure { LogBus.w("ParseSettings", "测活异常：${it.message}") }
                .getOrDefault(false)
            probeResults = probeResults + (index to ok)
            testing = testing - index
        }
    }

    fun probeAll() {
        if (probingAll || customParses.isEmpty()) return
        probingAll = true
        testing = customParses.indices.toSet()
        scope.launch {
            val api = ParseApi()
            customParses.forEachIndexed { i, p ->
                val ok = runCatching { api.probe(p) }.getOrDefault(false)
                probeResults = probeResults + (i to ok)
                testing = testing - i
            }
            probingAll = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("解析接口", style = SoftType.Title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
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
                            subtitle = if (probingAll) "测试中…" else "逐条检测线路是否可用",
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
                    val state = probeResults[index]
                    SettingsRow(
                        icon = Icons.Filled.Link,
                        title = parse.name.ifBlank { "未命名" },
                        subtitle = buildString {
                            append(if (parse.type == 1) "JSON" else "嗅探")
                            append(" · ${parse.url}")
                            when (state) {
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
                                        tint = Color(0xFF4CAF50),
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
                                    // 删一条后序号错位，清掉全部测活结果
                                    probeResults = emptyMap()
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
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            shape = RoundedCornerShape(SoftDimens.CardCorner),
            title = { Text("添加解析接口", style = SoftType.CardTitle) },
            text = {
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
                    }
                    Text(
                        text = "JSON：GET 地址+视频地址，取返回 JSON 的 url 字段；嗅探：用 WebView 打开抓直链",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                AppButton(
                    text = "添加",
                    onClick = {
                        ParseStore.add(context, parseName, parseUrl, parseType)
                        showAddDialog = false
                    },
                )
            },
            dismissButton = {
                AppButton(
                    text = "取消",
                    onClick = { showAddDialog = false },
                    variant = AppButtonVariant.Ghost,
                )
            },
        )
    }
}
