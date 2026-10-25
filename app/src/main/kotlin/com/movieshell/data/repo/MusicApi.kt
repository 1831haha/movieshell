package com.movieshell.data.repo

import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DEFAULT_UA_WEBKIT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * v0.79.0：在线音乐 API（GD Studio 聚合接口，2026-10-17 实测可用）。
 *
 * - 搜索：GET {BASE}?types=search&source={GD源}&name={q}&count={n}&pages={p}
 * - 播放地址：GET {BASE}?types=url&source={GD源}&id={url_id} → {"url": "..."}
 * - 封面：GET {BASE}?types=pic&source={GD源}&id={pic_id} → {"url": "真实图片地址"}
 *   （v0.89.0 实测：并非直接返回图片，而是包一层 {"url":...}，须经 picImageUrl 解析一次）
 *
 * v0.126.0：平台口径收敛——全 App 统一用 LX 平台 code（wy/tx/kw/kg/mg），
 * LX code 与 GD source 字符串的映射只在本对象一处（[gdSourceOf]）；旧数据里的
 * 别名（qq/wyy/netease/中文名）经 [normalizeSource] 归一。2026-10-20 实测：
 * GD 实例仅 netease 可用，tencent/kuwo/kugou/migu 均返回 source not supported，
 * 故 GD 路径（取链/封面/歌词）只对 [GD_SUPPORTED_PLATFORMS] 内的平台出数据。
 * v0.127.0：搜索/榜单不再靠 GD——wy 走 GD，其余四家走 [PlatformMusicApi]
 * 各自的公开接口（逐家实测），[SEARCHABLE_PLATFORMS] 五家全点亮；
 * 播放直链仍由 LX 脚本按曲目平台解析，不受 GD 限制。
 */
object MusicApi {
    private const val TAG = "MusicApi"
    private const val BASE = "https://music-api.gdstudio.xyz/api.php"

    /**
     * v0.126.0：已知可搜索平台 code（兜底与搜索接口口径）。
     * v0.180.0：这只是兜底，不是 UI 的固定分类——音乐页平台行优先按
     * 当前 LX 音源自报的平台列出（见 LxMusicSourceEngine.getSourcePlatforms），
     * 音源没加载出来时才先按这张表显示；以后新平台只要在这里加 code 并在
     * [PlatformMusicApi] 接上搜索接口，UI 不用改就能点亮。
     */
    val PLATFORM_CODES = listOf("wy", "tx", "kw", "kg", "mg")

    /** v0.180.0：可搜索平台的有序列表（UI 不直接读 PLATFORM_CODES）。 */
    fun searchablePlatformCodes(): List<String> = PLATFORM_CODES

    /** v0.126.0：LX code → GD source 字符串，唯一映射处。 */
    private val GD_SOURCE = mapOf(
        "wy" to "netease",
        "tx" to "tencent",
        "kw" to "kuwo",
        "kg" to "kugou",
        "mg" to "migu",
    )

    /** v0.126.0 实测：GD 实例实际能出数据的平台（其余 source 被服务端拒绝）。 */
    val GD_SUPPORTED_PLATFORMS = setOf("wy")

    /**
     * v0.127.0：搜索/榜单真正可用的平台全集——wy 走 GD，tx/kw/kg/mg 走
     * [PlatformMusicApi] 原生接口（2026-10-20 逐家实测全通）。搜索 chip 的
     * 可选口径只看这里，不再被 GD 的支持面误伤。
     */
    val SEARCHABLE_PLATFORMS = PLATFORM_CODES.toSet()

    /** v0.127.0：榜单条目（在线页「推荐歌单」按平台渲染的数据源）。 */
    data class ChartEntry(val id: String, val name: String, val coverUrl: String = "")

    /** v0.127.0：网易云榜单（原在线页写死的四榜，收敛到数据层与各家同口径）。 */
    private val WY_CHARTS = listOf(
        ChartEntry("3778678", "热歌榜"),
        ChartEntry("3779629", "新歌榜"),
        ChartEntry("19723756", "飙升榜"),
        ChartEntry("2884035", "原创榜"),
    )

