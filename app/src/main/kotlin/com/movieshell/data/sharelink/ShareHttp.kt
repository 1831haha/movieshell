package com.movieshell.data.sharelink

import com.movieshell.core.log.LogBus
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * v1.3.5：分享接口 HTTP 小件（照搬 PanSaveApi 的口径）。
 *
 * client 一律经参数注入，默认 15 秒超时；单测传 MockWebServer 的 client。
 * Cookie/token 值、带签名的完整 URL 绝不写进日志。
 * v1.3.22：__pugs 改用 OkHttp Cookie 解析并合并，失败时仍保留已读到的值；
 * v1.3.23：调用内保留同源跳转凭据；仅 download 显式启用有限 500 重试。
 * 日志仅记 method、白名单路径、HTTP/数字业务码和 __pugs 有无。
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

    private fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host == b.host && a.port == b.port

    /** Only replace this key; never put a cookie jar on the shared client. */
    private fun withPugs(cookie: String, pugs: String): String {
        if (pugs.isBlank()) return cookie
        return (cookie.split(';').map { it.trim() }
            .filter { it.isNotBlank() && !it.substringBefore('=').trim().equals("__pugs", true) } +
            "__pugs=$pugs").joinToString("; ")
    }

    /** Explicit opt-in for the existing read-only download operation, not all POSTs. */
    internal fun postDownloadJson(
        client: OkHttpClient,
        url: String,
        cookie: String,
        ua: String,
        referer: String,
        body: JSONObject,
        sleep: (Long) -> Unit = Thread::sleep,
    ): Resp {
        require(url.toHttpUrl().encodedPath == "/1/clouddrive/file/download")
        var pugs = ""
        repeat(3) { attempt ->
            val resp = postJson(client, url, withPugs(cookie, pugs), ua, referer, body)
            pugs = resp.pugs.ifBlank { pugs }
            if (resp.httpCode != 500 || attempt == 2) return resp.copy(pugs = pugs)
            // Do not swallow interruption/coroutine cancellation, including during backoff.
            sleep(200L * (attempt + 1))
        }
        error("unreachable")
    }

    private val loggedPaths = setOf(
        "/1/clouddrive/file/download", "/1/clouddrive/file/v2/play",
        "/1/clouddrive/share/sharepage/video_preview", "/1/clouddrive/share/sharepage/token",
        "/1/clouddrive/share/sharepage/detail", "/1/clouddrive/share/sharepage/save",
        "/1/clouddrive/file", "/1/clouddrive/file/sort", "/1/clouddrive/file/delete",
        "/1/clouddrive/task",
    )

    /** Never log arbitrary paths, query strings, business messages, or non-numeric codes. */
    internal fun diagnostic(method: String, url: HttpUrl, httpCode: Int, json: JSONObject?, pugs: String): String {
        val safeMethod = method.takeIf { it in setOf("GET", "POST") } ?: "OTHER"
        val path = url.encodedPath.takeIf { it in loggedPaths } ?: "[other]"
        val code = json?.opt("code")?.toString()?.takeIf { it.matches(Regex("-?[0-9]{1,10}")) } ?: "-"
        return "网盘接口 $safeMethod $path HTTP $httpCode code=$code __pugs键=" +
            (if (pugs.isNotBlank()) "有" else "无")
    }

    private fun execute(client: OkHttpClient, req: Request): Resp {
        // This state belongs to ONE call (and its redirects), never another user/account.
        var pugs = ""
        val scopedClient = client.newBuilder().addNetworkInterceptor { chain ->
            val next = chain.request()
            val builder = next.newBuilder()
            if (sameOrigin(req.url, next.url)) {
                val cookie = withPugs(next.header("Cookie").orEmpty(), pugs)
                if (cookie.isNotBlank()) builder.header("Cookie", cookie)
            } else {
                // Do not forward POST bodies (stoken/fid tokens) to a different origin.
                if (next.body != null) throw java.io.IOException("Cross-origin body blocked")
                // OkHttp doesn't automatically strip an explicit Cookie on cross-host redirects.
                builder.removeHeader("Cookie").removeHeader("Authorization")
                    .removeHeader("Proxy-Authorization").removeHeader("Referer")
            }
            chain.proceed(builder.build()).also { response ->
                if (sameOrigin(req.url, response.request.url)) {
                    pugs = pugsFromSetCookie(response.request.url, response.headers).ifBlank { pugs }
                }
            }
        }.build()
        return try {
            scopedClient.newCall(req).execute().use { resp ->
                // Also supports cached/prior responses; newest non-empty same-origin value wins.
                val responses = generateSequence(resp) { it.priorResponse }.toList().asReversed()
                for (response in responses) {
                    if (sameOrigin(req.url, response.request.url)) {
                        pugs = pugsFromSetCookie(response.request.url, response.headers).ifBlank { pugs }
                    }
                }
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                LogBus.d(TAG, diagnostic(req.method, req.url, resp.code, json, pugs))
                Resp(json, pugs, resp.code)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: java.io.IOException) {
            if (Thread.currentThread().isInterrupted) throw e
            LogBus.w(TAG, diagnostic(req.method, req.url, 0, null, pugs))
            Resp(null, pugs, 0)
        }
    }
}
