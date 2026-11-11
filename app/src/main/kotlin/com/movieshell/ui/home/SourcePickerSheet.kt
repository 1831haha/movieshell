package com.movieshell.ui.home

import com.movieshell.ui.components.AppSearchBar
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import com.movieshell.data.source_mgr.ApiSource
import com.movieshell.data.store.RuleInfo
import com.movieshell.data.store.ApiSourceStore
import com.movieshell.data.source_mgr.DrpySourceManager
import com.movieshell.core.log.LogBus
import com.movieshell.data.source_mgr.PhpSourceManager
import com.movieshell.data.source_mgr.PySourceManager
import com.movieshell.data.source_mgr.WebParseSourceManager
import com.movieshell.data.source_mgr.ZipPySourceStore
import com.movieshell.data.store.SourceStateStore
import com.movieshell.core.util.StoragePaths
import com.movieshell.data.source_mgr.SubCurrent
import com.movieshell.data.source_mgr.SubSiteRecord
import com.movieshell.data.source_mgr.SubSiteStore
import com.movieshell.ui.components.V5Chip
import com.movieshell.ui.components.sheetListScrollGuard
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
 * v1.1.25：双形态（见 HomeScreen 挂载处）——默认悬浮窗（小居中 Dialog，
 * 宽 86%、高 62%，标题 + 搜索 + 胶囊行扁平列表，不分组头、不带来源/类型/
 * 压缩包胶囊与宫格切换，关闭只走点外部/返回键）；切到底部弹窗即恢复
 * ModalBottomSheet 形态（85% 高、六种排列、胶囊筛选、守卫全在）。
 * 形态走 HomeSettings 持久化，标题行切换按钮点一下即换、下次打开记住；
 * 切形态时弹窗内容重建，搜索框文字会重置，接受。
 *
 * v0.153.0：打开即定位到当前源——131 个源里当前源靠后时不再从头翻；
 * 只在无搜索、无类型筛选的默认列表下定位一次（筛选会把当前源滤掉，强制定位无意义）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourcePickerSheet(
    mode: HomeSettings.PickerMode,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    // v0.17.0：异步加载，避免主线程扫文件卡顿（复用旧逻辑）
    var cur by remember { mutableStateOf<SubCurrent?>(null) }
    var drpyRules by remember { mutableStateOf<List<RuleInfo>>(emptyList()) }
    var pyRules by remember { mutableStateOf<List<RuleInfo>>(emptyList()) }
    var phpRules by remember { mutableStateOf<List<RuleInfo>>(emptyList()) }
    // v1.1.14：压缩包里的 PY 源（A 组 PySourceManager.listZipRules 供给）
    var zipRules by remember { mutableStateOf<List<RuleInfo>>(emptyList()) }
    // v1.1.15：已导入的压缩包名（ZipPySourceStore.listZips 供给），供本地源下的包名胶囊行用。
    var zipNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var drpyHosts by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var pyHosts by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var phpHosts by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var drpyActiveName by remember { mutableStateOf<String?>(null) }
    var pyActiveName by remember { mutableStateOf<String?>(null) }
    var phpActiveName by remember { mutableStateOf<String?>(null) }
    var webParseSites by remember { mutableStateOf<List<WebParseSourceManager.WebParseSite>>(emptyList()) }
    var webParseActiveName by remember { mutableStateOf<String?>(null) }
    var subSites by remember { mutableStateOf<List<Pair<ApiSource, SubSiteRecord>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    // v0.294.54：无缓存的已启用订阅（源管理页显示“未解析”的那批）。
    // 旧版弹窗只读一次缓存：订阅正在后台解析时打开弹窗，来源胶囊里
    // 永远没有它，解析跑完了也不出现，必须关掉重开才看得到。
    var unparsedSubs by remember { mutableStateOf<List<ApiSource>>(emptyList()) }
    var parsingSubNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var failedSubNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var autoParseStarted by remember { mutableStateOf(false) }
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
        val newZip = PySourceManager.listZipRules()
        zipNames = ZipPySourceStore.listZips(context)
        drpyRules = newDrpy
        pyRules = newPy
        phpRules = newPhp
        zipRules = newZip
        drpyActiveName = DrpySourceManager.activeRuleName()
        pyActiveName = PySourceManager.activeRuleName()
        phpActiveName = PhpSourceManager.activeRuleName()
        webParseSites = WebParseSourceManager.listSites()
        webParseActiveName = WebParseSourceManager.activeSite()?.fileName
        val enabledSubs = ApiSourceStore.load(context).filter { it.enabled }
        val newSubSites = enabledSubs.flatMap { sub ->
            val sites = SubSiteStore.getCached(context, sub.url)?.sites.orEmpty()
            sites.map { sub to it }
        }
        subSites = newSubSites
        // 多仓订阅 sites 为空但 warehouses 非空，已算解析过，不列入待补解析；
        // 只有缓存全空（或两边都空）才算未解析，避免对多仓反复自动下载。
        unparsedSubs = enabledSubs.filter { sub ->
            val cache = SubSiteStore.getCached(context, sub.url)
            cache == null || (cache.sites.isEmpty() && cache.warehouses.isEmpty())
        }
        // v0.140.0：本地规则的站点 host（文件头取，IO 线程做，不阻塞主线程）
        drpyHosts = newDrpy.associate { it.name to ruleFileHost(StoragePaths.jsDir(context), it.name) }
        pyHosts = newPy.associate { it.name to ruleFileHost(StoragePaths.pyDir(context), it.name) }
        phpHosts = newPhp.associate { it.name to ruleFileHost(PhpSourceManager.rulesDir(context), it.name) }
        val ids = HashSet<String>()
        newDrpy.forEach { ids += "local-drpy:${it.name}" }
        newPy.forEach { ids += "local-py:${it.name}" }
        newPhp.forEach { ids += "local-php:${it.name}" }
        newZip.forEach { ids += "local-zip:${it.name}" }
        webParseSites.forEach { ids += "local-webparse:${it.fileName}" }
        newSubSites.forEach { (sub, rec) -> ids += "sub:${sub.url}:${rec.key}" }
        ids.size
    }

    LaunchedEffect(Unit) {
        reloadFromCache()
        loading = false
    }

    // v0.294.54：缺缓存订阅自动补解析——只补“还没有缓存”的订阅，已有
    // 缓存的订阅不动，不重复刷新整批。跑在 SubSiteStore.appScope（进程级），
    // 关弹窗也不取消，跑完重读缓存，来源胶囊自己多出来，不用重开弹窗。
    // 同地址在途时 refresh() 内部去重，源管理页正在解析同一订阅也不会
    // 被重复下一遍。每个弹窗只自动补一次，失败不循环重试，失败名
    // 留在下方提示里，用户点右上角刷新可手动再试。
    LaunchedEffect(loading, unparsedSubs) {
        if (loading || autoParseStarted || unparsedSubs.isEmpty()) return@LaunchedEffect
        autoParseStarted = true
        val missing = unparsedSubs
        parsingSubNames = missing.map { it.name.ifBlank { hostFromUrl(it.url) ?: "订阅" } }
        failedSubNames = emptyList()
        SubSiteStore.appScope.launch {
            val failed = mutableListOf<String>()
            missing.forEach { sub ->
                val ok = runCatching { SubSiteStore.refresh(context, sub.url) }
                    .getOrNull()?.isSuccess == true
                if (!ok) failed.add(sub.name.ifBlank { hostFromUrl(sub.url) ?: "订阅" })
            }
            reloadFromCache()
            parsingSubNames = emptyList()
            failedSubNames = failed
        }
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
        val prevWebParse = WebParseSourceManager.activeSite()?.fileName
        SubSiteStore.clearCurrent(context)
        WebParseSourceManager.setActiveSite(null)
        if (isDrpy) {
            PySourceManager.setActiveRule(null)
            PhpSourceManager.setActiveRule(null)
            if (!DrpySourceManager.setActiveRuleAsync(name)) {
                if (prevPy != null) PySourceManager.setActiveRule(prevPy)
                else if (prevPhp != null) PhpSourceManager.setActiveRule(prevPhp)
                else if (prevDrpy != null && prevDrpy != name) DrpySourceManager.setActiveRuleAsync(prevDrpy)
                else if (prevWebParse != null) WebParseSourceManager.setActiveByFileName(prevWebParse)
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
                else if (prevWebParse != null) WebParseSourceManager.setActiveByFileName(prevWebParse)
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
        val prevWebParse = WebParseSourceManager.activeSite()?.fileName
        SubSiteStore.clearCurrent(context)
        DrpySourceManager.setActiveRule(null)
        PySourceManager.setActiveRule(null)
        WebParseSourceManager.setActiveSite(null)
        if (!PhpSourceManager.setActiveRule(name)) {
            // 先恢复切换前正在用的引擎（drpy／py／php／网页解析同一时间只一个在用）。
            if (prevDrpy != null) DrpySourceManager.setActiveRuleAsync(prevDrpy)
            else if (prevPy != null) PySourceManager.setActiveRule(prevPy)
            else if (prev != null && prev != name) PhpSourceManager.setActiveRule(prev)
            else if (prevWebParse != null) WebParseSourceManager.setActiveByFileName(prevWebParse)
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
        WebParseSourceManager.setActiveSite(null)
        SourceStateStore.clearActiveRule(context)
        SubSiteStore.setCurrent(context, sub.url, rec.key)
        onChanged()
        onDismiss()
    }

    /** 通用网页源选中：与源管理页 activateWebParse 同款切换，失败回滚到切换前的源。 */
    fun pickWebParse(site: WebParseSourceManager.WebParseSite) {
        switchJob?.cancel()
        switchJob = scope.launch {
            val prevDrpy = DrpySourceManager.activeRuleName()
            val prevPy = PySourceManager.activeRuleName()
            val prevPhp = PhpSourceManager.activeRuleName()
            val prevWebParse = WebParseSourceManager.activeSite()?.fileName
            SubSiteStore.clearCurrent(context)
            DrpySourceManager.setActiveRule(null)
            PySourceManager.setActiveRule(null)
            PhpSourceManager.setActiveRule(null)
            if (!WebParseSourceManager.setActiveSite(site)) {
                if (prevDrpy != null) DrpySourceManager.setActiveRuleAsync(prevDrpy)
                else if (prevPy != null) PySourceManager.setActiveRule(prevPy)
                else if (prevPhp != null) PhpSourceManager.setActiveRule(prevPhp)
                else if (prevWebParse != null && prevWebParse != site.fileName) {
                    WebParseSourceManager.setActiveByFileName(prevWebParse)
                }
                AppToast.show(context, "切换失败：通用网页源不存在", long = false)
                return@launch
            }
            SourceStateStore.setRuleEnabled(context, "webparse", site.fileName, true)
            SourceStateStore.saveActiveRule(context, "webparse", site.fileName)
            onChanged()
            onDismiss()
        }
    }

    // v1.1.14：压缩包导入——选 .zip 文件走 ZipPySourceStore.importZip，成功按
    // 导入源数 Toast 并重读列表，失败 Toast 原因；入口在标题行刷新按钮旁。
    val zipImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { ZipPySourceStore.importZip(context, uri) }
            if (result.ok) {
                AppToast.show(context, "已导入 ${result.count} 个源", long = false)
            } else {
                AppToast.show(context, result.reason ?: "导入失败", long = true)
            }
            reloadFromCache()
        }
    }

    // 统一条目：本地 drpy 规则 / 本地 py 规则 / 本地 php 规则 / 通用网页源 / 订阅站点
    val items: List<PickerItem> = remember(cur, drpyRules, pyRules, phpRules, zipRules, webParseSites, drpyHosts, pyHosts, phpHosts, drpyActiveName, pyActiveName, phpActiveName, webParseActiveName, subSites) {
        val list = mutableListOf<PickerItem>()
        drpyRules.forEach { rule ->
            val name = rule.title.ifBlank { rule.name }
            val host = drpyHosts[rule.name]
            list.add(
                PickerItem(
                    id = "local-drpy:${rule.name}",
                    name = name,
                    fileName = rule.name,
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
                    fileName = rule.name,
                    kind = SubSiteStore.KIND_PY,
                    logoUrl = siteLogoUrl(rule.name, name, null)
                        ?: host?.let { faviconUrlForHost(it) },
                    isCurrent = cur == null && rule.name == pyActiveName,
                    onPick = { pickLocal(false, rule.name) },
                ),
            )
        }
        // v1.1.14：压缩包里的 PY 源——kind 沿用 PY（走现成 PY 筛选 chip），
        // 身份串直接当 fileName（搜索按它匹配）；点按复用 pickLocal(false, 身份)，
        // PySourceManager.setActiveRule 接身份串切换，pyActiveName 命中即当前源。
        // 压缩包内条目没有站点 host，logoUrl 传空走首字色块回退。
        zipRules.forEach { rule ->
            val name = rule.title.ifBlank { rule.name }
            list.add(
                PickerItem(
                    id = "local-zip:${rule.name}",
                    name = name,
                    fileName = rule.name,
                    kind = SubSiteStore.KIND_PY,
                    logoUrl = null,
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
                    fileName = rule.name,
                    kind = SubSiteStore.KIND_TYPE4,
                    logoUrl = siteLogoUrl(rule.name, name, null)
                        ?: host?.let { faviconUrlForHost(it) },
                    isCurrent = cur == null && rule.name == phpActiveName,
                    onPick = { pickPhp(rule.name) },
                ),
            )
        }
        webParseSites.forEach { site ->
            list.add(
                PickerItem(
                    id = "local-webparse:${site.fileName}",
                    name = site.name,
                    kind = SubSiteStore.KIND_WEBPARSE,
                    logoUrl = hostFromUrl(site.url)?.let { faviconUrlForHost(it) },
                    isCurrent = cur == null && site.fileName == webParseActiveName,
                    onPick = { pickWebParse(site) },
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

    // v0.294.52：来源胶囊——原来 228 个源本地/订阅混排在一张表里翻找。
    // 从本版起不再混排：顶部一排来源胶囊（本地源 + 每个订阅名），点哪个下面
    // 只出哪个来源的站；没有“全部”入口，混排不再出现。来源取 subSites 里
    // 实际有缓存站点的订阅，名字取订阅名（空则取域名），括号里是该订阅站数。
    // originFilter 取值：null=未初始化（加载中临时看全部）、"local"=本地源、
    // "sub:<订阅 url>"=该订阅。PickerItem 的 id 本身带前缀（local- / sub:<url>:<key>），
    // 过滤直接按 id 前缀，不用给条目加新字段。
    val origins: List<Triple<String, String, Int>> = remember(subSites) {
        subSites.map { it.first }.distinctBy { it.url }.map { sub ->
            Triple(
                "sub:${sub.url}",
                sub.name.ifBlank { hostFromUrl(sub.url) ?: "订阅" },
                subSites.count { it.first.url == sub.url },
            )
        }
    }
    var originFilter by remember { mutableStateOf<String?>(null) }
    // 默认来源跟当前源走：正在用订阅站就默认该订阅，正在用本地规则就默认本地源；
    // 用户一旦手动点过胶囊就不再覆盖（originFilter 已非空）。
    // 注意：必须等 loading=false（reload 已把 cur 读回来）再定默认，否则首帧
    // cur 还是 null 会误定成“本地源”，之后 cur 变了也覆盖不回去。
    LaunchedEffect(loading, cur) {
        if (!loading && originFilter == null) {
            // cur 是 by 委托属性，判空后不能直接智能转换：先快照到局部变量再用。
            val c = cur
            originFilter = if (c != null) "sub:${c.subUrl}" else "local"
        }
    }
    val originFiltered: List<PickerItem> = remember(items, originFilter) {
        when (val origin = originFilter) {
            null -> items
            "local" -> items.filter { it.id.startsWith("local-") }
            else -> items.filter { it.id.startsWith("$origin:") }
        }
    }
    // 类型筛选 chips：只出现实际存在的类型，顺序固定；口径改成当前来源内，
    // 否则本地源下还会冒出只有订阅才有的 CMS 筛选，点了就是空表。
    val kindOrder = listOf(
        SubSiteStore.KIND_CMS, SubSiteStore.KIND_DRPY, SubSiteStore.KIND_PY,
        SubSiteStore.KIND_TYPE4, SubSiteStore.KIND_SPIDER, SubSiteStore.KIND_WEBPARSE,
    )
    val kindsPresent = remember(originFiltered) { kindOrder.filter { k -> originFiltered.any { it.kind == k } } }
    var query by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf<String?>(null) }
    // v1.1.15：压缩包筛选——选中的包名（ZipPySourceStore.listZips 里的文件名，
    // 含 .zip 后缀）；null = 全部本地源。只在本地源下出现，与类型筛选可叠加。
    var zipFilter by remember { mutableStateOf<String?>(null) }
    // 包被删掉后（源管理页删包）过滤条件失效就回全部，不留空表。
    LaunchedEffect(zipNames) {
        if (zipFilter != null && zipFilter !in zipNames) zipFilter = null
    }
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
    // 搜索文本 × 类型筛选 × 压缩包筛选联动（先按来源过滤，再按类型/包/搜索过滤）
    // v1.1.11：搜索除显示名外也按底层文件名匹配（本地规则的 fileName），标题与文件名不一致时搜文件名仍能找到。
    // v1.1.15：zipFilter 非空时只留该包里的条目（id 是 local-zip:zip:<包名>!<entry>，
    // 按 parseIdentity 取包名比对）；不在包里的散装 .py 只在“全部”里出现。
    val visible = remember(originFiltered, query, kindFilter, zipFilter) {
        originFiltered.filter { item ->
            (kindFilter == null || item.kind == kindFilter) &&
                (zipFilter == null || (item.id.startsWith("local-zip:") &&
                    ZipPySourceStore.parseIdentity(item.id.removePrefix("local-zip:"))?.first == zipFilter)) &&
                (query.isBlank() || item.name.contains(query.trim(), ignoreCase = true) ||
                    item.fileName?.contains(query.trim(), ignoreCase = true) == true)
        }
    }

    // v1.1.14：四组展示——本地源 / 订阅源 / 压缩包的源 / 网页源；组标题带数量，
    // 空组不显示；来源胶囊、搜索、类型筛选先过滤，分组只对过滤后的 visible 再分。
    val groupedVisible: List<PickerGroup> = remember(visible) { groupPickerItems(visible) }

    // v0.153.0：打开即定位到当前源。网格状态 + 只定位一次的标记（弹窗每次打开都是
    // 新组合，标记天然复位；搜索/筛选改动会取消在途滚动，标记只在滚动完成后才置位，
    // 避免一次取消就永久丢定位）。
    val gridState: LazyGridState = rememberLazyGridState()
    // v0.294.2：列表三种排法（列表/宽卡/紧凑）各用自己的 listState——
    // 切来源/筛选回顶与打开定位当前源都要它滚；底部弹窗形态下它还供
    // sheetListScrollGuard 判断列表是否在顶，悬浮窗形态下没有弹窗可守。
    val listState = rememberLazyListState()
    var scrolledToCurrent by remember { mutableStateOf(false) }
    // v1.1.14：分组后网格里多了组头行，定位下标要把组头算进去（组头 1 行 + 组内条目）。
    val currentGroupedIndex = remember(groupedVisible) {
        var index = 0
        var found = -1
        for (group in groupedVisible) {
            val within = group.items.indexOfFirst { it.isCurrent }
            if (within >= 0) {
                found = index + 1 + within
                break
            }
            index += 1 + group.items.size
        }
        found
    }
    // v1.1.25：按形态分流定位——悬浮窗是扁平列表，滚 listState 到当前源在
    // visible 里的下标；底部弹窗沿用分组网格口径，滚 gridState。
    LaunchedEffect(loading, groupedVisible) {
        if (loading || scrolledToCurrent) return@LaunchedEffect
        if (query.isNotBlank() || kindFilter != null || zipFilter != null) return@LaunchedEffect
        if (mode == HomeSettings.PickerMode.FLOAT) {
            val index = visible.indexOfFirst { it.isCurrent }
            if (index < 0) return@LaunchedEffect
            listState.scrollToItem(index)
        } else {
            val index = currentGroupedIndex
            if (index < 0) return@LaunchedEffect
            gridState.scrollToItem(index)
        }
        scrolledToCurrent = true
    }
    // v0.294.52：回本地源就是点一下来源胶囊——切来源时必须清类型筛选，
    // 否则订阅的 CMS 筛选会带到本地源上直接出空表；同时回顶，避免停在
    // 旧来源的滚动位置上。本地源胶囊常驻在列表上方，订阅状态下也一样，
    // 不用先关弹窗、不用翻 228 个源找本地规则。
    // v1.1.15：切来源同时清压缩包筛选，包名胶囊只在本地源下有意义。
    val selectOrigin: (String) -> Unit = { origin ->
        originFilter = origin
        kindFilter = null
        zipFilter = null
        scope.launch {
            gridState.scrollToItem(0)
            listState.scrollToItem(0)
        }
    }

    // v1.1.25：双形态根容器——悬浮窗是小居中卡片（宽 86%、高 62%，圆角 24dp，
    // 自己铺主题 surface，Dialog 默认居中 + 默认遮罩）；底部弹窗恢复 HEAD 的
    // 85% 高全宽，底色与圆角由 ModalBottomSheet 给。
    Column(
        modifier = if (mode == HomeSettings.PickerMode.FLOAT) {
            Modifier
                .fillMaxWidth(0.86f)
                .fillMaxHeight(0.62f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
        } else {
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        },
    ) {
        // v0.182.0：顶部标题改截图口径“切换站源”，右边加关闭（原来只能下滑关闭）。
        // v1.1.25：标题行加形态切换按钮——悬浮窗只留标题 + 切换按钮（关闭走点外部/
        // 返回键）；底部弹窗保留原来的导入/刷新/关闭，再加切换按钮。窄屏下总数先缩
        // （weight fill=false + 省略），按钮一律不裁。
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
            if (mode == HomeSettings.PickerMode.FLOAT) {
                Spacer(Modifier.weight(1f))
                PickerModeToggle(mode = mode)
            } else {
                Spacer(Modifier.padding(4.dp))
                Text(
                    // v0.267.0（T5）：原来标题用过滤后的 visible.size，搜
                    // 索/筛选后总数就变了；标题一律显示总数 items.size，
                    // 筛选后另注已筛选数，不再拿筛选数冒充总数。
                    // v0.294.52：总数按当前来源算（不是全量 228），否则标题与下面
                    // 只出一个来源的列表对不上。
                    text = if (loading) "加载中…" else if (visible.size == originFiltered.size) "共 ${originFiltered.size} 个源" else "共 ${originFiltered.size} 个源，已筛选 ${visible.size} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                IconButton(
                    onClick = {
                        // v1.1.21：无授权时不打开文件选择器，先提示去授权。
                        if (!StoragePaths.hasAccess(context)) {
                            AppToast.show(context, "需要先授予存储权限，才能导入压缩包源", long = true)
                        } else {
                            zipImportLauncher.launch("application/zip")
                        }
                    },
                    enabled = !loading,
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = "导入压缩包",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                PickerModeToggle(mode = mode)
                Spacer(Modifier.padding(6.dp))
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
        }
        // v1.1.21：无授权时本地源不可用的明确提示（不静默空表）。
        if (!StoragePaths.hasAccess(context)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = V5.Gutter, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "未授予存储权限，本地文件源不可用，订阅源照常用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "去授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        (context as? android.app.Activity)?.let { StoragePaths.requestAccess(it) }
                    },
                )
            }
        }
        // 搜索框：按源名过滤。底部弹窗形态下右边是小宫格切换（点一下换一种排列，
        // 长按打开选择）；悬浮窗形态下只有搜索框，照效果图。
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
            // v0.294.6：统一 AppSearchBar
            AppSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索站源",
                modifier = Modifier.weight(1f),
            )
            if (mode == HomeSettings.PickerMode.SHEET) {
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
        }
        Spacer(Modifier.height(8.dp))
        // v1.1.25：以下四组（来源胶囊 / 解析状态 / 类型筛选 / 压缩包胶囊）只在底部弹窗形态下出现；
        // 悬浮窗照效果图只有标题 + 搜索 + 胶囊行列表。
        if (mode == HomeSettings.PickerMode.SHEET) {
            // v0.294.52：来源胶囊行（横向可滑，订阅多也不挤）——第一颗永远是
            // “本地源”，正在用订阅时点它即回本地源列表，不用关弹窗翻找；
            // 每颗订阅胶囊点下去，下面只出该订阅的站。
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = V5.Gutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    V5Chip(
                        text = "本地源",
                        selected = originFilter == "local",
                        onClick = { selectOrigin("local") },
                        small = true,
                    )
                }
                items(origins, key = { it.first }) { (origin, label, count) ->
                    V5Chip(
                        text = "$label（$count）",
                        selected = originFilter == origin,
                        onClick = { selectOrigin(origin) },
                        small = true,
                    )
                }
            }
            // v0.294.54：缺缓存订阅的可见状态——旧版这里悄悄只有本地源，
            // 用户以为订阅丢了，其实只是缓存还没解析完。解析中给一行提示，
            // 解析完胶囊自己出现；失败也明说，不留“订阅不见了”的悬念。
            if (parsingSubNames.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = V5.Gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.padding(4.dp))
                    Text(
                        text = "正在解析订阅：${parsingSubNames.joinToString("、")}，解析完这里会多出对应胶囊，不用重开",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (failedSubNames.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "以下订阅解析失败，点右上角刷新再试一次：${failedSubNames.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = V5.Gutter),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
            // v1.1.15：压缩包名胶囊——当前来源是本地源且导过包时才出现，没导过
            // 不显示、不占地方；点哪个只出哪个包的源，再点一次或点“全部”回到
            // 全部本地源；与类型筛选可叠加；只筛选列表，不触发换源（点卡片才切换）。
            if (originFilter == "local" && zipNames.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = V5.Gutter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item {
                        V5Chip(
                            text = "全部",
                            selected = zipFilter == null,
                            onClick = { zipFilter = null },
                            small = true,
                        )
                    }
                    items(zipNames, key = { it }) { zipName ->
                        V5Chip(
                            text = zipDisplayName(zipName),
                            selected = zipFilter == zipName,
                            onClick = { zipFilter = if (zipFilter == zipName) null else zipName },
                            small = true,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        // v0.182.0：三种排列，数据同一批（现在的源原样放进去），只换排法。
        // v0.184.0：扩到 6 种，见 layoutNames。
        // v1.1.25：悬浮窗是扁平胶囊行列表（不分组头、不分六种排列）；底部弹窗
        // 沿用原来的六种排列，守卫照 HEAD 全挂回去。
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // 整区加载圈统一为 StatusRing（跑马灯圆环 + 黄色状态点）
                StatusRing(state = StatusRingState.Loading, diameter = 40.dp)
            }
        } else if (visible.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    // 悬浮窗里没有来源胶囊，空来源的提示不提“点上方胶囊”。
                    text = if (items.isEmpty()) "暂无可用站源" else if (originFiltered.isEmpty()) {
                        if (mode == HomeSettings.PickerMode.FLOAT) "该来源下暂无源" else "该来源下暂无源，点上方胶囊换个来源"
                    } else "没有匹配的源，换个关键词试试",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (mode == HomeSettings.PickerMode.FLOAT) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = V5.Gutter,
                    end = V5.Gutter,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.id }) { item ->
                    SourceFloatRow(item = item)
                }
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
                modifier = Modifier.fillMaxSize()
                    .sheetListScrollGuard { gridState.canScrollBackward },
            ) {
                groupedVisible.forEach { group ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PickerGroupHeader(title = group.title)
                    }
                    items(group.items, key = { it.id }) { item ->
                        SourceCard2Col(item = item)
                    }
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
                modifier = Modifier.fillMaxSize()
                    .sheetListScrollGuard { gridState.canScrollBackward },
            ) {
                groupedVisible.forEach { group ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PickerGroupHeader(title = group.title)
                    }
                    items(group.items, key = { it.id }) { item ->
                        SourceGridCard(item = item)
                    }
                }
            }
        } else if (layoutMode == 2) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = V5.Gutter,
                    end = V5.Gutter,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
                    .sheetListScrollGuard { listState.canScrollBackward },
            ) {
                groupedVisible.forEach { group ->
                    item { PickerGroupHeader(title = group.title) }
                    items(group.items, key = { it.id }) { item ->
                        SourceListRow(item = item)
                    }
                }
            }
        } else if (layoutMode == 3) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = V5.Gutter,
                    end = V5.Gutter,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
                    .sheetListScrollGuard { listState.canScrollBackward },
            ) {
                groupedVisible.forEach { group ->
                    item { PickerGroupHeader(title = group.title) }
                    items(group.items, key = { it.id }) { item ->
                        SourceWideCard(item = item)
                    }
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
                modifier = Modifier.fillMaxSize()
                    .sheetListScrollGuard { gridState.canScrollBackward },
            ) {
                groupedVisible.forEach { group ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PickerGroupHeader(title = group.title)
                    }
                    items(group.items, key = { it.id }) { item ->
                        SourceGridCard(item = item)
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = V5.Gutter,
                    end = V5.Gutter,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
                    .sheetListScrollGuard { listState.canScrollBackward },
            ) {
                groupedVisible.forEach { group ->
                    item { PickerGroupHeader(title = group.title) }
                    items(group.items, key = { it.id }) { item ->
                        SourceCompactRow(item = item)
                    }
                }
            }
        }
    }
}

