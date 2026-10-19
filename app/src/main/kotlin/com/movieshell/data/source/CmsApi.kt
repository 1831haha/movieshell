package com.movieshell.data.source

import android.util.Log
import android.util.Xml
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Episode
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * CMS 站源客户端（TVBox type=0 / type=1）。
 *
 * type=1（苹果 CMS JSON / 海洋 CMS 等 JSON 接口）：
 *   列表/搜索  GET {api}?ac=videolist&pg=N&t={tid}&wd={wd}
 *   详情       GET {api}?ac=detail&ids={vodId}
 *   分类       GET {api}?ac=list            -> 顶层 class 数组
 * type=0（XML，老 MacCMS 接口）：
 *   列表/搜索  GET {api}?ac=videolist&pg=N&t={tid}&wd={wd}
 *   详情       GET {api}?ac=videolist&ids={vodId}
 *   分类       rss > class > ty(id) 节点
 *
 * 所有请求单操作 15 秒超时、整 call 60 秒上限（v0.109.0），任何异常吞掉并返回空结果（列表）/ 空详情，不向上传播崩溃。
 */
class CmsApi(
    private val client: OkHttpClient = defaultClient()
) {

    companion object {
        private const val TAG = "CmsApi"
        private const val TIMEOUT_S = 15L
        /** v0.109.0：整 call 上限。审计过全部 defaultClient() 调用点：全是短 GET/HEAD
         *（CMS/探活/解析/嗅探 HEAD/音乐解析），无 SSE/流式/大文件下载（那些走各自私有
         * client）；最坏单链路 3 次重试 × 15s + 退避 1.5s ≈ 46.5s < 60s，保守取值。 */
        private const val CALL_TIMEOUT_S = 60L
        /** 重试退避基数：500ms → 1s 指数退避（阻塞拦截器里小睡可接受）。 */
        private const val RETRY_BACKOFF_BASE_MS = 500L
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        /**
         * v0.109.0：进程级单例。之前每次调用 build 一个新 OkHttpClient，
         * 连接池/Dispatcher 全丢，频繁建连 churn；现在全 App 共用一个。
         */
        fun defaultClient(): OkHttpClient = sharedClient

        private val sharedClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            buildDefaultClient()
        }

        private fun buildDefaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            // v0.99.0 P1-1：拦截器链——UA 统一补、IO 异常重试、内存 CookieJar、简易日志
            .cookieJar(memoryCookieJar)
            .addInterceptor(uaInterceptor)
            .addInterceptor(retryInterceptor)
            .addInterceptor(simpleLogInterceptor)
            .build()

        /**
         * 内存 CookieJar：host → cookies，需要登录态的源保持会话。
         * v0.110.0：synchronized + mutableMapOf → ConcurrentHashMap，读写无锁；
         * host 维度 LRU 上限 200（超限淘汰最久未用，见 SniffPlayHeaders 同款 200 上限），
         * 进程单例不再无限增长。
         * saveFromResponse 语义不变：整 host 覆盖（含空列表照存）；
         * loadForRequest 语义不变：命中返回该 host cookies、无则空列表。
         */
        private val memoryCookieJar = object : CookieJar {
            private data class Entry(val cookies: List<Cookie>, @Volatile var lastUsed: Long)

            private val store = ConcurrentHashMap<String, Entry>()
            private val maxHosts = 200

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                store[url.host] = Entry(cookies, System.currentTimeMillis())
                if (store.size > maxHosts) evictOldest()
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val e = store[url.host] ?: return emptyList()
                e.lastUsed = System.currentTimeMillis()
                return e.cookies
            }

            /** 淘汰最久未用的 host（近似 LRU；只在超限写入时触发，扫描成本可忽略）。 */
            private fun evictOldest() {
                var oldestKey: String? = null
                var oldestTime = Long.MAX_VALUE
                for ((host, e) in store) {
                    if (e.lastUsed < oldestTime) {
                        oldestTime = e.lastUsed
                        oldestKey = host
                    }
                }
                oldestKey?.let { store.remove(it) }
            }
        }

        /** UA 拦截器：没带 UA 的请求统一补浏览器 UA，不覆盖调用方显式设置的 */
        private val uaInterceptor = Interceptor { chain ->
            val req = chain.request()
            if (req.header("User-Agent") == null) {
                chain.proceed(req.newBuilder().header("User-Agent", UA).build())
            } else {
                chain.proceed(req)
            }
        }

        /**
         * 重试拦截器：IO 异常（超时/断连）最多重试 2 次；HTTP 错误码不重试。
         * 注意：Interceptor 是阻塞上下文，这里不能用协程 delay，只做小睡退避。
         * v0.109.0：加指数退避（500ms→1s），之前无间隔连发 3 次容易把对面打成限流。
         * v0.110.0 评估结论：保留 Thread.sleep——Interceptor.proceed 是同步阻塞 API，
         * OkHttp 自身 retryOnConnectionFailure 同样阻塞 dispatcher 线程；默认 64 并发、
         * 单 call 最坏阻塞 0.5s+1s=1.5s（3 次尝试，远低于 callTimeout 60s），耗尽概率
         * 可忽略。改成 delay 要把整条调用链改成挂起结构，代价大于收益。
         */
        private val retryInterceptor = Interceptor { chain ->
            var lastError: IOException? = null
            repeat(3) { attempt ->
                try {
                    return@Interceptor chain.proceed(chain.request())
                } catch (e: IOException) {
                    lastError = e
                    LogBus.w(TAG, "请求 IO 异常，重试 ${attempt + 1}/3：${chain.request().url}")
                    if (attempt < 2) {
                        try {
                            Thread.sleep(RETRY_BACKOFF_BASE_MS shl attempt)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            // 被中断就别再睡了，把原始异常抛出去结束重试
                            throw e
                        }
                    }
                }
            }
            throw lastError ?: IOException("unknown network error")
        }

        /** 简易日志拦截器（不引入 logging-interceptor 依赖）：方法 + URL + 状态码 */
        private val simpleLogInterceptor = Interceptor { chain ->
            val req = chain.request()
            val resp = chain.proceed(req)
            LogBus.d(TAG, "${req.method} ${req.url} → ${resp.code}")
            resp
        }

        private val jsonLenient = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    }

    // ---------------- 对外接口 ----------------

    /** 首页：推荐 / 最新（不带 t 参数的 videolist 即最新列表）。 */
    suspend fun home(site: Site): List<Vod> = safe(emptyList()) {
        val url = buildUrl(site, "ac" to "videolist") ?: return@safe emptyList<Vod>()
        LogBus.i(TAG, "CMS 请求: $url")
        parseList(site, getText(url))
    }

    /**
     * 分类：返回 (分类列表, 当前分类第 pg 页影片)。
     * type=1 用 ac=list 一次拿全部分类；type=0 从任意 videolist 响应的 class 节点拿。
     */
    suspend fun category(site: Site, tid: String, pg: Int): Pair<List<CategoryType>, List<Vod>> =
        safe(emptyList<CategoryType>() to emptyList()) {
            val classUrl = if (site.type == 1) {
                buildUrl(site, "ac" to "list")
            } else {
                // XML 的分类藏在 videolist 响应里，先拿一页顺手解析
                buildUrl(site, "ac" to "videolist", "pg" to "1")
            } ?: return@safe (emptyList<CategoryType>() to emptyList())

            val types: List<CategoryType>
            var list: List<Vod> = emptyList()
            if (site.type == 1) {
                types = parseClassJson(getText(classUrl))
            } else {
                val xml = getText(classUrl)
                types = parseClassXml(xml)
                list = parseListXml(xml)
            }
            if (tid.isNotBlank()) {
                val pageUrl = buildUrl(site, "ac" to "videolist", "t" to tid, "pg" to pg.toString())
                    ?: return@safe (types to emptyList())
                list = parseList(site, getText(pageUrl))
            }
            types to list
        }

    /** 详情。失败返回仅带 vodId 的空详情，不抛异常。 */
    suspend fun detail(site: Site, vodId: String): VodDetail = safe(VodDetail(vodId = vodId)) {
        val url = if (site.type == 1) {
            buildUrl(site, "ac" to "detail", "ids" to vodId)
        } else {
            // 老 XML 接口用 ac=videolist&ids= 取单条
            buildUrl(site, "ac" to "videolist", "ids" to vodId)
        } ?: return@safe VodDetail(vodId = vodId)
        LogBus.i(TAG, "CMS 详情请求: $url")
        parseDetail(site, getText(url), vodId)
    }

    /** 搜索：wd 由 HttpUrl 自动编码。 */
    suspend fun search(site: Site, wd: String): List<Vod> = safe(emptyList()) {
        if (wd.isBlank()) return@safe emptyList<Vod>()
        val url = buildUrl(site, "ac" to "videolist", "wd" to wd)
            ?: return@safe emptyList<Vod>()
        parseList(site, getText(url))
    }

    /**
     * 解析播放地址原文为分集列表。
     * TVBox 约定：$$$ 切来源分组，# 切分集，$ 切"集名$地址"（只切第一个 $，地址本身可含 $）。
     * @param playFrom 与分组一一对应的来源名（$$$ 分隔，可空；空时自动编号为 线路1/2…）
     */
    fun parsePlayUrl(vodPlayUrl: String, playFrom: String = ""): List<Episode> {
        if (vodPlayUrl.isBlank()) return emptyList()
        val flags = playFrom.split("\$\$\$")
        val out = ArrayList<Episode>()
        vodPlayUrl.split("\$\$\$").forEachIndexed { gi, group ->
            val flag = flags.getOrNull(gi)?.takeIf { it.isNotBlank() } ?: "线路${gi + 1}"
            group.split("#").forEach { ep ->
                val idx = ep.indexOf('$')
                if (idx > 0) {
                    val name = ep.substring(0, idx).trim()
                    val url = ep.substring(idx + 1).trim()
                    if (url.isNotBlank()) out += Episode(flag, name.ifBlank { "正片" }, url)
                }
            }
        }
        return out
    }

    // ---------------- 内部：请求 ----------------

    private fun buildUrl(site: Site, vararg params: Pair<String, String>): HttpUrl? {
        val base = site.api.toHttpUrlOrNull() ?: return null
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
                // v0.109.0：取消必须透传。之前连 CancellationException 一起吞，
                // 取消搜索/切页后底层请求照样跑到 15 秒超时才停。
                if (e is CancellationException) throw e
                Log.w(TAG, "request failed: ${e.message}")
                fallback
            }
        }

    // ---------------- 内部：JSON(type=1) 解析 ----------------

    private fun parseList(site: Site, text: String): List<Vod> =
        if (site.type == 1) parseListJson(text) else parseListXml(text)

    private fun parseDetail(site: Site, text: String, vodId: String): VodDetail =
        if (site.type == 1) parseDetailJson(text, vodId) else parseDetailXml(text, vodId)

    internal fun parseListJson(text: String): List<Vod> = runCatching {
        jsonLenient.parseToJsonElement(text).jsonObject.listArray().map { it.jsonObject.toVod() }
    }.getOrDefault(emptyList())

    internal fun parseClassJson(text: String): List<CategoryType> = runCatching {
        val root = jsonLenient.parseToJsonElement(text).jsonObject
        (root["class"] as? JsonArray).orEmpty().map {
            val o = it.jsonObject
            CategoryType(
                typeId = o.optString("type_id"),
                typeName = o.optString("type_name")
            )
        }.filter { it.typeId.isNotBlank() }
    }.getOrDefault(emptyList())

    internal fun parseDetailJson(text: String, vodId: String): VodDetail = runCatching {
        val first = jsonLenient.parseToJsonElement(text).jsonObject.listArray().firstOrNull()
            ?.jsonObject ?: return VodDetail(vodId = vodId)
        first.toVodDetail()
    }.getOrDefault(VodDetail(vodId = vodId))

    // listArray / optString / stripHtml 见顶层 JsonHelpers.kt（与 Type4Api 共用）

    private fun JsonObject.toVod(): Vod = Vod(
        vodId = optString("vod_id"),
        vodName = optString("vod_name"),
        vodPic = optString("vod_pic"),
        vodRemarks = optString("vod_remarks"),
        vodYear = optString("vod_year"),
        typeName = optString("type_name")
    )

    private fun JsonObject.toVodDetail(): VodDetail = VodDetail(
        vodId = optString("vod_id"),
        vodName = optString("vod_name"),
        vodPic = optString("vod_pic"),
        vodRemarks = optString("vod_remarks"),
        vodYear = optString("vod_year"),
        typeName = optString("type_name"),
        vodDesc = optString("vod_content").stripHtml(),
        vodActor = optString("vod_actor"),
        vodDirector = optString("vod_director"),
        vodPlayFrom = optString("vod_play_from"),
        vodPlayUrl = optString("vod_play_url")
    )

    // ---------------- 内部：XML(type=0) 解析 ----------------
    //
    // 老 MacCMS XML 结构：
    // <rss><list page="1" ...><video>
    //   <id>..</id><tid>..</tid><name>..</name><pic>..</pic><note>..</note>
    //   <year>..</year><des>..</des><actor>..</actor><director>..</director>
    //   <dl><dd flag="m3u8">第1集$http://..#第2集$http://..</dd></dl>
    // </video></list>
    // <class><ty id="1">电影</ty>...</class></rss>

    private fun parseListXml(text: String): List<Vod> =
        parseVideosXml(text).map { it.first }

    private fun parseDetailXml(text: String, vodId: String): VodDetail =
        parseVideosXml(text).firstOrNull()?.second ?: VodDetail(vodId = vodId)

    private fun parseClassXml(text: String): List<CategoryType> {
        val out = ArrayList<CategoryType>()
        runCatching {
            val p = Xml.newPullParser()
            p.setInput(StringReader(text))
            var inClass = false
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "class" -> inClass = true
                        "ty" -> if (inClass) {
                            val id = p.getAttributeValue(null, "id").orEmpty()
                            val name = p.nextText().trim()
                            if (id.isNotBlank()) out += CategoryType(id, name)
                        }
                    }
                    XmlPullParser.END_TAG -> if (p.name == "class") inClass = false
                }
                event = p.next()
            }
        }
        return out
    }

    /** 返回 (Vod, VodDetail) 对，detail 字段只有详情场景需要。 */
    private fun parseVideosXml(text: String): List<Pair<Vod, VodDetail>> {
        val out = ArrayList<Pair<Vod, VodDetail>>()
        runCatching {
            val p = Xml.newPullParser()
            p.setInput(StringReader(text))
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && p.name == "video") {
                    out += readVideoXml(p)
                }
                event = p.next()
            }
        }
        return out
    }

    private fun readVideoXml(p: XmlPullParser): Pair<Vod, VodDetail> {
        val f = HashMap<String, String>()
        val flags = ArrayList<String>()
        val groups = ArrayList<String>()
        var event = p.next()
        while (!(event == XmlPullParser.END_TAG && p.name == "video")) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "dd" -> {
                        flags += p.getAttributeValue(null, "flag").orEmpty()
                        groups += p.nextText()
                    }
                    "dl" -> { /* 容器，跳过 */ }
                    else -> f[p.name] = p.nextText()
                }
            }
            event = p.next()
        }
        val vod = Vod(
            vodId = f["id"].orEmpty(),
            vodName = f["name"].orEmpty(),
            vodPic = f["pic"].orEmpty(),
            vodRemarks = f["note"].orEmpty(),
            vodYear = f["year"].orEmpty(),
            typeName = f["typename"].orEmpty()
        )
        val detail = VodDetail(
            vodId = vod.vodId,
            vodName = vod.vodName,
            vodPic = vod.vodPic,
            vodRemarks = vod.vodRemarks,
            vodYear = vod.vodYear,
            typeName = vod.typeName,
            vodDesc = f["des"].orEmpty().stripHtml(),
            vodActor = f["actor"].orEmpty(),
            vodDirector = f["director"].orEmpty(),
            vodPlayFrom = flags.joinToString("\$\$\$"),
            vodPlayUrl = groups.joinToString("\$\$\$")
        )
        return vod to detail
    }
}
