package com.movieshell.ui.home

import com.movieshell.ui.theme.V5

import com.movieshell.ui.components.AppDropdownMenu
import com.movieshell.ui.components.AppToast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.data.repo.ApiSource
import com.movieshell.data.repo.RuleInfo
import com.movieshell.data.repo.ApiSourceStore
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.HomeSettings
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PhpSourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SourceStateStore
import com.movieshell.data.repo.StoragePaths
import com.movieshell.data.repo.SubCurrent
import com.movieshell.data.repo.SubSiteRecord
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.ui.components.V5Chip
import com.movieshell.ui.components.StatusRing
import com.movieshell.ui.components.StatusRingState
import com.movieshell.ui.components.faviconUrlForHost
import com.movieshell.ui.components.hdIconCandidatesForHost
import com.movieshell.ui.components.hostFromUrl
import com.movieshell.ui.components.siteLogoUrl
import java.io.File
import com.movieshell.ui.theme.LocalBrandGradient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.109.0 B7：首页换源器改 DsPlayer 式小图标模式。
 *
 * 原来是居中 Dialog + 行列表（v0.11.8），现改为底部弹窗内：
 * 顶部标题"选择片源 + 共 N 个源"、搜索框（按源名过滤）、小字类型筛选 chips
 * （按源类型口径 cms/drpy/py 等）、4 列小图标卡片网格（图标+名），点选即切换。
 * 搜索文本 × 类型筛选 × 点选切换三者联动。
 *
 * 数据源与切换逻辑复用 v0.7.1 的 SourceSwitchSheet（只改交互层）：
 * 本地规则（DrpySourceManager/PySourceManager/PhpSourceManager）+ 订阅站源（ApiSourceStore/SubSiteStore）。
 * 点选卡片：调现有切换逻辑 → onChanged（HomeScreen.refreshDrpyState(fromUserSwitch=true)
 * 即时重载首页）→ onDismiss 关弹窗，三者都发生。
 *
 * v0.140.0：图标由圆形改方形紧凑布局（44dp 圆角方块、间距收紧）；
 * 图标匹配加第四级：站点 host 的 favicon.ico（本地规则读文件头取 host，
 * 订阅站源取 api 的 host），取不到或加载失败仍回退首字。
 *
 * v0.153.0：打开即定位到当前源——131 个源里当前源靠后时不再从头翻；
 * 只在无搜索、无类型筛选的默认列表下定位一次（筛选会把当前源滤掉，强制定位无意义）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourcePickerSheet(
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    // v0.17.0：异步加载，避免主线程扫文件卡顿（复用旧逻辑）
    var cur by remember { mutableStateOf<SubCurrent?>(null) }
    var drpyRules by remember { mutableStateOf<List<RuleInfo>>(emptyList()) }
    var pyRules by remember { mutableStateOf<List<RuleInfo>>(emptyList()) }
    var phpRules by remember { mutableStateOf<List<RuleInfo>>(emptyList()) }
    var drpyHosts by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var pyHosts by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var phpHosts by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var drpyActiveName by remember { mutableStateOf<String?>(null) }
    var pyActiveName by remember { mutableStateOf<String?>(null) }
    var phpActiveName by remember { mutableStateOf<String?>(null) }
    var subSites by remember { mutableStateOf<List<Pair<ApiSource, SubSiteRecord>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // v0.271.0（T25）：切源单飞——快速连点两个源时，旧一单取消，不留假失败提示。
    var switchJob by remember { mutableStateOf<Job?>(null) }

    /**
     * 只读重读全部状态（本地规则重扫目录 + 订阅只读缓存，不走网络），
     * 首载与刷新后共用；返回按 PickerItem id 口径去重后的总源数。
     */
    suspend fun reloadFromCache(): Int = withContext(Dispatchers.IO) {
        cur = SubSiteStore.getCurrent(context)
        val newDrpy = DrpySourceManager.listRules()
        val newPy = PySourceManager.listRules()
        val newPhp = PhpSourceManager.listRules()
        drpyRules = newDrpy
        pyRules = newPy
        phpRules = newPhp
        drpyActiveName = DrpySourceManager.activeRuleName()
        pyActiveName = PySourceManager.activeRuleName()
        phpActiveName = PhpSourceManager.activeRuleName()
        val newSubSites = ApiSourceStore.load(context).filter { it.enabled }.flatMap { sub ->
            val sites = SubSiteStore.getCached(context, sub.url)?.sites.orEmpty()
            sites.map { sub to it }
        }
        subSites = newSubSites
        // v0.140.0：本地规则的站点 host（文件头取，IO 线程做，不阻塞主线程）
        drpyHosts = newDrpy.associate { it.name to ruleFileHost(StoragePaths.jsDir(context), it.name) }
        pyHosts = newPy.associate { it.name to ruleFileHost(StoragePaths.pyDir(context), it.name) }
        phpHosts = newPhp.associate { it.name to ruleFileHost(PhpSourceManager.rulesDir(context), it.name) }
        val ids = HashSet<String>()
        newDrpy.forEach { ids += "local-drpy:${it.name}" }
        newPy.forEach { ids += "local-py:${it.name}" }
        newPhp.forEach { ids += "local-php:${it.name}" }
        newSubSites.forEach { (sub, rec) -> ids += "sub:${sub.url}:${rec.key}" }
        ids.size
    }

    LaunchedEffect(Unit) {
        reloadFromCache()
        loading = false
    }

    /**
     * 标题行刷新按钮：逐个 enabled 订阅走 SubSiteStore.refresh（现成单一入口，
     * 失败不写缓存、旧列表天然保留，单个失败不中断其余），最后统一只读重读。
     * 不关弹窗、不改当前选中源。
     */
    fun onRefreshClick() {
        if (refreshing || loading) return
        refreshing = true
        scope.launch {
            var subTotal = 0
            var failCount = 0
            withContext(Dispatchers.IO) {
                val subs = ApiSourceStore.load(context).filter { it.enabled }
                subTotal = subs.size
                subs.forEach { sub ->
                    val ok = runCatching { SubSiteStore.refresh(context, sub.url) }
                        .getOrNull()?.isSuccess == true
                    if (!ok) failCount++
                }
            }
            val total = reloadFromCache()
            refreshing = false
            LogBus.i(
                "SourcePicker",
                "换源弹窗刷新：订阅 $subTotal 个，失败 $failCount 个，共 $total 个源",
            )
            AppToast.show(context, if (failCount == 0) "已刷新，共 $total 个源"
                else "已刷新，共 $total 个源；$failCount 个订阅刷新失败，已保留旧列表", long = false)
        }
    }

    fun pickLocal(isDrpy: Boolean, name: String) {
        // v0.271.0（T25）：Drpy 切源走 suspend 版，文件读与引擎建在 IO 线程；
        // 整个切换序列放 scope.launch，失败回滚里的 Drpy 恢复同样走 suspend 版。
        switchJob?.cancel()
        switchJob = scope.launch {
        // v0.217.0（F51）：与站源页同款——消费 setActiveRule 的 Boolean，
        // 失败时不写激活态、不关弹窗，让用户换一个再点。
        val prevDrpy = DrpySourceManager.activeRuleName()
        val prevPy = PySourceManager.activeRuleName()
        val prevPhp = PhpSourceManager.activeRuleName()
        SubSiteStore.clearCurrent(context)
        if (isDrpy) {
            PySourceManager.setActiveRule(null)
            PhpSourceManager.setActiveRule(null)
            if (!DrpySourceManager.setActiveRuleAsync(name)) {
                if (prevPy != null) PySourceManager.setActiveRule(prevPy)
                else if (prevPhp != null) PhpSourceManager.setActiveRule(prevPhp)
                else if (prevDrpy != null && prevDrpy != name) DrpySourceManager.setActiveRuleAsync(prevDrpy)
                AppToast.show(context, "切换失败：规则文件不存在或加载失败", long = false)
                return@launch
            }
            SourceStateStore.setRuleEnabled(context, "js", name, true)
            SourceStateStore.saveActiveRule(context, "js", name)
        } else {
            DrpySourceManager.setActiveRule(null)
            PhpSourceManager.setActiveRule(null)
            if (!PySourceManager.setActiveRule(name)) {
                if (prevDrpy != null) DrpySourceManager.setActiveRuleAsync(prevDrpy)
                else if (prevPhp != null) PhpSourceManager.setActiveRule(prevPhp)
                else if (prevPy != null && prevPy != name) PySourceManager.setActiveRule(prevPy)
                AppToast.show(context, "切换失败：规则文件不存在或加载失败", long = false)
                return@launch
            }
            SourceStateStore.setRuleEnabled(context, "py", name, true)
            SourceStateStore.saveActiveRule(context, "py", name)
        }
        onChanged()
        onDismiss()
        }
    }

    /** v0.147.0：本地 PHP 规则与 drpy/py 平齐进换源弹窗，点选即切换。 */
    fun pickPhp(name: String) {
        // v0.271.0（T25）：回滚里的 Drpy 恢复走 suspend 版，PHP 本身只验文件存在，不读文件。
        switchJob?.cancel()
        switchJob = scope.launch {
        val prev = PhpSourceManager.activeRuleName()
        val prevDrpy = DrpySourceManager.activeRuleName()
        val prevPy = PySourceManager.activeRuleName()
        SubSiteStore.clearCurrent(context)
        DrpySourceManager.setActiveRule(null)
        PySourceManager.setActiveRule(null)
        if (!PhpSourceManager.setActiveRule(name)) {
            // 先恢复切换前正在用的引擎（drpy／py／php 同一时间只一个在用）。
            if (prevDrpy != null) DrpySourceManager.setActiveRuleAsync(prevDrpy)
            else if (prevPy != null) PySourceManager.setActiveRule(prevPy)
            else if (prev != null && prev != name) PhpSourceManager.setActiveRule(prev)
            AppToast.show(context, "选中失败：规则文件不存在或加载失败", long = false)
            return@launch
        }
        SourceStateStore.setRuleEnabled(context, "php", name, true)
        SourceStateStore.saveActiveRule(context, "php", name)
        onChanged()
        onDismiss()
        }
    }

    fun pickSub(sub: ApiSource, rec: SubSiteRecord) {
        // 订阅优先：先清空本地规则激活态，再设订阅当前源
        DrpySourceManager.setActiveRule(null)
        PySourceManager.setActiveRule(null)
        PhpSourceManager.setActiveRule(null)
        SourceStateStore.clearActiveRule(context)
        SubSiteStore.setCurrent(context, sub.url, rec.key)
        onChanged()
        onDismiss()
    }

    // 统一条目：本地 drpy 规则 / 本地 py 规则 / 本地 php 规则 / 订阅站点
    val items: List<PickerItem> = remember(cur, drpyRules, pyRules, phpRules, drpyHosts, pyHosts, phpHosts, drpyActiveName, pyActiveName, phpActiveName, subSites) {
        val list = mutableListOf<PickerItem>()
        drpyRules.forEach { rule ->
            val name = rule.title.ifBlank { rule.name }
            val host = drpyHosts[rule.name]
            list.add(
                PickerItem(
                    id = "local-drpy:${rule.name}",
                    name = name,
                    kind = SubSiteStore.KIND_DRPY,
                    logoUrl = siteLogoUrl(rule.name, name, null)
                        ?: host?.let { faviconUrlForHost(it) },
                    isCurrent = cur == null && rule.name == drpyActiveName,
                    onPick = { pickLocal(true, rule.name) },
                ),
            )
        }
        pyRules.forEach { rule ->
            val name = rule.title.ifBlank { rule.name }
            val host = pyHosts[rule.name]
            list.add(
                PickerItem(
                    id = "local-py:${rule.name}",
                    name = name,
                    kind = SubSiteStore.KIND_PY,
                    logoUrl = siteLogoUrl(rule.name, name, null)
                        ?: host?.let { faviconUrlForHost(it) },
                    isCurrent = cur == null && rule.name == pyActiveName,
                    onPick = { pickLocal(false, rule.name) },
                ),
            )
        }
        // v0.147.0：本地 PHP 规则与 py 平齐；kind 复用 TYPE4，走现成“PHP”筛选 chip
        phpRules.forEach { rule ->
            val name = rule.title.ifBlank { rule.name }
            val host = phpHosts[rule.name]
            list.add(
                PickerItem(
                    id = "local-php:${rule.name}",
                    name = name,
                    kind = SubSiteStore.KIND_TYPE4,
                    logoUrl = siteLogoUrl(rule.name, name, null)
                        ?: host?.let { faviconUrlForHost(it) },
                    isCurrent = cur == null && rule.name == phpActiveName,
                    onPick = { pickPhp(rule.name) },
                ),
            )
        }
        subSites.forEach { (sub, rec) ->
            val name = rec.name.ifBlank { rec.key }
            val unsupported = rec.kind == SubSiteStore.KIND_UNSUPPORTED
            val c = cur
            list.add(
                PickerItem(
                    id = "sub:${sub.url}:${rec.key}",
                    name = name,
                    kind = rec.kind,
                    logoUrl = siteLogoUrl(rec.key, name, rec.ext)
                        ?: hostFromUrl(rec.api)?.let { faviconUrlForHost(it) },
                    isCurrent = c != null && c.subUrl == sub.url && c.siteKey == rec.key,
                    unsupportedReason = if (unsupported) rec.reason else null,
                    onPick = {
                        if (unsupported) {
                            AppToast.show(context, rec.reason, long = false)
                        } else {
                            pickSub(sub, rec)
                        }
                    },
                ),
            )
        }
        // v0.133.0：id 全带前缀不会裸撞，但同一订阅被重复添加或多仓展开重复时，
        // sub 条目会以同 url 加同 site key 的全串重复，构建时按 id 去重兜底
        list.distinctBy { it.id }
    }

    // 类型筛选 chips：只出现实际存在的类型，顺序固定
    val kindOrder = listOf(
        SubSiteStore.KIND_CMS, SubSiteStore.KIND_DRPY, SubSiteStore.KIND_PY,
        SubSiteStore.KIND_TYPE4, SubSiteStore.KIND_SPIDER,
    )
    val kindsPresent = remember(items) { kindOrder.filter { k -> items.any { it.kind == k } } }
    var query by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf<String?>(null) }
    // v0.182.0：三种排列类型，搜索框右边的小宫格按钮点一下换一种：
    // 0=截图式 2 列大卡（默认），1=原来 5 列小图标，2=列表。
    // v0.184.0：扩到 6 种，点一下依次换，长按直接打开选择菜单：
    // 0=2 列大卡，1=5 列图标，2=列表，3=1 列宽卡，4=3 列中卡，5=紧凑列表。
    // v0.188.0：布局选择走 HomeSettings 持久化（与首页布局同机制），
    // 关弹窗重开、进程重建都不再回默认大卡。
    val layoutMode by HomeSettings.pickerLayout.collectAsState()
    var showLayoutMenu by remember { mutableStateOf(false) }
    val layoutNames = listOf("大卡", "图标", "列表", "宽卡", "中卡", "紧凑")
    val layoutIcons = listOf(
        Icons.Filled.GridView, Icons.Filled.GridView, Icons.Filled.List,
        Icons.Filled.List, Icons.Filled.GridView, Icons.Filled.List,
    )
    // 搜索文本 × 类型筛选联动
    val visible = remember(items, query, kindFilter) {
        items.filter { item ->
            (kindFilter == null || item.kind == kindFilter) &&
                (query.isBlank() || item.name.contains(query.trim(), ignoreCase = true))
        }
    }

    // v0.153.0：打开即定位到当前源。网格状态 + 只定位一次的标记（弹窗每次打开都是
    // 新组合，标记天然复位；搜索/筛选改动会取消在途滚动，标记只在滚动完成后才置位，
    // 避免一次取消就永久丢定位）。
    val gridState: LazyGridState = rememberLazyGridState()
    var scrolledToCurrent by remember { mutableStateOf(false) }
    LaunchedEffect(loading, visible) {
        if (loading || scrolledToCurrent) return@LaunchedEffect
        if (query.isNotBlank() || kindFilter != null) return@LaunchedEffect
        val index = visible.indexOfFirst { it.isCurrent }
        if (index < 0) return@LaunchedEffect
        gridState.scrollToItem(index)
        scrolledToCurrent = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f),
    ) {
        // v0.182.0：顶部标题改截图口径“切换站源”，右边加关闭（原来只能下滑关闭）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V5.Gutter)
                .padding(top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "切换站源",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.padding(4.dp))
            Text(
                // v0.267.0（T5）：原来标题用过滤后的 visible.size，搜
                // 索/筛选后总数就变了；标题一律显示总数 items.size，
                // 筛选后另注已筛选数，不再拿筛选数冒充总数。
                text = if (loading) "加载中…" else if (visible.size == items.size) "共 ${items.size} 个源" else "共 ${items.size} 个源，已筛选 ${visible.size} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { onRefreshClick() },
                enabled = !refreshing && !loading,
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新源",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // v0.184.0：关闭改图 2 那种文字 pill（原来 X 图标太小、不起眼），
            // 背景跟随主题主色浅 tint，换主题色时它也跟着变。
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // 搜索框：按源名过滤，右边是小宫格切换（点一下换一种排列，长按打开选择）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V5.Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // v0.184.0：搜索框收窄——提示改短成单行“搜索站源”，
            // 不再是原来那句长提示换两行把框撑高；框体改浅灰 pill、
            // 去掉描边，贴近图 2；宫格按钮改宽一点写“宫格·大卡”，
            // 搜索框自然就窄下来了。
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索站源", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "清空",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
            )
            Spacer(Modifier.padding(8.dp))
            Box {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .combinedClickable(
                            onClick = { HomeSettings.setPickerLayout(context, (layoutMode + 1) % 6) },
                            onLongClick = { showLayoutMenu = true },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = layoutIcons[layoutMode],
                            contentDescription = "切换排列，长按选择",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.padding(2.dp))
                        Text(
                            text = "宫格·${layoutNames[layoutMode]}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                AppDropdownMenu(
                    expanded = showLayoutMenu,
                    onDismissRequest = { showLayoutMenu = false },
                ) {
                    layoutNames.forEachIndexed { index, name ->
                        DropdownMenuItem(
                            text = { Text(if (index == layoutMode) "$name（当前）" else name) },
                            leadingIcon = {
                                Icon(layoutIcons[index], contentDescription = null)
                            },
                            onClick = {
                                HomeSettings.setPickerLayout(context, index)
                                showLayoutMenu = false
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 小字类型筛选 chips
        if (kindsPresent.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = V5.Gutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                V5Chip(text = "全部", selected = kindFilter == null, onClick = { kindFilter = null }, small = true)
                kindsPresent.forEach { kind ->
                    V5Chip(
                        text = kindLabel(kind),
                        selected = kindFilter == kind,
                        onClick = { kindFilter = if (kindFilter == kind) null else kind },
                        small = true,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // v0.182.0：三种排列，数据同一批（现在的源原样放进去），只换排法。
        // v0.184.0：扩到 6 种，见 layoutNames。
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // 整区加载圈统一为 StatusRing（跑马灯圆环 + 黄色状态点）
                StatusRing(state = StatusRingState.Loading, diameter = 40.dp)
            }
        } else if (visible.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (items.isEmpty()) "暂无可用站源" else "没有匹配的源，换个关键词试试",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (layoutMode == 0) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = V5.Gutter,
                    end = V5.Gutter,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.id }) { item ->
                    SourceCard2Col(item = item)
                }
            }
        } else if (layoutMode == 1) {
            LazyVerticalGrid(
                state = gridState,
                // v0.167.0：4 列改 5 列（6 列会把 44dp 图标挤到边，名字也没处放）
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(
                    start = V5.Gutter,
                    end = V5.Gutter,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.id }) { item ->
                    SourceGridCard(item = item)
                }
            }
        } else if (layoutMode == 2) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = V5.Gutter,
                    end = V5.Gutter,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.id }) { item ->
                    SourceListRow(item = item)
                }
            }
        } else if (layoutMode == 3) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = V5.Gutter,
                    end = V5.Gutter,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.id }) { item ->
                    SourceWideCard(item = item)
                }
            }
        } else if (layoutMode == 4) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(
                    start = V5.Gutter,
                    end = V5.Gutter,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.id }) { item ->
                    SourceGridCard(item = item)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = V5.Gutter,
                    end = V5.Gutter,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.id }) { item ->
                    SourceCompactRow(item = item)
                }
            }
        }
    }
}

