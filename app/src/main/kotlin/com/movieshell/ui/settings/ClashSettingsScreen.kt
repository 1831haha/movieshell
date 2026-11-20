package com.movieshell.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movieshell.data.source_mgr.ApiSource
import com.movieshell.data.store.ApiSourceStore
import com.movieshell.data.source_mgr.ClashProxy
import com.movieshell.data.source_mgr.DrpySourceManager
import com.movieshell.data.source_mgr.PySourceManager
import com.movieshell.data.source_mgr.ServicePluginManager
import com.movieshell.data.source_mgr.SubSiteStore
import com.movieshell.data.source_mgr.WebSourceManager
import com.movieshell.data.store.RuleInfo
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppSwitch
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.CapsuleTabBar
import com.movieshell.ui.components.TechTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechTextField
import com.movieshell.ui.theme.V5
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * v1.3.26：Clash 代理设置页。
 *
 * 三块：① clash 服务状态（启动/停止/探活/去服务页）；② 订阅链接（只存本机，
 * 带 Clash UA 更新订阅，写进私有目录给 mihomo 的 file 型 provider 读）；
 * ③ 每源一个“走代理”开关（JS/PY 规则 + CMS 订阅 + 网页源，支持搜索与分组折叠；
 * PHP 不支持，见页内说明；音乐改直连，不走代理，见 HttpClient.noProxyClient）。
 * 只代理开了开关的源，其他源一点不受影响。
 */
