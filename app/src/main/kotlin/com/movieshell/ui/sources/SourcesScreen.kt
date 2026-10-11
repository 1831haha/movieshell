package com.movieshell.ui.sources

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.movieshell.data.repo.SubCache
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.data.repo.WebSourceManager
import java.io.File

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

/** 在 filesDir/drpy 下按规则名删除对应的 .js 文件（兼容 name 带/不带 .js 后缀） */
private fun deleteDrpyRuleFile(context: Context, name: String): Boolean {
    val dir = File(context.filesDir, "drpy")
    val target = dir.listFiles()?.firstOrNull {
        it.isFile && (it.name == name || it.nameWithoutExtension == name)
    } ?: return false
    return runCatching { target.delete() }.getOrDefault(false)
}

/** 在 filesDir/py 下按规则名删除对应的 .py 文件（兼容 name 带/不带 .py 后缀） */
private fun deletePyRuleFile(context: Context, name: String): Boolean {
    val dir = File(context.filesDir, "py")
    val target = dir.listFiles()?.firstOrNull {
        it.isFile && (it.name == name || it.nameWithoutExtension == name)
    } ?: return false
    return runCatching { target.delete() }.getOrDefault(false)
}

/** 文件导入目标：filesDir 下的子目录 + 允许的后缀 */
private data class ImportTarget(
    val dirName: String,          // filesDir 下子目录：drpy / py / php
    val extensions: List<String>, // 允许的后缀，带点，如 ".js"
    val label: String,            // 分组名，用于 Toast 提示
)

private val IMPORT_DPRY = ImportTarget("drpy", listOf(".js"), "JS文件夹")
private val IMPORT_PY = ImportTarget("py", listOf(".py"), "PY文件夹")
private val IMPORT_PHP = ImportTarget("php", listOf(".php"), "PHP文件夹")

/** 导入结果 */
private sealed interface ImportResult {
    data class Ok(val fileName: String) : ImportResult
    object WrongExt : ImportResult
    data class Failed(val reason: String) : ImportResult
}

