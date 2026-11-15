package com.movieshell.data.source

import android.content.Context
import com.movieshell.core.log.LogBus
import com.movieshell.core.net.HttpClient
import com.movieshell.core.net.ioSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * v1.2.13：TMDB 海报匹配（本地文件夹海报墙用）。
 *
 * 只做三件事：文件名解析成片名加年份、调 TMDB 搜索取第一条海报、按文件名缓存匹配结果。
 * 不做：剧集分集匹配（同一剧多集只按剧名取一张海报）、海报图本地落盘（Coil 自己缓存）。
 * 搜索不到、key 为空、断网都只返回空结果，不抛错、不弹错，调用方直接显示占位。
 */
object TmdbClient {

    private const val TAG = "TmdbClient"

    private const val SEARCH_BASE = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

    private const val PREFS = "tmdb_prefs"
    private const val KEY_API_KEY = "tmdb_api_key"
    private const val KEY_POSTER_VIEW = "openlist_poster_view"
    private const val CACHE_PREFS = "tmdb_cache_prefs"
    private const val KEY_CACHE = "cache_json"
    private const val MAX_CACHE = 200

    private val jsonLenient = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** 一条匹配结果：posterPath 为空表示搜过但无海报（占位显示用）。 */
    @Serializable
    data class TmdbMatch(
        val posterPath: String = "",
        val title: String = "",
        val year: Int? = null,
        val cachedAt: Long = 0L,
        /** v1.2.13：是否在 TMDB 搜到条目（false = 搜过但无结果，占位显示“未找到”）。 */
        val found: Boolean = true,
    )

    /** 文件名解析结果。 */
    data class ParsedFileName(
        val title: String,
        val year: Int?,
    )

    @Serializable
    private data class TmdbSearchItem(
        val poster_path: String? = null,
        val title: String? = null,
        val name: String? = null,
        val release_date: String? = null,
        val first_air_date: String? = null,
    )

    @Serializable
    private data class TmdbSearchResponse(
        val results: List<TmdbSearchItem> = emptyList(),
    )

    // ---------------- Key 与视图偏好（设置页覆盖用） ----------------

