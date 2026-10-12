package com.movieshell.data.source

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.movieshell.data.model.ParseConfig
import com.movieshell.player.SniffPlayHeaders
import com.movieshell.ui.web.isPlayableDirectUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * 解析链路（v0.7）：按顺序尝试订阅的 parses 数组，把视频地址解析成可播直链。
 *
 * - type=1：JSON 接口。GET `parse.url + urlencode(videoUrl)`，带上 parse.header
 *   （如 User-Agent）；返回 JSON 取 `url` 字段，兼容 `data.url` 包络。
 * - type=0：WebView 嗅探。加载 `parse.url + urlencode(videoUrl)`，
 *   复用 WebSniffScreen 的 [isPlayableDirectUrl] 判定抓 .m3u8/.mp4，20 秒超时；
 *   抓到的请求头经 SniffPlayHeaders 存下，播放器一次性取出透传。
 *
 * 取到第一条非空直链即返回；全部失败返回 ""。全程 runCatching：
 * 单条失败只换下一条，不抛异常。
 */
class ParseApi(
    private val client: OkHttpClient = CmsApi.defaultClient()
) {

    companion object {
        private const val TAG = "ParseApi"

        /** type=0 单条嗅探超时（与 WebSniffScreen 的手动嗅探页一致，20 秒） */
        private const val SNIFF_TIMEOUT_MS = 20_000L

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    }

    /**
     * 按顺序尝试每条解析线路，返回第一条抓到的直链；全部失败返回 ""。
     * @param context type=0 嗅探需要 Context 建 WebView；传 null 时跳过所有 type=0 条目
     */
    suspend fun parse(
        parses: List<ParseConfig>,
        videoUrl: String,
        context: Context? = null,
    ): String {
        if (parses.isEmpty() || videoUrl.isBlank()) return ""
        for (p in parses) {
            val hit = runCatching {
                when (p.type) {
                    1 -> parseType1(p, videoUrl)
                    0 -> if (context != null) sniffType0(context, p, videoUrl) else ""
                    else -> ""
                }
            }.getOrDefault("")
            if (hit.isNotBlank()) return hit
        }
        return ""
    }

    /**
     * type=1：GET `parse.url + urlencode(videoUrl)`，带 parse.header；
     * 返回 JSON 取 `url` / `data.url`；取不到或请求失败返回 ""。
     */
    private suspend fun parseType1(parse: ParseConfig, videoUrl: String): String =
        withContext(Dispatchers.IO) {
            val base = parse.url.trim()
            if (base.isBlank()) return@withContext ""
            val full = base + URLEncoder.encode(videoUrl, Charsets.UTF_8.name())
            val builder = Request.Builder().url(full).get()
            parse.header.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) {
                    runCatching { builder.header(k, v) }
                }
            }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext ""
                extractUrl(resp.body?.string().orEmpty())
            }
        }

    /**
     * 纯函数（可单元测试）：解析线路返回的 JSON，取顶层 `url`，
     * 兼容 `data.url` 包络；取不到返回 ""。
     */
    fun extractUrl(text: String): String = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        root.stringField("url").ifBlank {
            (root["data"] as? JsonObject)?.stringField("url").orEmpty()
        }
    }.getOrDefault("")

    /** 兼容字段值为数字 / 布尔 / 字符串的写法；缺失或 null 返回空串。 */
    private fun JsonObject.stringField(key: String): String =
        ((this[key] as? JsonPrimitive)?.contentOrNull).orEmpty().trim()

    /**
     * type=0：WebView 加载 `parse.url + urlencode(videoUrl)`，
     * shouldInterceptRequest 嗅探第一条 .m3u8/.mp4 直链；20 秒超时返回 ""。
     *
     * WebView 必须在主线程创建/操作，全程 withContext(Dispatchers.Main)；
     * 超时取消时在主线程销毁 WebView，避免泄漏。
     */
    private suspend fun sniffType0(
        context: Context,
        parse: ParseConfig,
        videoUrl: String,
    ): String = withTimeoutOrNull(SNIFF_TIMEOUT_MS) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var webView: WebView? = null
                try {
                    val wv = WebView(context.applicationContext)
                    webView = wv
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.mediaPlaybackRequiresUserGesture = false
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString().orEmpty()
                            if (reqUrl.isNotEmpty() && isPlayableDirectUrl(reqUrl)) {
                                // 存下该直链的请求头，播放器经 SniffPlayHeaders.take 透传
                                SniffPlayHeaders.put(reqUrl, request?.requestHeaders.orEmpty())
                                runCatching { view?.stopLoading() }
                                cont.resume(reqUrl)
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    cont.invokeOnCancellation {
                        // 取消（超时）时在主线程销毁，WebView 要求同线程操作
                        runCatching { wv.stopLoading(); wv.destroy() }
                    }
                    val target = parse.url.trim() +
                        URLEncoder.encode(videoUrl, Charsets.UTF_8.name())
                    if (parse.header.isNotEmpty()) wv.loadUrl(target, parse.header)
                    else wv.loadUrl(target)
                } catch (e: Exception) {
                    Log.w(TAG, "type0 嗅探异常", e)
                    runCatching { webView?.destroy() }
                    cont.resume("")
                }
            }
        }
    }.orEmpty()
}