/** 源类型小字标签（口径沿用 v0.107.0 的 CMS/drpy/PY/PHP/spider 徽标文案）。 */
private fun kindLabel(kind: String): String = when (kind) {
    SubSiteStore.KIND_CMS -> "CMS"
    SubSiteStore.KIND_DRPY -> "drpy"
    SubSiteStore.KIND_PY -> "PY"
    SubSiteStore.KIND_TYPE4 -> "PHP"
    SubSiteStore.KIND_SPIDER -> "spider"
    else -> "不支持"
}

/**
 * v0.182.0：卡片第二行不编假数据。截图里“响应速度：秒播/极速”那行，
 * 咱们源里根本没有测速字段，编一个就是假的；这里只写真实有的：
 * 本地规则 / 订阅站源 + 是不是当前源。
 */
private fun originLabel(id: String): String =
    if (id.startsWith("sub:")) "订阅站源" else "本地规则"

/**
 * v0.218.0（S2）：源卡片共用外框——原来 5 套卡片各抄一遍“当前源描边 +
 * 底色 + 点击”，改样式要改 5 处；现在框只此一处，5 套只留各自的内部排版。
 * 两种框：gradientRing=true 时当前源走 1.5dp 炫彩渐变描边（2 列大卡、
 * 网格卡），false 时一律细描边（列表、宽卡、紧凑行）；圆角与内边距
 * 各卡自传，视觉与原来一字不动。
 */
