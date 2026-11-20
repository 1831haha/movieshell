package com.movieshell.data.source

import com.movieshell.core.log.LogBus
import com.movieshell.data.source.CmsApi
import com.movieshell.core.net.DEFAULT_UA_WEBKIT
import com.movieshell.core.net.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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

    // v1.4.2：封面解析走独立 Dispatcher 的 picClient（见 HttpClient.picClient），
    // 不再跟 playlist 请求抢同一 host 的 5 路并发。
    private val picHttp = HttpClient.picClient()

    // v1.4.2：client 参数——封面解析走 picClient，其余走共享 client。
    private suspend fun get(url: String, client: OkHttpClient = http): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA_WEBKIT)
            .build()
        client.newCall(req).execute().use { resp ->
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
        // v1.4.2：分段耗时日志——1.4.1 实证响应 200 后约 3–4 秒才打“导入成功”，
        // 先定位 gap 在传输还是解析，再决定优化哪边。
        val reqStartMs = System.currentTimeMillis()
        val body = get("$BASE?types=playlist&source=${gdSourceOf("wy")}&id=$id")
        val reqEndMs = System.currentTimeMillis()
        LogBus.d(TAG, "歌单 $id 请求耗时 ${reqEndMs - reqStartMs}ms，响应 ${body.length} 字节")
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
        LogBus.d(TAG, "歌单 $id 解析耗时 ${System.currentTimeMillis() - reqEndMs}ms，${tracks.size} 首")
        if (tracks.isEmpty()) throw IllegalStateException("歌单是空的或已下架")
        LogBus.i(TAG, "歌单导入成功")
        return name to tracks
    }

    /** v0.83.0：单行歌词 */
    data class LyricLine(val timeMs: Long, val text: String)

    /**
     * v0.83.0：拉歌词（LRC）并解析；无歌词返回空列表。
     * v0.126.0：按曲目自身平台取词（GD 不支持的平台不发注定失败的请求）。
     * v0.294.16：根因——GD 歌词只支持 wy，tx/kw/kg/mg 以前直接空表，QQ/墨澜等
     * 源播放时歌词区恒「暂无歌词」。现路径：
     * ① wy → GD types=lyric；tx → QQ 公开歌词接口（songmid）；
     * ② 仍空则按歌名+歌手在网易云搜同曲（[pickSameSong]），再走 GD 取词。
     * 本地曲 / 无歌名时不搜索。
     */
    suspend fun lyric(track: Track): List<LyricLine> {
        if (track.isLocal) return emptyList()
        val direct = lyricDirect(track)
        if (direct.isNotEmpty()) return direct
        return lyricByTitleArtistFallback(track)
    }

    /** v0.294.16：按曲目自身平台 id 取词（失败空表，不抛）。 */
    private suspend fun lyricDirect(track: Track): List<LyricLine> {
        val lid = track.urlId.ifBlank { return emptyList() }
        val lx = normalizeSource(track.source).ifBlank { "wy" }
        val raw = when (lx) {
            "wy" -> lyricRawFromGd(lid)
            "tx" -> PlatformMusicApi.lyricRawTx(lid)
            else -> ""
        }
        return parseLrc(raw)
    }

    /**
     * v0.294.16：歌名+歌手跨源兜底——在 GD 可出词的网易云上搜同曲再取 LRC。
     * 与换平台播放共用 [pickSameSong]，避免误配成别的歌。
     */
    private suspend fun lyricByTitleArtistFallback(track: Track): List<LyricLine> {
        val name = track.name.trim()
        if (name.isBlank()) return emptyList()
        val q = listOf(name, track.artist.trim()).filter { it.isNotBlank() }.joinToString(" ")
        val candidates = runCatching {
            search(q, count = 10, platform = "wy")
        }.onFailure {
            LogBus.w(TAG, "歌词兜底搜索失败：${it.message}")
        }.getOrDefault(emptyList())
        val hit = pickSameSong(track, candidates)
        if (hit == null) {
            LogBus.i(TAG, "歌词兜底未匹配：${track.name} / ${track.artist}")
            return emptyList()
        }
        val lines = parseLrc(lyricRawFromGd(hit.urlId))
        if (lines.isNotEmpty()) {
            LogBus.i(TAG, "歌词兜底命中网易云 id=${hit.urlId}（${lines.size} 行）")
        }
        return lines
    }

    /** v0.294.16：GD types=lyric 原始 LRC 文本；失败空串。 */
    private suspend fun lyricRawFromGd(id: String): String {
        if (id.isBlank()) return ""
        val body = runCatching {
            get("$BASE?types=lyric&source=${gdSourceOf("wy")}&id=$id")
        }.getOrNull() ?: return ""
        return runCatching { JSONObject(body).optString("lyric") }.getOrDefault("")
    }

    /**
     * v0.83.0 / v0.99.0 P1-10 / v0.294.16：LRC 文本 → 行列表。
     * 一行多个时间戳都保留；无时间戳或空正文跳过。公开给单测。
     */
    fun parseLrc(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val pat = Regex("""\[(\d+):(\d+)(?:\.(\d+))?\]""")
        val lines = mutableListOf<LyricLine>()
        for (ln in raw.lineSequence()) {
            val matches = pat.findAll(ln).toList()
            if (matches.isEmpty()) continue
            val text = ln.substring(matches.last().range.last + 1).trim()
            if (text.isBlank()) continue
            for (m in matches) {
                // v1.3.33 B-P2-1：min/sec 同样可失败解析——一行坏时间戳只跳过
                // 该时间戳，不再整首无歌词；sec 钳 0–59；溢出换算直接丢弃该行。
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toLongOrNull() ?: continue
                if (sec !in 0..59) continue
                val cs = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                val timeMs = lrcTimeMs(min, sec, cs) ?: continue
                lines += LyricLine(timeMs, text)
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /**
     * v1.3.33 B-P2-1：LRC 时间戳换算（分:秒.毫秒 → ms），溢出返回 null。
     * 只换 toLongOrNull 不够——合法 Long 也可能在 min*60000 时回绕，
     * 先判 min 上限再乘（sec ≤ 59、cs ≤ 999，余量已留足）。纯函数，供单测。
     */
    internal fun lrcTimeMs(min: Long, sec: Long, cs: Long): Long? {
        if (min < 0 || min > (Long.MAX_VALUE - 60_000L) / 60_000L) return null
        return min * 60_000L + sec * 1_000L + cs
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
    // v1.4.2：失败退避截止时间（原 picFailAt 记“上次失败时刻”，现记“下次可重试时刻”，
    // 退避窗口按失败类型分档——见 picImageUrl）。
    private val picFailUntil = mutableMapOf<String, Long>()
    private const val PIC_FAIL_BACKOFF_MS = 30_000L
    // v1.4.2：HTTP 错误（503 等服务端拒绝）退避 5 分钟——服务端自己说不行时，
    // 30 秒后重试大概率还是 503，列表滚动/重组只会反复续命。
    private const val PIC_HTTP_FAIL_BACKOFF_MS = 300_000L
    // v1.4.2：封面并发节流——45 首各打一次时最多 4 路在途，别把 GD 接口与
    // 同 host 的 playlist 请求一起冲垮（配 picClient 的独立 Dispatcher 双保险）。
    private val picSemaphore = Semaphore(4)
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
                val failUntil = synchronized(picFailUntil) { picFailUntil[key] } ?: 0L
                if (System.currentTimeMillis() < failUntil) return ""
                deferred = picScope.async {
                    picSemaphore.withPermit {
                        runCatching {
                            JSONObject(get("$BASE?types=pic&source=${gdSourceOf(lx)}&id=$picId", picHttp))
                                .optString("url")
                        }.fold(
                            onSuccess = { it },
                            onFailure = { e ->
                                // 失败退避按类型分档：HTTP 错误（get() 对非 2xx 抛
                                // IllegalStateException，含 503）退避 5 分钟，
                                // IO 异常（超时/断连）退避 30 秒。
                                val window = if (e is IllegalStateException) PIC_HTTP_FAIL_BACKOFF_MS else PIC_FAIL_BACKOFF_MS
                                synchronized(picFailUntil) { picFailUntil[key] = System.currentTimeMillis() + window }
                                ""
                            },
                        )
                    }
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
            synchronized(picFailUntil) { picFailUntil.remove(key) }
        }
        // 空结果的退避截止时间已在请求协程内按失败类型记下，这里不再覆写。
        return url
    }
}
