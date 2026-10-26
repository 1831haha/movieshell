package com.movieshell.ui.market

import android.content.ClipData
import com.movieshell.ui.components.AppSwitch
import com.movieshell.ui.components.AppToast
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.ServicePluginManager
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppChip
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.StatusRing
import com.movieshell.ui.components.StatusRingState
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.TechTextField
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 服务插件页（v0.120.0 C3）：已装服务插件的启动/停止/日志/卸载 + 文件安装 + 环境自检。
 * 包规范见 xiaying-plugins/SERVER_PLUGIN_SPEC.md；卡片与弹窗走 AppCard / TechDialog，
 * 文字颜色走 MaterialTheme（弹窗体内容区由 TechDialog 固定浅色）。
 * v0.126.0：顶部加「示例插件」卡——内置 demo-service-plugin.zip 一键安装
 * （走 install 全套校验），装好后用列表行既有「启动」一键启动。
 * v0.132.0：补成真管理器——列表头统计 + 新建/编辑全屏编辑器（运行时/探活/
 * 自启动/常驻/环境变量/占位符插入）+ 配置导入导出 + 日志复制/清空。
 * v0.136.0：「示例插件」大卡撤掉，改头部小字「示例」入口（与新建/导入/导出
 * 同排、置于最前）：一键生成 T4-PHP 示例，已存在的跳过，
 * 全部已存在时逐名提示（仿 DS）。
 * v0.137.0：生成列表去掉示例服务，只留 T4-PHP。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServerPluginScreen(
    onBack: () -> Unit = {},
    // v0.127.0：插件中心页签嵌入模式——不渲染自己的顶栏，自检/上传降为内容区首行
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var plugins by remember { mutableStateOf<List<ServicePluginManager.PluginInfo>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var logFor by remember { mutableStateOf<ServicePluginManager.PluginInfo?>(null) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var uninstallFor by remember { mutableStateOf<ServicePluginManager.PluginInfo?>(null) }
    var selfCheck by remember { mutableStateOf<ServicePluginManager.SelfCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    // v0.127.0：服务卡启动配置（describeLaunch 只读视图）+ 配置展开态 + 探活通过集
    var launchInfos by remember {
        mutableStateOf<Map<String, ServicePluginManager.LaunchInfo>>(emptyMap())
    }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var webReady by remember { mutableStateOf<Set<String>>(emptySet()) }
    // v0.136.0：「示例」一键生成进行中标志
    var generatingExamples by remember { mutableStateOf(false) }
    // v0.132.0：全屏编辑器状态（非空时代替列表渲染）+ 编辑中服务的运行 PID + 保存中标志
    var editing by remember { mutableStateOf<ServicePluginManager.ServiceDef?>(null) }
    var editingPid by remember { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf(false) }
    // v0.132.0：导入弹窗 + 粘贴文本
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    fun reload() { tick++ }

    fun openNew() {
        editingPid = null
        editing = ServicePluginManager.blankDef()
    }

    // v0.132.0：编辑回显——serviceDef 在 IO 线程读（zip 服务也返回占位符形式）
    fun openEdit(p: ServicePluginManager.PluginInfo) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    ServicePluginManager.serviceDef(context, p.id) to
                        ServicePluginManager.pidOf(p.id)
                }.getOrNull()
            }
            val def = loaded?.first
            if (def == null) {
                AppToast.show(context, "读取服务配置失败", long = false)
            } else {
                editingPid = launchInfos[p.id]?.pid ?: loaded?.second
                editing = def
            }
        }
    }

    // v0.132.0：保存服务定义（IO 线程）；PHP/Node 本版无运行时本体，文案单独提示
    fun saveEditing(def: ServicePluginManager.ServiceDef) {
        if (saving) return
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ServicePluginManager.saveService(context, def) }
            }
            saving = false
            result.onSuccess {
                editing = null
                editingPid = null
                reload()
                val msg = if (
                    def.runtime == ServicePluginManager.RuntimeKind.PHP ||
                    def.runtime == ServicePluginManager.RuntimeKind.NODE
                ) {
                    "运行时未安装，仅保存配置"
                } else {
                    "已保存"
                }
                AppToast.show(context, msg, long = false)
            }.onFailure {
                AppToast.show(context, it.message ?: "保存失败", long = true)
            }
        }
    }

    // v0.132.0：导出全部服务配置 JSON 到剪贴板（写法照 LogBubble 的复制逻辑）
    fun doExport() {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { ServicePluginManager.exportServicesJson(context) }
                    .getOrNull()
            }
            if (text == null) {
                AppToast.show(context, "导出失败", long = false)
                return@launch
            }
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("movieshell_services", text))
                AppToast.show(context, "服务配置已复制到剪贴板", long = false)
            }.onFailure {
                AppToast.show(context, "复制失败：${it.message}", long = true)
            }
        }
    }

    // v0.136.0：「示例」一键生成（仿 DS 服务页头部小字入口）——
    // T4-PHP 走 ServicePluginManager.importT4PhpExample（importServicesJson 单一路径）。
    // 已存在的按 id/同名判存跳过、不覆盖；全部已存在时逐名提示。
    // v0.137.0：生成列表去掉示例服务，只留 T4-PHP。
    fun generateExamples() {
        if (generatingExamples) return
        generatingExamples = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val existing = ServicePluginManager.existingExampleNames(context)
                    if (existing.size == ServicePluginManager.EXAMPLE_SERVICES.size) {
                        "示例已全部存在：${existing.joinToString(" / ")}"
                    } else {
                        val created = mutableListOf<String>()
                        if ("T4-PHP" !in existing) {
                            ServicePluginManager.importT4PhpExample(context)
                            created += "T4-PHP"
                        }
                        buildString {
                            append("已生成示例：${created.joinToString(" / ")}")
                            if (existing.isNotEmpty()) {
                                append("（${existing.joinToString(" / ")}已存在，跳过）")
                            }
                            if ("T4-PHP" in created) {
                                append("；T4-PHP 需先在市场安装 PHP 运行时再启动")
                            }
                        }
                    }
                }.getOrElse { "生成示例失败：${it.message}" }
            }
            AppToast.show(context, msg, long = true)
            generatingExamples = false
            reload()
        }
    }
    LaunchedEffect(tick) {
        plugins = withContext(Dispatchers.IO) { ServicePluginManager.installedPlugins(context) }
    }
    // v0.138.0：启动中轮询——STARTING 是 5 秒宽限窗内的过渡态，仍有
    // STARTING 时每秒刷新一次，直到翻绿（运行中）或翻红（异常）才停。
    LaunchedEffect(plugins) {
        while (plugins.any { it.status == ServicePluginManager.Status.STARTING }) {
            delay(1000)
            reload()
        }
    }
    // v0.127.0：插件列表变化后同步读各服务的启动配置（命令行/路径/配置项呈现用）
    LaunchedEffect(plugins) {
        launchInfos = withContext(Dispatchers.IO) {
            plugins.mapNotNull { p ->
                ServicePluginManager.describeLaunch(context, p.id)?.let { p.id to it }
            }.toMap()
        }
    }

    // 从文件安装：选 zip → 拷临时文件 → ServicePluginManager.install
    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File.createTempFile("svc_install_", ".zip", context.cacheDir)
                    try {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("无法读取所选文件")
                        input.use { s -> tmp.outputStream().use { s.copyTo(it) } }
                        ServicePluginManager.install(context, tmp)
                        "安装成功"
                    } finally {
                        tmp.delete()
                    }
                }.getOrElse { "安装失败：${it.message}" }
            }
            AppToast.show(context, msg, long = false)
            reload()
        }
    }

    fun toggle(p: ServicePluginManager.PluginInfo) {
        if (busyId != null) return
        busyId = p.id
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    if (p.status == ServicePluginManager.Status.RUNNING ||
                        p.status == ServicePluginManager.Status.STARTING
                    ) {
                        ServicePluginManager.stop(context, p.id)
                        "${p.name} 已停止"
                    } else {
                        ServicePluginManager.start(context, p.id)
                        "${p.name} 已启动"
                    }
                }.getOrElse { "操作失败：${it.message}" }
            }
            AppToast.show(context, msg, long = false)
            busyId = null
            reload()
        }
    }

    fun runSelfCheck() {
        if (checking) return
        checking = true
        scope.launch {
            selfCheck = withContext(Dispatchers.IO) {
                ServicePluginManager.runSelfCheck(context)
            }
            checking = false
        }
    }

    BackHandler(enabled = editing != null) { editing = null }

    // v0.132.0：编辑器全屏代替列表（带返回，系统返回键同效）
    editing?.let { def ->
        ServiceEditor(
            initial = def,
            pid = editingPid,
            saving = saving,
            onBack = { editing = null },
            onSave = { saveEditing(it) },
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        topBar = {
            if (!embedded) {
                TabTopBar(
                    title = "服务插件",
                    onBack = onBack,
                    actions = {
                        TextButton(
                            onClick = { generateExamples() },
                            enabled = !generatingExamples,
                        ) {
                            Text(if (generatingExamples) "生成中…" else "示例")
                        }
                        TextButton(onClick = { openNew() }) { Text("新建服务") }
                        TextButton(onClick = { doExport() }) { Text("导出") }
                        TextButton(onClick = { showImport = true }) { Text("导入") }
                        IconButton(onClick = { runSelfCheck() }) {
                            if (checking) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Filled.BugReport, contentDescription = "环境自检")
                            }
                        }
                        IconButton(onClick = { zipPicker.launch("application/zip") }) {
                            Icon(Icons.Filled.Upload, contentDescription = "从文件安装")
                        }
                    },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // v0.132.0：列表头——运行统计一行；embedded 时同区补齐管理入口
            item {
                Column {
                    val runningCount = plugins.count {
                        it.status == ServicePluginManager.Status.RUNNING
                    }
                    Text(
                        "运行中 $runningCount · 共 ${plugins.size} 个服务",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (embedded) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(
                                onClick = { generateExamples() },
                                enabled = !generatingExamples,
                            ) {
                                Text(if (generatingExamples) "生成中…" else "示例")
                            }
                            TextButton(onClick = { openNew() }) { Text("新建服务") }
                            TextButton(onClick = { doExport() }) { Text("导出") }
                            TextButton(onClick = { showImport = true }) { Text("导入") }
                            TextButton(
                                onClick = { runSelfCheck() },
                                enabled = !checking,
                            ) {
                                Text(if (checking) "自检中…" else "环境自检")
                            }
                            TextButton(
                                onClick = { zipPicker.launch("application/zip") },
                            ) {
                                Text("从文件安装")
                            }
                        }
                    }
                }
            }
            if (plugins.isEmpty()) {
                item {
                    EmptyState(
                        kind = EmptyKind.Empty,
                        icon = Icons.Filled.Dns,
                        title = "还没有服务插件",
                        hint = "点上方「示例」一键生成 T4-PHP 示例，或从文件安装带 server.json 的 zip 包",
                    )
                }
            }
            items(plugins, key = { it.id }) { p ->
                val info = launchInfos[p.id]
                val statusText = when (p.status) {
                    ServicePluginManager.Status.RUNNING -> "运行中"
                    ServicePluginManager.Status.ERROR -> "异常（进程非 0 退出）"
                    ServicePluginManager.Status.STOPPED -> "已停止"
                    ServicePluginManager.Status.STARTING -> "启动中"
                }
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        // v0.127.0：第一行——状态点 + 名 + 端口 chip + 版本（仿 DS 服务卡结构）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // v0.139.0：状态点升级为统一 StatusRing（跑马灯圆环 + 中央状态点）
                            StatusRing(
                                state = when (p.status) {
                                    ServicePluginManager.Status.RUNNING -> StatusRingState.Ready
                                    ServicePluginManager.Status.STARTING -> StatusRingState.Loading
                                    ServicePluginManager.Status.ERROR -> StatusRingState.Failed
                                    ServicePluginManager.Status.STOPPED -> StatusRingState.Idle
                                },
                                diameter = 22.dp,
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(
                                p.name,
                                style = SoftType.CardTitle,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    ":${p.port}",
                                    style = SoftType.Caption,
                                    fontFamily = FontFamily.Default,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "v${p.version}",
                                style = SoftType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // v0.132.0：自启动小标记
                            if (p.autoStart) {
                                Spacer(Modifier.size(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        "自启动",
                                        style = SoftType.Caption,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        // 第二行：一句话作用（server.json 的 desc，缺失用兜底文案）
                        Text(
                            info?.desc?.ifBlank { null } ?: "本地 HTTP 服务",
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        // 第三行：状态行
                        Text(
                            "状态：$statusText · 127.0.0.1:${p.port}",
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        // 命令行行 + 工作目录行（真实绝对路径，端口已替换为实际分配值）
                        if (info != null) {
                            Text(
                                info.commandLine,
                                style = SoftType.Caption,
                                fontFamily = FontFamily.Default,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                info.workDirPath,
                                style = SoftType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // v0.127.0：启动配置只读展开区（server.json 关键项，路径为安装后真实路径）
                        // v0.132.0：补探活方式/自启动/常驻前台/运行 PID 四行
                        if (expandedId == p.id && info != null) {
                            Spacer(Modifier.height(8.dp))
                            ConfigRow("启动命令", info.command)
                            ConfigRow(
                                "参数",
                                info.args.joinToString(" ").ifBlank { "（无）" },
                            )
                            ConfigRow("端口策略", info.portSpec)
                            ConfigRow("健康检查", info.healthPath)
                            ConfigRow("工作目录", info.workDirPath)
                            ConfigRow(
                                "探活方式",
                                when (info.healthMode) {
                                    "PROCESS" -> "仅进程"
                                    "TCP" -> "TCP"
                                    "HTTP" -> "HTTP"
                                    else -> info.healthMode
                                },
                            )
                            ConfigRow("自启动", if (info.autoStart) "开" else "关")
                            ConfigRow("常驻前台", if (info.resident) "开" else "关")
                            if (info.pid != null) {
                                ConfigRow("进程 PID", info.pid.toString())
                            }
                            if (info.envKeys.isNotEmpty()) {
                                ConfigRow("环境变量", info.envKeys.joinToString("、"))
                            }
                        }
                    }
                    // v0.132.0：按钮行改 FlowRow 右对齐（加「编辑」后窄屏自动换行不溢出）
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        TextButton(
                            onClick = { toggle(p) },
                            enabled = busyId == null,
                        ) {
                            Text(
                                if (p.status == ServicePluginManager.Status.RUNNING ||
                                    p.status == ServicePluginManager.Status.STARTING
                                ) "停止" else "启动",
                            )
                        }
                        TextButton(onClick = {
                            logFor = p
                            scope.launch {
                                logLines = withContext(Dispatchers.IO) {
                                    ServicePluginManager.logs(p.id).takeLast(50)
                                }
                            }
                        }) { Text("日志") }
                        TextButton(onClick = {
                            scope.launch {
                                val ok = ServicePluginManager.healthCheck(context, p.id)
                                if (ok) {
                                    webReady = webReady + p.id
                                    AppToast.show(context, "探活通过：服务可达", long = false)
                                } else {
                                    webReady = webReady - p.id
                                    AppToast.show(context, "探活失败：服务未响应", long = false)
                                }
                            }
                        }) { Text("探活") }
                        // 打开 Web 首页：只在探活通过且运行中时给（与 healthCheck 同口径地址）
                        if (p.id in webReady &&
                            p.status == ServicePluginManager.Status.RUNNING
                        ) {
                            TextButton(onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("http://127.0.0.1:${p.port}/"),
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }) { Text("打开") }
                        }
                        TextButton(onClick = { openEdit(p) }) { Text("编辑") }
                        TextButton(onClick = {
                            expandedId = if (expandedId == p.id) null else p.id
                        }) { Text(if (expandedId == p.id) "收起" else "配置") }
                        TextButton(onClick = { uninstallFor = p }) { Text("卸载") }
                    }
                }
            }
        }
    }

    // 日志弹窗：环形缓冲最近 50 行；v0.132.0 顶部加复制/清空
    logFor?.let { p ->
        TechDialog(
            onDismissRequest = { logFor = null },
            title = "${p.name} · 日志",
            subtitle = "最近 ${logLines.size} 行（进程未运行时为空）",
            dismissText = "关闭",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    runCatching {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText(
                                "movieshell_log", logLines.joinToString("\n"),
                            ),
                        )
                        AppToast.show(context, "日志已复制到剪贴板", long = false)
                    }.onFailure {
                        AppToast.show(context, "复制失败：${it.message}", long = true)
                    }
                }) { Text("复制") }
                TextButton(onClick = {
                    ServicePluginManager.clearLogs(p.id)
                    logLines = emptyList()
                    AppToast.show(context, "日志已清空", long = false)
                }) { Text("清空") }
            }
            Column(
                modifier = Modifier.fillMaxWidth().height(320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (logLines.isEmpty()) {
                    Text("（暂无日志）", style = SoftType.Body)
                } else {
                    logLines.forEach { line ->
                        Text(line, style = SoftType.Caption, fontFamily = FontFamily.Default)
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }

    // v0.132.0：导入弹窗——粘贴导出的 JSON，重名自动改名不覆盖（后端规则，文案写明）
    if (showImport) {
        TechDialog(
            onDismissRequest = { showImport = false },
            title = "导入服务配置",
            subtitle = "重名服务自动改名加后缀（-2、-3…），不会覆盖已有服务",
            confirmText = "导入",
            onConfirm = {
                if (importText.isBlank()) {
                    AppToast.show(context, "请先粘贴 JSON", long = false)
                    return@TechDialog
                }
                val text = importText
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            ServicePluginManager.importServicesJson(context, text)
                        }
                    }
                    result.onSuccess { summary ->
                        showImport = false
                        importText = ""
                        AppToast.show(context, summary, long = true)
                        reload()
                    }.onFailure {
                        AppToast.show(context, it.message ?: "导入失败", long = true)
                    }
                }
            },
        ) {
            TechTextField(
                value = importText,
                onValueChange = { importText = it },
                placeholder = "粘贴导出的服务配置 JSON",
                singleLine = false,
                minLines = 6,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
        }
    }

    // 卸载确认
    uninstallFor?.let { p ->
        TechDialog(
            onDismissRequest = { uninstallFor = null },
            title = "卸载 ${p.name}？",
            subtitle = "将停止进程并删除 ${
                launchInfos[p.id]?.let { File(it.workDirPath).parentFile?.absolutePath }
                    ?: "该服务工作目录"
            } 全部文件，此操作不可恢复。",
            confirmText = "卸载",
            onConfirm = {
                uninstallFor = null
                scope.launch {
                    val msg = withContext(Dispatchers.IO) {
                        runCatching {
                            ServicePluginManager.uninstall(context, p.id)
                            "已卸载 ${p.name}"
                        }.getOrElse { "卸载失败：${it.message}" }
                    }
                    AppToast.show(context, msg, long = false)
                    reload()
                }
            },
        ) { }
    }

    // 环境自检结果
    selfCheck?.let { result ->
        TechDialog(
            onDismissRequest = { selfCheck = null },
            title = "环境自检",
            subtitle = if (result.allOk) "全部通过" else "有失败项，详情如下",
            dismissText = "关闭",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                result.items.forEach { item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            if (item.ok) "✓" else "✗",
                            color = if (item.ok) Color(0xFF81C784) else Color(0xFFE57373),
                            style = SoftType.Body,
                        )
                        Spacer(Modifier.size(8.dp))
                        Column {
                            Text(item.name, style = SoftType.Body)
                            Text(
                                item.detail,
                                style = SoftType.Caption,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** v0.127.0：服务卡「配置」展开区的一行（标签固定宽 + 等宽值），全卡各行同口径对齐。 */
@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = SoftType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = SoftType.Caption,
            fontFamily = FontFamily.Default,
            modifier = Modifier.weight(1f),
        )
    }
}

/** v0.132.0：编辑器字段小标签（与卡片同口径的 Caption + 次级色）。 */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = SoftType.Caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * v0.134.0：编辑器分段卡——AppCard 包一段字段，卡内 14dp 内边距、段内 8dp 间距；
 * title 非空时段首一行 CardTitle。仅布局收敛，字段逻辑不经此组件。
 */
@Composable
private fun EditorSection(
    title: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) {
                Text(
                    title,
                    style = SoftType.CardTitle,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            content()
        }
    }
}

/**
 * v0.132.0：服务全屏编辑器——基本配置 + 高级配置（可执行文件/工作目录/
 * 环境变量/探活方式）+ 占位符 chip 插入最近聚焦字段。字段组件照全屏页表单
 * v0.146.0：高级配置不再折叠、直接展示；页边距收紧、段标题用主题主色。
 * 现成口径用 AppTextField；保存由宿主在 IO 线程调 saveService 并回列表。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceEditor(
    initial: ServicePluginManager.ServiceDef,
    pid: Long?,
    saving: Boolean,
    onBack: () -> Unit,
    onSave: (ServicePluginManager.ServiceDef) -> Unit,
) {
    val context = LocalContext.current
    var name by remember(initial) { mutableStateOf(initial.name) }
    var desc by remember(initial) { mutableStateOf(initial.desc) }
    var portText by remember(initial) {
        mutableStateOf(if (initial.port > 0) initial.port.toString() else "")
    }
    var runtime by remember(initial) { mutableStateOf(initial.runtime) }
    var argsText by remember(initial) { mutableStateOf(initial.args.joinToString(" ")) }
    var autoStart by remember(initial) { mutableStateOf(initial.autoStart) }
    var resident by remember(initial) { mutableStateOf(initial.resident) }
    var command by remember(initial) { mutableStateOf(initial.command) }
    var workDir by remember(initial) { mutableStateOf(initial.workDir) }
    val envList = remember(initial) {
        mutableStateListOf<Pair<String, String>>().apply { addAll(initial.env) }
    }
    var healthMode by remember(initial) { mutableStateOf(initial.healthMode) }
    var healthPath by remember(initial) { mutableStateOf(initial.healthPath) }
    // 占位符插入目标：command / workDir / args / env:<下标>，默认 command
    var insertTarget by remember(initial) { mutableStateOf("command") }

    fun insertPlaceholder(ph: String) {
        when {
            insertTarget == "workDir" -> workDir += ph
            insertTarget == "args" -> argsText += ph
            insertTarget.startsWith("env:") -> {
                val idx = insertTarget.removePrefix("env:").toIntOrNull()
                if (idx != null && idx in envList.indices) {
                    val pair = envList[idx]
                    envList[idx] = pair.first to (pair.second + ph)
                }
            }
            else -> command += ph
        }
    }

    fun doSave() {
        if (name.isBlank()) {
            AppToast.show(context, "名称不能为空", long = false)
            return
        }
        onSave(
            initial.copy(
                name = name.trim(),
                desc = desc.trim(),
                port = portText.toIntOrNull() ?: 0,
                runtime = runtime,
                command = command.trim(),
                args = argsText.trim()
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() },
                workDir = workDir.trim(),
                env = envList
                    .filter { it.first.isNotBlank() }
                    .map { it.first.trim() to it.second },
                healthMode = healthMode,
                healthPath = healthPath.trim(),
                autoStart = autoStart,
                resident = resident,
            ),
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        topBar = {
            TabTopBar(
                title = if (initial.id.isBlank()) "新建服务" else "编辑服务",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EditorSection(title = "基本配置") {
            FieldLabel("名称（必填）")
            AppTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "服务名称",
                modifier = Modifier.fillMaxWidth(),
            )
            FieldLabel("描述")
            AppTextField(
                value = desc,
                onValueChange = { desc = it },
                placeholder = "一句话说明这个服务是做什么的",
                modifier = Modifier.fillMaxWidth(),
            )
            FieldLabel("端口")
            AppTextField(
                value = portText,
                onValueChange = { v -> portText = v.filter { it.isDigit() } },
                placeholder = "如 29580",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "0 或留空 = 自动分配（从 29578 起找空闲端口）",
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FieldLabel("运行时")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = runtime == ServicePluginManager.RuntimeKind.SCRIPT,
                    onClick = { runtime = ServicePluginManager.RuntimeKind.SCRIPT },
                    label = { Text("脚本") },
                )
                FilterChip(
                    selected = runtime == ServicePluginManager.RuntimeKind.PHP,
                    onClick = {
                        runtime = ServicePluginManager.RuntimeKind.PHP
                        if (command.isBlank()) command = "__plugin:php__/bin/php"
                    },
                    label = { Text("PHP") },
                )
                FilterChip(
                    selected = runtime == ServicePluginManager.RuntimeKind.NODE,
                    onClick = {
                        runtime = ServicePluginManager.RuntimeKind.NODE
                        if (command.isBlank()) command = "__plugin:nodejs__/bin/node"
                    },
                    label = { Text("Node") },
                )
                FilterChip(
                    selected = runtime == ServicePluginManager.RuntimeKind.CUSTOM,
                    onClick = { runtime = ServicePluginManager.RuntimeKind.CUSTOM },
                    label = { Text("自定义") },
                )
            }
            FieldLabel("启动参数")
            AppTextField(
                value = argsText,
                onValueChange = { argsText = it },
                placeholder = "多个参数以空格分隔",
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) insertTarget = "args" },
            )
            }
            EditorSection(title = null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "自启动",
                    style = SoftType.Body,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                AppSwitch(checked = autoStart, onCheckedChange = { autoStart = it })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "常驻前台",
                    style = SoftType.Body,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "下一版生效",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                AppSwitch(checked = resident, onCheckedChange = { resident = it })
            }
            }
            EditorSection(title = "占位符") {
            Text(
                "点按插入到最近聚焦的字段末尾",
                style = SoftType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    "__filesDir__",
                    "__sdcard__",
                    "__pkg__",
                    "__plugin:hello-service__",
                ).forEach { ph ->
                    AppChip(
                        text = ph,
                        selected = false,
                        small = true,
                        onClick = { insertPlaceholder(ph) },
                    )
                }
            }
            }
            EditorSection(title = "高级配置") {
            FieldLabel("可执行文件")
            AppTextField(
                value = command,
                onValueChange = { command = it },
                placeholder = "如 server.sh 或 __plugin:php__/bin/php",
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) insertTarget = "command" },
            )
            FieldLabel("工作目录")
            AppTextField(
                value = workDir,
                onValueChange = { workDir = it },
                placeholder = "如 __plugin:hello-service__",
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) insertTarget = "workDir" },
            )
            FieldLabel("环境变量")
            envList.forEachIndexed { idx, pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppTextField(
                        value = pair.first,
                        onValueChange = { v ->
                            envList[idx] = v to envList[idx].second
                        },
                        placeholder = "KEY",
                        modifier = Modifier.weight(1f),
                    )
                    AppTextField(
                        value = pair.second,
                        onValueChange = { v ->
                            envList[idx] = envList[idx].first to v
                        },
                        placeholder = "VALUE",
                        modifier = Modifier.weight(1f)
                            .onFocusChanged {
                                if (it.isFocused) insertTarget = "env:$idx"
                            },
                    )
                    TextButton(onClick = { envList.removeAt(idx) }) {
                        Text("删除")
                    }
                }
            }
            TextButton(onClick = { envList.add("" to "") }) {
                Text("添加变量")
            }
            FieldLabel("探活方式")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = healthMode == ServicePluginManager.HealthMode.PROCESS,
                    onClick = {
                        healthMode = ServicePluginManager.HealthMode.PROCESS
                    },
                    label = { Text("仅进程") },
                )
                FilterChip(
                    selected = healthMode == ServicePluginManager.HealthMode.TCP,
                    onClick = { healthMode = ServicePluginManager.HealthMode.TCP },
                    label = { Text("TCP") },
                )
                FilterChip(
                    selected = healthMode == ServicePluginManager.HealthMode.HTTP,
                    onClick = {
                        healthMode = ServicePluginManager.HealthMode.HTTP
                    },
                    label = { Text("HTTP") },
                )
            }
            FieldLabel("HTTP 路径")
            if (healthMode == ServicePluginManager.HealthMode.HTTP) {
                AppTextField(
                    value = healthPath,
                    onValueChange = { healthPath = it },
                    placeholder = "如 /healthz",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    healthPath.ifBlank { "（未设置）" } + "（仅 HTTP 探活时可编辑）",
                    style = SoftType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pid != null) {
                ConfigRow("进程 PID", pid.toString())
            }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                AppButton(
                    text = if (saving) "保存中…" else "保存",
                    onClick = { doSave() },
                    enabled = !saving,
                )
            }
        }
    }
}
