package com.movieshell.data.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * drpy JS 规则引擎接口（v0.2：WebView 真实现）。
 *
 * drpy 规则形如 `var rule = {host, homeUrl, url, detailUrl, searchUrl,
 * 推荐(), 一级(), 二级(), 搜索(), 播放(), class_parse(), headers}`，
 * 全部是 async 函数，用 `this.input` 取渲染后的 URL，用全局 `request(url)` 发网络。
 * 真实执行路线：隐藏 WebView 里先 evaluate 垫片（assets/drpy/shim.js），
 * 再 evaluate 规则源码；网络请求经 @JavascriptInterface 桥回 OkHttp。
 */
interface DrpyEngine {

    /** 载入一条 drpy 规则源码。必须在主线程调用。 */
    fun loadRule(js: String)

    /** 首页推荐。 */
    suspend fun home(): List<Vod>

    /** 全部分类（来自 rule.class_parse 或 class_name/class_url）。 */
    suspend fun categories(): List<CategoryType>

    /** 分类列表。 */
    suspend fun category(tid: String, pg: Int): List<Vod>

    /** 详情。 */
    suspend fun detail(id: String): VodDetail

    /** 搜索。 */
    suspend fun search(wd: String): List<Vod>

    /**
     * 取播放地址。
     * @return 最终可播的直链（规则内部已做嗅探/解析）。
     */
    suspend fun play(flag: String, id: String): String

    /** 销毁 WebView，释放资源。 */
    fun release()
}

