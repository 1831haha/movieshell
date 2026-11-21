package com.movieshell.player.danmaku

import android.content.Context
import com.movieshell.core.util.AtomicFile
import com.movieshell.core.log.LogBus
import com.movieshell.core.util.SafeFileName
import com.movieshell.data.source.CmsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/**
 * v0.54：弹幕数据源（弹弹play 公开 API，无需登录）。
 * 按标题搜索番剧 -> 取弹幕库 ID -> 拉取弹幕列表。
 * 失败静默返回空（无弹幕不影响播放）。
 */
object DanmakuSource {
    private const val TAG = "Danmaku"

    // v0.112.0 B1：弹幕小 JSON（搜索/拉取均为 GET），改用进程级共享单例。
    private val client = CmsApi.defaultClient()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class SearchResp(val animes: List<Anime> = emptyList())
    @Serializable
    private data class Anime(val animeId: Long = 0, val animeTitle: String = "")
    @Serializable
    private data class CommentResp(val comments: List<Comment> = emptyList())
    @Serializable
    private data class Comment(
        val cid: Long = 0,
        val p: String = "", // "时间,模式,颜色,用户"
        val m: String = "",
    )

    /**
     * v0.146.0：播放器弹幕钩子——内置源 + 本地 danmu-service 合并取数（唯一入口）。
     * 两路都按 timeMs 排序后合并，再走同一套去重（同文本 3 秒内只留一条）+
     * 密度控制（每分钟最多 60 条）；本地服务未运行/失败时只有内置源，不影响播放。
     * 开关只有一个（DanmakuSettings.enabled），由 PlayerScreen 在开关打开时调用。
     * v1.2.22：再加自定义接口一路（用户在弹幕设置里贴的 LogVar 站），三路合并；
     * 自定义一路整体 10 秒封顶，超时只丢这一路，不拖内置源与本地服务。
     */
    suspend fun fetchMerged(title: String, episode: Int = 1): List<Danmaku> = withContext(Dispatchers.IO) {
        val builtin = fetch(title, episode)
        val local = DanmakuLocalSource.fetch(title, episode)
        val customHosts = DanmakuSettings.customHosts.value
        val custom = if (customHosts.isEmpty()) emptyList()
        else withTimeoutOrNull(10_000) { fetchCustom(title, episode, customHosts) } ?: emptyList()
        if (local.isEmpty() && custom.isEmpty()) return@withContext builtin
        (builtin + local + custom).sortedBy { it.timeMs }
            .let { dedup(it) }
            .let { limitDensity(it) }
    }

    /**
     * v1.2.22：自定义弹幕接口——直接按弹弹play/LogVar 协议拉用户贴的站：
     * search → bangumi（按集数配 episodeId）→ comment；每站单请求 5 秒超时、
     * 失败跳过；多站并发。协议与 danmu-service 的 fetch_host 同款（server.sh）。
     */
    suspend fun fetchCustom(title: String, episode: Int, hosts: List<String>): List<Danmaku> =
        withContext(Dispatchers.IO) {
            if (hosts.isEmpty()) return@withContext emptyList()
            coroutineScope {
                hosts.map { host ->
                    async {
                        runCatching { fetchCustomHost(host, title, episode) }
                            .onFailure { LogBus.w(TAG, "自定义弹幕 $host 失败：${it.message}") }
                            .getOrElse { emptyList() }
                    }
                }.awaitAll().flatten()
            }.sortedBy { it.timeMs }
        }

    private suspend fun fetchCustomHost(host: String, title: String, episode: Int): List<Danmaku> {
        val q = java.net.URLEncoder.encode(title.take(20).trim(), "UTF-8")
        val searchBody = getBody("https://$host/api/v2/search/anime?keyword=$q") ?: return emptyList()
        val animeId = json.parseToJsonElement(searchBody).jsonObject["animes"]
            ?.jsonArray?.firstOrNull()?.jsonObject?.get("animeId")
            ?.jsonPrimitive?.longOrNull ?: return emptyList()
        val bangumiBody = getBody("https://$host/api/v2/bangumi/$animeId") ?: return emptyList()
        val episodeId = pickEpisodeId(bangumiBody, episode) ?: return emptyList()
        val commentBody = getBody("https://$host/api/v2/comment/$episodeId") ?: return emptyList()
        val comments = json.decodeFromString(CommentResp.serializer(), commentBody).comments
        val result = mapComments(comments)
        LogBus.i(TAG, "自定义弹幕 $host：$title E$episode -> ${result.size} 条")
        return result
    }

