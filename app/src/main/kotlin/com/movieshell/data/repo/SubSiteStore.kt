package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.model.ParseConfig
import com.movieshell.data.model.Site
import com.movieshell.data.model.SubSiteKeyPrefix
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.TvBoxConfigParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 订阅站源展开 / 持久化（v0.5.1）。
 *
 * 背景：站源页"添加订阅"之前只存 URL，搜索页把每个订阅 URL 硬当成 type=1 CMS
 * 接口去请求——完整 TVBox 配置（含 sites 数组，如 vcloudc 的 api.json）当 CMS
 * 请求必失败，点播页也完全没接订阅。本 Store 负责：
 *
 *  1. refresh()：下载订阅 JSON，用 TvBoxConfigParser 解析 sites，按规则展开：
 *     - 扩展字段"类型"为小说/漫画 -> 标记 unsupported（App 只做视频点播）；
 *     - type=0/1 -> CMS，直用原始 api；
 *     - type=3 且 api 以 csp_ 开头 -> 标记 unsupported（Java spider，需 tvkj.jar）；
 *     - type=4 -> type4（PHP 站：api 是 PHP 脚本的 HTTP 地址，直接请求，不下载）；
 *     - type=3 且 api 以 .py 结尾 -> 下载 .py 到 filesDir/py/subs/；
 *     - type=3 且 api 以 .js 结尾 -> 按订阅 base URL 补全 api/ext 为绝对 URL，
 *       下载到 filesDir/drpy/subs/，挑出含 `var rule` 定义的那个做规则文件；
 *       drpy2.min.js 是 ES module（import 语法），WebView 里直接 evaluate 不可行，
 *       好在规则文件本身是标准 drpy 格式，直接用现有垫片执行 ext 即可；
 *     - 其余 -> unsupported。
 *  2. 落盘 filesDir/sub_sites.json：记录每站的订阅 base URL，供升级修复用；
 *  3. repairOnUpgrade()：v0.5.1 首次启动时，对 api 仍是相对路径 / 本地文件丢失的
 *     旧站源按 base URL 重新下载修复，修不好的标 unsupported；
 *  4. getCurrent()/setCurrent()：点播页"当前源"（subUrl + siteKey），SharedPreferences 持久化。
 */
@Serializable
data class SubSiteRecord(
    val key: String = "",
    val name: String = "",
    val type: Int = 1,
    /** drpy/py：本地绝对路径；CMS：原始接口 URL；unsupported：原始 api */
    val api: String = "",
    val ext: String = "",
    /** 修复用：展开前的原始 api/ext（相对路径形态） */
    val origApi: String = "",
    val origExt: String = "",
    /** cms / drpy / py / type4 / unsupported */
    val kind: String = SubSiteStore.KIND_CMS,
    val reason: String = "",
    val searchable: Boolean = true,
    val enabled: Boolean = true,
    /** 订阅声明的扩展字段"类型"（如 "小说"/"漫画"） */
    val typeLabel: String = "",
)

@Serializable
data class SubCache(
    val subUrl: String = "",
    val baseUrl: String = "",
    val fetchedAt: Long = 0L,
    val sites: List<SubSiteRecord> = emptyList(),
    /** 订阅顶层 parses 数组（解析线路），随订阅持久化，type=4 的 parse=1 播放走它解析 */
    val parses: List<ParseConfig> = emptyList(),
    /** 订阅顶层 spider 字段解析出的 jar 绝对地址（csp_ 站的 Java spider 运行环境） */
    val spiderJar: String = "",
)

@Serializable
private data class SubCacheFile(val caches: List<SubCache> = emptyList())

@Serializable
data class SubCurrent(val subUrl: String = "", val siteKey: String = "")

object SubSiteStore {
    private const val TAG = "SubSiteStore"
    private const val FILE_NAME = "sub_sites.json"
    private const val PREFS = "movieshell_sub"
    private const val KEY_CUR_URL = "current_sub_url"
    private const val KEY_CUR_KEY = "current_site_key"
    private const val KEY_REPAIRED_051 = "repaired_051"

    const val KIND_CMS = "cms"
    const val KIND_DRPY = "drpy"
    const val KIND_PY = "py"
    /** type=4 PHP 站：api 是 PHP 脚本的 HTTP 地址，无需下载，直接请求 */
    const val KIND_TYPE4 = "type4"
    /** Java spider 站（api 以 csp_ 开头）：v0.8.0 起由 SpiderEngine 动态加载 jar 执行 */
    const val KIND_SPIDER = "spider"
    const val KIND_UNSUPPORTED = "unsupported"

