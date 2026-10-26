package com.movieshell.ui.music

import com.movieshell.ui.components.AppSwitch
import com.movieshell.ui.components.AppToast
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.movieshell.data.repo.MusicApi
import com.movieshell.data.repo.MusicSource
import com.movieshell.data.repo.AtomicFile
import com.movieshell.data.repo.MusicSourceStore
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.LxCookieKit
import com.movieshell.data.source.LxMusicSourceEngine
import com.movieshell.data.source.resolveBuiltinHuibq
import com.movieshell.player.MusicPlayer
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.TechTextField
import com.movieshell.ui.components.accentTitleColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
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
    // v0.103.0：档次 chips 数据表（value=null=加载中，空 list=失败/无数据），
    // 声明提前到 reload() 之前——reload 要 bump 下面的刷新计数触发重拉。
    val qualityOf = remember { mutableStateMapOf<String, List<String>?>() }
    // v0.217.0（F35）：档次刷新计数——reload 出的列表内容相等时 LaunchedEffect
    // 不会重跑，光清失败条目没人重拉；计数一变 effect 必重跑。
    var qualityRefresh by remember { mutableIntStateOf(0) }
    // v0.213.0：删源原来直接执行无确认，误删即丢脚本与 Cookie；
    // 改走歌单删除同款确认弹窗。
    var deleteSourceTarget by remember { mutableStateOf<MusicSource?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    // v0.213.0：测试弹窗标题原来写死“测试内置源解析”，自定义源也
    // 顶这个标题；记下被测源，标题按内置/自定义区分。
    var testSource by remember { mutableStateOf<MusicSource?>(null) }
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

    // v0.216.0（C4）：注册表损坏横幅——读坏后写盘已暂停，横幅给用户可见提示
    // 与“重新开始”入口（清掉损坏文件与备份，下次自动重建种子源）。
    // 注意：必须写在 reload() 之前，局部函数不提升，写在后面编译找不到。
    var showCorruptBanner by remember { mutableStateOf(false) }
    fun reload() {
        // v0.217.0（F35）：刷新必重拉档次——计数变化强制 effect 重跑，
        // effect 内先清失败／已删源条目再按“不在表里”重拉。
        qualityRefresh += 1
        scope.launch {
            sources = MusicSourceStore.validateLocalScripts(context)
            showCorruptBanner = MusicSourceStore.hasCorruptBackup(context)
        }
    }
    LaunchedEffect(Unit) { reload() }

    // v0.118.0：Cookie 配置——扫描脚本头部 @xxx_cookie 声明动态生成输入框；
    // 保存写回脚本头部，远端脚本先转存本地副本（file://）再改
    var cookieTarget by remember { mutableStateOf<MusicSource?>(null) }
    var cookieSlots by remember { mutableStateOf<List<LxCookieKit.CookieSlot>>(emptyList()) }
    val cookieValues = remember { mutableStateMapOf<String, String>() }
    var cookieText by remember { mutableStateOf<String?>(null) }
    var cookieLoading by remember { mutableStateOf(false) }
    // v0.123.0：读脚本/扫描失败原因——弹窗必须显示，不许静默空白（用户反馈点不开）
    var cookieError by remember { mutableStateOf<String?>(null) }
    // v0.123.0：内置源 Cookie 说明弹窗（内置行也要有 Cookie 入口，点开讲清免配置）
    var builtinCookieTip by remember { mutableStateOf<MusicSource?>(null) }

    /** v0.123.0：失败带原因抛出（原先吞成 null，弹窗只剩一句"读取失败"没下文）。 */
    fun readScriptText(url: String): String {
        if (url.startsWith("file://")) {
            val f = File(URI(url))
            if (!f.isFile) throw IllegalStateException("本地脚本文件不存在（可能已被清理）")
            return f.readText()
        }
        return CmsApi.defaultClient().newCall(Request.Builder().url(url).get().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("下载脚本失败：HTTP ${resp.code}")
                resp.body?.string()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("脚本内容为空")
            }
    }

    fun openCookie(source: MusicSource) {
        cookieTarget = source
        cookieSlots = emptyList()
        cookieValues.clear()
        cookieText = null
        cookieError = null
        cookieLoading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { readScriptText(source.url) }
            }
            cookieLoading = false
            result.onSuccess { text ->
                cookieText = text
                val slots = runCatching { LxCookieKit.scan(text) }
                    .onFailure { cookieError = "扫描脚本声明失败：${it.message}" }
                    .getOrDefault(emptyList())
                cookieSlots = slots
                slots.forEach { cookieValues[it.key] = it.current }
            }.onFailure {
                cookieText = null
                cookieError = it.message ?: "未知错误"
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
            // v0.217.0（F28）：远端分支的注册表成败必须参与 ok 判断——原来
            // add() 返回值根本没接，注册表写盘失败也报“已保存”。
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (src.url.startsWith("file://")) {
                        // v0.188.0：编辑既有脚本前先拷 .bak，写盘走 AtomicFile（L25）
                        val target = File(URI(src.url))
                        runCatching {
                            target.copyTo(File(target.parentFile, "${target.name}.bak"), overwrite = true)
                        }
                        if (!AtomicFile.writeText(target, newText)) throw IllegalStateException("脚本写盘失败")
                        true
                    } else {
                        val dir = File(context.filesDir, "lx_sources")
                        if (!dir.isDirectory) dir.mkdirs()
                        val dest = File(dir, "${UUID.randomUUID()}.js")
                        if (!AtomicFile.writeText(dest, newText)) throw IllegalStateException("脚本写盘失败")
                        MusicSourceStore.add(
                            context,
                            src.copy(url = "file://${dest.absolutePath}"),
                        )
                    }
                }.getOrDefault(false)
            }
            if (ok) {
                lxEngine.evictScript(src.url)
                // v0.217.0（F27）：播放器侧单例引擎也要失效该脚本——原来只清
                // 管理页这台引擎，当下播的歌还是旧脚本，换 Cookie 像没换。
                // 下次起播生效（在播的歌不受影响），提示里说准。
                MusicPlayer.evictLxScript(src.url)
                cookieTarget = null
                reload()
                AppToast.show(context, "Cookie 已保存，脚本已重载，下次起播生效", long = false)
            } else {
                // 失败不关弹窗、不丢用户刚填的内容，可改完再点一次保存。
                AppToast.show(context, "保存失败，Cookie 未写入", long = false)
            }
        }
    }

    // v0.103.0：档次 chips——进入管理页对启用的自定义源并发 loadScript
    //（引擎内已有 15s 超时，不另起超时），成功后读 inited 上报的 qualitys；
    // file:// 本地源同样走 loadScript。value=null=加载中，空 list=失败/无数据
    LaunchedEffect(sources, lxEngine, qualityRefresh) {
        // v0.217.0（F35）：失败态不再是终态——刷新后失败（空列表）条目先清掉，
        // 下面按“不在表里”重拉；已删源的条目一并清掉。加载中（null）的不动。
        qualityOf.keys
            .filter { id -> sources.none { it.id == id } || qualityOf[id]?.isEmpty() == true }
            .forEach { qualityOf.remove(it) }
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
                AppToast.show(context, "不是有效的洛雪音源脚本", long = false)
                return@launch
            }
            val tmp = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File.createTempFile("lx_import", ".js", context.cacheDir)
                    if (!AtomicFile.writeText(f, text)) throw IllegalStateException("脚本写盘失败")
                    f
                }.getOrNull()
            }
            if (tmp == null) {
                AppToast.show(context, "导入失败：无法读取文件", long = false)
                return@launch
            }
            val displayName = uri.lastPathSegment?.takeIf { it.isNotBlank() }
                ?.removeSuffix(".js") ?: "本地音源"
            val added = MusicSourceStore.addLocal(context, displayName, tmp)
            runCatching { tmp.delete() }
            if (added != null) {
                // v0.218.0（S18）：只在当前无首选时自动设首选——原来
                // 导入／添加就直接抢成首选，悄悄换掉用户已有的显式选择。
                if (MusicPlayer.preferredSourceIdFlow.value == null) {
                    MusicPlayer.setPreferredSource(context, added.id)
                    AppToast.show(context, "已导入 ${added.name}，已设为首选音源", long = false)
                } else {
                    AppToast.show(context, "已导入 ${added.name}，新源在自动顺序里", long = false)
                }
                showAdd = false
                reload()
            } else {
                AppToast.show(context, "导入失败", long = false)
            }
        }
    }

    fun doTest(source: MusicSource) {
        // v0.131.0：当前曲目本体顺手留住——测试解析与播放同口径透传 musicInfo 歌名/歌手/专辑
        val curTrack = MusicPlayer.state.value.track
        val urlId = curTrack?.urlId
        if (urlId.isNullOrBlank()) {
            AppToast.show(context, "先播放一首歌再测试", long = false)
            return
        }
        // v0.217.0（F31）：测试按当前曲目的平台走，不再写死 "wy"——
        // 原来播 QQ／酷狗的歌却用网易云 id 去测，结果不可信。
        val testPlatform = MusicApi.normalizeSource(curTrack?.source.orEmpty()).ifBlank { "wy" }
        testMsg = null
        testing = true
        testSource = source
        // v0.109.0 A6：testing 期间允许取消——关弹窗（返回键/点外部）即 cancel；
        // 旧 runCatching 会吞 CancellationException 导致取消后仍弹"解析失败"，这里显式重抛
        testJob?.cancel()
        val job = scope.launch {
            try {
                // v0.120.0：逐档串行试——内置按引擎自报档次（192k→128k），自定义按脚本
                // inited 自报音质降序（无自报兜底 320k→128k）；每档记 ✓/✗，取消时 break
                val (summary, firstUrl) = withContext(Dispatchers.IO) {
                    val qualities: List<String> = if (source.builtin) {
                        LxMusicSourceEngine.BUILTIN_QUALITIES
                    } else {
                        runCatching { lxEngine.loadScript(source.url) }
                            .getOrElse { e -> if (e is CancellationException) throw e else false }
                        // v0.124.0：与播放同口径按当前曲目平台取档，不拿别平台独占档空试
                        LxMusicSourceEngine.sortHighToLow(lxEngine.getQualityIds(source.url, testPlatform))
                            .ifEmpty { listOf("320k", "128k") }
                    }
                    val results = ArrayList<String>()
                    var first: String? = null
                    for (q in qualities) {
                        if (!isActive) break
                        val url = runCatching {
                            if (source.builtin) resolveBuiltinHuibq(testPlatform, urlId, q)
                            else lxEngine.resolveMusicUrl(
                                source.url, testPlatform, urlId, q,
                                name = curTrack?.name.orEmpty(),
                                singer = curTrack?.artist.orEmpty(),
                                albumName = curTrack?.album.orEmpty(),
                            )
                        }.getOrElse { e -> if (e is CancellationException) throw e else null }
                        val ok = !url.isNullOrBlank()
                        if (ok && first == null) first = url
                        results += "$q ${if (ok) "✓" else "✗"}"
                    }
                    results.joinToString(" / ") to first
                }
                // 取消中途退出时不回填结果（testing 由 finally 的身份守卫落，不会误开新弹窗）
                if (!isActive) return@launch
                testMsg = if (firstUrl.isNullOrBlank()) "$summary\n解析失败"
                else "$summary\n解析成功：\n${firstUrl.take(80)}"
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
            // v0.121.0：Cookie 引导条——用户找不到 Cookie 入口，这里先讲清（每行右侧按钮）
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "点每行右侧的「Cookie」按钮，可填各平台登录 Cookie，解锁会员 / 无损音质",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // v0.216.0（C4）：注册表损坏横幅
            if (showCorruptBanner) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "音源注册表损坏，已保留备份，写盘已暂停",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            scope.launch {
                                MusicSourceStore.clearCorrupt(context)
                                showCorruptBanner = false
                                reload()
                            }
                        }) { Text("重新开始") }
                        TextButton(onClick = { showCorruptBanner = false }) { Text("暂不处理") }
                    }
                }
            }
            // v0.213.0：无源时原来整页只剩引导条 + 悬浮钮，没有空态
            // 说明；补一句，告诉用户点右下角 + 添加。
            if (sources.isEmpty()) {
                item {
                    Text(
                        text = "还没有音乐源，点右下角 + 添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(sources, key = { it.id }) { source ->
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
                // v0.127.0：行重做为统一骨架卡片（内置/非内置同构，三行对齐成列）：
                // 一行 源名+内置徽标+开关；二行 地址小字+操作组右对齐；三行 音质 chips。
                // 测试/Cookie/开关/删除的逻辑与原先完全一致，只重排布局。
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = source.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (source.scriptMissing) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "已失效",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.16f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                            if (source.builtin) {
                                Spacer(Modifier.width(6.dp))
                                // v0.109.0 A9：内置 chip 青底 16% 透，文字用明度自适应青色
                                val accentChip = accentTitleColor()
                                Text(
                                    text = "内置",
                                    fontSize = 11.sp,
                                    color = accentChip,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(accentChip.copy(alpha = 0.16f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            AppSwitch(
                                checked = source.enabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        MusicSourceStore.setEnabled(context, source.id, enabled)
                                        // v0.213.0：禁用首选源后原来成悬空 ID；
                                        // 禁用即清首选，芯片与实际播放不再打架。
                                        if (!enabled) MusicPlayer.clearPreferredSourceIf(context, source.id)
                                        reload()
                                    }
                                },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (source.builtin) "内置音源" else source.url,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { doTest(source) }) {
                                Text("测试", fontSize = 13.sp)
                            }
                            // v0.123.0：内置行也给 Cookie 按钮（原先只有非内置行有，用户在
                            // huibq 行找不到入口直呼"没有 cookie 界面"）；内置点开是免配置说明
                            TextButton(onClick = {
                                if (source.builtin) {
                                    builtinCookieTip = source
                                } else {
                                    openCookie(source)
                                }
                            }) {
                                Text("Cookie", fontSize = 13.sp)
                            }
                            if (!source.builtin) {
                                IconButton(onClick = { deleteSourceTarget = source }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "删除音乐源",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        if (!labels.isNullOrEmpty()) {
                            Spacer(Modifier.height(8.dp))
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
                    AppToast.show(context, "名称和脚本地址不能为空", long = false)
                    return@TechDialog
                }
                scope.launch {
                    // v0.218.0（S18）：同本地导入——只在无首选时自动设首选。
                    val created = MusicSource(name = name.trim(), url = url.trim())
                    MusicSourceStore.add(context, created)
                    if (MusicPlayer.preferredSourceIdFlow.value == null) {
                        MusicPlayer.setPreferredSource(context, created.id)
                        AppToast.show(context, "已添加，已设为首选音源", long = false)
                    } else {
                        AppToast.show(context, "已添加，新源在自动顺序里", long = false)
                    }
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
            // v0.119.2：confirmText 不可空（传 null 编译错）；无配置项时按钮已由 onConfirm=null 隐藏
            confirmText = "保存",
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
                        text = "脚本读取失败：${cookieError ?: "未知原因"}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                cookieSlots.isEmpty() -> {
                    Column {
                        Text(
                            text = cookieError
                                ?: "该脚本未声明 Cookie 配置项（头部无 @xxx_cookie 注释）。" +
                                    "只有墨澜类带声明的脚本才能在这里填 Cookie。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // v0.123.0：0 声明时把脚本提到的平台列出来，不让用户对着空弹窗猜
                        val sniffed = cookieText?.let { LxCookieKit.sniffPlatforms(it) }
                            .orEmpty()
                        if (sniffed.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "脚本里提到的平台：${sniffed.joinToString("、")}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "获取方式：电脑浏览器登录对应平台官网 → F12 → 网络 → 复制请求头里的 Cookie。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    deleteSourceTarget?.let { src ->
        TechDialog(
            onDismissRequest = { deleteSourceTarget = null },
            title = "删除音乐源",
            subtitle = src.name,
            confirmText = "删除",
            onConfirm = {
                scope.launch {
                    val ok = MusicSourceStore.remove(context, src.id)
                    if (ok) {
                        MusicPlayer.clearPreferredSourceIf(context, src.id)
                    } else {
                        AppToast.show(context, "删除失败，音源未改动", long = false)
                    }
                    deleteSourceTarget = null
                    reload()
                }
            },
        ) {
            Text("确定删除音乐源「${src.name}」？本地脚本文件与已填的 Cookie 也会一起删掉。")
        }
    }

    // v0.123.0：内置源 Cookie 说明（免配置，讲清 Cookie 入口在脚本类音源那一行）
    builtinCookieTip?.let { src ->
        TechDialog(
            onDismissRequest = { builtinCookieTip = null },
            title = "Cookie 配置",
            subtitle = src.name,
            confirmText = "知道了",
            onConfirm = { builtinCookieTip = null },
            dismissText = null,
        ) {
            Text(
                text = "内置源是免费线路，免配置，不需要填 Cookie。\n" +
                    "需要 Cookie 的是洛雪脚本类音源（如墨澜聚合）：在它们那一行点「Cookie」，" +
                    "填各平台登录 Cookie 可解锁会员 / 无损音质。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            title = "测试${if (testSource?.builtin == true) "内置" else "自定义"}源解析",
            subtitle = testSource?.name,
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
internal fun qualityLabel(id: String): String = when (id) {
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
/** 修复：原 val getter 在非 @Composable 上下文读 MaterialTheme.colorScheme，编译不过，改成 @Composable 函数 */
@Composable
private fun cookieCardModifier(): Modifier = Modifier
    .fillMaxWidth()
    .clip(RoundedCornerShape(14.dp))
    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
    .padding(12.dp)

/** v0.119.0：Cookie 配置状态概览卡——已配 x / n + 作用说明 */
@Composable
private fun CookieStatusCard(filled: Int, total: Int) {
    val done = total > 0 && filled == total
    // 未配完是提醒态，用琥珀色（不再用旧品牌金）。
    val accent = if (done) Color(0xFF4ADE80) else Color(0xFFFCD34D)
    Row(
        modifier = cookieCardModifier(),
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
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (done) "各平台将以登录态解析，可解锁会员与无损音质"
                else "粘贴各平台登录后的 Cookie，解锁会员与无损音质",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** v0.119.0：已填写/未填写状态徽标 */
@Composable
private fun CookieStatusPill(filled: Boolean) {
    val c = if (filled) Color(0xFF4ADE80) else MaterialTheme.colorScheme.onSurfaceVariant
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
    // v0.213.0 音乐界面误导修复：Cookie 原来明文躺在输入框里；
    // 默认遮掩，点“显示”才明文，点“隐藏”恢复遮掩。
    var showCookie by remember { mutableStateOf(false) }
    Column(modifier = cookieCardModifier()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = slot.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            CookieStatusPill(filled = filled)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = cookieDesc(slot.key),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TechTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "粘贴 ${slot.label} 的 Cookie",
            singleLine = false,
            minLines = 2,
            visualTransformation = if (showCookie) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { showCookie = !showCookie }) {
                Text(if (showCookie) "隐藏" else "显示", fontSize = 12.sp)
            }
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
                        AppToast.show(context, "剪贴板是空的", long = false)
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
