package com.movieshell.ui.lab

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.SniffPatternStore
import com.movieshell.data.repo.StoragePaths
import java.io.File

/**
 * v0.15.0：实验室页面。
 *
 * - 插件管理：已安装插件列表（PY/drpy/spider）
 * - 插件添加入口：右下角 + 按钮
 * - v0.16.1：嗅探自定义正则管理（对标 DsPlayer"扩展正则"）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showAddPattern by remember { mutableStateOf(false) }
    var patterns by remember { mutableStateOf(SniffPatternStore.list()) }

    fun refreshPatterns() {
        patterns = SniffPatternStore.list()
    }

    // v0.16.2：插件文件导入（.py/.js/.jar）
    val pluginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = importPluginFile(context, uri)
        Toast.makeText(context, result, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("实验室") },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    LogBus.i("UI", "点击实验室添加插件")
                    pluginLauncher.launch("*/*")
                }
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加插件")
            }
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
        ) {
            item {
                Text(
                    text = "插件管理",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "已安装的 PY / drpy / spider 插件将显示在这里",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }
            // TODO: 插件列表
            item {
                Column {
                    Icon(
                        Icons.Filled.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "暂无插件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
            // v0.16.1：嗅探自定义正则
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "嗅探正则",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showAddPattern = true }) {
                        Text("添加")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "匹配到的 URL 直接视为可播直链（如：\\.m3u8\\?.*token=）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (patterns.isEmpty()) {
                item {
                    Text(
                        text = "暂无自定义正则",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(patterns) { pattern ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = pattern,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                LogBus.i("UI", "删除嗅探正则: $pattern")
                                SniffPatternStore.remove(pattern)
                                refreshPatterns()
                            }
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }

    // 添加正则对话框
    if (showAddPattern) {
        var input by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAddPattern = false },
            title = { Text("添加嗅探正则") },
            text = {
                Column {
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            error = null
                        },
                        label = { Text("正则表达式") },
                        placeholder = { Text("\\.m3u8\\?.*") },
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (error != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (SniffPatternStore.add(input)) {
                            LogBus.i("UI", "添加嗅探正则: $input")
                            refreshPatterns()
                            showAddPattern = false
                        } else {
                            error = "正则格式无效或已存在"
                        }
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPattern = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * v0.16.2：导入插件文件到对应目录。
 * - .py → py 目录
 * - .js → js 目录
 * - .jar → spider 目录
 * 返回提示文本。
 */
private fun importPluginFile(context: Context, uri: Uri): String {
    return runCatching {
        val fileName = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
            }
        }.getOrNull() ?: "plugin_${System.currentTimeMillis()}"

        val targetDir = when {
            fileName.endsWith(".py", ignoreCase = true) -> StoragePaths.pyDir(context)
            fileName.endsWith(".js", ignoreCase = true) -> StoragePaths.jsDir(context)
            fileName.endsWith(".jar", ignoreCase = true) -> StoragePaths.spiderDir(context)
            else -> return "不支持的文件类型（仅 .py/.js/.jar）"
        }
        targetDir.mkdirs()
        val dst = File(targetDir, fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { input.copyTo(it) }
        }
        LogBus.i("Lab", "插件已导入: $fileName -> ${targetDir.name}")
        "已导入 $fileName"
    }.onFailure {
        LogBus.e("Lab", "插件导入失败: ${it.message}")
    }.getOrDefault("导入失败")
}