    /** v1.2.22：bangumi 集数配对——episodeNumber 精确命中优先，其次按顺序第 N 集，再退首集（同 server.sh）。 */
    internal fun pickEpisodeId(bangumiBody: String, episode: Int): Long? {
        val episodes = runCatching {
            json.parseToJsonElement(bangumiBody).jsonObject["episodes"]?.jsonArray
        }.getOrNull() ?: return null
        if (episodes.isEmpty()) return null
        val pairs = episodes.mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["episodeId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            // episodeNumber 在不同部署里可能是字符串也可能是数字，都按字符串比。
            val num = obj["episodeNumber"]?.jsonPrimitive?.content ?: return@mapNotNull null
            num to id
        }
        if (pairs.isEmpty()) return null
        pairs.firstOrNull { it.first == episode.toString() }?.second?.let { return it }
        if (episode in 1..pairs.size) return pairs[episode - 1].second
        return pairs.first().second
    }

    /** v1.2.22：单次 GET，5 秒超时；失败返回 null（调用方当无弹幕处理）。 */
    private suspend fun getBody(url: String): String? = withTimeoutOrNull(5_000) {
        val req = Request.Builder().url(url).header("User-Agent", "movieshell/1.0").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }

    /**
     * 按标题搜弹幕。返回按 timeMs 排序的列表；搜不到/失败返回空。
     * episode 用于多集时尽量匹配对应集（弹弹play 按番剧+集数取）。
     *
     * v0.55：智能匹配——标题清洗（去集数/特殊符号/括号内容）+ 多关键词回退；
     * 结果去重（同文本 3 秒内只留一条）+ 密度控制（每分钟最多 60 条）。
     */
    suspend fun fetch(title: String, episode: Int = 1): List<Danmaku> = withContext(Dispatchers.IO) {
        try {
            // v0.55：先查磁盘缓存
            val cacheKey = "${title.take(30)}_E$episode"
            loadCache(cacheKey)?.let {
                LogBus.i(TAG, "弹幕磁盘缓存命中：$title E$episode -> ${it.size} 条")
                return@withContext it
            }
            val animeId = searchAnimeSmart(title) ?: return@withContext emptyList()
            val comments = fetchComments(animeId, episode)
            val result = mapComments(comments)
                .sortedBy { it.timeMs }
                .let { dedup(it) }      // v0.55：去重
                .let { limitDensity(it) } // v0.55：密度控制
            // v0.55：写磁盘缓存
            if (result.isNotEmpty()) saveCache(cacheKey, result)
            LogBus.i(TAG, "弹幕拉取：$title E$episode -> ${result.size} 条（去重限流后）")
            result
        } catch (e: CancellationException) {
            // v0.246.0：取消必须重抛——原来 catch Exception 把取消当普通失败吞掉，
            // 取消后仍继续跑完并返回空列表，调用方以为只是没弹幕。
            throw e
        } catch (e: Exception) {
            LogBus.w(TAG, "弹幕拉取失败：${e.message}")
            emptyList()
        }
    }

    /** v1.2.22：弹幕评论（弹弹play/LogVar 同款 p 字段）转 Danmaku；内置源与自定义接口共用。 */
    private fun mapComments(comments: List<Comment>): List<Danmaku> = comments.mapNotNull { c ->
        val parts = c.p.split(",")
        if (parts.size < 3 || c.m.isBlank()) return@mapNotNull null
        val timeSec = parts[0].toDoubleOrNull() ?: return@mapNotNull null
        val mode = parts[1].toIntOrNull() ?: 1
        val colorInt = parts[2].toIntOrNull() ?: 16777215
        val type = when (mode) {
            4 -> Danmaku.TYPE_BOTTOM
            5 -> Danmaku.TYPE_TOP
            else -> Danmaku.TYPE_SCROLL
        }
        val argb = 0xFF000000.toInt() or (colorInt and 0xFFFFFF)
        Danmaku(
            text = c.m.take(60),
            timeMs = (timeSec * 1000).toLong(),
            color = argb,
            type = type,
        )
    }

