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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.ApiSource
import com.movieshell.data.repo.ApiSourceStore
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.PhpSourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SourceProbe
import com.movieshell.data.repo.toResult
import com.movieshell.data.repo.SourceProbeStore
import com.movieshell.data.repo.SourceStateStore
import com.movieshell.data.repo.StoragePaths
import com.movieshell.data.repo.SubCache
import com.movieshell.data.repo.SubSiteRecord
import com.movieshell.data.repo.SubRefreshStore
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.data.repo.TrashStore
import com.movieshell.data.repo.WarehouseSync
import com.movieshell.data.repo.WebSourceManager
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.CapsuleTabBar
import com.movieshell.ui.components.AppChip
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.SectionTitle
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.trash.TrashScreen
import com.movieshell.ui.preview.openFileWithEditor
import com.movieshell.ui.preview.previewKindDir
import com.movieshell.ui.preview.shareFile
import com.movieshell.ui.theme.LocalBrandGradient
import com.movieshell.ui.theme.LocalSoftExtra
import com.movieshell.ui.theme.SoftDimens
import com.movieshell.ui.theme.SoftType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.movieshell.data.source.SourceProbeWorker

/** 站源条目：开关/备注/分组状态用 mutableStateOf 包裹，保证变更即时刷新 */
class SourceEntry(
    val name: String,
    val detail: String,
    enabled: Boolean = true,
    note: String = "",
    group: String = "",
    /** v0.122.0：多仓自动子仓的父仓 URL；空串=顶层订阅 */
    val parentUrl: String = "",
) {
    var enabled by mutableStateOf(enabled)
    var note by mutableStateOf(note)
    var group by mutableStateOf(group)
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
    "php" -> PhpSourceManager.rulesDir(context)
    "web" -> StoragePaths.webDir(context)
    else -> File(context.filesDir, dirName)
}

/** 文件导入目标：规则子目录 + 允许的后缀（目录实际位置见 targetDir，走 StoragePaths） */
internal data class ImportTarget(
    val dirName: String,          // 逻辑子目录：drpy / py / php
    val extensions: List<String>, // 允许的后缀，带点，如 ".js"
    val label: String,            // 分组名，用于 Toast 提示
)

private val IMPORT_DPRY = ImportTarget("drpy", listOf(".js"), "JS文件夹")
internal val IMPORT_PY = ImportTarget("py", listOf(".py"), "PY文件夹")
private val IMPORT_PHP = ImportTarget("php", listOf(".php"), "PHP文件夹")
private val IMPORT_WEB = ImportTarget("web", listOf(".html", ".htm"), "网页源")

/** 导入结果 */
internal sealed interface ImportResult {
    data class Ok(val fileName: String) : ImportResult
    object WrongExt : ImportResult
    data class Failed(val reason: String) : ImportResult
}

/**
 * 把用户选中的文件拷贝到规则目录（见 targetDir）。
 * - 文件名取原名（重名则加时间戳后缀避免覆盖）
 * - 后缀不在允许列表内 -> WrongExt
 */