    const val REASON_JAVA_SPIDER =
        "Java spider 源（api 以 csp_ 开头）：加载订阅的 spider jar 失败，" +
            "请检查网络后重试，或刷新一次该订阅。"

    /**
     * v0.9.0：进程级 scope。订阅刷新这类"用户切走页面也要跑完"的任务用它，
     * 别用 composable 的 rememberCoroutineScope（组合离开会被取消，
     * 报 "The coroutine scope left the composition"）。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** v0.9.0：同一目标文件的并发下载串行化（key = dir1/dir2/name）。 */
    private val downloadLocks = ConcurrentHashMap<String, Any>()

    // ---------------- v0.8.0：spider jar 地址 ----------------

    /** 取订阅缓存里存的 spider jar 绝对地址（无则空串）。 */
    fun getSpiderJar(context: Context, subUrl: String): String =
        getCached(context, subUrl)?.spiderJar.orEmpty()

    /**
     * 补取 spider jar 地址：v0.8.0 之前添加的订阅缓存里没有 spiderJar 字段，
     * 轻量拉一次配置 JSON 只取顶层 spider 字段（不重下规则文件），写回缓存。
     * 必须在 IO 线程调用。
     */
    fun ensureSpiderJar(context: Context, subUrl: String): String {
        val cached = getSpiderJar(context, subUrl)
        if (cached.isNotBlank()) return cached
        return runCatching {
            val config = TvBoxConfigParser.parseConfig(downloadText(subUrl.trim()))
            val resolved = JarSpiderStore.resolveJarUrl(subUrl.trim(), config.spider)
            if (resolved.isNotBlank()) {
                val all = loadAll(context).toMutableMap()
                all[subUrl.trim()]?.let { cache ->
                    all[subUrl.trim()] = cache.copy(spiderJar = resolved)
                    saveAll(context, all)
                }
            }
            resolved
        }.onFailure { LogBus.e(TAG, "补取 spider 字段失败 $subUrl：${it.message}") }
            .getOrDefault("")
    }

    // ---------------- v0.8.0 升级修复 ----------------

    private const val KEY_REPAIRED_080 = "repaired_080"

