package com.movieshell.data.repo

import android.content.Context
import com.movieshell.BuildConfig
import com.movieshell.data.repo.PluginStore.InstalledPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * 插件市场客户端 + 安装器（v0.23.0，单入口；市场协议兼容 DsPlayer 官方市场）。
 *
 * - fetch(url)：拉 market.json → MarketInfo（含市场名/logo/插件列表）。
 * - install(context, plugin)：下载 → MD5 强制校验 → 包内 plugin.json（如有）与条目做
 *   id/version 一致性校验 → 按 type 分流：
 *   source → py/js/php 规则目录（engine 缺省时按包内目录嗅探，兼容 DsPlayer 源包）；
 *   server → pluginsDir/<id>/ 整包解压；
 *   live（裸 JSON，非 zip）→ liveDir/<id>.json（直播页 v0.24 做，先存好）。
 * - uninstall(context, id)：删文件 + 清记录；删掉 active 规则时清空防悬空。
 *
 * 所有失败都抛 IllegalStateException（中文原因），调用方负责 Toast + LogBus，不静默。
 */
object PluginMarket {

    private const val TAG = "PluginMarket"
    private const val UA = "XiaYing/0.23.0"

    /** 侠影可安装的 type；apk/import 等默认隐藏（设置里可开"显示不兼容插件"仅查看）。 */
    // v0.29.0：player=播放器引擎插件（libmpv 的 .so ZIP 包）
    // v0.30.0：subtitle=字幕搜索插件（DEX，经 DexClassLoader 动态加载）
    private val INSTALLABLE_TYPES = setOf("source", "server", "live", "env", "player", "subtitle")

    /** v0.27.2：一键安装三段进度（下载→校验→安装），供安装进度条 UI 用。 */
    enum class InstallStage { Downloading, Verifying, Installing }

    fun interface InstallProgress {
        /** stage 当前阶段；done/total 仅下载阶段有意义（字节数，total 可能为 -1）。 */
        fun onStage(stage: InstallStage, done: Long, total: Long)
    }

    data class MarketInfo(
        val name: String,
        val logo: String,
        val desc: String,
        val updatedAt: String,
        val plugins: List<MarketPlugin>,
    )

