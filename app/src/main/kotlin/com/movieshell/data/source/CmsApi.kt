package com.movieshell.data.source

import android.util.Xml
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Episode
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
         * saveFromResponse 语义：仅当 Set-Cookie 非空时整 host 覆盖；
         * v0.112.0 修 B6：之前无条件覆盖，无 Set-Cookie 的响应会把该 host 已有 cookie 清空。
         * loadForRequest 语义不变：命中返回该 host cookies、无则空列表。
         * v0.110.1：匿名 object 内禁止声明类（'Class' is prohibited here），Entry 移到 companion 作用域。
         */
        private data class CookieEntry(val cookies: List<Cookie>, @Volatile var lastUsed: Long)

        private val memoryCookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, CookieEntry>()
            private val maxHosts = 200

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (cookies.isNotEmpty()) {
                    store[url.host] = CookieEntry(cookies, System.currentTimeMillis())
                    if (store.size > maxHosts) evictOldest()
                }
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

            /** v0.201.0：验证通道回写 Cookie（按名合并，不丢该 host 已有项）。 */
            fun put(url: HttpUrl, cookies: List<Cookie>) {
                val host = url.host
                val old = store[host]?.cookies.orEmpty()
                val merged = old.filter { o -> cookies.none { it.name == o.name } } + cookies
                store[host] = CookieEntry(merged, System.currentTimeMillis())
            }

            /** v0.201.0：清掉某 host 的全部 Cookie（验证重进时不带旧 Cookie）。 */
            fun clear(host: String) {
                store.remove(host)
            }
        }

        /**
         * v0.201.0：把 WebView 拿到的 Cookie（"a=b; c=d" 形）写进共享 Jar，
         * 后续 OkHttp 请求自动带上。解析失败的段直接丢弃，不影响其它段。
         */
        fun putCookies(url: String, cookieHeader: String) {
            val httpUrl = url.toHttpUrlOrNull() ?: return
            val cookies = cookieHeader.split(";").mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) null else Cookie.parse(httpUrl, trimmed)
            }
            if (cookies.isNotEmpty()) memoryCookieJar.put(httpUrl, cookies)
        }

        /** v0.201.0：清掉某地址 host 在 OkHttp Jar 与 WebView 里的 Cookie。 */
        fun clearCookies(url: String) {
            val host = url.toHttpUrlOrNull()?.host ?: return
            memoryCookieJar.clear(host)
            runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
        }

        /** UA 拦截器：没带 UA 的请求统一补浏览器 UA，不覆盖调用方显式设置的 */
        private val uaInterceptor = Interceptor { chain ->
            val req = chain.request()
            if (req.header("User-Agent") == null) {
                chain.proceed(req.newBuilder().header("User-Agent", DEFAULT_UA).build())
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
         * v0.111.0 B5：加两项检查——①已取消的 call 不再睡 0.5s/1s 后重试，
         * 直接抛；②非幂等方法（POST/PUT/PATCH/DELETE）一次失败就抛，不重试。
         */
        private val retryInterceptor = Interceptor { chain ->
            var lastError: IOException? = null
            repeat(3) { attempt ->
                if (chain.call().isCanceled()) throw IOException("Canceled")  // 已取消直接抛，不重试
                try {
                    return@Interceptor chain.proceed(chain.request())
                } catch (e: IOException) {
                    if (chain.call().isCanceled()) throw e  // 取消表现为 IOException("Canceled")，不睡不重试
                    val m = chain.request().method
                    if (!m.equals("GET", ignoreCase = true) && !m.equals("HEAD", ignoreCase = true)) throw e  // 非幂等不重试
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
            // v0.188.0 M30：重试计数落日志——此前每次重试都记了
            // “重试 N/3”，但 3 次耗尽时无收尾日志，排障时分不清是
            // “没重试”还是“重试耗尽”。耗尽时补一条。
            LogBus.w(TAG, "请求重试耗尽（3 次）：${chain.request().url}")
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
    suspend fun home(site: Site): List<Vod> = ioSafe(TAG, emptyList()) {
        val url = buildUrl(site, "ac" to "videolist") ?: return@ioSafe emptyList<Vod>()
        LogBus.i(TAG, "CMS 请求: $url")
        parseList(site, client.getTextCancellable(url))
    }

    /**
     * 分类：返回 (分类列表, 当前分类第 pg 页影片)。
     * type=1 用 ac=list 一次拿全部分类；type=0 从任意 videolist 响应的 class 节点拿。
     */
    suspend fun category(site: Site, tid: String, pg: Int): Pair<List<CategoryType>, List<Vod>> =
        ioSafe(TAG, emptyList<CategoryType>() to emptyList()) {
            val classUrl = if (site.type == 1) {
                buildUrl(site, "ac" to "list")
            } else {
                // XML 的分类藏在 videolist 响应里，先拿一页顺手解析
                buildUrl(site, "ac" to "videolist", "pg" to "1")
            } ?: return@ioSafe (emptyList<CategoryType>() to emptyList())

            val types: List<CategoryType>
            var list: List<Vod> = emptyList()
            if (site.type == 1) {
                types = parseClassJson(client.getTextCancellable(classUrl))
            } else {
                val xml = client.getTextCancellable(classUrl)
                types = parseClassXml(xml)
                list = parseListXml(xml)
            }
            if (tid.isNotBlank()) {
                val pageUrl = buildUrl(site, "ac" to "videolist", "t" to tid, "pg" to pg.toString())
                    ?: return@ioSafe (types to emptyList())
                list = parseList(site, client.getTextCancellable(pageUrl))
            }
            types to list
        }

    /** 详情。失败返回仅带 vodId 的空详情，不抛异常。 */
    suspend fun detail(site: Site, vodId: String): VodDetail = ioSafe(TAG, VodDetail(vodId = vodId)) {
        val url = if (site.type == 1) {
            buildUrl(site, "ac" to "detail", "ids" to vodId)
        } else {
            // 老 XML 接口用 ac=videolist&ids= 取单条
            buildUrl(site, "ac" to "videolist", "ids" to vodId)
        } ?: return@ioSafe VodDetail(vodId = vodId)
        LogBus.i(TAG, "CMS 详情请求: $url")
        parseDetail(site, client.getTextCancellable(url), vodId)
    }

    /** 搜索：wd 由 HttpUrl 自动编码。 */
    suspend fun search(site: Site, wd: String): List<Vod> = ioSafe(TAG, emptyList()) {
        if (wd.isBlank()) return@ioSafe emptyList<Vod>()
        val url = buildUrl(site, "ac" to "videolist", "wd" to wd)
            ?: return@ioSafe emptyList<Vod>()
        parseList(site, client.getTextCancellable(url))
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

    /**
     * v0.188.0 M30：可取消版同步 GET（语义与 SourceNetKit.getText 一致：
     * 全仓 UA、非 2xx 抛 IOException，由 ioSafe 交 fallback）。
     * 超时口径说明：调用方总超时本就小于底层 60 秒 callTimeout
     * （聚合搜索单源 15 秒、探活 10 秒、嗅探 20 秒），但同步 execute
     * 原先不响应协程取消，调用方超时后线程仍占着跑到 60 秒；现取消
     * 即 cancel 底层 call（关 socket），超时真正起效。取消时抛回 job
     * 自身的取消异常：搜索超时触发的是 TimeoutCancellationException，
     * searchAll 认它才记慢源；外部取消的原样透传给 ioSafe（ioSafe
     * 本就透传取消，不吞成 fallback）。
     */
    private suspend fun OkHttpClient.getTextCancellable(url: HttpUrl): String {
        val call = newCall(Request.Builder().url(url).header("User-Agent", DEFAULT_UA).build())
        val job = currentCoroutineContext()[Job]
        job?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${resp.message}")
                return resp.body?.string().orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (job?.isCancelled == true) currentCoroutineContext().ensureActive()
            throw e
        }
    }

    private fun buildUrl(site: Site, vararg params: Pair<String, String>): HttpUrl? {
        val base = site.api.toHttpUrlOrNull() ?: return null
        val b = base.newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
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

    // listArray / optString / stripHtml 见顶层 JsonHelpers.kt；
    // toVod / toVodDetail 见顶层 VodMappers.kt（与 Type4Api 共用）

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
