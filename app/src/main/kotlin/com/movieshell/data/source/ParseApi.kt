package com.movieshell.data.source

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.movieshell.data.model.ParseConfig
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.ParseStore
import com.movieshell.player.SniffPlayHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        LogBus.i(TAG, "开始解析：${parses.size} 条线路，url=$videoUrl")
        for ((index, p) in parses.withIndex()) {
            // v0.161.0：已熔断的线直接跳过，不再连续撞死接口
            if (ParseStore.isTripped(p.url)) {
                LogBus.i(TAG, "第 ${index + 1} 条线路已熔断，跳过：${p.name}")
                continue
            }
            val hit = runCatching {
                when (p.type) {
                    1 -> parseType1(p, videoUrl)
                    0 -> if (context != null) sniffType0(context, p, videoUrl) else ""
                    else -> ""
                }
            }.onFailure {
                // 只熔断抛异常的线；空结果（片不支持）不熔断
                ParseStore.markTripped(p.url)
                LogBus.w(TAG, "第 ${index + 1} 条线路失败：${it.message}")
            }
                .getOrDefault("")
            if (hit.isNotBlank()) {
                ParseStore.clearTripped(p.url)
                LogBus.i(TAG, "第 ${index + 1} 条线路成功：${p.name}")
                return hit
            }
        }
        LogBus.e(TAG, "全部 ${parses.size} 条线路解析失败")
        return ""
    }

    /**
     * 测活（v0.78.0）：快速判断一条解析线路是否可用，10 秒超时。
     * - type=1（JSON）：GET `url + 测试地址`，返回合法 JSON 即算存活
     *   （即使是报错 JSON，也说明服务本身在）。
     * - type=0（嗅探）：GET 解析页基地址，HTTP 200 即算存活
     *   （不做完整 WebView 嗅探，那个太慢；只验域名/服务在不在）。
     */
    suspend fun probe(parse: ParseConfig): Boolean {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
            withTimeoutOrNull(10_000L) {
                when (parse.type) {
                    1 -> {
                        val base = parse.url.trim()
                        if (base.isBlank()) return@withTimeoutOrNull false
                        val full = base + URLEncoder.encode(
                            "https://v.qq.com/x/cover/test.html", Charsets.UTF_8.name()
                        )
                        val builder = Request.Builder().url(full).get()
                        parse.header.forEach { (k, v) ->
                            if (k.isNotBlank() && v.isNotBlank()) {
                                runCatching { builder.header(k, v) }
                            }
                        }
                        client.newCall(builder.build()).execute().use { resp ->
                            if (!resp.isSuccessful) return@withTimeoutOrNull false
                            val body = resp.body?.string().orEmpty().trim()
                            runCatching { json.parseToJsonElement(body).jsonObject }
                                .isSuccess
                        }
                    }
                    else -> {
                        val base = parse.url.trim()
                        if (base.isBlank()) return@withTimeoutOrNull false
                        val builder = Request.Builder().url(base).get()
                        client.newCall(builder.build()).execute().use { resp ->
                            resp.isSuccessful
                        }
                    }
                }
            } ?: false
            }.getOrDefault(false)
        }
        // v0.161.0：设置页测活通过，说明服务活了，解除熔断
        if (ok) ParseStore.clearTripped(parse.url)
        return ok
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
     * shouldInterceptRequest 嗅探直链；20 秒超时返回 ""。
     *
     * 判定走 [SniffEngine]：后缀直判（.m3u8/.mp4/.mpd/.aac）即命中；
     * 可疑无后缀 URL 异步 HEAD 看 Content-Type（不阻塞原请求）；
     * rtmp 走 libmpv（v0.152.0）：抓到即返回，不再只记日志。
     * WebView 必须在主线程创建/操作，全程 withContext(Dispatchers.Main)；
     * 超时取消时在主线程销毁 WebView，避免泄漏。
     */
    private suspend fun sniffType0(
        context: Context,
        parse: ParseConfig,
        videoUrl: String,
    ): String = withTimeoutOrNull(SNIFF_TIMEOUT_MS) {
        SniffConfig.ensureLoaded(context)
        // v0.109.0：HEAD 探测协程跟随本次嗅探的生命周期。WebViewClient 是本函数内创建、
        // 本函数返回即销毁（无更长的宿主组件），所以 scope 以本次嗅探调用为边界：
        // 命中/超时/异常/取消时整体取消，不再用 GlobalScope 裸奔耗资源。
        val probeJob = SupervisorJob()
        val probeScope = CoroutineScope(probeJob + Dispatchers.IO)
        try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    var webView: WebView? = null
                    // 探测是异步的（HEAD 回来时拦截早已返回），resume 必须防重入
                    fun tryResume(url: String) {
                        if (!cont.isCompleted) cont.resume(url)
                    }
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
                                if (reqUrl.isNotEmpty()) {
                                    when (SniffEngine.sniffKind(reqUrl)) {
                                        SniffEngine.SniffKind.DIRECT -> {
                                            // 存下该直链的请求头，播放器经 SniffPlayHeaders.take 透传
                                            SniffPlayHeaders.put(reqUrl, request?.requestHeaders.orEmpty())
                                            probeJob.cancel()
                                            runCatching { view?.stopLoading() }
                                            tryResume(reqUrl)
                                        }
                                        SniffEngine.SniffKind.RTMP -> {
                                            // v0.152.0：rtmp 走 libmpv 可播，直接当嗅探结果返回；
                                            // 不存 SniffPlayHeaders（rtmp 不走 http 请求头），
                                            // 未装 libmpv 时 EngineHost.play 拒绝并提示。
                                            LogBus.i(TAG, "type0 抓到 rtmp，走 libmpv：$reqUrl")
                                            probeJob.cancel()
                                            runCatching { view?.stopLoading() }
                                            tryResume(reqUrl)
                                        }
                                        null -> if (SniffEngine.isSuspiciousMediaUrl(reqUrl)) {
                                            // 切 IO 做 HEAD 探测，不阻塞拦截线程
                                            val referer = request?.requestHeaders?.get("Referer")
                                            probeScope.launch {
                                                val kind = SniffEngine.probeContentType(reqUrl, client)
                                                if (kind == SniffEngine.SniffKind.DIRECT) {
                                                    if (!referer.isNullOrBlank()) {
                                                        SniffPlayHeaders.put(
                                                            reqUrl,
                                                            mapOf("Referer" to referer),
                                                        )
                                                    }
                                                    withContext(Dispatchers.Main) {
                                                        runCatching { view?.stopLoading() }
                                                        tryResume(reqUrl)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        cont.invokeOnCancellation {
                            // 取消（超时）时：停掉 HEAD 探测 + 在主线程销毁 WebView（要求同线程操作）
                            probeJob.cancel()
                            runCatching { wv.stopLoading(); wv.destroy() }
                        }
                        val target = parse.url.trim() +
                            URLEncoder.encode(videoUrl, Charsets.UTF_8.name())
                        if (parse.header.isNotEmpty()) wv.loadUrl(target, parse.header)
                        else wv.loadUrl(target)
                    } catch (e: Exception) {
                        Log.w(TAG, "type0 嗅探异常", e)
                        runCatching { webView?.destroy() }
                        tryResume("")
                    }
                }
            }
        } finally {
            probeJob.cancel()
        }
    }.orEmpty()
}