    /** v0.55：标题清洗 + 多关键词回退搜索 */
    private fun searchAnimeSmart(title: String): Long? {
        val candidates = buildList {
            // 1. 原标题前 20 字
            add(title.take(20).trim())
            // 2. 去括号内容（"斗罗大陆（重制版）" -> "斗罗大陆"）
            val noBracket = title.replace(Regex("[（(\\[].*?[）)\\]]"), "").trim().take(20)
            if (noBracket.isNotEmpty() && noBracket != title.take(20).trim()) add(noBracket)
            // 3. 去集数/季数（"凡人修仙传 第2季" -> "凡人修仙传"）
            val noEp = title.replace(Regex("\\s*第\\s*[一二三四五六七八九十0-9]+\\s*[季部]"), "")
                .replace(Regex("\\s*S\\d+", RegexOption.IGNORE_CASE), "")
                .trim().take(20)
            if (noEp.isNotEmpty() && noEp != title.take(20).trim()) add(noEp)
        }.distinct().filter { it.isNotEmpty() }
        for (q in candidates) {
            val id = searchAnime(q)
            if (id != null) {
                if (q != candidates[0]) LogBus.i(TAG, "弹幕标题回退匹配：$q")
                return id
            }
        }
        return null
    }

    /** v0.55：去重——同文本 3 秒内只留第一条 */
    private fun dedup(list: List<Danmaku>): List<Danmaku> {
        if (list.size < 2) return list
        val result = ArrayList<Danmaku>(list.size)
        val lastSeen = mutableMapOf<String, Long>()
        for (d in list) {
            val last = lastSeen[d.text]
            if (last == null || d.timeMs - last > 3000) {
                result.add(d)
                lastSeen[d.text] = d.timeMs
            }
        }
        // 防内存爆炸：只记最近 500 条文本
        if (lastSeen.size > 500) lastSeen.clear()
        return result
    }

    /** v0.55：密度控制——每分钟最多 60 条，超了均匀抽稀 */
    private fun limitDensity(list: List<Danmaku>): List<Danmaku> {
        if (list.isEmpty()) return list
        val result = ArrayList<Danmaku>(list.size)
        var windowStart = 0L
        var windowCount = 0
        for (d in list) {
            val window = d.timeMs / 60_000
            if (window != windowStart) {
                windowStart = window
                windowCount = 0
            }
            if (windowCount < 60) {
                result.add(d)
                windowCount++
            }
            // 超了就丢（按时间排序，丢的是后面的，保留前面的）
        }
        return result
    }

    // v0.55：弹幕磁盘缓存（filesDir/danmaku_cache，按标题+集数存 7 天）
    private const val CACHE_VALID_MS = 7L * 24 * 60 * 60 * 1000
    private var appContext: Context? = null
    fun initCache(context: Context) { appContext = context.applicationContext }

    private fun cacheFile(key: String): java.io.File? {
        val ctx = appContext ?: return null
        val dir = java.io.File(ctx.filesDir, "danmaku_cache").also { it.mkdirs() }
        // v0.294.1：key 含中文片名，原来全被吃成下划线、同长度片名共用一份弹幕缓存；走 SafeFileName 保唯一
        val safe = SafeFileName.encode(key, Regex("[^a-zA-Z0-9_-]"), 60, "_")
        return java.io.File(dir, "$safe.json")
    }

    private fun loadCache(key: String): List<Danmaku>? {
        val f = cacheFile(key) ?: return null
        return runCatching {
            val text = f.readText()
            val e = json.decodeFromString(CacheEntry.serializer(), text)
            if (System.currentTimeMillis() - e.savedAt > CACHE_VALID_MS) {
                // v1.3.33 B-P2-4：过期缓存顺手删文件——只返 null 不管磁盘，
                // 从不访问的旧文件会一直占着地方。
                runCatching { f.delete() }
                return null
            }
            if (e.items.isEmpty()) return null
            e.items.map { Danmaku(it.text, it.timeMs, it.color, it.type) }
        }.getOrNull()
    }

    private fun saveCache(key: String, list: List<Danmaku>) {
        val f = cacheFile(key) ?: return
        runCatching {
            // 只存前 500 条，文件别太大
            val e = CacheEntry(
                savedAt = System.currentTimeMillis(),
                items = list.take(500).map { CacheItem(it.text, it.timeMs, it.color, it.type) },
            )
            AtomicFile.writeText(f, json.encodeToString(CacheEntry.serializer(), e))
        }
    }

    @Serializable
    private data class CacheEntry(val savedAt: Long = 0L, val items: List<CacheItem> = emptyList())
    @Serializable
    private data class CacheItem(val text: String, val timeMs: Long, val color: Int, val type: Int)

