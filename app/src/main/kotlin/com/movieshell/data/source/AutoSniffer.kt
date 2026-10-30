package com.movieshell.data.source

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.movieshell.data.repo.LogBus
import com.movieshell.player.PlayHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * v0.294.7（H4 / Bug6）：后台自动嗅探核心——解析线路 type=0、规则 parse=1 的页面嗅探、
 * playUrl 解析页前缀、解析线路测活都走这里。与手动嗅探页共用 [SniffEngine] 判定与
 * [SniffScripts] 注入脚本：
 * - 网络拦截：后缀/`/m3u8` 路径/订阅 rules/用户正则；主 frame 导航不误判；ts 分片线索；
 *   可疑无后缀地址 HEAD 探测；
 * - 去广告：广告域名直接拦截（含订阅 ads）、广告地址不进候选、注入去广告脚本；
 * - JS 层：video src 钩子、动态 iframe 追踪、blob(MSE) 深挖、懒播放触发、站点 click 选择器；
 * - 结算：入选前带播放头做一次 Content-Type 验证，网页/脚本类直接排除；
 * - 候选择优：m3u8 立即收；mp4 等先挂起 2.5 秒等更优的 m3u8（防片头广告 mp4 抢跑）；
 * - WebView：混合内容放行、第三方 Cookie、UA 取线路 header 或嗅探 UA 设置（不带 wv 标记）；
 *   全局最多 2 个后台 WebView 并发。
 * 命中的请求头 = 拦截原始头 + Cookie（CookieManager）+ UA/Referer 补缺。
 *
 * v0.294.9：
 * - 命中时把同页排名前 3 的候选登记到 [SniffAlternates]，播放失败先试同页备选再换线路；
 * - 不加载图片（blockNetworkImage）、字体/图片请求直接回空——页面更快、少耗流量（参考海阔 blockRules）；
 * - 订阅 rules 的 `script`：命中域名的页面每 500ms 执行一次，返回 http 地址即作为候选。
 */
object AutoSniffer {

    private const val TAG = "AutoSniffer"
    private const val GRACE_MS = 2_500L

    /** 后台嗅探 WebView 全局并发上限 */
    private val gate = Semaphore(2)

    data class Hit(val url: String, val headers: Map<String, String>)

