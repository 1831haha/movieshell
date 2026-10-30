package com.movieshell.ui.market

import android.os.SystemClock
import com.movieshell.ui.components.AppDropdownMenu
import com.movieshell.ui.components.AppSwitch
import com.movieshell.ui.components.AppToast
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.repo.LogBus
import com.movieshell.ui.components.TabTopBar
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
import com.movieshell.ui.components.TechDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.movieshell.ui.theme.V4
import com.movieshell.ui.theme.V4Type

/**
 * 插件市场页（v0.23.0）。
 * - 分类过滤：全部 / 环境 / 应用
 * - 搜索过滤；不支持的 type 默认隐藏（设置里可开"显示不兼容插件"，置灰禁装）
 * - 卡片点击进详情页；卡片上可直接安装/更新/卸载
 *
 * v0.27.0 Soft Tech 换皮：卡片走 AppCard，筛选走 AppChip，搜索走 AppTextField，
 * 三态走 EmptyState / LoadingSkeleton。过滤与按钮状态机逻辑零变更。
 * v0.134.0：多市场归并——注册表里启用的市场并发拉取、按插件 id 去重
 * （注册表顺序在前者胜出），头部信息条汇总市场/插件/可更新数，市场增删改
 * 与 GitHub 加速代理收敛到「管理市场」底部弹窗（仿 DS 多源管理）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginMarketScreen(
    onBack: () -> Unit = {},
    onDetail: (String, String) -> Unit = { _, _ -> },
    // v0.127.0：插件中心页签嵌入模式——不渲染自己的顶栏（中心页统一顶栏），
    // 标题/刷新降为内容区首行；独立路由进来时行为不变。
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val sources by PluginStore.marketSources.collectAsState()
    val showIncompatible by PluginStore.showIncompatible.collectAsState()
    val enabledSources = remember(sources) { sources.filter { it.enabled } }

    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") } // ""=全部 env/app
    // v0.134.0：搜索行默认收起（信息条搜索图标切换）；状态筛选 0 全部/1 可更新/2 已安装/3 未安装
    var searchOpen by remember { mutableStateOf(false) }
    var statusFilter by remember { mutableStateOf(0) }
    var archFilter by remember { mutableStateOf("") }
    var showManage by remember { mutableStateOf(false) }
    // v0.134.0：按市场地址存各自的 MarketInfo，归并时按注册表顺序合并去重。
    // remember 初值同步读磁盘缓存填入（秒开，与旧版缓存优先口径一致）。
    var infosByUrl by remember {
        PluginStore.init(context)
        mutableStateOf(
            PluginStore.marketSources.value.filter { it.enabled }
                .mapNotNull { src ->
                    PluginMarket.loadCached(context, src.url)?.let { src.url to it }
                }
                .toMap(),
        )
    }
    // v0.134.0：首轮拉取是否结束（loading 只在 merged 为空且首轮未完成时成立）
    var firstLoadDone by remember { mutableStateOf(false) }
    // v0.96.1：加载超过 8 秒还没回来→提示"正在走镜像"，免得用户以为卡死（之前三张骨架屏干转被当成加载不出来）
    var loadingSlow by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var installedTick by remember { mutableStateOf(0) }
    var busyId by remember { mutableStateOf<String?>(null) }
    // v0.27.2：一键安装进度弹窗态
    var installProgress by remember { mutableStateOf<InstallProgressUi?>(null) }

    val installedMap = remember(installedTick) {
        PluginStore.listInstalled().associateBy { it.id }
    }
    // v0.134.0：多源归并——注册表顺序在前者胜出，插件自带 sourceMarket 标来源
    val merged = remember(infosByUrl, sources) {
        enabledSources.flatMap { infosByUrl[it.url]?.plugins.orEmpty() }
            .distinctBy { it.id }
    }
    val loading = merged.isEmpty() && !firstLoadDone
    LaunchedEffect(loading) {
        loadingSlow = false
        if (loading) {
            delay(8000)
            loadingSlow = true
        }
    }
    val installedCount = merged.count { installedMap.containsKey(it.id) }
    val updatableCount = merged.count { p ->
        installedMap[p.id]?.let { PluginMarket.isUpdatable(p, it.version) } == true
    }
    // v0.134.0：架构筛选选项由数据派生——全是单一架构时不渲染这一行（有意为之）
    val archOptions = remember(merged) {
        merged.flatMap { it.abis }.distinct().sorted()
    }

    // v0.27.4：市场拉取走 LaunchedEffect（跟随组合生命周期，离开页面自动取消；
    // 刷新按钮只递增 reloadTick，上一轮未完成会被自动取消）
    var reloadTick by remember { mutableStateOf(0) }
    fun reload() {
        reloadTick++
    }
    // v0.136.0：各市场最近一次成功拉取的时刻（elapsedRealtime）与已消费的
    // reloadTick——拉取 effect 靠这两者做新鲜度收敛（见 effect 内注释）
    val fetchedAt = remember { mutableMapOf<String, Long>() }
    var consumedReloadTick by remember { mutableStateOf(0) }

    // v0.134.0：多源并发拉取——先给缺失的 url 补磁盘缓存（切源/启停后秒显），
    // 再 coroutineScope + async 并发 fetchRemote，逐个完成即增量并入 infosByUrl；
    // 全部失败且无缓存走旧错误态，部分失败只 Toast、保留已显示的缓存。
    // v0.136.0：新鲜度收敛——v0.134.0 时管理弹窗每次变更（开关/改名/增删）
    // 都经 onChanged 无条件 reload()，effect 对全部启用市场整表重拉：真机日志
    // 20 秒内连拉 6 轮（加市场时新市场刚被 validateMarket 拉过又立刻重拉）。
    // 现改为：本轮只拉「从未成功拉过、或距上次成功拉取已超
    // MARKET_REFETCH_MIN_MS」的市场；手动刷新（刷新按钮/错误重试，reloadTick
    // 变化）仍强制全量；管理变更只重读缓存种子（onChanged），不再触发重拉。
    LaunchedEffect(enabledSources.map { it.url }, reloadTick) {
        error = null
        val missing = enabledSources.filter { it.url !in infosByUrl }
        if (missing.isNotEmpty()) {
            val seeded = missing.mapNotNull { src ->
                PluginMarket.loadCached(context, src.url)?.let { src.url to it }
            }
            if (seeded.isNotEmpty()) infosByUrl = infosByUrl + seeded
        }
        if (enabledSources.isEmpty()) {
            consumedReloadTick = reloadTick
            firstLoadDone = true
            return@LaunchedEffect
        }
        val forced = reloadTick != consumedReloadTick
        consumedReloadTick = reloadTick
        val now = SystemClock.elapsedRealtime()
        val targets = enabledSources.filter { src ->
            val last = fetchedAt[src.url]
            forced || last == null || now - last >= MARKET_REFETCH_MIN_MS
        }
        if (targets.isEmpty()) {
            firstLoadDone = true
            return@LaunchedEffect
        }
        // v0.218.0（S25）：多市场并发拉取，失败计数与首错用原子容器——
        // 原来多协程直接改 var，失败数可能少算、首错可能被后到的覆盖。
        val failCount = AtomicInteger(0)
        val firstErrorRef = AtomicReference<String?>(null)
        coroutineScope {
            val jobs = targets.map { src ->
                async {
                    runCatching { PluginMarket.fetchRemote(context, src.url) }
                        .onSuccess {
                            fetchedAt[src.url] = SystemClock.elapsedRealtime()
                            infosByUrl = infosByUrl + (src.url to it)
                        }
                        .onFailure { e ->
                            if (e is CancellationException) throw e
                            failCount.incrementAndGet()
                            firstErrorRef.compareAndSet(null, e.message ?: e.toString())
                            LogBus.e("PluginMarket", "市场拉取失败(${src.name})：${e.message}")
                        }
                }
            }
            jobs.forEach { it.await() }
        }
        firstLoadDone = true
        val mergedNow = enabledSources
            .flatMap { infosByUrl[it.url]?.plugins.orEmpty() }
            .distinctBy { it.id }
        val failCountNow = failCount.get()
        val firstError = firstErrorRef.get()
        if (failCountNow > 0) {
            if (mergedNow.isEmpty()) {
                error = firstError
                // v0.25.0：超时/失败给明确中文 Toast，不只记日志
                AppToast.show(context, "插件市场加载失败：$firstError", long = true)
            } else {
                AppToast.show(context, "$failCountNow 个市场刷新失败，已显示缓存", long = false)
            }
        }
    }

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

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        topBar = {
            if (!embedded) {
                TabTopBar(
                    title = "插件市场",
                    onBack = onBack,
                )
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = V4.Gutter),
        ) {
            // v0.134.0：头部信息条（embedded 与否都渲染）——汇总数 + 刷新 + 管理 + 搜索开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val countsText = "${sources.size} 个市场 · ${merged.size} 个插件" +
                    if (updatableCount > 0) " · $updatableCount 个可更新" else ""
                Text(
                    text = countsText,
                    style = V4Type.Body,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
                TextButton(onClick = { showManage = true }) { Text("管理") }
                IconButton(onClick = { searchOpen = !searchOpen }) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                }
            }
            if (searchOpen) {
                AppTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索插件",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            // v0.134.0：状态筛选 chips（数字随 merged/安装态实时派生）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppChip(text = "全部", selected = statusFilter == 0, onClick = { statusFilter = 0 })
                AppChip(
                    text = "可更新 $updatableCount",
                    selected = statusFilter == 1,
                    onClick = { statusFilter = 1 },
                )
                AppChip(
                    text = "已安装 $installedCount",
                    selected = statusFilter == 2,
                    onClick = { statusFilter = 2 },
                )
                AppChip(
                    text = "未安装 ${merged.size - installedCount}",
                    selected = statusFilter == 3,
                    onClick = { statusFilter = 3 },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppChip(text = "全部", selected = category.isEmpty(), onClick = { category = "" })
                AppChip(text = "环境", selected = category == "env", onClick = { category = "env" })
                AppChip(text = "应用", selected = category == "app", onClick = { category = "app" })
            }
            // v0.134.0：架构筛选——仅当归并结果含 2 种以上架构时才渲染这一行
            if (archOptions.size >= 2) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppChip(
                        text = "全部架构",
                        selected = archFilter.isEmpty(),
                        onClick = { archFilter = "" },
                    )
                    archOptions.forEach { abi ->
                        AppChip(
                            text = abi,
                            selected = archFilter == abi,
                            onClick = { archFilter = abi },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                sources.isEmpty() -> {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.Store,
                        title = "还没有市场",
                        hint = "添加一个市场地址后即可浏览插件",
                        actionText = "添加市场",
                        onAction = { showManage = true },
                    )
                }
                enabledSources.isEmpty() -> {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.Store,
                        title = "市场全部已停用",
                        hint = "在管理里开启至少一个市场",
                        actionText = "管理市场",
                        onAction = { showManage = true },
                    )
                }
                loading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // v0.96.1：加载态给明确文案，骨架屏不再"无声干转"
                        Text(
                            text = if (loadingSlow) "网络较慢，正在尝试镜像线路，请稍候…"
                            else "正在加载插件市场…",
                            style = V4Type.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        repeat(3) {
                            LoadingSkeleton(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(148.dp),
                                shape = RoundedCornerShape(V4.RField),
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
                    val shown = merged
                        .filter { showIncompatible || it.isSupported() }
                        .filter { category.isEmpty() || it.category == category }
                        .filter {
                            query.isBlank() ||
                                it.name.contains(query, true) ||
                                it.id.contains(query, true) ||
                                it.desc.contains(query, true)
                        }
                        .filter { p ->
                            when (statusFilter) {
                                1 -> installedMap[p.id]?.let {
                                    PluginMarket.isUpdatable(p, it.version)
                                } == true
                                2 -> installedMap.containsKey(p.id)
                                3 -> !installedMap.containsKey(p.id)
                                else -> true
                            }
                        }
                        .filter {
                            archFilter.isEmpty() || it.abis.isEmpty() || archFilter in it.abis
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
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(shown, key = { it.id }) { p ->
                                PluginCard(
                                    plugin = p,
                                    installedVersion = installedMap[p.id]?.version,
                                    busy = busyId == p.id,
                                    onDetail = { onDetail(p.id, p.sourceMarket) },
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
    if (showManage) {
        ManageMarketsSheet(
            sources = sources,
            infosByUrl = infosByUrl,
            onDismiss = { showManage = false },
            onChanged = {
                // v0.136.0：数据变更后只重读各启用源缓存种子（增删/改址立即反映
                // 到归并列表）；是否补拉由 effect 的新鲜度收敛决定（新地址从未
                // 拉过会被拉一次，已拉过的在间隔内不重拉），不再无条件整表重拉
                infosByUrl = PluginStore.marketSources.value.filter { it.enabled }
                    .mapNotNull { src ->
                        PluginMarket.loadCached(context, src.url)?.let { src.url to it }
                    }
                    .toMap()
            },
        )
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
    val hasUpdate = PluginMarket.isUpdatable(plugin, installedVersion)
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
                    Text(text = plugin.name, style = V4Type.RowTitle)
                    if (plugin.author.isNotBlank()) {
                        Text(
                            text = plugin.author,
                            style = V4Type.Meta,
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
                    style = V4Type.Meta,
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

/** v0.136.0：同一市场两次网络拉取的最小间隔（会话内新鲜度收敛，手动刷新除外）。 */
private const val MARKET_REFETCH_MIN_MS = 60_000L

