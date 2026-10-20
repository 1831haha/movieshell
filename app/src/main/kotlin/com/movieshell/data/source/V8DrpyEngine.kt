package com.movieshell.data.source

import android.content.Context
import android.util.Base64
import android.util.Log
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.FilterGroup
import com.movieshell.data.model.FilterOption
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * v0.113.0：V8 drpy 引擎（androidx.javascriptengine，替代 app.cash.quickjs）。
 *
 * A1–A5 根治：
 * - A1/A2：每引擎一个 [JsSession]（V8 isolate），`request()` 在 JS 侧返回 Promise，
 *   宿主网络请求经 MessagePort RPC 在 IO 协程执行——JS 线程零阻塞，无共享单线程瓶颈。
 * - A4：[JsSession.eval] 带硬截止，超时直接 close isolate 真正终止脚本（V8 isolate
 *   跑在 WebView 提供的独立进程，死循环不会卡宿主线程）。
 * - A5：release 即时 close isolate，在途 evaluate 以 IsolateTerminatedException 立即失败。
 *
 * JS 环境：assets/jsbridge/host.js（RPC 基元）+ drpy/shim.js（async 垫片）+ 规则源码。
 * 设备不支持 V8 sandbox（或缺 Promise/MessagePort 能力）时 bootstrap 失败，所有调用
 * 返回空并记日志——明确失败，不静默。
 */