    /**
     * v0.126.0：平台 code 归一——LX code 原样，别名（qq/wyy/netease）与中文名
     * 映射到 LX code；认不出返回空串（旧数据兼容：已存 Track 的 source 可能是
     * 别名或中文，读取侧一律先过这里再用）。
     */
    fun normalizeSource(code: String): String = when (code.trim().lowercase()) {
        "wy", "wyy", "netease", "网易云", "网易云音乐" -> "wy"
        "tx", "qq", "腾讯", "qq音乐" -> "tx"
        "kw", "kuwo", "酷我", "酷我音乐" -> "kw"
        "kg", "kugou", "酷狗", "酷狗音乐" -> "kg"
        "mg", "migu", "咪咕", "咪咕音乐" -> "mg"
        else -> ""
    }

    /** v0.126.0：LX code → GD source 字符串；认不出的 code 回退 netease。 */
    fun gdSourceOf(lxCode: String): String =
        GD_SOURCE[normalizeSource(lxCode)] ?: "netease"

    // v0.81.0：@Serializable 以便本地音乐/收藏持久化；localUri 存本地音频的 content URI
    // v0.123.0：source=来源平台 code（wy/tx/kg/kw/mg），列表行常驻来源 chip 用；
    // 带默认值，旧收藏/歌单 JSON 缺字段读作空串（不显示，不编造）
    @kotlinx.serialization.Serializable
    data class Track(
        val id: String,
        val name: String,
        val artist: String,
        val album: String,
        val picId: String,
        val urlId: String,
        val localUri: String = "",
        val source: String = "",
    ) {
        val isLocal: Boolean get() = localUri.isNotBlank()
    }

    /** v0.123.0：平台 code → 中文名，全 App 一处真源（LxCookieKit 亦委托此处）；未知返回 null。
     *  v0.126.0：别名归一收敛到 [normalizeSource]，本函数只管 code → 中文名。 */
    fun platformLabel(code: String): String? = when (normalizeSource(code)) {
        "tx" -> "QQ音乐"
        "wy" -> "网易云音乐"
        "kw" -> "酷我音乐"
        "kg" -> "酷狗音乐"
        "mg" -> "咪咕音乐"
        else -> null
    }

    /**
     * v0.180.0：平台短标签——由 [platformLabel] 去“音乐”后缀派生，
     * 不是 UI 里再写一张五家对照表；认不出的 code 原样返回，音源以后
     * 报出新平台时 chip 上至少能显示 code，不会空白。
     */
    fun platformShortLabel(code: String): String {
        val full = platformLabel(code)
        if (full == null) return code.trim().ifBlank { code }
        val short = full.removeSuffix("音乐")
        return if (short == "网易云") "网易" else short
    }

    /**
     * v0.126.0：跨平台同曲匹配——候选里先挑「歌名归一相等且歌手有交集」的，
     * 其次「歌名归一相等」的第一条；都没有返回 null。
     * 歌名归一 = 小写、去空白、剥掉括号后缀（Live/Remix 等版本标注）。
     */
    fun pickSameSong(origin: Track, candidates: List<Track>): Track? {
        fun normName(s: String): String = s.lowercase()
            .replace(Regex("\\s"), "")
            .substringBefore("(").substringBefore("（").trim()
        fun artistTokens(s: String): Set<String> =
            s.split("、", ",", "，", "/", "&")
                .map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        val on = normName(origin.name)
        if (on.isBlank()) return null
        val oa = artistTokens(origin.artist)
        val nameEq = candidates.filter { normName(it.name) == on }
        return nameEq.firstOrNull { artistTokens(it.artist).intersect(oa).isNotEmpty() }
            ?: nameEq.firstOrNull()
    }

