package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.model.ParseConfig
import com.movieshell.data.model.Site
import com.movieshell.data.model.SubSiteKeyPrefix
import com.movieshell.data.model.WarehouseEntry
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DEFAULT_UA
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
    /** v0.112.0：用户备注（订阅站点列表展示） */
    val note: String = "",
    /** v0.112.0：用户分组（订阅站点列表分组筛选） */
    val group: String = "",
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
    /** v0.43.0：多仓子仓列表；非空时本条目是多仓容器，sites 为空 */
    val warehouses: List<WarehouseEntry> = emptyList(),
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
    /** v0.46.0：换源自动刷新——上次已加载内容的源标识（= HomeScreen.homeCacheKey()）。 */
    private const val KEY_LAST_LOADED_SOURCE = "last_loaded_source"

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
            val saved = if (changed) saveAll(context, repaired) else true
            Log.i(TAG, "v0.8.0 spider 站修复完成，changed=$changed")
            // v0.186.0 M21：只在成功时置标记；异常时不清标记，下次启动可重试
            // v0.247.0（F28）：saveAll 失败也不置标记——原来它内部吞异常，
            // 标记照置、修复永久丢失；未改动时无需写盘，直接置标记。
            if (saved) prefs.edit().putBoolean(KEY_REPAIRED_080, true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "spider 站修复异常", e)
        }
    }

    const val REASON_NOVEL_COMIC =
        "小说/漫画类站点：侠影只做视频点播，该类型暂不支持。"

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

    /**
     * v0.186.0 M20：删订阅统一跑五项清理，与站源页 deleteItem() 共用禁用态与探活入口：
     * 文件——订阅展开时下载的 drpy/py 规则、spider ext 文件（只删缓存会残留）；
     * 禁用态——子站启停在缓存条目 enabled 里、订阅启停在站源页删条目时消失，
     * SourceStateStore.disabled 与站源页 pins 只针对本地规则 tab，不含订阅条目；
     * 探活——订阅本身与展开的各子站记录；置顶——订阅无 pins 条目；
     * active——当前源指向被删订阅时清掉。共享 spider jar 多订阅可共用，不删；
     * 规则文件是直接删除而非进回收站（TrashStore 只记订阅元数据），恢复订阅后刷新重下。
     */
    fun removeCache(context: Context, subUrl: String) {
        // v0.217.0（F58）：入口先 trim 再全程用 trim 后的值——原来查缓存
        // 用了 trim 兜底、删缓存却只删原样 key，调用方传了带空白的地址
        // 就会“查得到、删不掉”，缓存记录残留成死链。
        val trimmedUrl = subUrl.trim()
        val cache = getCached(context, trimmedUrl)
        val all = loadAll(context).toMutableMap()
        if (all.remove(trimmedUrl) != null) saveAll(context, all)
        // 当前源指向被删的订阅 -> 清掉（两边都按 trim 后比）
        if (getCurrent(context)?.subUrl?.trim() == trimmedUrl) clearCurrent(context)
        if (cache == null) return
        cache.sites.forEach { rec ->
            if ((rec.kind == KIND_DRPY || rec.kind == KIND_PY) && rec.api.isNotBlank()) {
                runCatching { File(rec.api).takeIf { it.isFile }?.delete() }
            }
            if (rec.kind == KIND_SPIDER && rec.origExt.isNotBlank() &&
                !rec.origExt.trimStart().startsWith("{") &&
                !rec.origExt.startsWith("http", ignoreCase = true)
            ) {
                runCatching {
                    val url = resolveUrl(URI(trimmedUrl), rec.origExt)
                    val rawName = url.substringAfterLast("/").substringBefore("?").take(40)
                        .replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "ext.json" }
                    val safeKey = rec.key.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(30)
                    File(File(context.filesDir, "spider"), "ext")
                        .let { File(it, "${safeKey}_$rawName") }
                        .takeIf { it.isFile }?.delete()
                }
            }
        }
        // subs 目录按订阅 id 前缀扫一遍：fileNameFor 一律以 subId 开头，
        // 同订阅的引擎文件等残留一并清掉；本地规则文件名不带此前缀，不会误伤
        val prefix = "${subId(trimmedUrl)}_"
        listOf(File(StoragePaths.jsDir(context), "subs"), File(StoragePaths.pyDir(context), "subs"))
            .forEach { dir ->
                dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }.orEmpty()
                    .forEach { runCatching { it.delete() } }
            }
        val probeKeys = buildList {
            add("sub" to trimmedUrl)
            cache.sites.forEach { add("sub" to siteKey(trimmedUrl, it)) }
        }
        SourceProbeStore.removeProbes(context, probeKeys)
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

    /** v0.112.0：设置站点的用户备注与分组（站点编辑弹窗保存用） */
    fun setSiteNoteGroup(
        context: Context,
        subUrl: String,
        siteKey: String,
        note: String,
        group: String,
    ) {
        val all = loadAll(context).toMutableMap()
        val cache = all[subUrl] ?: return
        val sites = cache.sites.map {
            if (it.key == siteKey) it.copy(note = note, group = group) else it
        }
        all[subUrl] = cache.copy(sites = sites)
        saveAll(context, all)
    }

    private fun loadAll(context: Context): Map<String, SubCache> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return emptyMap()
        return try {
            val decoded = json.decodeFromString(SubCacheFile.serializer(), file.readText())
            // v0.187.0 H15：只对 drpy/py 规则的本地 api 做根拼回——落盘
            // 的是逻辑根相对路径，存量绝对路径先剥已知根再拼当前根；
            // CMS、type4、spider、unsupported 的 api 是接口地址或标识，原样不动
            val caches = decoded.caches.map { cache ->
                cache.copy(sites = cache.sites.map { rec ->
                    if ((rec.kind == KIND_DRPY || rec.kind == KIND_PY) && rec.api.isNotBlank()) {
                        rec.copy(api = StoragePaths.resolveStoredPath(context, rec.api))
                    } else rec
                })
            }
            caches.associateBy { it.subUrl }
        } catch (e: Exception) {
            Log.w(TAG, "读取 $FILE_NAME 失败", e)
            // v0.170.0：读坏先保全原文件，下次写盘不再悄悄清空
            AtomicFile.preserveCorrupt(file)
            emptyMap()
        }
    }

    // v0.247.0（F28）：写盘结果改返回 Boolean——原来吞异常只记日志，
    // 调用方以为写成了；刷新两路必须查它（失败变 Result.failure），
    // 两处升级修复用它决定是否置“已修复”标记；其余语句位调用（蜘蛛
    // jar 回填、删订阅、启停、备注）失败后果自显（下次重读即见旧值、
    // jar 回填下次重取），不另改签名，见 GPT 复核说明。
    private fun saveAll(context: Context, all: Map<String, SubCache>): Boolean {
        return try {
            // v0.187.0 H15：落盘前只把 drpy/py 规则的本地 api 换算成逻辑根
            // 相对路径（如 js/subs/xxx.js），内存态仍是当前根绝对路径
            val stored = all.values.map { cache ->
                cache.copy(sites = cache.sites.map { rec ->
                    if ((rec.kind == KIND_DRPY || rec.kind == KIND_PY) && rec.api.isNotBlank()) {
                        rec.copy(api = StoragePaths.toRelativePath(context, rec.api))
                    } else rec
                })
            }
            val text = json.encodeToString(
                SubCacheFile.serializer(),
                SubCacheFile(stored),
            )
            // v0.170.0：原子写盘。注意：writeText 永不抛异常，失败返回
            // false，必须原样返回它的 Boolean——第 1 轮写成“调完写 true”，
            // 写盘返回 false 时 saveAll 照样报 true，刷新失败抛不出来。
            AtomicFile.writeText(File(context.filesDir, FILE_NAME), text)
        } catch (e: Exception) {
            Log.w(TAG, "写入 $FILE_NAME 失败", e)
            false
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
                // v0.43.0：多仓优先识别——命中则存子仓列表直接返回，不走站点展开
                TvBoxConfigParser.parseWarehouse(raw)?.let { wh ->
                    LogBus.i(TAG, "多仓解析：${wh.size} 个子仓：$subUrl")
                    val cache = SubCache(
                        subUrl = subUrl.trim(),
                        baseUrl = baseOf(URI(subUrl.trim())),
                        fetchedAt = System.currentTimeMillis(),
                        warehouses = wh,
                    )
                    val all = loadAll(context).toMutableMap()
                    all[cache.subUrl] = cache
                    if (!saveAll(context, all)) throw IllegalStateException("订阅缓存写盘失败，刷新结果未落盘")
                    return@runCatching cache
                }
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
                        // v0.171.0：同一订阅内同 key 站源只留首条，否则两条记录
                        // 共用 fileNameFor 文件名互相覆盖、getCurrent 恒取第一条
                        .distinctBy { it.key.trim() }
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
                if (!saveAll(context, all)) throw IllegalStateException("订阅缓存写盘失败，刷新结果未落盘")
                // v0.8.0：订阅刷新后 spider jar 可能已更新，清掉旧缓存下次重下
                // （写盘失败时抛在上面，不走到这里，旧 jar 仍对得上旧缓存）
                SpiderEngine.evict(context, cache.subUrl)
                cache
            }.onFailure { LogBus.e(TAG, "订阅刷新失败 $subUrl：${it.message}") }
                .onSuccess {
                    if (it.warehouses.isNotEmpty()) {
                        LogBus.i(TAG, "多仓刷新成功：${it.warehouses.size} 个子仓")
                    } else {
                        LogBus.i(TAG, "订阅刷新成功：${it.sites.size} 个站已展开")
                    }
                }
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
            // v0.11.2：spider 的 ext 若是相对路径（如 ./json/xx.json），下载后把文件内容
            // 传给 spider.init；传相对路径 spider 拿不到配置，首页一直空
            val localExt = if (ext.isNotBlank() && !ext.startsWith("http", ignoreCase = true)
                && !ext.trimStart().startsWith("{")
            ) {
                val url = resolveUrl(baseUri, ext)
                val rawName = url.substringAfterLast("/").substringBefore("?").take(40)
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "ext.json" }
                val safeKey = sc.key.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(30)
                val f = downloadTo(context, "spider", "ext", "${safeKey}_$rawName", url)
                // 读出内容传给 spider（Hiker 类 spider 要的是规则 JSON 本身）；
                // 读不到就回退原 ext，不比原来更差
                runCatching { f?.readText()?.takeIf { it.isNotBlank() } }.getOrNull() ?: ext
            } else ext
            return base.copy(kind = KIND_SPIDER, api = api, ext = localExt)
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
            // v0.186.0 M21：空缓存直接走到底统一置标记，不再提前 return 漏掉标记
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
            val saved = if (changed) saveAll(context, repaired) else true
            Log.i(TAG, "v0.5.1 升级修复完成，changed=$changed")
            // v0.186.0 M21：只在成功时置标记；异常时不清标记，下次启动可重试
            // v0.247.0（F28）：saveAll 失败也不置标记（同上）；未改动时直接置标记。
            if (saved) prefs.edit().putBoolean(KEY_REPAIRED_051, true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "升级修复异常", e)
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

    /**
     * v0.46.0：换源自动刷新。上次已加载内容的源标识（HomeScreen.homeCacheKey() 的值）。
     * 持久化比对，跨 tab/进程也准；冷启动时两者一致，不触发自动加载。
     */
    fun lastLoadedSource(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_LOADED_SOURCE, null)

    fun setLastLoadedSource(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_LOADED_SOURCE, key)
            .apply()
    }

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
        val req = Request.Builder().url(url).header("User-Agent", DEFAULT_UA).build()
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
     * v0.11.0：404 时试一次"路径转小写"（vcloudc 仓库里 js/ 是小写，
     * api.json 却写了 JS/、drpy_libs/ 等陈旧大写路径）；其他 4xx 直接停。
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
            // v0.247.0（DS12 同类）：原来直接往目标文件写，失败时删目标——
            // 刷新失败会把旧版规则文件也丢掉。改经 .part 暂存，成功后改名；
            // 失败只删 .part，旧版规则留着，下次刷新再试。
            val part = File(dir, "$name.part")
            var lastErr: String? = null
            var tryUrl = url
            var lowered = false
            for (attempt in 0 until 3) {
                try {
                    val req = Request.Builder().url(tryUrl).header("User-Agent", DEFAULT_UA).build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        resp.body?.byteStream()?.use { input ->
                            part.outputStream().use { input.copyTo(it) }
                        } ?: throw IllegalStateException("空响应体")
                    }
                    if (part.length() == 0L) {
                        part.delete()
                        throw IllegalStateException("下载为空")
                    }
                    // v0.247.0（DS12 复核）：不用先删旧目标再改名（改名失败时
                    // 旧规则已丢）；改走 AtomicFile 备份替换，落位失败恢复旧目标。
                    if (!AtomicFile.replaceFile(part, dst)) {
                        part.delete()
                        throw IllegalStateException("规则落盘失败")
                    }
                    LogBus.i(TAG, "规则下载成功：${dst.absolutePath}")
                    return dst
                } catch (e: Exception) {
                    lastErr = e.message
                    part.delete()
                    val msg = (e as? IllegalStateException)?.message.orEmpty()
                    // 404 且没试过小写路径：把 host 后的路径全转小写再试一次（不占 3 次重试）
                    if (msg.contains("HTTP 404") && !lowered) {
                        val m = Regex("^(https?://[^/]+/)(.*)$").matchEntire(tryUrl)
                        val low = if (m != null) m.groupValues[1] + m.groupValues[2].lowercase() else tryUrl
                        if (low != tryUrl) {
                            lowered = true
                            tryUrl = low
                            LogBus.i(TAG, "404 转小写路径重试：$low")
                            continue
                        }
                    }
                    // 其他 4xx 是服务端明确拒绝，重试没用，直接停
                    if (Regex("HTTP 4\\d\\d").containsMatchIn(msg)) break
                    if (attempt < 2) LogBus.w(TAG, "规则下载重试 ${attempt + 2}/3 $tryUrl：${e.message}")
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
        runCatching { ensureSlashPath(baseUri).resolve(ref.trim()).toString() }.getOrDefault(ref.trim())

    /**
     * v0.108.0 A2：base path 为空时补 "/"。java.net.URI.resolve 在 base path 为空时不补斜杠
     *（URI("http://a").resolve("b") → "http://ab"），订阅 `http://肥猫.net` 的相对规则
     * `json/txt/ken.txt` 会被拼成畸形 host `http://肥猫.netjson/...` 致 DNS 失败（用户真机日志实锤）。
     */
    fun ensureSlashPath(uri: URI): URI =
        if (uri.path.isNullOrEmpty()) URI(uri.scheme, uri.authority, "/", null, null) else uri

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

}
