package com.movieshell.data.sharelink

import com.movieshell.core.log.LogBus
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * v1.3.5：分享接口 HTTP 小件（照搬 PanSaveApi 的口径）。
 *
 * client 一律经参数注入，默认 15 秒超时；单测传 MockWebServer 的 client。
 * Cookie/token 值、带签名的完整 URL 绝不写进日志。
 * v1.3.22：__pugs 改用 OkHttp Cookie 解析并合并，失败时仍保留已读到的值；
 * 日志只记 HTTP 状态码和 __pugs 键有无。
 */
object ShareHttp {

    private const val TAG = "ShareHttp"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class Resp(val json: JSONObject?, val pugs: String, val httpCode: Int = 0)

    /**
     * 查询串值编码（v1.3.6）：URLEncoder 把空格编成 +，服务端按 + 读会错意，
     * 改回 %20；字面 + 编 %2B。stoken 这类服务端发的 token 常含 +/=，
     * 不编码会被截断（夸克 detail 回 14001“非法token”，目录报空）。
     */
    fun enc(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * v1.3.22：从 Set-Cookie 取出 __pugs 值。先走 OkHttp [Cookie.parse]
     * （认 Domain/Path/HttpOnly/Expires、大小写），解析失败再按第一段 name=value
     * 兜底（前导空格、引号包裹等非标准但合法形态）。只返回值，不记值。
     */
    internal fun pugsFromSetCookie(url: HttpUrl, headers: Headers): String {
        for (raw in headers.values("Set-Cookie")) {
            val parsed = Cookie.parse(url, raw)
            if (parsed != null && parsed.name.equals("__pugs", ignoreCase = true)) {
                val value = parsed.value.trim().trim('"')
                if (value.isNotBlank()) return value
            }
            val first = raw.trim().substringBefore(';').trim()
            val eq = first.indexOf('=')
            if (eq <= 0) continue
            val name = first.substring(0, eq).trim()
            val value = first.substring(eq + 1).trim().trim('"')
            if (name.equals("__pugs", ignoreCase = true) && value.isNotBlank()) return value
        }
        return ""
    }

    fun postJson(
        client: OkHttpClient,
        url: String,
        cookie: String,
        ua: String,
        referer: String,
        body: JSONObject,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Resp {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .header("Content-Type", "application/json")
            .apply {
                if (cookie.isNotBlank()) header("Cookie", cookie)
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .post(body.toString().toRequestBody(JSON))
            .build()
        return execute(client, req)
    }

    fun postForm(
        client: OkHttpClient,
        url: String,
        ua: String,
        referer: String,
        form: okhttp3.FormBody,
        cookie: String = "",
    ): Resp {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .post(form)
            .build()
        return execute(client, req)
    }

    fun getJson(
        client: OkHttpClient,
        url: String,
        cookie: String,
        ua: String,
        referer: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Resp {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .apply {
                if (cookie.isNotBlank()) header("Cookie", cookie)
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .get()
            .build()
        return execute(client, req)
    }

    fun getText(
        client: OkHttpClient,
        url: String,
        ua: String? = null,
        referer: String? = null,
    ): String? {
        val req = Request.Builder().url(url)
            .apply {
                if (ua != null) header("User-Agent", ua)
                if (referer != null) header("Referer", referer)
            }
            .get()
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.onFailure { LogBus.w(TAG, "请求失败：${it::class.java.simpleName}") }.getOrNull()
    }

    private fun execute(client: OkHttpClient, req: Request): Resp {
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                val pugs = pugsFromSetCookie(resp.request.url, resp.headers)
                LogBus.d(
                    TAG,
                    "网盘接口 HTTP ${resp.code} __pugs键=" + (if (pugs.isNotBlank()) "有" else "无"),
                )
                Resp(json, pugs, resp.code)
            }
        }.onFailure {
            LogBus.w(TAG, "网盘接口请求失败：${it::class.java.simpleName}")
        }.getOrDefault(Resp(null, "", 0))
    }
}
