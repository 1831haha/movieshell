package com.movieshell.ui.web

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebResourceRequestCompat
import androidx.webkit.WebResourceResponseCompat
import com.movieshell.player.SniffPlayHeaders
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 直链判定（纯函数，可单元测试）：
 * 只看 URL 的 path 部分（去掉 query/fragment 后）是否以 .m3u8/.mp4 结尾，
 * 忽略大小写。带 query 的 m3u8（如 xxx.m3u8?token=abc）同样命中。
 */
fun isPlayableDirectUrl(url: String): Boolean {
    val path = runCatching {
        url.substringBefore('#').substringBefore('?')
    }.getOrDefault(url).lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".mp4")
}

/** 嗅探超时：20 秒内没抓到直链则提示用户，可继续手动浏览 */
private const val SNIFF_TIMEOUT_MS = 20_000L

/**
 * 网页源嗅探页：可见 WebView 加载目标站，用户可自行浏览点播；
 * WebViewClient.shouldInterceptRequest 嗅探到第一条 m3u8/mp4 直链后
 * 停止加载并回调 onPlay 交给现有播放器；20 秒超时仅 Toast 提示不关页面。
 *
 * Service Worker 嗅探是补齐：部分站点的 fetch 走 SW 上下文，WebViewClient 拦不到；
 * 两条嗅探链路共用同一套首条命中逻辑（[hitDone] 去重），先命中的生效。
 * WebView 不支持该特性时静默跳过，绝不崩。
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

    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf(name) }
    // 首条命中标记：只取第一条直链
    val hitDone = remember { AtomicBoolean(false) }
    // 超时提示标记：超时后只提示一次
    var timeoutToasted by remember { mutableStateOf(false) }
    // AndroidView factory 里创建的 WebView 引用，供销毁用
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    /**
     * 命中直链的统一处理：先把 Referer/UA 存进 [SniffPlayHeaders]，再停止加载 -> 交给播放器
     * （全局只执行一次；WebViewClient 嗅探与 Service Worker 嗅探共用 [hitDone] 去重，
     * 第一个命中的生效）。
     *
     * Referer 优先取本次拦截到的请求的 Referer 头（部分站点校验它），
     * 拿不到则退回当前页面 URL；UA 取 WebView 实际使用的 UA
     * （页面 JS 拿到直链时用的就是它，播放器用同一 UA 最不容易被拦）。
     *
     * 拦截回调（WebViewClient / SW 都可能在后台线程）可直接调用：
     * 对 view.url/settings 的读取在 view.post 里切回 UI 线程；
     * reqReferer 已在调用方读好传进来。
     */
    fun onHit(view: WebView, reqReferer: String?, directUrl: String) {
        if (!hitDone.compareAndSet(false, true)) return
        // SW 回调可能在后台线程，view.post 切回 UI 线程；
        // 极端情况下 WebView 已销毁，包一层防崩
        view.post {
            runCatching {
                val referer = reqReferer ?: view.url.orEmpty()
                val userAgent = runCatching { view.settings?.userAgentString }.getOrNull().orEmpty()
                val headers = buildMap {
                    if (referer.isNotBlank()) put("Referer", referer)
                    if (userAgent.isNotBlank()) put("User-Agent", userAgent)
                }
                SniffPlayHeaders.put(directUrl, headers)
                view.stopLoading()
                onPlay(directUrl, pageTitle.ifBlank { name })
            }
        }
    }

    fun showTimeoutToast() {
        if (!timeoutToasted && !hitDone.get()) {
            timeoutToasted = true
            Toast.makeText(context, "未嗅探到可播放直链，可继续手动浏览", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pageTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
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
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank()) pageTitle = title
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                val webView = view
                                val reqUrl = request?.url?.toString().orEmpty()
                                if (webView != null && reqUrl.isNotEmpty() && isPlayableDirectUrl(reqUrl)) {
                                    // 拦截线程可安全读 request
                                    val reqReferer = runCatching { request?.requestHeaders?.get("Referer") }
                                        .getOrNull()?.takeIf { it.isNotBlank() }
                                    onHit(webView, reqReferer, reqUrl)
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        // Service Worker 嗅探补齐：部分站点的 fetch 走 SW 上下文，WebViewClient 拦不到。
                        // 不支持该特性的 WebView 静默跳过（不崩）；命中时走与上面完全相同的 onHit 命中处理，
                        // 共用 hitDone 去重；未命中返回 null 不拦截。
                        runCatching {
                            if (ServiceWorkerControllerCompat.isFeatureSupported(
                                    ServiceWorkerControllerCompat.FEATURE_SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST
                                )
                            ) {
                                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                                    object : ServiceWorkerClientCompat() {
                                        override fun shouldInterceptRequest(
                                            request: WebResourceRequestCompat
                                        ): WebResourceResponseCompat? {
                                            val reqUrl = request.url.toString()
                                            if (isPlayableDirectUrl(reqUrl)) {
                                                // SW 回调在后台线程：Referer 在这里读好，onHit 内部切回 UI 线程
                                                val reqReferer = request.requestHeaders["Referer"]
                                                    ?.takeIf { it.isNotBlank() }
                                                onHit(this@apply, reqReferer, reqUrl)
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