    data class MarketPlugin(
        val id: String,
        val name: String,
        val version: String,
        val type: String,
        val engine: String,
        val md5: String,
        val url: String,
        val desc: String,
        val author: String,
        val size: Long,
        /** 图标绝对地址（market.json 里相对路径已按市场地址解析）。 */
        val iconUrl: String,
        val tags: List<String>,
        val changelog: String,
        val platforms: List<String>,
        /** env=环境 / app=应用（DsPlayer 词表）。 */
        val category: String,
        val minApp: String,
        val abis: List<String>,
    ) {
        /** 是否可在本机安装：type 支持且 platforms 含 android（缺省视为支持）。 */
        fun isSupported(): Boolean =
            type in INSTALLABLE_TYPES && (platforms.isEmpty() || "android" in platforms)

        /** minApp 高于当前版本则需升级 App 才能装。 */
        fun needsNewerApp(): Boolean =
            minApp.isNotBlank() && isNewer(minApp, BuildConfig.VERSION_NAME)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * v0.25.0：市场索引拉取专用客户端（索引小，15s 超时；下载走上面的 http 长超时）。
     * v0.45.0：对冲层另有 10 秒单镜像超时，这里的 30 秒 callTimeout 只做兜底。
     */
    private val marketHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var lastList: List<MarketPlugin> = emptyList()

    /** 详情页用：从最近一次拉取结果里按 id 取。 */
    fun cached(id: String): MarketPlugin? = lastList.firstOrNull { it.id == id }

    /** 拉取市场（v0.45.0：多镜像对冲重写；无磁盘缓存，引导页等轻量调用方用）。 */
    suspend fun fetch(url: String): MarketInfo = withContext(Dispatchers.IO) {
        val (body, effectiveUrl) = fetchBodyHedged(url)
        parseMarket(effectiveUrl, body).also { lastList = it.plugins }
    }

    /**
     * v0.45.0：读磁盘缓存（filesDir/market_cache/<url md5>.json），损坏/缺失返回 null。
     * 市场页进场先调这个秒开，再后台 fetchRemote 刷新。
     */
    fun loadCached(context: Context, url: String): MarketInfo? = runCatching {
        val f = cacheFile(context, url)
        if (!f.exists()) return null
        val text = f.readText()
        val nl = text.indexOf('\n')
        if (nl < 0) return null
        parseMarket(text.substring(0, nl), text.substring(nl + 1))
            .also { lastList = it.plugins }
    }.getOrNull()

    /** v0.45.0：拉远端（多镜像对冲）并写磁盘缓存，供市场页后台刷新用。 */
    suspend fun fetchRemote(context: Context, url: String): MarketInfo = withContext(Dispatchers.IO) {
        val (body, effectiveUrl) = fetchBodyHedged(url)
        runCatching {
            val f = cacheFile(context, url)
            f.parentFile?.mkdirs()
            f.writeText("$effectiveUrl\n$body")
        }.onFailure { LogBus.w(TAG, "市场缓存写入失败：${it.message}") }
        parseMarket(effectiveUrl, body).also { lastList = it.plugins }
    }

    /** market.json 的 JSON 解析（纯函数，缓存/远端共用）。 */
    private fun parseMarket(effectiveUrl: String, body: String): MarketInfo {
        val root = runCatching { JSONObject(body) }
            .getOrElse { throw IllegalStateException("market.json 不是合法 JSON") }
        val arr = runCatching { root.getJSONArray("plugins") }
            .getOrElse { throw IllegalStateException("market.json 格式错误：缺 plugins 数组") }
        // v0.25.0：相对 URL 按实际成功的地址拼（主备域名整套切换）
        val base = effectiveUrl.substringBeforeLast("/") + "/"
        val plugins = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MarketPlugin(
                id = o.optString("id").also { if (it.isBlank()) throw IllegalStateException("插件条目缺 id") },
                name = o.optString("name", o.optString("id")),
                version = o.optString("version", ""),
                type = o.optString("type", ""),
                engine = o.optString("engine", ""),
                md5 = o.optString("md5"),
                url = o.optString("url").also {
                    if (it.isBlank()) throw IllegalStateException("插件 ${o.optString("id")} 缺 url")
                },
                desc = o.optString("desc", ""),
                author = o.optString("author", ""),
                size = o.optLong("size", 0),
                iconUrl = resolveIcon(o.optString("icon", ""), base),
                tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                changelog = o.optString("changelog", ""),
                platforms = o.optJSONArray("platforms")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                category = o.optString("category", ""),
                minApp = o.optString("minApp", ""),
                abis = o.optJSONArray("abis")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            )
        }
        LogBus.i(TAG, "市场解析成功：${plugins.size} 个插件")
        return MarketInfo(
            name = root.optString("name", "插件市场"),
            logo = resolveIcon(root.optString("logo", ""), base),
            desc = root.optString("desc", ""),
            updatedAt = root.optString("updatedAt", ""),
            plugins = plugins,
        )
    }

    /**
     * v0.45.0：市场索引拉取重写——多镜像对冲。
     * 4 个镜像同时请求，谁先成功用谁（单个 10 秒超时）；HTTP 错误码视为该镜像失败、
     * 继续等其他镜像；全部失败才抛中文错。旧的"串行主备 + 每地址 30 秒"在弱网下
     * 最长转 2 分钟才报错，是"偶尔加载不出来"的病根之一。
     */
    private suspend fun fetchBodyHedged(url: String): Pair<String, String> = coroutineScope {
        val urls = mirrorUrls(url)
        val ch = Channel<Result<Pair<String, String>>>(Channel.UNLIMITED)
        val jobs = urls.map { u ->
            launch(Dispatchers.IO) {
                ch.send(runCatching { withTimeout(10_000) { getBody(u) } to u })
            }
        }
        var lastErr: Throwable? = null
        repeat(urls.size) {
            val r = ch.receive()
            if (r.isSuccess) {
                val (body, won) = r.getOrThrow()
                if (won != urls[0]) LogBus.i(TAG, "市场拉取经镜像成功：$won")
                jobs.forEach { it.cancel() }
                return@coroutineScope body to won
            }
            lastErr = r.exceptionOrNull()
            LogBus.w(TAG, "市场镜像失败：${r.exceptionOrNull()?.message}")
        }
        jobs.forEach { it.cancel() }
        throw IllegalStateException(
            "市场拉取失败：${urls.size} 个镜像都连不上（${lastErr?.message}），请检查网络后重试",
        )
    }

