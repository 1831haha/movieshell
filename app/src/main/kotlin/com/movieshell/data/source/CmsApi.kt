package com.movieshell.data.source

import android.util.Log
import android.util.Xml
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Episode
import com.movieshell.data.model.Site
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
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
 * 所有请求 15 秒超时，任何异常吞掉并返回空结果（列表）/ 空详情，不向上传播崩溃。
 */
class CmsApi(
    private val client: OkHttpClient = defaultClient()
) {

    companion object {
        private const val TAG = "CmsApi"
        private const val TIMEOUT_S = 15L
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()

        private val jsonLenient = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    }

    // ---------------- 对外接口 ----------------

    /** 首页：推荐 / 最新（不带 t 参数的 videolist 即最新列表）。 */
    suspend fun home(site: Site): List<Vod> = safe(emptyList()) {
        val url = buildUrl(site, "ac" to "videolist") ?: return@safe emptyList<Vod>()
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

    private fun JsonObject.listArray(): JsonArray =
        (this["list"] as? JsonArray) ?: JsonArray(emptyList())

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

    /** 兼容字段值为数字 / 布尔 / 字符串的写法；缺失或 null 返回空串。 */
    private fun JsonObject.optString(key: String): String {
        val prim = (this[key] as? JsonPrimitive) ?: return ""
        if (prim is JsonNull) return ""
        return prim.contentOrNull.orEmpty()
    }

    private fun String.stripHtml(): String =
        replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()

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
