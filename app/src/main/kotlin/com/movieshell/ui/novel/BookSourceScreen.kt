package com.movieshell.ui.novel

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.goldTitleColor
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.novel.BookSource
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.PurifyRuleStore
import com.movieshell.data.novel.PyBookApi
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.source.CmsApi
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.TechTextField
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v0.61.0 书源管理：列表 / 启用开关 / 删除 / 粘贴 JSON 导入。
 * 兼容"阅读"App 导出的 bookSource.json（单条或数组）。
 * v0.66.0：点本地 PY 源 → 打开该源专属找书页（onOpenPySource）。
 */
@Composable
fun BookSourceScreen(
    onBack: () -> Unit = {},
    onOpenPySource: (ruleName: String) -> Unit = {},
    onOpenPurifyRules: () -> Unit = {}, // v0.95.0：净化规则管理
    embedded: Boolean = false, // v0.79.0：嵌入书架子 tab 时隐藏头部
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // v0.109.0 A9：亮色主题白底金字对比度约 2.1:1，按底色明度自适应
    val gold = goldTitleColor()

    val sources by BookSourceManager.sources.collectAsState()
    // v0.64.0：本地 PY 书源
    val pyRules by PyBookSourceManager.rules.collectAsState()
    val pyEnabled by PyBookSourceManager.enabled.collectAsState()
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    // v0.81.0：书源 URL 导入 + 本地书源文件导入
    var showUrlImport by remember { mutableStateOf(false) }
    var importUrl by remember { mutableStateOf("") }
    var importingUrl by remember { mutableStateOf(false) }
    // v0.84.0：订阅预览——URL 下载后先列出全部书源，勾选再导入
    var urlPreview by remember { mutableStateOf<List<BookSource>?>(null) }
    var urlChecked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deleteTarget by remember { mutableStateOf<BookSource?>(null) }
    var deletePyTarget by remember { mutableStateOf<PyBookSourceManager.PyBookRule?>(null) }
    // v0.67.0：PY 源连通性测试
    var testingPy by remember { mutableStateOf<String?>(null) }
    // v0.96.1：净化规则生效数（按钮上展示）
    var purifyCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        purifyCount = runCatching {
            PurifyRuleStore.list(context).count { it.enable }
        }.getOrDefault(-1)
    }
    var testResult by remember { mutableStateOf<String?>(null) }
    // v0.76.0：导进来的是影视 spider——弹窗提示，可一键转去影视源导入
    var videoSpiderUri by remember { mutableStateOf<android.net.Uri?>(null) }

    LaunchedEffect(Unit) {
        BookSourceManager.load(context)
        PyBookSourceManager.load(context)
    }

    val pyImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val r = PyBookSourceManager.import(context, uri)) {
                is PyBookSourceManager.ImportResult.Ok ->
                    Toast.makeText(context, "已导入 ${r.fileName}", Toast.LENGTH_SHORT).show()
                PyBookSourceManager.ImportResult.WrongExt ->
                    Toast.makeText(context, "请选择 .py 文件", Toast.LENGTH_SHORT).show()
                PyBookSourceManager.ImportResult.InvalidRule ->
                    Toast.makeText(
                        context,
                        "不是有效的书源文件（需要实现 bookSearch 方法）",
                        Toast.LENGTH_SHORT,
                    ).show()
                // v0.76.0：是影视 spider——弹窗，不再只是一句 toast 打发
                PyBookSourceManager.ImportResult.IsVideoSpider -> videoSpiderUri = uri
                is PyBookSourceManager.ImportResult.Failed ->
                    Toast.makeText(context, "导入失败：${r.msg}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // v0.81.0：本地 bookSource.json 文件导入
    val jsonFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (json.isNullOrBlank()) {
                Toast.makeText(context, "读取文件失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val n = BookSourceManager.import(context, json)
            Toast.makeText(
                context,
                if (n > 0) "导入 $n 个书源" else "没有识别出书源，检查 JSON 格式",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // v0.76.0：影视 spider 识别弹窗
    videoSpiderUri?.let { uri ->
        TechDialog(
            onDismissRequest = { videoSpiderUri = null },
            title = "这是影视源，不是书源",
            confirmText = "导入为影视源",
            onConfirm = {
                videoSpiderUri = null
                scope.launch {
                    val msg = when (
                        val r = com.movieshell.ui.sources.importRuleFile(
                            context, uri,
                            com.movieshell.ui.sources.IMPORT_PY,
                        )
                    ) {
                        is com.movieshell.ui.sources.ImportResult.Ok ->
                            "已导入为影视源 ${r.fileName}，去站源页查看"
                        com.movieshell.ui.sources.ImportResult.WrongExt ->
                            "请选择 .py 文件"
                        is com.movieshell.ui.sources.ImportResult.Failed ->
                            "导入失败：${r.reason}"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            },
        ) {
            Text(
                "检测到这个 .py 是影视 spider（有 homeContent/searchContent 等影视方法，" +
                    "没有 bookSearch），放进书源管理用不了。要直接导入为影视源吗？",
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (!embedded) {
        // v0.86.0：第一行只留返回+标题，清爽
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = gold)
            }
            Text("书源管理", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = gold)
        }
        Spacer(Modifier.height(8.dp))
        // v0.86.0：第二行导入操作——四个均分，图标+文字单行不断行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ImportAction(
                icon = Icons.Filled.ContentPaste,
                label = "粘贴导入",
                onClick = { importText = ""; showImport = true },
            )
            ImportAction(
                icon = Icons.Filled.Link,
                label = "URL导入",
                onClick = { importUrl = ""; showUrlImport = true },
            )
            ImportAction(
                icon = Icons.Filled.FolderOpen,
                label = "文件导入",
                onClick = { jsonFileLauncher.launch("*/*") },
            )
            ImportAction(
                icon = Icons.Filled.Code,
                label = "导入本地PY",
                onClick = { pyImportLauncher.launch("*/*") },
            )
        }
        Spacer(Modifier.height(8.dp))
        }
        Text(
            "粘贴/URL/文件可导入阅读 App 的 bookSource.json；导入本地PY选 .py 书源文件；开关控制搜索时是否使用该源",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // v0.95.0：净化规则入口（正文替换规则管理）；v0.96.1：按钮上显示生效数
        FilledTonalButton(
            onClick = onOpenPurifyRules,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.AutoFixHigh, "净化规则", modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "净化规则（正文去广告/版式整理）" +
                    if (purifyCount >= 0) " · $purifyCount 条生效" else ""
            )
        }
        // v0.107.0 P0-7：删光书源后手动恢复内置源（已存在则提示，无需操作）
        OutlinedButton(
            onClick = {
                scope.launch {
                    val added = BookSourceManager.restoreBuiltin(context)
                    Toast.makeText(
                        context,
                        if (added) "已恢复内置书源（全本小说网）" else "内置书源已存在，无需恢复",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Refresh, "恢复默认书源", modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("恢复默认书源")
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // v0.64.0：本地 PY 书源区
            if (pyRules.isNotEmpty()) {
                item(key = "py_header") {
                    Text(
                        "本地 PY 书源（${pyRules.count { it.name in pyEnabled }} 已启用）",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = gold,
                    )
                }
            }
            items(pyRules, key = { "py_${it.name}" }) { r ->
                AppCard(
                    // v0.66.0：点卡片直接打开该源（找书只搜它）
                    onClick = { onOpenPySource(r.name) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(r.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                "${r.name} · ${r.sizeKb}KB",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Switch(
                            checked = r.name in pyEnabled,
                            onCheckedChange = {
                                PyBookSourceManager.setEnabled(context, r.name, it)
                            },
                        )
                        // v0.67.0：连通性测试（跑一遍 bookSearch）
                        if (testingPy == r.name) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(onClick = {
                                testingPy = r.name
                                testResult = null
                                scope.launch {
                                    val path = PyBookSourceManager.rulePath(context, r.name)
                                    val msg = if (path == null) {
                                        "找不到源文件"
                                    } else {
                                        PyBookApi().testSearch(path).fold(
                                            onSuccess = { n -> "测试通过：搜到 $n 本书" },
                                            onFailure = { e -> "测试失败：${e.message}" },
                                        )
                                    }
                                    testingPy = null
                                    testResult = "《${r.title}》$msg"
                                }
                            }) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    "测试",
                                    tint = gold,
                                )
                            }
                        }
                        IconButton(onClick = { deletePyTarget = r }) {
                            Icon(
                                Icons.Filled.Delete,
                                "删除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            if (sources.isNotEmpty()) {
                item(key = "json_header") {
                    Text(
                        "JSON 书源",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = gold,
                    )
                }
            }
            items(sources, key = { "json_${it.key}" }) { s ->
                    AppCard {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(s.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(
                                    s.url,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                if (s.group.isNotBlank()) {
                                    Text(
                                        s.group,
                                        fontSize = 12.sp,
                                        color = gold,
                                    )
                                }
                            }
                            Switch(
                                checked = s.enabled,
                                onCheckedChange = {
                                    scope.launch { BookSourceManager.setEnabled(context, s.key, it) }
                                },
                            )
                            IconButton(onClick = { deleteTarget = s }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    "删除",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            if (sources.isEmpty() && pyRules.isEmpty()) {
                item(key = "empty") {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "还没有书源，点顶部导入",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // v0.84.0：换科技感 TechDialog
    if (showImport) {
        TechDialog(
            onDismissRequest = { showImport = false },
            title = "导入书源",
            subtitle = "粘贴阅读 App 的 bookSource.json（单条或数组）",
            confirmText = "导入",
            onConfirm = {
                scope.launch {
                    val n = BookSourceManager.import(context, importText)
                    showImport = false
                    Toast.makeText(
                        context,
                        if (n > 0) "导入 $n 个书源" else "没有识别出书源，检查 JSON 格式",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        ) {
            TechTextField(
                value = importText,
                onValueChange = { importText = it },
                placeholder = "粘贴 bookSource.json",
                modifier = Modifier.fillMaxWidth().height(200.dp),
                singleLine = false,
            )
        }
    }

    // v0.81.0：书源 URL 导入；v0.84.0：换科技感 TechDialog
    if (showUrlImport) {
        TechDialog(
            onDismissRequest = { showUrlImport = false },
            title = "书源 URL 导入",
            subtitle = "输入 bookSource.json 的订阅地址，先预览再导入",
            confirmText = if (importingUrl) "下载中…" else "下一步",
            onConfirm = {
                val url = importUrl.trim()
                if (url.isBlank()) return@TechDialog
                importingUrl = true
                scope.launch {
                    val json = withContext(Dispatchers.IO) {
                        runCatching {
                            val req = okhttp3.Request.Builder().url(url).build()
                            // v0.112.0 B2：书源 URL 导入用共享单例，不再每请求 new 一个 client。
                            CmsApi.defaultClient().newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) null
                                else resp.body?.string()
                            }
                        }.getOrNull()
                    }
                    importingUrl = false
                    if (json.isNullOrBlank()) {
                        Toast.makeText(context, "下载失败，检查 URL", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    // v0.84.0：先解析预览，勾选再导入（对标阅读3.0网络导入）
                    val parsed = BookSource.listFromJson(json)
                    if (parsed.isEmpty()) {
                        Toast.makeText(
                            context, "没有识别出书源，检查 JSON 格式",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@launch
                    }
                    urlPreview = parsed
                    urlChecked = parsed.map { it.key }.toSet()
                    showUrlImport = false
                }
            },
            confirmEnabled = !importingUrl,
        ) {
            TechTextField(
                value = importUrl,
                onValueChange = { importUrl = it },
                placeholder = "https://…/bookSource.json",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // v0.84.0：订阅预览——列出 URL 里全部书源，勾选再导入；换科技感 TechDialog
    urlPreview?.let { preview ->
        TechDialog(
            onDismissRequest = { urlPreview = null },
            title = "订阅预览",
            subtitle = "共 ${preview.size} 个书源，已选 ${urlChecked.size} 个",
            confirmText = "导入选中",
            onConfirm = {
                scope.launch {
                    val selected = preview.filter { it.key in urlChecked }
                    val n = BookSourceManager.importSources(context, selected)
                    urlPreview = null
                    Toast.makeText(
                        context,
                        if (n > 0) "导入 $n 个书源"
                        else "所选书源都已存在，无需重复导入",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            confirmEnabled = urlChecked.isNotEmpty(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { urlChecked = preview.map { it.key }.toSet() },
                ) { Text("全选", fontSize = 12.sp) }
                TextButton(
                    onClick = { urlChecked = emptySet() },
                ) { Text("全不选", fontSize = 12.sp) }
            }
            Box(modifier = Modifier.height(300.dp)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(preview, key = { it.key }) { s ->
                        val checked = s.key in urlChecked
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    urlChecked = if (on) urlChecked + s.key
                                    else urlChecked - s.key
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    s.name.ifBlank { s.key },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    color = Color.White,
                                )
                                Text(
                                    s.url,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { s ->
        TechDialog(
            onDismissRequest = { deleteTarget = null },
            title = "删除书源",
            confirmText = "删除",
            onConfirm = {
                scope.launch { BookSourceManager.remove(context, s.key) }
                deleteTarget = null
            },
        ) {
            Text("确定删除「${s.name}」？")
        }
    }

    // v0.64.0：删除本地 PY 书源
    deletePyTarget?.let { r ->
        TechDialog(
            onDismissRequest = { deletePyTarget = null },
            title = "删除书源",
            confirmText = "删除",
            onConfirm = {
                scope.launch { PyBookSourceManager.remove(context, r.name) }
                deletePyTarget = null
            },
        ) {
            Text("确定删除本地 PY 书源「${r.title}」？文件也会被删除。")
        }
    }

    // v0.67.0：PY 源测试结果
    testResult?.let { msg ->
        TechDialog(
            onDismissRequest = { testResult = null },
            title = "连通性测试",
            dismissText = "知道了",
        ) {
            Text(msg)
        }
    }
}

/** v0.86.0：书源顶栏导入操作按钮——图标+文字，四均分单行 */
@Composable
private fun RowScope.ImportAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
