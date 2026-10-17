package com.movieshell.ui.novel

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.novel.BookSource
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.novel.PyBookSourceManager
import kotlinx.coroutines.launch

/**
 * v0.61.0 书源管理：列表 / 启用开关 / 删除 / 粘贴 JSON 导入。
 * 兼容"阅读"App 导出的 bookSource.json（单条或数组）。
 */
@Composable
fun BookSourceScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gold = Color(0xFFD9C39A)

    val sources by BookSourceManager.sources.collectAsState()
    // v0.64.0：本地 PY 书源
    val pyRules by PyBookSourceManager.rules.collectAsState()
    val pyEnabled by PyBookSourceManager.enabled.collectAsState()
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<BookSource?>(null) }
    var deletePyTarget by remember { mutableStateOf<PyBookSourceManager.PyBookRule?>(null) }

    LaunchedEffect(Unit) {
        BookSourceManager.load(context)
        PyBookSourceManager.load(context)
    }

    val pyImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = when (val r = PyBookSourceManager.import(context, uri)) {
                is PyBookSourceManager.ImportResult.Ok -> "已导入 ${r.fileName}"
                PyBookSourceManager.ImportResult.WrongExt -> "请选择 .py 文件"
                PyBookSourceManager.ImportResult.InvalidRule ->
                    "不是有效的书源文件（需要实现 bookSearch 方法）"
                is PyBookSourceManager.ImportResult.Failed -> "导入失败：${r.msg}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = gold)
            }
            Text("书源管理", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = gold)
            Spacer(Modifier.weight(1f))
            // v0.64.0：导入本地 .py 书源
            TextButton(onClick = { pyImportLauncher.launch("*/*") }) {
                Text("导入本地PY", color = gold, fontSize = 13.sp)
            }
            IconButton(onClick = { importText = ""; showImport = true }) {
                Icon(Icons.Filled.Add, "导入书源", tint = gold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "右上角 + 粘贴阅读 App 导出的 bookSource.json 导入；「导入本地PY」选 .py 书源文件；开关控制搜索时是否使用该源",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
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
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ),
                    ) {
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
            }
            if (sources.isEmpty() && pyRules.isEmpty()) {
                item(key = "empty") {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "还没有书源，点右上角导入",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入书源") },
            text = {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    placeholder = { Text("粘贴 bookSource.json（单条或数组）") },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val n = BookSourceManager.import(context, importText)
                        showImport = false
                        Toast.makeText(
                            context,
                            if (n > 0) "导入 $n 个书源" else "没有识别出书源，检查 JSON 格式",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { s ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除书源") },
            text = { Text("确定删除「${s.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { BookSourceManager.remove(context, s.key) }
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    // v0.64.0：删除本地 PY 书源
    deletePyTarget?.let { r ->
        AlertDialog(
            onDismissRequest = { deletePyTarget = null },
            title = { Text("删除书源") },
            text = { Text("确定删除本地 PY 书源「${r.title}」？文件也会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { PyBookSourceManager.remove(context, r.name) }
                    deletePyTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletePyTarget = null }) { Text("取消") }
            },
        )
    }
}