class V8DrpyEngine(
    context: Context,
) : DrpyEngine {

    private val appContext = context.applicationContext
    private val http: OkHttpClient = CmsApi.defaultClient()

    private var ruleJs: String? = null

    /** 每引擎一个 V8 会话（isolate + port + RPC 分发）；bootstrap 失败/超时后置 null 重建。 */
    private var session: JsSession? = null

    private val hostJs by lazy { assetText("jsbridge/host.js") }
    private val shimJs by lazy { assetText("drpy/shim.js") }

    companion object {
        private const val TAG = "V8Drpy"
        private const val JS_TIMEOUT_MS = 15_000L
        /** v0.110.0：home 缓存窗口 60s（原 10s）。规则标识 = 引擎实例本身，切源建新实例。 */
        private const val HOME_CACHE_MS = 60_000L
        /** v0.109.0 P0-1：query() 单次返回元素上限，防超大页面 JSON 爆炸。 */
        private const val MAX_QUERY_ELEMENTS = 5000
    }

    private fun assetText(path: String): String =
        appContext.assets.open(path).bufferedReader().readText()

    override fun loadRule(js: String) {
        ruleJs = js
        // v0.110.0：换规则即失效 home 缓存，避免旧规则的 home 串给新规则。
        sharedHomeJson = null
        sharedHomeAt = 0L
        // isolate.close() 线程安全、即时生效；下次调用时用新规则重建会话。
        runCatching { session?.close() }
        session = null
    }

    /** 惰性建会话：注册宿主 RPC（http/query/gunzip）→ bootstrap host.js + shim + 规则。 */
    private suspend fun ensureSession(): JsSession? {
        session?.let { return it }
        val rule = ruleJs ?: return null
        val s = JsSession(TAG)
        s.registerRpc("http") { payload -> rpcHttp(payload) }
        s.registerRpc("query") { payload -> rpcQuery(payload) }
        s.registerRpc("gunzip") { payload -> rpcGunzip(payload) }
        val portName = JsSession.newPortName()
        val scripts = listOf(
            "globalThis.__PORT_NAME = ${JSONObject.quote(portName)};",
            hostJs,
            shimJs,
            rule,
        )
        return if (s.bootstrap(portName, scripts)) {
            session = s
            LogBus.i(TAG, "V8 引擎已就绪")
            s
        } else {
            s.close()
            LogBus.e(TAG, "V8 引擎引导失败: ${s.lastError}")
            null
        }
    }

    override suspend fun home(): List<Vod> = withContext(Dispatchers.IO) {
        val json = sharedHomeJson() ?: return@withContext emptyList()
        parseVodList(json)
    }

    /**
     * v0.50.0 修：shim.js 只导出了 __drpyHome/__drpyCategory，根本没有 __drpyCategories，
     * 改走 __drpyHome 的 class 数组；与 home() 共享一次调用（60s 窗口，学 PyApi）。
     */
    override suspend fun categories(): List<CategoryType> = withContext(Dispatchers.IO) {
        val json = sharedHomeJson() ?: return@withContext emptyList()
        parseCategoriesFromHome(json)
    }

    @Volatile
    private var sharedHomeJson: String? = null
    @Volatile
    private var sharedHomeAt: Long = 0L

    private suspend fun sharedHomeJson(): String? {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = sharedHomeJson
        if (cached != null && now - sharedHomeAt < HOME_CACHE_MS) return cached
        val json = evalForJson("(function(){return __drpyHome();})()")
        if (json != null) {
            sharedHomeJson = json
            sharedHomeAt = now
        }
        return json
    }

    /** 从 __drpyHome() 的 {"class":[...],"list":[...]} 里取分类数组。 */
    private fun parseCategoriesFromHome(json: String): List<CategoryType> {
        return runCatching {
            val o = JSONObject(json)
            if (o.has("error")) {
                Log.w(TAG, "规则返回 error: ${o.optString("error")}")
                return emptyList()
            }
            val arr = o.optJSONArray("class") ?: JSONArray()
            List(arr.length()) { i ->
                val c = arr.getJSONObject(i)
                CategoryType(
                    typeId = c.optString("type_id"),
                    typeName = c.optString("type_name"),
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun category(tid: String, pg: Int, filter: Map<String, String>): List<Vod> =
        withContext(Dispatchers.IO) {
            val filterJson = JSONObject(filter as Map<*, *>).toString()
            val json = evalForJson(
                "(function(){return __drpyCategory(${JSONObject.quote(tid)}, $pg, $filterJson);})()"
            ) ?: return@withContext emptyList()
            parseVodList(json)
        }

    override suspend fun filters(tid: String): List<FilterGroup> = withContext(Dispatchers.IO) {
        val json = evalForJson("(function(){return __drpyFilters(${JSONObject.quote(tid)});})()")
            ?: return@withContext emptyList()
        parseFilters(json)
    }

    override suspend fun detail(id: String): VodDetail = withContext(Dispatchers.IO) {
        val json = evalForJson("(function(){return __drpyDetail(${JSONObject.quote(id)});})()")
            ?: return@withContext VodDetail()
        parseDetail(json)
    }

    override suspend fun search(wd: String): List<Vod> = withContext(Dispatchers.IO) {
        val json = evalForJson("(function(){return __drpySearch(${JSONObject.quote(wd)});})()")
            ?: return@withContext emptyList()
        parseVodList(json)
    }

    override suspend fun play(flag: String, id: String): String = withContext(Dispatchers.IO) {
        val json = evalForJson(
            "(function(){return __drpyPlay(${JSONObject.quote(flag)}, ${JSONObject.quote(id)});})()"
        ) ?: return@withContext ""
        parsePlayUrl(json)
    }

    override fun release() {
        // A5：isolate.close() 即时终止（在途 evaluate 立即失败），任意线程可调。
        runCatching { session?.close() }
        session = null
    }

    /**
     * evaluate 导出入口（返回 JSON 字符串的 Promise）。失败返回 null：
     * - 超时/会话终止 → 会话置 null，下次调用重建（新 isolate，无残留死循环）；
     * - 普通 JS 异常 → isolate 未损坏，保留会话（单次规则错误不牵连后续调用）。
     */
    private suspend fun evalForJson(expression: String): String? {
        return try {
            val s = ensureSession() ?: return null
            val result = s.eval(expression, JS_TIMEOUT_MS)
            result.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
        } catch (e: JsSessionTimeoutException) {
            LogBus.w(TAG, "JS 执行超时，isolate 已终止: ${e.message}")
            session = null
            null
        } catch (e: JsSessionClosedException) {
            LogBus.w(TAG, "JS 会话不可用: ${e.message}")
            session = null
            null
        } catch (e: Exception) {
            LogBus.w(TAG, "JS 执行失败: ${e.message}")
            null
        }
    }

    // ---------------- 宿主 RPC handlers（JsSession 在 IO 协程调用） ----------------

    /** kind='http'：返回 body 文本；失败 resolve ""（与 shim 的旧契约一致，不 reject）。 */
    private suspend fun rpcHttp(payload: JSONObject): String = withContext(Dispatchers.IO) {
        // shim 侧 headers 以 JSON.stringify 传输（字符串），需二次解析
        val headers = runCatching { JSONObject(payload.optString("headers")) }.getOrNull()
        val url = payload.optString("url")
        val method = payload.optString("method", "GET")
        val body = payload.optString("body")
        doRequest(url, method, headers, body)
    }

    /** kind='query'：jsoup HTML 查询（pdfh/pdfa 用），返回 JSON 数组；失败返回 "[]"。 */
    private fun rpcQuery(payload: JSONObject): String {
        return runCatching {
            val doc = Jsoup.parseBodyFragment(payload.optString("html"))
            val sel = payload.optString("sel")
            val els = if (sel.isBlank()) {
                listOfNotNull(doc.body())
            } else {
                doc.select(sel).take(MAX_QUERY_ELEMENTS)
            }
            val arr = JSONArray()
            els.forEach { el ->
                val attrs = JSONObject()
                el.attributes().forEach { attrs.put(it.key, it.value) }
                arr.put(
                    JSONObject()
                        .put("text", el.text())
                        .put("html", el.html())
                        .put("outer", el.outerHtml())
                        .put("attrs", attrs),
                )
            }
            arr.toString()
        }.getOrDefault("[]")
    }

    /** kind='gunzip'：base64(gzip) → 解压文本；失败返回 ""。 */
    private fun rpcGunzip(payload: JSONObject): String {
        return runCatching {
            val bytes = Base64.decode(payload.optString("data"), Base64.DEFAULT)
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.onFailure {
            LogBus.w(TAG, "gunzip 失败: ${it.message}")
        }.getOrDefault("")
    }

    /**
     * v0.99.0：统一网络执行（IO 协程内，不再阻塞任何 JS 线程）。
     * POST/PUT/PATCH 的 Content-Type 从 headers 里读，默认 application/x-www-form-urlencoded（P1-3）。
     */
    private fun doRequest(url: String, method: String, headers: JSONObject?, body: String): String {
        val call = runCatching {
            val reqBuilder = Request.Builder().url(url)
            headers?.keys()?.forEach { k ->
                reqBuilder.header(k, headers.optString(k))
            }
            when (val m = method.uppercase()) {
                "POST", "PUT", "PATCH" -> {
                    val ct = headers?.optString("Content-Type")?.ifBlank { null }
                        ?: headers?.optString("content-type")?.ifBlank { null }
                        ?: "application/x-www-form-urlencoded"
                    reqBuilder.method(m, body.toRequestBody(ct.toMediaTypeOrNull()))
                }
                "GET" -> reqBuilder.get()
                else -> reqBuilder.method(m, null)
            }
            http.newCall(reqBuilder.build())
        }.getOrNull() ?: return ""
        return try {
            call.execute().use { resp -> resp.body?.string() ?: "" }
        } catch (e: Exception) {
            LogBus.w(TAG, "request 失败: $url ${e.message}")
            ""
        }
    }

    // ---------------- 解析（与旧 QuickJS 版一致） ----------------

    private fun parseVodList(json: String): List<Vod> {
        return runCatching {
            val o = JSONObject(json)
            if (o.has("error")) {
                Log.w(TAG, "规则返回 error: ${o.optString("error")}")
                return emptyList()
            }
            val arr = o.optJSONArray("list") ?: JSONArray()
            List(arr.length()) { i ->
                val v = arr.getJSONObject(i)
                Vod(
                    vodId = v.optString("vod_id"),
                    vodName = v.optString("vod_name"),
                    vodPic = v.optString("vod_pic"),
                    vodRemarks = v.optString("vod_remarks"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * v0.44.0：实现 drpy 筛选解析。
     * shim 的 __drpyFilters 返回 {"filterable":1,"groups":[{"key","name","value":[{"n","v"}]}]}，
     * 对到 FilterGroup(key, name, options=[FilterOption(n->name, v->value)])。
     */
    private fun parseFilters(json: String): List<FilterGroup> {
        return runCatching {
            val o = JSONObject(json)
            if (o.has("error")) {
                Log.w(TAG, "规则返回 error: ${o.optString("error")}")
                return emptyList()
            }
            val groups = o.optJSONArray("groups") ?: JSONArray()
            List(groups.length()) { i ->
                val g = groups.getJSONObject(i)
                val opts = g.optJSONArray("value") ?: JSONArray()
                FilterGroup(
                    key = g.optString("key"),
                    name = g.optString("name"),
                    options = List(opts.length()) { j ->
                        val item = opts.getJSONObject(j)
                        FilterOption(
                            name = item.optString("n"),
                            value = item.optString("v"),
                        )
                    }.filter { it.name.isNotEmpty() || it.value.isNotEmpty() },
                )
            }.filter { it.key.isNotEmpty() && it.options.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    private fun parseDetail(json: String): VodDetail {
        return runCatching {
            val o = JSONObject(json)
            VodDetail(
                vodId = o.optString("vod_id"),
                vodName = o.optString("vod_name"),
                vodPic = o.optString("vod_pic"),
                vodDesc = o.optString("vod_content"),
                vodPlayFrom = o.optString("vod_play_from"),
                vodPlayUrl = o.optString("vod_play_url"),
            )
        }.getOrDefault(VodDetail())
    }

    private fun parsePlayUrl(json: String): String {
        return runCatching {
            val o = JSONObject(json)
            o.optString("url", o.optString("play_url", ""))
        }.getOrDefault("")
    }
}
