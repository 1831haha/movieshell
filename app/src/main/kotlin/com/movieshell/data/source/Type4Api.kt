package com.movieshell.data.source

import android.util.Log
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.repo.SiteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * type=4 播放请求的返回。
 * @param url 直链地址（parse=1 时是待解析的中间地址，见 [SiteRepository.type4PlayWithParses] 走订阅 parses 解析）
 * @param parse 0=直链直接播；1=需走订阅 parses 解析链路
 * @param header 播放 header 白名单（User-Agent/Referer/Origin/Cookie），调用方经 SniffPlayHeaders 透传给播放器
 */
data class Type4PlayResult(
    val url: String = "",
    val parse: Int = 0,
    val header: Map<String, String> = emptyMap()
)

/**
 * type=4 PHP 站源客户端（TVBox type=4，api 是 PHP 脚本的 HTTP 地址）。
 *
 * PHP 跑在远端或本机 php -S 服务里，壳子只发 HTTP 请求、解析 JSON，
 * 不需要在 App 里跑 PHP。请求映射（docs/type4-protocol.md §8 定稿）：
 *   首页   GET {api}                          -> {class:[{type_id,type_name}], list:[vod...]}
 *   分类   GET {api}?ac=detail&t={tid}&pg={n} -> {list,page,pagecount,limit,total}
 *   搜索   GET {api}?wd={key}&pg={n}          -> {list}
 *   详情   GET {api}?ac=detail&ids={vodId}    -> {list:[vod_detail]}
 *   播放   GET {api}?ac=play&flag={f}&id={id}  -> {parse:0|1,url,header}
 *          （无效时回退 GET {api}?play={id}，模式 B 独立文件只认后者）
 *
 * 约定：{code:500,msg} 按空结果处理；vod 字段与播放串拼装（$$$/ # / $）与苹果 CMS
 * 一致，详情的剧集切分复用 CmsApi.parsePlayUrl（经由 SiteRepository.parseEpisodes）。
 * 所有请求走 runCatching，失败返回空结果，不向上传播异常。
 */
