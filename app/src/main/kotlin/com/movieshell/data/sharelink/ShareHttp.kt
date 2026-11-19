package com.movieshell.data.sharelink

import com.movieshell.core.log.LogBus
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
 * Cookie/token 值绝不写进日志。
 */
object ShareHttp {

    private const val TAG = "ShareHttp"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class Resp(val json: JSONObject?, val pugs: String)

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

    fun postJson(
        client: OkHttpClient,
        url: String,
        cookie: String,
        ua: String,
        referer: String,
        body: JSONObject,
        extraHeaders: Map<String, String> = emptyMap(),
        caller: String = "",
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
        return execute(client, req, caller)
    }

    fun postForm(
        client: OkHttpClient,
        url: String,
        ua: String,
        referer: String,
        form: okhttp3.FormBody,
        cookie: String = "",
        caller: String = "",
    ): Resp {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .post(form)
            .build()
        return execute(client, req, caller)
    }

    fun getJson(
        client: OkHttpClient,
        url: String,
        cookie: String,
        ua: String,
        referer: String,
        extraHeaders: Map<String, String> = emptyMap(),
        caller: String = "",
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
        return execute(client, req, caller)
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
        }.onFailure { LogBus.w(TAG, "请求失败：${it.message}") }.getOrNull()
    }

    /**
     * v1.3.24：从全部 Set-Cookie 里取第一个非空的 __pugs（键名大小写不敏感；
     * 空值不算有，后面的同名头还能补）。纯函数，单测覆盖。
     */
    internal fun extractPugs(setCookieHeaders: List<String>): String {
        for (header in setCookieHeaders) {
            val pair = header.substringBefore(';').trim()
            if (pair.substringBefore('=').trim().equals("__pugs", ignoreCase = true) && '=' in pair) {
                val value = pair.substringAfter('=', "").trim()
                if (value.isNotEmpty()) return value
            }
        }
        return ""
    }

    /**
     * v1.3.24：起播前对转码 m3u8 做一次预检，只记 HTTP 状态与 __pugs 有无，
     * 不记值、不改播放行为（TeleAgent P0-2 第 3 条：412 响应本身可能是下发点）。
     * 返回（HTTP 状态，响应头里有没有 __pugs）；请求失败返回（-1，false）。
     */
    fun probe(
        client: OkHttpClient,
        url: String,
        cookie: String,
        ua: String,
        referer: String,
    ): Pair<Int, Boolean> {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Referer", referer)
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .get()
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                resp.code to extractPugs(resp.headers("Set-Cookie")).isNotBlank()
            }
        }.onFailure { LogBus.w(TAG, "m3u8预检失败：" + it.message) }.getOrDefault(-1 to false)
    }

    private fun execute(client: OkHttpClient, req: Request, caller: String = ""): Resp {
        return runCatching {
            client.newCall(req).execute().use { resp ->
                // v1.3.24：先取响应头再读体——读体异常也不能把已下发的 __pugs 丢了
                // （之前读体在前，异常直接走 getOrDefault(Resp(null, ""))）。
                val pugs = extractPugs(resp.headers("Set-Cookie"))
                val text = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                val json = runCatching { JSONObject(text) }.getOrNull()
                // v1.3.24：逐请求记 __pugs 有无 + 调用点（GPT/TeleAgent 都要归因；
                // 只记有无，绝不记 Cookie 值）。格式与 1.3.23 的日志行保持一致。
                val code = json?.optInt("code", -1) ?: -1
                LogBus.d(
                    TAG,
                    "网盘接口 " + req.method + " " + req.url.encodedPath + " HTTP " + resp.code +
                        " code=" + code + " __pugs键=" + (if (pugs.isNotBlank()) "有" else "无") +
                        (if (caller.isNotBlank()) " 调用点=" + caller else ""),
                )
                Resp(json, pugs)
            }
        }.onFailure { LogBus.w(TAG, "网盘接口请求失败：" + it.message) }.getOrDefault(Resp(null, ""))
    }
}