    /** 当前生效的 key：没填过返回空串（海报墙不联网，只显示占位）；存过空串表示用户已清空（不联网）。 */
    fun apiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_API_KEY)) prefs.getString(KEY_API_KEY, "").orEmpty() else ""
    }

    /** 设置页存的原始值：null=没填过，空串=用户已清空。 */
    fun storedApiKey(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_API_KEY)) prefs.getString(KEY_API_KEY, "").orEmpty() else null
    }

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_API_KEY, key.trim()).apply()
    }

    /** 海报墙视图偏好（默认列表，记住选择）。 */
    fun isPosterView(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_POSTER_VIEW, false)

    fun setPosterView(context: Context, poster: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_POSTER_VIEW, poster).apply()
    }

    /** 海报地址（w500 宽，列表与海报墙共用）。 */
    fun posterUrl(posterPath: String): String = "$IMAGE_BASE$posterPath"

    // ---------------- 文件名解析（纯函数，可单测） ----------------

    private val YEAR_RE = Regex("(?<![0-9])(19\\d\\d|20\\d\\d)(?![0-9])")
    private val BRACKET_RE = Regex("\\[[^\\]]*\\]|\\([^\\)]*\\)|【[^】]*】")
    private val SEPARATOR_RE = Regex("[._\\-－—]+")
    private val EPISODE_RE = Regex(
        "(?i)\\bS\\d{1,2}E\\d{1,2}\\b|\\bS\\d{1,2}\\b|\\bE\\d{1,2}\\b|\\bEP\\d{1,2}\\b" +
            "|第\\d{1,3}[集季]|更新至第?\\d{1,3}集",
    )
    private val JUNK_TOKEN_RES = Regex("^(\\d+p|\\d+k|x26[45]|h\\.?26[45]|\\d+bit)$")

    /** 画质、格式、来源类词：整词命中才去掉（标题里的真词不受影响）。 */
    private val JUNK_TOKENS = setOf(
        "1080", "720", "2160", "1440", "480", "360", "4320",
        "bluray", "web-dl", "webdl", "webrip", "hdtv", "hdrip", "dvdrip", "brrip",
        "hevc", "avc", "hdr", "hdr10", "dolby", "dts", "aac", "ac3", "truehd", "atmos",
        "remux", "proper", "repack", "extended", "uncut", "limited", "internal",
        "dual", "audio", "3d", "sbs", "xvid", "divx", "web", "dl", "rip", "bd", "hd",
        "uhd", "fhd", "sd", "cam", "ts", "tc", "dvd", "complete", "season", "episode",
        "全集", "完结", "高清", "超清", "蓝光", "国语", "粤语", "中字", "内嵌",
        "双语", "无字幕", "中英", "繁中", "简中",
    )

    /**
     * 文件名解析成片名加年份。解析不出片名（只剩年份、集数、画质词）返回 null，
     * 调用方按原文件名显示、不请求。
     * 年份取最后一个 19xx/20xx（如 1942 那部 2012 年上映的片，取 2012）。
     */
    fun parseFileName(fileName: String): ParsedFileName? {
        var name = fileName.substringAfterLast("/").substringBeforeLast(".")
        if (name.isBlank()) return null
        var year: Int? = null
        val yearMatch = YEAR_RE.findAll(name).lastOrNull()
        if (yearMatch != null) {
            val y = yearMatch.value.toIntOrNull()
            if (y != null && y in 1900..2030) {
                year = y
                name = name.removeRange(yearMatch.range)
            }
        }
        name = BRACKET_RE.replace(name, " ")
        name = SEPARATOR_RE.replace(name, " ")
        name = EPISODE_RE.replace(name, " ")
        val title = name.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .filter { token ->
                val lower = token.lowercase()
                lower !in JUNK_TOKENS && !JUNK_TOKEN_RES.matches(lower)
            }
            .joinToString(" ")
            .trim()
        if (title.isBlank()) return null
        return ParsedFileName(title, year)
    }

    // ---------------- 搜索 ----------------

    /** 先搜电影，无结果再搜剧；取第一条带海报的结果，没有带海报的取第一条。 */
    suspend fun searchMatch(title: String, year: Int?, apiKey: String): TmdbMatch? {
        if (apiKey.isBlank() || title.isBlank()) return null
        searchOne("movie", title, year, apiKey)?.let { return it }
        return searchOne("tv", title, year, apiKey)
    }

    private suspend fun searchOne(kind: String, title: String, year: Int?, apiKey: String): TmdbMatch? =
        ioSafe(TAG, null) {
            val url = "$SEARCH_BASE/search/$kind".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("api_key", apiKey)
                ?.addQueryParameter("language", "zh-CN")
                ?.addQueryParameter("query", title)
                ?.apply {
                    if (year != null) {
                        addQueryParameter(
                            if (kind == "movie") "primary_release_year" else "first_air_date_year",
                            year.toString(),
                        )
                    }
                }
                ?.build() ?: return@ioSafe null
            val req = Request.Builder().url(url).get().build()
            val text = withContext(Dispatchers.IO) {
                HttpClient.defaultClient().newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()
                }
            } ?: return@ioSafe null
            val resp = jsonLenient.decodeFromString<TmdbSearchResponse>(text)
            if (resp.results.isEmpty()) return@ioSafe null
            val item = resp.results.firstOrNull { !it.poster_path.isNullOrBlank() } ?: resp.results[0]
            val date = if (kind == "movie") item.release_date else item.first_air_date
            TmdbMatch(
                posterPath = item.poster_path.orEmpty(),
                title = item.title ?: item.name.orEmpty(),
                year = date?.take(4)?.toIntOrNull(),
                cachedAt = System.currentTimeMillis(),
            )
        }

    // ---------------- 缓存（内存外再加一份本地 prefs，按文件名做 key） ----------------

    /** 读全部缓存（文件名到匹配结果）。 */
    fun loadCache(context: Context): MutableMap<String, TmdbMatch> {
        val raw = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CACHE, null) ?: return mutableMapOf()
        return runCatching {
            jsonLenient.decodeFromString(
                MapSerializer(String.serializer(), TmdbMatch.serializer()),
                raw,
            ).toMutableMap()
        }.onFailure { LogBus.w(TAG, "TMDB 缓存解析失败，清空重建") }
            .getOrDefault(mutableMapOf())
    }

    /** 合并写入：新结果覆盖旧的，超 200 条按缓存时间淘汰最旧的。 */
    fun putCache(context: Context, entries: Map<String, TmdbMatch>) {
        if (entries.isEmpty()) return
        val cache = loadCache(context)
        cache.putAll(entries)
        while (cache.size > MAX_CACHE) {
            val oldest = cache.minByOrNull { it.value.cachedAt }?.key ?: break
            cache.remove(oldest)
        }
        runCatching {
            val text = jsonLenient.encodeToString(
                MapSerializer(String.serializer(), TmdbMatch.serializer()),
                cache,
            )
            context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_CACHE, text).apply()
        }.onFailure { LogBus.w(TAG, "TMDB 缓存写盘失败 ${it.javaClass.simpleName}: ${it.message}") }
    }
}
