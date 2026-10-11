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
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.PySourceManager
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
    val dirName: String,          // filesDir 下子目录：drpy / py / ai / php
    val extensions: List<String>, // 允许的后缀，带点，如 ".js"
    val label: String,            // 分组名，用于 Toast 提示
)

private val IMPORT_DPRY = ImportTarget("drpy", listOf(".js"), "JS文件夹")
private val IMPORT_PY = ImportTarget("py", listOf(".py"), "PY文件夹")
private val IMPORT_AI = ImportTarget("ai", listOf(".js", ".json", ".py"), "AI文件夹")
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

/** 扫描 filesDir/<dirName>/ 下的文件，转成 SourceEntry 列表（给 AI/PHP 分组用） */
private fun listLocalFolderEntries(context: Context, dirName: String, desc: String): List<SourceEntry> {
    val dir = File(context.filesDir, dirName).apply { mkdirs() }
    return dir.listFiles { f -> f.isFile }
        .orEmpty()
        .sortedBy { it.name }
        .map { f -> SourceEntry(f.name, "$desc · ${f.length() / 1024} KB", enabled = false) }
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
            entries = mutableStateListOf(
                SourceEntry("量子资源", "https://cj.lziapi.com/api.php/provide/vod/"),
                SourceEntry("天空资源", "https://api.tiankongapi.com/api.php/provide/vod/"),
                SourceEntry("暴风资源", "https://bfzyapi.com/api.php/provide/vod/", enabled = false),
            ),
        )
    }
    val tailGroups = remember {
        mutableStateListOf(
            SourceGroup(
                id = "ai", name = "AI文件夹", icon = Icons.Filled.AutoAwesome,
                entries = mutableStateListOf(),
            ),
            SourceGroup(
                id = "php", name = "PHP文件夹", icon = Icons.Filled.Extension,
                entries = mutableStateListOf(),
            ),
        )
    }

    /** 刷新 AI/PHP 分组：从 filesDir/ai|php 扫描真实文件 */
    fun refreshLocalFolder(groupId: String) {
        val group = tailGroups.firstOrNull { it.id == groupId } ?: return
        val (dirName, desc) = when (groupId) {
            "ai" -> "ai" to "AI 规则"
            "php" -> "php" to "PHP 规则"
            else -> return
        }
        group.entries.clear()
        group.entries.addAll(listLocalFolderEntries(context, dirName, desc))
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
        refreshLocalFolder("ai")
        refreshLocalFolder("php")
        refreshWebSites()
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
            "ai" -> "ai"
            "php" -> "php"
            else -> return
        }
        runCatching { File(File(context.filesDir, dirName), entry.name).delete() }
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
        urlInput = ""
        urlError = false
        showAddDialog = false
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
                GroupCard(
                    group = apiGroup,
                    onRemove = { entry -> apiGroup.entries.remove(entry) },
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
                    "ai" -> IMPORT_AI
                    "php" -> IMPORT_PHP
                    else -> null
                }
                GroupCard(
                    group = group,
                    onRemove = { entry -> removeLocalFolderEntry(group.id, entry) },
                    onImport = importTarget?.let { t -> { requestImport(t) } },
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
                            SourceEntryRow(entry = entry, onRemove = { onRemove(entry) })
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
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
        Switch(
            checked = entry.enabled,
            onCheckedChange = { entry.enabled = it },
        )
    }
}