    private fun getBody(u: String): String {
        val req = Request.Builder().url(u).header("User-Agent", UA).build()
        marketHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("返回为空")
        }
    }

    /**
     * v0.45.0：镜像展开。官方 raw 地址展开 4 镜像
     * （raw / jsdelivr / ghfast / gh-proxy，国内镜像在后两位）；
     * 用户自填的第三方市场地址只用它自己，不乱拼镜像。
     */
    private fun mirrorUrls(url: String): List<String> {
        val m = Regex("https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)")
            .matchEntire(url) ?: return listOf(url)
        val (owner, repo, branch, path) = m.destructured
        val raw = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        return listOf(
            url,
            "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path",
            "https://ghfast.top/$raw",
            "https://gh-proxy.com/$raw",
        )
    }

    /** v0.45.0：磁盘缓存文件（首行是实际生效地址，余下是 market.json 原文）。 */
    private fun cacheFile(context: Context, url: String): File {
        val name = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "market_cache/$name.json")
    }

    private fun resolveIcon(icon: String, base: String): String = when {
        icon.isBlank() -> ""
        icon.startsWith("http://") || icon.startsWith("https://") -> icon
        else -> base + icon.removePrefix("/")
    }

    /** 一键安装（含更新：覆盖安装）。progress 为空则静默安装。 */
    suspend fun install(
        context: Context,
        plugin: MarketPlugin,
        progress: InstallProgress? = null,
    ) = withContext(Dispatchers.IO) {
        if (!plugin.isSupported()) throw IllegalStateException("插件类型 ${plugin.type} 侠影暂不支持")
        if (plugin.needsNewerApp()) throw IllegalStateException("该插件需要侠影 ${plugin.minApp} 及以上")
        LogBus.i(TAG, "开始安装：${plugin.id} v${plugin.version}")
        val tmp = File.createTempFile("plugin_", ".tmp", context.cacheDir)
        try {
            progress?.onStage(InstallStage.Downloading, 0, plugin.size)
            download(plugin.url, tmp, progress, plugin.size)
            progress?.onStage(InstallStage.Verifying, 0, 0)
            verifyMd5(tmp, plugin.md5, plugin.id)
            progress?.onStage(InstallStage.Installing, 0, 0)
            if (plugin.type == "live") installLive(context, plugin, tmp)
            else installZip(context, plugin, tmp)
            LogBus.i(TAG, "安装成功：${plugin.id} v${plugin.version}")
        } finally {
            tmp.delete()
        }
    }

    /** live：裸 JSON（非 zip），校验顶层 lives 数组后存 liveDir/<id>.json。 */
    private fun installLive(context: Context, plugin: MarketPlugin, file: File) {
        val o = runCatching { JSONObject(file.readText()) }
            .getOrElse { throw IllegalStateException("live 插件不是合法 JSON：${plugin.id}") }
        if (o.optJSONArray("lives") == null) throw IllegalStateException("live 插件缺 lives 数组：${plugin.id}")
        val dst = File(StoragePaths.liveDir(context), "${plugin.id}.json")
        file.copyTo(dst, overwrite = true)
        PluginStore.recordInstalled(
            context,
            InstalledPlugin(plugin.id, plugin.name, plugin.version, "live", "", listOf(dst.name)),
        )
    }

    /** zip 包：plugin.json 一致性校验 → 按 type 解压。 */
    private fun installZip(context: Context, plugin: MarketPlugin, zip: File) {
        val installed = mutableListOf<String>()
        var engine = plugin.engine
        ZipFile(zip).use { zf ->
            val entries = zf.entries().asSequence().filter { !it.isDirectory }.toList()
            if (entries.isEmpty()) throw IllegalStateException("插件包为空：${plugin.id}")
            checkManifest(zf, plugin)
            when (plugin.type) {
                "source" -> {
                    engine = detectEngine(plugin, entries.map { it.name })
                    val dir = ruleDir(context, engine)
                    val ext = when (engine) {
                        "py" -> ".py"; "js" -> ".js"; "php" -> ".php"
                        else -> throw IllegalStateException("不支持的源引擎：$engine")
                    }
                    entries.filter { it.name.endsWith(ext, ignoreCase = true) }.forEach { e ->
                        val name = File(e.name).name
                        zf.getInputStream(e).use { input ->
                            safeDst(dir, name).outputStream().use { input.copyTo(it) }
                        }
                        installed.add(name)
                    }
                    if (installed.isEmpty()) throw IllegalStateException("插件包里没有 $ext 规则文件")
                }
                "server" -> {
                    val root = File(StoragePaths.pluginsDir(context), plugin.id)
                    entries.forEach { e ->
                        val dst = safeDst(root, e.name)
                        dst.parentFile?.mkdirs()
                        zf.getInputStream(e).use { input -> dst.outputStream().use { input.copyTo(it) } }
                        installed.add(e.name)
                    }
                }
                /**
                 * v0.27.2：env=环境依赖包。包内 pylibs/ 下的内容解到私有 filesDir/pylibs/
                 * （必须私有：native .so 只能从私有目录 dlopen），ChaquopyEngine 已把该
                 * 目录加入 sys.path，规则 import 即用。卸载时按记录删除。
                 */
                "env" -> {
                    val dir = StoragePaths.pyLibsDir(context)
                    entries.filter { it.name.startsWith("pylibs/") }.forEach { e ->
                        val rel = e.name.removePrefix("pylibs/")
                        if (rel.isBlank()) return@forEach
                        val dst = safeDst(dir, rel)
                        dst.parentFile?.mkdirs()
                        zf.getInputStream(e).use { input -> dst.outputStream().use { input.copyTo(it) } }
                        installed.add(rel)
                    }
                    if (installed.isEmpty()) throw IllegalStateException("依赖包里没有 pylibs/ 内容：${plugin.id}")
                }
                /**
                 * v0.29.0：player=播放器引擎插件。包内 libs/ 下的 .so 解到私有
                 * filesDir/mpv-libs/（native 库必须走私有目录 dlopen），
                 * MpvPluginLoader.ensureLoaded 按序 System.load。
                 */
                "player" -> {
                    val dir = File(context.filesDir, "mpv-libs").also { it.mkdirs() }
                    entries.filter { it.name.startsWith("libs/") && it.name.endsWith(".so") }.forEach { e ->
                        val name = File(e.name).name
                        zf.getInputStream(e).use { input ->
                            safeDst(dir, name).outputStream().use { input.copyTo(it) }
                        }
                        installed.add(name)
                    }
                    if (installed.isEmpty()) throw IllegalStateException("播放器插件包里没有 libs 目录的 so 文件：${plugin.id}")
                }
                /**
                 * v0.30.0：subtitle=字幕搜索插件。包内 subtitle-plugin.dex 解到私有
                 * filesDir/subtitle-plugin/，SubtitlePluginLoader 经 DexClassLoader 加载。
                 */
                "subtitle" -> {
                    val dir = File(context.filesDir, "subtitle-plugin").also { it.mkdirs() }
                    entries.filter { it.name.endsWith(".dex") }.forEach { e ->
                        val name = "subtitle-plugin.dex"
                        zf.getInputStream(e).use { input ->
                            safeDst(dir, name).outputStream().use { input.copyTo(it) }
                        }
                        installed.add(name)
                    }
                    if (installed.isEmpty()) throw IllegalStateException("字幕插件包里没有 dex 文件：${plugin.id}")
                    com.movieshell.data.source.subtitle.SubtitlePluginLoader.reset()
                }
                else -> throw IllegalStateException("不支持的插件类型：${plugin.type}")
            }
        }
        PluginStore.recordInstalled(
            context,
            InstalledPlugin(plugin.id, plugin.name, plugin.version, plugin.type, engine, installed),
        )
    }

    /** 包内 plugin.json（如有）与市场条目做 id/version 一致性校验。 */
    private fun checkManifest(zf: ZipFile, plugin: MarketPlugin) {
        val e = zf.entries().asSequence().firstOrNull { it.name == "plugin.json" } ?: return
        val o = runCatching {
            JSONObject(zf.getInputStream(e).readBytes().toString(Charsets.UTF_8))
        }.getOrNull() ?: return
        val mid = o.optString("id")
        if (mid.isNotBlank() && mid != plugin.id) {
            throw IllegalStateException("包内 id($mid)与市场条目(${plugin.id})不一致，拒绝安装")
        }
        val mver = o.optString("version")
        if (mver.isNotBlank() && mver != plugin.version) {
            throw IllegalStateException("包内版本($mver)与市场条目(${plugin.version})不一致，拒绝安装")
        }
    }

    /** engine 缺省时按包内目录嗅探（兼容 DsPlayer 源包布局）。 */
    private fun detectEngine(plugin: MarketPlugin, names: List<String>): String {
        if (plugin.engine.isNotBlank()) return plugin.engine
        val joined = names.joinToString("\n")
        return when {
            joined.contains("hipy/") || names.any { it.endsWith(".py", true) } -> "py"
            names.any { it.endsWith(".js", true) } -> "js"
            names.any { it.endsWith(".php", true) } -> "php"
            else -> throw IllegalStateException("无法识别源引擎：${plugin.id}")
        }
    }

    /** 卸载：删文件 + 清记录 + 若命中 active 规则则清空。 */
    suspend fun uninstall(context: Context, id: String) = withContext(Dispatchers.IO) {
        val rec = PluginStore.installedOf(id)
            ?: throw IllegalStateException("未安装：$id")
        when (rec.type) {
            "server" -> File(StoragePaths.pluginsDir(context), id).deleteRecursively()
            "live" -> rec.files.forEach { File(StoragePaths.liveDir(context), it).delete() }
            "env" -> {
                val dir = StoragePaths.pyLibsDir(context)
                rec.files.forEach { File(dir, it).delete() }
                // 尽量清掉空目录（删不掉也无妨）
                dir.walkTopDown().filter { it.isDirectory && it != dir }
                    .sortedByDescending { it.absolutePath.length }
                    .forEach { runCatching { it.delete() } }
            }
            // v0.29.0：player 插件卸载删 .so（native 库进程内无法卸载，重启生效）
            "player" -> {
                val dir = File(context.filesDir, "mpv-libs")
                rec.files.forEach { File(dir, it).delete() }
            }
            // v0.30.0：subtitle 插件卸载删 DEX
            "subtitle" -> {
                val dir = File(context.filesDir, "subtitle-plugin")
                rec.files.forEach { File(dir, it).delete() }
                com.movieshell.data.source.subtitle.SubtitlePluginLoader.reset()
            }
            else -> {
                val dir = ruleDir(context, rec.engine)
                rec.files.forEach { File(dir, it).delete() }
                clearActiveIfGone(rec)
            }
        }
        PluginStore.removeInstalled(context, id)
        LogBus.i(TAG, "卸载成功：$id")
    }

    /** 远程版本是否新于本地版本（语义版本比对；minApp 门槛复用）。 */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".", "-"); val l = local.split(".", "-")
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrNull(i)?.toIntOrNull() ?: 0
            val b = l.getOrNull(i)?.toIntOrNull() ?: 0
            if (a != b) return a > b
        }
        return false
    }

    /** 人性化大小：1.2 MB / 340 KB。 */
    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%d KB".format(bytes / 1024)
        else -> "$bytes B"
    }

    // ---------------- 内部 ----------------

    private fun download(
        url: String,
        dst: File,
        progress: InstallProgress?,
        totalHint: Long,
    ) {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("下载失败：HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("下载内容为空")
            val total = totalHint.takeIf { it > 0 } ?: body.contentLength()
            var done = 0L
            body.byteStream().use { input ->
                dst.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        if (n == 0) continue
                        out.write(buf, 0, n)
                        done += n
                        progress?.onStage(InstallStage.Downloading, done, total)
                    }
                }
            }
        }
        if (dst.length() == 0L) throw IllegalStateException("下载内容为空")
    }

    private fun verifyMd5(file: File, expect: String, id: String) {
        if (expect.isBlank()) throw IllegalStateException("插件 $id 未提供 md5，拒绝安装")
        val actual = MessageDigest.getInstance("MD5").let { md ->
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } > 0) md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        if (!actual.equals(expect, ignoreCase = true)) {
            throw IllegalStateException("MD5 校验失败（插件 $id），文件可能被篡改，已拒绝安装")
        }
    }

    /** zip-slip 防护：解压目标必须落在 dir 内。 */
    private fun safeDst(dir: File, name: String): File {
        val dst = File(dir, name)
        if (!dst.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            throw IllegalStateException("插件包路径非法：$name")
        }
        return dst
    }

    private fun ruleDir(context: Context, engine: String): File = when (engine) {
        "py" -> StoragePaths.pyDir(context)
        "js" -> StoragePaths.jsDir(context)
        "php" -> StoragePaths.phpDir(context)
        else -> throw IllegalStateException("不支持的源引擎：$engine")
    }

    /** 卸载后若 active 规则文件已不存在，清空 active，避免悬空。 */
    private fun clearActiveIfGone(rec: InstalledPlugin) {
        if (rec.files.contains(PySourceManager.activeRuleName())) {
            LogBus.i(TAG, "active py 规则随插件卸载：${PySourceManager.activeRuleName()}")
            PySourceManager.setActiveRule(null)
        }
        if (rec.files.contains(DrpySourceManager.activeRuleName())) {
            LogBus.i(TAG, "active js 规则随插件卸载：${DrpySourceManager.activeRuleName()}")
            DrpySourceManager.setActiveRule(null)
        }
        if (rec.files.contains(PhpSourceManager.activeRuleName())) {
            LogBus.i(TAG, "active php 规则随插件卸载：${PhpSourceManager.activeRuleName()}")
            PhpSourceManager.setActiveRule(null)
        }
    }
}