@Composable
fun ClashSettingsScreen(
    onBack: () -> Unit = {},
    onOpenServicePage: () -> Unit = {},
    // v1.3.27：安装在市场页（plugin_center/2），服务页只管已装服务的启停。
    onOpenMarketPage: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var urlInput by remember { mutableStateOf(ClashProxy.subscriptionUrl()) }
    var subStatus by remember {
        mutableStateOf(if (ClashProxy.hasSubscription()) "已拉取过订阅文件" else "还没拉过订阅")
    }
    var updating by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var jsRules by remember { mutableStateOf<List<RuleInfo>>(emptyList()) }
    var pyRules by remember { mutableStateOf<List<RuleInfo>>(emptyList()) }
    var cmsSubs by remember { mutableStateOf<List<ApiSource>>(emptyList()) }
    // v1.4.4：网页源分组——只列 http(s) 站源（file:// 本地页没有 host，走不了代理）。
    var webSites by remember { mutableStateOf<List<WebSourceManager.WebSite>>(emptyList()) }
    var proxyOn by remember { mutableStateOf<Set<String>>(emptySet()) }
    // v1.4.4：生效域名快照（ClashProxy.proxiedHosts），让“走没走代理”可查。
    var proxiedHosts by remember { mutableStateOf<Set<String>>(emptySet()) }
    // v1.3.30：源列表搜索 + 胶囊过滤（0=全部 1=已开启 2=未开启）。
    var proxyQuery by remember { mutableStateOf("") }
    var proxyFilter by remember { mutableStateOf(0) }
    // v1.3.30：分组展开记忆——用户点过组头才记（hasExpandedMemory），没记过时
    // 有已开的组默认展开、其余收起。rememberSaveable 才能扛过旋转/进程重建
    // （check_imports.py 抓不住 rememberSaveable 漏 import，见 AGENTS.md）。
    var hasExpandedMemory by rememberSaveable { mutableStateOf(false) }
    var expandedGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }

    fun refresh() {
        installed = ClashProxy.isInstalled(context)
        running = ClashProxy.isRunning()
        jsRules = runCatching { DrpySourceManager.listRules() }.getOrDefault(emptyList())
        pyRules = runCatching { PySourceManager.listRules() }.getOrDefault(emptyList())
        cmsSubs = runCatching { ApiSourceStore.load(context) }.getOrDefault(emptyList())
        webSites = runCatching { WebSourceManager.listSites() }.getOrDefault(emptyList())
            .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
        proxyOn = ClashProxy.enabledKeys()
        proxiedHosts = ClashProxy.proxiedHosts()
    }
    LaunchedEffect(Unit) {
        // v1.3.27：进页先认领市场装到 plugins/ 的旧 clash（无注册表），
        // 再读安装态——不然已装好的 clash 在这里仍显示未安装。
        withContext(Dispatchers.IO) {
            runCatching { ServicePluginManager.reconcileMarketServices(context) }
        }
        refresh()
    }

    val statusText = when {
        // v1.3.27：安装在市场页，不在服务页（服务页只列已装的服务）。
        !installed -> "未安装：去插件中心 → 市场页安装 clash 插件"
        running -> "运行中 · 控制端口 ${ClashProxy.controllerPort()} · 混合端口 ${ClashProxy.mixedPort()}"
        else -> "已安装，未运行"
    }

    // v1.3.30：分组默认展开态——有已开的组默认展开、其余收起；用户点过组头后
    // 以记忆为准。展开记忆只记“用户点过的结果”，默认态不写入记忆（源列表
    // 变化时默认态仍能跟上）。
    fun isGroupExpanded(key: String): Boolean {
        if (hasExpandedMemory) return expandedGroups.contains(key)
        return when (key) {
            "py" -> pyRules.any { proxyOn.contains(ClashProxy.pyKey(it.name)) }
            "cms" -> cmsSubs.any { proxyOn.contains(ClashProxy.cmsKey(it.url)) }
            "web" -> webSites.any { proxyOn.contains(ClashProxy.webKey(it.fileName)) }
            else -> jsRules.any { proxyOn.contains(ClashProxy.jsKey(it.name)) }
        }
    }
    fun toggleGroup(key: String) {
        val current = setOf("js", "py", "cms", "web").filter { isGroupExpanded(it) }.toMutableSet()
        if (current.contains(key)) current.remove(key) else current.add(key)
        expandedGroups = current.toList()
        hasExpandedMemory = true
    }

    // v1.3.30：搜索 + 胶囊叠加过滤——按源名（标题/文件名/订阅名/地址）实时过滤。
    val proxyQueryTrimmed = proxyQuery.trim()
    val isFiltering = proxyQueryTrimmed.isNotEmpty() || proxyFilter != 0
    fun matchQuery(text: String): Boolean =
        proxyQueryTrimmed.isEmpty() || text.contains(proxyQueryTrimmed, ignoreCase = true)
    fun matchFilter(on: Boolean): Boolean = when (proxyFilter) {
        1 -> on
        2 -> !on
        else -> true
    }
    fun matchSource(on: Boolean, vararg texts: String): Boolean =
        matchFilter(on) && texts.any { matchQuery(it) }

    // v1.3.30：平铺过滤结果（搜索或胶囊过滤时分组隐藏，改出平铺结果）。
    val flatItems = buildList {
        jsRules.forEach { rule ->
            val key = ClashProxy.jsKey(rule.name)
            if (matchSource(proxyOn.contains(key), rule.title, rule.name)) {
                add(ProxyListItem.SourceRow(0, rule.title.ifBlank { rule.name }, rule.name, key))
            }
        }
        pyRules.forEach { rule ->
            val key = ClashProxy.pyKey(rule.name)
            if (matchSource(proxyOn.contains(key), rule.title, rule.name)) {
                add(ProxyListItem.SourceRow(1, rule.title.ifBlank { rule.name }, rule.name, key))
            }
        }
        cmsSubs.forEach { sub ->
            val key = ClashProxy.cmsKey(sub.url)
            if (matchSource(proxyOn.contains(key), sub.name, sub.url)) {
                add(ProxyListItem.SourceRow(2, sub.name.ifBlank { sub.url }, sub.url, key))
            }
        }
        webSites.forEach { site ->
            val key = ClashProxy.webKey(site.fileName)
            if (matchSource(proxyOn.contains(key), site.name, site.url)) {
                add(ProxyListItem.SourceRow(3, site.name.ifBlank { site.url }, site.url, key))
            }
        }
    }

    // v1.3.30：分组折叠列表（无过滤时）——组头 + 组内行，空组展开后给一行提示。
    val groupedItems = buildList {
        add(ProxyListItem.Header("js"))
        if (isGroupExpanded("js")) {
            if (jsRules.isEmpty()) add(ProxyListItem.EmptyNote("暂无本地 JS 规则"))
            else jsRules.forEach { rule ->
                add(ProxyListItem.SourceRow(0, rule.title.ifBlank { rule.name }, rule.name, ClashProxy.jsKey(rule.name)))
            }
        }
        add(ProxyListItem.Header("py"))
        if (isGroupExpanded("py")) {
            if (pyRules.isEmpty()) add(ProxyListItem.EmptyNote("暂无本地 PY 规则"))
            else pyRules.forEach { rule ->
                add(ProxyListItem.SourceRow(1, rule.title.ifBlank { rule.name }, rule.name, ClashProxy.pyKey(rule.name)))
            }
        }
        add(ProxyListItem.Header("cms"))
        if (isGroupExpanded("cms")) {
            if (cmsSubs.isEmpty()) add(ProxyListItem.EmptyNote("暂无 CMS 订阅"))
            else cmsSubs.forEach { sub ->
                add(ProxyListItem.SourceRow(2, sub.name.ifBlank { sub.url }, sub.url, ClashProxy.cmsKey(sub.url)))
            }
        }
        add(ProxyListItem.Header("web"))
        if (isGroupExpanded("web")) {
            if (webSites.isEmpty()) add(ProxyListItem.EmptyNote("暂无网页源（仅 http(s) 站源可走代理）"))
            else webSites.forEach { site ->
                add(ProxyListItem.SourceRow(3, site.name.ifBlank { site.url }, site.url, ClashProxy.webKey(site.fileName)))
            }
        }
    }
    val proxyItems = if (isFiltering) flatItems else groupedItems
    // 提示行（EmptyNote）是透明文字不占 SegItem 位置——圆角按 SegItem 行算。
    val proxySegItems = proxyItems.filter { it !is ProxyListItem.EmptyNote }
    fun proxySegPos(item: ProxyListItem): SegPos = clashPos(proxySegItems.indexOf(item), proxySegItems.size)

    Scaffold(
        topBar = { TabTopBar(title = "Clash 代理", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // ===== 服务状态 =====
            item { ClashCaption("服务") }
            item {
                AppCard(
                    shape = RoundedCornerShape(V5.CornerCard),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SegItem(
                            position = SegPos.Single,
                            icon = Icons.Outlined.Extension,
                            title = "clash 服务",
                            subtitle = statusText,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (running) {
                                AppButton(
                                    text = "停止",
                                    onClick = {
                                        // v1.3.28：启动/停止走 IO 线程——start/stop 内有
                                        // 注册表文件 IO 与摘要验算，主线程点按有 ANR 风险。
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    ServicePluginManager.stop(
                                                        context,
                                                        ClashProxy.PLUGIN_ID,
                                                    )
                                                }
                                            }
                                            r.onFailure {
                                                if (it is CancellationException) throw it
                                                AppToast.show(context, "停止失败：${it.message}")
                                            }
                                            refresh()
                                        }
                                    },
                                    variant = AppButtonVariant.Secondary,
                                )
                            } else if (installed) {
                                AppButton(
                                    text = "启动",
                                    onClick = {
                                        // v1.3.28：同上，走 IO 线程。
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    ServicePluginManager.start(
                                                        context,
                                                        ClashProxy.PLUGIN_ID,
                                                    )
                                                }
                                            }
                                            r.onFailure {
                                                if (it is CancellationException) throw it
                                                AppToast.show(context, "启动失败：${it.message}")
                                            }
                                            refresh()
                                        }
                                    },
                                )
                            }
                            AppButton(
                                text = "探活",
                                onClick = {
                                    scope.launch {
                                        val ok = runCatching {
                                            ServicePluginManager.healthCheck(context, ClashProxy.PLUGIN_ID)
                                        }.getOrDefault(false)
                                        AppToast.show(context, if (ok) "探活通过：服务可达" else "探活失败：服务不可达")
                                        refresh()
                                    }
                                },
                                variant = AppButtonVariant.Ghost,
                            )
                            // v1.3.27：未装时去市场安装（服务页没有安装入口），已装才去服务页。
                            if (!installed) {
                                AppButton(
                                    text = "去市场安装",
                                    onClick = onOpenMarketPage,
                                )
                            } else {
                                AppButton(
                                    text = "服务页",
                                    onClick = onOpenServicePage,
                                    variant = AppButtonVariant.Ghost,
                                )
                            }
                        }
                    }
                }
            }
            // ===== 订阅 =====
            item { ClashCaption("订阅") }
            item {
                AppCard(
                    shape = RoundedCornerShape(V5.CornerCard),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TechTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            placeholder = "粘贴 Clash 订阅链接",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton(
                                text = "保存",
                                onClick = {
                                    if (ClashProxy.setSubscriptionUrl(urlInput)) {
                                        AppToast.show(context, "订阅链接已保存（只存本机）")
                                    } else {
                                        AppToast.show(context, "链接不对，要以 http:// 或 https:// 开头")
                                    }
                                },
                                enabled = urlInput.isNotBlank(),
                            )
                            AppButton(
                                text = if (updating) "更新中…" else "更新订阅",
                                onClick = {
                                    scope.launch {
                                        updating = true
                                        try {
                                            val r = ClashProxy.fetchSubscription()
                                            r.onSuccess { n ->
                                                subStatus = "已更新，节点约 $n 个"
                                                AppToast.show(context, "订阅已更新，节点约 $n 个")
                                            }.onFailure { e ->
                                                if (e is CancellationException) throw e
                                                subStatus = e.message ?: "更新失败"
                                                AppToast.show(context, "更新失败：${e.message}")
                                            }
                                        } finally {
                                            updating = false
                                        }
                                    }
                                },
                                variant = AppButtonVariant.Secondary,
                                enabled = !updating && ClashProxy.subscriptionUrl().isNotBlank(),
                            )
                        }
                        Text(
                            text = subStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "订阅只存本机私有目录，不上传；更新订阅时带 Clash 客户端标识拉取（面板只认它才回配置）。服务启动后自动读订阅里的节点，挑延迟最低的用；运行中更新订阅，新节点最长 5 分钟内生效（想立刻生效就去服务页重启一下 clash）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // ===== 按源走代理 =====
            item { ClashCaption("按源走代理") }
            item {
                AppCard(
                    shape = RoundedCornerShape(V5.CornerCard),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "只代理开了开关的源，其他源一点不受影响。开了开关的源，搜索、封面、播放都走本地 clash 代理。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        // v1.3.30：搜索框（胶囊，TechTextField）+ 全部/已开启/未开启
                        // 胶囊——按源名实时过滤；过滤时分组隐藏、改出平铺结果。
                        TechTextField(
                            value = proxyQuery,
                            onValueChange = { proxyQuery = it },
                            placeholder = "搜索源名…",
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                        CapsuleTabBar(
                            titles = listOf("全部", "已开启", "未开启"),
                            selected = proxyFilter,
                            onSelect = { proxyFilter = it },
                        )
                        if (isFiltering) {
                            Text(
                                text = "共 ${proxyItems.size} 个结果",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            if (proxyItems.isEmpty()) {
                                Text(
                                    text = "没有匹配的源",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                        proxyItems.forEach { item ->
                            when (item) {
                                is ProxyListItem.Header -> {
                                    val key = item.key
                                    val headerIcon = when (key) {
                                        "py" -> Icons.Filled.Terminal
                                        "cms" -> Icons.Outlined.Link
                                        "web" -> Icons.Outlined.Extension
                                        else -> Icons.Outlined.Code
                                    }
                                    val headerName = when (key) {
                                        "py" -> "PY 规则"
                                        "cms" -> "CMS 订阅"
                                        "web" -> "网页源"
                                        else -> "JS 规则"
                                    }
                                    val headerTotal = when (key) {
                                        "py" -> pyRules.size
                                        "cms" -> cmsSubs.size
                                        "web" -> webSites.size
                                        else -> jsRules.size
                                    }
                                    val headerOn = when (key) {
                                        "py" -> pyRules.count { proxyOn.contains(ClashProxy.pyKey(it.name)) }
                                        "cms" -> cmsSubs.count { proxyOn.contains(ClashProxy.cmsKey(it.url)) }
                                        "web" -> webSites.count { proxyOn.contains(ClashProxy.webKey(it.fileName)) }
                                        else -> jsRules.count { proxyOn.contains(ClashProxy.jsKey(it.name)) }
                                    }
                                    SegItem(
                                        position = proxySegPos(item),
                                        icon = headerIcon,
                                        title = headerName,
                                        subtitle = "${headerTotal} 个 · 已开 ${headerOn}",
                                        onClick = { toggleGroup(key) },
                                        trailing = {
                                            Icon(
                                                imageVector = if (isGroupExpanded(key)) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                    )
                                }
                                is ProxyListItem.SourceRow -> {
                                    val rowIcon = when (item.kind) {
                                        1 -> Icons.Filled.Terminal
                                        2 -> Icons.Outlined.Link
                                        3 -> Icons.Outlined.Extension
                                        else -> Icons.Outlined.Code
                                    }
                                    SegItem(
                                        position = proxySegPos(item),
                                        icon = rowIcon,
                                        title = item.title,
                                        subtitle = item.subtitle,
                                        trailing = {
                                            AppSwitch(
                                                checked = proxyOn.contains(item.key),
                                                onCheckedChange = { on ->
                                                    ClashProxy.setSourceProxied(item.key, on)
                                                    when (item.kind) {
                                                        1 -> {
                                                            // PY：按源名判定，不记 host 集；
                                                            // 拨开关后重推 Python 侧代理状态。
                                                            PySourceManager.refreshPyProxyState()
                                                        }
                                                        2 -> {
                                                            if (on) {
                                                                // 订阅 URL 本身是订阅地址，不是站地址；
                                                                // 从缓存的站点 api 地址里取 host  prime。
                                                                val hosts = runCatching {
                                                                    SubSiteStore.getCached(context, item.subtitle)?.sites
                                                                        ?.mapNotNull { ClashProxy.hostOf(it.api) }
                                                                        .orEmpty()
                                                                }.getOrDefault(emptyList())
                                                                if (hosts.isNotEmpty()) ClashProxy.registerHosts(item.key, hosts)
                                                            }
                                                        }
                                                        3 -> {
                                                            // 网页源：subtitle 就是站源地址，取它的 host prime。
                                                            if (on) {
                                                                ClashProxy.hostOf(item.subtitle)?.let {
                                                                    ClashProxy.registerHosts(item.key, listOf(it))
                                                                }
                                                            }
                                                        }
                                                        else -> {
                                                            if (on) {
                                                                DrpySourceManager.ruleHost(item.subtitle)?.let {
                                                                    ClashProxy.registerHosts(item.key, listOf(it))
                                                                }
                                                            }
                                                        }
                                                    }
                                                    proxyOn = ClashProxy.enabledKeys()
                                                    proxiedHosts = ClashProxy.proxiedHosts()
                                                },
                                            )
                                        },
                                    )
                                }
                                is ProxyListItem.EmptyNote -> {
                                    Text(
                                        text = item.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                        // v1.4.4：生效域名快照——已开代理的源登记的 host 并集（ClashProxy.proxiedHosts）。
                        // 让“走没走代理”可查：命中这里的域名才走代理；clash 未运行时全部直连（见上方服务状态）。
                        Text(
                            text = "生效域名（${proxiedHosts.size} 个）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                        )
                        if (proxiedHosts.isEmpty()) {
                            Text(
                                text = "暂无——还没有源开代理。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        } else {
                            proxiedHosts.sorted().forEach { host ->
                                Text(
                                    text = host,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                )
                            }
                            if (!running) {
                                Text(
                                    text = "clash 未运行，这些域名当前实际直连。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                        // v1.3.33：PHP 列为灰态“不支持”——PHP 走原生 php，本版不走代理；
                        // 只留一句小字用户无从发现，开关列表里必须有这行灰态，拨了开关也不会生效。
                        // v1.4.4：网页源已进上面的开关列表（webKey），这里只剩 PHP。
                        Text(
                            text = "不支持走代理",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                        )
                        SegItem(
                            position = SegPos.Single,
                            icon = Icons.Outlined.Code,
                            title = "PHP 规则",
                            subtitle = "走原生 php，暂不支持走代理",
                            enabled = false,
                            trailing = {
                                Text(
                                    text = "不支持",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                        Text(
                            text = "网页源开关只对其接口请求生效；在嗅探浏览器里打开的页面本身不走代理。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

private fun clashPos(index: Int, size: Int): SegPos = when {
    size <= 1 -> SegPos.Single
    index == 0 -> SegPos.First
    index == size - 1 -> SegPos.Last
    else -> SegPos.Middle
}

/**
 * v1.3.30：按源走代理列表的行——组头 / 源行 / 空组提示行。
 * SourceRow.kind：0=JS 规则 1=PY 规则 2=CMS 订阅 3=网页源；subtitle 兼作查 host/
 * 查缓存站点的键（JS/PY 为规则文件名，CMS 为订阅地址，网页源为站源地址）。
 */
private sealed interface ProxyListItem {
    data class Header(val key: String) : ProxyListItem
    data class SourceRow(val kind: Int, val title: String, val subtitle: String, val key: String) : ProxyListItem
    data class EmptyNote(val text: String) : ProxyListItem
}

@Composable
private fun ClashCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 8.dp),
    )
}