/**
 * v1.1.25：形态切换按钮——胶囊样式（与“宫格·大卡”按钮同款底色），带交换
 * 图标 + 当前形态名；点一下立刻换形态并持久化（HomeSettings），下次打开
 * 记住；切形态时弹窗内容重建，搜索框文字会重置，接受。
 */
@Composable
private fun PickerModeToggle(mode: HomeSettings.PickerMode) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable {
                val next = if (mode == HomeSettings.PickerMode.FLOAT) {
                    HomeSettings.PickerMode.SHEET
                } else {
                    HomeSettings.PickerMode.FLOAT
                }
                HomeSettings.setPickerMode(context, next)
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.SwapHoriz,
                contentDescription = "切换弹窗形态",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(2.dp))
            Text(
                text = mode.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * v1.1.25：悬浮窗胶囊行——圆角胶囊：棕色首字母图标 + 源名 + 右边类型小标签；
 * 点行即换源；当前源靠卡片底色浅 tint + 名字加粗标出（SourceCardFrame 现成口径）。
 */
@Composable
private fun SourceFloatRow(item: PickerItem) {
    SourceCardFrame(
        item = item,
        corner = 50.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        gradientRing = false,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceLogo(logoUrl = item.logoUrl, name = item.name, size = 32.dp)
            Spacer(Modifier.padding(8.dp))
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
    }
}

/** 源类型小字标签（口径沿用 v0.107.0 的 CMS/drpy/PY/PHP/spider 徽标文案）。 */
private fun kindLabel(kind: String): String = when (kind) {
    SubSiteStore.KIND_CMS -> "CMS"
    SubSiteStore.KIND_DRPY -> "drpy"
    SubSiteStore.KIND_PY -> "PY"
    SubSiteStore.KIND_TYPE4 -> "PHP"
    SubSiteStore.KIND_SPIDER -> "spider"
    SubSiteStore.KIND_WEBPARSE -> "网页源"
    else -> "不支持"
}

/**
 * v0.182.0：卡片第二行不编假数据。截图里“响应速度：秒播/极速”那行，
 * 咱们源里根本没有测速字段，编一个就是假的；这里只写真实有的：
 * 本地规则 / 订阅站源 + 是不是当前源。
 * v1.1.14：压缩包里的源单标“压缩包的源”，与四组分组头口径一致。
 */
private fun originLabel(id: String): String = when {
    id.startsWith("sub:") -> "订阅站源"
    id.startsWith("local-zip:") -> "压缩包的源"
    else -> "本地规则"
}

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
    /** v1.1.11：本地规则的底层文件名（rule.name，如 xxx.py）；搜索除显示名外也按它匹配，标题与文件名不一致时搜文件名仍能找到。 */
    val fileName: String? = null,
    val kind: String,
    val logoUrl: String?,
    val isCurrent: Boolean,
    val unsupportedReason: String? = null,
    val onPick: () -> Unit,
)

