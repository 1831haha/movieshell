package com.movieshell.ui.novel

import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppSwitch
import com.movieshell.ui.components.AppToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.accentTitleColor
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.movieshell.data.novel.BookSource
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.PurifyRuleStore
import com.movieshell.data.novel.PyBookApi
import com.movieshell.data.novel.PyBookSourceManager
import com.movieshell.data.source.CmsApi
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.TechTextField
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * v0.61.0 书源管理：列表 / 启用开关 / 删除 / 粘贴 JSON 导入。
 * 兼容"阅读"App 导出的 bookSource.json（单条或数组）。
 * v0.66.0：点本地 PY 源 → 打开该源专属找书页（onOpenPySource）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookSourceScreen(
    onBack: () -> Unit = {},
    onOpenPySource: (ruleName: String) -> Unit = {},
    onOpenExplore: (sourceKey: String) -> Unit = {},
    // v0.214.0：发现聚合页入口
    onOpenExploreAggregate: () -> Unit = {},
    onOpenPurifyRules: () -> Unit = {}, // v0.95.0：净化规则管理
    embedded: Boolean = false, // v0.79.0：嵌入书架子 tab 时隐藏头部
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // v0.109.0 A9：亮色主题白底青字按底色明度自适应
    val accent = accentTitleColor()

    val sources by BookSourceManager.sources.collectAsState()
    // v0.64.0：本地 PY 书源
    val pyRules by PyBookSourceManager.rules.collectAsState()
    val pyEnabled by PyBookSourceManager.enabled.collectAsState()
    var showImport by remember { mutableStateOf(false) }
    var showImportMenu by remember { mutableStateOf(false) }
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
    // v0.211.0 审计修复：删源确认要显示受影响的在架本数（原来文案
    // 说“书架里已有的书不受影响”，与实际断源行为相反）。
    var deleteAffectedCount by remember { mutableStateOf<Int?>(null) }
    var deletePyAffectedCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(deleteTarget) {
        deleteAffectedCount = deleteTarget?.let {
            withContext(Dispatchers.IO) {
                com.movieshell.data.repo.NovelStore.countBySourceKey(context, it.key)
            }
        }
    }
    LaunchedEffect(deletePyTarget) {
        deletePyAffectedCount = deletePyTarget?.let {
            withContext(Dispatchers.IO) {
                com.movieshell.data.repo.NovelStore.countBySourceKey(
                    context,
                    "${PyBookSourceManager.SOURCE_KEY_PREFIX}${it.name}",
                )
            }
        }
    }
    // v0.196.0：长按卡片编辑（仿阅读书源列表）——JSON 改名/地址/分组，PY 改文件名
    var editTarget by remember { mutableStateOf<BookSource?>(null) }
    var editPyTarget by remember { mutableStateOf<PyBookSourceManager.PyBookRule?>(null) }
    // v0.206.0：长按书源文本编辑——JSON 整条文本、PY 整文件文本
    var textEditJson by remember { mutableStateOf<BookSource?>(null) }
    var textEditPy by remember { mutableStateOf<PyBookSourceManager.PyBookRule?>(null) }
    var textEditPyText by remember { mutableStateOf("") }
    // v0.197.0：阅读式书源列表——顶部搜索过滤 + 批量选择（全选/反选/删除）
    var srcQuery by remember { mutableStateOf("") }
    var batchMode by remember { mutableStateOf(false) }
    var selectedSrcKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchDeleteConfirm by remember { mutableStateOf(false) }
    // v0.217.0（F45）：批量删除确认框也要报受影响的在架本数——原来只有
    // 单删确认框有，批量删完用户才知道书架断了多少本。
    var batchAffectedCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(batchDeleteConfirm) {
        batchAffectedCount = if (!batchDeleteConfirm) null
        else withContext(Dispatchers.IO) {
            selectedSrcKeys.sumOf { k ->
                val key = when {
                    k.startsWith("py_") -> PyBookSourceManager.SOURCE_KEY_PREFIX + k.removePrefix("py_")
                    k.startsWith("json_") -> k.removePrefix("json_")
                    else -> k
                }
                com.movieshell.data.repo.NovelStore.countBySourceKey(context, key)
            }
        }
    }
    val shownPy = remember(pyRules, srcQuery) {
        if (srcQuery.isBlank()) pyRules else pyRules.filter {
            it.name.contains(srcQuery, ignoreCase = true) || it.title.contains(srcQuery, ignoreCase = true)
        }
    }
    val shownJson = remember(sources, srcQuery) {
        if (srcQuery.isBlank()) sources else sources.filter {
            it.name.contains(srcQuery, ignoreCase = true) || it.url.contains(srcQuery, ignoreCase = true)
        }
    }
    val allShownKeys = remember(shownPy, shownJson) {
        shownPy.map { "py_${it.name}" } + shownJson.map { "json_${it.key}" }
    }
    // v0.67.0：PY 源连通性测试
    var testingPy by remember { mutableStateOf<String?>(null) }
    // v0.217.0（F42）：验证排队锁——原来多源测试并发跑、共用 testingPy／
    // testResult，先跑完的把后跑的转圈清掉、结果互相顶掉；一次只测一个，
    // 点多个源时按点击顺序排队出结果。
    val testMutex = remember { Mutex() }
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
                    AppToast.show(context, "已导入 ${r.fileName}", long = false)
                PyBookSourceManager.ImportResult.WrongExt ->
                    AppToast.show(context, "请选择 .py 文件", long = false)
                PyBookSourceManager.ImportResult.InvalidRule ->
                    AppToast.show(context, "不是有效的书源文件（需要实现 bookSearch 方法）", long = false)
                // v0.76.0：是影视 spider——弹窗，不再只是一句 toast 打发
                PyBookSourceManager.ImportResult.IsVideoSpider -> videoSpiderUri = uri
                is PyBookSourceManager.ImportResult.Failed ->
                    AppToast.show(context, "导入失败：${r.msg}", long = false)
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
                AppToast.show(context, "读取文件失败", long = false)
                return@launch
            }
            // v0.186.0：import 返回 0 有三种含义——没识别、已存在、写盘失败；
            // 先解析一次：有新 key 却返回 0 只可能是写盘失败（失败时内存已回滚）。
            val parsed = BookSource.listFromJson(json)
            val hasNew = parsed.any { p -> BookSourceManager.sources.value.none { it.key == p.key } }
            val n = BookSourceManager.import(context, json)
            AppToast.show(context, when {
                    n > 0 -> "导入 $n 个书源"
                    parsed.isEmpty() -> "没有识别出书源，检查 JSON 格式"
                    hasNew -> "保存失败，书源未导入，请重试"
                    else -> "书源已存在，无需重复导入"
                }, long = false)
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
                    AppToast.show(context, msg, long = true)
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
        // v0.227.0：四个导入入口并成一个——标题行末尾一个“导入书源”
        // 钮，点开弹窗再选方式；原来四个均分大钮占的一行省掉。
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = accent)
            }
            Text(
                "书源管理",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = { showImportMenu = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text("导入书源", fontSize = 12.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(6.dp))
        }
        // v0.223.0：头部压缩——说明只留一行（开关语义），发现聚合/净化规则/
        // 恢复默认由三个通栏大钮并成一行小钮，省约 110dp。
        Text(
            "开关控制搜索时是否使用该源",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // v0.214.0：发现聚合入口（多源首屏横滑行，不用逐源钻进去）。
            FilledTonalButton(
                onClick = onOpenExploreAggregate,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Explore, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text("发现聚合", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // v0.95.0：净化规则入口（正文替换规则管理）；v0.96.1：按钮上显示生效数
            FilledTonalButton(
                onClick = onOpenPurifyRules,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.AutoFixHigh, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text(
                    "净化规则" + if (purifyCount >= 0) " · $purifyCount" else "",
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // v0.107.0 P0-7：删光书源后手动恢复内置源（已存在则提示，无需操作）
            OutlinedButton(
                onClick = {
                    scope.launch {
                        // v0.186.0：restoreBuiltin 返回 false 有两种含义——已存在、写盘失败；
                        // 调用前已有内置源才是已存在，否则就是写盘失败（失败时内存已回滚）。
                        // 内置源 key 与 BookSourceManager 的内置 key 一致：quanben 网址去协议去尾斜杠。
                        val hadBuiltin = BookSourceManager.sources.value.any { it.key == "www.quanben.io" }
                        val added = BookSourceManager.restoreBuiltin(context)
                        AppToast.show(context, when {
                                added -> "已恢复内置书源（全本小说网）"
                                hadBuiltin -> "内置书源已存在，无需恢复"
                                else -> "保存失败，内置书源未恢复，请重试"
                            }, long = false)
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text("恢复默认", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(8.dp))
        // v0.197.0：阅读式顶部搜索（按名/地址过滤）+ 批量入口
        Row(verticalAlignment = Alignment.CenterVertically) {
            TechTextField(
                value = srcQuery,
                onValueChange = { srcQuery = it },
                placeholder = "搜索书源",
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                batchMode = !batchMode
                if (!batchMode) selectedSrcKeys = emptySet()
            }) {
                Text(if (batchMode) "取消批量" else "批量")
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // v0.64.0：本地 PY 书源区
            if (shownPy.isNotEmpty()) {
                item(key = "py_header") {
                    Text(
                        "本地 PY 书源（${pyRules.count { it.name in pyEnabled }} 已启用）",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }
            }
            items(shownPy, key = { "py_${it.name}" }) { r ->
                // v0.223.0：每源单独卡片（点卡打开该源，长按进文本编辑）。
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onOpenPySource(r.name) },
                    onLongClick = {
                        scope.launch {
                            val text = PyBookSourceManager.readContent(context, r.name)
                            if (text == null) {
                                AppToast.show(context, "读取失败，请重试", long = false)
                            } else {
                                textEditPyText = text
                                textEditPy = r
                            }
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (batchMode) {
                            Checkbox(
                                checked = "py_${r.name}" in selectedSrcKeys,
                                onCheckedChange = {
                                    selectedSrcKeys = if (it) selectedSrcKeys + "py_${r.name}"
                                    else selectedSrcKeys - "py_${r.name}"
                                },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                r.title, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${r.name} · ${r.sizeKb}KB",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        AppSwitch(
                            checked = r.name in pyEnabled,
                            onCheckedChange = {
                                if (!PyBookSourceManager.setEnabled(context, r.name, it)) {
                                    AppToast.show(context, "保存失败", long = false)
                                }
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
                                    // 排队：等前一个源的测试跑完再跑自己的，
                                    // 转圈与结果不再被别的源顶掉。
                                    testMutex.withLock {
                                        val path = PyBookSourceManager.rulePath(context, r.name)
                                        val msg = if (path == null) {
                                            "找不到源文件"
                                        } else {
                                            PyBookApi().testSearch(path).fold(
                                                onSuccess = { n -> "测试通过：搜到 $n 本书" },
                                                onFailure = { e -> "测试失败：${e.message}" },
                                            )
                                        }
                                        testResult = "《${r.title}》$msg"
                                    }
                                    testingPy = null
                                }
                            }) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    "测试",
                                    tint = accent,
                                )
                            }
                        }
                        IconButton(onClick = { editPyTarget = r }) {
                            Icon(Icons.Filled.Edit, "编辑", tint = accent)
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
            if (shownJson.isNotEmpty()) {
                item(key = "json_header") {
                    Text(
                        "JSON 书源",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                }
            }
            items(shownJson, key = { "json_${it.key}" }) { s ->
                // v0.223.0：每源单独卡片——第一行书名 + 开关（开关恒贴右缘），
                // 第二行地址 + 操作钮（操作列恒贴右缘），各卡片开关与操作列纵向对齐。
                // 点卡进字段编辑，长按进文本编辑（与原来点行/长按一致）。
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { editTarget = s },
                    onLongClick = { textEditJson = s },
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (batchMode) {
                                Checkbox(
                                    checked = "json_${s.key}" in selectedSrcKeys,
                                    onCheckedChange = {
                                        selectedSrcKeys = if (it) selectedSrcKeys + "json_${s.key}"
                                        else selectedSrcKeys - "json_${s.key}"
                                    },
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            }
                            Text(
                                s.name, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            AppSwitch(
                                checked = s.enabled,
                                onCheckedChange = {
                                    scope.launch {
                                        // v0.188.0：写盘失败提示“保存失败”；管理器已回滚
                                        // 内存表，开关随 StateFlow 旧值自动弹回，不用手动复位。
                                        if (!BookSourceManager.setEnabled(context, s.key, it)) {
                                            AppToast.show(context, "保存失败", long = false)
                                        }
                                    }
                                },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    s.url,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (s.group.isNotBlank()) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        s.group,
                                        fontSize = 12.sp,
                                        color = accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            // v0.201.0：有发现分类的源行末加「发现」入口（进书架子结构内的发现页）
                            if (s.exploreUrl.isNotBlank()) {
                                TextButton(onClick = { onOpenExplore(s.key) }) {
                                    Text("发现", fontSize = 13.sp)
                                }
                            }
                            IconButton(onClick = { editTarget = s }) {
                                Icon(Icons.Filled.Edit, "编辑", tint = accent)
                            }
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
            }
            if (shownPy.isEmpty() && shownJson.isEmpty()) {
                item(key = "empty") {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (srcQuery.isBlank()) "还没有书源，点顶部导入" else "没有匹配的书源",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // v0.197.0：底部批量栏（阅读式：全选 / 反选 / 删除）
        if (batchMode) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { selectedSrcKeys = allShownKeys.toSet() }) {
                    Text("全选（${selectedSrcKeys.size}/${allShownKeys.size}）")
                }
                TextButton(onClick = {
                    selectedSrcKeys = allShownKeys.filter { it !in selectedSrcKeys }.toSet()
                }) {
                    Text("反选")
                }
                TextButton(
                    enabled = selectedSrcKeys.isNotEmpty(),
                    onClick = { batchDeleteConfirm = true },
                ) {
                    Text("删除")
                }
            }
        }
    }

    // v0.197.0：批量删除确认
    if (batchDeleteConfirm) {
        TechDialog(
            onDismissRequest = { batchDeleteConfirm = false },
            title = "删除 ${selectedSrcKeys.size} 个书源？",
            confirmText = "删除",
            onConfirm = {
                batchDeleteConfirm = false
                val keys = selectedSrcKeys
                scope.launch {
                    // v0.217.0（F45）：报实删数——原来直接报勾选数，个别源
                    // 写盘失败也算“已删除”；JSON 删除无返回值，按单删同款
                    // “删完 key 还在即失败”逐个验。
                    var okCount = 0
                    withContext(Dispatchers.IO) {
                        keys.forEach { k ->
                            when {
                                k.startsWith("py_") -> {
                                    if (PyBookSourceManager.remove(context, k.removePrefix("py_"))) okCount++
                                }
                                k.startsWith("json_") -> {
                                    val key = k.removePrefix("json_")
                                    BookSourceManager.remove(context, key)
                                    if (BookSourceManager.sources.value.none { it.key == key }) okCount++
                                }
                            }
                        }
                    }
                    selectedSrcKeys = emptySet()
                    batchMode = false
                    AppToast.show(
                        context,
                        when {
                            okCount == keys.size -> "已删除 $okCount 个书源"
                            okCount > 0 -> "已删除 $okCount/${keys.size} 个书源，${keys.size - okCount} 个删除失败"
                            else -> "删除失败，书源未删除"
                        },
                        long = false,
                    )
                }
            },
        ) {
            Text(
                "删除后找书不再用这些源；书架里用这些源的书将无法追更，阅读时需要换源。" +
                    (batchAffectedCount?.let { n ->
                        if (n > 0) " 本次删除影响书架上 $n 本书。" else ""
                    } ?: ""),
            )
        }
    }

    // v0.227.0：导入方式二选一变四选一弹窗——原来四个大钮占一整行，
    // 并成标题行一个“导入书源”入口，点开再选方式，原四路动作原样接线。
    if (showImportMenu) {
        TechDialog(
            onDismissRequest = { showImportMenu = false },
            title = "导入书源",
            onConfirm = null,
        ) {
            ImportMenuItem(Icons.Filled.ContentPaste, "粘贴导入") {
                showImportMenu = false; importText = ""; showImport = true
            }
            ImportMenuItem(Icons.Filled.Link, "URL导入") {
                showImportMenu = false; importUrl = ""; showUrlImport = true
            }
            ImportMenuItem(Icons.Filled.FolderOpen, "文件导入") {
                showImportMenu = false; jsonFileLauncher.launch("*/*")
            }
            ImportMenuItem(Icons.Filled.Code, "导入本地PY") {
                showImportMenu = false; pyImportLauncher.launch("*/*")
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
                    // v0.186.0：import 返回 0 有三种含义——没识别、已存在、写盘失败；
                    // 先解析一次：有新 key 却返回 0 只可能是写盘失败（失败时内存已回滚）。
                    val parsed = BookSource.listFromJson(importText)
                    val hasNew = parsed.any { p -> BookSourceManager.sources.value.none { it.key == p.key } }
                    val n = BookSourceManager.import(context, importText)
                    showImport = false
                    AppToast.show(context, when {
                            n > 0 -> "导入 $n 个书源"
                            parsed.isEmpty() -> "没有识别出书源，检查 JSON 格式"
                            hasNew -> "保存失败，书源未导入，请重试"
                            else -> "书源已存在，无需重复导入"
                        }, long = false)
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
                        AppToast.show(context, "下载失败，检查 URL", long = false)
                        return@launch
                    }
                    // v0.84.0：先解析预览，勾选再导入（对标阅读3.0网络导入）
                    val parsed = BookSource.listFromJson(json)
                    if (parsed.isEmpty()) {
                        AppToast.show(context, "没有识别出书源，检查 JSON 格式", long = false)
                        return@launch
                    }
                    // v0.133.0：外部订阅文件可含同址重复条目（http 与 https、同址尾斜杠
                    // 归一化后 key 相同），预览渲染前按 key 去重防 LazyColumn key 撞车；
                    // 勾选态本来就按 key 记，去重不丢信息
                    urlPreview = parsed.distinctBy { it.key }
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
                    // v0.186.0：importSources 返回 0 可能是全已存在，也可能是写盘失败；
                    // 选中里有新 key 却返回 0 只可能是写盘失败（失败时内存已回滚）。
                    val hasNew = selected.any { s -> BookSourceManager.sources.value.none { it.key == s.key } }
                    val n = BookSourceManager.importSources(context, selected)
                    urlPreview = null
                    AppToast.show(context, when {
                            n > 0 -> "导入 $n 个书源"
                            hasNew -> "保存失败，书源未导入，请重试"
                            else -> "所选书源都已存在，无需重复导入"
                        }, long = false)
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
                                    color = MaterialTheme.colorScheme.onSurface,
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
                scope.launch {
                    // v0.186.0：JSON 删除无 Boolean 返回值，写盘失败时内存回滚、
                    // 删完 key 还在就是保存失败；成功时保持原来不弹提示。
                    BookSourceManager.remove(context, s.key)
                    if (BookSourceManager.sources.value.any { it.key == s.key }) {
                        AppToast.show(context, "删除失败：保存失败，请重试", long = false)
                    }
                }
                deleteTarget = null
            },
        ) {
            Text(
                "确定删除「${s.name}」？" +
                    (deleteAffectedCount?.let { n ->
                        if (n > 0) " 书架上有 $n 本书在用这个源，删后这些书将无法追更，阅读时需要换源。" else ""
                    } ?: ""),
            )
        }
    }

    // v0.64.0：删除本地 PY 书源
    deletePyTarget?.let { r ->
        TechDialog(
            onDismissRequest = { deletePyTarget = null },
            title = "删除书源",
            confirmText = "删除",
            onConfirm = {
                scope.launch {
                    // v0.186.0：PY 删除返回 false 即文件删除失败，弹保存失败提示；
                    // 成功时保持原来不弹提示。
                    val ok = PyBookSourceManager.remove(context, r.name)
                    if (!ok) {
                        AppToast.show(context, "删除失败：保存失败，请重试", long = false)
                    }
                }
                deletePyTarget = null
            },
        ) {
            Text(
                "确定删除本地 PY 书源「${r.title}」？文件也会被删除。" +
                    (deletePyAffectedCount?.let { n ->
                        if (n > 0) " 书架上有 $n 本书在用这个源，删后这些书将无法追更，阅读时需要换源。" else ""
                    } ?: ""),
            )
        }
    }

    // v0.214.0：✎进字段编辑——按基础／搜索／详情／目录／正文／发现六页签拆开的
    // 表单（原来只有名称／地址／分组三框，规则一律改不了；全文 JSON 仍走长按文本编辑）。
    // 保存走 updateSourceJson（整条 JSON 回写，字段全量 round-trip；地址变了自动迁书架）。
    editTarget?.let { s ->
        var draft by remember(s.key) { mutableStateOf(s) }
        var tab by remember(s.key) { mutableStateOf(0) }
        val tabs = listOf("基础", "搜索", "详情", "目录", "正文", "发现")
        TechDialog(
            onDismissRequest = { editTarget = null },
            title = "编辑书源",
            subtitle = "按页签改规则；想改整条 JSON 请长按源进文本编辑",
            confirmText = "保存",
            onConfirm = {
                scope.launch {
                    val ok = BookSourceManager.updateSourceJson(context, s.key, draft.toJson().toString())
                    if (!ok) {
                        AppToast.show(context, "保存失败：名称或地址为空、地址与已有书源重复或写盘失败", long = false)
                    }
                }
                editTarget = null
            },
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                tabs.forEachIndexed { i, t ->
                    TextButton(onClick = { tab = i }) {
                        Text(
                            t,
                            fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal,
                            color = if (tab == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (tab) {
                    0 -> {
                        SourceField("名称", draft.name) { draft = draft.copy(name = it) }
                        SourceField("地址", draft.url, hint = "书源唯一标识由此算出，改地址会同步迁书架上的书") { draft = draft.copy(url = it) }
                        SourceField("分组", draft.group, hint = "可空") { draft = draft.copy(group = it) }
                        SourceField("自定义 UA", draft.headerUa, hint = "空则用默认 UA") { draft = draft.copy(headerUa = it) }
                        SourceField("登录地址", draft.loginUrl) { draft = draft.copy(loginUrl = it) }
                        SourceField("登录校验 JS", draft.loginCheckJs, hint = "阅读式 loginCheckJs，空表示不需要", multiLine = true) { draft = draft.copy(loginCheckJs = it) }
                    }
                    1 -> {
                        SourceField("搜索地址", draft.searchUrl, hint = "{{key}} 按 UTF-8 编码，{{page}} 恒为 1") { draft = draft.copy(searchUrl = it) }
                        SourceField("搜索列表", draft.ruleSearchList, hint = "ruleSearch.bookList") { draft = draft.copy(ruleSearchList = it) }
                        SourceField("书名", draft.ruleSearchName, hint = "ruleSearch.name") { draft = draft.copy(ruleSearchName = it) }
                        SourceField("作者", draft.ruleSearchAuthor, hint = "ruleSearch.author") { draft = draft.copy(ruleSearchAuthor = it) }
                        SourceField("封面", draft.ruleSearchCover, hint = "ruleSearch.coverUrl") { draft = draft.copy(ruleSearchCover = it) }
                        SourceField("详情地址", draft.ruleSearchBookUrl, hint = "ruleSearch.bookUrl") { draft = draft.copy(ruleSearchBookUrl = it) }
                    }
                    2 -> {
                        SourceField("书名", draft.ruleBookName, hint = "ruleBookInfo.name") { draft = draft.copy(ruleBookName = it) }
                        SourceField("作者", draft.ruleBookAuthor, hint = "ruleBookInfo.author") { draft = draft.copy(ruleBookAuthor = it) }
                        SourceField("简介", draft.ruleBookIntro, hint = "ruleBookInfo.intro", multiLine = true) { draft = draft.copy(ruleBookIntro = it) }
                        SourceField("封面", draft.ruleBookCover, hint = "ruleBookInfo.coverUrl") { draft = draft.copy(ruleBookCover = it) }
                        SourceField("目录地址", draft.ruleBookTocUrl, hint = "ruleBookInfo.tocUrl") { draft = draft.copy(ruleBookTocUrl = it) }
                    }
                    3 -> {
                        SourceField("目录列表", draft.ruleTocList, hint = "ruleToc.chapterList") { draft = draft.copy(ruleTocList = it) }
                        SourceField("章节名", draft.ruleTocTitle, hint = "ruleToc.chapterName") { draft = draft.copy(ruleTocTitle = it) }
                        SourceField("章节地址", draft.ruleTocUrl, hint = "ruleToc.chapterUrl") { draft = draft.copy(ruleTocUrl = it) }
                        SourceField("目录下一页", draft.ruleTocNext, hint = "ruleToc.nextTocUrl，JS 源才跟分页") { draft = draft.copy(ruleTocNext = it) }
                    }
                    4 -> {
                        SourceField("正文", draft.ruleContent, hint = "ruleContent.content，空则走通用正文兜底") { draft = draft.copy(ruleContent = it) }
                        SourceField("正文替换", draft.ruleContentReplace, hint = "replaceRegex：每行一条，行内用 ## 分隔正则与替换", multiLine = true) { draft = draft.copy(ruleContentReplace = it) }
                        SourceField("正文下一页", draft.ruleContentNext, hint = "ruleContent.nextContentUrl") { draft = draft.copy(ruleContentNext = it) }
                        SourceField("共享 JS 库", draft.jsLib, hint = "阅读式 jsLib（内联代码，URL 形式暂不支持）", multiLine = true) { draft = draft.copy(jsLib = it) }
                    }
                    else -> {
                        SourceField("发现地址", draft.exploreUrl, hint = "阅读式 exploreUrl：分类 JSON 数组或对象") { draft = draft.copy(exploreUrl = it) }
                        SourceField("发现列表", draft.ruleExploreList, hint = "ruleExplore.bookList") { draft = draft.copy(ruleExploreList = it) }
                        SourceField("书名", draft.ruleExploreName, hint = "ruleExplore.name") { draft = draft.copy(ruleExploreName = it) }
                        SourceField("作者", draft.ruleExploreAuthor, hint = "ruleExplore.author") { draft = draft.copy(ruleExploreAuthor = it) }
                        SourceField("详情地址", draft.ruleExploreBookUrl, hint = "ruleExplore.bookUrl") { draft = draft.copy(ruleExploreBookUrl = it) }
                        SourceField("封面", draft.ruleExploreCover, hint = "ruleExplore.coverUrl") { draft = draft.copy(ruleExploreCover = it) }
                        SourceField("简介", draft.ruleExploreIntro, hint = "ruleExplore.intro", multiLine = true) { draft = draft.copy(ruleExploreIntro = it) }
                    }
                }
            }
        }
    }

    // v0.196.0：✎重命名——只改文件名，不改文件内容；v0.206.0：长按进文本编辑
    // v0.206.0：长按 JSON 书源文本编辑——整条 JSON 可改，格式不对不保存
    textEditJson?.let { s ->
        SourceTextEditorDialog(
            title = "编辑书源 · ${s.name}",
            initialText = s.toJson().toString(2),
            onDismiss = { textEditJson = null },
            onSave = { text ->
                val jsonOk = runCatching { JSONObject(text) }.isSuccess
                if (!jsonOk) {
                    "JSON 格式不对，未保存"
                } else if (BookSourceManager.updateSourceJson(context, s.key, text)) {
                    null
                } else {
                    "保存失败：地址与已有书源重复或写盘失败"
                }
            },
        )
    }

    // v0.206.0：长按 PY 书源文本编辑——整文件可改，保存即生效
    textEditPy?.let { r ->
        SourceTextEditorDialog(
            title = "编辑书源 · ${r.title}",
            initialText = textEditPyText,
            onDismiss = { textEditPy = null },
            onSave = { text ->
                val ok = PyBookSourceManager.writeContent(context, r.name, text)
                if (ok) null else "保存失败，请重试"
            },
        )
    }

    editPyTarget?.let { r ->
        var newBase by remember(r.name) { mutableStateOf(r.title) }
        TechDialog(
            onDismissRequest = { editPyTarget = null },
            title = "重命名书源",
            subtitle = "只改文件名，不改文件内容",
            confirmText = "保存",
            onConfirm = {
                scope.launch {
                    val ok = PyBookSourceManager.rename(context, r.name, newBase.trim())
                    if (!ok) {
                        AppToast.show(context, "重命名失败：名称为空、含非法字符或已存在同名文件", long = false)
                    }
                }
                editPyTarget = null
            },
        ) {
            TechTextField(value = newBase, onValueChange = { newBase = it }, placeholder = "新名称，不用写 .py")
            Spacer(Modifier.height(8.dp))
            Text(
                "保存后文件将改名为“${newBase.trim().removeSuffix(".py")}.py”，卡片标题跟着变。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/** v0.227.0：导入方式选项行——图标+文字整行可点，原 ImportAction 四钮并成弹窗后改用它 */
@Composable
private fun ImportMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp)
    }
}

/**
 * v0.214.0：字段编辑器单行（标签 + 输入框 + 字段 key 提示）。
 */
@Composable
private fun SourceField(
    label: String,
    value: String,
    hint: String? = null,
    multiLine: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    Column {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        TechTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = hint ?: label,
            singleLine = !multiLine,
            minLines = if (multiLine) 3 else 1,
        )
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
        }
    }
}

/**
 * v0.206.0：书源文本编辑——全屏对话框，等宽多行文本，保存回调返回 null 表示成功、
 * 非空表示失败原因（弹 Toast 后留在编辑器里，不丢已改的文本）。
 * v0.210.0：正文改 12.sp 等宽 + 圆角容器，字小一点、看着干净一点。
 */
@Composable
private fun SourceTextEditorDialog(
    title: String,
    initialText: String,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initialText) }
    val scrollState = rememberScrollState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
            ) { // v0.238.0：顶部贴顶，去掉多余的状态栏内边距
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = {
                        scope.launch {
                            val err = onSave(text)
                            if (err == null) {
                                AppToast.show(context, "已保存", long = false)
                                onDismiss()
                            } else {
                                AppToast.show(context, err, long = false)
                            }
                        }
                    }) { Text("保存") }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(12.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                fontFamily = FontFamily.Default,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
            }
        }
    }
}
