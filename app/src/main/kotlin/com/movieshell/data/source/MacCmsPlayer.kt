package com.movieshell.data.source

import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

/**
 * v0.294.9：苹果CMS（MacCMS）播放页「免嗅」快路径。
 *
 * 苹果CMS 模板的播放页源码里直接写着 `var player_aaaa={...,"url":"...","encrypt":0|1|2}`：
 * encrypt=1 是 JS escape，encrypt=2 是 base64(escape)。与 drpy-node 的 commonLazyParse 同口径
 * （正则 `player_.*?=(.*?)<`）。拿到 m3u8/mp4 直接播，拿到 VIP 站地址交解析线路，
 * 其它情况（取不到/非 http/无后缀）返回 null，调用方照旧走 WebView 嗅探。
 */
object MacCmsPlayer {

    private const val TAG = "MacCmsPlayer"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val PLAYER_RE = Regex("player_[A-Za-z0-9_]*\\s*=\\s*(\\{.*?\\})\\s*[;<\\n]", RegexOption.DOT_MATCHES_ALL)

    data class Result(val url: String, val from: String)

    /** 纯函数：从 HTML 里取播放器配置并解出地址；取不到返回 null。 */
    fun extract(html: String): Result? {
        if (!html.contains("player_")) return null
        for (m in PLAYER_RE.findAll(html)) {
            val obj = runCatching { json.parseToJsonElement(m.groupValues[1]).jsonObject }.getOrNull() ?: continue
            val raw = obj.str("url")
            if (raw.isBlank()) continue
            val url = decode(raw, obj.str("encrypt")).trim()
            if (url.isBlank()) continue
            return Result(url, obj.str("from"))
        }
        return null
    }

    /** 纯函数：按 encrypt 解码（0 原样 / 1 unescape / 2 base64 后 unescape）。 */
    fun decode(raw: String, encrypt: String): String = when (encrypt.trim()) {
        "1" -> unescape(raw)
        "2" -> runCatching {
            unescape(String(java.util.Base64.getDecoder().decode(raw.trim()), Charsets.UTF_8))
        }.getOrDefault("")
        else -> raw.replace("\\/", "/")
    }

    /** 纯函数：JS unescape（%XX 与 %uXXXX；非法序列原样保留）。 */
    fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                val u = if (s.length >= i + 6 && (s[i + 1] == 'u' || s[i + 1] == 'U')) s.substring(i + 2, i + 6).toIntOrNull(16) else null
                if (u != null) { sb.append(u.toChar()); i += 6; continue }
                val h = if (s.length >= i + 3) s.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (h != null) { sb.append(h.toChar()); i += 3; continue }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** 纯函数：解出的地址补全（`//x` → https:，相对地址按页面补全）；非 http 返回 null。 */
    fun absolutize(url: String, page: String): String? {
        val u = url.trim()
        val abs = when {
            u.startsWith("http://", true) || u.startsWith("https://", true) -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") && page.contains("://") ->
                page.substringBefore("://") + "://" + page.substringAfter("://").substringBefore('/') + u
            else -> return null
        }
        return abs
    }

    /** 请求播放页并尝试解出地址（≤6 秒；任何失败返回 null）。 */
    suspend fun fetch(page: String, headers: Map<String, String>): String? = withTimeoutOrNull(6_000L) {
        withContext(Dispatchers.IO) {
            runCatching {
                val b = Request.Builder().url(page).get()
                com.movieshell.player.PlayHeaders.normalize(headers).forEach { (k, v) -> runCatching { b.header(k, v) } }
                if (com.movieshell.player.PlayHeaders.get(headers, "User-Agent") == null) {
                    b.header("User-Agent", SniffConfig.uaString())
                }
                CmsApi.defaultClient().newCall(b.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val ct = resp.header("Content-Type").orEmpty().lowercase()
                    if (ct.isNotEmpty() && !ct.contains("html") && !ct.contains("text")) return@use null
                    val body = resp.body?.string().orEmpty()
                    val r = extract(body) ?: return@use null
                    absolutize(r.url, page)?.also { LogBus.i(TAG, "免嗅命中（${r.from.ifBlank { "player" }}）：${it.take(100)}") }
                }
            }.getOrNull()
        }
    }

    private fun JsonObject.str(k: String): String = ((this[k] as? JsonPrimitive)?.contentOrNull).orEmpty()
}