class Type4Api(
    private val client: OkHttpClient = CmsApi.defaultClient()
) {

    companion object {
        private const val TAG = "Type4Api"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        /** 播放 header 白名单（小写比对）：只透传这四个，其余丢弃。 */
        private val headerAllow = setOf("user-agent", "referer", "origin", "cookie")
    }

    // ---------------- 对外接口（5 个） ----------------

    /** 首页：分类 + 推荐列表（一次 GET {api}）。 */
    suspend fun home(site: Site): List<Vod> = safe(emptyList()) {
        val url = buildUrl(site) ?: return@safe emptyList<Vod>()
        parseHome(getText(url)).second
    }

    /**
     * 分类：返回 (分类列表, 当前分类第 pg 页)。
     * 分类列表取自首页响应的 class 数组（PHP 站无独立取分类接口）。
     */
    suspend fun category(site: Site, tid: String, pg: Int): Pair<List<CategoryType>, List<Vod>> =
        safe(emptyList<CategoryType>() to emptyList()) {
            val homeUrl = buildUrl(site)
                ?: return@safe (emptyList<CategoryType>() to emptyList())
            val types = parseHome(getText(homeUrl)).first
            if (tid.isBlank()) return@safe types to emptyList()
            val url = buildUrl(site, "ac" to "detail", "t" to tid, "pg" to pg.toString())
                ?: return@safe types to emptyList()
            types to parseCategory(getText(url))
        }

    /** 搜索。 */
    suspend fun search(site: Site, wd: String): List<Vod> = safe(emptyList()) {
        if (wd.isBlank()) return@safe emptyList<Vod>()
        val url = buildUrl(site, "wd" to wd, "pg" to "1")
            ?: return@safe emptyList<Vod>()
        parseSearch(getText(url))
    }

    /** 详情：取 list[0]。失败返回仅带 vodId 的空详情。 */
    suspend fun detail(site: Site, vodId: String): VodDetail = safe(VodDetail(vodId = vodId)) {
        val url = buildUrl(site, "ac" to "detail", "ids" to vodId)
            ?: return@safe VodDetail(vodId = vodId)
        parseDetail(getText(url), vodId)
    }

    /**
     * 播放双试探：
     *   先 GET {api}?ac=play&flag={flag}&id={id}；
     *   若返回不是有效播放响应（含 class 落回首页、url 为空或解析失败），
     *   再发 GET {api}?play={urlEncode(id)}。
     * 模式 A（BaseSpider::run）两种参数都认，第一次即中；模式 B（独立文件）
     * 只认 ?play=，第一次会落回首页、第二次命中。
     */
    suspend fun play(site: Site, flag: String, id: String): Type4PlayResult =
        safe(Type4PlayResult()) {
            val first = buildUrl(site, "ac" to "play", "flag" to flag, "id" to id)
                ?.let { parsePlay(getText(it)) }
            if (first?.isPlayResponse() == true) return@safe first
            val second = buildUrl(site, "play" to id) ?: return@safe Type4PlayResult()
            parsePlay(getText(second))
        }

    // ---------------- 纯解析函数（输入 String 输出模型，便于测试） ----------------

    /** 首页 text -> (分类, 推荐列表)。{code:500} 或解析失败返回双空。 */
    fun parseHome(text: String): Pair<List<CategoryType>, List<Vod>> = runCatching {
        val root = text.toRootObject()?.takeUnless { it.isError() }
            ?: return emptyList<CategoryType>() to emptyList()
        val types = (root["class"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .map { o -> CategoryType(o.optString("type_id"), o.optString("type_name")) }
            .filter { it.typeId.isNotBlank() }
        val list = root.listArray().map { it.jsonObject.toVod() }
        types to list
    }.getOrDefault(emptyList<CategoryType>() to emptyList())

    /** 分类/搜索 text -> 影片列表。{code:500} 或解析失败返回空。 */
    fun parseCategory(text: String): List<Vod> = parseSearch(text)

    /** 搜索 text -> 影片列表。 */
    fun parseSearch(text: String): List<Vod> = runCatching {
        val root = text.toRootObject()?.takeUnless { it.isError() }
            ?: return emptyList()
        root.listArray().map { it.jsonObject.toVod() }
    }.getOrDefault(emptyList())

    /** 详情 text -> VodDetail（取 list[0]）。失败返回仅带 vodId 的空详情。 */
    fun parseDetail(text: String, vodId: String): VodDetail = runCatching {
        val first = text.toRootObject()?.takeUnless { it.isError() }
            ?.listArray()?.firstOrNull()?.jsonObject
            ?: return VodDetail(vodId = vodId)
        first.toVodDetail()
    }.getOrDefault(VodDetail(vodId = vodId))

    /** 播放 text -> (直链, parse 标记, 白名单 header)。 */
    fun parsePlay(text: String): Type4PlayResult = runCatching {
        val root = text.toRootObject()?.takeUnless { it.isError() }
            ?: return Type4PlayResult()
        val header = (root["header"] as? JsonObject)?.entries
            ?.mapNotNull { (k, v) ->
                val value = (v as? JsonPrimitive)
                    ?.takeUnless { it is JsonNull }
                    ?.contentOrNull?.trim().orEmpty()
                if (k.lowercase() in headerAllow && value.isNotEmpty()) k to value else null
            }
            ?.toMap().orEmpty()
        Type4PlayResult(
            url = root.optString("url").trim(),
            parse = root.optInt("parse") ?: 0,
            header = header
        )
    }.getOrDefault(Type4PlayResult())

    /** 有效播放响应：url 非空；或显含 parse 字段（parse=1 走解析链路时 url 可为空）。 */
    private fun Type4PlayResult.isPlayResponse(): Boolean = url.isNotBlank() || parse != 0

    // ---------------- 内部：请求 ----------------

    /**
     * api 规范化：先按原样解析；失败（如含中文/空格/ᵈᶻ）则把 path 段逐段
     * percent-encode 后再拼（保留 scheme/host），确保
     * "http://127.0.0.1:19978/人人影视 ᵈᶻ.php" 这类地址能发出去。
     */
    internal fun normalizeApi(api: String): HttpUrl? {
        val trimmed = api.trim()
        trimmed.toHttpUrlOrNull()?.let { return it }
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return null
        val pathStart = trimmed.indexOf('/', schemeEnd + 3)
        val head = if (pathStart < 0) trimmed else trimmed.substring(0, pathStart)
        val rawPath = if (pathStart < 0) "" else trimmed.substring(pathStart)
        val encodedPath = rawPath.split('/').joinToString("/") { encodePathSegment(it) }
        return (head + encodedPath).toHttpUrlOrNull()
    }

    /** 单个 path 段编码：已存在的 %XX 保留不二次编码；空格等转 %20。 */
    private fun encodePathSegment(seg: String): String {
        if (seg.isEmpty()) return seg
        val sb = StringBuilder(seg.length)
        Regex("%[0-9A-Fa-f]{2}|[^%]").findAll(seg).forEach { m ->
            val t = m.value
            if (t.startsWith("%")) sb.append(t.uppercase())
            else sb.append(URLEncoder.encode(t, Charsets.UTF_8.name()).replace("+", "%20"))
        }
        return sb.toString()
    }

    private fun buildUrl(site: Site, vararg params: Pair<String, String>): HttpUrl? {
        val base = normalizeApi(site.api) ?: return null
        val b = base.newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    @Throws(IOException::class)
    private fun getText(url: HttpUrl): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${resp.message}")
            return resp.body?.string().orEmpty()
        }
    }

    private suspend fun <T> safe(fallback: T, block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "request failed: ${e.message}")
                fallback
            }
        }

    // ---------------- 内部：JSON 解析 helpers ----------------

    private fun String.toRootObject(): JsonObject? = runCatching {
        json.parseToJsonElement(this).jsonObject
    }.getOrNull()

    /** 错误包络 {code:500,msg}（code 兼容数字/字符串写法）。 */
    private fun JsonObject.isError(): Boolean = optInt("code") == 500

    private fun JsonObject.listArray(): JsonArray =
        (this["list"] as? JsonArray) ?: JsonArray(emptyList())

    private fun JsonObject.toVod(): Vod = Vod(
        vodId = optString("vod_id"),
        vodName = optString("vod_name"),
        vodPic = optString("vod_pic").fixPic(),
        vodRemarks = optString("vod_remarks"),
        vodYear = optString("vod_year"),
        typeName = optString("type_name")
    )

    private fun JsonObject.toVodDetail(): VodDetail = VodDetail(
        vodId = optString("vod_id"),
        vodName = optString("vod_name"),
        vodPic = optString("vod_pic").fixPic(),
        vodRemarks = optString("vod_remarks"),
        vodYear = optString("vod_year"),
        typeName = optString("type_name"),
        vodDesc = optString("vod_content").stripHtml(),
        vodActor = optString("vod_actor"),
        vodDirector = optString("vod_director"),
        vodPlayFrom = optString("vod_play_from"),
        vodPlayUrl = optString("vod_play_url")
    )

    /** 兼容字段值为数字 / 布尔 / 字符串的写法；缺失或 null 返回空串。 */
    private fun JsonObject.optString(key: String): String {
        val prim = (this[key] as? JsonPrimitive) ?: return ""
        if (prim is JsonNull) return ""
        return prim.contentOrNull.orEmpty()
    }

    /** 兼容 Int / "500" / true 等写法；缺失或 null 返回 null。 */
    private fun JsonObject.optInt(key: String): Int? {
        val prim = (this[key] as? JsonPrimitive) ?: return null
        if (prim is JsonNull) return null
        prim.intOrNull?.let { return it }
        return prim.contentOrNull?.toIntOrNull()
    }

    /** pic 以 // 开头补 https:。 */
    private fun String.fixPic(): String = if (startsWith("//")) "https:$this" else this

    private fun String.stripHtml(): String =
        replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()
}
