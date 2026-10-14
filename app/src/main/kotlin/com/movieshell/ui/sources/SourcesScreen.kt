package com.movieshell.ui.sources

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.ApiSource
import com.movieshell.data.repo.ApiSourceStore
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.PhpSourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SourceProbe
import com.movieshell.data.repo.SourceStateStore
import com.movieshell.data.repo.StoragePaths
import com.movieshell.data.repo.SubCache
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.data.repo.TrashStore
import com.movieshell.data.repo.WebSourceManager
import com.movieshell.ui.preview.openFileWithEditor
import com.movieshell.ui.preview.previewKindDir
import com.movieshell.ui.preview.shareFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** 站源条目：开关状态用 mutableStateOf 包裹，保证 Switch 切换即时刷新 */
class SourceEntry(
    val name: String,
    val detail: String,
    enabled: Boolean = true,
) {
    var enabled by mutableStateOf(enabled)
}

/** 站源分组 */
class SourceGroup(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val entries: SnapshotStateList<SourceEntry>,
) {
    var expanded by mutableStateOf(true)
}

/** v0.7.2：规则目录统一走 StoragePaths（/storage/emulated/0/xiaying/ 下） */
private fun targetDir(context: Context, dirName: String): File = when (dirName) {
    "drpy" -> StoragePaths.jsDir(context)
    "py" -> StoragePaths.pyDir(context)
    "php" -> StoragePaths.phpDir(context)
    "web" -> StoragePaths.webDir(context)
    else -> File(context.filesDir, dirName)
}

/** 文件导入目标：规则子目录 + 允许的后缀（目录实际位置见 targetDir，走 StoragePaths） */
private data class ImportTarget(
    val dirName: String,          // 逻辑子目录：drpy / py / php
    val extensions: List<String>, // 允许的后缀，带点，如 ".js"
    val label: String,            // 分组名，用于 Toast 提示
)

private val IMPORT_DPRY = ImportTarget("drpy", listOf(".js"), "JS文件夹")
private val IMPORT_PY = ImportTarget("py", listOf(".py"), "PY文件夹")
private val IMPORT_PHP = ImportTarget("php", listOf(".php"), "PHP文件夹")
private val IMPORT_WEB = ImportTarget("web", listOf(".html", ".htm"), "网页源")

/** 导入结果 */
private sealed interface ImportResult {
    data class Ok(val fileName: String) : ImportResult
    object WrongExt : ImportResult
    data class Failed(val reason: String) : ImportResult
}

/**
 * 把用户选中的文件拷贝到规则目录（见 targetDir）。
 * - 文件名取原名（重名则加时间戳后缀避免覆盖）
 * - 后缀不在允许列表内 -> WrongExt
 */
private fun importRuleFile(context: Context, uri: Uri, target: ImportTarget): ImportResult {
    val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    } ?: return ImportResult.Failed("读取文件名失败")
    val ext = "." + displayName.substringAfterLast('.', "").lowercase()
    if (target.extensions.none { it.equals(ext, ignoreCase = true) }) {
        return ImportResult.WrongExt
    }
    return try {
        val dir = targetDir(context, target.dirName)
        var dst = File(dir, displayName)
        if (dst.exists()) {
            val base = displayName.substringBeforeLast('.')
            dst = File(dir, "${base}_${System.currentTimeMillis()}$ext")
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { input.copyTo(it) }
        } ?: return ImportResult.Failed("无法读取所选文件")
        ImportResult.Ok(dst.name)
    } catch (e: Exception) {
        ImportResult.Failed(e.message ?: "未知错误")
    }
}

/** 扫描规则目录下的文件，转成 SourceEntry 列表（给 PHP 分组用）；activeName 命中的条目 enabled=true（高亮为当前激活） */
private fun listLocalFolderEntries(
    context: Context,
    dirName: String,
    desc: String,
    activeName: String? = null,
): List<SourceEntry> {
    val dir = targetDir(context, dirName)
    return dir.listFiles { f -> f.isFile }
        .orEmpty()
        .sortedBy { it.name }
        .map { f -> SourceEntry(f.name, "$desc · ${f.length() / 1024} KB", enabled = f.name == activeName) }
}

// ---------- v0.9.0：文件网格 tab（JS / PY / PHP / 网页源） ----------

/** 置顶顺序存 prefs "sources_pin"，每个 tab 一个 key，值是文件名按顺序 \n 拼接 */
private const val PIN_PREFS = "sources_pin"
private const val TAB_JS = "js"
private const val TAB_PY = "py"
private const val TAB_PHP = "php"
private const val TAB_WEB = "web"

private fun loadPins(context: Context, tab: String): MutableList<String> {
    val raw = context.getSharedPreferences(PIN_PREFS, Context.MODE_PRIVATE)
        .getString(tab, "").orEmpty()
    return raw.split('\n').filter { it.isNotBlank() }.toMutableList()
}

