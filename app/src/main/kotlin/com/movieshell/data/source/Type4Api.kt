package com.movieshell.data.source

import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.SiteRepository
import kotlinx.coroutines.CancellationException
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
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

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
 * type=4 搜索返回。
 * @param total 上游 total 字段（有才透出，无则为 null，不编造）
 * @param list 本页影片列表
 */
data class Type4SearchPage(
    val total: Int? = null,
    val list: List<Vod> = emptyList()
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

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        /** 播放 header 白名单（小写比对）：只透传这四个，其余丢弃。 */
        private val headerAllow = setOf("user-agent", "referer", "origin", "cookie")

        /** 站点记忆键：key 为空时退回 api；拼接 api 使站点配置变化时记忆自动失效。 */
        private fun memKey(site: Site): String = "${site.key.ifBlank { site.api }}|${site.api}"

        /**
         * 分类列表内存缓存：memKey(site) -> types。PHP 站无独立取分类接口，
         * 每次切分类都要请求一次首页；缓存后同站切分类不再重复请求。
         * 只缓存非空结果（首页失败/包络错误时不污染缓存，下次重拉）。
         * v0.217.0（F59）：加时间戳窗口——原来永久不过期，订阅站后台改了
         * 分类要重启 App 才刷新；与 V8 首页缓存同档 60 秒。
         */
        private val typeCache = ConcurrentHashMap<String, Pair<Long, List<CategoryType>>>()
        private const val TYPE_CACHE_TTL_MS = 60_000L

        /**
         * 播放模式 B 记忆：memKey(site) -> true 表示该站已确认走 ?play= 独立分支，
         * 下次直接走第二分支，省掉第一次注定落空的 ac=play 试探。
         * 只有第二分支真正成功（有效播放响应）才标记；不改站点配置 JSON。
         */
        private val modeB = ConcurrentHashMap<String, Boolean>()
    }

    /**
     * v0.217.0（F54）：失败原因按站分流——原来单例一份 lastError，
     * 聚合搜索多 Type4 站并发时 A 站成功会把 B 站刚记下的失败原因清掉，
     * 失败计数忽多忽少；现在每个站各记各的，调用方按站 key 取。
     * v0.187.0 H04 起供调用方区分“源没数据”与“请求/解析失败”。
     */
    private val lastErrorBySite = ConcurrentHashMap<String, String>()

    /** 取某站最近一次调用的失败原因（null=该站最近一次成功或未跑过）。 */
    fun lastErrorFor(siteKey: String): String? = lastErrorBySite[siteKey]

    /**
     * 带失败原因的 ioSafe：与 SourceNetKit.ioSafe 同款语义（取消透传、其余异常交 fallback），
     * 但异常进应用内 LogBus 并记 [lastErrorFor]，不再只走 android.util.Log 且原因全丢。
     */
    private suspend fun <T> ioSafeTracked(siteKey: String, fallback: T, block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            lastErrorBySite.remove(siteKey)
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = "请求失败：${e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName}"
                lastErrorBySite[siteKey] = reason
                LogBus.w(TAG, reason)
                fallback
            }
        }

    /** 地址规范化失败时抛给 [ioSafeTracked]，由它记原因并交 fallback。 */
    private fun invalidApi(site: Site): Nothing =
        throw IllegalArgumentException("站源地址无效：${site.api}")

    // ---------------- 对外接口（5 个） ----------------

    /** 首页：分类 + 推荐列表（一次 GET {api}）。 */
    suspend fun home(site: Site): List<Vod> = ioSafeTracked(site.key, emptyList()) {
        val url = buildUrl(site) ?: invalidApi(site)
        parseHome(client.getText(url), site.key).second
    }

    /**
     * 分类：返回 (分类列表, 当前分类第 pg 页)。
     * 分类列表取自首页响应的 class 数组（PHP 站无独立取分类接口），按站做内存缓存：
     * 同一站切分类不再重复请求首页；切站或该站 api 变化时记忆键变化，自动失效重拉。
     */
    suspend fun category(site: Site, tid: String, pg: Int): Pair<List<CategoryType>, List<Vod>> =
        ioSafeTracked(site.key, emptyList<CategoryType>() to emptyList()) {
            val types = cachedTypes(site)
            if (tid.isBlank()) return@ioSafeTracked types to emptyList()
            val url = buildUrl(site, "ac" to "detail", "t" to tid, "pg" to pg.toString())
                ?: invalidApi(site)
            types to parseCategory(client.getText(url), site.key)
        }

    /** 分类列表内存缓存：命中且在 60 秒窗口内直接返回；过期重拉。 */
    private fun cachedTypes(site: Site): List<CategoryType> {
        val key = memKey(site)
        typeCache[key]?.let { (at, types) ->
            if (System.currentTimeMillis() - at < TYPE_CACHE_TTL_MS) return types
        }
        val homeUrl = buildUrl(site) ?: return emptyList()
        val types = parseHome(client.getText(homeUrl), site.key).first
        if (types.isNotEmpty()) typeCache[key] = System.currentTimeMillis() to types
        return types
    }

    /**
     * 搜索（分页就绪版）：pg 透传给上游；total 为上游 total 字段，无则为 null（不编造）。
     * 调用方（SiteRepository.search）目前只要列表；搜索页暂无分页 UI，UI 不动。
     */
    suspend fun searchPage(site: Site, wd: String, pg: Int = 1): Type4SearchPage =
        ioSafeTracked(site.key, Type4SearchPage()) {
            if (wd.isBlank()) return@ioSafeTracked Type4SearchPage()
            val url = buildUrl(site, "wd" to wd, "pg" to pg.toString())
                ?: invalidApi(site)
            parseSearchPage(client.getText(url), site.key)
        }

    /** 搜索：返回列表；需要总数/翻页时用 [searchPage]。 */
    suspend fun search(site: Site, wd: String, pg: Int = 1): List<Vod> =
        searchPage(site, wd, pg).list

    /** 详情：取 list[0]。失败返回仅带 vodId 的空详情。 */
    suspend fun detail(site: Site, vodId: String): VodDetail = ioSafeTracked(site.key, VodDetail(vodId = vodId)) {
        val url = buildUrl(site, "ac" to "detail", "ids" to vodId)
            ?: invalidApi(site)
        parseDetail(client.getText(url), vodId, site.key)
    }

    /**
     * 播放双试探（带模式记忆）：
     *   先 GET {api}?ac=play&flag={flag}&id={id}；
     *   若返回不是有效播放响应（含 class 落回首页、url 为空或解析失败），
     *   再发 GET {api}?play={urlEncode(id)}。
     * 模式 A（BaseSpider::run）两种参数都认，第一次即中；模式 B（独立文件）
     * 只认 ?play=，第一次会落回首页、第二次命中。一旦某站走过第二分支成功，
     * 记住它，下次直接走第二分支（不改站点配置 JSON，用户侧配置原样不动）。
     */
    suspend fun play(site: Site, flag: String, id: String): Type4PlayResult =
        ioSafeTracked(site.key, Type4PlayResult()) {
            val key = memKey(site)
            if (modeB[key] != true) {
                val first = buildUrl(site, "ac" to "play", "flag" to flag, "id" to id)
                    ?.let { parsePlay(client.getText(it), site.key) }
                if (first?.isPlayResponse() == true) return@ioSafeTracked first
            }
            val second = buildUrl(site, "play" to id) ?: invalidApi(site)
            val result = parsePlay(client.getText(second), site.key)
            if (result.isPlayResponse()) modeB[key] = true
            // 双试探里首试探失败是模式 B 的正常路径：最终拿到有效响应时清掉
            // 首试探留下的原因，否则一次成功的播放会背着失败原因。
            if (result.isPlayResponse()) lastErrorBySite.remove(site.key)
            result
        }

    // ---------------- 纯解析函数（输入 String 输出模型，便于测试） ----------------

    /** 首页 text -> (分类, 推荐列表)。{code:500} 或解析失败返回双空。 */
    fun parseHome(text: String, siteKey: String? = null): Pair<List<CategoryType>, List<Vod>> = runCatching {
        val raw = text.toRootObject()
        if (raw != null && raw.isError()) {
            val reason = "上游返回 code 500：${raw.optString("msg").takeIf { it.isNotBlank() } ?: "无 msg"}"
            siteKey?.let { lastErrorBySite[it] = reason }
            LogBus.w(TAG, reason)
            return emptyList<CategoryType>() to emptyList()
        }
        val root = raw
            ?: return emptyList<CategoryType>() to emptyList()
        val types = (root["class"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .map { o -> CategoryType(o.optString("type_id"), o.optString("type_name")) }
            .filter { it.typeId.isNotBlank() }
        val list = root.listArray().map { it.jsonObject.toVod(picTransform = { it.fixPic() }) }
        types to list
    }.onFailure {
        val reason = "首页解析失败: ${it.message}"
        siteKey?.let { lastErrorBySite[it] = reason }
        LogBus.w(TAG, reason)
    }.getOrDefault(emptyList<CategoryType>() to emptyList())

    /** 分类/搜索 text -> 影片列表。{code:500} 或解析失败返回空。 */
    fun parseCategory(text: String, siteKey: String? = null): List<Vod> = parseSearch(text, siteKey)

    /** 搜索 text -> 影片列表。 */
    fun parseSearch(text: String, siteKey: String? = null): List<Vod> = parseSearchPage(text, siteKey).list

    /**
     * 搜索 text -> (总数, 影片列表)。total 只有上游给了才透出，没有就是 null。
     * {code:500} 或解析失败返回 (null, 空列表)。
     */
    fun parseSearchPage(text: String, siteKey: String? = null): Type4SearchPage = runCatching {
        val raw = text.toRootObject()
        if (raw != null && raw.isError()) {
            val reason = "上游返回 code 500：${raw.optString("msg").takeIf { it.isNotBlank() } ?: "无 msg"}"
            siteKey?.let { lastErrorBySite[it] = reason }
            LogBus.w(TAG, reason)
            return Type4SearchPage()
        }
        val root = raw ?: return Type4SearchPage()
        Type4SearchPage(
            total = root.optInt("total"),
            list = root.listArray().map { it.jsonObject.toVod(picTransform = { it.fixPic() }) }
        )
    }.onFailure {
        val reason = "搜索解析失败: ${it.message}"
        siteKey?.let { lastErrorBySite[it] = reason }
        LogBus.w(TAG, reason)
    }.getOrDefault(Type4SearchPage())

    /** 详情 text -> VodDetail（取 list[0]）。失败返回仅带 vodId 的空详情。 */
    fun parseDetail(text: String, vodId: String, siteKey: String? = null): VodDetail = runCatching {
        val raw = text.toRootObject()
        if (raw != null && raw.isError()) {
            val reason = "上游返回 code 500：${raw.optString("msg").takeIf { it.isNotBlank() } ?: "无 msg"}"
            siteKey?.let { lastErrorBySite[it] = reason }
            LogBus.w(TAG, reason)
            return VodDetail(vodId = vodId)
        }
        val first = raw
            ?.listArray()?.firstOrNull()?.jsonObject
            ?: return VodDetail(vodId = vodId)
        first.toVodDetail(picTransform = { it.fixPic() })
    }.onFailure {
        val reason = "详情解析失败: ${it.message}"
        siteKey?.let { lastErrorBySite[it] = reason }
        LogBus.w(TAG, reason)
    }.getOrDefault(VodDetail(vodId = vodId))

    /** 播放 text -> (直链, parse 标记, 白名单 header)。 */
    fun parsePlay(text: String, siteKey: String? = null): Type4PlayResult = runCatching {
        val raw = text.toRootObject()
        if (raw != null && raw.isError()) {
            val reason = "上游返回 code 500：${raw.optString("msg").takeIf { it.isNotBlank() } ?: "无 msg"}"
            siteKey?.let { lastErrorBySite[it] = reason }
            LogBus.w(TAG, reason)
            return Type4PlayResult()
        }
        val root = raw ?: return Type4PlayResult()
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
    }.onFailure {
        val reason = "播放解析失败: ${it.message}"
        siteKey?.let { lastErrorBySite[it] = reason }
        LogBus.w(TAG, reason)
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

    // ---------------- 内部：JSON 解析 helpers ----------------

    private fun String.toRootObject(): JsonObject? = runCatching {
        json.parseToJsonElement(this).jsonObject
    }.getOrNull()

    /** 错误包络 {code:500,msg}（code 兼容数字/字符串写法）。 */
    private fun JsonObject.isError(): Boolean = optInt("code") == 500

    // listArray / optString / stripHtml 见顶层 JsonHelpers.kt；
    // toVod / toVodDetail 见顶层 VodMappers.kt（与 CmsApi 共用）

    /** 兼容 Int / "500" / true 等写法；缺失或 null 返回 null。 */
    private fun JsonObject.optInt(key: String): Int? {
        val prim = (this[key] as? JsonPrimitive) ?: return null
        if (prim is JsonNull) return null
        prim.intOrNull?.let { return it }
        return prim.contentOrNull?.toIntOrNull()
    }

}