/** 添加/编辑市场表单的编辑目标：null=未打开，Add=新增，Edit=编辑既有市场。 */
private sealed interface MarketEditTarget {
    data object Add : MarketEditTarget
    data class Edit(val source: PluginStore.MarketSource) : MarketEditTarget
}

/**
 * v0.134.0：管理市场底部弹窗——市场注册表的增删改/启停 + GitHub 加速代理。
 * 数据变更统一走 onChanged 回到市场页重 seed 缓存；是否补拉由市场页
 * effect 的新鲜度收敛决定（v0.136.0），变更不再无条件整表重拉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageMarketsSheet(
    sources: List<PluginStore.MarketSource>,
    infosByUrl: Map<String, MarketInfo>,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editTarget by remember { mutableStateOf<MarketEditTarget?>(null) }
    var deleteFor by remember { mutableStateOf<PluginStore.MarketSource?>(null) }
    var validating by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(
            topStart = V4.RField,
            topEnd = V4.RField,
        ),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "管理市场", style = V4Type.RowTitle)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { editTarget = MarketEditTarget.Add }) {
                    Icon(Icons.Filled.Add, contentDescription = "添加市场")
                }
            }
            sources.forEach { src ->
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = src.name, style = V4Type.Body)
                                Text(
                                    text = src.url,
                                    style = V4Type.Meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            AppSwitch(
                                checked = src.enabled,
                                onCheckedChange = {
                                    PluginStore.setMarketEnabled(context, src.id, it)
                                    onChanged()
                                },
                            )
                            var menuOpen by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                                }
                                AppDropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("编辑名称/地址") },
                                        onClick = {
                                            menuOpen = false
                                            editTarget = MarketEditTarget.Edit(src)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("复制地址") },
                                        onClick = {
                                            menuOpen = false
                                            val cm = context.getSystemService(
                                                android.content.ClipboardManager::class.java,
                                            )
                                            cm.setPrimaryClip(
                                                android.content.ClipData.newPlainText(
                                                    "market", src.url,
                                                ),
                                            )
                                            AppToast.show(context, "地址已复制", long = false)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "删除市场",
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            deleteFor = src
                                        },
                                    )
                                }
                            }
                        }
                        // 副行：插件数（内存或现读缓存）+ 缓存时间，缺失部分不拼
                        val pluginCount = infosByUrl[src.url]?.plugins?.size
                            ?: PluginMarket.loadCached(context, src.url)?.plugins?.size
                        val cachedAt = PluginMarket.cacheUpdatedAt(context, src.url)
                        val subText = when {
                            pluginCount == null && cachedAt == null -> "暂无缓存"
                            else -> listOfNotNull(
                                pluginCount?.let { "$it 个插件" },
                                cachedAt?.let {
                                    "缓存于 " + SimpleDateFormat(
                                        "HH:mm", Locale.CHINA,
                                    ).format(Date(it))
                                },
                            ).joinToString(" · ")
                        }
                        Text(
                            text = subText,
                            style = V4Type.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = "停用的市场不参与拉取与归并；删除仅移除列表项与缓存。",
                style = V4Type.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // GitHub 加速代理：单一入口、全 App 生效（数据层按域作用于拉取与下载）
            Text(text = "GitHub 加速代理（作用于 GitHub 域直链）", style = V4Type.RowTitle)
            val githubProxy by PluginStore.githubProxy.collectAsState()
            AppTextField(
                value = githubProxy,
                onValueChange = { PluginStore.setGithubProxy(context, it) },
                placeholder = "如 https://gh-proxy.com/，留空 = 直连",
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "仅作用于 raw.githubusercontent.com 与 github.com 域的市场拉取与插件下载，存在一处、全 App 生效。",
                style = V4Type.Meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 添加/编辑表单：先本地协议校验，再试拉校验市场地址，成功才落注册表
    editTarget?.let { target ->
        val isAdd = target is MarketEditTarget.Add
        val editing = (target as? MarketEditTarget.Edit)?.source
        var nameInput by remember(target) { mutableStateOf(editing?.name ?: "") }
        var urlInput by remember(target) { mutableStateOf(editing?.url ?: "") }
        TechDialog(
            onDismissRequest = { if (!validating) editTarget = null },
            title = if (isAdd) "添加市场" else "编辑市场",
            confirmText = if (validating) "校验中…" else "保存",
            confirmEnabled = !validating,
            onConfirm = {
                val url = urlInput.trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    AppToast.show(context, "地址要以 http:// 或 https:// 开头", long = false)
                    return@TechDialog
                }
                validating = true
                scope.launch {
                    runCatching { PluginMarket.validateMarket(url) }
                        .onSuccess {
                            val ok = if (isAdd) {
                                PluginStore.addMarketSource(context, nameInput, url)
                            } else {
                                PluginStore.updateMarketSource(
                                    context, editing!!.id, nameInput, url,
                                )
                            }
                            validating = false
                            if (!ok) {
                                AppToast.show(context, if (isAdd) "该地址已在列表中"
                                    else "名称/地址为空或地址已被其他市场占用", long = false)
                            } else {
                                if (editing != null && editing.url != url) {
                                    PluginMarket.deleteCache(context, editing.url)
                                }
                                editTarget = null
                                onChanged()
                            }
                        }
                        .onFailure { e ->
                            validating = false
                            AppToast.show(context, "保存失败：${e.message}", long = true)
                        }
                }
            },
            dismissText = "取消",
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                AppTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    placeholder = "名称",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                AppTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = "market.json 地址",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "可添加 DsPlayer 官方市场（实验性）：${PluginStore.DSPLAYER_MARKET_URL}",
                    style = V4Type.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // 删除确认：删注册表条目 + 本地缓存，列表插件随归并结果消失
    deleteFor?.let { src ->
        TechDialog(
            onDismissRequest = { deleteFor = null },
            title = "删除市场？",
            subtitle = "将删除「${src.name}」及其本地缓存，列表里的插件会随之消失。",
            confirmText = "删除",
            onConfirm = {
                deleteFor = null
                PluginStore.removeMarketSource(context, src.id)
                PluginMarket.deleteCache(context, src.url)
                onChanged()
            },
            dismissText = "取消",
        ) {}
    }
}