    // v0.112.0 B1：纯短 GET（搜索/播放地址/封面），每次请求显式带 UA 头，
    // 共享单例的 UA 拦截器不覆盖调用方显式 UA；改用进程级共享单例。
    private val http = CmsApi.defaultClient()

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA_WEBKIT)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("音乐接口请求失败：${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    /**
     * 搜索歌曲（全 App 唯一分发入口）。v0.126.0：加平台参数（LX code）。
     * v0.127.0：wy 走 GD；tx/kw/kg/mg 分发到 [PlatformMusicApi] 各家原生接口，
     * 搜哪家出哪家的歌与该平台自己的 id，内容跟随平台切换。
     */
    suspend fun search(
        query: String,
        page: Int = 1,
        count: Int = 20,
        platform: String = "wy",
    ): List<Track> {
        val lx = normalizeSource(platform).ifBlank { "wy" }
        if (lx != "wy") return PlatformMusicApi.search(lx, query, page, count)
        // v0.218.0（S16）：删走不到的 GD 兜底——非 wy 早已 return，wy 又恒在
        // GD_SUPPORTED_PLATFORMS（setOf("wy")）里，这段恒为假。
        val q = URLEncoder.encode(query, "UTF-8")
        val body = get("$BASE?types=search&source=${gdSourceOf(lx)}&name=$q&count=$count&pages=$page")
        val tracks = mutableListOf<Track>()
        runCatching {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val artists = o.optJSONArray("artist")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
                }.orEmpty()
                tracks += Track(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    artist = artists.joinToString("、").ifBlank { "未知歌手" },
                    album = o.optString("album"),
                    picId = o.optString("pic_id"),
                    urlId = o.optString("url_id"),
                    // v0.126.0：来源标实际搜索平台（LX code），不再恒 wy
                    source = lx,
                )
            }
        }.onFailure {
            LogBus.w(TAG, "音乐搜索解析失败：${it.message}")
        }
        LogBus.i(TAG, "音乐搜索「$query」：${tracks.size} 条")
        return tracks
    }

    /** v0.127.0：按平台取榜单列表（在线页内容跟随所选平台）。 */
    suspend fun charts(platform: String): List<ChartEntry> {
        val lx = normalizeSource(platform).ifBlank { "wy" }
        return if (lx == "wy") WY_CHARTS else PlatformMusicApi.charts(lx)
    }

    /** v0.127.0：按平台取榜单曲目；wy 复用网易歌单解析，其余走各家原生接口。 */
    suspend fun chartTracks(platform: String, chartId: String): List<Track> {
        val lx = normalizeSource(platform).ifBlank { "wy" }
        return if (lx == "wy") playlist(chartId).second
        else PlatformMusicApi.chartTracks(lx, chartId)
    }

    /** v0.82.0：歌单导入——网易云歌单 ID/链接 → 歌单名 + 曲目列表。 */
    suspend fun playlist(idOrUrl: String): Pair<String, List<Track>> {
        // v0.107.0 P0-8：优先取 id= 参数——直接取首个数字串会把 music.163.com 里的 "163" 当成歌单 ID
        val id = Regex("[?&]id=(\\d+)").find(idOrUrl)?.groupValues?.get(1)
            ?: Regex("[0-9]+").find(idOrUrl)?.value
            ?: throw IllegalStateException("没识别出歌单 ID")
        // 歌单导入仍是网易云歌单（GD playlist 仅 netease），源写 gdSourceOf("wy")
        val body = get("$BASE?types=playlist&source=${gdSourceOf("wy")}&id=$id")
        val tracks = mutableListOf<Track>()
        val name = runCatching {
            val root = JSONObject(body)
            val pl = root.getJSONObject("playlist")
            val arr = pl.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sid = o.optLong("id", 0L).toString()
                if (sid == "0") continue
                val ar = o.optJSONArray("ar")
                val artists = (0 until (ar?.length() ?: 0))
                    .mapNotNull { ar!!.optJSONObject(it)?.optString("name")?.ifBlank { null } }
                val al = o.optJSONObject("al")
                tracks += Track(
                    id = sid,
                    name = o.optString("name").ifBlank { "未知曲目" },
                    artist = artists.joinToString("、").ifBlank { "未知歌手" },
                    album = al?.optString("name").orEmpty(),
                    picId = al?.optString("pic_str").orEmpty(),
                    urlId = sid,
                    source = "wy",
                )
            }
            pl.optString("name").ifBlank { "歌单 $id" }
        }.getOrElse { throw IllegalStateException("歌单解析失败") }
        if (tracks.isEmpty()) throw IllegalStateException("歌单是空的或已下架")
        LogBus.i(TAG, "歌单导入成功")
        return name to tracks
    }

    /** v0.83.0：单行歌词 */
    data class LyricLine(val timeMs: Long, val text: String)

    /** v0.83.0：拉歌词（LRC）并解析；无歌词返回空列表。
     *  v0.126.0：按曲目自身平台取词（GD 不支持的平台直接空表，不发注定失败的请求）。 */
    suspend fun lyric(track: Track): List<LyricLine> {
        val lid = track.urlId.ifBlank { return emptyList() }
        val lx = normalizeSource(track.source).ifBlank { "wy" }
        if (lx !in GD_SUPPORTED_PLATFORMS) return emptyList()
        val body = runCatching {
            get("$BASE?types=lyric&source=${gdSourceOf(lx)}&id=$lid")
        }.getOrNull() ?: return emptyList()
        val raw = runCatching { JSONObject(body).optString("lyric") }.getOrDefault("")
        if (raw.isBlank()) return emptyList()
        val pat = Regex("""\[(\d+):(\d+)(?:\.(\d+))?\]""")
        val lines = mutableListOf<LyricLine>()
        for (ln in raw.lineSequence()) {
            // v0.99.0 P1-10：一行多个时间戳（如 [00:12.00][00:25.00]歌词）都要保留
            val matches = pat.findAll(ln).toList()
            if (matches.isEmpty()) continue
            val text = ln.substring(matches.last().range.last + 1).trim()
            if (text.isBlank()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val cs = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                lines += LyricLine(min * 60000 + sec * 1000 + cs, text)
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /** 取可播放地址（url_id → 直链）。v0.126.0：按平台（LX code）取链。 */
    suspend fun playUrl(urlId: String, platform: String = "wy"): String {
        val lx = normalizeSource(platform).ifBlank { "wy" }
        if (lx !in GD_SUPPORTED_PLATFORMS) {
            throw IllegalStateException("GD 不支持平台 $lx 取链")
        }
        val body = get("$BASE?types=url&source=${gdSourceOf(lx)}&id=$urlId")
        val url = runCatching { JSONObject(body).optString("url") }.getOrDefault("")
        if (url.isBlank()) throw IllegalStateException("未取到播放地址")
        return url
    }

    /**
     * v0.89.0：封面真实图片地址。
     *
     * types=pic 实际返回 {"url": "https://p2.music.126.net/...jpg"}（包一层，不是直出图片），
     * 这里解析一次；结果进内存缓存，列表滚动不反复请求；失败返回 ""。
     *
     * v0.125.0 补充B：真机日志里同一 id 几分钟内被重复请求十余次——成功结果本就
     * 缓存（v0.89.0 起），重复的全是没进缓存的：GD 503（约五成）与 200 但 url 为
     * 空，加上多行同时组合时的并发重复。这里收敛为全 App 一处取图入口：
     * ①成功结果按 source:id 缓存（只存 URL 字符串，图片本体走 Coil 磁盘缓存）；
     * ②同一 id 的在途请求合并成一次（Deferred 表），并发调用共享结果；
     * ③失败/空结果不缓存结果，但记 30 秒退避窗，避免列表重组时无间隔反复打
     * 接口，退避过后照常重试。
     */
    private val picCache = mutableMapOf<String, String>()
    private val picInFlight = mutableMapOf<String, Deferred<String>>()
    private val picFailAt = mutableMapOf<String, Long>()
    private const val PIC_FAIL_BACKOFF_MS = 30_000L
    private val picScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** v0.126.0：封面按平台（LX code）解析；缓存键含 GD source，跨平台同 id 不串图。
     *  v0.127.0：picId 本身就是完整图片地址时直通（PlatformMusicApi 各家直出封面
     *  URL 存 picId，不经 GD 二次解析）。 */
    suspend fun picImageUrl(picId: String, platform: String = "wy"): String {
        if (picId.isBlank()) return ""
        if (picId.startsWith("http://") || picId.startsWith("https://")) return picId
        val lx = normalizeSource(platform).ifBlank { "wy" }
        if (lx !in GD_SUPPORTED_PLATFORMS) return ""
        val key = "${gdSourceOf(lx)}:$picId"
        synchronized(picCache) { picCache[key]?.let { return it } }
        val deferred: Deferred<String>
        synchronized(picInFlight) {
            val existing = picInFlight[key]
            if (existing != null) {
                deferred = existing
            } else {
                val lastFail = synchronized(picFailAt) { picFailAt[key] } ?: 0L
                if (System.currentTimeMillis() - lastFail < PIC_FAIL_BACKOFF_MS) return ""
                deferred = picScope.async {
                    runCatching {
                        JSONObject(get("$BASE?types=pic&source=${gdSourceOf(lx)}&id=$picId"))
                            .optString("url")
                    }.getOrDefault("")
                }
                picInFlight[key] = deferred
            }
        }
        val url = deferred.await()
        synchronized(picInFlight) {
            if (picInFlight[key] === deferred) picInFlight.remove(key)
        }
        if (url.isNotBlank()) {
            synchronized(picCache) { picCache[key] = url }
            synchronized(picFailAt) { picFailAt.remove(key) }
        } else {
            synchronized(picFailAt) { picFailAt[key] = System.currentTimeMillis() }
        }
        return url
    }
}
