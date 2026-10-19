package com.movieshell.data.source

import android.content.Context
import android.util.Log
import app.cash.quickjs.QuickJs
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.FilterGroup
import com.movieshell.data.model.FilterOption
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.security.MessageDigest
import kotlin.coroutines.EmptyCoroutineContext

/**
 * v0.16.0：QuickJS drpy 引擎（替代 WebView）。
 *
 * v0.99.0 单线程化改造（诊断 P0-2/P0-3/P0-4）：QuickJS 非线程安全，所有 JS 调用强制走
 * companion 共享的 "quickjs-worker" 单线程 dispatcher；loadRule 切源强制重建上下文；
 * JS 异常/超时后关闭重建；进行中的网络 Call 被追踪，release 时取消。
 *
 * JS 环境：shim.js（同 WebView 版）+ 规则源码，通过 __bridge 提供同步 request()/http()。
 */
class QuickJsDrpyEngine(
    context: Context,
) : DrpyEngine {

    private val appContext = context.applicationContext
    private val http: OkHttpClient = CmsApi.defaultClient()

    private var quickJs: QuickJs? = null
    private var ruleJs: String? = null
    private var homeCache: JSONObject? = null

    /**
     * v0.99.0：进行中的网络请求。bridge 在 JS 线程调用 request()，release() 可能在任意线程，
     * 用并发集合保证可见性；release 时全部 cancel。
     */
    private val pendingCalls = ConcurrentHashMap.newKeySet<Call>()

    companion object {
        private const val TAG = "QuickJsDrpy"
        private const val JS_TIMEOUT_MS = 15_000L

        /**
         * v0.99.1：全 App 共享的 JS 单线程。QuickJS 的 JSContext 只能绑一个线程，
         * 所有 evaluate 强制走这里，杜绝多线程并发进 JNI 导致 SIGSEGV。
         * newSingleThreadContext 在新版 kotlinx.coroutines 已移除，改用
         * Executors 单线程 + asCoroutineDispatcher（官方推荐替代）。
         * 生命周期与 App 一致，不关闭。
         */
        val jsDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "quickjs-worker").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    override fun loadRule(js: String) {
        ruleJs = js
        homeCache = null
        // v0.99.0 P0-3：切源强制重建——旧上下文跑的是老规则，post 到 JS 线程关闭，
        // 下次 ensureEngine 新建实例并 evaluate 新规则（单线程 FIFO 保序）。
        jsDispatcher.dispatch(EmptyCoroutineContext, Runnable { closeEngine() })
    }

    /**
     * 关闭 QuickJS 上下文并取消进行中的网络请求。必须在 jsDispatcher 线程内调用，
     * 避免与 evaluate 并发造成 use-after-free。
     */
    private fun closeEngine() {
        pendingCalls.forEach { runCatching { it.cancel() } }
        pendingCalls.clear()
        runCatching { quickJs?.close() }
        quickJs = null
        homeCache = null
    }

    /** 调用方保证在 jsDispatcher 线程内（经 evalForJson 进入）。 */
    private fun ensureEngine(): QuickJs {
        quickJs?.let { return it }
        val qjs = QuickJs.create()
        // 绑定同步 request 桥：QuickJS set() 只接受接口，传 JsBridgeInterface
        qjs.set("__bridge", JsBridgeInterface::class.java, JsBridge())
        // 先跑 shim，建立 request/setResult 等全局函数
        val shim = appContext.assets.open("drpy/shim.js").bufferedReader().readText()
        qjs.evaluate(shim, "shim.js")
        // 再跑规则源码
        ruleJs?.let { qjs.evaluate(it, "rule.js") }
        quickJs = qjs
        LogBus.i(TAG, "QuickJS 引擎已创建")
        return qjs
    }

    override suspend fun home(): List<Vod> = withContext(Dispatchers.IO) {
        val json = sharedHomeJson() ?: return@withContext emptyList()
        parseVodList(json)
    }

    /**
     * v0.50.0 修：shim.js 只导出了 __drpyHome/__drpyCategory，根本没有 __drpyCategories，
     * 之前这里永远报 "'__drpyCategories' is not defined"（被 evalForJson 吞掉返回空）。
     * 改走 __drpyHome 的 class 数组；与 home() 共享一次调用（10s 窗口，学 PyApi）。
     */
    override suspend fun categories(): List<CategoryType> = withContext(Dispatchers.IO) {
        val json = sharedHomeJson() ?: return@withContext emptyList()
        parseCategoriesFromHome(json)
    }

    @Volatile
    private var sharedHomeJson: String? = null
    @Volatile
    private var sharedHomeAt: Long = 0L

    private suspend fun sharedHomeJson(): String? = withContext(Dispatchers.IO) {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = sharedHomeJson
        if (cached != null && now - sharedHomeAt < 10_000) return@withContext cached
        val json = evalForJson("(function(){return __drpyHome();})()")
        if (json != null) {
            sharedHomeJson = json
            sharedHomeAt = now
        }
        json
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
        // v0.99.0：切到 JS 线程关闭，避免与 evaluate 并发造成 use-after-free；
        // ruleJs 保留，release 后复用可重建（切源由 loadRule 强制重建）。
        jsDispatcher.dispatch(EmptyCoroutineContext, Runnable { closeEngine() })
    }

    /**
     * v0.99.0：在 JS 单线程同步执行（QuickJS 是同步引擎）。
     * 返回 JSON 字符串；JS 异常/超时返回 null 并关闭重建上下文。
     * 注意：withTimeout 取消不掉 JNI 里死循环的 evaluate，超时后 post 的 closeEngine
     * 会排队等待——这是 QuickJS 无中断机制下的极限，见 P0-4。
     */
    private suspend fun evalForJson(expression: String): String? {
        return try {
            withTimeout(JS_TIMEOUT_MS) {
                withContext(jsDispatcher) {
                    runCatching {
                        val qjs = ensureEngine()
                        qjs.evaluate(expression)?.toString()
                    }.onFailure {
                        LogBus.w(TAG, "JS 执行失败: ${it.message}")
                        // 上下文可能已损坏，直接关闭，下次重建
                        closeEngine()
                    }.getOrNull()
                }
            }
        } catch (e: TimeoutCancellationException) {
            LogBus.w(TAG, "JS 执行超时(${JS_TIMEOUT_MS}ms)，关闭重建引擎")
            jsDispatcher.dispatch(EmptyCoroutineContext, Runnable { closeEngine() })
            null
        }
    }

    /**
     * JS 桥接口：QuickJS 的 set() 只接受接口绑定，实现类在下面。
     * v0.99.0 P0-1：补上 http()——shim.js 调的是 __bridge.http(url, headersJson, method, body)，
     * 之前没注册导致所有规则的 request()/fetch() 抛 TypeError。
     */
    interface JsBridgeInterface {
        fun request(url: String, options: String?): String

        /** 兼容 shim.js 的 4 参调用。 */
        fun http(url: String, headersJson: String, method: String, body: String): String
        fun log(msg: String)

        /**
         * v0.107.0 P0-2：shim.js 的 md5() 转调 __bridge.md5()，之前没注册
         * （与 v0.99.0 的 http() 未注册同类 bug），依赖 MD5 签名的 lazy 解析会静默算错地址。
         */
        fun md5(s: String): String
    }

    /** JS 桥：提供同步 request()/http() 给 drpy 规则。 */
    inner class JsBridge : JsBridgeInterface {
        override fun request(url: String, options: String?): String {
            val opt = runCatching {
                if (options.isNullOrBlank()) JSONObject() else JSONObject(options)
            }.getOrDefault(JSONObject())
            return doRequest(
                url = url,
                method = opt.optString("method", "GET"),
                headers = opt.optJSONObject("headers"),
                body = opt.optString("body", ""),
            )
        }

        override fun http(url: String, headersJson: String, method: String, body: String): String {
            val headers = runCatching {
                if (headersJson.isBlank()) null else JSONObject(headersJson)
            }.getOrNull()
            return doRequest(url, method, headers, body)
        }

        override fun log(msg: String) {
            LogBus.d(TAG, "JS: $msg")
        }

        override fun md5(s: String): String {
            return runCatching {
                MessageDigest.getInstance("MD5")
                    .digest(s.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
        }

        /**
         * v0.99.0：统一网络执行，request()/http() 共用。
         * 返回裸 body 字符串（"" 表失败），与 shim.js 里 `JSON.parse(request(...))` 的旧契约一致；
         * POST/PUT/PATCH 的 Content-Type 从 headers 里读，默认 application/x-www-form-urlencoded（P1-3）。
         * 在 JS 线程同步 execute，会占住 JS 线程直到返回/超时——调用方可被 release() cancel。
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
            pendingCalls.add(call)
            return try {
                call.execute().use { resp -> resp.body?.string() ?: "" }
            } catch (e: Exception) {
                LogBus.w(TAG, "request 失败: $url ${e.message}")
                ""
            } finally {
                pendingCalls.remove(call)
            }
        }
    }

    // ---------------- 解析（与 WebView 版一致） ----------------

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
     * 规则返回 error 或无 groups 时返回空列表（UI 不显示筛选行）。
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