/** v1.1.14：四组展示的一组——标题已带数量（如“本地源（12）”），空组不进列表。 */
private data class PickerGroup(val title: String, val items: List<PickerItem>)

/** v1.1.15：压缩包胶囊显示名——去掉 .zip 后缀（大小写不敏感）。 */
private fun zipDisplayName(zipName: String): String =
    if (zipName.endsWith(".zip", ignoreCase = true)) zipName.dropLast(4) else zipName

/**
 * v1.1.14：按 id 前缀把过滤后的条目分四组，组内顺序不变；
 * 组顺序固定：本地源 / 订阅源 / 压缩包的源 / 网页源。
 */
private fun groupPickerItems(visible: List<PickerItem>): List<PickerGroup> {
    val local = mutableListOf<PickerItem>()
    val sub = mutableListOf<PickerItem>()
    val zip = mutableListOf<PickerItem>()
    val web = mutableListOf<PickerItem>()
    visible.forEach { item ->
        when {
            item.id.startsWith("local-zip:") -> zip += item
            item.id.startsWith("local-webparse:") -> web += item
            item.id.startsWith("sub:") -> sub += item
            else -> local += item
        }
    }
    return listOf(
        "本地源" to local,
        "订阅源" to sub,
        "压缩包的源" to zip,
        "网页源" to web,
    ).mapNotNull { (title, items) ->
        if (items.isEmpty()) null else PickerGroup("$title（${items.size}）", items)
    }
}

/** v1.1.14：四组分组头——小字主色标题，网格里占满整行。 */
@Composable
private fun PickerGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

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