private fun savePins(context: Context, tab: String, pins: List<String>) {
    context.getSharedPreferences(PIN_PREFS, Context.MODE_PRIVATE)
        .edit().putString(tab, pins.joinToString("\n")).apply()
}

/** 网格文件条目（各 tab 统一模型） */
private data class GridFileItem(
    val kind: String,       // js / py / php / web
    val fileName: String,   // 文件名（定位文件用）
    val displayName: String, // 展示名（规则 title / 站点名）
    val detail: String,     // 副标题（大小 / url）
    val isActive: Boolean,
    val isPinned: Boolean,
    /** v0.24.0：启停开关（禁用的源首页/搜索/检测跳过） */
    val enabled: Boolean = true,
    /** v0.24.0：批量检测结果徽标 */
    val probe: SourceProbe.Result? = null,
)

/** 探活结果 key：kind:name */
private fun probeKey(kind: String, name: String) = "$kind:$name"

/** 探活徽标颜色 */
@Composable
private fun ProbeDot(probe: SourceProbe.Result?) {
    val color = when (probe) {
        is SourceProbe.Result.Ok -> Color(0xFF4CAF50)
        is SourceProbe.Result.Slow -> Color(0xFFFFB300)
        is SourceProbe.Result.Fail -> Color(0xFFE53935)
        is SourceProbe.Result.Unsupported -> Color(0xFF9E9E9E)
        null -> return
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
    Spacer(Modifier.width(4.dp))
}

/** 置顶优先（按置顶顺序），其余按展示名排序 */
private fun sortWithPins(items: List<GridFileItem>, pins: List<String>): List<GridFileItem> {
    val order = pins.withIndex().associate { it.value to it.index }
    return items.sortedWith(
        compareBy({ order[it.fileName] ?: Int.MAX_VALUE }, { it.displayName.lowercase() }),
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SourcesScreen(
    onWebSniff: (name: String, url: String) -> Unit = { _, _ -> },
    onPreview: (kind: String, fileName: String) -> Unit = { _, _ -> },
    // v0.22.0：插件市场入口
    onMarketClick: () -> Unit = {},
    // v0.24.0：回收站入口
    onTrashClick: () -> Unit = {},
) {
    val context = LocalContext.current

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = remember {
        listOf(
            "接口站源" to Icons.Filled.Language,
            "JS" to Icons.Filled.Code,
            "PY" to Icons.Filled.Terminal,
            "PHP" to Icons.Filled.Extension,
            "网页源" to Icons.Filled.Public,
        )
    }

    // 各 tab 的真实数据
    val drpyRules = remember { mutableStateListOf<DrpySourceManager.RuleInfo>() }
    val pyRules = remember { mutableStateListOf<PySourceManager.RuleInfo>() }
    val phpEntries = remember { mutableStateListOf<SourceEntry>() }
    val webSites = remember { mutableStateListOf<WebSourceManager.WebSite>() }
    // 各 tab 置顶列表
    val pins = remember { mutableStateMapOf<String, MutableList<String>>() }

    val apiGroup = remember {
        SourceGroup(
            id = "api", name = "接口站源", icon = Icons.Filled.Language,
            entries = ApiSourceStore.load(context)
                .map { SourceEntry(it.name, it.url, it.enabled) }
                .toMutableStateList(),
        )
    }

    /** 接口站源持久化：增删/开关后写回 filesDir/api_sources.json，供备份使用 */
    fun persistApiGroup() {
        ApiSourceStore.save(
            context,
            apiGroup.entries.map { ApiSource(it.name, it.detail, it.enabled) },
        )
    }

    // ---- v0.5.1 订阅站点展开 ----
    /** subUrl -> 展开缓存 */
    val subCaches = remember { mutableStateMapOf<String, SubCache>() }
    /** subUrl -> 是否正在解析 */
    val subParsing = remember { mutableStateMapOf<String, Boolean>() }
    /** subUrl -> 站点列表是否展开 */
    val subExpanded = remember { mutableStateMapOf<String, Boolean>() }
    /** 点播页当前源的完整 site key（"sub:…"），null=未设置 */
    var currentSubSiteKey by remember { mutableStateOf<String?>(null) }

    /** 解析订阅：下载 TVBox JSON，展开站点并落盘 */
    fun parseSubscription(subUrl: String) {
        if (subParsing[subUrl] == true) return
        // v0.9.0：用进程级 scope，用户切走页面刷新也要跑完
        //（composable scope 会在组合离开时取消，报 scope left the composition）
        SubSiteStore.appScope.launch {
            subParsing[subUrl] = true
            SubSiteStore.refresh(context, subUrl)
                .onSuccess { cache ->
                    subCaches[subUrl] = cache
                    val ok = cache.sites.count { it.kind != SubSiteStore.KIND_UNSUPPORTED }
                    val bad = cache.sites.size - ok
                    Toast.makeText(
                        context,
                        "解析完成：${cache.sites.size} 个站点，$ok 个可用" +
                            if (bad > 0) "，$bad 个不支持" else "",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                .onFailure { e ->
                    Toast.makeText(context, "解析失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            subParsing[subUrl] = false
        }
    }

    /** 设为点播页当前源 */
    fun setCurrentSubSite(subUrl: String, siteKey: String, siteName: String) {
        if (SubSiteStore.setCurrent(context, subUrl, siteKey)) {
            currentSubSiteKey = SubSiteStore.currentSiteKey(context)
            Toast.makeText(context, "点播页当前源：$siteName", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "设置失败：该站点不可用", Toast.LENGTH_SHORT).show()
        }
    }

    fun removeSubscription(entry: SourceEntry) {
        // v0.24.0：软删除进回收站
        TrashStore.trashSubscription(context, entry.name, entry.detail)
        apiGroup.entries.remove(entry)
        SubSiteStore.removeCache(context, entry.detail)
        currentSubSiteKey = SubSiteStore.currentSiteKey(context)
        persistApiGroup()
    }

    fun toggleSubscription() {
        persistApiGroup()
        // 被关掉的订阅若正好是当前源 -> 清掉，避免点播页读到无效源
        val cur = SubSiteStore.getCurrent(context)
        if (cur != null) {
            val stillOn = apiGroup.entries.any { it.detail == cur.subUrl && it.enabled }
            if (!stillOn) {
                SubSiteStore.clearCurrent(context)
                currentSubSiteKey = null
            }
        }
    }

    // ---------- 各 tab 刷新 ----------
    fun refreshDrpyRules() {
        drpyRules.clear()
        drpyRules.addAll(DrpySourceManager.listRules())
    }

    fun refreshPyRules() {
        pyRules.clear()
        pyRules.addAll(PySourceManager.listRules())
    }

    fun refreshPhpEntries() {
        phpEntries.clear()
        phpEntries.addAll(
            listLocalFolderEntries(context, "php", "PHP 规则", PhpSourceManager.activeRuleName()),
        )
    }

    fun refreshWebSites() {
        webSites.clear()
        webSites.addAll(WebSourceManager.listSites())
    }

    fun refreshTab(tab: String) {
        when (tab) {
            TAB_JS -> refreshDrpyRules()
            TAB_PY -> refreshPyRules()
            TAB_PHP -> refreshPhpEntries()
            TAB_WEB -> refreshWebSites()
        }
    }

    // ---------- v0.24.0：批量探活 ----------
    var probing by remember { mutableStateOf(false) }
    var probeStatus by remember { mutableStateOf("") }
    var probeJob by remember { mutableStateOf<Job?>(null) }
    val probeResults = remember { mutableStateMapOf<String, SourceProbe.Result>() }
    /** 启停状态流：toggle 后自动重组，无需手动刷新 */
    val disabledRules by SourceStateStore.disabled.collectAsState()

    /**
     * 对各 tab 已启用条目做轻量探活（调 home()，8 秒超时），不碰全局激活状态。
     * PHP 本地规则手机端无解释器，标"不支持"不探；订阅只探地址连通性。
     */
    fun runBatchProbe() {
        if (probing) return
        probing = true
        probeStatus = "准备中…"
        probeResults.clear()
        probeJob = SubSiteStore.appScope.launch {
            val targets = mutableListOf<Triple<String, String, suspend () -> SourceProbe.Result>>()
            DrpySourceManager.listRules().forEach { r ->
                if (SourceStateStore.isRuleEnabled(TAB_JS, r.name)) {
                    val f = File(StoragePaths.jsDir(context), r.name)
                    targets += Triple(TAB_JS, r.name, suspend { SourceProbe.probeJs(context, f) })
                }
            }
            PySourceManager.listRules().forEach { r ->
                if (SourceStateStore.isRuleEnabled(TAB_PY, r.name)) {
                    val f = File(StoragePaths.pyDir(context), r.name)
                    targets += Triple(TAB_PY, r.name, suspend { SourceProbe.probePy(context, f) })
                }
            }
            // PHP：手机端无解释器，不探
            phpEntries.forEach { e ->
                probeResults[probeKey(TAB_PHP, e.name)] = SourceProbe.Result.Unsupported
            }
            WebSourceManager.listSites().forEach { s ->
                if (SourceStateStore.isRuleEnabled(TAB_WEB, s.fileName)) {
                    targets += Triple(TAB_WEB, s.fileName, suspend { SourceProbe.probeHttp(s.url) })
                }
            }
            apiGroup.entries.filter { it.enabled }.forEach { e ->
                targets += Triple("sub", e.detail, suspend { SourceProbe.probeHttp(e.detail) })
            }
            supervisorScope {
                targets.map { (kind, name, fn) ->
                    async {
                        probeStatus = "$name"
                        probeResults[probeKey(kind, name)] = fn()
                    }
                }.awaitAll()
            }
            probing = false
            val ok = probeResults.count { it.value is SourceProbe.Result.Ok }
            val slow = probeResults.count { it.value is SourceProbe.Result.Slow }
            val fail = probeResults.count { it.value is SourceProbe.Result.Fail }
            Toast.makeText(
                context,
                "检测完成：正常 $ok，缓慢 $slow，失败 $fail",
                Toast.LENGTH_LONG,
            ).show()
            LogBus.i("Probe", "批量检测完成：正常 $ok / 缓慢 $slow / 失败 $fail")
        }
    }

    /** 规则文件启停开关（单点：首页/搜索/检测统一走 SourceStateStore） */
    fun toggleRuleEnabled(item: GridFileItem, enabled: Boolean) {
        SourceStateStore.setRuleEnabled(context, item.kind, item.fileName, enabled)
    }

    // ---------- 激活逻辑：照抄 HomeScreen 切源 BottomSheet ----------
    fun activateDrpy(name: String) {
        SubSiteStore.clearCurrent(context)
        PySourceManager.setActiveRule(null)
        DrpySourceManager.setActiveRule(name)
        SourceStateStore.setRuleEnabled(context, TAB_JS, name, true)
        refreshDrpyRules()
        Toast.makeText(context, "已切换为 $name", Toast.LENGTH_SHORT).show()
    }

    fun activatePy(name: String) {
        SubSiteStore.clearCurrent(context)
        DrpySourceManager.setActiveRule(null)
        PySourceManager.setActiveRule(name)
        SourceStateStore.setRuleEnabled(context, TAB_PY, name, true)
        refreshPyRules()
        Toast.makeText(context, "已切换为 $name", Toast.LENGTH_SHORT).show()
    }

    /** PHP 规则选中：仅做选中标记（手机端无 PHP 解释器，不可执行），如实 Toast。 */
    fun selectPhpRule(name: String) {
        PhpSourceManager.setActiveRule(name)
        refreshPhpEntries()
        Toast.makeText(
            context,
            "已选中 $name：${PhpSourceManager.UNSUPPORTED_REASON}",
            Toast.LENGTH_LONG,
        ).show()
    }

    fun activateWeb(site: WebSourceManager.WebSite) {
        WebSourceManager.setActiveSite(site.fileName)
        refreshWebSites()
        Toast.makeText(context, "已切换为 ${site.name}", Toast.LENGTH_SHORT).show()
    }

    fun openWebSniff(site: WebSourceManager.WebSite) {
        WebSourceManager.setActiveSite(site.fileName)
        refreshWebSites()
        onWebSniff(site.name, site.url)
    }

    // ---------- 网格条目组装（含激活态/置顶排序） ----------
    fun tabItems(tab: String): List<GridFileItem> {
        val tabPins = pins[tab].orEmpty()
        val subActive = SubSiteStore.getCurrent(context) != null
        fun itemEnabled(kind: String, fileName: String) =
            !disabledRules.getOrDefault(kind, emptySet()).contains(fileName)
        val raw: List<GridFileItem> = when (tab) {
            TAB_JS -> {
                val active = if (subActive) null else DrpySourceManager.activeRuleName()
                drpyRules.map {
                    GridFileItem(
                        TAB_JS, it.name, it.title.ifBlank { it.name },
                        "drpy 规则 · ${it.sizeKb} KB", it.name == active, false,
                        enabled = itemEnabled(TAB_JS, it.name),
                        probe = probeResults[probeKey(TAB_JS, it.name)],
                    )
                }
            }
            TAB_PY -> {
                val active = if (subActive) null else PySourceManager.activeRuleName()
                pyRules.map {
                    GridFileItem(
                        TAB_PY, it.name, it.title.ifBlank { it.name },
                        "hipy 规则 · ${it.sizeKb} KB", it.name == active, false,
                        enabled = itemEnabled(TAB_PY, it.name),
                        probe = probeResults[probeKey(TAB_PY, it.name)],
                    )
                }
            }
            TAB_PHP -> phpEntries.map {
                GridFileItem(
                    TAB_PHP, it.name, it.name, it.detail, it.enabled, false,
                    enabled = itemEnabled(TAB_PHP, it.name),
                    probe = probeResults[probeKey(TAB_PHP, it.name)],
                )
            }
            TAB_WEB -> {
                val active = WebSourceManager.activeSiteName()
                webSites.map {
                    GridFileItem(
                        TAB_WEB, it.fileName, it.name, it.url, it.fileName == active, false,
                        enabled = itemEnabled(TAB_WEB, it.fileName),
                        probe = probeResults[probeKey(TAB_WEB, it.fileName)],
                    )
                }
            }
            else -> emptyList()
        }
        return sortWithPins(raw, tabPins).map { it.copy(isPinned = tabPins.contains(it.fileName)) }
    }

    // ---------- 删除（v0.24.0：软删除进回收站，可恢复） ----------
    fun deleteItem(item: GridFileItem) {
        val dir = when (item.kind) {
            TAB_JS -> StoragePaths.jsDir(context)
            TAB_PY -> StoragePaths.pyDir(context)
            TAB_PHP -> StoragePaths.phpDir(context)
            else -> StoragePaths.webDir(context)
        }
        TrashStore.trashFile(context, item.kind, File(dir, item.fileName), item.displayName)
        when (item.kind) {
            TAB_JS -> if (DrpySourceManager.activeRuleName() == item.fileName) {
                DrpySourceManager.setActiveRule(null)
            }
            TAB_PY -> if (PySourceManager.activeRuleName() == item.fileName) {
                PySourceManager.setActiveRule(null)
            }
            TAB_PHP -> if (PhpSourceManager.activeRuleName() == item.fileName) {
                PhpSourceManager.setActiveRule(null)
            }
            TAB_WEB -> if (WebSourceManager.activeSiteName() == item.fileName) {
                WebSourceManager.setActiveSite(null)
            }
        }
        // 置顶记录同步清理
        pins[item.kind]?.let {
            it.remove(item.fileName)
            savePins(context, item.kind, it)
        }
        refreshTab(item.kind)
        Toast.makeText(context, "已移入回收站", Toast.LENGTH_SHORT).show()
    }

    // ---------- 置顶 ----------
    fun togglePin(item: GridFileItem) {
        val tabPins = pins.getOrPut(item.kind) { mutableListOf() }
        if (tabPins.contains(item.fileName)) {
            tabPins.remove(item.fileName)
            Toast.makeText(context, "已取消置顶", Toast.LENGTH_SHORT).show()
        } else {
            tabPins.add(item.fileName)
            Toast.makeText(context, "已置顶到首", Toast.LENGTH_SHORT).show()
        }
        savePins(context, item.kind, tabPins)
    }

    // ---------- 长按菜单 / 信息 / 删除确认 ----------
    var menuItem by remember { mutableStateOf<GridFileItem?>(null) }
    var infoItem by remember { mutableStateOf<GridFileItem?>(null) }
    var deleteItemState by remember { mutableStateOf<GridFileItem?>(null) }

    // ---------- 文件导入 ----------
    var pendingImport by remember { mutableStateOf<ImportTarget?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val target = pendingImport
        pendingImport = null
        if (target == null || uri == null) return@rememberLauncherForActivityResult
        when (val r = importRuleFile(context, uri, target)) {
            is ImportResult.Ok -> {
                when (target.dirName) {
                    "drpy" -> {
                        refreshDrpyRules()
                        if (DrpySourceManager.activeRuleName() == null) {
                            activateDrpy(r.fileName)
                        }
                        Toast.makeText(context, "已导入 ${r.fileName}", Toast.LENGTH_SHORT).show()
                    }
                    "py" -> {
                        refreshPyRules()
                        if (PySourceManager.activeRuleName() == null) {
                            activatePy(r.fileName)
                        }
                        Toast.makeText(context, "已导入 ${r.fileName}", Toast.LENGTH_SHORT).show()
                    }
                    "php" -> {
                        refreshPhpEntries()
                        Toast.makeText(
                            context,
                            "已保存到PHP文件夹，${PhpSourceManager.UNSUPPORTED_REASON}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    "web" -> {
                        refreshWebSites()
                        Toast.makeText(context, "已导入 ${r.fileName}，可直接打开嗅探", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        refreshTab(target.dirName)
                        Toast.makeText(
                            context,
                            "已保存到${target.label}，该类型执行引擎开发中",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            is ImportResult.WrongExt ->
                Toast.makeText(
                    context,
                    "请选择 ${target.extensions.joinToString(" / ")} 文件",
                    Toast.LENGTH_LONG,
                ).show()
            is ImportResult.Failed ->
                Toast.makeText(context, "导入失败：${r.reason}", Toast.LENGTH_LONG).show()
        }
    }

    fun requestImport(target: ImportTarget) {
        pendingImport = target
        importLauncher.launch("*/*")
    }

    LaunchedEffect(Unit) {
        refreshDrpyRules()
        refreshPyRules()
        refreshPhpEntries()
        refreshWebSites()
        listOf(TAB_JS, TAB_PY, TAB_PHP, TAB_WEB).forEach { pins[it] = loadPins(context, it) }
        // v0.5.1：读订阅展开缓存 + 点播页当前源
        apiGroup.entries.forEach { e ->
            SubSiteStore.getCached(context, e.detail)?.let { subCaches[e.detail] = it }
        }
        currentSubSiteKey = SubSiteStore.currentSiteKey(context)
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf(false) }

    // 网页站源添加对话框：名称 + URL（URL 必须 http(s) 开头）
    var showWebAddDialog by remember { mutableStateOf(false) }
    var webNameInput by remember { mutableStateOf("") }
    var webUrlInput by remember { mutableStateOf("") }
    var webUrlError by remember { mutableStateOf(false) }

    fun addWebSite() {
        val url = webUrlInput.trim()
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            webUrlError = true
            return
        }
        val site = WebSourceManager.addSite(webNameInput, url)
        if (site == null) {
            webUrlError = true
            return
        }
        refreshWebSites()
        webNameInput = ""
        webUrlInput = ""
        webUrlError = false
        showWebAddDialog = false
        Toast.makeText(context, "已添加 ${site.name}", Toast.LENGTH_SHORT).show()
    }

    fun addSubscription() {
        val url = urlInput.trim()
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            urlError = true
            return
        }
        val name = runCatching {
            url.substringAfter("://").substringBefore("/").substringBefore(":")
        }.getOrDefault("自定义订阅").ifBlank { "自定义订阅" }
        apiGroup.entries.add(0, SourceEntry(name, url))
        persistApiGroup()
        urlInput = ""
        urlError = false
        showAddDialog = false
        // v0.5.1：新订阅自动解析站点
        parseSubscription(url)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // v0.22.0：插件市场入口；v0.24.0：批量检测 + 回收站
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onMarketClick,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Store, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("插件市场")
            }
            OutlinedButton(
                onClick = { runBatchProbe() },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (probing) "检测中…" else "批量检测")
            }
            OutlinedButton(
                onClick = onTrashClick,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("回收站")
            }
        }
        // v0.9.0：顶部横滑 Tab——数据来源切换
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 8.dp,
        ) {
            tabs.forEachIndexed { index, (label, icon) ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(label) },
                    icon = { Icon(icon, contentDescription = null) },
                )
            }
        }

        when (selectedTab) {
            0 -> {
                // 接口站源 tab：订阅卡片原样保留
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加订阅")
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        SubscriptionCard(
                            group = apiGroup,
                            caches = subCaches,
                            parsing = subParsing,
                            expandedMap = subExpanded,
                            currentSiteKey = currentSubSiteKey,
                            probeResults = probeResults,
                            onRemove = ::removeSubscription,
                            onToggle = ::toggleSubscription,
                            onParse = ::parseSubscription,
                            onSetCurrent = ::setCurrentSubSite,
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
            else -> {
                val tab = when (selectedTab) {
                    1 -> TAB_JS
                    2 -> TAB_PY
                    3 -> TAB_PHP
                    else -> TAB_WEB
                }
                val badgeLabel = when (tab) {
                    TAB_JS -> "JS"
                    TAB_PY -> "PY"
                    TAB_PHP -> "PHP"
                    else -> "网页"
                }
                val onItemClick: (GridFileItem) -> Unit = { item ->
                    when (item.kind) {
                        TAB_JS -> activateDrpy(item.fileName)
                        TAB_PY -> activatePy(item.fileName)
                        TAB_PHP -> selectPhpRule(item.fileName)
                        TAB_WEB -> webSites.firstOrNull { it.fileName == item.fileName }
                            ?.let(::activateWeb)
                    }
                }
                // tab 顶部：文件数 + 导入/添加
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "共 ${tabItems(tab).size} 个文件 · 单击激活，长按更多操作",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (tab == TAB_WEB) {
                        TextButton(onClick = { showWebAddDialog = true }) { Text("添加") }
                        TextButton(onClick = { requestImport(IMPORT_WEB) }) { Text("导入") }
                    } else {
                        val target = when (tab) {
                            TAB_JS -> IMPORT_DPRY
                            TAB_PY -> IMPORT_PY
                            else -> IMPORT_PHP
                        }
                        TextButton(onClick = { requestImport(target) }) { Text("导入") }
                    }
                }
                FileGrid(
                    items = tabItems(tab),
                    badgeLabel = badgeLabel,
                    onClick = onItemClick,
                    onLongClick = { menuItem = it },
                    onToggleEnabled = ::toggleRuleEnabled,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // ---- 长按操作菜单（ModalBottomSheet） ----
    menuItem?.let { item ->
        ModalBottomSheet(onDismissRequest = { menuItem = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val file = File(previewKindDir(context, item.kind), item.fileName)
                MenuRow(Icons.Filled.Visibility, "文件预览") {
                    menuItem = null
                    onPreview(item.kind, item.fileName)
                }
                MenuRow(Icons.Filled.Edit, "文件编辑") {
                    menuItem = null
                    if (!openFileWithEditor(context, file)) {
                        Toast.makeText(context, "没有可用的文本编辑器", Toast.LENGTH_LONG).show()
                    }
                }
                MenuRow(Icons.Filled.Share, "分享文件") {
                    menuItem = null
                    if (!shareFile(context, file)) {
                        Toast.makeText(context, "分享失败", Toast.LENGTH_LONG).show()
                    }
                }
                MenuRow(Icons.Filled.Info, "文件信息") {
                    menuItem = null
                    infoItem = item
                }
                if (item.kind == TAB_WEB) {
                    // 网页源额外入口：打开嗅探浏览器（原单击行为）
                    MenuRow(Icons.Filled.Public, "打开嗅探浏览器") {
                        menuItem = null
                        webSites.firstOrNull { it.fileName == item.fileName }
                            ?.let(::openWebSniff)
                    }
                }
                MenuRow(
                    Icons.Filled.PushPin,
                    if (item.isPinned) "取消置顶" else "置顶到首",
                ) {
                    menuItem = null
                    togglePin(item)
                }
                MenuRow(Icons.Filled.Delete, "删除文件", destructive = true) {
                    menuItem = null
                    deleteItemState = item
                }
            }
        }
    }

    // ---- 文件信息 ----
    infoItem?.let { item ->
        val file = File(previewKindDir(context, item.kind), item.fileName)
        val modified = remember(item) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(file.lastModified()))
        }
        AlertDialog(
            onDismissRequest = { infoItem = null },
            title = { Text("文件信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("文件名：${item.fileName}")
                    Text("大小：${formatSize(file.length())}")
                    Text("修改时间：$modified")
                    Text("路径：${file.absolutePath}")
                }
            },
            confirmButton = {
                TextButton(onClick = { infoItem = null }) { Text("关闭") }
            },
        )
    }

    // ---- 删除二次确认 ----
    deleteItemState?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteItemState = null },
            title = { Text("删除文件") },
            text = { Text("将「${item.fileName}」移入回收站，可随时恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteItemState = null
                        deleteItem(item)
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteItemState = null }) { Text("取消") }
            },
        )
    }

    // ---- v0.24.0：批量检测进度 ----
    if (probing) {
        AlertDialog(
            onDismissRequest = {
                probeJob?.cancel()
                probing = false
            },
            title = { Text("批量检测中") },
            text = {
                Column {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "正在检测：$probeStatus",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "已完成 ${probeResults.size} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        probeJob?.cancel()
                        probing = false
                    },
                ) { Text("取消") }
            },
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加订阅") },
            text = {
                Column {
                    Text(
                        text = "输入站源订阅地址（CMS 接口 JSON）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it; urlError = false },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://…/api.php/provide/vod/") },
                        singleLine = true,
                        isError = urlError,
                        supportingText = if (urlError) {
                            { Text("请输入以 http:// 或 https:// 开头的有效地址") }
                        } else null,
                    )
                }
            },
            confirmButton = {
                Button(onClick = ::addSubscription) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            },
        )
    }

    if (showWebAddDialog) {
        AlertDialog(
            onDismissRequest = { showWebAddDialog = false },
            title = { Text("添加网页站源") },
            text = {
                Column {
                    Text(
                        text = "输入网站名称和地址，长按条目菜单可打开嗅探浏览器",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = webNameInput,
                        onValueChange = { webNameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("名称") },
                        placeholder = { Text("如：示例影视站") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = webUrlInput,
                        onValueChange = { webUrlInput = it; webUrlError = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("网址") },
                        placeholder = { Text("https://…") },
                        singleLine = true,
                        isError = webUrlError,
                        supportingText = if (webUrlError) {
                            { Text("请输入以 http:// 或 https:// 开头的有效地址") }
                        } else null,
                    )
                }
            },
            confirmButton = {
                Button(onClick = ::addWebSite) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showWebAddDialog = false }) { Text("取消") }
            },
        )
    }
}