    /** 定期清理用的文件信息（名 / 修改时间 / 大小）。 */
    internal data class CacheFileInfo(val name: String, val lastModified: Long, val length: Long)

    /**
     * v1.3.33 B-P2-4：定期清理选出要删的文件（纯函数，供单测）。
     * 先删过期（修改时间距 now 超 TTL），再按最旧先删到数量/总大小上限内。
     * 年龄按 lastModified 算——缓存文件写盘时间即保存时间，与 loadCache 的
     * savedAt 口径一致，清理时不必逐个解析文件。
     */
    internal fun selectCacheFilesToDelete(
        files: List<CacheFileInfo>, now: Long, ttlMs: Long, maxFiles: Int, maxBytes: Long,
    ): List<CacheFileInfo> {
        val expired = files.filter { now - it.lastModified > ttlMs }
        val alive = (files - expired.toSet()).sortedBy { it.lastModified }
        var total = alive.sumOf { it.length }
        val over = mutableListOf<CacheFileInfo>()
        var idx = 0
        while ((alive.size - idx > maxFiles || total > maxBytes) && idx < alive.size) {
            total -= alive[idx].length
            over += alive[idx]
            idx++
        }
        return expired + over
    }

    /**
     * v1.3.33 B-P2-4：定期清理弹幕磁盘缓存（CacheAutoClean 每日任务调）：
     * 过期文件全删；再按 1000 个文件 / 256MB 上限删最旧的。
     * @return 删掉的文件数。
     */
    fun cleanCache(context: Context, maxFiles: Int = 1000, maxBytes: Long = 256L * 1024 * 1024): Int {
        val dir = java.io.File(context.filesDir, "danmaku_cache")
        if (!dir.isDirectory) return 0
        val infos = dir.listFiles()?.filter { it.isFile }
            ?.map { CacheFileInfo(it.name, it.lastModified(), it.length()) } ?: return 0
        val toDelete = selectCacheFilesToDelete(infos, System.currentTimeMillis(), CACHE_VALID_MS, maxFiles, maxBytes)
        var removed = 0
        toDelete.forEach { info ->
            if (runCatching { java.io.File(dir, info.name).delete() }.getOrDefault(false)) removed++
        }
        if (removed > 0) LogBus.i(TAG, "弹幕缓存定期清理：删 $removed 个文件")
        return removed
    }

    private fun searchAnime(title: String): Long? {
        // 取标题前 20 字搜，避免过长标题搜不到
        val q = title.take(20).trim()
        if (q.isEmpty()) return null
        val req = Request.Builder()
            .url("https://${DanmakuSettings.builtinHost.value}/api/v2/search/anime?keyword=${java.net.URLEncoder.encode(q, "UTF-8")}")
            .header("User-Agent", "movieshell/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val parsed = json.decodeFromString(SearchResp.serializer(), body)
            // 取第一个（最相关）
            return parsed.animes.firstOrNull()?.animeId
        }
    }

    private fun fetchComments(animeId: Long, episode: Int): List<Comment> {
        val req = Request.Builder()
            .url("https://${DanmakuSettings.builtinHost.value}/api/v2/comment/$animeId/$episode")
            .header("User-Agent", "movieshell/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            return json.decodeFromString(CommentResp.serializer(), body).comments
        }
    }
}

/**
 * v1.3.25 合并 8：弹幕接口 host 归一化（纯函数）——去 scheme/路径/端口/
 * 尾点，只取 host 部分；非法返回 null。parseCustomDanmakuHosts 与
 * 内置接口设置共用同一套规则。
 */
fun normalizeDanmakuHost(raw: String): String? {
    var h = raw.trim().lowercase()
    if (h.isEmpty()) return null
    h = h.removePrefix("https://").removePrefix("http://")
    h = h.substringBefore('/').substringBefore('?').substringBefore('#')
    h = h.substringBefore(':').removeSuffix(".")
    if (!h.contains('.') || h.length > 253) return null
    if (!h.all { it.code < 128 && (it.isLetterOrDigit() || it == '.' || it == '-') }) return null
    if (h.startsWith('.') || h.startsWith('-') || h.endsWith('-')) return null
    if (h.split('.').any { it.isEmpty() }) return null
    return h
}

