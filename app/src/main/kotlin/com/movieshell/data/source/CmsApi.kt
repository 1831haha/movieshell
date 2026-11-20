package com.movieshell.data.source

import android.util.Xml
import com.movieshell.core.net.DEFAULT_UA
import com.movieshell.domain.model.CategoryType
import com.movieshell.domain.model.Episode
import com.movieshell.domain.model.Site
import com.movieshell.domain.model.Vod
import com.movieshell.domain.model.VodDetail
import com.movieshell.core.log.LogBus
import com.movieshell.core.net.HttpClient
import com.movieshell.core.net.ioSafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader

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

        /**
         * v1.1.4 分层第 0 批：共享 OkHttp 客户端已迁入 core/net/HttpClient，
         * 这里只留转发，31 处调用点不用改。两个有意例外（DownloadQueueWorker 大文件、
         * LlmClient）仍各自私有 client，不并入共享单例。
         */
        fun defaultClient(): OkHttpClient = HttpClient.defaultClient()

        /** v1.4.4：音乐专用直连 client（见 HttpClient.noProxyClient），只供音乐模块用。 */
        fun noProxyClient(): OkHttpClient = HttpClient.noProxyClient()

        /** drpy 规则请求专用 client（3/4/4/5 秒、零重试），只供 V8DrpyEngine 用。 */
        fun drpyClient(): OkHttpClient = HttpClient.drpyClient()

        /** WebView 拿到的 Cookie 写进共享 Jar（见 HttpClient.putCookies）。 */
        fun putCookies(url: String, cookieHeader: String) = HttpClient.putCookies(url, cookieHeader)

        /** 清掉某地址 host 在 OkHttp Jar 与 WebView 里的 Cookie。 */
        fun clearCookies(url: String) = HttpClient.clearCookies(url)

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
                // v0.294.53：父分类空页展开补片——通用树口径，不按站打补丁。
                // 规则：CMS 分类是一棵树，影片挂在叶子分类下；父编号直接问
                // videolist 时，影片不在父编号下，服务器天然回 0 条，不是站挂了。
                // 只在父编号本页为空且确有子分类时才补片：先按原口径问一次，
                // 有片的站完全不受影响；子分类各问同页并行取、按分类顺序拼、
                // 按 vodId 去重，翻页时同理（子分类同页号再拼一次）。
                // 子分类认法分两路，缺一不可：①分类表自带的父编号；
                // ②分类表不给父编号时，从影片自带的父编号字段反推（见下）。
                if (list.isEmpty()) {
                    var children = types.filter { it.typePid == tid }
                    if (children.isEmpty() && site.type == 1) {
                        // 分类表没给父编号的站：首页一页影片里，子编号与父编号
                        // 是成对出现的（影片字段），用它反推该父编号的子分类。
                        // 首页一页未必覆盖全部子分类，能推多少补多少，推不出
                        // 的子分类仍为空——这是本口径的已知盲区，不谎称全覆盖。
                        val homeUrl = buildUrl(site, "ac" to "videolist", "pg" to "1")
                        if (homeUrl != null) {
                            val derived = runCatching {
                                parseParentChildMapJson(client.getTextCancellable(homeUrl))[tid]
                                    .orEmpty()
                            }.getOrDefault(emptyList())
                            children = derived.map { childId ->
                                CategoryType(
                                    typeId = childId,
                                    typeName = types.firstOrNull { it.typeId == childId }?.typeName.orEmpty(),
                                )
                            }
                        }
                    }
                    if (children.isNotEmpty()) {
                        val merged = coroutineScope {
                            children.map { child ->
                                async {
                                    // 单个子分类失败只空着它一个，不拖垮整页；取消必须透传，
                                    // 不能包成空列表吞掉（切分类取消后请求照跑的旧教训）。
                                    try {
                                        val childUrl = buildUrl(
                                            site, "ac" to "videolist",
                                            "t" to child.typeId, "pg" to pg.toString(),
                                        ) ?: return@async emptyList<Vod>()
                                        parseList(site, client.getTextCancellable(childUrl))
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        emptyList()
                                    }
                                }
                            }.awaitAll()
                        }.flatten()
                        val seen = HashSet<String>()
                        list = merged.filter { vod ->
                            vod.vodId.isBlank() || seen.add(vod.vodId)
                        }
                        if (list.isNotEmpty()) {
                            LogBus.i(TAG, "CMS 父分类 $tid 本页空，展开 ${children.size} 个子分类补片 ${list.size} 条")
                        }
                    }
                }
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
                typeName = o.optString("type_name"),
                // type_pid 可能是数字，optString 已兼容数字写法；
                // 顶级分类为 0，补片时只认 typePid == tid 的子分类，0 不会误配。
                typePid = o.optString("type_pid")
            )
        }.filter { it.typeId.isNotBlank() }
    }.getOrDefault(emptyList())

    /**
     * 从一页影片 JSON 反推父子关系：父编号 -> 子编号（去重、保序）。
     * 影片字段约定：type_id 是影片所在叶子分类，type_id_1 是其父分类；
     * 两字段缺一即跳过该条，不影响其他条。
     */
    internal fun parseParentChildMapJson(text: String): Map<String, List<String>> = runCatching {
        val root = jsonLenient.parseToJsonElement(text).jsonObject
        val out = LinkedHashMap<String, LinkedHashSet<String>>()
        root.listArray().forEach {
            val o = it.jsonObject
            val child = o.optString("type_id")
            val parent = o.optString("type_id_1")
            if (child.isNotBlank() && parent.isNotBlank() && parent != "0") {
                out.getOrPut(parent) { LinkedHashSet() } += child
            }
        }
        out.mapValues { it.value.toList() }
    }.getOrDefault(emptyMap())

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
                            // 属性必须在 nextText() 之前读：nextText 会把解析器推到结束标签。
                            val pid = p.getAttributeValue(null, "pid").orEmpty()
                            val name = p.nextText().trim()
                            // XML 老接口一般无 pid 属性，缺失即空串，补片逻辑自动不触发。
                            if (id.isNotBlank()) out += CategoryType(id, name, pid)
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
