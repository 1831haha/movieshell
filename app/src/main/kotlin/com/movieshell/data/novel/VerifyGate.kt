package com.movieshell.data.novel

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * v0.201.0：Cloudflare/WebView 验证通道（引擎 ↔ 界面）。
 *
 * v0.210.0：搜书验证先走后台——挑战页在真浏览器里通常自己就过了（JS 挑战跑完、
 * cf_clearance 落袋），不需要人看、也不需要人点。后台成了直接回填 HTML + Cookie；
 * 只有真人验证码、超时才返回 null，调用方再弹可见验证页（老路子，兜底）。
 * 后台 WebView 全程在主线程建/毁，轮询 delay 不阻塞主线程；后台任何异常都
 * 只返回 null，绝不让验证拖垮搜索。
 *
 * 引擎在 IO 线程调 awaitVerify 挂起等待；界面层 VerifyGateDialog 收到请求后弹
 * 全屏 WebView，用户手动过验证后点「验证完成」，结果（HTML + 落地 URL + Cookie）
 * 回填；超时（默认 180 秒）或用户取消返回 null，规则求值按空结果处理，不崩。
 *
 * 同一时间只服务一个请求：新请求到来时旧请求的等待被取消（规则求值侧
 * runCatching 兜底，表现为该次规则返回空）。
 */
object VerifyGate {

    data class Request(val url: String, val title: String, val ua: String?)

    data class Result(val html: String, val url: String, val cookieHeader: String)

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    private var deferred: CompletableDeferred<Result?>? = null

    private var appContext: Context? = null

    /** MainActivity.onCreate 里调一次，给后台验证提供建 WebView 用的上下文。 */
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    suspend fun awaitVerify(
        url: String,
        title: String,
        ua: String?,
        timeoutMs: Long = 180_000,
    ): Result? {
        deferred?.cancel()
        val d = CompletableDeferred<Result?>()
        deferred = d
        _request.value = Request(url, title, ua)
        return try {
            withTimeoutOrNull(timeoutMs) { d.await() }
        } finally {
            if (_request.value?.url == url) _request.value = null
            if (deferred === d) deferred = null
        }
    }

    fun complete(result: Result?) {
        deferred?.complete(result)
    }

    /**
     * v0.210.0：后台验证（不弹页面）。
     * 建一个不挂界面的 WebView 跑验证：挑战标记消失且正文够长，或 cf_clearance
     * 已落地，即算通过，取 HTML + Cookie 返回；超时、没上下文、任何异常都
     * 返回 null，调用方再弹可见验证页。
     */
    suspend fun awaitVerifyBackground(url: String, ua: String?, timeoutMs: Long = 30_000): Result? {
        val ctx = appContext ?: return null
        return try {
            withContext(Dispatchers.Main) {
                var webView: WebView? = null
                var pageUrl = url
                var pageDone = false
                try {
                    val view = WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        ua?.let { settings.userAgentString = it }
                        // 不挂界面也给一个手机尺寸，挑战指纹里窗口大小才正常
                        layout(0, 0, 1080, 1920)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, finishedUrl: String) {
                                pageUrl = finishedUrl
                                pageDone = true
                            }
                        }
                        loadUrl(url)
                    }.also { webView = it }
                    var outcome: Result? = null
                    withTimeoutOrNull(timeoutMs) {
                        while (outcome == null) {
                            if (pageDone) {
                                val html = evalHtml(view)
                                if (html.isNotBlank()) {
                                    val cookies = collectCookies(url, pageUrl)
                                    val passed = cookies.contains("cf_clearance=") ||
                                        (challengeMarkers.none { html.contains(it) } && html.length > 500)
                                    if (passed) outcome = Result(html, pageUrl, cookies)
                                }
                            }
                            if (outcome == null) delay(1500)
                        }
                    }
                    outcome
                } finally {
                    webView?.destroy()
                }
            }
        } catch (e: CancellationException) {
            // v0.247.0（取消语义）：验证等待被取消时原样抛出，不当成验证失败
            // 返回 null——否则调用方会继续弹可见验证页，白白多走一步。
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private val challengeMarkers = listOf(
        "Just a moment",
        "cf-challenge",
        "Attention Required",
        "Checking if the site connection is secure",
    )

    private suspend fun evalHtml(view: WebView): String = suspendCancellableCoroutine { cont ->
        view.evaluateJavascript("(function(){return document.documentElement.outerHTML})()") { raw ->
            cont.resume(decodeHtml(raw))
        }
    }

    private fun collectCookies(vararg urls: String): String {
        return urls
            .flatMap { u -> CookieManager.getInstance().getCookie(u).orEmpty().split(";") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("; ")
    }

    /** evaluateJavascript 回调是 JSON 字符串字面量，包一层再取即得原文（验证窗同款）。 */
    private fun decodeHtml(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching { JSONObject("{\"v\":$raw}").getString("v") }.getOrDefault("")
    }
}