    /**
     * v0.8.0 之前 csp_ 站被标成 KIND_UNSUPPORTED，转成 KIND_SPIDER
     * （jar 改为首次使用时懒下载）。只执行一次。
     */
    suspend fun repairSpiderKind(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REPAIRED_080, false)) return@withContext
        try {
            val all = loadAll(context)
            var changed = false
            val repaired = all.mapValues { (_, cache) ->
                val sites = cache.sites.map { rec ->
                    if (rec.kind == KIND_UNSUPPORTED &&
                        rec.api.trim().startsWith("csp_", ignoreCase = true)
                    ) {
                        changed = true
                        rec.copy(kind = KIND_SPIDER, reason = "")
                    } else rec
                }
                cache.copy(sites = sites)
            }
            if (changed) saveAll(context, repaired)
            Log.i(TAG, "v0.8.0 spider 站修复完成，changed=$changed")
        } catch (e: Exception) {
            Log.w(TAG, "spider 站修复异常", e)
        } finally {
            prefs.edit().putBoolean(KEY_REPAIRED_080, true).apply()
        }
    }

    const val REASON_NOVEL_COMIC =
        "小说/漫画类站点：MovieShell 只做视频点播，该类型暂不支持。"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val client by lazy { CmsApi.defaultClient() }

    /** 订阅短 id：拼进子站点 key（sub:<id>:<siteKey>）与本地文件名。 */
    fun subId(subUrl: String): String = abs(subUrl.hashCode()).toString(36)

    /** 子站点的完整 key（"sub:<id>:<siteKey>"）。 */
    fun siteKey(subUrl: String, rec: SubSiteRecord): String =
        "$SubSiteKeyPrefix${subId(subUrl)}:${rec.key}"

    /** SubSiteRecord -> 运行时 Site（key 带 sub: 前缀，供 SubSiteEngines 识别）。 */
    fun toSite(subUrl: String, rec: SubSiteRecord): Site = Site(
        key = siteKey(subUrl, rec),
        name = rec.name.ifBlank { rec.key },
        type = rec.type,
        api = rec.api.trim(),
        searchable = rec.searchable,
        quickSearch = false,
        typeLabel = rec.typeLabel,
        ext = rec.ext,
        subUrl = subUrl,
    )

    /** 按 "sub:<id>:<key>" 反查 (subUrl, record)，找不到返回 null。 */
    fun findSite(context: Context, siteKey: String): Pair<String, SubSiteRecord>? {
        if (!siteKey.startsWith(SubSiteKeyPrefix)) return null
        val rest = siteKey.removePrefix(SubSiteKeyPrefix).split(":", limit = 2)
        if (rest.size != 2) return null
        val (id, key) = rest
        val cache = loadAll(context).values.firstOrNull { subId(it.subUrl) == id } ?: return null
        val rec = cache.sites.firstOrNull { it.key == key } ?: return null
        return cache.subUrl to rec
    }

    // ---------------- 缓存读写 ----------------

    fun getCached(context: Context, subUrl: String): SubCache? = loadAll(context)[subUrl]

    /** 取某订阅的解析线路（顶层 parses 数组）：refresh 时随订阅持久化，无缓存返回空。 */
    fun getParses(context: Context, subUrl: String): List<ParseConfig> =
        getCached(context, subUrl)?.parses.orEmpty()

    fun removeCache(context: Context, subUrl: String) {
        val all = loadAll(context).toMutableMap()
        if (all.remove(subUrl) != null) saveAll(context, all)
        // 当前源指向被删的订阅 -> 清掉
        if (getCurrent(context)?.subUrl == subUrl) clearCurrent(context)
    }

    fun setSiteEnabled(context: Context, subUrl: String, siteKey: String, enabled: Boolean) {
        val all = loadAll(context).toMutableMap()
        val cache = all[subUrl] ?: return
        val sites = cache.sites.map { if (it.key == siteKey) it.copy(enabled = enabled) else it }
        all[subUrl] = cache.copy(sites = sites)
        saveAll(context, all)
        if (!enabled && getCurrent(context)?.let { it.subUrl == subUrl && it.siteKey == siteKey } == true) {
            clearCurrent(context)
        }
    }

    private fun loadAll(context: Context): Map<String, SubCache> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return emptyMap()
        return try {
            json.decodeFromString(SubCacheFile.serializer(), file.readText())
                .caches.associateBy { it.subUrl }
        } catch (e: Exception) {
            Log.w(TAG, "读取 $FILE_NAME 失败", e)
            emptyMap()
        }
    }

    private fun saveAll(context: Context, all: Map<String, SubCache>) {
        try {
            val text = json.encodeToString(
                SubCacheFile.serializer(),
                SubCacheFile(all.values.toList()),
            )
            File(context.filesDir, FILE_NAME).writeText(text)
        } catch (e: Exception) {
            Log.w(TAG, "写入 $FILE_NAME 失败", e)
        }
    }

    // ---------------- 展开 ----------------

    /**
     * 有缓存直接返回；否则下载展开一次（60 秒总超时），失败返回 null。
     * 搜索页用它做"先判断"：能解析出 sites 的就是完整 TVBox 配置。
     */
    suspend fun ensureExpanded(context: Context, subUrl: String): SubCache? {
        getCached(context, subUrl)?.let { return it }
        return withTimeoutOrNull(60_000) { refresh(context, subUrl).getOrNull() }
    }

    /** 下载订阅 JSON 并展开全部站点，落盘；sites 为空视为失败。 */
    suspend fun refresh(context: Context, subUrl: String): Result<SubCache> {
        LogBus.i(TAG, "刷新订阅：$subUrl")
        return withContext(Dispatchers.IO) {
            runCatching {
                val raw = downloadText(subUrl.trim())
                LogBus.i(TAG, "订阅下载成功 ${raw.length} 字符：$subUrl")
                val config = TvBoxConfigParser.parseConfig(raw)
                LogBus.i(TAG, "订阅解析：${config.sites.size} 个站，${config.parses.size} 条解析线路")
                if (config.sites.isEmpty()) {
                    throw IllegalStateException("订阅里没有 sites 数组，可能不是完整 TVBox 配置")
                }
                val baseUri = URI(subUrl.trim())
                // 站点展开（含规则文件下载）并行做：大包几十个站时串行太慢
                val sites: List<SubSiteRecord> = coroutineScope {
                    config.sites.map { sc ->
                        async {
                            runCatching { expandSite(context, subUrl.trim(), baseUri, sc) }
                                .getOrElse {
                                    Log.w(TAG, "展开站点失败: ${sc.key}", it)
                                    null
                                }
                        }
                    }.awaitAll().filterNotNull()
                }
                if (sites.isEmpty()) throw IllegalStateException("订阅站点全部展开失败")
                val cache = SubCache(
                    subUrl = subUrl.trim(),
                    baseUrl = baseOf(baseUri),
                    fetchedAt = System.currentTimeMillis(),
                    sites = sites,
                    parses = config.parses,
                    spiderJar = JarSpiderStore.resolveJarUrl(subUrl.trim(), config.spider),
                )
                val all = loadAll(context).toMutableMap()
                all[cache.subUrl] = cache
                saveAll(context, all)
                // v0.8.0：订阅刷新后 spider jar 可能已更新，清掉旧缓存下次重下
                SpiderEngine.evict(context, cache.subUrl)
                cache
            }.onFailure { LogBus.e(TAG, "订阅刷新失败 $subUrl：${it.message}") }
                .onSuccess { LogBus.i(TAG, "订阅刷新成功：${it.sites.size} 个站已展开") }
        }
    }

    private fun expandSite(
        context: Context,
        subUrl: String,
        baseUri: URI,
        sc: com.movieshell.data.model.SiteConfig,
    ): SubSiteRecord? {
        val api = sc.api.trim()
        val ext = sc.ext.trim()
        if (sc.key.isBlank() || api.isBlank()) return null
        val searchable = sc.searchable != 0
        val base = SubSiteRecord(
            key = sc.key, name = sc.name.ifBlank { sc.key }, type = sc.type,
            origApi = api, origExt = ext, searchable = searchable,
            typeLabel = sc.typeLabel,
        )
        // 小说/漫画站：App 只做视频点播，该类型统一标不支持（UI 打徽标，home/search 跳过）
        val isNovelOrComic = sc.typeLabel.trim().let { it.contains("小说") || it.contains("漫画") }
        if (isNovelOrComic) {
            return base.copy(kind = KIND_UNSUPPORTED, api = api, ext = ext, reason = REASON_NOVEL_COMIC)
        }
        // csp_ 开头：Java spider（v0.8.0 起由 SpiderEngine 动态加载 jar 执行，放最前，避免被 .js/.py 误判）
        if (api.startsWith("csp_", ignoreCase = true)) {
            return base.copy(kind = KIND_SPIDER, api = api, ext = ext)
        }
        if (sc.type == 0 || sc.type == 1) {
            return base.copy(kind = KIND_CMS, api = api, ext = ext)
        }
        // type=4：api 是 PHP 脚本的 HTTP 地址（PHP 跑在远端/本地服务里，壳子只发 HTTP），
        // 无需下载规则文件；相对路径按订阅 base URL 补全，含中文/空格的由 Type4Api 请求时编码
        if (sc.type == 4) {
            return base.copy(kind = KIND_TYPE4, api = resolveUrl(baseUri, api), ext = ext)
        }
        if (api.endsWith(".py", ignoreCase = true)) {
            val url = resolveUrl(baseUri, api)
            val dst = downloadTo(context, "py", "subs", fileNameFor(subUrl, sc.key, url, ".py"), url)
                ?: return base.copy(
                    kind = KIND_UNSUPPORTED, api = api, ext = ext,
                    reason = "PY 规则下载失败：$url",
                )
            return base.copy(kind = KIND_PY, api = dst.absolutePath, ext = ext)
        }
        if (api.endsWith(".js", ignoreCase = true)) {
            // 候选：ext 优先（TVBox 里 ext 才是规则文件，api 常是 drpy2.min.js 引擎），
            // api 自身是规则时也兼容（如 api=./js/xxx.js 且 ext 为空）。
            val candidates = listOf(ext, api)
                .filter { it.endsWith(".js", ignoreCase = true) }
                .distinct()
                .map { resolveUrl(baseUri, it) }
            val files = candidates.mapNotNull { url ->
                downloadTo(context, "drpy", "subs", fileNameFor(subUrl, sc.key, url, ".js"), url)
                    ?.let { url to it }
            }
            if (files.isEmpty()) {
                return base.copy(
                    kind = KIND_UNSUPPORTED, api = api, ext = ext,
                    reason = "JS 规则下载失败：${candidates.firstOrNull().orEmpty()}",
                )
            }
            // 挑含 var rule 定义的那个做规则文件；drpy2.min.js 自身只是引擎（且是 ES module 跑不起来），跳过
            val ruleFile = files.firstOrNull { (_, f) -> isRuleFile(f) }?.second
                ?: files.firstOrNull { (url, _) -> !isEngineFileName(url) }?.second
            // 非规则文件删掉，保持目录干净
            files.forEach { (_, f) -> if (f != ruleFile) runCatching { f.delete() } }
            if (ruleFile == null) {
                return base.copy(
                    kind = KIND_UNSUPPORTED, api = api, ext = ext,
                    reason = "下载的 JS 里没找到 var rule 定义，规则不完整，暂不支持。",
                )
            }
            return base.copy(kind = KIND_DRPY, api = ruleFile.absolutePath, ext = ext)
        }
        return base.copy(
            kind = KIND_UNSUPPORTED, api = api, ext = ext,
            reason = "未知的站源形态（type=${sc.type}），暂不支持。",
        )
    }

    // ---------------- v0.5.1 升级修复 ----------------

    /**
     * 首次启动修复：缓存里 api 仍是相对路径、或本地规则文件丢失的 drpy/py 站，
     * 按记录的 base URL（origApi/origExt）重新下载；修不好的标 unsupported。
     * 只执行一次（SharedPreferences 标记）。
     */
    suspend fun repairOnUpgrade(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REPAIRED_051, false)) return@withContext
        try {
            val all = loadAll(context)
            if (all.isEmpty()) return@withContext
            var changed = false
            val repaired = all.mapValues { (subUrl, cache) ->
                val baseUri = runCatching { URI(subUrl) }.getOrNull()
                val sites = cache.sites.map { rec ->
                    val lost = (rec.kind == KIND_DRPY || rec.kind == KIND_PY) &&
                        (isRelativePath(rec.api) || !File(rec.api).isFile)
                    if (!lost || baseUri == null || rec.origApi.isBlank()) return@map rec
                    changed = true
                    val fixed = runCatching {
                        reDownload(context, subUrl, baseUri, rec)
                    }.getOrNull()
                    fixed ?: rec.copy(
                        kind = KIND_UNSUPPORTED,
                        reason = "规则文件丢失且按订阅地址自动修复下载失败",
                    )
                }
                cache.copy(sites = sites)
            }
            if (changed) saveAll(context, repaired)
            Log.i(TAG, "v0.5.1 升级修复完成，changed=$changed")
        } catch (e: Exception) {
            Log.w(TAG, "升级修复异常", e)
        } finally {
            prefs.edit().putBoolean(KEY_REPAIRED_051, true).apply()
        }
    }

    /** 按 origApi/origExt 重新下载，成功返回修复后的 record。 */
    private fun reDownload(
        context: Context,
        subUrl: String,
        baseUri: URI,
        rec: SubSiteRecord,
    ): SubSiteRecord? {
        return when (rec.kind) {
            KIND_PY -> {
                val url = resolveUrl(baseUri, rec.origApi)
                val dst = downloadTo(context, "py", "subs", fileNameFor(subUrl, rec.key, url, ".py"), url)
                    ?: return null
                rec.copy(api = dst.absolutePath)
            }
            KIND_DRPY -> {
                val candidates = listOf(rec.origExt, rec.origApi)
                    .filter { it.endsWith(".js", ignoreCase = true) }
                    .distinct()
                    .map { resolveUrl(baseUri, it) }
                val files = candidates.mapNotNull { url ->
                    downloadTo(context, "drpy", "subs", fileNameFor(subUrl, rec.key, url, ".js"), url)
                        ?.let { url to it }
                }
                if (files.isEmpty()) return null
                val ruleFile = files.firstOrNull { (_, f) -> isRuleFile(f) }?.second
                    ?: files.firstOrNull { (url, _) -> !isEngineFileName(url) }?.second
                files.forEach { (_, f) -> if (f != ruleFile) runCatching { f.delete() } }
                if (ruleFile == null) return null
                rec.copy(api = ruleFile.absolutePath)
            }
            else -> null
        }
    }

    // ---------------- 当前源 ----------------

    fun getCurrent(context: Context): SubCurrent? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_CUR_URL, null).orEmpty()
        val key = prefs.getString(KEY_CUR_KEY, null).orEmpty()
        if (url.isBlank() || key.isBlank()) return null
        // 记录没了 / 被标不支持 / 被关掉 -> 视为无效
        val rec = getCached(context, url)?.sites?.firstOrNull { it.key == key }
            ?: return null
        if (!rec.enabled || rec.kind == KIND_UNSUPPORTED) return null
        return SubCurrent(url, key)
    }

    /** 设为点播页当前源；目标无效返回 false。 */
    fun setCurrent(context: Context, subUrl: String, siteKey: String): Boolean {
        val rec = getCached(context, subUrl)?.sites?.firstOrNull { it.key == siteKey }
            ?: return false
        if (!rec.enabled || rec.kind == KIND_UNSUPPORTED) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CUR_URL, subUrl)
            .putString(KEY_CUR_KEY, siteKey)
            .apply()
        return true
    }

    fun clearCurrent(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_CUR_URL).remove(KEY_CUR_KEY).apply()
    }

    /** 当前源的完整 site key（"sub:<id>:<key>"），UI 高亮用；未设置返回 null。 */
    fun currentSiteKey(context: Context): String? {
        val cur = getCurrent(context) ?: return null
        val rec = getCached(context, cur.subUrl)?.sites?.firstOrNull { it.key == cur.siteKey }
            ?: return null
        return siteKey(cur.subUrl, rec)
    }

    // ---------------- 内部工具 ----------------

    private fun downloadText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("空响应")
        }
    }

    /**
     * v0.9.0：同一目标文件的并发下载串行化 + 3 次尝试。
     * 修 fstat ENOENT（多站同引 drpy2.min.js 时并发写同一文件）与
     * "connection closed" 偶发失败。注意：不复用旧文件，刷新订阅时
     * 规则文件照旧重下，保证拿到上游最新内容。
     */
    private fun downloadTo(context: Context, dir1: String, dir2: String, name: String, url: String): File? {
        val lock = downloadLocks.getOrPut("$dir1/$dir2/$name") { Any() }
        synchronized(lock) {
            // v0.7.2：订阅下载的规则也进公开目录（drpy→xiaying/js/subs，py→xiaying/py/subs）
            val base = when (dir1) {
                "drpy" -> StoragePaths.jsDir(context)
                "py" -> StoragePaths.pyDir(context)
                else -> File(context.filesDir, dir1)
            }
            val dir = File(base, dir2).apply { mkdirs() }
            val dst = File(dir, name)
            var lastErr: String? = null
            repeat(3) { attempt ->
                try {
                    val req = Request.Builder().url(url).header("User-Agent", UA).build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        resp.body?.byteStream()?.use { input ->
                            dst.outputStream().use { input.copyTo(it) }
                        } ?: throw IllegalStateException("空响应体")
                    }
                    if (dst.length() == 0L) {
                        dst.delete()
                        throw IllegalStateException("下载为空")
                    }
                    LogBus.i(TAG, "规则下载成功：${dst.absolutePath}")
                    return dst
                } catch (e: Exception) {
                    lastErr = e.message
                    dst.delete()
                    if (attempt < 2) LogBus.w(TAG, "规则下载重试 ${attempt + 2}/3 $url：${e.message}")
                }
            }
            LogBus.e(TAG, "规则下载失败 $url：$lastErr")
            return null
        }
    }

    /**
     * v0.9.0 修：URL 里已带扩展名时不再追加（修 "xxx.js.js" 双后缀）。
     */
    private fun fileNameFor(subUrl: String, siteKey: String, url: String, suffix: String): String {
        val safeKey = siteKey.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40)
        val base = url.substringAfterLast("/").substringBefore("?").take(40)
            .replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "rule" }
        val name = if (base.endsWith(suffix, ignoreCase = true)) base else base + suffix
        return "${subId(subUrl)}_${safeKey}_$name"
    }

    private fun baseOf(uri: URI): String {
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme}://${uri.host}$port"
    }

    /** 相对路径按订阅 URL 解析；绝对 URL 原样返回。java.net.URI.resolve 全兼容。 */
    private fun resolveUrl(baseUri: URI, ref: String): String =
        runCatching { baseUri.resolve(ref.trim()).toString() }.getOrDefault(ref.trim())

    private fun isRelativePath(path: String): Boolean {
        val t = path.trim()
        return !(t.startsWith("http://", ignoreCase = true) ||
            t.startsWith("https://", ignoreCase = true) || t.startsWith("/"))
    }

    /** 规则文件判定：含 var/let/const rule 定义。 */
    private fun isRuleFile(f: File): Boolean {
        return try {
            val head = f.inputStream().use { input ->
                val buf = ByteArray(8192)
                val n = input.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }
            Regex("""(?m)^\s*(var|let|const)\s+rule\b""").containsMatchIn(head)
        } catch (e: Exception) {
            false
        }
    }

    private fun isEngineFileName(url: String): Boolean {
        val name = url.substringAfterLast("/").lowercase()
        return name == "drpy2.min.js" || name == "drpy.min.js" || name == "drpy2.js"
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
}
