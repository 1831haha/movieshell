package com.movieshell.ui.market

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
 */
@Composable
fun ServerPluginScreen(onBack: () -> Unit = {}) {
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

    fun reload() { tick++ }
    LaunchedEffect(tick) {
        plugins = withContext(Dispatchers.IO) { ServicePluginManager.installedPlugins(context) }
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

    Scaffold(
        topBar = {
            TabTopBar(
                title = "服务插件",
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            if (checking) return@IconButton
                            checking = true
                            scope.launch {
                                selfCheck = withContext(Dispatchers.IO) {
                                    ServicePluginManager.runSelfCheck(context)
                                }
                                checking = false
                            }
                        },
                    ) {
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
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val dotColor = when (p.status) {
                            ServicePluginManager.Status.RUNNING -> Color(0xFF4CAF50)
                            ServicePluginManager.Status.ERROR -> Color(0xFFE53935)
                            ServicePluginManager.Status.STOPPED -> Color(0xFF9E9E9E)
                        }
                        Box(
                            modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor),
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                p.name,
                                style = SoftType.CardTitle,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "端口 ${p.port} · v${p.version} · " + when (p.status) {
                                    ServicePluginManager.Status.RUNNING -> "运行中"
                                    ServicePluginManager.Status.ERROR -> "异常（进程非 0 退出）"
                                    ServicePluginManager.Status.STOPPED -> "已停止"
                                },
                                style = SoftType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
            subtitle = "将停止进程并删除 filesDir/server_plugins/${p.id}/ 全部文件，此操作不可恢复。",
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