/** 双列文件网格：单击激活，长按出操作菜单 */
@Composable
private fun FileGrid(
    items: List<GridFileItem>,
    badgeLabel: String,
    onClick: (GridFileItem) -> Unit,
    onLongClick: (GridFileItem) -> Unit,
    onToggleEnabled: (GridFileItem, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Column(
            modifier = modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "该分组暂无文件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "点右上角「导入/添加」，或把文件拷到手机 xiaying 目录对应文件夹",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.kind + "/" + it.fileName }) { item ->
            FileCard(
                item = item,
                badgeLabel = badgeLabel,
                onClick = { onClick(item) },
                onLongClick = { onLongClick(item) },
                onToggleEnabled = onToggleEnabled,
            )
        }
    }
}

/** 网格卡片：类型徽标 + 文件名 + 激活打勾 + 启停开关 + 探活徽标 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FileCard(
    item: GridFileItem,
    badgeLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleEnabled: (GridFileItem, Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (item.enabled) 1f else 0.45f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                ) {
                    Text(
                        text = badgeLabel,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (item.isPinned) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = "已置顶",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                ProbeDot(item.probe)
                if (item.isActive) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "当前使用",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (item.isActive) FontWeight.Bold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(40.dp),
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // v0.24.0：源级启停（禁用后首页/搜索/检测跳过该源）
                Switch(
                    checked = item.enabled,
                    onCheckedChange = { onToggleEnabled(item, it) },
                )
            }
        }
    }
}

/** 长按菜单行 */
@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (destructive) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 类型徽标：CMS / drpy / PY / PHP / spider / 不支持 */
@Composable
private fun KindBadge(kind: String) {
    val (label, color) = when (kind) {
        SubSiteStore.KIND_CMS -> "CMS" to MaterialTheme.colorScheme.primary
        SubSiteStore.KIND_DRPY -> "drpy" to MaterialTheme.colorScheme.tertiary
        SubSiteStore.KIND_PY -> "PY" to MaterialTheme.colorScheme.secondary
        SubSiteStore.KIND_TYPE4 -> "PHP" to MaterialTheme.colorScheme.tertiary
        SubSiteStore.KIND_SPIDER -> "spider" to MaterialTheme.colorScheme.secondary
        else -> "不支持" to MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 接口站源卡（v0.5.1）。
 * 每条启用的订阅可展开站点列表：站名 + 类型徽标（CMS/drpy/PY/PHP/不支持）；
 * 点可用站点即"设为点播页当前源"（持久化），点"不支持"的站点 Toast 说明原因。
 */
@Composable
private fun SubscriptionCard(
    group: SourceGroup,
    caches: SnapshotStateMap<String, SubCache>,
    parsing: SnapshotStateMap<String, Boolean>,
    expandedMap: SnapshotStateMap<String, Boolean>,
    currentSiteKey: String?,
    probeResults: SnapshotStateMap<String, SourceProbe.Result>,
    onRemove: (SourceEntry) -> Unit,
    onToggle: () -> Unit,
    onParse: (String) -> Unit,
    onSetCurrent: (subUrl: String, siteKey: String, siteName: String) -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // 分组头
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = group.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                BadgedBox(badge = { Badge { Text(group.entries.size.toString()) } }) {
                    Spacer(Modifier.size(4.dp))
                }
                Spacer(Modifier.width(12.dp))
                IconButton(
                    onClick = { group.expanded = !group.expanded },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (group.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (group.expanded) "收起" else "展开",
                    )
                }
            }

            AnimatedVisibility(visible = group.expanded) {
                Column {
                    if (group.entries.isEmpty()) {
                        Text(
                            text = "暂无订阅，点上方「添加订阅」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    group.entries.forEach { entry ->
                        val subUrl = entry.detail
                        val expanded = expandedMap[subUrl] == true
                        Column(
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = subUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Switch(
                                    checked = entry.enabled,
                                    onCheckedChange = {
                                        entry.enabled = it
                                        onToggle()
                                    },
                                )
                                // v0.24.0：批量检测徽标
                                ProbeDot(probeResults[probeKey("sub", subUrl)])
                                IconButton(onClick = { onRemove(entry) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "删除",
                                    )
                                }
                                IconButton(
                                    onClick = { expandedMap[subUrl] = !expanded },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                        contentDescription = if (expanded) "收起站点" else "展开站点",
                                    )
                                }
                            }

                            AnimatedVisibility(visible = expanded) {
                                val cache = caches[subUrl]
                                val isParsing = parsing[subUrl] == true
                                Column(
                                    modifier = Modifier.padding(
                                        start = 16.dp, end = 8.dp, bottom = 8.dp,
                                    ),
                                ) {
                                    when {
                                        isParsing -> {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(vertical = 8.dp),
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = "正在解析订阅…",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        cache == null -> {
                                            TextButton(onClick = { onParse(subUrl) }) {
                                                Text("解析站点")
                                            }
                                        }
                                        else -> {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Text(
                                                    text = "站点（${cache.sites.size}）· 点站点设为点播页当前源",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                TextButton(onClick = { onParse(subUrl) }) {
                                                    Text("刷新")
                                                }
                                            }
                                            cache.sites.forEach { rec ->
                                                val key = SubSiteStore.siteKey(subUrl, rec)
                                                val isCurrent = key == currentSiteKey
                                                val supported =
                                                    rec.kind != SubSiteStore.KIND_UNSUPPORTED
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            if (supported) {
                                                                onSetCurrent(subUrl, rec.key, rec.name)
                                                            } else {
                                                                Toast.makeText(
                                                                    context,
                                                                    rec.reason.ifBlank { "该站点暂不支持" },
                                                                    Toast.LENGTH_LONG,
                                                                ).show()
                                                            }
                                                        }
                                                        .padding(vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = rec.name,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    KindBadge(rec.kind)
                                                    Spacer(Modifier.width(8.dp))
                                                    // v0.24.0：站点级启停（禁用后聚合搜索跳过）
                                                    Switch(
                                                        checked = rec.enabled,
                                                        onCheckedChange = { enabled ->
                                                            SubSiteStore.setSiteEnabled(
                                                                context, subUrl, rec.key, enabled,
                                                            )
                                                            SubSiteStore.getCached(context, subUrl)?.let {
                                                                caches[subUrl] = it
                                                            }
                                                        },
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    if (isCurrent) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Check,
                                                            contentDescription = "点播页当前源",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(20.dp),
                                                        )
                                                    } else {
                                                        Spacer(Modifier.size(20.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