/**
 * 基于隐藏 WebView 的 drpy 引擎实现。
 *
 * 线程模型：WebView 的创建 / evaluateJavascript / destroy 一律走主线程
 * Handler；suspend 函数用 suspendCancellableCoroutine 把 JS 回调转成协程，
 * 外层包 withTimeout(30s)。规则里的 request() 经 __bridge 同步打到 OkHttp
 * （@JavascriptInterface 回调本来就在后台线程，同步 execute 没问题）。
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class WebViewDrpyEngine(context: Context) : DrpyEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client: OkHttpClient = CmsApi.defaultClient()

    private var webView: WebView? = null

    private enum class LoadState { IDLE, LOADING, READY, FAILED }

    @Volatile
    private var loadState = LoadState.IDLE

    /** __drpyHome() 的缓存：home() 和 categories() 共用一次网络结果。 */
    @Volatile
    private var homeCache: JSONObject? = null

    // ---------------- DrpyEngine ----------------

    override fun loadRule(js: String) {
        loadState = LoadState.LOADING
        homeCache = null
        mainHandler.post {
            try {
                val wv = ensureWebView()
                val shim = appContext.assets.open("drpy/shim.js")
                    .bufferedReader().use { it.readText() }
                val script = "$shim\n;\n$js\n;typeof rule !== 'undefined' ? 'rule-ok' : 'rule-missing';"
                wv.evaluateJavascript(script) { r ->
                    val ok = r?.trim('"') == "rule-ok"
                    loadState = if (ok) LoadState.READY else LoadState.FAILED
                    if (!ok) Log.w(TAG, "drpy 规则载入失败: $r")
                }
            } catch (e: Exception) {
                Log.w(TAG, "loadRule 异常", e)
                loadState = LoadState.FAILED
            }
        }
    }

    override suspend fun home(): List<Vod> {
        val o = fetchHome() ?: return emptyList()
        return o.optJSONArray("list").toVodList()
    }

    override suspend fun categories(): List<CategoryType> {
        val o = fetchHome() ?: return emptyList()
        val arr = o.optJSONArray("class") ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                add(CategoryType(c.optString("type_id"), c.optString("type_name")))
            }
        }
    }

    override suspend fun category(tid: String, pg: Int): List<Vod> {
        val json = evalForJson(
            "(async()=>__drpyCategory(${JSONObject.quote(tid)},$pg))()"
        ) ?: return emptyList()
        return runCatching {
            JSONObject(json).optJSONArray("list").toVodList()
        }.getOrElse {
            Log.w(TAG, "category 解析失败", it)
            emptyList()
        }
    }

    override suspend fun detail(id: String): VodDetail {
        val json = evalForJson(
            "(async()=>__drpyDetail(${JSONObject.quote(id)}))()"
        ) ?: return VodDetail(vodId = id)
        return runCatching {
            val o = JSONObject(json)
            VodDetail(
                vodId = o.optString("vod_id", id),
                vodName = o.optString("vod_name"),
                vodPic = o.optString("vod_pic"),
                vodRemarks = o.optString("vod_remarks"),
                vodYear = o.optString("vod_year"),
                vodActor = o.optString("vod_actor"),
                vodDirector = o.optString("vod_director"),
                vodDesc = o.optString("vod_desc"),
                vodPlayFrom = o.optString("vod_play_from"),
                vodPlayUrl = o.optString("vod_play_url")
            )
        }.getOrElse {
            Log.w(TAG, "detail 解析失败", it)
            VodDetail(vodId = id)
        }
    }

    override suspend fun search(wd: String): List<Vod> {
        if (wd.isBlank()) return emptyList()
        val json = evalForJson(
            "(async()=>__drpySearch(${JSONObject.quote(wd)},1))()"
        ) ?: return emptyList()
        return runCatching {
            JSONObject(json).optJSONArray("list").toVodList()
        }.getOrElse {
            Log.w(TAG, "search 解析失败", it)
            emptyList()
        }
    }

    override suspend fun play(flag: String, id: String): String {
        val json = evalForJson(
            "(async()=>__drpyPlay(${JSONObject.quote(flag)},${JSONObject.quote(id)}))()"
        ) ?: return ""
        return runCatching { JSONObject(json).optString("url") }
            .getOrElse {
                Log.w(TAG, "play 解析失败", it)
                ""
            }
    }

    override fun release() {
        mainHandler.post {
            try {
                webView?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "WebView destroy 异常", e)
            }
            webView = null
            loadState = LoadState.IDLE
            homeCache = null
        }
    }

    // ---------------- 内部 ----------------

    /** 主线程创建（或复用）隐藏 WebView，只在 mainHandler 里调。 */
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(appContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.addJavascriptInterface(JsBridge(), "__bridge")
        wv.loadDataWithBaseURL(null, "<html><head></head><body></body></html>", "text/html", "utf-8", null)
        webView = wv
        return wv
    }

    private suspend fun awaitReady(): Boolean = withTimeoutOrNull(READY_TIMEOUT_MS) {
        while (loadState == LoadState.IDLE || loadState == LoadState.LOADING) delay(100)
        loadState == LoadState.READY
    } ?: false

    private suspend fun fetchHome(): JSONObject? {
        homeCache?.let { return it }
        val json = evalForJson("(async()=>__drpyHome())()") ?: return null
        return runCatching {
            val o = JSONObject(json)
            if (o.has("error")) {
                Log.w(TAG, "home 返回 error: ${o.optString("error")}")
                null
            } else {
                homeCache = o
                o
            }
        }.getOrNull()
    }

    /**
     * 在主线程 evaluate JS（表达式返回 Promise 会被 WebView resolve 后再回调），
     * 拿到 JSON 字符串；超时 / 未就绪 / 规则返回 error 时返回 null。
     */
    private suspend fun evalForJson(expression: String): String? {
        if (!awaitReady()) {
            Log.w(TAG, "引擎未就绪，跳过 $expression")
            return null
        }
        val raw = try {
            withTimeout(JS_TIMEOUT_MS) {
                suspendCancellableCoroutine<String?> { cont ->
                    mainHandler.post {
                        val wv = webView
                        if (wv == null) {
                            cont.resume(null)
                            return@post
                        }
                        try {
                            wv.evaluateJavascript(expression) { result -> cont.resume(result) }
                        } catch (e: Exception) {
                            Log.w(TAG, "evaluateJavascript 异常", e)
                            cont.resume(null)
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "JS 执行超时: $expression".take(120))
            return null
        }
        val json = unwrapJsString(raw) ?: return null
        // 垫片约定：异常时返回 {"error": "..."}，这里统一吞掉
        if (runCatching { JSONObject(json).has("error") }.getOrDefault(false)) {
            Log.w(TAG, "规则执行 error: $json".take(300))
            return null
        }
        return json
    }

    /** WebView 回调把 JS 字符串包了一层 JSON 引号，这里解掉。 */
    private fun unwrapJsString(raw: String?): String? {
        if (raw == null) return null
        val t = raw.trim()
        if (t == "null" || t == "undefined" || t.isEmpty()) return null
        return if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            runCatching { JSONObject("{\"v\":$t}").optString("v") }.getOrNull()
        } else t
    }

    private fun JSONArray?.toVodList(): List<Vod> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val o = optJSONObject(i) ?: continue
                add(
                    Vod(
                        vodId = o.optString("vod_id"),
                        vodName = o.optString("vod_name"),
                        vodPic = o.optString("vod_pic"),
                        vodRemarks = o.optString("vod_remarks"),
                        vodYear = o.optString("vod_year"),
                        typeName = o.optString("type_name")
                    )
                )
            }
        }
    }

    /** JS 桥：规则里的 request() 最终落到这里，同步 OkHttp，异常返回空串。 */
    private inner class JsBridge {
        @JavascriptInterface
        fun http(url: String, headersJson: String, method: String, body: String): String {
            return try {
                val builder = Request.Builder().url(url)
                runCatching {
                    val headers = JSONObject(headersJson)
                    val keys = headers.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        builder.header(k, headers.optString(k))
                    }
                }
                val m = method.uppercase()
                if (m == "POST" || m == "PUT" || m == "PATCH") {
                    builder.method(m, body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                } else {
                    builder.method(m, null)
                }
                client.newCall(builder.build()).execute().use { resp ->
                    resp.body?.string() ?: ""
                }
            } catch (e: Exception) {
                Log.w(TAG, "__bridge.http 异常: $url", e)
                ""
            }
        }
    }

    companion object {
        private const val TAG = "WebViewDrpyEngine"
        private const val READY_TIMEOUT_MS = 20_000L
        private const val JS_TIMEOUT_MS = 30_000L
    }
}
