package com.movieshell.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.movieshell.data.repo.ApiSourceStore
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PhpSourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SourceStateStore
import com.movieshell.data.repo.StoragePaths
import com.movieshell.data.repo.SubCurrent
import com.movieshell.data.repo.SubSiteRecord
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.ui.components.AppChip
import com.movieshell.ui.components.StatusRing
import com.movieshell.ui.components.StatusRingState
import com.movieshell.ui.components.faviconUrlForHost
import com.movieshell.ui.components.hostFromUrl
import com.movieshell.ui.components.siteLogoUrl
import java.io.File
import com.movieshell.ui.theme.LocalBrandGradient
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.Dispatchers
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
 */
@Composable
fun SourcePickerSheet(
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    // v0.17.0：异步加载，避免主线程扫文件卡顿（复用旧逻辑）
    var cur by remember { mutableStateOf<SubCurrent?>(null) }
    var drpyRules by remember { mutableStateOf<List<DrpySourceManager.RuleInfo>>(emptyList()) }
    var pyRules by remember { mutableStateOf<List<PySourceManager.RuleInfo>>(emptyList()) }
    var phpRules by remember { mutableStateOf<List<PhpSourceManager.RuleInfo>>(emptyList()) }
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
        phpHosts = newPhp.associate { it.name to ruleFileHost(StoragePaths.phpDir(context), it.name) }
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
            Toast.makeText(
                context,
                if (failCount == 0) "已刷新，共 $total 个源"
                else "已刷新，共 $total 个源；$failCount 个订阅刷新失败，已保留旧列表",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun pickLocal(isDrpy: Boolean, name: String) {
        SubSiteStore.clearCurrent(context)
        if (isDrpy) {
            PySourceManager.setActiveRule(null)
            PhpSourceManager.setActiveRule(null)
            DrpySourceManager.setActiveRule(name)
            SourceStateStore.setRuleEnabled(context, "js", name, true)
            SourceStateStore.saveActiveRule(context, "js", name)
        } else {
            DrpySourceManager.setActiveRule(null)
            PhpSourceManager.setActiveRule(null)
            PySourceManager.setActiveRule(name)
            SourceStateStore.setRuleEnabled(context, "py", name, true)
            SourceStateStore.saveActiveRule(context, "py", name)
        }
        onChanged()
        onDismiss()
    }

    /** v0.147.0：本地 PHP 规则与 drpy/py 平齐进换源弹窗，点选即切换。 */
    fun pickPhp(name: String) {
        SubSiteStore.clearCurrent(context)
        DrpySourceManager.setActiveRule(null)
        PySourceManager.setActiveRule(null)
        PhpSourceManager.setActiveRule(name)
        SourceStateStore.setRuleEnabled(context, "php", name, true)
        SourceStateStore.saveActiveRule(context, "php", name)
        onChanged()
        onDismiss()
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
                            Toast.makeText(context, rec.reason, Toast.LENGTH_SHORT).show()
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
    // 搜索文本 × 类型筛选联动
    val visible = remember(items, query, kindFilter) {
        items.filter { item ->
            (kindFilter == null || item.kind == kindFilter) &&
                (query.isBlank() || item.name.contains(query.trim(), ignoreCase = true))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f),
    ) {
        // 顶部标题：选择片源 + 共 N 个源
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SoftDimens.PagePadding)
                .padding(top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "选择片源",
                style = SoftType.Title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.padding(4.dp))
            Text(
                text = "共 ${visible.size} 个源",
                style = SoftType.Caption,
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
        }
        // 搜索框：按源名过滤
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索源名", color = MaterialTheme.colorScheme.onSurfaceVariant) },
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
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SoftDimens.PagePadding),
        )
        Spacer(Modifier.height(8.dp))
        // 小字类型筛选 chips
        if (kindsPresent.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SoftDimens.PagePadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppChip(text = "全部", selected = kindFilter == null, onClick = { kindFilter = null }, small = true)
                kindsPresent.forEach { kind ->
                    AppChip(
                        text = kindLabel(kind),
                        selected = kindFilter == kind,
                        onClick = { kindFilter = if (kindFilter == kind) null else kind },
                        small = true,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // 4 列小图标卡片网格
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // 整区加载圈统一为 StatusRing（跑马灯圆环 + 黄色状态点）
                StatusRing(state = StatusRingState.Loading, diameter = 40.dp)
            }
        } else if (visible.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (items.isEmpty()) "暂无可用站源" else "没有匹配的源，换个关键词试试",
                    style = SoftType.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(
                    start = SoftDimens.PagePadding,
                    end = SoftDimens.PagePadding,
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
    val brandColors = LocalBrandGradient.current
    // 选中态：1.5dp 炫彩渐变描边（照 SourcesScreen.RainbowRingCard 口径）
    Box(
        modifier = Modifier
            .then(
                if (item.isCurrent) {
                    Modifier
                        .background(
                            Brush.linearGradient(brandColors),
                            RoundedCornerShape(14.dp),
                        )
                        .padding(1.5.dp)
                } else Modifier,
            )
            .clip(RoundedCornerShape(14.dp))
            .background(LocalSoftExtra.current.glass)
            .alpha(if (item.unsupportedReason != null) 0.5f else 1f)
            .clickable(onClick = item.onPick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SourceLogo(logoUrl = item.logoUrl, name = item.name, size = 44.dp)
            Text(
                text = item.name,
                style = SoftType.Caption,
                color = if (item.isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = kindLabel(item.kind),
                style = SoftType.Caption,
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
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(brandColors)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.toString() ?: "源",
            style = SoftType.Title,
            fontWeight = FontWeight.Bold,
            color = onGradientColor,
        )
        if (logoUrl != null) {
            AsyncImage(
                model = logoUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
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
