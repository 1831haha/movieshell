package com.movieshell.ui.market

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PluginMarket
import com.movieshell.data.repo.PluginMarket.MarketInfo
import com.movieshell.data.repo.PluginMarket.MarketPlugin
import com.movieshell.data.repo.PluginStore
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppChip
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.LoadingSkeleton
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 插件市场页（v0.23.0）。
 * - 顶部切换市场源（可增删，设置→插件管理）
 * - 分类过滤：全部 / 环境 / 应用
 * - 搜索过滤；不支持的 type 默认隐藏（设置里可开"显示不兼容插件"，置灰禁装）
 * - 卡片点击进详情页；卡片上可直接安装/更新/卸载
 *
 * v0.27.0 Soft Tech 换皮：卡片走 AppCard，筛选走 AppChip，搜索走 AppTextField，
 * 三态走 EmptyState / LoadingSkeleton。过滤与按钮状态机逻辑零变更。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginMarketScreen(onBack: () -> Unit = {}, onDetail: (String) -> Unit = {}) {
    val context = LocalContext.current
    val sources by PluginStore.marketSources.collectAsState()
    val activeIdx by PluginStore.activeMarketIdx.collectAsState()
    val showIncompatible by PluginStore.showIncompatible.collectAsState()

    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") } // ""=全部 env/app
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<MarketInfo?>(null) }
    var installedTick by remember { mutableStateOf(0) }
    var busyId by remember { mutableStateOf<String?>(null) }
    // v0.27.2：一键安装进度弹窗态
    var installProgress by remember { mutableStateOf<InstallProgressUi?>(null) }

    // v0.27.4：市场拉取走 LaunchedEffect（跟随组合生命周期，离开页面自动取消；
    // 刷新按钮只递增 reloadTick，上一轮未完成会被自动取消——根治
    // "The coroutine scope left the composition"（之前 reload() 里 scope.launch，
    // 快速连点刷新/切走页面时旧协程在已离开组合的 scope 里恢复就报这个错）
    var reloadTick by remember { mutableStateOf(0) }
    fun reload() {
        reloadTick++
    }

    // v0.45.0：缓存秒开 + 后台对冲刷新。进场先读磁盘缓存（有则秒显、不转圈），
    // 再后台拉新；拉新失败但有缓存时只 toast、不清空列表——"偶尔加载不出来"到此为止。
    suspend fun fetchMarket() {
        error = null
        val url = PluginStore.marketUrl.value
        try {
            PluginStore.init(context)
            val cached = PluginMarket.loadCached(context, url)
            // 切市场时先换上新缓存（或清空），旧市场的列表不串台
            info = cached
            loading = cached == null
            info = PluginMarket.fetchRemote(context, url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogBus.e("PluginMarket", "市场拉取失败：${e.message}")
            if (info == null) {
                error = e.message ?: e.toString()
                // v0.25.0：超时/失败给明确中文 Toast，不只记日志
                Toast.makeText(context, "插件市场加载失败：${e.message}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "市场刷新失败，已显示缓存", Toast.LENGTH_SHORT).show()
            }
        } finally {
            loading = false
        }
    }
    LaunchedEffect(activeIdx, reloadTick) { fetchMarket() }

    /** 带进度条的一键安装（列表页/详情页共用流程）。 */
    fun doInstall(p: MarketPlugin) {
        busyId = p.id
        // v0.27.4：安装是长耗时任务，走进程级 appScope，页面切走也不中断，
        // 不再用组合绑定的 scope（之前切走页面会报 scope left the composition）
        SubSiteStore.appScope.launch {
            val (ok, msg) = PluginUi.installWithProgress(context, p) { installProgress = it }
            PluginUi.toast(context.applicationContext, msg)
            installProgress = null
            installedTick++
            busyId = null
            if (!ok) LogBus.e("PluginMarket", "安装失败已 toast：${p.id}")
        }
    }
    val installedMap = remember(installedTick) {
        PluginStore.listInstalled().associateBy { it.id }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(info?.name ?: "插件市场", style = SoftType.Title) },
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
                .padding(horizontal = SoftDimens.PagePadding),
        ) {
            // v0.29.0：市场顶部显示仓库链接（点文本复制，图标按钮浏览器打开）
            RepoLinkRow()
            Spacer(Modifier.height(8.dp))
            if (sources.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sources.size) { i ->
                        AppChip(
                            text = sources[i].name,
                            selected = i == activeIdx,
                            onClick = { PluginStore.setActiveMarket(context, i) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppChip(text = "全部", selected = category.isEmpty(), onClick = { category = "" })
                AppChip(text = "环境", selected = category == "env", onClick = { category = "env" })
                AppChip(text = "应用", selected = category == "app", onClick = { category = "app" })
            }
            Spacer(Modifier.height(8.dp))
            AppTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索插件",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            when {
                loading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(SoftDimens.CardSpacing)) {
                        repeat(3) {
                            LoadingSkeleton(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(148.dp),
                                shape = RoundedCornerShape(SoftDimens.CardCorner),
                            )
                        }
                    }
                }
                error != null -> {
                    EmptyState(
                        kind = EmptyKind.Error,
                        icon = Icons.Filled.CloudOff,
                        title = "插件市场加载失败",
                        hint = error ?: "检查网络或设置页的市场源",
                        actionText = "重试",
                        onAction = { reload() },
                    )
                }
                else -> {
                    val shown = (info?.plugins.orEmpty())
                        .filter { showIncompatible || it.isSupported() }
                        .filter { category.isEmpty() || it.category == category }
                        .filter {
                            query.isBlank() ||
                                it.name.contains(query, true) ||
                                it.id.contains(query, true) ||
                                it.desc.contains(query, true)
                        }
                    if (shown.isEmpty()) {
                        EmptyState(
                            kind = EmptyKind.Empty,
                            icon = Icons.Filled.Search,
                            title = "没有找到插件",
                            hint = "换个关键词或分类试试",
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(SoftDimens.CardSpacing),
                        ) {
                            items(shown, key = { it.id }) { p ->
                                PluginCard(
                                    plugin = p,
                                    installedVersion = installedMap[p.id]?.version,
                                    busy = busyId == p.id,
                                    onDetail = { onDetail(p.id) },
                                    onInstall = {
                                        doInstall(p)
                                    },
                                    onUninstall = {
                                        busyId = p.id
                                        // v0.27.4：同安装，走进程级 scope，页面切走不中断
                                        SubSiteStore.appScope.launch {
                                            PluginUi.toast(
                                                context.applicationContext,
                                                PluginUi.uninstall(context, p),
                                            )
                                            installedTick++
                                            busyId = null
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    // v0.27.2：一键安装进度弹窗（全局覆盖在页面上）
    InstallProgressDialog(installProgress)
}

@Composable
private fun PluginCard(
    plugin: MarketPlugin,
    installedVersion: String?,
    busy: Boolean,
    onDetail: () -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val hasUpdate = installedVersion != null &&
        PluginMarket.isNewer(plugin.version, installedVersion)
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (plugin.isSupported()) 1f else 0.55f),
        onClick = onDetail,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (plugin.iconUrl.isNotBlank()) {
                    AsyncImage(
                        model = plugin.iconUrl,
                        contentDescription = plugin.name,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.size(10.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = plugin.name, style = SoftType.CardTitle)
                    if (plugin.author.isNotBlank()) {
                        Text(
                            text = plugin.author,
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(plugin.type) })
                if (plugin.category.isNotBlank()) {
                    AssistChip(onClick = {}, label = { Text(PluginUi.categoryLabel(plugin.category)) })
                }
                AssistChip(onClick = {}, label = { Text("v${plugin.version}") })
                if (installedVersion != null) {
                    AssistChip(
                        onClick = {},
                        label = { Text(if (hasUpdate) "有新版" else "已安装") },
                    )
                }
                if (!plugin.isSupported()) {
                    AssistChip(onClick = {}, label = { Text("不支持") })
                }
            }
            if (plugin.desc.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = plugin.desc,
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(8.dp))
            PluginActionButtons(
                plugin = plugin,
                installedVersion = installedVersion,
                busy = busy,
                onInstall = onInstall,
                onUninstall = onUninstall,
            )
        }
    }
}

/** v0.29.0：市场顶部仓库链接行——点文本复制，点图标浏览器打开。 */
@Composable
private fun RepoLinkRow() {
    val context = LocalContext.current
    val repoUrl = "https://github.com/1831haha/xiaying-plugins"
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = repoUrl,
            style = SoftType.Caption,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .clickable {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("repo", repoUrl))
                    Toast.makeText(context, "仓库链接已复制", Toast.LENGTH_SHORT).show()
                },
        )
        IconButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(repoUrl),
                        ),
                    )
                }
            },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = "浏览器打开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
