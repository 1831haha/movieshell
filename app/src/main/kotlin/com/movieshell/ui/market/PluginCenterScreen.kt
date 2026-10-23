package com.movieshell.ui.market

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.repo.PluginMarket
import com.movieshell.data.repo.PluginStore
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.CapsuleTabBar
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.127.0：插件中心——把原「插件市场」「服务插件」两个入口合并为一个
 * （仿 DS 插件中心三页签的信息结构，视觉沿用本 App 语言）：
 * - 插件：已安装插件（数据源 PluginStore 已装记录，市场缓存补简介/图标/更新态）
 * - 服务：ServerPluginScreen 内容原样嵌入（embedded，不另起顶栏）
 * - 市场：PluginMarketScreen 内容原样嵌入（列表/搜索/筛选/详情/安装全复用）
 * 旧路由 market / service_plugins 兼容跳转到本页对应页签，不留死入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginCenterScreen(
    initialTab: Int = 0,
    onBack: () -> Unit = {},
    onDetail: (String) -> Unit = {},
) {
    var tab by rememberSaveable(initialTab) {
        mutableIntStateOf(initialTab.coerceIn(0, 2))
    }
    Scaffold(
        topBar = {
            Column {
                TabTopBar(title = "插件中心", onBack = onBack)
                CapsuleTabBar(
                    titles = listOf("插件", "服务", "市场"),
                    selected = tab,
                    onSelect = { tab = it },
                )
            }
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (tab) {
                0 -> InstalledPluginsBody(onDetail = onDetail)
                1 -> ServerPluginScreen(embedded = true)
                else -> PluginMarketScreen(embedded = true, onDetail = onDetail)
            }
        }
    }
}

/** 「插件」页签：已安装插件列表（名 + 版本 + 一句话作用 + 类型标签 + 卸载/更新）。 */
@Composable
private fun InstalledPluginsBody(onDetail: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var plugins by remember { mutableStateOf<List<PluginStore.InstalledPlugin>>(emptyList()) }
    var marketById by remember {
        mutableStateOf<Map<String, PluginMarket.MarketPlugin>>(emptyMap())
    }
    var uninstallFor by remember { mutableStateOf<PluginStore.InstalledPlugin?>(null) }
    var installState by remember { mutableStateOf<InstallProgressUi?>(null) }

    LaunchedEffect(tick) {
        PluginStore.init(context)
        // 市场缓存只为补简介/图标/更新态，取不到不影响已装列表本身；
        // v0.134.0：多市场归并——各启用源缓存按注册表顺序合并，同 id 先到者胜出
        val byId = mutableMapOf<String, PluginMarket.MarketPlugin>()
        PluginStore.marketSources.value.filter { it.enabled }.forEach { src ->
            PluginMarket.loadCached(context, src.url)?.plugins?.forEach { p ->
                byId.putIfAbsent(p.id, p)
            }
        }
        marketById = byId
        plugins = withContext(Dispatchers.IO) { PluginStore.listInstalled() }
    }

    fun doUpdate(mp: PluginMarket.MarketPlugin) {
        scope.launch {
            val (ok, msg) = PluginUi.installWithProgress(context, mp) { installState = it }
            installState = null
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            if (ok) tick++
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (plugins.isEmpty()) {
            item {
                Text(
                    "暂无已安装插件。去「市场」页签逛逛，或在「服务」页签装本地服务。",
                    style = SoftType.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(plugins, key = { it.id }) { p ->
            val mp = marketById[p.id]
            val hasUpdate = mp != null && PluginMarket.isUpdatable(mp, p.version)
            val desc = mp?.desc?.lineSequence()?.firstOrNull()?.trim()
                ?.ifBlank { null } ?: installedFallbackDesc(p)
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!mp?.iconUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = mp?.iconUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                            Spacer(Modifier.size(12.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                p.name,
                                style = SoftType.CardTitle,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                desc,
                                style = SoftType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TagChip("v${p.version}")
                        TagChip(installedTypeLabel(p))
                        if (hasUpdate && mp != null) TagChip("可更新 v${mp.version}")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (mp != null) {
                            TextButton(onClick = { onDetail(p.id) }) { Text("详情") }
                        }
                        if (hasUpdate && mp != null) {
                            TextButton(onClick = { doUpdate(mp) }) { Text("更新") }
                        }
                        TextButton(onClick = { uninstallFor = p }) { Text("卸载") }
                    }
                }
            }
        }
    }

    InstallProgressDialog(installState)

    uninstallFor?.let { p ->
        var deleteDocs by remember(p.id) { mutableStateOf(false) }
        TechDialog(
            onDismissRequest = { uninstallFor = null },
            title = "卸载 ${p.name}？",
            subtitle = "将删除该插件的全部文件，正在使用它的源会失效。",
            confirmText = "卸载",
            onConfirm = {
                uninstallFor = null
                scope.launch {
                    val msg = withContext(Dispatchers.IO) {
                        runCatching {
                            PluginMarket.uninstall(context, p.id, deleteDocs)
                            "已卸载 ${p.name}"
                        }.getOrElse { "卸载失败：${it.message}" }
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    tick++
                }
            },
            dismissText = "取消",
        ) {
            if (p.type == "setup") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteDocs, onCheckedChange = { deleteDocs = it })
                    Text(
                        text = "一并删除已写入的说明文档（默认不删）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** 已装插件没有市场条目时的一句话作用兜底（按类型/引擎给，不编造具体功能）。 */
private fun installedFallbackDesc(p: PluginStore.InstalledPlugin): String = when {
    p.type.equals("server", true) -> "本地服务插件（在「服务」页签管理启停）"
    p.engine.equals("py", true) || p.type.equals("py", true) -> "Python 规则插件"
    p.type.contains("jar", true) -> "JAR 蜘蛛插件"
    p.engine.isNotBlank() || p.type.isNotBlank() -> "JS 规则插件"
    else -> "已安装插件"
}

/** 已装插件的类型标签（市场条目在手时用市场口径，否则按本地记录）。 */
private fun installedTypeLabel(p: PluginStore.InstalledPlugin): String = when {
    p.type.equals("server", true) -> "服务"
    p.engine.equals("py", true) || p.type.equals("py", true) -> "Python"
    p.type.contains("jar", true) -> "JAR"
    p.engine.isNotBlank() -> p.engine.uppercase()
    p.type.isNotBlank() -> p.type
    else -> "插件"
}

@Composable
private fun TagChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            style = SoftType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
