package com.movieshell.data.sharelink

import com.movieshell.core.log.LogBus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
        }.onFailure { LogBus.w(TAG, "请求失败：${it.message}") }.getOrNull()
    }

    private fun execute(client: OkHttpClient, req: Request): Resp {
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                val pugs = resp.headers("Set-Cookie")
                    .firstOrNull { it.startsWith("__pugs=") }
                    ?.substringAfter("__pugs=")?.substringBefore(';').orEmpty()
                Resp(json, pugs)
            }
        }.onFailure { LogBus.w(TAG, "网盘接口请求失败：${it.message}") }.getOrDefault(Resp(null, ""))
    }
}
