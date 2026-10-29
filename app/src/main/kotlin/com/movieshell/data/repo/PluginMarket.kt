package com.movieshell.data.repo

import android.content.Context
import com.movieshell.BuildConfig
import com.movieshell.data.repo.PluginStore.InstalledPlugin
import com.movieshell.data.source.CmsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * 插件市场客户端 + 安装器（v0.23.0，单入口；市场协议兼容 DsPlayer 官方市场）。
 *
 * - fetch(url)：拉 market.json → MarketInfo（含市场名/logo/插件列表）。
 * - install(context, plugin)：下载 → MD5/SHA-256 辅助校验 → 包签名校验
 *   （信任锚，未签名或验签失败拒绝安装）→ 包内 plugin.json（如有）与条目做
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
    // v0.185.0：setup=一键配置插件（建目录 + 写说明文档，文档来自插件包、不进安装包）
    private val INSTALLABLE_TYPES = setOf("source", "server", "live", "env", "player", "subtitle", "runtime", "setup")

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
        /** 市场索引签名（Base64，签法见 PluginSignature），空白表示旧市场未签名。 */
        val signature: String = "",
    )

    data class MarketPlugin(
        val id: String,
        val name: String,
        val version: String,
        val type: String,
        val engine: String,
        val md5: String,
        /** 辅助校验用 SHA-256，不是信任锚；信任锚是下面的 signature。 */
        val sha256: String = "",
        /** 插件包签名（Base64，对包文件原始字节签名，签法见 PluginSignature）。 */
        val signature: String = "",
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
        val sourceMarket: String = "",
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
     * v0.45.0：拉取层另有 10 秒单镜像超时，这里的 30 秒 callTimeout 只做兜底。
     * v0.112.0 B1：索引拉取并入进程级共享单例。
     */
    private val marketHttp = CmsApi.defaultClient()

    @Volatile
    // v0.218.0（S25）：多市场并发拉取时 lastList 赋值无锁——原来
    // rememberPlugins 加了 @Synchronized，lastList 三处赋值没加，详情页
    // 兜底可能读到别家市场的旧列表；Volatile 保证跨线程可见性。
    private var lastList: List<MarketPlugin> = emptyList()

    /** v0.79.0：进程级最近一次完整市场信息（按请求 URL key），供市场页首帧秒开（内存→磁盘→网络三级）。 */
    private val lastInfoMap = mutableMapOf<String, MarketInfo>()

    @Synchronized
    private fun rememberInfo(url: String, info: MarketInfo) {
        lastInfoMap[url] = info
    }

    /** 详情页用：按（市场，id）累积最近见过的插件。
     * v0.246.0：原来按 id 单键，多市场同 id 会互相覆盖——
     * 用户在 A 市场点某插件，详情可能显示后拉的 B 市场同 id 版本。
     * 改复合键保留各市场记录；remember 时先删后放，保持插入序 = 最近序。 */
    private val lastById = mutableMapOf<String, MarketPlugin>()

    @Synchronized
    private fun rememberPlugins(list: List<MarketPlugin>) {
        list.forEach { p ->
            val key = "${p.sourceMarket}\u0000${p.id}"
            val old = lastById[key]
            lastById.remove(key)
            lastById[key] = if (p.sourceMarket.isBlank() && !old?.sourceMarket.isNullOrBlank()) {
                p.copy(sourceMarket = old!!.sourceMarket)
            } else {
                p
            }
        }
    }

    /** 给插件打来源市场名：优先注册表里的名称，未注册则用市场自报名称。 */
    private fun stamp(url: String, info: MarketInfo): MarketInfo =
        info.copy(plugins = info.plugins.map { it.copy(sourceMarket = PluginStore.nameOfSource(url) ?: info.name) })

    /** 详情页用：先查当前显示的市场列表（用户刚从这个列表点进来，最贴合），
     * 再查按（市场，id）累积的记录（多市场同 id 时取最近见过的）。
     * v0.246.0：原来只查单键 lastById，多市场同 id 互相覆盖。
     * 加 @Synchronized：rememberPlugins 写 lastById 时持同一把锁，查时不加锁
     * 遍历可能撞 ConcurrentModificationException。 */
    @Synchronized
    fun cached(id: String): MarketPlugin? =
        lastList.firstOrNull { it.id == id }
            ?: lastById.entries.lastOrNull { it.key.substringAfter('\u0000') == id }?.value

    /** v0.246.0：详情页带市场时的精确查——只认该市场，查不到返回 null。
     * v0.246.0 四轮（GPT 审）：原来查不到回落 cached(id)，而 cached(id) 优先查
     * 全局 lastList，目标市场记录缺失时可能展示另一市场的同 id 插件；
     * 改精确查不到就返回 null，详情页走已有空态，不展示可能错误的插件。
     * 市场为空时（老入口）才走旧的 cached(id) 兜底。 */
    @Synchronized
    fun cached(id: String, market: String): MarketPlugin? {
        if (market.isBlank()) return cached(id)
        return lastById["$market\u0000$id"]
    }

    /** 添加/编辑市场保存前试拉校验：内部即 fetch（拉取+解析+plugins 数组校验），成功才允许保存。 */
    suspend fun validateMarket(url: String): MarketInfo = fetch(url)

    fun deleteCache(context: Context, url: String) {
        runCatching { cacheFile(context, url).delete() }
            .onFailure { LogBus.w(TAG, "市场缓存删除失败：${it.message}") }
    }

    fun cacheUpdatedAt(context: Context, url: String): Long? {
        val f = cacheFile(context, url)
        return if (f.exists()) f.lastModified() else null
    }

    /** 拉取市场（v0.123.0：多镜像串行首成即停；无磁盘缓存，引导页等轻量调用方用）。
     * S1 过渡态：镜像只作传输，拉到索引后验索引签名；签名无效直接抛错，
     * 不展示可能被镜像篡改过的插件列表；未签名或公钥未配置只警告不拦截。 */
    suspend fun fetch(url: String): MarketInfo = withContext(Dispatchers.IO) {
        val (body, effectiveUrl) = fetchBodySequential(url)
        val info = stamp(url, parseMarket(effectiveUrl, body))
        marketSignatureWarning(info)?.let { LogBus.w(TAG, it) }
        info.also {
            lastList = it.plugins
            rememberPlugins(it.plugins)
        }
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
        val info = stamp(url, parseMarket(text.substring(0, nl), text.substring(nl + 1)))
        // 缓存里的旧索引：签名无效按无缓存处理，未签名或公钥未配置只警告不拦截。
        if (PluginSignature.checkMarketSignature(info.plugins, info.signature) ==
            PluginSignature.SignatureCheck.INVALID
        ) {
            return null
        }
        marketSignatureWarning(info)?.let { LogBus.w(TAG, it) }
        info
            .also { lastList = it.plugins }
            .also { rememberPlugins(it.plugins) }
            .also { rememberInfo(url, it) }
    }.getOrNull()

    /** v0.45.0：拉远端并写磁盘缓存，供市场页后台刷新用（拉取口径见 fetchBodySequential）。 */
    suspend fun fetchRemote(context: Context, url: String): MarketInfo = withContext(Dispatchers.IO) {
        val (body, effectiveUrl) = fetchBodySequential(url)
        runCatching {
            val f = cacheFile(context, url)
            f.parentFile?.mkdirs()
            f.writeText("$effectiveUrl\n$body")
        }.onFailure { LogBus.w(TAG, "市场缓存写入失败：${it.message}") }
        val info = stamp(url, parseMarket(effectiveUrl, body))
        marketSignatureWarning(info)?.let { LogBus.w(TAG, it) }
        info
            .also { lastList = it.plugins }
            .also { rememberPlugins(it.plugins) }
            .also { rememberInfo(url, it) }
    }

    /** S1 过渡态：索引签名检查的唯一入口。签名无效抛错拦截；
     * 未签名或公钥未配置返回警告文案，调用方记日志后继续展示。 */
    private fun marketSignatureWarning(info: MarketInfo): String? {
        return when (PluginSignature.checkMarketSignature(info.plugins, info.signature)) {
            PluginSignature.SignatureCheck.VERIFIED -> null
            PluginSignature.SignatureCheck.UNSIGNED ->
                "该市场索引未签名，展示内容未经签名校验"
            PluginSignature.SignatureCheck.UNCONFIGURED ->
                "插件市场签名公钥未配置，展示内容未经签名校验"
            PluginSignature.SignatureCheck.INVALID ->
                throw IllegalStateException("市场索引签名校验失败，索引可能被篡改，已拒绝展示")
        }
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
                sha256 = o.optString("sha256"),
                signature = o.optString("signature"),
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
            signature = root.optString("signature"),
        )
    }

    /**
     * v0.123.0：市场索引拉取——多镜像串行尝试，第一个成功立即返回，后面的不再请求。
     * （v0.45.0 曾做并发对冲：真机日志显示 4 个镜像全被请求、全都返回 200 才出结果，
     * 浪费流量且拖慢；串行首成即停，单个镜像 10 秒超时，弱网最长多等一个镜像的时间。）
     */
    private suspend fun fetchBodySequential(url: String): Pair<String, String> {
        val proxied = PluginStore.applyGithubProxy(url)
        val urls = (if (proxied != url) listOf(proxied) else emptyList()) + mirrorUrls(url)
        var lastErr: Throwable? = null
        for (u in urls) {
            try {
                val body = withTimeout(10_000) { getBody(u) }
                if (u != urls[0]) LogBus.i(TAG, "市场拉取经镜像成功：$u")
                return body to u
            } catch (e: TimeoutCancellationException) {
                // 单镜像超时 = 该镜像失败，继续下一个（它是 CancellationException
                // 子类，必须排在透传分支前面拦下，否则首个镜像慢就整单失败）
                lastErr = e
                LogBus.w(TAG, "市场镜像超时：$u")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErr = e
                LogBus.w(TAG, "市场镜像失败：$u ${e.message}")
            }
        }
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
     * S1：镜像只作传输，不作信任——索引与包都以签名为准，
     * 镜像改了内容会在验签那一步被拦下。
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

    /**
     * 一键安装（含更新：覆盖安装）。progress 为空则静默安装。
     * v0.96.1：返回安装后校验告警（无则 null）；live 插件装完自动刷新直播聚合。
     */
    suspend fun install(
        context: Context,
        plugin: MarketPlugin,
        progress: InstallProgress? = null,
    ): String? = withContext(Dispatchers.IO) {
        if (!plugin.isSupported()) throw IllegalStateException("插件类型 ${plugin.type} 侠影暂不支持")
        if (plugin.needsNewerApp()) throw IllegalStateException("该插件需要侠影 ${plugin.minApp} 及以上")
        LogBus.i(TAG, "开始安装：${plugin.id} v${plugin.version}")
        // v0.96.1：记下旧版本号，供 live 备份命名
        val oldVersion = PluginStore.installedOf(plugin.id)?.version
        val tmp = File.createTempFile("plugin_", ".tmp", context.cacheDir)
        var warn: String? = null
        try {
            progress?.onStage(InstallStage.Downloading, 0, plugin.size)
            val effectiveUrl = PluginStore.applyGithubProxy(plugin.url)
            download(effectiveUrl, tmp, progress, plugin.size)
            progress?.onStage(InstallStage.Verifying, 0, 0)
            // S1 过渡态：md5 只作辅助校验，信任锚是包签名；签名无效
            // 拒绝安装，未签名或公钥未配置只警告（warn 返回给调用方弹提示）。
            verifyMd5(tmp, plugin.md5, plugin.id)
            if (plugin.sha256.isNotBlank()) {
                val actualSha = ExecutableTrust.sha256(tmp)
                if (actualSha == null || !actualSha.equals(plugin.sha256, ignoreCase = true)) {
                    throw IllegalStateException("SHA-256 校验失败（插件 ${plugin.id}），文件可能被篡改，已拒绝安装")
                }
            }
            val signatureWarn = when (PluginSignature.checkPackageSignature(tmp, plugin.signature)) {
                PluginSignature.SignatureCheck.VERIFIED -> null
                PluginSignature.SignatureCheck.UNSIGNED ->
                    "插件 ${plugin.id} 未提供签名，安装内容未经签名校验"
                PluginSignature.SignatureCheck.UNCONFIGURED ->
                    "插件市场签名公钥未配置，插件 ${plugin.id} 未经签名校验"
                PluginSignature.SignatureCheck.INVALID ->
                    throw IllegalStateException("插件 ${plugin.id} 签名校验失败，文件可能被篡改，已拒绝安装")
            }
            // S3：插件包是 native/DEX/可执行代码，明文下载时过渡态的警告不够——
            // 中间人能同时换包和 md5，只有验过签名（签名防篡改，与传输无关）才放行；
            // 未签名或公钥未配置的明文包一律拒绝，https 包才走上面的警告口径。
            if (effectiveUrl.startsWith("http://", ignoreCase = true) &&
                PluginSignature.checkPackageSignature(tmp, plugin.signature) !=
                PluginSignature.SignatureCheck.VERIFIED
            ) {
                throw IllegalStateException(
                    "插件 ${plugin.id} 明文下载且未验过签名，已拒绝安装：请改用 https 市场地址或等市场签名发布",
                )
            }
            if (signatureWarn != null) {
                LogBus.w(TAG, signatureWarn)
                warn = signatureWarn
            }
            progress?.onStage(InstallStage.Installing, 0, 0)
            val installWarn = if (plugin.type == "live") {
                installLive(context, plugin, tmp, oldVersion).also {
                    // v0.96.1：live 插件装完自动刷新聚合，不然还播内存里的旧频道
                    runCatching { LiveStore.refresh(context) }
                        .onFailure { LogBus.w(TAG, "安装后刷新直播聚合失败：${it.message}") }
                }
            } else {
                installZip(context, plugin, tmp)
            }
            // 签名警告与安装告警都返回，调用方一起弹，不互相覆盖。
            warn = listOfNotNull(signatureWarn, installWarn)
                .joinToString("；").takeIf { it.isNotBlank() }
            LogBus.i(TAG, "安装成功：${plugin.id} v${plugin.version}")
        } finally {
            tmp.delete()
        }
        warn
    }

    /**
     * live：裸 JSON（非 zip），校验顶层 lives 数组后存 liveDir/<id>.json。
     * v0.96.1：覆盖前备份旧文件（<id>.json.bak-<旧版本号>，只留最近 3 个）；
     * 返回频道数校验告警（无则 null）。
     */
    private fun installLive(
        context: Context,
        plugin: MarketPlugin,
        file: File,
        oldVersion: String?,
    ): String? {
        val o = runCatching { JSONObject(file.readText()) }
            .getOrElse { throw IllegalStateException("live 插件不是合法 JSON：${plugin.id}") }
        val lives = o.optJSONArray("lives")
            ?: throw IllegalStateException("live 插件缺 lives 数组：${plugin.id}")
        val newCount = lives.length()
        val dir = StoragePaths.liveDir(context)
        val dst = File(dir, "${plugin.id}.json")
        var oldCount = -1
        if (dst.exists()) {
            oldCount = runCatching {
                JSONObject(dst.readText()).optJSONArray("lives")?.length() ?: -1
            }.getOrDefault(-1)
            val stamp = oldVersion?.takeIf { it.isNotBlank() }
                ?: System.currentTimeMillis().toString()
            val bak = File(dir, "${plugin.id}.json.bak-$stamp")
            runCatching { dst.copyTo(bak, overwrite = true) }
                .onSuccess { LogBus.i(TAG, "live 旧文件已备份：${bak.name}") }
                .onFailure { LogBus.w(TAG, "live 备份失败：${it.message}") }
            // 只保留最近 3 个备份（注意备份名不以 .json 结尾，不会被直播聚合误读）
            dir.listFiles { f -> f.isFile && f.name.startsWith("${plugin.id}.json.bak-") }
                .orEmpty().sortedByDescending { it.lastModified() }
                .drop(3).forEach { runCatching { it.delete() } }
        }
        file.copyTo(dst, overwrite = true)
        PluginStore.recordInstalled(
            context,
            InstalledPlugin(plugin.id, plugin.name, plugin.version, "live", "", listOf(dst.name)),
        )
        // 校验：新文件 0 频道，或比旧文件少一半以上 → 告警（toast 由调用方弹）
        return when {
            newCount == 0 -> "新版本频道数为 0，可去插件详情还原旧版"
            oldCount > 0 && newCount * 2 < oldCount ->
                "新版本频道数（$newCount）比旧版（$oldCount）少一半以上，可去插件详情还原旧版"
            else -> null
        }
    }

    /** zip 包：plugin.json 一致性校验 → 按 type 解压。返回 setup 的配置结果（其余 type 返回 null）。 */
    private fun installZip(context: Context, plugin: MarketPlugin, zip: File): String? {
        val installed = mutableListOf<String>()
        var engine = plugin.engine
        var setupSummary: String? = null
        val budget = ZipInstall.UnpackBudget()
        ZipFile(zip).use { zf ->
            val entries = zf.entries().asSequence().filter { !it.isDirectory }.toList()
            if (entries.isEmpty()) throw IllegalStateException("插件包为空：${plugin.id}")
            if (entries.size > ZipInstall.MAX_ZIP_ENTRIES) {
                throw IllegalStateException("插件包条目数超限（上限 ${ZipInstall.MAX_ZIP_ENTRIES}）：${plugin.id}")
            }
            checkManifest(zf, plugin)
            when (plugin.type) {
                "source" -> {
                    engine = detectEngine(plugin, entries.map { it.name })
                    val dir = ruleDir(context, engine)
                    val ext = when (engine) {
                        "py" -> ".py"; "js" -> ".js"; "php" -> ".php"
                        else -> throw IllegalStateException("不支持的源引擎：$engine")
                    }
                    // v0.186.0：先解暂存再整批换入（H13）。规则目录是共享目录，
                    // 不能像 server 那样整目录改名替换，故按本插件的文件集合换入：
                    // 暂存里校验通过后，先把旧版同插件文件备份、再把暂存文件搬入，
                    // 换入失败时从备份还原；旧版文件集合以注册表记录为准，换版删掉
                    // 的规则随换入一并删除，不再残留。校验失败时目标目录未动。
                    val ruleEntries = entries.filter { it.name.endsWith(ext, ignoreCase = true) }
                    if (ruleEntries.isEmpty()) throw IllegalStateException("插件包里没有 $ext 规则文件")
                    val staging = ZipInstall.stagingDir(dir)
                    staging.deleteRecursively()
                    staging.mkdirs()
                    try {
                        ruleEntries.forEach { e ->
                            val name = File(e.name).name
                            ZipInstall.copyEntryBounded(zf, e, ZipInstall.safeDst(staging, name), budget)
                            installed.add(name)
                        }
                        val oldFiles = PluginStore.installedOf(plugin.id)
                            ?.takeIf { it.type == "source" }?.files.orEmpty()
                        swapFileSet(dir, staging, oldFiles, installed.toList())
                    } catch (e: Exception) {
                        runCatching { staging.deleteRecursively() }
                        throw e
                    }
                }
                "server" -> {
                    val root = File(StoragePaths.pluginsDir(context), plugin.id)
                    // v0.171.0：先解到同目录暂存目录，全部解完才替换旧目录；
                    // 解压中途失败时旧目录原样保留，注册表也不更新，不再出现
                    // "旧版已删、新版残缺、注册表仍显示旧版"的不一致态
                    val staging = ZipInstall.stagingDir(root)
                    staging.deleteRecursively()
                    staging.mkdirs()
                    try {
                        entries.forEach { e ->
                            val dst = ZipInstall.safeDst(staging, e.name)
                            dst.parentFile?.mkdirs()
                            ZipInstall.copyEntryBounded(zf, e, dst, budget)
                            installed.add(e.name)
                        }
                        ZipInstall.swapDir(root, staging)
                        // v0.192.0：落盘后写 .nomedia，插件包里带媒体味后缀的
                        // 文件（如 node_modules 的 .ts）不再被相册收成假视频。
                        StoragePaths.ensureNomedia(root)
                    } catch (e: Exception) {
                        runCatching { staging.deleteRecursively() }
                        throw e
                    }
                }
                /**
                 * v0.137.0：runtime=运行时插件（如 php）。包内文件原样解到私有
                 * runtime/<id>/（native 载荷只能从私有目录执行）。
                 * v0.139.0：先清旧目录再解，避免旧版残留（如动态版 lib 目录的 so 文件近 80MB）；
                 * 静态单文件直接运行，动态文件走 ServicePluginManager 的 linker64 回退。
                 * v0.171.0：改先解暂存目录再整体替换，失败时旧版保留（见 server 分支注释）。
                 */
                "runtime" -> {
                    val root = File(StoragePaths.runtimeDir(context), plugin.id)
                    // v0.171.0：同 server 分支，先解暂存目录再整体替换旧目录，
                    // 失败时旧版保留、注册表不更新（此前先清旧目录再解，失败即丢旧版）
                    val staging = ZipInstall.stagingDir(root)
                    staging.deleteRecursively()
                    staging.mkdirs()
                    try {
                        entries.forEach { e ->
                            val dst = ZipInstall.safeDst(staging, e.name)
                            dst.parentFile?.mkdirs()
                            ZipInstall.copyEntryBounded(zf, e, dst, budget)
                            installed.add(e.name)
                        }
                        ZipInstall.swapDir(root, staging)
                    } catch (e: Exception) {
                        runCatching { staging.deleteRecursively() }
                        throw e
                    }
                }
                /**
                 * v0.27.2：env=环境依赖包。包内 pylibs/ 下的内容解到私有 filesDir/pylibs/
                 * （必须私有：native .so 只能从私有目录 dlopen），ChaquopyEngine 已把该
                 * 目录加入 sys.path，规则 import 即用。卸载时按记录删除。
                 */
                "env" -> {
                    val dir = StoragePaths.pyLibsDir(context)
                    // v0.186.0：与 source 分支同口径，先解暂存再按本插件文件集合换入
                    // （pylibs 是共享目录，不能整目录替换；换入失败时备份还原）。
                    val envEntries = entries.filter { it.name.startsWith("pylibs/") }
                        .filter { it.name.removePrefix("pylibs/").isNotBlank() }
                    if (envEntries.isEmpty()) throw IllegalStateException("依赖包里没有 pylibs/ 内容：${plugin.id}")
                    val staging = ZipInstall.stagingDir(dir)
                    staging.deleteRecursively()
                    staging.mkdirs()
                    try {
                        envEntries.forEach { e ->
                            val rel = e.name.removePrefix("pylibs/")
                            ZipInstall.copyEntryBounded(zf, e, ZipInstall.safeDst(staging, rel), budget)
                            installed.add(rel)
                        }
                        val oldFiles = PluginStore.installedOf(plugin.id)
                            ?.takeIf { it.type == "env" }?.files.orEmpty()
                        swapFileSet(dir, staging, oldFiles, installed.toList())
                    } catch (e: Exception) {
                        runCatching { staging.deleteRecursively() }
                        throw e
                    }
                }
                /**
                 * v0.29.0：player=播放器引擎插件。包内 libs/ 下的 .so 解到私有
                 * filesDir/mpv-libs/（native 库必须走私有目录 dlopen），
                 * MpvPluginLoader.ensureLoaded 按序 System.load。
                 */
                "player" -> {
                    val dir = File(context.filesDir, "mpv-libs").also { it.mkdirs() }
                    // v0.186.0：与 source 分支同口径，先解暂存再按本插件文件集合换入。
                    val soEntries = entries.filter { it.name.startsWith("libs/") && it.name.endsWith(".so") }
                    if (soEntries.isEmpty()) throw IllegalStateException("播放器插件包里没有 libs 目录的 so 文件：${plugin.id}")
                    val staging = ZipInstall.stagingDir(dir)
                    staging.deleteRecursively()
                    staging.mkdirs()
                    try {
                        soEntries.forEach { e ->
                            val name = File(e.name).name
                            ZipInstall.copyEntryBounded(zf, e, ZipInstall.safeDst(staging, name), budget)
                            installed.add(name)
                        }
                        val oldFiles = PluginStore.installedOf(plugin.id)
                            ?.takeIf { it.type == "player" }?.files.orEmpty()
                        swapFileSet(dir, staging, oldFiles, installed.toList())
                    } catch (e: Exception) {
                        runCatching { staging.deleteRecursively() }
                        throw e
                    }
                }
                /**
                 * v0.30.0：subtitle=字幕搜索插件。包内 subtitle-plugin.dex 解到私有
                 * filesDir/subtitle-plugin/，SubtitlePluginLoader 经 DexClassLoader 加载。
                 */
                "subtitle" -> {
                    val dir = File(context.filesDir, "subtitle-plugin").also { it.mkdirs() }
                    // v0.186.0：与 source 分支同口径，先解暂存再换入；
                    // 目标只有一个 dex 文件，换入失败时备份还原，旧 dex 不丢。
                    val dexEntries = entries.filter { it.name.endsWith(".dex") }
                    if (dexEntries.isEmpty()) throw IllegalStateException("字幕插件包里没有 dex 文件：${plugin.id}")
                    val staging = ZipInstall.stagingDir(dir)
                    staging.deleteRecursively()
                    staging.mkdirs()
                    // B2：旧 dex 下次换入前先可写，否则只读文件在覆盖路径上可能失败；
                    // 换入后立即设只读，照 SpiderEngine 的做法，Android 14+ 才肯加载。
                    File(dir, "subtitle-plugin.dex").takeIf { it.isFile }?.setWritable(true)
                    try {
                        dexEntries.forEach { e ->
                            val name = "subtitle-plugin.dex"
                            ZipInstall.copyEntryBounded(zf, e, ZipInstall.safeDst(staging, name), budget)
                            installed.add(name)
                        }
                        val oldFiles = PluginStore.installedOf(plugin.id)
                            ?.takeIf { it.type == "subtitle" }?.files.orEmpty()
                        swapFileSet(dir, staging, oldFiles, installed.toList())
                        val dexFile = File(dir, "subtitle-plugin.dex")
                        if (dexFile.isFile && !dexFile.setReadOnly()) {
                            LogBus.w(TAG, "字幕 dex 设只读失败 ${dexFile.name}")
                        }
                    } catch (e: Exception) {
                        runCatching { staging.deleteRecursively() }
                        throw e
                    }
                    com.movieshell.data.source.subtitle.SubtitlePluginLoader.reset()
                }
                /**
                 * v0.185.0：setup=一键配置插件。安装即配置：
                 * ① StoragePaths.setupDirs() 建齐全部目录；
                 * ② 包内 docs/ 下的说明文档先存一份到 pluginsDir/<id>/docs/（供以后
                 *    “重新一键配置”找回），再按相对路径写到 xiaying 根/子目录，
                 *    已有文件一律不覆盖（只记跳过数，不删、不改用户文件）；
                 * ③ 无 docs/ 也照常建目录，不算失败。
                 */
                "setup" -> {
                    setupSummary = applySetupDocs(context, plugin, zf, entries, installed, budget)
                }
                else -> throw IllegalStateException("不支持的插件类型：${plugin.type}")
            }
        }
        PluginStore.recordInstalled(
            context,
            InstalledPlugin(plugin.id, plugin.name, plugin.version, plugin.type, engine, installed),
        )
        return setupSummary
    }

    /**
     * v0.185.0：setup 插件的配置执行（安装与“重新一键配置”共用同一入口）。
     * docsSource 为 null 时从已安装的 pluginsDir/<id>/docs/ 取文档（重新配置用）；
     * 非 null 时从安装包取文档并先存一份到 pluginsDir/<id>/docs/。
     * 返回给用户看的汇总（目录数/写入数/跳过数），已有文件不覆盖。
     */
    fun reconfigureSetup(context: Context, id: String): String {
        val rec = PluginStore.installedOf(id)
            ?: throw IllegalStateException("未安装一键配置插件：$id")
        if (rec.type != "setup") throw IllegalStateException("不是一键配置插件：$id")
        val docsDir = File(StoragePaths.pluginsDir(context), "$id/docs")
        if (!docsDir.isDirectory) throw IllegalStateException("一键配置插件缺 docs，重新安装一次再试")
        val dirs = StoragePaths.setupDirs(context)
        var written = 0
        var skipped = 0
        docsDir.walkTopDown().filter { it.isFile }.forEach { src ->
            val rel = src.relativeTo(docsDir).path
            // v0.188.0：目标路径同样过 safeDst，不依赖“先写副本后写目标”的顺序
            val dst = ZipInstall.safeDst(StoragePaths.baseDir(context), rel)
            dst.parentFile?.mkdirs()
            if (dst.exists()) skipped++ else {
                src.copyTo(dst)
                written++
            }
        }
        return setupSummaryText(dirs, written, skipped)
    }

    /**
     * v0.188.0：setup 汇总文案（L24）。目录数按实际落点拆开：公开根下的算
     * 公开目录，落到 filesDir 回退位置的算私有回退目录——无存储权限时
     * 全部目录都在私有回退位置，用户在文件管理器里看不到，文案不再
     * 笼统写“已建好 N 个目录”误导。
     */
    private fun setupSummaryText(dirs: List<File>, written: Int, skipped: Int): String {
        val pubRoot = runCatching { StoragePaths.publicRoot().canonicalPath }.getOrNull()
        var pub = 0
        dirs.forEach { d ->
            val c = runCatching { d.canonicalPath }.getOrNull()
            if (pubRoot != null && c != null &&
                (c == pubRoot || c.startsWith(pubRoot + File.separator))
            ) pub++
        }
        val priv = dirs.size - pub
        return "一键配置完成：已建好 $pub 个公开目录、$priv 个私有回退目录，" +
            "写入 $written 份说明，$skipped 份已有未覆盖"
    }

    private fun applySetupDocs(
        context: Context,
        plugin: MarketPlugin,
        zf: ZipFile,
        entries: List<java.util.zip.ZipEntry>,
        installed: MutableList<String>,
        budget: ZipInstall.UnpackBudget,
    ): String {
        val dirs = StoragePaths.setupDirs(context)
        val docsRoot = File(StoragePaths.pluginsDir(context), "${plugin.id}/docs")
        docsRoot.mkdirs()
        var written = 0
        var skipped = 0
        entries.filter { it.name.startsWith("docs/") && !it.name.endsWith("/") }.forEach { e ->
            val rel = e.name.removePrefix("docs/")
            // ① 先存一份到插件自己的 docs/（重新配置的来源，卸载时随插件一起删）
            // v0.186.0：这次解压计入解压预算；② 的文件拷贝不是解压，不重复计。
            val srcDst = ZipInstall.safeDst(docsRoot, rel)
            srcDst.parentFile?.mkdirs()
            ZipInstall.copyEntryBounded(zf, e, srcDst, budget)
            installed.add("docs/$rel")
            // ② 再写到 xiaying 目标位置，已有不覆盖
            // v0.188.0：目标路径同样过 safeDst（L24 防御纵深，不依赖顺序）
            val dst = ZipInstall.safeDst(StoragePaths.baseDir(context), rel)
            dst.parentFile?.mkdirs()
            if (dst.exists()) skipped++ else {
                zf.getInputStream(e).use { input -> dst.outputStream().use { input.copyTo(it) } }
                written++
            }
        }
        return setupSummaryText(dirs, written, skipped)
    }

    /**
     * 包内 plugin.json（如有）与市场条目做 id/version 一致性校验。
     * v0.186.0：解析失败不再静默放行，改为拒绝安装。签名未变（仍是私有函数，
     * 仍抛 IllegalStateException），调用链 install 经 PluginUi 捕获后记 error
     * 日志并弹失败提示，故无需另加提示分支，也无需折中为仅记日志。
     */
    private fun checkManifest(zf: ZipFile, plugin: MarketPlugin) {
        val e = zf.entries().asSequence().firstOrNull { it.name == "plugin.json" } ?: return
        val o = runCatching {
            JSONObject(zf.getInputStream(e).readBytes().toString(Charsets.UTF_8))
        }.getOrElse {
            LogBus.e(TAG, "包内 plugin.json 解析失败，拒绝安装：${plugin.id}（${it.message}）")
            throw IllegalStateException("包内 plugin.json 解析失败，拒绝安装：${plugin.id}")
        }
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
    suspend fun uninstall(context: Context, id: String, deleteDocs: Boolean = false) = withContext(Dispatchers.IO) {
        val rec = PluginStore.installedOf(id)
            ?: throw IllegalStateException("未安装：$id")
        when (rec.type) {
            // v0.185.0：setup 卸载只删插件自己的 docs/ 副本，不删已建好的目录
            // 和已写入的说明，更不碰用户放进各目录的规则文件（删了就丢不回来）。
            // v0.188.0：deleteDocs=true 时另删已写入的说明（L24 选项，默认 false）：
            // 只删内容与 docs 副本逐字节一致的目标文件——用户改过、或安装时
            // 已存在而被跳过的文件一律不动；只删文件，目录一律保留。
            "setup" -> {
                if (deleteDocs) deleteSetupDocs(context, id)
                File(StoragePaths.pluginsDir(context), id).deleteRecursively()
            }
            "server" -> File(StoragePaths.pluginsDir(context), id).deleteRecursively()
            // v0.137.0：runtime 插件卸载删私有安装目录（服务页共享安装的拷备由
            // ServicePluginManager 下次解析 php 占位时按来源存亡自行清理）
            "runtime" -> File(StoragePaths.runtimeDir(context), id).deleteRecursively()
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
                // v0.217.0（F57）：与站源页删除同一套六项清理——原来只清
                // 内存激活态，禁用态／探活／首页缓存／置顶全残留，同名源
                // 重装后旧状态复活。
                rec.files.forEach { SourceDeleteCleanup.run(context, rec.engine, it) }
            }
        }
        PluginStore.removeInstalled(context, id)
        LogBus.i(TAG, "卸载成功：$id")
        // v0.96.1：live 插件卸载后刷新聚合
        if (rec.type == "live") {
            runCatching { LiveStore.refresh(context) }
                .onFailure { LogBus.w(TAG, "卸载后刷新直播聚合失败：${it.message}") }
        }
    }

    /**
     * v0.188.0：setup 卸载删说明（L24）。比对依据是卸载前尚存的 docs/ 副本：
     * 副本已不在时无法验证，一律不删目标文件，宁可残留也不误删用户文件。
     */
    private fun deleteSetupDocs(context: Context, id: String) {
        val docsDir = File(StoragePaths.pluginsDir(context), "$id/docs")
        if (!docsDir.isDirectory) return
        val base = StoragePaths.baseDir(context)
        var deleted = 0
        docsDir.walkTopDown().filter { it.isFile }.forEach { src ->
            val rel = src.relativeTo(docsDir).path
            val dst = runCatching { ZipInstall.safeDst(base, rel) }.getOrNull() ?: return@forEach
            if (!dst.isFile) return@forEach
            val same = runCatching {
                dst.readBytes().contentEquals(src.readBytes())
            }.getOrDefault(false)
            if (same && dst.delete()) deleted++
        }
        LogBus.i(TAG, "setup 卸载删说明：$id，共删 $deleted 份（内容不一致的已保留）")
    }

    /**
     * v0.96.1：live 插件还原旧版——把最新的备份拷回去并刷新聚合。
     * 返回还原的备份文件名；无备份抛异常。
     */
    suspend fun restoreLiveBackup(context: Context, id: String): String =
        withContext(Dispatchers.IO) {
            val dir = StoragePaths.liveDir(context)
            val bak = dir.listFiles { f -> f.isFile && f.name.startsWith("$id.json.bak-") }
                .orEmpty().maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("没有可还原的备份")
            bak.copyTo(File(dir, "$id.json"), overwrite = true)
            runCatching { LiveStore.refresh(context) }
            LogBus.i(TAG, "live 已还原旧版：${bak.name}")
            bak.name
        }

    /** v0.96.1：是否有 live 备份（供 UI 决定显不显示"还原旧版"）。 */
    fun hasLiveBackup(context: Context, id: String): Boolean =
        StoragePaths.liveDir(context)
            .listFiles { f -> f.isFile && f.name.startsWith("$id.json.bak-") }
            .orEmpty().isNotEmpty()

    /** 远程版本是否新于本地版本（语义版本比对；minApp 门槛复用）。 */
    fun isNewer(remote: String, local: String): Boolean {
        // v0.186.0：先剥 v/V 前缀再分段比对，否则 v1.2 的首段解析为 0，会被误判为不新于 1.x。
        fun stripPrefix(v: String): String {
            val t = v.trim()
            return if (t.startsWith("v") || t.startsWith("V")) t.substring(1) else t
        }
        val r = stripPrefix(remote).split(".", "-"); val l = stripPrefix(local).split(".", "-")
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrNull(i)?.toIntOrNull() ?: 0
            val b = l.getOrNull(i)?.toIntOrNull() ?: 0
            if (a != b) return a > b
        }
        return false
    }

    /**
     * v0.139.0：运行时插件是否可更新——版本号更新，或同为 runtime 但版本不同
     * （换构建路线时版本号可能回落，如动态版 8.5.1 → 静态版 8.3.35，仍应视为可更新）。
     */
    fun isUpdatable(plugin: MarketPlugin, installedVersion: String?): Boolean =
        installedVersion != null && (isNewer(plugin.version, installedVersion) ||
            (plugin.type == "runtime" && plugin.version != installedVersion))

    /**
     * v0.188.0：全仓唯一字节格式化（L03 收敛：SourcesScreen、SettingsScreen、
     * PluginUi 的三份同名函数已删，调用点全部改调这里）。
     * 口径：<=0 返空（调用方按空处理，如插件详情不显示大小）；GB/MB 一位小数、
     * KB 取整；Locale.US 固定小数点，不随系统语言变逗号。
     */
    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1L shl 30 -> "%.1f GB".format(Locale.US, bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(Locale.US, bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%d KB".format(Locale.US, bytes / 1024)
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
            // S3：https 被重定向降级到 http 时中止，防中间人借重定向换包；
            // 临时文件由调用方 install 的 finally 删掉，不会进入校验与安装。
            if (url.startsWith("https://", ignoreCase = true) &&
                resp.request.url.scheme.equals("http", ignoreCase = true)
            ) {
                throw IllegalStateException("插件下载被重定向到明文，已中止下载")
            }
            val body = resp.body ?: throw IllegalStateException("下载内容为空")
            val total = totalHint.takeIf { it > 0 } ?: body.contentLength()
            // v0.186.0：下载截断（H14）。totalHint 即市场条目 plugin.size，本函数
            // 此前只把它当进度 total 用、不做超限判断。市场 size 是元数据、可能滞后
            // 于包体重建，故不按等值卡死，留 20% 宽容度；超出 120% 即视为标注失真
            // 或内容异常，中止下载；调用方 install 的 finally 会删掉临时文件，
            // 截断的包不会进入校验与安装。size 未标注（totalHint<=0）时沿用旧口径不限流。
            val limit = if (totalHint > 0) totalHint + totalHint / 5 else Long.MAX_VALUE
            var done = 0L
            body.byteStream().use { input ->
                dst.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        if (n == 0) continue
                        out.write(buf, 0, n)
                        done += n
                        if (done > limit) {
                            throw IllegalStateException("下载大小超出市场标注的 120%，已中止下载")
                        }
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

    /**
     * v0.186.0：共享目录的文件集合换入（H13，source/env/player/subtitle 共用）。
     * 规则目录、pylibs、mpv-libs 都是多插件共享目录，不能整目录改名替换，
     * 故只换本插件的文件集合：先把目标目录里将被覆盖或删除的旧文件搬进
     * 同父目录的备份目录，再把暂存文件搬入，最后删备份与暂存；换入中途
     * 失败时删掉已搬入的新文件、把备份搬回，旧版与注册表不动（注册表由
     * installZip 在整批成功后才写）。oldFiles 取自旧版注册表记录，newFiles
     * 是本次暂存里的相对路径，两者都经 safeDst 校验防路径逃逸。
     */
    private fun swapFileSet(
        dir: File,
        staging: File,
        oldFiles: List<String>,
        newFiles: List<String>,
    ) {
        dir.mkdirs()
        val parent = staging.parentFile
            ?: throw IllegalStateException("暂存目录无父目录：${staging.path}")
        val backup = File(parent, "${dir.name}.swapbak-${System.currentTimeMillis()}")
        backup.mkdirs()
        val movedNew = mutableListOf<String>()
        try {
            (oldFiles + newFiles).distinct().forEach { rel ->
                val src = ZipInstall.safeDst(dir, rel)
                if (!src.isFile) return@forEach
                val bak = ZipInstall.safeDst(backup, rel)
                bak.parentFile?.mkdirs()
                if (!src.renameTo(bak)) {
                    src.copyTo(bak, overwrite = true)
                    src.delete()
                }
            }
            staging.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(staging).path
                val dst = ZipInstall.safeDst(dir, rel)
                dst.parentFile?.mkdirs()
                if (!f.renameTo(dst)) {
                    f.copyTo(dst, overwrite = true)
                    f.delete()
                }
                movedNew.add(rel)
            }
            runCatching { staging.deleteRecursively() }
            runCatching { backup.deleteRecursively() }
        } catch (e: Exception) {
            movedNew.forEach { rel -> runCatching { ZipInstall.safeDst(dir, rel).delete() } }
            if (backup.isDirectory) {
                backup.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(backup).path
                    val dst = ZipInstall.safeDst(dir, rel)
                    dst.parentFile?.mkdirs()
                    runCatching {
                        if (!f.renameTo(dst)) f.copyTo(dst, overwrite = true)
                    }
                }
            }
            runCatching { staging.deleteRecursively() }
            runCatching { backup.deleteRecursively() }
            throw e
        }
    }

    private fun ruleDir(context: Context, engine: String): File = when (engine) {
        "py" -> StoragePaths.pyDir(context)
        "js" -> StoragePaths.jsDir(context)
        "php" -> PhpSourceManager.rulesDir(context)
        else -> throw IllegalStateException("不支持的源引擎：$engine")
    }
}
