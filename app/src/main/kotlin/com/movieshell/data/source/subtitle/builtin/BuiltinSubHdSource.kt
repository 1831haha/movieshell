package com.movieshell.data.source.subtitle.builtin

import com.movieshell.data.source.subtitle.OnlineSubtitle
import com.movieshell.data.source.subtitle.SubtitleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * SubHD 字幕源（v0.28.0，首版唯一写死源）。
 *
 * - 搜索：GET https://subhd.tv/search/{关键词}，HTML 直爬，无需登录。
 *   条目结构（2026-10 实测）：a[href='/a/{sid}'] 两个链接（标题 + 文件名），
 *   语言 tag 在 span.p-1.fw-bold，格式在 span.p-1.text-secondary，
 *   大小/下载量在 span.align-text-top.me-3。
 * - 下载三步（站内 JS 流程）：POST /api/sub/prepare-download {sid}
 *   → GET /down/{sid}（拿放行 Cookie）→ POST /api/sub/down {sid} 拿 CDN 直链。
 *   下到压缩包时用系统 zip 解出第一个 srt/ass/ssa；rar/7z/sup 等直接报不支持。
 */
object BuiltinSubHdSource : SubtitleSource {
    override val id = "subhd"
    override val name = "SubHD"

    private const val BASE = "https://subhd.tv"
    private const val MAX_BYTES = 5 * 1024 * 1024L

    private val jar = object : CookieJar {
        private val store = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store.removeAll { c -> cookies.any { it.name == c.name } }
            store.addAll(cookies)
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store.toList()
    }

    private val http = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val ua =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36"

    // /a/{sid} 两种链接：标题链和文件名链，按 sid 去重，取更长的文件名做标题
    private val LINK_RE = Regex("""href='/a/([a-zA-Z0-9]+)'[^>]*>\s*([^<]{2,120})""")
    private val LANG_RE =
        Regex("""<span class="p-1 fw-bold">([^<]{1,12})</span>""")
    private val FMT_RE =
        Regex("""<span class="p-1 text-secondary">([A-Za-z0-9]{2,5})</span>""")
    private val META_RE =
        Regex("""<span class=['"]align-text-top me-3['"]>([^<]{1,16})</span>""")

    override suspend fun search(keyword: String): List<OnlineSubtitle> =
        withContext(Dispatchers.IO) {
            withTimeout(10_000) {
                val q = URLEncoder.encode(keyword.trim(), "UTF-8")
                val html = get("$BASE/search/$q") ?: throw IllegalStateException("搜索请求失败")
                parseSearch(html)
            }
        }

    private fun parseSearch(html: String): List<OnlineSubtitle> {
        // 按 sid 分组：同一条目有多段文本，拼起来找语言/格式/大小
        val blocks = html.split("<div class=\"clearfix\">").drop(1)
        return blocks.mapNotNull { b ->
            val sid = LINK_RE.find(b)?.groupValues?.get(1) ?: return@mapNotNull null
            val titles = LINK_RE.findAll(b).map { it.groupValues[2].trim() }.toList()
            if (titles.isEmpty()) return@mapNotNull null
            val title = titles.maxByOrNull { it.length }.orEmpty()
            val langs = LANG_RE.findAll(b).map { it.groupValues[1] }.distinct().joinToString("/")
            val fmt = FMT_RE.find(b)?.groupValues?.get(1).orEmpty().uppercase()
            val metas = META_RE.findAll(b).map { it.groupValues[1] }.toList()
            OnlineSubtitle(
                sourceId = id,
                sid = sid,
                title = title,
                language = langs,
                format = fmt,
                size = metas.getOrNull(0).orEmpty(),
                downloads = metas.getOrNull(1).orEmpty(),
            )
        }.distinctBy { it.sid }.take(30)
    }

    override suspend fun download(item: OnlineSubtitle): Pair<String, String> =
        withContext(Dispatchers.IO) {
            withTimeout(30_000) {
                // 1. 准备下载
                val prep = postJson(
                    "$BASE/api/sub/prepare-download",
                    JSONObject().put("sid", item.sid).toString(),
                    referer = "$BASE/a/${item.sid}",
                ) ?: throw IllegalStateException("准备下载失败")
                val downPath = JSONObject(prep).optString("down_url", "/down/${item.sid}")
                // 2. 拿放行 Cookie
                get("$BASE$downPath", referer = "$BASE/a/${item.sid}")
                // 3. 拿 CDN 直链
                val downResp = postJson(
                    "$BASE/api/sub/down",
                    JSONObject().put("sid", item.sid).toString(),
                    referer = "$BASE/a/${item.sid}",
                ) ?: throw IllegalStateException("获取下载地址失败")
                val fileUrl = JSONObject(downResp).optString("url")
                    .ifBlank { throw IllegalStateException("下载地址为空（可能需要解锁）") }
                fetchFile(fileUrl)
            }
        }

    /** 拉文件并处理压缩包，返回（内容，扩展名）。 */
    private fun fetchFile(url: String): Pair<String, String> {
        val req = Request.Builder().url(url).header("User-Agent", ua).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("下载失败：${resp.code}")
            val bytes = resp.body?.bytes() ?: throw IllegalStateException("空文件")
            if (bytes.size > MAX_BYTES) throw IllegalStateException("文件过大（>${MAX_BYTES / 1024 / 1024}MB）")
            val name = resp.request.url.pathSegments.lastOrNull().orEmpty()
            val ext = name.substringAfterLast('.', "").lowercase()
            // zip 包：解出第一个字幕文件
            if (ext == "zip" || bytes.isZip()) {
                return unzipFirst(bytes) ?: throw IllegalStateException("压缩包里没有字幕文件")
            }
            if (ext !in setOf("srt", "ass", "ssa", "vtt")) {
                throw IllegalStateException("不支持的格式：$ext（只要 srt/ass/ssa/vtt）")
            }
            return bytes.toString(Charsets.UTF_8) to ext
        }
    }

    private fun ByteArray.isZip(): Boolean =
        size > 4 && this[0] == 0x50.toByte() && this[1] == 0x4B.toByte()

    private fun unzipFirst(bytes: ByteArray): Pair<String, String>? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val n = e.name.substringAfterLast('/').lowercase()
                val ext = n.substringAfterLast('.', "")
                if (!e.isDirectory && ext in setOf("srt", "ass", "ssa", "vtt")) {
                    val out = zip.readBytes()
                    if (out.size <= MAX_BYTES) return out.toString(Charsets.UTF_8) to ext
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        return null
    }

    private fun get(url: String, referer: String = BASE): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua).header("Referer", referer).build()
        http.newCall(req).execute().use { resp ->
            return if (resp.isSuccessful) resp.body?.string() else null
        }
    }

    private fun postJson(url: String, json: String, referer: String): String? {
        val body = json.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(body)
            .header("User-Agent", ua).header("Referer", referer)
            .header("Accept", "application/json").build()
        http.newCall(req).execute().use { resp ->
            return if (resp.isSuccessful) resp.body?.string() else null
        }
    }
}