/**
 * 把用户选中的文件拷贝到 filesDir/<dirName>/。
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
        val dir = File(context.filesDir, target.dirName).apply { mkdirs() }
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

/** 扫描 filesDir/<dirName>/ 下的文件，转成 SourceEntry 列表（给 PHP 分组用）；activeName 命中的条目 enabled=true（高亮为当前激活） */
private fun listLocalFolderEntries(
    context: Context,
    dirName: String,
    desc: String,
    activeName: String? = null,
): List<SourceEntry> {
    val dir = File(context.filesDir, dirName).apply { mkdirs() }
    return dir.listFiles { f -> f.isFile }
        .orEmpty()
        .sortedBy { it.name }
        .map { f -> SourceEntry(f.name, "$desc · ${f.length() / 1024} KB", enabled = f.name == activeName) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onWebSniff: (name: String, url: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current

    // drpy 规则：从 DrpySourceManager 读真实数据（filesDir/drpy/*.js）
    val drpyRules = remember { mutableStateListOf<DrpySourceManager.RuleInfo>() }
    var activeRuleName by remember { mutableStateOf<String?>(null) }
    // PY 规则：从 PySourceManager 读真实数据（filesDir/py/*.py）
    val pyRules = remember { mutableStateListOf<PySourceManager.RuleInfo>() }
    var activePyRuleName by remember { mutableStateOf<String?>(null) }
    // 网页站源：从 WebSourceManager 读真实数据（filesDir/web/*.json）
    val webSites = remember { mutableStateListOf<WebSourceManager.WebSite>() }
    var activeWebSiteName by remember { mutableStateOf<String?>(null) }

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
    val scope = rememberCoroutineScope()
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
        scope.launch {
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
    val tailGroups = remember {
        mutableStateListOf(
            SourceGroup(
                id = "php", name = "PHP文件夹", icon = Icons.Filled.Extension,
                entries = mutableStateListOf(),
            ),
        )
    }

    /** 刷新 PHP 分组：从 filesDir/php 扫描真实文件，激活中的条目高亮 */
    fun refreshLocalFolder(groupId: String) {
        val group = tailGroups.firstOrNull { it.id == groupId } ?: return
        val (dirName, desc) = when (groupId) {
            "php" -> "php" to "PHP 规则"
            else -> return
        }
        val activeName = when (groupId) {
            "php" -> PhpSourceManager.activeRuleName()
            else -> null
        }
        group.entries.clear()
        group.entries.addAll(listLocalFolderEntries(context, dirName, desc, activeName))
    }

    fun refreshDrpyRules() {
        drpyRules.clear()
        drpyRules.addAll(DrpySourceManager.listRules())
        activeRuleName = DrpySourceManager.activeRuleName()
    }

    fun refreshPyRules() {
        pyRules.clear()
        pyRules.addAll(PySourceManager.listRules())
        activePyRuleName = PySourceManager.activeRuleName()
    }

    fun selectDrpyRule(name: String) {
        DrpySourceManager.setActiveRule(name)
        activeRuleName = name
    }

    fun selectPyRule(name: String) {
        PySourceManager.setActiveRule(name)
        activePyRuleName = name
    }

    /** PHP 规则选中：仅做选中标记（手机端无 PHP 解释器，不可执行），如实 Toast。 */
    fun selectPhpRule(name: String) {
        PhpSourceManager.setActiveRule(name)
        refreshLocalFolder("php")
        Toast.makeText(
            context,
            "已选中 $name：${PhpSourceManager.UNSUPPORTED_REASON}",
            Toast.LENGTH_LONG
        ).show()
    }

    fun refreshWebSites() {
        webSites.clear()
        webSites.addAll(WebSourceManager.listSites())
        activeWebSiteName = WebSourceManager.activeSiteName()
    }

    fun removeWebSite(fileName: String) {
        WebSourceManager.deleteSite(fileName)
        webSites.removeAll { it.fileName == fileName }
        if (activeWebSiteName == fileName) activeWebSiteName = null
    }

    fun openWebSniff(site: WebSourceManager.WebSite) {
        WebSourceManager.setActiveSite(site.fileName)
        activeWebSiteName = site.fileName
        onWebSniff(site.name, site.url)
    }

    LaunchedEffect(Unit) {
        drpyRules.clear()
        drpyRules.addAll(DrpySourceManager.listRules())
        activeRuleName = DrpySourceManager.activeRuleName()
        pyRules.clear()
        pyRules.addAll(PySourceManager.listRules())
        activePyRuleName = PySourceManager.activeRuleName()
        refreshLocalFolder("php")
        refreshWebSites()
        // v0.5.1：读订阅展开缓存 + 点播页当前源
        apiGroup.entries.forEach { e ->
            SubSiteStore.getCached(context, e.detail)?.let { subCaches[e.detail] = it }
        }
        currentSubSiteKey = SubSiteStore.currentSiteKey(context)
    }

    // 文件导入：一个 launcher + 待处理目标；选错后缀 Toast 提示
    var pendingImport by remember { mutableStateOf<ImportTarget?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
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
                            selectDrpyRule(r.fileName)
                        }
                        Toast.makeText(context, "已导入 ${r.fileName}", Toast.LENGTH_SHORT).show()
                    }
                    "py" -> {
                        refreshPyRules()
                        if (PySourceManager.activeRuleName() == null) {
                            selectPyRule(r.fileName)
                        }
                        Toast.makeText(context, "已导入 ${r.fileName}", Toast.LENGTH_SHORT).show()
                    }
                    "php" -> {
                        refreshLocalFolder("php")
                        Toast.makeText(
                            context,
                            "已保存到PHP文件夹，${PhpSourceManager.UNSUPPORTED_REASON}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    else -> {
                        refreshLocalFolder(target.dirName)
                        Toast.makeText(
                            context,
                            "已保存到${target.label}，该类型执行引擎开发中",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            is ImportResult.WrongExt ->
                Toast.makeText(
                    context,
                    "请选择 ${target.extensions.joinToString(" / ")} 文件",
                    Toast.LENGTH_LONG
                ).show()
            is ImportResult.Failed ->
                Toast.makeText(context, "导入失败：${r.reason}", Toast.LENGTH_LONG).show()
        }
    }

    fun requestImport(target: ImportTarget) {
        pendingImport = target
        importLauncher.launch("*/*")
    }

    fun removeLocalFolderEntry(groupId: String, entry: SourceEntry) {
        val dirName = when (groupId) {
            "php" -> "php"
            else -> return
        }
        runCatching { File(File(context.filesDir, dirName), entry.name).delete() }
        // 删的是当前激活的规则 -> 清空激活，避免悬空引用
        if (groupId == "php" && PhpSourceManager.activeRuleName() == entry.name) {
            PhpSourceManager.setActiveRule(null)
        }
        tailGroups.firstOrNull { it.id == groupId }?.entries?.remove(entry)
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

    fun removeDrpyRule(name: String) {
        deleteDrpyRuleFile(context, name)
        drpyRules.removeAll { it.name == name }
        if (activeRuleName == name) {
            // 删掉的是当前规则 -> 清空激活状态
            DrpySourceManager.setActiveRule(null)
            activeRuleName = null
        }
    }

    fun removePyRule(name: String) {
        deletePyRuleFile(context, name)
        pyRules.removeAll { it.name == name }
        if (activePyRuleName == name) {
            // 删掉的是当前规则 -> 清空激活状态
            PySourceManager.setActiveRule(null)
            activePyRuleName = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部"添加订阅"按钮
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
                    onRemove = ::removeSubscription,
                    onToggle = ::toggleSubscription,
                    onParse = ::parseSubscription,
                    onSetCurrent = ::setCurrentSubSite,
                )
            }
            // JS 分组：真实 drpy 规则数据
            item {
                DrpyGroupCard(
                    rules = drpyRules,
                    activeRuleName = activeRuleName,
                    onSelect = ::selectDrpyRule,
                    onRemove = ::removeDrpyRule,
                    onImport = { requestImport(IMPORT_DPRY) },
                )
            }
            // PY 分组：真实 PY 规则数据
            item {
                PyGroupCard(
                    rules = pyRules,
                    activeRuleName = activePyRuleName,
                    onSelect = ::selectPyRule,
                    onRemove = ::removePyRule,
                    onImport = { requestImport(IMPORT_PY) },
                )
            }
            // 网页站源分组：点击条目进入 WebView 嗅探
            item {
                WebGroupCard(
                    sites = webSites,
                    activeSiteName = activeWebSiteName,
                    onSelect = ::openWebSniff,
                    onRemove = ::removeWebSite,
                    onAdd = { showWebAddDialog = true },
                )
            }
            items(tailGroups, key = { it.id }) { group ->
                val importTarget = when (group.id) {
                    "php" -> IMPORT_PHP
                    else -> null
                }
                // PHP 条目点击即选中当前规则
                val onSelect: ((SourceEntry) -> Unit)? = when (group.id) {
                    "php" -> { entry -> selectPhpRule(entry.name) }
                    else -> null
                }
                GroupCard(
                    group = group,
                    onRemove = { entry -> removeLocalFolderEntry(group.id, entry) },
                    onImport = importTarget?.let { t -> { requestImport(t) } },
                    onSelect = onSelect,
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
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
                        text = "输入网站名称和地址，点击条目即在内置浏览器中打开并嗅探直链",
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

/** drpy 规则分组卡：数据来自 DrpySourceManager.listRules() */
@Composable
private fun DrpyGroupCard(
    rules: SnapshotStateList<DrpySourceManager.RuleInfo>,
    activeRuleName: String?,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onImport: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "JS文件夹",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                BadgedBox(badge = { Badge { Text(rules.size.toString()) } }) {
                    Spacer(Modifier.size(4.dp))
                }
                TextButton(onClick = onImport) { Text("导入") }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    if (rules.isEmpty()) {
                        Text(
                            text = "该分组暂无文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    } else {
                        rules.forEach { rule ->
                            DrpyRuleRow(
                                rule = rule,
                                isActive = rule.name == activeRuleName,
                                onSelect = { onSelect(rule.name) },
                                onRemove = { onRemove(rule.name) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** drpy 规则行：点击设为当前规则；删除后若是当前规则则清空激活状态 */
@Composable
private fun DrpyRuleRow(
    rule: DrpySourceManager.RuleInfo,
    isActive: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) { Text("当前") }
                }
            }
            Text(
                text = "drpy 规则 · ${rule.sizeKb} KB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** PY 规则分组卡：数据来自 PySourceManager.listRules() */
@Composable
private fun PyGroupCard(
    rules: SnapshotStateList<PySourceManager.RuleInfo>,
    activeRuleName: String?,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onImport: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "PY文件夹",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                BadgedBox(badge = { Badge { Text(rules.size.toString()) } }) {
                    Spacer(Modifier.size(4.dp))
                }
                TextButton(onClick = onImport) { Text("导入") }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    if (rules.isEmpty()) {
                        Text(
                            text = "该分组暂无文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    } else {
                        rules.forEach { rule ->
                            PyRuleRow(
                                rule = rule,
                                isActive = rule.name == activeRuleName,
                                onSelect = { onSelect(rule.name) },
                                onRemove = { onRemove(rule.name) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** PY 规则行：点击设为当前规则；删除后若是当前规则则清空激活状态 */
@Composable
private fun PyRuleRow(
    rule: PySourceManager.RuleInfo,
    isActive: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) { Text("当前") }
                }
            }
            Text(
                text = "hipy 规则 · ${rule.sizeKb} KB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 网页站源分组卡：数据来自 WebSourceManager.listSites() */
@Composable
private fun WebGroupCard(
    sites: SnapshotStateList<WebSourceManager.WebSite>,
    activeSiteName: String?,
    onSelect: (WebSourceManager.WebSite) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "网页站源",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                BadgedBox(badge = { Badge { Text(sites.size.toString()) } }) {
                    Spacer(Modifier.size(4.dp))
                }
                TextButton(onClick = onAdd) { Text("添加") }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    if (sites.isEmpty()) {
                        Text(
                            text = "点击右上角「添加」录入网页站源，点击条目进入嗅探",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    } else {
                        sites.forEach { site ->
                            WebSiteRow(
                                site = site,
                                isActive = site.fileName == activeSiteName,
                                onSelect = { onSelect(site) },
                                onRemove = { onRemove(site.fileName) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 网页站源行：点击进入 WebView 嗅探；删除后若是当前站点则清空激活状态 */
@Composable
private fun WebSiteRow(
    site: WebSourceManager.WebSite,
    isActive: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = site.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) { Text("上次打开") }
                }
            }
            Text(
                text = site.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun GroupCard(
    group: SourceGroup,
    onRemove: (SourceEntry) -> Unit,
    onImport: (() -> Unit)? = null,
    onToggle: (() -> Unit)? = null,
    /** 非空时条目可点击选中（PHP 分组用）；此时隐藏 Switch，改显示激活对勾 */
    onSelect: ((SourceEntry) -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // 分组头：图标 + 名称 + 文件数徽标 + 展开/收起
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
                if (onImport != null) {
                    TextButton(onClick = onImport) { Text("导入") }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
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
                            text = "该分组暂无文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    } else {
                        group.entries.forEach { entry ->
                            SourceEntryRow(
                                entry = entry,
                                onRemove = { onRemove(entry) },
                                onToggle = onToggle,
                                onSelect = onSelect?.let { sel -> { sel(entry) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceEntryRow(
    entry: SourceEntry,
    onRemove: () -> Unit,
    onToggle: (() -> Unit)? = null,
    /** 非空时整行文本区可点击选中（PHP 分组用）；此时隐藏 Switch，改用对勾显示激活态 */
    onSelect: (() -> Unit)? = null,
) {
    // 选中模式下 entry.enabled 表示"当前激活"，普通模式下它是 Switch 开关态
    val isActive = onSelect != null && entry.enabled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = if (onSelect != null) Modifier
                .weight(1f)
                .clickable(onClick = onSelect)
            else Modifier.weight(1f)
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (onSelect != null) {
            // 选中模式：激活的条目显示对勾，占位保持行高对齐
            if (isActive) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "当前使用",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Spacer(Modifier.size(24.dp))
            }
        } else {
            Switch(
                checked = entry.enabled,
                onCheckedChange = {
                    entry.enabled = it
                    onToggle?.invoke()
                },
            )
        }
    }
}

/** 类型徽标：CMS / drpy / PY / 不支持 */
@Composable
private fun KindBadge(kind: String) {
    val (label, color) = when (kind) {
        SubSiteStore.KIND_CMS -> "CMS" to MaterialTheme.colorScheme.primary
        SubSiteStore.KIND_DRPY -> "drpy" to MaterialTheme.colorScheme.tertiary
        SubSiteStore.KIND_PY -> "PY" to MaterialTheme.colorScheme.secondary
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
 * 每条启用的订阅可展开站点列表：站名 + 类型徽标（CMS/drpy/PY/不支持）；
 * 点可用站点即"设为点播页当前源"（持久化），点"不支持"的站点 Toast 说明原因。
 */
@Composable
private fun SubscriptionCard(
    group: SourceGroup,
    caches: SnapshotStateMap<String, SubCache>,
    parsing: SnapshotStateMap<String, Boolean>,
    expandedMap: SnapshotStateMap<String, Boolean>,
    currentSiteKey: String?,
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