internal fun importRuleFile(context: Context, uri: Uri, target: ImportTarget): ImportResult {
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
    filter: ((File) -> Boolean)? = null,
): List<SourceEntry> {
    val dir = targetDir(context, dirName)
    return dir.listFiles { f -> f.isFile && (filter?.invoke(f) ?: true) }
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

/** v0.86.0：批量检测目标（检测 tab 列表展示用） */
private data class ProbeTarget(
    val kind: String,
    val name: String,
    val label: String,
)

/** 探活徽标颜色（Soft Tech 柔和版） */
@Composable
private fun ProbeDot(probe: SourceProbe.Result?) {
    val soft = LocalSoftExtra.current
    val color = when (probe) {
        is SourceProbe.Result.Ok -> soft.success
        is SourceProbe.Result.Slow -> soft.warning
        is SourceProbe.Result.Fail -> soft.errorSoft
        is SourceProbe.Result.Unsupported ->
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        null -> return
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
    Spacer(Modifier.width(4.dp))
}

/** v0.86.0：检测 tab 的逐源结果行——名称 + 状态徽标 + 耗时/失败原因 */
@Composable
private fun ProbeResultRow(target: ProbeTarget, result: SourceProbe.Result?) {
    val soft = LocalSoftExtra.current
    val (label, color) = when (result) {
        is SourceProbe.Result.Ok -> "${result.ms}ms" to soft.success
        is SourceProbe.Result.Slow -> "${result.ms}ms" to soft.warning
        is SourceProbe.Result.Fail -> result.reason to soft.errorSoft
        is SourceProbe.Result.Unsupported -> "不支持" to
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        null -> "待检测" to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val statusText = when (result) {
        is SourceProbe.Result.Ok -> "正常"
        is SourceProbe.Result.Slow -> "缓慢"
        is SourceProbe.Result.Fail -> "失败"
        is SourceProbe.Result.Unsupported -> "不支持"
        null -> "待检测"
    }
    AppCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = target.label,
                style = SoftType.Body,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = statusText,
                style = SoftType.Caption,
                color = color,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 置顶优先（按置顶顺序），其余按展示名排序 */
private fun sortWithPins(items: List<GridFileItem>, pins: List<String>): List<GridFileItem> {    val order = pins.withIndex().associate { it.value to it.index }
    return items.sortedWith(
        compareBy({ order[it.fileName] ?: Int.MAX_VALUE }, { it.displayName.lowercase() }),
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SourcesScreen(
    onWebSniff: (name: String, url: String) -> Unit = { _, _ -> },
    onPreview: (kind: String, fileName: String) -> Unit = { _, _ -> },
    // v0.90.0：通知点进来可直达检测 tab（MainActivity 传 tab 参数）
    initialTab: Int = 0,
    // v0.108.0：点站点设为当前源后顺手切回主页 tab（待 MainActivity 接线：navController.navigateTab("home")）
    onGoHome: () -> Unit = {},
) {
    val context = LocalContext.current

    // v0.27.4：单页两 section（订阅源 / 本地源），不再分 tab

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
                .map { SourceEntry(it.name, it.url, it.enabled, it.note, it.group, it.parentUrl) }
                .toMutableStateList(),
        )
    }

    /** 接口站源持久化：增删/开关/备注分组后写回 filesDir/api_sources.json，供备份使用 */
    fun persistApiGroup() {
        ApiSourceStore.save(
            context,
            apiGroup.entries.map {
                ApiSource(it.name, it.detail, it.enabled, it.note, it.group, it.parentUrl)
            },
        )
    }

    /** v0.122.0：多仓同步直接改了 ApiSourceStore，UI 列表从存储重读对齐 */
    fun reloadApiGroup() {
        apiGroup.entries.clear()
        apiGroup.entries.addAll(
            ApiSourceStore.load(context).map {
                SourceEntry(it.name, it.url, it.enabled, it.note, it.group, it.parentUrl)
            },
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

    /** 解析订阅：下载 TVBox JSON，展开站点并落盘。quiet=true 不弹结果 Toast（多仓子仓自动解析用，避免连环弹） */
    fun parseSubscription(subUrl: String, quiet: Boolean = false) {
        if (subParsing[subUrl] == true) return
        // v0.9.0：用进程级 scope，用户切走页面刷新也要跑完
        //（composable scope 会在组合离开时取消，报 scope left the composition）
        SubSiteStore.appScope.launch {
            subParsing[subUrl] = true
            SubSiteStore.refresh(context, subUrl)
                .onSuccess { cache ->
                    // v0.46.0：手动刷新也记状态，"已失效" badge 及时更新
                    SubRefreshStore.recordResult(context, subUrl, true)
                    subCaches[subUrl] = cache
                    // v0.43.0：多仓 Toast 单独文案
                    if (cache.warehouses.isNotEmpty()) {
                        // v0.122.0：多仓展开为独立子仓订阅——同步增删后对齐 UI 列表，
                        // 新增子仓静默自动解析展开（子仓本身又是多仓时 WarehouseSync
                        // 按 parentUrl 拦截，只展开一层）
                        val parentEntry = apiGroup.entries.firstOrNull { it.detail == subUrl }
                        if (parentEntry != null) {
                            val added = WarehouseSync.syncChildren(
                                context,
                                ApiSource(
                                    parentEntry.name, subUrl, parentEntry.enabled,
                                    parentEntry.note, parentEntry.group, parentEntry.parentUrl,
                                ),
                                cache.warehouses,
                            )
                            reloadApiGroup()
                            added.forEach { parseSubscription(it, quiet = true) }
                        }
                        if (!quiet) {
                            Toast.makeText(
                                context,
                                "多仓解析完成：${cache.warehouses.size} 个子仓",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        return@onSuccess
                    }
                    if (!quiet) {
                        val ok = cache.sites.count { it.kind != SubSiteStore.KIND_UNSUPPORTED }
                        val bad = cache.sites.size - ok
                        Toast.makeText(
                            context,
                            "解析完成：${cache.sites.size} 个站点，$ok 个可用" +
                                if (bad > 0) "，$bad 个不支持" else "",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                .onFailure { e ->
                    // v0.46.0：手动刷新失败也记，"已失效" badge 及时更新
                    SubRefreshStore.recordResult(context, subUrl, false)
                    if (!quiet) {
                        Toast.makeText(context, "解析失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            subParsing[subUrl] = false
        }
    }

    /** 设为点播页当前源（v0.108.0：成功后顺手切回主页 tab） */
    fun setCurrentSubSite(subUrl: String, siteKey: String, siteName: String) {
        if (SubSiteStore.setCurrent(context, subUrl, siteKey)) {
            currentSubSiteKey = SubSiteStore.currentSiteKey(context)
            Toast.makeText(context, "点播页当前源：$siteName", Toast.LENGTH_SHORT).show()
            onGoHome()
        } else {
            Toast.makeText(context, "设置失败：该站点不可用", Toast.LENGTH_SHORT).show()
        }
    }

    fun removeSubscription(entry: SourceEntry) {
        // v0.24.0：软删除进回收站
        TrashStore.trashSubscription(context, entry.name, entry.detail)
        apiGroup.entries.remove(entry)
        // v0.122.0：删父仓连带删其自动子仓（随父删，记录+缓存一起清）；
        // 删的若是子仓本身，removeChildren 按它的 URL 找不到子记录，无副作用
        WarehouseSync.removeChildren(context, entry.detail)
        apiGroup.entries.removeAll { it.parentUrl == entry.detail }
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
            listLocalFolderEntries(context, "php", "PHP 规则", PhpSourceManager.activeRuleName(), PhpSourceManager::isSourceFile),
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

    // ---------- v0.90.0：批量检测后台化（SourceProbeWorker 跑，结果落 SourceProbeStore） ----------
    /** 探活徽标：key="kind:name" -> Result（Worker 写 store，这里只读） */
    val probeMap by SourceProbeStore.map.collectAsState()
    val probeReport by SourceProbeStore.report.collectAsState()

    /** 取某源的检测结果（各 tab 徽标用） */
    fun probeOf(kind: String, name: String): SourceProbe.Result? =
        probeMap[SourceProbeStore.probeKey(kind, name)]?.toResult()

    /** 派生给 SubscriptionCard 的只读视图（key -> Result） */
    val probeResultView = remember(probeMap) {
        probeMap.mapValues { it.value.toResult() }
    }

    LaunchedEffect(Unit) { SourceProbeStore.load(context) }

    // Worker 运行状态（后台检测进度：done/total/label）
    val wm = remember { WorkManager.getInstance(context) }
    val probeWorkInfos by remember(wm) {
        wm.getWorkInfosForUniqueWorkFlow(SourceProbeWorker.WORK_NAME)
    }.collectAsState(initial = emptyList())
    val probeWork = probeWorkInfos.firstOrNull {
        it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
    }
    val probeRunning = probeWork != null
    val probeDone = probeWork?.progress?.getInt("done", 0) ?: 0
    val probeTotal = probeWork?.progress?.getInt("total", 0) ?: 0
    val probeLabel = probeWork?.progress?.getString("label").orEmpty()
    var showProbeDialog by remember { mutableStateOf(false) }

    /**
     * 开始检测：已在跑则提示，否则 enqueue 唯一任务并弹出进度框。
     * isRunningBlocking 是阻塞读，放 IO 线程。
     */
    fun startProbe() {
        SubSiteStore.appScope.launch(Dispatchers.IO) {
            if (SourceProbeWorker.isRunningBlocking(context)) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "检测正在进行中", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            SourceProbeWorker.start(context)
            withContext(Dispatchers.Main) { showProbeDialog = true }
        }
    }
    /** 启停状态流：toggle 后自动重组，无需手动刷新 */
    val disabledRules by SourceStateStore.disabled.collectAsState()

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
        SourceStateStore.saveActiveRule(context, TAB_JS, name)
        refreshDrpyRules()
        Toast.makeText(context, "已切换为 $name", Toast.LENGTH_SHORT).show()
    }

    fun activatePy(name: String) {
        SubSiteStore.clearCurrent(context)
        DrpySourceManager.setActiveRule(null)
        PySourceManager.setActiveRule(name)
        SourceStateStore.setRuleEnabled(context, TAB_PY, name, true)
        SourceStateStore.saveActiveRule(context, TAB_PY, name)
        refreshPyRules()
        Toast.makeText(context, "已切换为 $name", Toast.LENGTH_SHORT).show()
    }

    /** PHP 规则选中：v0.151.0 起走 CLI 引擎，选中后首页/详情/播放直接出内容。 */
    fun selectPhpRule(name: String) {
        PhpSourceManager.setActiveRule(name)
        SourceStateStore.saveActiveRule(context, TAB_PHP, name)
        refreshPhpEntries()
        Toast.makeText(context, "已选中 $name", Toast.LENGTH_SHORT).show()
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
                        probe = probeOf(TAB_JS, it.name),
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
                        probe = probeOf(TAB_PY, it.name),
                    )
                }
            }
            TAB_PHP -> phpEntries.map {
                GridFileItem(
                    TAB_PHP, it.name, it.name, it.detail, it.enabled, false,
                    enabled = itemEnabled(TAB_PHP, it.name),
                    probe = probeOf(TAB_PHP, it.name),
                )
            }
            TAB_WEB -> {
                val active = WebSourceManager.activeSiteName()
                webSites.map {
                    GridFileItem(
                        TAB_WEB, it.fileName, it.name, it.url, it.fileName == active, false,
                        enabled = itemEnabled(TAB_WEB, it.fileName),
                        probe = probeOf(TAB_WEB, it.fileName),
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
            TAB_PHP -> PhpSourceManager.rulesDir(context)
            else -> StoragePaths.webDir(context)
        }
        TrashStore.trashFile(context, item.kind, File(dir, item.fileName), item.displayName)
        when (item.kind) {
            TAB_JS -> if (DrpySourceManager.activeRuleName() == item.fileName) {
                DrpySourceManager.setActiveRule(null)
                SourceStateStore.clearActiveRule(context)
            }
            TAB_PY -> if (PySourceManager.activeRuleName() == item.fileName) {
                PySourceManager.setActiveRule(null)
                SourceStateStore.clearActiveRule(context)
            }
            TAB_PHP -> if (PhpSourceManager.activeRuleName() == item.fileName) {
                PhpSourceManager.setActiveRule(null)
                SourceStateStore.clearActiveRule(context)
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
    // v0.108.0：订阅删除二次确认（挂起的待删订阅，弹窗确认后才进回收站）
    var pendingDeleteSub by remember { mutableStateOf<SourceEntry?>(null) }
    // v0.112.0：订阅级备注/分组编辑（挂起的待编辑订阅）
    var pendingEditSub by remember { mutableStateOf<SourceEntry?>(null) }

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
                            "已保存到PHP文件夹",
                            Toast.LENGTH_SHORT,
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
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf(false) }
    // v0.112.0：订阅级备注/分组（添加时可填）
    var noteInput by remember { mutableStateOf("") }
    var groupInput by remember { mutableStateOf("") }

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
        // v0.42.0：名称选填，不填则按域名自动生成
        val inputName = nameInput.trim()
        val name = if (inputName.isNotBlank()) inputName else runCatching {
            url.substringAfter("://").substringBefore("/").substringBefore(":")
        }.getOrDefault("自定义订阅").ifBlank { "自定义订阅" }
        apiGroup.entries.add(0, SourceEntry(name, url, note = noteInput.trim(), group = groupInput.trim()))
        persistApiGroup()
        nameInput = ""
        urlInput = ""
        noteInput = ""
        groupInput = ""
        urlError = false
        showAddDialog = false
        // v0.5.1：新订阅自动解析站点
        parseSubscription(url)
    }

    // v0.27.4：本地源引擎筛选（全部/JS/PY/PHP）
    var localEngine by remember { mutableStateOf("all") }

    val webItems = tabItems(TAB_WEB)
    val localItems = when (localEngine) {
        TAB_JS -> tabItems(TAB_JS)
        TAB_PY -> tabItems(TAB_PY)
        TAB_PHP -> tabItems(TAB_PHP)
        else -> tabItems(TAB_JS) + tabItems(TAB_PY) + tabItems(TAB_PHP)
    }
    val onLocalClick: (GridFileItem) -> Unit = { item ->
        when (item.kind) {
            TAB_JS -> activateDrpy(item.fileName)
            TAB_PY -> activatePy(item.fileName)
            TAB_PHP -> selectPhpRule(item.fileName)
        }
    }
    fun localBadge(item: GridFileItem) = when (item.kind) {
        TAB_JS -> "JS"
        TAB_PY -> "PY"
        TAB_PHP -> "PHP"
        else -> "？"
    }

    // v0.86.0：四 tab——订阅源 / 本地源 / 批量检测 / 回收站（检测与回收站收进页内）
    // v0.90.0：通知可带 tab 参数直达（如检测 tab）
    var subTab by rememberSaveable { mutableIntStateOf(initialTab) }

    /** 订阅源 tab：v0.104.0 订阅 2 列卡片（对标 DsPlayer 资源页）+ 网页源 */
    @Composable
    fun SubscriptionTab() {
        // v0.112.0：订阅分组筛选（分组改名/删光后过滤自动回退到全部）
        var subGroupFilter by remember { mutableStateOf("") }
        val subGroups =
            apiGroup.entries.map { it.group.trim() }.filter { it.isNotBlank() }.distinct()
        val effectiveFilter =
            if (subGroupFilter.isNotBlank() && subGroups.none { it == subGroupFilter }) ""
            else subGroupFilter
        // v0.133.0：同一 URL 可重复入列（手动添加不查重、备份恢复不去重；
        // WarehouseSync 只防自己新增子仓），渲染前按 URL 去重，防 LazyGrid key 撞车
        val visibleSubs = (if (effectiveFilter.isBlank()) apiGroup.entries
        else apiGroup.entries.filter { it.group.trim() == effectiveFilter })
            .distinctBy { it.detail }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        // ===== 订阅源 =====
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionTitle(title = "订阅源")
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(
                    text = "添加订阅",
                    onClick = { showAddDialog = true },
                    modifier = Modifier.weight(1f),
                    variant = AppButtonVariant.Primary,
                )
                AppButton(
                    text = if (probeRunning) "检测中 $probeDone/$probeTotal…" else "批量检测",
                    onClick = { startProbe() },
                    enabled = !probeRunning,
                    modifier = Modifier.weight(1f),
                    variant = AppButtonVariant.Ghost,
                )
            }
        }
        // v0.112.0：订阅分组 chips 筛选（只有订阅被标过分组才出现）
        if (subGroups.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AppChip(
                        text = "全部",
                        small = true,
                        selected = effectiveFilter.isBlank(),
                        onClick = { subGroupFilter = "" },
                    )
                    subGroups.forEach { g ->
                        AppChip(
                            text = g,
                            small = true,
                            selected = effectiveFilter == g,
                            onClick = { subGroupFilter = if (effectiveFilter == g) "" else g },
                        )
                    }
                }
            }
        }
        // v0.104.0：检测状态行
        item(span = { GridItemSpan(maxLineSpan) }) {
            val statusText = when {
                probeRunning -> "检测中 $probeDone/$probeTotal…"
                probeReport.time > 0 -> {
                    val rs = probeReport.records
                    "上次检测：${SourceProbeStore.agoText(probeReport.time)} · " +
                        "正常 ${rs.count { it.status == "OK" }} / " +
                        "缓慢 ${rs.count { it.status == "SLOW" }} / " +
                        "失败 ${rs.count { it.status == "FAIL" }}"
                }
                else -> "尚未检测"
            }
            Text(
                text = statusText,
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (apiGroup.entries.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    kind = EmptyKind.Empty,
                    icon = Icons.Filled.Language,
                    title = "暂无订阅",
                    hint = "点「添加订阅」新增",
                )
            }
        } else {
            // v0.104.0：每张卡片一个订阅；展开态占满整行
            visibleSubs.forEach { entry ->
                val subUrl = entry.detail
                val expanded = subExpanded[subUrl] == true
                val probe = probeResultView[SourceProbeStore.probeKey("sub", subUrl)]
                val refreshStatus = SubRefreshStore.statusOf(context, subUrl)
                if (expanded) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "exp:$subUrl") {
                        SubscriptionExpandedCard(
                            entry = entry,
                            subUrl = subUrl,
                            probe = probe,
                            probeTime = probeReport.time,
                            cache = subCaches[subUrl],
                            refreshStatus = refreshStatus,
                            currentSiteKey = currentSubSiteKey,
                            caches = subCaches,
                            parsingMap = subParsing,
                            expandedMap = subExpanded,
                            onToggle = { entry.enabled = it; toggleSubscription() },
                            onRemove = { pendingDeleteSub = entry },
                            onCollapse = { subExpanded[subUrl] = false },
                            onParse = ::parseSubscription,
                            onSetCurrent = ::setCurrentSubSite,
                            onEdit = { pendingEditSub = entry },
                        )
                    }
                } else {
                    item(key = "card:$subUrl") {
                        SubscriptionGridCard(
                            entry = entry,
                            subUrl = subUrl,
                            probe = probe,
                            probeTime = probeReport.time,
                            cache = subCaches[subUrl],
                            refreshStatus = refreshStatus,
                            onToggle = { entry.enabled = it; toggleSubscription() },
                            onRemove = { pendingDeleteSub = entry },
                            onExpand = { subExpanded[subUrl] = true },
                            onEdit = { pendingEditSub = entry },
                        )
                    }
                }
            }
        }
        // ===== 网页源（外观不变，整行占位） =====
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "网页源 · ${webItems.size} 个",
                    style = SoftType.CardTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = "添加",
                    onClick = { showWebAddDialog = true },
                    variant = AppButtonVariant.Ghost,
                )
                AppButton(
                    text = "导入",
                    onClick = { requestImport(IMPORT_WEB) },
                    variant = AppButtonVariant.Ghost,
                )
            }
        }
        if (webItems.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    kind = EmptyKind.Empty,
                    icon = Icons.Filled.Public,
                    title = "暂无网页源",
                    hint = "点「添加」手动新增",
                )
            }
        } else {
            webItems.forEach { item ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "web:${item.fileName}") {
                    FileCard(
                        item = item,
                        badgeLabel = "网页",
                        onClick = {
                            webSites.firstOrNull { it.fileName == item.fileName }
                                ?.let(::activateWeb)
                        },
                        onLongClick = { menuItem = item },
                        onToggleEnabled = ::toggleRuleEnabled,
                    )
                }
            }
        }
        }
    }

    /** 本地源 tab：PY / JS / drpy / JAR 文件网格 + 导入入口 */
    @Composable
    fun LocalTab() {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            // v0.141.0：卡片缩成单行后行距同步收紧
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        // ===== 本地源 =====
        item {
            SectionTitle(title = "本地源")
        }
        item {
            Text(
                text = "本地规则文件（PY / JS / drpy / JAR），单击设为当前源",
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppChip(text = "全部", selected = localEngine == "all", onClick = { localEngine = "all" })
                AppChip(text = "JS", selected = localEngine == TAB_JS, onClick = { localEngine = TAB_JS })
                AppChip(text = "PY", selected = localEngine == TAB_PY, onClick = { localEngine = TAB_PY })
                AppChip(text = "PHP", selected = localEngine == TAB_PHP, onClick = { localEngine = TAB_PHP })
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "共 ${localItems.size} 个规则 · 单击激活，长按更多操作",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                when (localEngine) {
                    TAB_JS -> AppButton(text = "导入", onClick = { requestImport(IMPORT_DPRY) }, variant = AppButtonVariant.Ghost)
                    TAB_PY -> AppButton(text = "导入", onClick = { requestImport(IMPORT_PY) }, variant = AppButtonVariant.Ghost)
                    TAB_PHP -> AppButton(text = "导入", onClick = { requestImport(IMPORT_PHP) }, variant = AppButtonVariant.Ghost)
                    else -> {
                        AppButton(text = "JS", onClick = { requestImport(IMPORT_DPRY) }, variant = AppButtonVariant.Ghost)
                        AppButton(text = "PY", onClick = { requestImport(IMPORT_PY) }, variant = AppButtonVariant.Ghost)
                        AppButton(text = "PHP", onClick = { requestImport(IMPORT_PHP) }, variant = AppButtonVariant.Ghost)
                    }
                }
            }
        }
        if (localItems.isEmpty()) {
            item {
                EmptyState(
                    kind = EmptyKind.Empty,
                    icon = Icons.Filled.FolderOpen,
                    title = "暂无本地规则",
                    hint = "点「导入」添加 .py / .js / .php 文件",
                )
            }
        } else {
            items(localItems.chunked(2)) { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { item ->
                        FileCard(
                            item = item,
                            badgeLabel = localBadge(item),
                            onClick = { onLocalClick(item) },
                            onLongClick = { menuItem = item },
                            onToggleEnabled = ::toggleRuleEnabled,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
        }
    }

    /** v0.90.0：批量检测 tab——后台 Worker 跑，读 SourceProbeStore 展示上次结果 + 实时进度 */
    @Composable
    fun ProbeTab() {
        // v0.133.0：重复订阅会让探活任务同 kind+name 成对出现，渲染前去重防 key 撞车
        //（与 SourceProbeStore.map 的 associateBy 口径一致，计数随之按去重后计）
        val records = probeReport.records.distinctBy { "${it.kind}:${it.name}" }
        val ok = records.count { it.status == "OK" }
        val slow = records.count { it.status == "SLOW" }
        val fail = records.count { it.status == "FAIL" }
        val unsupported = records.count { it.status == "UNSUPPORTED" }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "对已启用源调 home() 探活，8 秒超时；绿=正常(≤3s) 黄=缓慢(>3s) 红=失败；" +
                        "开始后可切走/退后台，跑完发通知",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (probeReport.time > 0) {
                item {
                    Text(
                        text = "上次检测：${SourceProbeStore.agoText(probeReport.time)}" +
                            " · 正常 $ok / 缓慢 $slow / 失败 $fail" +
                            if (unsupported > 0) " / 不支持 $unsupported" else "",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(
                        text = if (probeRunning) "检测中 $probeDone/$probeTotal…" else "开始检测",
                        onClick = { if (probeRunning) showProbeDialog = true else startProbe() },
                        modifier = Modifier.weight(1f),
                        variant = AppButtonVariant.Primary,
                    )
                    if (probeRunning) {
                        AppButton(
                            text = "取消",
                            onClick = { SourceProbeWorker.cancel(context) },
                            variant = AppButtonVariant.Ghost,
                        )
                    }
                }
            }
            if (probeRunning) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "正在检测：$probeLabel",
                        style = SoftType.Body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "已完成 $probeDone / $probeTotal（后台运行中，可切走）",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(records, key = { "${it.kind}:${it.name}" }) { r ->
                ProbeResultRow(
                    target = ProbeTarget(r.kind, r.name, r.label),
                    result = r.toResult(),
                )
            }
            if (records.isEmpty() && !probeRunning) {
                item {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.Language,
                        title = "还没有检测结果",
                        hint = "点「开始检测」跑一次",
                    )
                }
            }
            if (records.isNotEmpty()) {
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CapsuleTabBar(
            titles = listOf("订阅源", "本地源", "批量检测", "回收站"),
            selected = subTab,
            onSelect = { subTab = it },
        )
        when (subTab) {
            0 -> SubscriptionTab()
            1 -> LocalTab()
            2 -> ProbeTab()
            3 -> TrashScreen(embedded = true)
        }
    }
    // ---- 长按操作菜单（ModalBottomSheet） ----
    menuItem?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { menuItem = null },
            shape = RoundedCornerShape(
                topStart = SoftDimens.CardCorner,
                topEnd = SoftDimens.CardCorner,
            ),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = item.fileName,
                    style = SoftType.CardTitle,
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
        TechDialog(
            onDismissRequest = { infoItem = null },
            title = "文件信息",
            dismissText = "关闭",
        ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("文件名：${item.fileName}", style = SoftType.Body)
                    Text("大小：${formatSize(file.length())}", style = SoftType.Body)
                    Text("修改时间：$modified", style = SoftType.Body)
                    Text("路径：${file.absolutePath}", style = SoftType.Body)
                }
        }
    }

    // ---- 删除二次确认 ----
    deleteItemState?.let { item ->
        TechDialog(
            onDismissRequest = { deleteItemState = null },
            title = "删除文件",
            confirmText = "删除",
            onConfirm = {
                deleteItemState = null
                deleteItem(item)
            },
        ) {
                Text(
                    "将「${item.fileName}」移入回收站，可随时恢复。",
                    style = SoftType.Body,
                )
        }
    }

    // ---- v0.108.0：订阅删除二次确认 ----
    pendingDeleteSub?.let { sub ->
        TechDialog(
            onDismissRequest = { pendingDeleteSub = null },
            title = "删除订阅",
            confirmText = "删除",
            onConfirm = {
                pendingDeleteSub = null
                removeSubscription(sub)
            },
        ) {
                Text(
                    "将「${sub.name}」移入回收站，可随时恢复。",
                    style = SoftType.Body,
                )
        }
    }

    // ---- v0.112.0：订阅级备注/分组编辑 ----
    pendingEditSub?.let { sub ->
        var editNote by remember(sub) { mutableStateOf(sub.note) }
        var editGroup by remember(sub) { mutableStateOf(sub.group) }
        TechDialog(
            onDismissRequest = { pendingEditSub = null },
            title = "编辑订阅 · ${sub.name}",
            confirmText = "保存",
            onConfirm = {
                sub.note = editNote.trim()
                sub.group = editGroup.trim()
                persistApiGroup()
                pendingEditSub = null
            },
        ) {
            Column {
                Text(
                    text = "备注",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                AppTextField(
                    value = editNote,
                    onValueChange = { editNote = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "如：4K 资源多，更新快",
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "分组",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                AppTextField(
                    value = editGroup,
                    onValueChange = { editGroup = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "如：影视 / 直播",
                )
            }
        }
    }

    // ---- v0.90.0：批量检测进度（Worker 后台跑；关弹窗=后台继续，"取消检测"真 cancel） ----
    if (showProbeDialog && probeRunning) {
        TechDialog(
            onDismissRequest = { showProbeDialog = false },
            title = "批量检测中",
            subtitle = "8 秒超时自动判失败；关掉后切走/退后台会继续跑，跑完发通知",
            dismissText = "后台运行",
            confirmText = "取消检测",
            onConfirm = {
                SourceProbeWorker.cancel(context)
                showProbeDialog = false
            },
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text(
                text = "正在检测：$probeLabel",
                color = Color.White,
                style = SoftType.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "已完成 $probeDone / $probeTotal",
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showAddDialog) {
        TechDialog(
            onDismissRequest = { showAddDialog = false },
            title = "添加订阅",
            confirmText = "确定",
            onConfirm = { addSubscription() },
        ) {
                Column {
                    AppTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "名称（选填，如：东篱线路）",
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "输入站源订阅地址（CMS 接口 JSON）",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    AppTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it; urlError = false },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "https://…/api.php/provide/vod/",
                    )
                    if (urlError) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "请输入以 http:// 或 https:// 开头的有效地址",
                            style = SoftType.Caption,
                            color = LocalSoftExtra.current.errorSoft,
                        )
                    }
                    // v0.112.0：订阅级备注/分组（选填）
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "备注",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "如：4K 资源多，更新快（选填）",
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "分组",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = groupInput,
                        onValueChange = { groupInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "如：影视 / 直播（选填）",
                    )
                }
        }
    }

    if (showWebAddDialog) {
        TechDialog(
            onDismissRequest = { showWebAddDialog = false },
            title = "添加网页站源",
            confirmText = "确定",
            onConfirm = { addWebSite() },
        ) {
                Column {
                    Text(
                        text = "输入网站名称和地址，长按条目菜单可打开嗅探浏览器",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "名称",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = webNameInput,
                        onValueChange = { webNameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "如：示例影视站",
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "网址",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = webUrlInput,
                        onValueChange = { webUrlInput = it; webUrlError = false },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "https://…",
                    )
                    if (webUrlError) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "请输入以 http:// 或 https:// 开头的有效地址",
                            style = SoftType.Caption,
                            color = LocalSoftExtra.current.errorSoft,
                        )
                    }
                }
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
    modifier: Modifier = Modifier,
) {
    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (item.enabled) 1f else 0.45f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        // v0.141.0：尽量小的单行紧凑卡——徽标＋名＋探活点＋当前勾＋开关一行排完，
        // 详情行与两行名全部去掉，名用小字单行省略；单击激活/长按菜单/开关启停都不变
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            ) {
                Text(
                    text = badgeLabel,
                    color = MaterialTheme.colorScheme.primary,
                    style = SoftType.Caption,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
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
            Spacer(Modifier.width(6.dp))
            Text(
                text = item.displayName,
                style = SoftType.Caption,
                fontWeight = if (item.isActive) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            ProbeDot(item.probe)
            if (item.isActive) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "当前使用",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
            // v0.24.0：源级启停（禁用后首页/搜索/检测跳过该源）
            Switch(
                checked = item.enabled,
                onCheckedChange = { onToggleEnabled(item, it) },
            )
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
    val tint = if (destructive) LocalSoftExtra.current.errorSoft
    else MaterialTheme.colorScheme.onSurfaceVariant
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
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = SoftType.Body,
            color = if (destructive) tint else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 类型徽标：CMS / drpy / PY / PHP / spider / 不支持 */
/** v0.46.0：刷新时间人话（刚刚/x 分钟前/x 小时前/x 天前）。 */
private fun timeAgo(ts: Long): String {
    val m = (System.currentTimeMillis() - ts) / 60000
    return when {
        m < 1 -> "刚刚"
        m < 60 -> "$m 分钟前"
        m < 1440 -> "${m / 60} 小时前"
        else -> "${m / 1440} 天前"
    }
}

@Composable
private fun KindBadge(kind: String) {    val (label, color) = when (kind) {
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
            style = SoftType.Caption,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * v0.104.0：订阅卡片共用信息区——名称 / 站点数 / 延迟·检测时间 / 刷新状态。
 * 颜色铁律：全部走主题色，禁止硬编码 Color.White。
 */
@Composable
private fun SubscriptionInfoLines(
    entry: SourceEntry,
    probe: SourceProbe.Result?,
    probeTime: Long,
    cache: SubCache?,
    refreshStatus: SubRefreshStore.SubStatus?,
) {
    // v0.115.0 修：本函数是多子节点发射器，必须自带 Column——v0.108.0 起收起卡片
    // 把它套在 Box（裁剪）里，Box 会把 Row/Text 全部叠在同一位置，文字互相重叠
    Column {
    val soft = LocalSoftExtra.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = entry.name,
            style = SoftType.CardTitle,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        ProbeDot(probe)
    }
    Spacer(Modifier.height(6.dp))
    val siteText = when {
        cache == null -> "未解析"
        cache.warehouses.isNotEmpty() -> "子仓 ${cache.warehouses.size} 个"
        else -> "站点 ${cache.sites.size} 个"
    }
    Text(
        text = siteText,
        style = SoftType.Caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    // v0.112.0：订阅级备注/分组小字（都没有则不占行）
    val subNoteLine = buildString {
        val g = entry.group.trim()
        val n = entry.note.trim()
        if (g.isNotBlank()) append("【$g】")
        if (n.isNotBlank()) {
            if (isNotEmpty()) append(" ")
            append(n)
        }
    }
    if (subNoteLine.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = subNoteLine,
            style = SoftType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (latencyText, latencyColor) = when (probe) {
            is SourceProbe.Result.Ok -> "${probe.ms}ms" to soft.success
            is SourceProbe.Result.Slow -> "${probe.ms}ms" to soft.warning
            is SourceProbe.Result.Fail -> "无法连接" to soft.errorSoft
            else -> "待检测" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            text = latencyText,
            style = SoftType.Caption,
            fontWeight = FontWeight.Bold,
            color = latencyColor,
            maxLines = 1,
        )
        Text(
            text = " · ",
            style = SoftType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "检测 ${SourceProbeStore.agoText(probeTime)}",
            style = SoftType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (refreshStatus != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (!refreshStatus.ok) "已失效（刷新失败）"
            else "上次刷新：${timeAgo(refreshStatus.checkedAt)}",
            style = SoftType.Caption,
            color = if (!refreshStatus.ok) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    }
}

/** v0.108.0：炫彩渐变描边卡片容器——照 TechDialog 的 1.5dp 四色渐变描边口径（跟随主题）。 */
@Composable
private fun RainbowRingCard(
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(LocalBrandGradient.current),
                RoundedCornerShape(SoftDimens.CardCorner),
            )
            .padding(1.5.dp)
            .clip(RoundedCornerShape(SoftDimens.CardCorner)),
    ) {
        AppCard(modifier = Modifier.fillMaxWidth(), onClick = onClick, content = content)
    }
}

/** v0.104.0：订阅 2 列卡片（收起态，对标 DsPlayer 资源页） */
@Composable
private fun SubscriptionGridCard(
    entry: SourceEntry,
    subUrl: String,
    probe: SourceProbe.Result?,
    probeTime: Long,
    cache: SubCache?,
    refreshStatus: SubRefreshStore.SubStatus?,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onExpand: () -> Unit,
    onEdit: () -> Unit,
) {
    // v0.108.0：统一卡片高度 160dp，信息区裁剪、操作行底对齐，保证 2 列视觉对齐；整卡可点展开
    RainbowRingCard(onClick = onExpand) {
        Column(modifier = Modifier.height(160.dp).padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds(),
            ) {
                SubscriptionInfoLines(
                    entry = entry,
                    probe = probe,
                    probeTime = probeTime,
                    cache = cache,
                    refreshStatus = refreshStatus,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = entry.enabled,
                    onCheckedChange = onToggle,
                )
                Spacer(Modifier.weight(1f))
                // v0.112.0：编辑订阅备注/分组
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "编辑备注分组",
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除",
                    )
                }
                IconButton(onClick = onExpand, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = "展开站点",
                    )
                }
            }
        }
    }
}

/** v0.104.0：订阅展开卡片（占满整行；内容复用原展开逻辑，只换容器） */
@Composable
private fun SubscriptionExpandedCard(
    entry: SourceEntry,
    subUrl: String,
    probe: SourceProbe.Result?,
    probeTime: Long,
    cache: SubCache?,
    refreshStatus: SubRefreshStore.SubStatus?,
    currentSiteKey: String?,
    caches: SnapshotStateMap<String, SubCache>,
    parsingMap: SnapshotStateMap<String, Boolean>,
    expandedMap: SnapshotStateMap<String, Boolean>,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onCollapse: () -> Unit,
    onParse: (String) -> Unit,
    onSetCurrent: (subUrl: String, siteKey: String, siteName: String) -> Unit,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // v0.108.0：展开卡片也套炫彩描边，与收起卡片统一
    RainbowRingCard {
        Column(modifier = Modifier.padding(12.dp)) {
            SubscriptionInfoLines(
                entry = entry,
                probe = probe,
                probeTime = probeTime,
                cache = cache,
                refreshStatus = refreshStatus,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = entry.enabled,
                    onCheckedChange = onToggle,
                )
                Spacer(Modifier.weight(1f))
                // v0.112.0：编辑订阅备注/分组
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "编辑备注分组",
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除",
                    )
                }
                IconButton(onClick = onCollapse, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ExpandLess,
                        contentDescription = "收起",
                    )
                }
            }
            // v0.42.0：完整订阅链接展示 + 复制（原展开逻辑）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subUrl,
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(subUrl))
                        Toast.makeText(context, "订阅链接已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "复制链接",
                    )
                }
            }
            SubscriptionCacheBody(
                cacheUrl = subUrl,
                caches = caches,
                parsing = parsingMap,
                expandedMap = expandedMap,
                currentSiteKey = currentSiteKey,
                onParse = onParse,
                onSetCurrent = onSetCurrent,
                parseButtonText = "解析站点",
                depth = 0,
            )
        }
    }
}

/**
 * v0.43.0：订阅内容体（解析中 / 未解析 / 多仓 / 站点列表 四态）。
 * 多仓的子仓展开后复用同一套逻辑；depth 限嵌套一层，子仓若仍是多仓只给提示。
 */
@Composable
private fun SubscriptionCacheBody(
    cacheUrl: String,
    caches: SnapshotStateMap<String, SubCache>,
    parsing: SnapshotStateMap<String, Boolean>,
    expandedMap: SnapshotStateMap<String, Boolean>,
    currentSiteKey: String?,
    onParse: (String) -> Unit,
    onSetCurrent: (subUrl: String, siteKey: String, siteName: String) -> Unit,
    parseButtonText: String,
    depth: Int,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val cache = caches[cacheUrl]
    val isParsing = parsing[cacheUrl] == true
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
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        cache == null -> {
            AppButton(
                text = parseButtonText,
                onClick = { onParse(cacheUrl) },
                variant = AppButtonVariant.Ghost,
            )
        }
        cache.warehouses.isNotEmpty() && depth == 0 -> {
            Text(
                text = "子仓（${cache.warehouses.size}）· 展开子仓查看站点",
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            cache.warehouses.forEach { wh ->
                val whExpanded = expandedMap[wh.url] == true
                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = wh.name,
                                style = SoftType.Body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = wh.url,
                                style = SoftType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(wh.url))
                                Toast.makeText(context, "子仓链接已复制", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "复制子仓链接",
                            )
                        }
                        IconButton(
                            onClick = { expandedMap[wh.url] = !whExpanded },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = if (whExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (whExpanded) "收起子仓" else "展开子仓",
                            )
                        }
                    }
                    AnimatedVisibility(visible = whExpanded) {
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            SubscriptionCacheBody(
                                cacheUrl = wh.url,
                                caches = caches,
                                parsing = parsing,
                                expandedMap = expandedMap,
                                currentSiteKey = currentSiteKey,
                                onParse = onParse,
                                onSetCurrent = onSetCurrent,
                                parseButtonText = "解析子仓",
                                depth = 1,
                            )
                        }
                    }
                }
            }
        }
        else -> {
            if (cache.warehouses.isNotEmpty()) {
                Text(
                    text = "该子仓仍为多仓（${cache.warehouses.size} 个子仓），请将其链接单独添加为订阅",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                SubscriptionSiteRows(
                    cache = cache,
                    cacheUrl = cacheUrl,
                    caches = caches,
                    currentSiteKey = currentSiteKey,
                    onParse = onParse,
                    onSetCurrent = onSetCurrent,
                )
            }
        }
    }
}

