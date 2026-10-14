package com.movieshell.ui.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.SniffEngine
import com.movieshell.data.source.SniffEngine.SniffKind
import com.movieshell.player.SniffPlayHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** 嗅探超时：20 秒内没抓到直链则提示用户，可继续手动浏览 */
private const val SNIFF_TIMEOUT_MS = 20_000L

/** 拦截到的 URL 记录上限（供"嗅探测试"用，太多只留最近的） */
private const val SEEN_URL_CAP = 300

/**
 * 网页源嗅探页（v0.10 增强）：可见 WebView 加载目标站，用户可自行浏览点播。
 *
 * 嗅探三层（判定全在 [SniffEngine]）：
 * 1. 后缀直判：shouldInterceptRequest 抓 .m3u8/.mp4/.mpd/.aac 直接命中；
 *    rtmp 标"需外部播放"（App 内播不了，只 Toast 提示一次）。
 * 2. 无后缀流探测：URL 可疑（path/query 含 video/play/stream 等词）时异步 HEAD
 *    看 Content-Type 判定，不阻塞原请求、不全量探测。
 * 3. iframe 剥壳：页面加载完用 JS 抠所有 iframe src，逐个走第 2 条探测。
 *
 * 命中直链后 stopLoading（省流量）并回调 onPlay；20 秒超时仅 Toast 提示不关页面。
 * Service Worker 嗅探是补齐：SW 上下文的 fetch，WebViewClient 拦不到；
 * 两条链路共用 hitDone 去重，先命中的生效。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSniffScreen(
    name: String,
    url: String,
    onBack: () -> Unit,
    onPlay: (directUrl: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val probeScope = rememberCoroutineScope()
    val probeClient = remember { CmsApi.defaultClient() }

    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf(name) }
    var currentUrl by remember { mutableStateOf(url) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    // 首条命中标记：只取第一条直链
    val hitDone = remember { AtomicBoolean(false) }
    // 超时提示标记：超时后只提示一次
    var timeoutToasted by remember { mutableStateOf(false) }
    // AndroidView factory 里创建的 WebView 引用
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // 拦截到的 URL（去重保序，供"嗅探测试"用；拦截回调在后台线程，用同步容器）
    val seenUrls = remember { java.util.Collections.synchronizedSet(LinkedHashSet<String>()) }
    // rtmp 已提示过的 URL（一个 URL 只提示一次）
    val rtmpToasted = remember { java.util.Collections.synchronizedSet(mutableSetOf<String>()) }
    // 嗅探测试：结果列表，null=没在测
    var testResults by remember { mutableStateOf<List<Pair<String, SniffKind>>?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun rememberSeen(u: String) {
        if (u.isBlank()) return
        if (seenUrls.size >= SEEN_URL_CAP) {
            // 超上限：清掉一半最老的（同步容器上操作）
            synchronized(seenUrls) {
                val it = seenUrls.iterator()
                var n = seenUrls.size / 2
                while (n-- > 0 && it.hasNext()) { it.next(); it.remove() }
            }
        }
        seenUrls.add(u)
    }

    /**
     * 命中直链的统一处理：先把 Referer/UA/Cookie 存进 [SniffPlayHeaders]，
     * 再 stopLoading（省流量）-> 交给播放器。全局只执行一次。
     * v0.21.0：补传 Cookie（WebView 的 CookieManager 里取，防盗链站点常要它）。
     */
    fun onHit(view: WebView, reqReferer: String?, directUrl: String) {
        if (!hitDone.compareAndSet(false, true)) return
        view.post {
            runCatching {
                val referer = reqReferer ?: view.url.orEmpty()
                val userAgent = runCatching { view.settings?.userAgentString }.getOrNull().orEmpty()
                val cookie = runCatching {
                    CookieManager.getInstance().getCookie(directUrl)
                }.getOrNull().orEmpty()
                val headers = buildMap {
                    if (referer.isNotBlank()) put("Referer", referer)
                    if (userAgent.isNotBlank()) put("User-Agent", userAgent)
                    if (cookie.isNotBlank()) put("Cookie", cookie)
                }
                SniffPlayHeaders.put(directUrl, headers)
                view.stopLoading()
                onPlay(directUrl, pageTitle.ifBlank { name })
            }
        }
    }

    fun onRtmp(view: WebView, rtmpUrl: String) {
        if (!rtmpToasted.add(rtmpUrl)) return
        view.post {
            Toast.makeText(context, "抓到 rtmp 流，需用外部播放器打开", Toast.LENGTH_LONG).show()
        }
    }

    /** 可疑 URL 的异步 Content-Type 探测（不阻塞原请求） */
    fun probeAsync(view: WebView, reqReferer: String?, target: String) {
        probeScope.launch(Dispatchers.IO) {
            val kind = SniffEngine.probeContentType(target, probeClient) ?: return@launch
            when (kind) {
                SniffKind.DIRECT -> onHit(view, reqReferer, target)
                SniffKind.RTMP -> onRtmp(view, target)
            }
        }
    }

    /** iframe 剥壳：页面加载完抠 iframe src，逐个探测直链 */
    fun probeIframes(view: WebView) {
        if (hitDone.get()) return
        view.evaluateJavascript(
            "(function(){var r=[];var f=document.getElementsByTagName('iframe');" +
                "for(var i=0;i<f.length;i++){var s=f[i].src;if(s)r.push(s);}" +
                "return JSON.stringify(r);})()",
        ) { raw ->
            if (hitDone.get()) return@evaluateJavascript
            val srcs = runCatching {
                // evaluateJavascript 返回 JSON 字符串字面量，先解开转义
                val decoded = JSONObject("{\"v\":$raw}").getString("v")
                SniffEngine.extractIframeSrcs(decoded, view.url.orEmpty())
            }.getOrDefault(emptyList())
            for (src in srcs) {
                if (hitDone.get()) break
                rememberSeen(src)
                when (SniffEngine.sniffKind(src)) {
                    SniffKind.DIRECT -> {
                        onHit(view, view.url, src)
                        break
                    }
                    SniffKind.RTMP -> onRtmp(view, src)
                    null -> {
                        if (SniffEngine.isSuspiciousMediaUrl(src)) probeAsync(view, view.url, src)
                    }
                }
            }
        }
    }

    fun showTimeoutToast() {
        if (!timeoutToasted && !hitDone.get()) {
            timeoutToasted = true
            Toast.makeText(context, "未嗅探到可播放直链，可继续手动浏览", Toast.LENGTH_LONG).show()
        }
    }

    /** 一键嗅探测试：对已拦截的 URL + 当前页 iframe 逐个做 Content-Type 判定，结果弹列表 */
    fun runSniffTest() {
        val view = webViewRef ?: return
        if (testing) return
        testing = true
        probeScope.launch(Dispatchers.IO) {
            val candidates = LinkedHashSet<String>()
            synchronized(seenUrls) { candidates += seenUrls }
            // 当前页 iframe 也加进来
            val iframeSrcs = withContext(Dispatchers.Main) {
                runCatching {
                    var out: List<String> = emptyList()
                    val latch = java.util.concurrent.CountDownLatch(1)
                    view.evaluateJavascript(
                        "(function(){var r=[];var f=document.getElementsByTagName('iframe');" +
                            "for(var i=0;i<f.length;i++){var s=f[i].src;if(s)r.push(s);}" +
                            "return JSON.stringify(r);})()",
                    ) { raw ->
                        out = runCatching {
                            val decoded = JSONObject("{\"v\":$raw}").getString("v")
                            SniffEngine.extractIframeSrcs(decoded, view.url.orEmpty())
                        }.getOrDefault(emptyList())
                        latch.countDown()
                    }
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    out
                }.getOrDefault(emptyList())
            }
            candidates += iframeSrcs
            val results = mutableListOf<Pair<String, SniffKind>>()
            for (u in candidates) {
                if (hitDone.get()) break
                val kind = SniffEngine.sniffKind(u)
                    ?: if (SniffEngine.isSuspiciousMediaUrl(u)) {
                        SniffEngine.probeContentType(u, probeClient)
                    } else null
                if (kind != null) results += u to kind
            }
            withContext(Dispatchers.Main) {
                testing = false
                testResults = results
                if (results.isEmpty()) {
                    Toast.makeText(context, "嗅探测试：未发现可播直链", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(pageTitle, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
                // v0.10：浏览器式头部——后退/前进/刷新 + 当前 URL + 嗅探测试
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { webViewRef?.goBack() },
                        enabled = canGoBack,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "后退")
                    }
                    IconButton(
                        onClick = { webViewRef?.goForward() },
                        enabled = canGoForward,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "前进")
                    }
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    Text(
                        text = currentUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = ::runSniffTest,
                        enabled = !testing,
                    ) {
                        Text(if (testing) "测试中…" else "嗅探测试")
                    }
                }
            }
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        // v0.10：混合内容放行 + 第三方 Cookie（部分站点的视频/鉴权依赖它们）
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank()) pageTitle = title
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                currentUrl = view?.url ?: url.orEmpty()
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                                if (view != null) probeIframes(view)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                val webView = view
                                val reqUrl = request?.url?.toString().orEmpty()
                                if (webView != null && reqUrl.isNotEmpty()) {
                                    rememberSeen(reqUrl)
                                    // 拦截线程可安全读 request
                                    val reqReferer = runCatching { request?.requestHeaders?.get("Referer") }
                                        .getOrNull()?.takeIf { it.isNotBlank() }
                                    when (SniffEngine.sniffKind(reqUrl)) {
                                        SniffKind.DIRECT -> onHit(webView, reqReferer, reqUrl)
                                        SniffKind.RTMP -> onRtmp(webView, reqUrl)
                                        null -> if (SniffEngine.isSuspiciousMediaUrl(reqUrl)) {
                                            probeAsync(webView, reqReferer, reqUrl)
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        // Service Worker 嗅探补齐：部分站点的 fetch 走 SW 上下文，WebViewClient 拦不到。
                        // 不支持该特性的 WebView 静默跳过（不崩）；命中时走与上面完全相同的 onHit 命中处理，
                        // 共用 hitDone 去重；未命中返回 null 不拦截。
                        runCatching {
                            if (WebViewFeature.isFeatureSupported(
                                    WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST
                                )
                            ) {
                                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                                    object : ServiceWorkerClientCompat() {
                                        override fun shouldInterceptRequest(
                                            request: WebResourceRequest
                                        ): WebResourceResponse? {
                                            val reqUrl = request.url.toString()
                                            if (reqUrl.isEmpty()) return null
                                            // SW 回调在后台线程：Referer 在这里读好，onHit 内部切回 UI 线程
                                            val reqReferer = request.requestHeaders["Referer"]
                                                ?.takeIf { it.isNotBlank() }
                                            when (SniffEngine.sniffKind(reqUrl)) {
                                                SniffKind.DIRECT -> onHit(this@apply, reqReferer, reqUrl)
                                                SniffKind.RTMP -> onRtmp(this@apply, reqUrl)
                                                null -> { /* SW 上下文不做 HEAD 探测，避免跨线程复杂 */ }
                                            }
                                            return null
                                        }
                                    }
                                )
                            }
                        }
                        webViewRef = this
                        loadUrl(url)
                    }
                },
            )
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // 嗅探测试结果弹窗：点 DIRECT 条目直接播放
    val results = testResults
    if (results != null) {
        AlertDialog(
            onDismissRequest = { testResults = null },
            title = { Text("嗅探测试（${results.size} 条）") },
            text = {
                if (results.isEmpty()) {
                    Text("没抓到可播直链，可以换个页面再试一次。")
                } else {
                    LazyColumn {
                        items(results, key = { it.first }) { (u, kind) ->
                            val label = if (kind == SniffKind.DIRECT) "可播" else "rtmp·需外部播放"
                            TextButton(
                                onClick = {
                                    testResults = null
                                    val view = webViewRef
                                    if (kind == SniffKind.DIRECT && view != null) {
                                        onHit(view, view.url, u)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "rtmp 流需用外部播放器打开",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start,
                                ) {
                                    Text(
                                        text = "[$label]",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = u,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { testResults = null }) { Text("关闭") }
            },
        )
    }

    // 20 秒嗅探超时：只 Toast，不关闭页面
    LaunchedEffect(url) {
        delay(SNIFF_TIMEOUT_MS)
        showTimeoutToast()
    }

    // 页面销毁时销毁 WebView，防止泄漏；同时清掉 SW 嗅探拦截器，
    // 避免拦截器持有已销毁 WebView 的引用
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.apply {
                    stopLoading()
                    removeAllViews()
                    destroy()
                }
            }
            webViewRef = null
            runCatching {
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(null)
            }
        }
    }
}
