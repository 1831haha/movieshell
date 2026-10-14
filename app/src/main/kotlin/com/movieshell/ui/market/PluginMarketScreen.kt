package com.movieshell.ui.market

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PluginMarket
import com.movieshell.data.repo.PluginMarket.MarketPlugin
import com.movieshell.data.repo.PluginStore
import kotlinx.coroutines.launch

/**
 * 插件市场页（v0.22.0）。
 * - 从 marketUrl 拉 market.json → 列表展示（名/版本/类型/描述）→ 搜索过滤
 * - 一键安装（下载→MD5 强制校验→按 type 解压到对应源目录）
 * - 已安装显示版本、有新版提示一键更新、卸载删文件+清缓存
 * - server 插件 App 只做下载校验与版本管理，Node.js 服务需外部运行
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginMarketScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val marketUrl by PluginStore.marketUrl.collectAsState()

    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var plugins by remember { mutableStateOf<List<MarketPlugin>>(emptyList()) }
    var installedTick by remember { mutableStateOf(0) } // 自增刷新已安装状态
    var busyId by remember { mutableStateOf<String?>(null) }

    fun reload() {
        loading = true
        error = null
        scope.launch {
            try {
                PluginStore.init(context)
                plugins = PluginMarket.fetch(PluginStore.marketUrl.value)
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                LogBus.e("PluginMarket", "市场拉取失败：${e.message}")
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(marketUrl) { reload() }
    val installedMap = remember(installedTick) {
        PluginStore.listInstalled().associateBy { it.id }
    }

    fun toast(msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun doInstall(p: MarketPlugin) {
        busyId = p.id
        scope.launch {
            try {
                PluginMarket.install(context, p)
                installedTick++
                toast("已安装 ${p.name} v${p.version}")
            } catch (e: Exception) {
                val msg = "安装失败：${e.message}"
                LogBus.e("PluginMarket", "$msg（${p.id}）")
                toast(msg)
            } finally {
                busyId = null
            }
        }
    }

    fun doUninstall(p: MarketPlugin) {
        busyId = p.id
        scope.launch {
            try {
                PluginMarket.uninstall(context, p.id)
                installedTick++
                toast("已卸载 ${p.name}")
            } catch (e: Exception) {
                val msg = "卸载失败：${e.message}"
                LogBus.e("PluginMarket", "$msg（${p.id}）")
                toast(msg)
            } finally {
                busyId = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件市场") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索插件") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "server 插件 App 只负责下载校验与版本管理，Node.js 服务需自行运行",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            when {
                loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
                error != null -> {
                    Text(
                        text = "加载失败：$error\n检查网络或设置页的市场地址。",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { reload() }) { Text("重试") }
                }
                else -> {
                    val shown = plugins.filter {
                        query.isBlank() ||
                            it.name.contains(query, true) ||
                            it.id.contains(query, true) ||
                            it.desc.contains(query, true)
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(shown, key = { it.id }) { p ->
                            PluginCard(
                                plugin = p,
                                installedVersion = installedMap[p.id]?.version,
                                busy = busyId == p.id,
                                onInstall = { doInstall(p) },
                                onUninstall = { doUninstall(p) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginCard(
    plugin: MarketPlugin,
    installedVersion: String?,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val hasUpdate = installedVersion != null &&
        PluginMarket.isNewer(plugin.version, installedVersion)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(plugin.type) })
                AssistChip(onClick = {}, label = { Text(plugin.engine.ifBlank { "-" }) })
                AssistChip(onClick = {}, label = { Text("v${plugin.version}") })
                if (installedVersion != null) {
                    AssistChip(
                        onClick = {},
                        label = { Text(if (hasUpdate) "有新版" else "已安装") },
                    )
                }
            }
            if (plugin.desc.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = plugin.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                when {
                    installedVersion == null -> {
                        Button(onClick = onInstall, enabled = !busy) {
                            Icon(Icons.Filled.CloudDownload, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("安装")
                        }
                    }
                    hasUpdate -> {
                        Button(onClick = onInstall, enabled = !busy) { Text("更新到 v${plugin.version}") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onUninstall, enabled = !busy) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("卸载")
                        }
                    }
                    else -> {
                        OutlinedButton(onClick = onUninstall, enabled = !busy) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("卸载")
                        }
                    }
                }
            }
        }
    }
}
