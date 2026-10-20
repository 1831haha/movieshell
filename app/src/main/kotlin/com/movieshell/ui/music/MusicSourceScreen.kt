package com.movieshell.ui.music

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateMapOf
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
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.LxCookieKit
import com.movieshell.data.source.LxMusicSourceEngine
import com.movieshell.data.source.resolveBuiltinHuibq
import com.movieshell.player.MusicPlayer
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.TechTextField
import com.movieshell.ui.components.goldTitleColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.UUID

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
    var sources by remember { mutableStateOf<List<MusicSource>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    /** v0.109.0 A6：测试协程句柄——testing 期间允许用户取消（返回键/点外部） */
    var testJob by remember { mutableStateOf<Job?>(null) }
    // v0.100.0：页面退出时释放引擎，避免 JS 引擎实例泄漏
    DisposableEffect(lxEngine) {
        onDispose {
            // v0.109.0 A6：在飞的测试协程先取消，再释引擎
            testJob?.cancel()
            runCatching { lxEngine.release() }
        }
    }

    fun reload() {
        scope.launch { sources = MusicSourceStore.list(context) }
    }
    LaunchedEffect(Unit) { reload() }

    // v0.118.0：Cookie 配置——扫描脚本头部 @xxx_cookie 声明动态生成输入框；
    // 保存写回脚本头部，远端脚本先转存本地副本（file://）再改
    var cookieTarget by remember { mutableStateOf<MusicSource?>(null) }
    var cookieSlots by remember { mutableStateOf<List<LxCookieKit.CookieSlot>>(emptyList()) }
    val cookieValues = remember { mutableStateMapOf<String, String>() }
    var cookieText by remember { mutableStateOf<String?>(null) }
    var cookieLoading by remember { mutableStateOf(false) }

    fun readScriptText(url: String): String? = runCatching {
        if (url.startsWith("file://")) {
            File(URI(url)).takeIf { it.isFile }?.readText()
        } else {
            CmsApi.defaultClient().newCall(Request.Builder().url(url).get().build())
                .execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
        }
    }.getOrNull()

    fun openCookie(source: MusicSource) {
        cookieTarget = source
        cookieSlots = emptyList()
        cookieValues.clear()
        cookieText = null
        cookieLoading = true
        scope.launch {
            val text = withContext(Dispatchers.IO) { readScriptText(source.url) }
            cookieLoading = false
            cookieText = text
            if (text != null) {
                val slots = LxCookieKit.scan(text)
                cookieSlots = slots
                slots.forEach { cookieValues[it.key] = it.current }
            }
        }
    }

    fun saveCookie() {
        val src = cookieTarget ?: return
        val base = cookieText ?: return
        scope.launch {
            var newText = base
            cookieSlots.forEach { slot ->
                newText = LxCookieKit.apply(newText, slot.key, cookieValues[slot.key].orEmpty())
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (src.url.startsWith("file://")) {
                        File(URI(src.url)).writeText(newText)
                    } else {
                        val dir = File(context.filesDir, "lx_sources")
                        if (!dir.isDirectory) dir.mkdirs()
                        val dest = File(dir, "${UUID.randomUUID()}.js")
                        dest.writeText(newText)
                        MusicSourceStore.add(
                            context,
                            src.copy(url = "file://${dest.absolutePath}"),
                        )
                    }
                }.isSuccess
            }
            lxEngine.evictScript(src.url)
            cookieTarget = null
            reload()
            Toast.makeText(
                context,
                if (ok) "Cookie 已保存，脚本已重载" else "保存失败",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // v0.103.0：档次 chips——进入管理页对启用的自定义源并发 loadScript
    //（引擎内已有 15s 超时，不另起超时），成功后读 inited 上报的 qualitys；
    // file:// 本地源同样走 loadScript。value=null=加载中，空 list=失败/无数据
    val qualityOf = remember { mutableStateMapOf<String, List<String>?>() }
    LaunchedEffect(sources, lxEngine) {
        sources
            .filter { it.enabled && !it.builtin && it.url.isNotBlank() && it.id !in qualityOf }
            .forEach { s ->
                qualityOf[s.id] = null
                launch {
                    qualityOf[s.id] =
                        if (lxEngine.loadScript(s.url)) lxEngine.getQualityIds(s.url)
                        else emptyList()
                }
            }
    }

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
        testMsg = null
        testing = true
        // v0.109.0 A6：testing 期间允许取消——关弹窗（返回键/点外部）即 cancel；
        // 旧 runCatching 会吞 CancellationException 导致取消后仍弹"解析失败"，这里显式重抛
        testJob?.cancel()
        val job = scope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    runCatching {
                        if (source.builtin) {
                            // v0.106.0：按内置源自报的最高档测试
                            resolveBuiltinHuibq("wy", urlId, LxMusicSourceEngine.BUILTIN_QUALITIES.first())
                        } else {
                            lxEngine.resolveMusicUrl(source.url, "wy", urlId, "320k")
                        }
                    }.getOrElse { e -> if (e is CancellationException) throw e else null }
                }
                testMsg = if (url.isNullOrBlank()) "解析失败"
                else "解析成功：\n${url.take(80)}"
            } finally {
                // 只有"我仍是当前测试"时才落 testing：旧任务的 finally 不能误关新测试的弹窗；
                // 用户主动取消时 onDismissRequest 已直接把 testing 置 false 立刻关弹窗
                if (testJob === coroutineContext[Job]) testing = false
            }
        }
        testJob = job
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
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (source.builtin) {
                                Spacer(Modifier.width(6.dp))
                                // v0.109.0 A9：内置 chip 金底 16% 透在亮色主题下近白，金字难读，用明度自适应金
                                val goldChip = goldTitleColor()
                                Text(
                                    text = "内置",
                                    fontSize = 11.sp,
                                    color = goldChip,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(goldChip.copy(alpha = 0.16f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (source.url.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = source.url,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // v0.103.0：档次 chips——内置 huibq 用自报两档（v0.106.0：走引擎共享声明）；
                        // 自定义源取脚本 inited 自报的 qualitys；加载中"…", 失败/无数据"未知"
                        val labels: List<String>? = when {
                            source.builtin -> LxMusicSourceEngine.BUILTIN_QUALITIES.map(::qualityLabel)
                            !source.enabled -> null
                            else -> {
                                val ids = qualityOf[source.id]
                                when {
                                    ids == null -> listOf("…")
                                    ids.isEmpty() -> listOf("未知")
                                    else -> ids.sortedBy { qualityRank(it) }
                                        .map(::qualityLabel)
                                        .distinct()
                                }
                            }
                        }
                        if (!labels.isNullOrEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                labels.forEach { label ->
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                    .copy(alpha = 0.12f),
                                            )
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { doTest(source) }) {
                        Text("测试", fontSize = 13.sp)
                    }
                    if (!source.builtin) {
                        TextButton(onClick = { openCookie(source) }) {
                            Text("Cookie", fontSize = 13.sp)
                        }
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

    // v0.119.0：Cookie 配置弹窗精修——状态概览卡 + 平台玻璃卡 + 剪贴板快捷操作
    val cookieSrc = cookieTarget
    if (cookieSrc != null) {
        TechDialog(
            onDismissRequest = { cookieTarget = null },
            title = "Cookie 配置",
            subtitle = cookieSrc.name,
            confirmText = if (cookieSlots.isNotEmpty()) "保存" else null,
            onConfirm = if (cookieSlots.isNotEmpty()) ::saveCookie else null,
            dismissText = "关闭",
        ) {
            when {
                cookieLoading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                cookieText == null -> {
                    Text(
                        text = "脚本读取失败，无法配置",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                cookieSlots.isEmpty() -> {
                    Text(
                        text = "该脚本未声明 Cookie 配置项（头部无 @xxx_cookie 注释）",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    val filled = cookieSlots.count { cookieValues[it.key].orEmpty().isNotBlank() }
                    CookieStatusCard(filled = filled, total = cookieSlots.size)
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        cookieSlots.forEach { slot ->
                            CookieSlotCard(
                                slot = slot,
                                value = cookieValues[slot.key].orEmpty(),
                                onValueChange = { cookieValues[slot.key] = it },
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        Text(
                            text = "Cookie 仅写入本机脚本文件，不会上传到任何服务器。",
                            fontSize = 11.sp,
                            color = Color(0xFF8A93B2),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "获取方式：电脑浏览器登录对应平台官网 → F12 → 网络 → 复制请求头里的 Cookie。",
                            fontSize = 11.sp,
                            color = Color(0xFF8A93B2),
                        )
                    }
                }
            }
        }
    }

    // 测试中 / 测试结果
    if (testing || testMsg != null) {
        TechDialog(
            // v0.109.0 A6：testing 期间关弹窗 = 取消测试协程并清状态，不再锁死；
            // 注意 resolveMusicUrl 内部是 QuickJS 同步调用，cancel 只保证弹窗可关、
            // 过期结果不再回填，JS 线程上的阻塞调用要等它自己返回/15s 超时
            onDismissRequest = {
                if (testing) {
                    // v0.109.0 A6：testing 期间允许取消——立刻关弹窗（testing=false），
                    // 后台的测试协程 cancel 掉；其 finally 有 Job 身份守卫，不会误关后续测试
                    testJob?.cancel()
                    testJob = null
                    testing = false
                } else {
                    testMsg = null
                }
            },
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * v0.103.0：音质 id → 档次中文标签；未知 id 直接显示原文。
 * 有自带的用自带的——优先用脚本 inited 自报的 qualitys id 进这个映射。
 */
private fun qualityLabel(id: String): String = when (id) {
    "128k" -> "流畅"
    "192k" -> "标准"
    "320k" -> "高清"
    "flac" -> "无损"
    "flac24bit", "hires", "atmos", "atmos_plus", "master" -> "超高清"
    else -> id
}

/** 档次排序权重（v0.106.0：收敛到 LxMusicSourceEngine 单一口径，防两处打架） */
private fun qualityRank(id: String): Int = LxMusicSourceEngine.qualityRank(id)

/** v0.119.0：Cookie 弹窗玻璃卡片通用底 */
private val cookieCardModifier: Modifier
    get() = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(Color.White.copy(alpha = 0.05f))
        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
        .padding(12.dp)

/** v0.119.0：Cookie 配置状态概览卡——已配 x / n + 作用说明 */
@Composable
private fun CookieStatusCard(filled: Int, total: Int) {
    val done = total > 0 && filled == total
    val accent = if (done) Color(0xFF4ADE80) else Color(0xFFD9C39A)
    Row(
        modifier = cookieCardModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(accent),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "已配置 $filled / $total",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (done) "各平台将以登录态解析，可解锁会员与无损音质"
                else "粘贴各平台登录后的 Cookie，解锁会员与无损音质",
                fontSize = 12.sp,
                color = Color(0xFF8A93B2),
            )
        }
    }
}

/** v0.119.0：已填写/未填写状态徽标 */
@Composable
private fun CookieStatusPill(filled: Boolean) {
    val c = if (filled) Color(0xFF4ADE80) else Color(0xFF8A93B2)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(c.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(c),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (filled) "已填写" else "未填写",
            fontSize = 11.sp,
            color = c,
        )
    }
}

/** v0.119.0：单个平台 Cookie 卡片——名称 + 状态徽标 + 作用说明 + 多行输入 + 剪贴板快捷操作 */
@Composable
private fun CookieSlotCard(
    slot: LxCookieKit.CookieSlot,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val filled = value.isNotBlank()
    Column(modifier = cookieCardModifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = slot.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Spacer(Modifier.weight(1f))
            CookieStatusPill(filled = filled)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = cookieDesc(slot.key),
            fontSize = 12.sp,
            color = Color(0xFF8A93B2),
        )
        Spacer(Modifier.height(8.dp))
        TechTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "粘贴 ${slot.label} 的 Cookie",
            singleLine = false,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (filled) {
                TextButton(onClick = { onValueChange("") }) {
                    Text("清空", fontSize = 12.sp)
                }
            }
            TextButton(
                onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    val text = cm.primaryClip?.getItemAt(0)
                        ?.coerceToText(context)?.toString().orEmpty()
                    if (text.isNotBlank()) {
                        onValueChange(text)
                    } else {
                        Toast.makeText(context, "剪贴板是空的", Toast.LENGTH_SHORT).show()
                    }
                },
            ) {
                Text("从剪贴板粘贴", fontSize = 12.sp)
            }
        }
    }
}

/** v0.119.0：各平台 Cookie 作用说明 */
private fun cookieDesc(key: String): String = when (key) {
    "tx", "qq" -> "QQ 音乐登录态：解锁会员曲目、无损与母带音质"
    "wy", "wyy", "netease" -> "网易云音乐登录态：解锁 VIP 曲目完整版、无损音质"
    "kw" -> "酷我音乐登录态：提升解析成功率与音质"
    "kg" -> "酷狗音乐登录态：提升解析成功率与音质"
    "mg" -> "咪咕音乐登录态：提升解析成功率与音质"
    else -> "该平台登录态 Cookie"
}
