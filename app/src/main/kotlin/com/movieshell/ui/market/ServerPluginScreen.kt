package com.movieshell.ui.market

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.ServicePluginManager
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 服务插件页（v0.120.0 C3）：已装服务插件的启动/停止/日志/卸载 + 文件安装 + 环境自检。
 * 包规范见 xiaying-plugins/SERVER_PLUGIN_SPEC.md；卡片与弹窗走 AppCard / TechDialog，
 * 文字颜色走 MaterialTheme（弹窗体内容区由 TechDialog 固定浅色）。
 * v0.126.0：顶部加「示例插件」卡——内置 demo-service-plugin.zip 一键安装
 * （走 install 全套校验），装好后用列表行既有「启动」一键启动。
 */
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
    // v0.126.0：内置示例插件一键安装中标志
    var installingDemo by remember { mutableStateOf(false) }

    fun reload() { tick++ }

    // v0.126.0：内置示例插件（assets/demo-service-plugin.zip）一键安装——
    // 拷到 cacheDir 后走 ServicePluginManager.install 全部既有校验，不开后门
    fun installDemo() {
        if (installingDemo) return
        installingDemo = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File.createTempFile("demo_svc_", ".zip", context.cacheDir)
                    try {
                        context.assets.open("demo-service-plugin.zip").use { s ->
                            tmp.outputStream().use { s.copyTo(it) }
                        }
                        ServicePluginManager.install(context, tmp)
                        "示例服务已安装，在下方列表点「启动」即可运行"
                    } finally {
                        tmp.delete()
                    }
                }.getOrElse { "安装失败：${it.message}" }
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            installingDemo = false
            reload()
        }
    }
    LaunchedEffect(tick) {
        plugins = withContext(Dispatchers.IO) { ServicePluginManager.installedPlugins(context) }
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
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            reload()
        }
    }

    fun toggle(p: ServicePluginManager.PluginInfo) {
        if (busyId != null) return
        busyId = p.id
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    if (p.status == ServicePluginManager.Status.RUNNING) {
                        ServicePluginManager.stop(context, p.id)
                        "${p.name} 已停止"
                    } else {
                        ServicePluginManager.start(context, p.id)
                        "${p.name} 已启动"
                    }
                }.getOrElse { "操作失败：${it.message}" }
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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

    Scaffold(
        topBar = {
            if (!embedded) {
                TabTopBar(
                    title = "服务插件",
                    onBack = onBack,
                    actions = {
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
            if (embedded) {
                // v0.127.0：嵌入模式的内容区首行——环境自检 / 从文件安装（替代原顶栏）
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { runSelfCheck() }, enabled = !checking) {
                            Text(if (checking) "自检中…" else "环境自检")
                        }
                        TextButton(onClick = { zipPicker.launch("application/zip") }) {
                            Text("从文件安装")
                        }
                    }
                }
            }
            // v0.126.0：示例插件卡（列表之上）——未装给一键安装，装好引导去列表启动
            item {
                val demoInstalled = plugins.any { it.id == "hello-service" }
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "示例插件",
                            style = SoftType.CardTitle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "内置一个最小服务插件「示例服务」：装好后在本页列表点「启动」，" +
                                "它会在本机端口提供探活页并持续输出日志，先摸清服务插件的安装与运行。",
                            style = SoftType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (demoInstalled) {
                                Text(
                                    "已安装 · 到下方列表点「启动」",
                                    style = SoftType.Caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                TextButton(
                                    onClick = { installDemo() },
                                    enabled = !installingDemo,
                                ) {
                                    Text(if (installingDemo) "安装中…" else "一键安装")
                                }
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
                        hint = "点右上角上传按钮，从文件安装一个带 server.json 的 zip 包",
                    )
                }
            }
            items(plugins, key = { it.id }) { p ->
                val info = launchInfos[p.id]
                val statusText = when (p.status) {
                    ServicePluginManager.Status.RUNNING -> "运行中"
                    ServicePluginManager.Status.ERROR -> "异常（进程非 0 退出）"
                    ServicePluginManager.Status.STOPPED -> "已停止"
                }
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        // v0.127.0：第一行——状态点 + 名 + 端口 chip + 版本（仿 DS 服务卡结构）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dotColor = when (p.status) {
                                ServicePluginManager.Status.RUNNING -> Color(0xFF4CAF50)
                                ServicePluginManager.Status.ERROR -> Color(0xFFE53935)
                                ServicePluginManager.Status.STOPPED -> Color(0xFF9E9E9E)
                            }
                            Box(
                                modifier = Modifier.size(10.dp)
                                    .clip(CircleShape).background(dotColor),
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
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "v${p.version}",
                                style = SoftType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                                fontFamily = FontFamily.Monospace,
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
                            if (info.envKeys.isNotEmpty()) {
                                ConfigRow("环境变量", info.envKeys.joinToString("、"))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = { toggle(p) },
                            enabled = busyId == null,
                        ) {
                            Text(
                                if (p.status == ServicePluginManager.Status.RUNNING) "停止" else "启动",
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
                                    Toast.makeText(
                                        context, "探活通过：服务可达", Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    webReady = webReady - p.id
                                    Toast.makeText(
                                        context, "探活失败：服务未响应", Toast.LENGTH_SHORT,
                                    ).show()
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
                        TextButton(onClick = {
                            expandedId = if (expandedId == p.id) null else p.id
                        }) { Text(if (expandedId == p.id) "收起" else "配置") }
                        TextButton(onClick = { uninstallFor = p }) { Text("卸载") }
                    }
                }
            }
        }
    }

    // 日志弹窗：环形缓冲最近 50 行
    logFor?.let { p ->
        TechDialog(
            onDismissRequest = { logFor = null },
            title = "${p.name} · 日志",
            subtitle = "最近 ${logLines.size} 行（进程未运行时为空）",
            dismissText = "关闭",
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().height(320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (logLines.isEmpty()) {
                    Text("（暂无日志）", style = SoftType.Body)
                } else {
                    logLines.forEach { line ->
                        Text(line, style = SoftType.Caption, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
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
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}