/**
 * v1.2.22：自定义弹幕接口——每行一个 LogVar 站地址，只取 host 部分（API 走 host 根，
 * 页面路由后缀如 /87654321、/cynnsq 不用）。纯函数：去 scheme/路径/端口、去重、上限 10 个。
 */
fun parseCustomDanmakuHosts(raw: String): List<String> {
    val seen = LinkedHashSet<String>()
    for (token in raw.split('\n', ',', '，', ' ', '\t')) {
        val h = normalizeDanmakuHost(token) ?: continue
        seen.add(h)
        if (seen.size >= 10) break
    }
    return seen.toList()
}

/**
 * v1.4.8 A5：danmu-service 插件内置的 14 家弹幕源（与插件 server.sh 的
 * HOSTS 词表逐字一致）。App 侧写 CUSTOM_HOSTS env 前去掉与这些重复的项，
 * 插件侧只追加、不重复跑同一家。
 */
val PLUGIN_BUILTIN_DANMAKU_HOSTS: List<String> = listOf(
    "dm.999.team",
    "danmu.qianting168.com",
    "dm.ljiaovm.com",
    "danmu.longemby.com",
    "justdanmu.irisnb.com",
    "dandan.wenjian.de",
    "danmu.cynn.top",
    "dmfl.us.ci",
    "danmu.0000996.xyz",
    "dm.taotu.ink",
    "logvardm.278612356.xyz",
    "logvar.hhht.cc.cd",
    "dm.66608880.xyz",
    "danmu.iyo.us.ci",
)

/** v1.4.8 A5：danmu-service 的服务 id、CUSTOM_HOSTS env 键、最低版本。 */
const val DANMU_SERVICE_ID = "danmu-service"
const val DANMU_SERVICE_CUSTOM_HOSTS_KEY = "CUSTOM_HOSTS"
const val DANMU_SERVICE_MIN_VERSION = "1.0.2"

/**
 * v1.4.8 A5：写进 danmu-service env 的 host 序列化（纯函数）——逗号分隔、
 * 去空白、去重、去掉与插件内置 14 家重复的项、超限截断到 10 个。
 * 与 parseCustomDanmakuHosts 的区别：去内置重复在截断之前做，排在前面的
 * 内置项不占自定义名额。
 */
fun serializePluginDanmakuHosts(raw: String): String {
    val seen = LinkedHashSet<String>()
    for (token in raw.split('\n', ',', '，', ' ', '\t')) {
        val h = normalizeDanmakuHost(token) ?: continue
        if (h in PLUGIN_BUILTIN_DANMAKU_HOSTS) continue
        seen.add(h)
        if (seen.size >= 10) break
    }
    return seen.joinToString(",")
}

/**
 * v1.4.8 A5：插件版本门（纯函数）——按点分段逐段比数字，1.0.2 及以上放行。
 * 未安装（null）或非法版本号一律不放行。
 */
fun isDanmakuPluginVersionSupported(version: String?): Boolean {
    if (version.isNullOrBlank()) return false
    val min = intArrayOf(1, 0, 2)
    val parts = version.trim().removePrefix("v").split(".")
    for (i in min.indices) {
        val v = parts.getOrNull(i)?.toIntOrNull() ?: 0
        if (v != min[i]) return v > min[i]
    }
    return true
}

/**
 * v0.54：弹幕开关与状态（持久化在 movieshell_prefs）。
 * v0.56：加样式设置（大小/透明度/速度/区域），全部持久化。
 */