/** v0.43.0：站点列表（表头 + 行），订阅与子仓共用。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionSiteRows(
    cache: SubCache,
    cacheUrl: String,
    caches: SnapshotStateMap<String, SubCache>,
    currentSiteKey: String?,
    onParse: (String) -> Unit,
    onSetCurrent: (subUrl: String, siteKey: String, siteName: String) -> Unit,
) {
    val context = LocalContext.current
    // v0.112.0：分组 chips 筛选（只有站点被标过分组才出现）
    val groups = remember(cache) {
        cache.sites.map { it.group.trim() }.filter { it.isNotBlank() }.distinct()
    }
    var groupFilter by remember(cacheUrl) { mutableStateOf("") }
    val visibleSites = if (groupFilter.isBlank()) cache.sites
    else cache.sites.filter { it.group.trim() == groupFilter }
    // v0.112.0：站点备注/分组编辑弹窗（挂起的待编辑站点）
    var editingSite by remember(cacheUrl) { mutableStateOf<SubSiteRecord?>(null) }
    if (groups.isNotEmpty()) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppChip(
                text = "全部",
                small = true,
                selected = groupFilter.isBlank(),
                onClick = { groupFilter = "" },
            )
            groups.forEach { g ->
                AppChip(
                    text = g,
                    small = true,
                    selected = groupFilter == g,
                    onClick = { groupFilter = if (groupFilter == g) "" else g },
                )
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "站点（${visibleSites.size}）· 点站点设为当前源并回主页",
            style = SoftType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        AppButton(
            text = "刷新",
            onClick = { onParse(cacheUrl) },
            variant = AppButtonVariant.Ghost,
        )
    }
    visibleSites.forEach { rec ->
        val key = SubSiteStore.siteKey(cacheUrl, rec)
        val isCurrent = key == currentSiteKey
        val supported = rec.kind != SubSiteStore.KIND_UNSUPPORTED
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (supported) {
                        onSetCurrent(cacheUrl, rec.key, rec.name)
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rec.name,
                    style = SoftType.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // v0.112.0：备注/分组小字展示（都没有则不占行）
                val subLine = buildString {
                    val g = rec.group.trim()
                    val n = rec.note.trim()
                    if (g.isNotBlank()) append("【$g】")
                    if (n.isNotBlank()) {
                        if (isNotEmpty()) append(" ")
                        append(n)
                    }
                }
                if (subLine.isNotEmpty()) {
                    Text(
                        text = subLine,
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            KindBadge(rec.kind)
            Spacer(Modifier.width(8.dp))
            // v0.112.0：编辑备注/分组
            IconButton(
                onClick = { editingSite = rec },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "编辑备注分组",
                )
            }
            Spacer(Modifier.width(4.dp))
            // v0.24.0：站点级启停（禁用后聚合搜索跳过）
            Switch(
                checked = rec.enabled,
                onCheckedChange = { enabled ->
                    SubSiteStore.setSiteEnabled(
                        context, cacheUrl, rec.key, enabled,
                    )
                    SubSiteStore.getCached(context, cacheUrl)?.let {
                        caches[cacheUrl] = it
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
    // v0.112.0：站点备注/分组编辑弹窗
    val target = editingSite
    if (target != null) {
        var noteInput by remember(target.key) { mutableStateOf(target.note) }
        var groupInput by remember(target.key) { mutableStateOf(target.group) }
        TechDialog(
            onDismissRequest = { editingSite = null },
            title = "编辑站点 · ${target.name}",
            confirmText = "保存",
            onConfirm = {
                SubSiteStore.setSiteNoteGroup(
                    context, cacheUrl, target.key, noteInput.trim(), groupInput.trim(),
                )
                SubSiteStore.getCached(context, cacheUrl)?.let { caches[cacheUrl] = it }
                editingSite = null
            },
        ) {
            Column {
                Text(
                    text = "备注",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                AppTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "如：4K 资源多，更新快",
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "分组",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                AppTextField(
                    value = groupInput,
                    onValueChange = { groupInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "如：影视 / 直播",
                )
            }
        }
    }
}