@Composable
private fun SourceCardFrame(
    item: PickerItem,
    corner: Dp,
    contentPadding: PaddingValues,
    gradientRing: Boolean,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val brandColors = LocalBrandGradient.current
    Box(
        modifier = Modifier
            .then(
                if (gradientRing && item.isCurrent) {
                    Modifier
                        .background(
                            Brush.linearGradient(brandColors),
                            RoundedCornerShape(corner),
                        )
                        .padding(1.5.dp)
                } else Modifier,
            )
            .clip(RoundedCornerShape(corner))
            .background(
                if (item.isCurrent) brandColors.first().copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surface,
            )
            .then(
                if (gradientRing && item.isCurrent) Modifier
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(corner)),
            )
            .alpha(if (item.unsupportedReason != null) 0.5f else 1f)
            .clickable(onClick = item.onPick)
            .padding(contentPadding),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** 截图式 2 列大卡：左边源名（可换两行），右边类型小标签，第二行是来源/当前源。 */
@Composable
private fun SourceCard2Col(item: PickerItem) {
    // v0.184.0：源框太不明显——原来毛玻璃底太透，改实色底 + 细描边；
    // 当前源底色直接取主题品牌色第一色浅 tint，换主题色时它也跟着变，
    // 不再是写死的绿。
    SourceCardFrame(
        item = item,
        corner = 16.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        gradientRing = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // v0.183.0：大卡也带高清图标，顶上一排“图标 + 类型标签”，
            // 名字放下面，不再是纯文字卡，看着像应用图标卡。
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceLogo(logoUrl = item.logoUrl, name = item.name, size = 40.dp)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = kindLabel(item.kind),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (item.isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (item.isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    item.unsupportedReason != null -> item.unsupportedReason
                    item.isCurrent -> "当前源 · ${originLabel(item.id)}"
                    else -> originLabel(item.id)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (item.isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 列表式一行：图标 + 名 + 类型/来源，名字长也不怕，适合源多时翻找。 */
@Composable
private fun SourceListRow(item: PickerItem) {
    SourceCardFrame(
        item = item,
        corner = 16.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        gradientRing = false,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceLogo(logoUrl = item.logoUrl, name = item.name, size = 36.dp)
            Spacer(Modifier.padding(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (item.isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (item.isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${kindLabel(item.kind)} · ${originLabel(item.id)}" +
                        if (item.isCurrent) " · 当前源" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.isCurrent) {
                Text(
                    text = "当前",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** v0.184.0：1 列宽卡——图标 + 名 + 类型/来源一排到底，名字长、想看清时用。 */
@Composable
private fun SourceWideCard(item: PickerItem) {
    SourceCardFrame(
        item = item,
        corner = 16.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        gradientRing = false,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceLogo(logoUrl = item.logoUrl, name = item.name, size = 48.dp)
            Spacer(Modifier.padding(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (item.isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (item.isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${kindLabel(item.kind)} · ${originLabel(item.id)}" +
                        if (item.isCurrent) " · 当前源" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.isCurrent) {
                Text(
                    text = "当前",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** v0.184.0：紧凑列表——一行到底，图标小、字小，源多时一屏看得多。 */
@Composable
private fun SourceCompactRow(item: PickerItem) {
    SourceCardFrame(
        item = item,
        corner = 12.dp,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        gradientRing = false,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceLogo(logoUrl = item.logoUrl, name = item.name, size = 28.dp)
            Spacer(Modifier.padding(4.dp))
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (item.isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (item.isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = kindLabel(item.kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private data class PickerItem(
    val id: String,
    val name: String,
    val kind: String,
    val logoUrl: String?,
    val isCurrent: Boolean,
    val unsupportedReason: String? = null,
    val onPick: () -> Unit,
)

/** 4 列网格里的单个源卡片：图标 + 名。v0.140.0：方形紧凑（圆角收至 14dp，间距收紧）。 */
@Composable
private fun SourceGridCard(item: PickerItem) {
    // 选中态：1.5dp 炫彩渐变描边（照 SourcesScreen.RainbowRingCard 口径）
    SourceCardFrame(
        item = item,
        corner = 16.dp,
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
        gradientRing = true,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SourceLogo(logoUrl = item.logoUrl, name = item.name, size = 44.dp)
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = kindLabel(item.kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 源图标：有 logoUrl（ext 图片 / 内置表 / 站点 favicon）走 Coil 加载；
 * 失败或无 logo 时回退首字色块 + 主题渐变。
 * 失败回退零成本：首字始终画在底层，AsyncImage 成功才盖住（不依赖 Coil 回调 API）。
 * v0.140.0：圆形改方形（12dp 圆角方块，与卡片圆角语言一致）。
 */
@Composable
private fun SourceLogo(logoUrl: String?, name: String, size: Dp) {
    val brandColors = LocalBrandGradient.current
    // v0.105.0 口径：明度自适应，luminance>0.5 取深色 0xFF1A1408
    val onGradientColor =
        if (brandColors.map { it.luminance() }.average() > 0.5f) Color(0xFF1A1408) else Color.White
    // v0.183.0：高清候选链。logoUrl 若是 favicon.ico（16/32px，放大必糊），
    // 不直接拿它当图，先试本站的 apple-touch-icon / 192，失败逐级降，
    // 最后一张才是 favicon.ico；ext/内置图本身就是图 URL 的，走单张。
    val candidates = remember(logoUrl) {
        if (logoUrl == null) {
            emptyList()
        } else if (logoUrl.endsWith("/favicon.ico")) {
            val host = hostFromUrl(logoUrl)
            if (host != null) hdIconCandidatesForHost(host) else listOf(logoUrl)
        } else {
            listOf(logoUrl)
        }
    }
    var candidateIndex by remember(logoUrl) { mutableStateOf(0) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(brandColors))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.toString() ?: "源",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = onGradientColor,
        )
        if (candidates.isNotEmpty() && candidateIndex < candidates.size) {
            AsyncImage(
                model = candidates[candidateIndex],
                contentDescription = name,
                // Fit 不裁切：矩形图不再被 Crop 放大裁掉边缘、糊一圈；
                // High 缩放：180/192 的高清图缩到 44dp 时边缘更利落。
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                onError = {
                    if (candidateIndex < candidates.lastIndex) candidateIndex++
                    else candidateIndex = candidates.size
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
    }
}

/** 代码托管/静态资源域名不算站点域，首个 URL 回退时跳过这些。 */
private val NON_SITE_HOSTS = setOf(
    "github.com", "raw.githubusercontent.com", "gitee.com", "gitcode.com",
    "cdn.jsdelivr.net", "unpkg.com", "cdnjs.cloudflare.com",
)

/**
 * v0.140.0：读规则文件头（16KB）取站点 host。
 * `host = 'https://...'` / `host: 'https://...'` 优先，否则取首个
 * 非代码托管 URL 的 host；都没有返回 null（调用方回退首字）。
 * 只读文件头、不扫全文，130 个源在 IO 线程一次读完也轻。
 */
private fun ruleFileHost(dir: File, name: String): String? {
    val head = runCatching {
        File(dir, name).bufferedReader().use { r ->
            val buf = CharArray(16 * 1024)
            val n = r.read(buf)
            if (n <= 0) "" else String(buf, 0, n)
        }
    }.getOrNull() ?: return null
    Regex("""host\s*[:=]\s*['"]https?://([^/'"\s]+)""").find(head)?.let {
        return it.groupValues[1]
    }
    return Regex("""https?://([^/'"\s]+)""").findAll(head)
        .map { it.groupValues[1].lowercase() }
        .firstOrNull { it !in NON_SITE_HOSTS }
}