    suspend fun sniff(
        context: Context,
        pageUrl: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = SniffConfig.autoSniffTimeoutMs,
        click: String = "",
        label: String = "",
    ): Hit? {
        if (!(pageUrl.startsWith("http://", true) || pageUrl.startsWith("https://", true))) return null
        SniffConfig.ensureLoaded(context)
        SniffRules.ensureLoaded(context)
        return gate.withPermit {
            withTimeoutOrNull(timeoutMs) { runOnce(context, pageUrl, PlayHeaders.normalize(headers), click, label) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runOnce(
        context: Context,
        pageUrl: String,
        headers: Map<String, String>,
        click: String,
        label: String,
    ): Hit? {
        val client: OkHttpClient = CmsApi.defaultClient()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        var wvRef: WebView? = null
        try {
            return withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val done = AtomicBoolean(false)
                    val seen = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
                    val pageRef = java.util.concurrent.atomic.AtomicReference(pageUrl)
                    var best: Hit? = null
                    var bestRank = Int.MAX_VALUE
                    // 全部通过结算的候选（主线程读写），命中时取前 3 登记备选
                    val accepted = ArrayList<Pair<Int, Hit>>()
                    var graceJob: Job? = null
                    val ua = PlayHeaders.get(headers, "User-Agent")?.takeIf { it.isNotBlank() } ?: SniffConfig.uaString()

                    fun finish(hit: Hit?) {
                        if (!done.compareAndSet(false, true)) return
                        job.cancel()
                        if (hit != null) {
                            LogBus.i(TAG, "${label.ifBlank { "嗅探" }}命中：${hit.url.take(120)}")
                            runCatching {
                                val others = accepted.sortedBy { it.first }.map { it.second }.filter { it.url != hit.url }
                                val group = (listOf(hit) + others).map { SniffAlternates.Candidate(it.url, it.headers) }
                                SniffAlternates.register(group)
                                if (group.size > 1) LogBus.d(TAG, "同页备选 ${minOf(group.size, 3) - 1} 个")
                            }
                        }
                        if (cont.isActive) cont.resume(hit)
                    }

                    fun buildHeaders(url: String, req: Map<String, String>?): Map<String, String> {
                        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
                        val extra = buildMap {
                            put("User-Agent", ua)
                            pageRef.get().takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                            if (cookie.isNotBlank()) put("Cookie", cookie)
                        }
                        // 拦截原始头优先；Cookie/UA/Referer 只补缺（WebView 拦截头不含 Cookie）
                        return PlayHeaders.merge(req, extra)
                    }

                    // 主线程：按优先级收候选
                    fun offerMain(hit: Hit) {
                        if (done.get()) return
                        val rank = SniffEngine.rankIndex(hit.url)
                        accepted.add(rank to hit)
                        if (rank == 0) { finish(hit); return }
                        if (rank < bestRank) { best = hit; bestRank = rank }
                        if (graceJob == null) {
                            graceJob = CoroutineScope(job + Dispatchers.Main).launch {
                                delay(GRACE_MS)
                                finish(best)
                            }
                        }
                    }

                    // 任意线程：结算验证后入选
                    fun candidate(url: String, req: Map<String, String>?, verify: Boolean = true) {
                        if (done.get() || url.isBlank() || !seen.add(url)) return
                        if (SniffConfig.adFilterEnabled && SniffEngine.isAdUrl(url)) {
                            LogBus.d(TAG, "广告地址跳过：${url.take(100)}")
                            return
                        }
                        val h = buildHeaders(url, req)
                        scope.launch {
                            val denied = verify && !SniffEngine.matchesCustomPattern(url) &&
                                SniffEngine.settlementDenied(url, client, h)
                            if (denied) {
                                LogBus.d(TAG, "结算排除（网页/脚本）：${url.take(100)}")
                                return@launch
                            }
                            withContext(Dispatchers.Main) { offerMain(Hit(url, h)) }
                        }
                    }

                    fun consider(url: String, req: Map<String, String>?, isMainFrame: Boolean) {
                        if (done.get() || url.isBlank()) return
                        when (SniffEngine.sniffKind(url, isMainFrame)) {
                            SniffEngine.SniffKind.DIRECT -> candidate(url, req)
                            SniffEngine.SniffKind.RTMP -> { if (seen.add(url)) scope.launch(Dispatchers.Main) { finish(Hit(url, emptyMap())) } }
                            null -> when {
                                SniffEngine.isTsSegment(url) -> SniffEngine.tsCluePlaylists(url).forEach { pl ->
                                    if (seen.add("probe:$pl")) scope.launch {
                                        if (SniffEngine.probeContentType(pl, client) == SniffEngine.SniffKind.DIRECT) candidate(pl, req, verify = false)
                                    }
                                }
                                !isMainFrame && SniffEngine.isSuspiciousMediaUrl(url) -> if (seen.add("probe:$url")) scope.launch {
                                    if (SniffEngine.probeContentType(url, client) == SniffEngine.SniffKind.DIRECT) candidate(url, req, verify = false)
                                }
                                else -> Unit
                            }
                        }
                    }

                    val wv = WebView(context.applicationContext)
                    wvRef = wv
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.mediaPlaybackRequiresUserGesture = false
                    wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    wv.settings.userAgentString = ua
                    // v0.294.9：后台嗅探不加载图片（只要视频地址，页面更快）
                    wv.settings.blockNetworkImage = true
                    runCatching {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    }
                    PlayHeaders.get(headers, "Cookie")?.let { c ->
                        runCatching { c.split(';').forEach { kv -> CookieManager.getInstance().setCookie(pageUrl, kv.trim()) } }
                    }
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            if (!url.isNullOrBlank()) pageRef.set(url)
                            view?.evaluateJavascript(JS_HOOK_INSTALL, null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view ?: return
                            view.evaluateJavascript(JS_HOOK_INSTALL, null)
                            view.evaluateJavascript(JS_IFRAME_OBSERVER, null)
                            if (SniffConfig.adFilterEnabled) view.evaluateJavascript(JS_RM_AD, null)
                            if (click.isNotBlank()) view.evaluateJavascript(clickJs(click), null)
                            // 页面里直接写着的 iframe 也剥一层
                            view.evaluateJavascript(
                                "(function(){var r=[];var f=document.getElementsByTagName('iframe');" +
                                    "for(var i=0;i<f.length;i++){if(f[i].src)r.push(f[i].src);}return JSON.stringify(r);})()",
                            ) { raw ->
                                parseRootStringArray(decodeJsResult(raw)).forEach { src ->
                                    consider(SniffEngine.resolveUrl(pageRef.get(), src), null, false)
                                }
                            }
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val reqUrl = request?.url?.toString().orEmpty()
                            if (reqUrl.isEmpty() || done.get()) return super.shouldInterceptRequest(view, request)
                            if (SniffConfig.adFilterEnabled && SniffEngine.isAdHost(request?.url?.host.orEmpty())) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }
                            val mainFrame = request?.isForMainFrame == true
                            consider(reqUrl, request?.requestHeaders, mainFrame)
                            // v0.294.9：字体 / 图片请求直接回空（先判过候选再拦，伪装成图片的分片不受影响）
                            if (!mainFrame && isStaticJunk(reqUrl)) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    // JS 轮询：video src / hook / 动态 iframe / blob 深挖 / 懒播放 / click
                    CoroutineScope(job + Dispatchers.Main).launch {
                        var ticks = 0
                        var blobDug = false
                        var lazyDone = false
                        while (isActive && !done.get()) {
                            delay(1_500)
                            ticks++
                            if (!lazyDone && ticks >= 3 && best == null) {
                                lazyDone = true
                                wv.evaluateJavascript(JS_LAZY_PLAY, null)
                                if (click.isNotBlank()) wv.evaluateJavascript(clickJs(click), null)
                            }
                            wv.evaluateJavascript(JS_VIDEO_POLL) { raw ->
                                val decoded = decodeJsResult(raw)
                                parseStringArray(decoded, "f").forEach { f ->
                                    consider(SniffEngine.resolveUrl(pageRef.get(), f), null, false)
                                }
                                parseStringArray(decoded, "v").forEach { v ->
                                    when {
                                        v.startsWith("blob:", true) -> if (!blobDug) {
                                            blobDug = true
                                            wv.evaluateJavascript(JS_BLOB_DIG) { r2 ->
                                                parseRootStringArray(decodeJsResult(r2)).forEach { u ->
                                                    val clean = u.replace("\\/", "/").replace("\\u0026", "&")
                                                    consider(clean, null, false)
                                                }
                                            }
                                        }
                                        v.startsWith("http", true) -> consider(SniffEngine.resolveUrl(pageRef.get(), v), null, false)
                                    }
                                }
                            }
                        }
                    }
                    // v0.294.9：订阅 rules 的 script（命中当前页面域名才跑；没有就不启动）
                    CoroutineScope(job + Dispatchers.Main).launch {
                        delay(800)
                        while (isActive && !done.get()) {
                            val scripts = runCatching { SniffRules.scriptsFor(pageRef.get()) }.getOrDefault(emptyList())
                            if (scripts.isEmpty()) { delay(1_500); continue }
                            scripts.forEach { sc ->
                                wv.evaluateJavascript(ruleScriptJs(sc)) { raw ->
                                    val r = decodeJsResult(raw).trim()
                                    if (r.startsWith("http://", true) || r.startsWith("https://", true) || r.startsWith("//")) {
                                        candidate(SniffEngine.resolveUrl(pageRef.get(), r), null)
                                    }
                                }
                            }
                            delay(500)
                        }
                    }
                    cont.invokeOnCancellation { job.cancel() }
                    val extra = headers.filterKeys { !it.equals("User-Agent", true) }
                    if (extra.isNotEmpty()) wv.loadUrl(pageUrl, extra) else wv.loadUrl(pageUrl)
                }
            }
        } finally {
            job.cancel()
            val wv = wvRef
            wvRef = null
            if (wv != null) {
                withContext(NonCancellable + Dispatchers.Main) {
                    runCatching { wv.stopLoading(); wv.destroy() }
                }
            }
        }
    }

    private val JUNK_SUFFIXES = listOf(
        ".woff", ".woff2", ".ttf", ".otf", ".eot",
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".bmp",
    )

    /** v0.294.9（纯函数）：字体 / 图片资源（按路径后缀） */
    fun isStaticJunk(url: String): Boolean {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return JUNK_SUFFIXES.any { path.endsWith(it) }
    }

    /**
     * 订阅 script 包装：先按函数体执行（支持 return 写法），没返回值且脚本里没有 return 再按表达式求值；
     * 异常吞掉；只把字符串结果带回。
     */
    internal fun ruleScriptJs(script: String): String {
        val q = JSONObject.quote(script)
        return "(function(){var s=$q;var r=null;try{r=(new Function(s))();}catch(e){}" +
            "if(r==null&&s.indexOf('return')<0){try{r=(0,eval)(s);}catch(e){}}" +
            "return (typeof r==='string')?r:'';})()"
    }

    private fun clickJs(selector: String): String =
        "(function(){try{document.querySelectorAll(${JSONObject.quote(selector)}).forEach(function(e){try{e.click();}catch(x){}});}catch(e){}return 'ok';})()"
}
