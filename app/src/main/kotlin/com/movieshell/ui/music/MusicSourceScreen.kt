package com.movieshell.ui.music

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.repo.MusicSource
import com.movieshell.data.repo.MusicSourceStore
import com.movieshell.data.source.LxMusicSourceEngine
import com.movieshell.data.source.resolveBuiltinHuibq
import com.movieshell.player.MusicPlayer
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.TechTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v0.100.0 音乐源管理页：洛雪音源（脚本 URL）增删改查 + 测试解析。
 *
 * 测试：内置 huibq 走顶层 resolveBuiltinHuibq（纯 HTTP）；自定义源走
 * LxMusicSourceEngine.resolveMusicUrl（QuickJS 跑 LX 脚本）。
 */
@Composable
fun MusicSourceScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lxEngine = remember(context) { LxMusicSourceEngine(context) }
    // v0.100.0：页面退出时释放引擎，避免 QuickJs 实例泄漏
    DisposableEffect(lxEngine) { onDispose { runCatching { lxEngine.release() } } }
    var sources by remember { mutableStateOf<List<MusicSource>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch { sources = MusicSourceStore.list(context) }
    }
    LaunchedEffect(Unit) { reload() }

    // v0.103.0：本地 .js 文件导入。mime 传 */*（很多文件管理器给 .js 报奇怪 mime），
    // 选完后校验扩展名或内容头含 EVENT_NAMES/globalThis.lx，不对则 Toast。
    val jsImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            val looksJs = uri.path.orEmpty().endsWith(".js", ignoreCase = true) ||
                text.orEmpty().contains("EVENT_NAMES") ||
                text.orEmpty().contains("globalThis.lx")
            if (text.isNullOrBlank() || !looksJs) {
                Toast.makeText(context, "不是有效的洛雪音源脚本", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val tmp = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File.createTempFile("lx_import", ".js", context.cacheDir)
                    f.writeText(text)
                    f
                }.getOrNull()
            }
            if (tmp == null) {
                Toast.makeText(context, "导入失败：无法读取文件", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val displayName = uri.lastPathSegment?.takeIf { it.isNotBlank() }
                ?.removeSuffix(".js") ?: "本地音源"
            val added = MusicSourceStore.addLocal(context, displayName, tmp)
            runCatching { tmp.delete() }
            if (added != null) {
                Toast.makeText(context, "已导入 ${added.name}", Toast.LENGTH_SHORT).show()
                showAdd = false
                reload()
            } else {
                Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun doTest(source: MusicSource) {
        val urlId = MusicPlayer.state.value.track?.urlId
        if (urlId.isNullOrBlank()) {
            Toast.makeText(context, "先播放一首歌再测试", Toast.LENGTH_SHORT).show()
            return
        }
        testing = true
        scope.launch {
            val url = withContext(Dispatchers.IO) {
                if (source.builtin) {
                    runCatching { resolveBuiltinHuibq("wy", urlId, "320k") }.getOrNull()
                } else {
                    runCatching {
                        lxEngine.resolveMusicUrl(source.url, "wy", urlId, "320k")
                    }.getOrNull()
                }
            }
            testMsg = if (url.isNullOrBlank()) "解析失败"
            else "解析成功：\n${url.take(80)}"
            testing = false
        }
    }

    Scaffold(
        topBar = { TabTopBar(title = "音乐源管理", onBack = onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加音乐源")
            }
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(sources, key = { it.id }) { source ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = source.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (source.builtin) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "内置",
                                    fontSize = 11.sp,
                                    color = Color(0xFFD9C39A),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFD9C39A).copy(alpha = 0.16f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (source.url.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = source.url,
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.45f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { doTest(source) }) {
                        Text("测试", fontSize = 13.sp)
                    }
                    Switch(
                        checked = source.enabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                MusicSourceStore.setEnabled(context, source.id, enabled)
                                reload()
                            }
                        },
                    )
                    if (!source.builtin) {
                        IconButton(onClick = {
                            scope.launch {
                                MusicSourceStore.remove(context, source.id)
                                reload()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "删除音乐源",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // 添加音乐源
    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        TechDialog(
            onDismissRequest = { showAdd = false },
            title = "添加音乐源",
            subtitle = "粘贴洛雪音乐源脚本地址（.js）",
            confirmText = "添加",
            onConfirm = {
                if (name.isBlank() || url.isBlank()) {
                    Toast.makeText(context, "名称和脚本地址不能为空", Toast.LENGTH_SHORT).show()
                    return@TechDialog
                }
                scope.launch {
                    MusicSourceStore.add(context, MusicSource(name = name.trim(), url = url.trim()))
                    showAdd = false
                    reload()
                }
            },
        ) {
            TechTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "名称，如：六音音源",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            TechTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = "脚本 URL，如 https://…/lx-source.js",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            TextButton(
                onClick = { jsImportLauncher.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("从本地文件导入（.js）", fontSize = 14.sp)
            }
        }
    }

    // 测试中 / 测试结果
    if (testing || testMsg != null) {
        TechDialog(
            onDismissRequest = { testMsg = null },
            title = "测试内置源解析",
            dismissText = if (testing) null else "关闭",
            onConfirm = null,
        ) {
            if (testing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Text(
                    text = testMsg.orEmpty(),
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}