object DanmakuSettings {
    private const val PREFS = "movieshell_prefs"
    private const val KEY_ENABLED = "danmaku_enabled"
    // v0.56
    private const val KEY_SIZE = "danmaku_size" // 0=小 1=中 2=大
    private const val KEY_ALPHA = "danmaku_alpha" // 0-100
    private const val KEY_SPEED = "danmaku_speed" // 0=慢 1=中 2=快
    private const val KEY_AREA = "danmaku_area" // 0=全屏 1=上半屏
    // v0.150.0：弹幕行高（对标 DS danmakuLineHeight）0=紧凑 1=标准 2=宽松
    private const val KEY_LINE_HEIGHT = "danmaku_line_height"
    // v1.2.22：自定义弹幕接口（原始文本，每行一个 LogVar 站地址）
    private const val KEY_CUSTOM_HOSTS = "danmaku_custom_hosts"
    // v1.3.25 合并 8：内置弹幕接口 host（默认弹弹play 官方站；用户可填镜像站）
    private const val KEY_BUILTIN_HOST = "danmaku_builtin_host"
    const val DEFAULT_BUILTIN_HOST = "api.dandanplay.net"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    // v0.56：样式状态流
    private val _textSize = MutableStateFlow(1)
    val textSize: StateFlow<Int> = _textSize
    private val _alpha = MutableStateFlow(100)
    val alpha: StateFlow<Int> = _alpha
    private val _speed = MutableStateFlow(1)
    val speed: StateFlow<Int> = _speed
    private val _area = MutableStateFlow(0)
    val area: StateFlow<Int> = _area
    private val _lineHeight = MutableStateFlow(1)
    val lineHeight: StateFlow<Int> = _lineHeight
    // v1.2.22：自定义弹幕接口（原始文本 + 解析后的 host 列表）
    private val _customHostsRaw = MutableStateFlow("")
    val customHostsRaw: StateFlow<String> = _customHostsRaw
    private val _customHosts = MutableStateFlow<List<String>>(emptyList())
    val customHosts: StateFlow<List<String>> = _customHosts
    // v1.3.25 合并 8：内置弹幕接口 host（默认官方站，init 前也按默认走，不静默丢弹幕）
    private val _builtinHost = MutableStateFlow(DEFAULT_BUILTIN_HOST)
    val builtinHost: StateFlow<String> = _builtinHost

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = p.getBoolean(KEY_ENABLED, false)
        _textSize.value = p.getInt(KEY_SIZE, 1)
        _alpha.value = p.getInt(KEY_ALPHA, 100)
        _speed.value = p.getInt(KEY_SPEED, 1)
        _area.value = p.getInt(KEY_AREA, 0)
        _lineHeight.value = p.getInt(KEY_LINE_HEIGHT, 1)
        _customHostsRaw.value = p.getString(KEY_CUSTOM_HOSTS, "") ?: ""
        _customHosts.value = parseCustomDanmakuHosts(_customHostsRaw.value)
        _builtinHost.value = p.getString(KEY_BUILTIN_HOST, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_BUILTIN_HOST
    }

    fun setEnabled(context: Context, on: Boolean) {
        _enabled.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
        LogBus.i("Danmaku", "弹幕开关：$on")
    }

    // v0.56：样式设置
    fun setTextSize(context: Context, v: Int) {
        _textSize.value = v.coerceIn(0, 2)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SIZE, _textSize.value).apply()
    }
    fun setAlpha(context: Context, v: Int) {
        _alpha.value = v.coerceIn(30, 100)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ALPHA, _alpha.value).apply()
    }
    fun setSpeed(context: Context, v: Int) {
        _speed.value = v.coerceIn(0, 2)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SPEED, _speed.value).apply()
    }
    fun setArea(context: Context, v: Int) {
        _area.value = v.coerceIn(0, 1)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_AREA, _area.value).apply()
    }
    fun setLineHeight(context: Context, v: Int) {
        _lineHeight.value = v.coerceIn(0, 2)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LINE_HEIGHT, _lineHeight.value).apply()
    }

    /** v1.2.22：自定义弹幕接口——存原始文本，解析后的 host 列表同步进状态流。 */
    fun setCustomHosts(context: Context, raw: String) {
        _customHostsRaw.value = raw
        _customHosts.value = parseCustomDanmakuHosts(raw)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_HOSTS, raw).apply()
        LogBus.i("Danmaku", "自定义弹幕接口：${_customHosts.value.size} 个")
    }

    /**
     * v1.3.25 合并 8：内置弹幕接口——填镜像站 host 用，留空恢复默认。
     * 非法地址返回 false（调用方保留原值并提示）；合法返回 true。
     */
    fun setBuiltinHost(context: Context, raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            _builtinHost.value = DEFAULT_BUILTIN_HOST
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_BUILTIN_HOST).apply()
            LogBus.i("Danmaku", "内置弹幕接口：恢复默认 $DEFAULT_BUILTIN_HOST")
            return true
        }
        val host = normalizeDanmakuHost(trimmed) ?: return false
        _builtinHost.value = host
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BUILTIN_HOST, host).apply()
        LogBus.i("Danmaku", "内置弹幕接口：$host")
        return true
    }


    // v1.3.33 B-P2-11：fontPx/scrollMs/alphaInt 已删——DanmakuView 里同名
    // 的是局部变量（各算各的），此处三个函数生产+单测零调用。
}
