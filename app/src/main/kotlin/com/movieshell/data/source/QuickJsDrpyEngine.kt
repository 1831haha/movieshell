package com.movieshell.data.source

import android.content.Context
import android.util.Log
import app.cash.quickjs.QuickJs
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.FilterGroup
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.16.0：QuickJS drpy 引擎（替代 WebView）。
 *
 * 对标 DsPlayer：drpy 规则本就是为 QuickJS 写的，WebView 又重又慢还容易被规则里的
 * 浏览器嗅探代码干扰。QuickJS 纯 JS 引擎，启动快、内存小、兼容性更好。
 *
 * JS 环境：shim.js（同 WebView 版）+ 规则源码，通过 __bridge 提供同步 request()。
 */
class QuickJsDrpyEngine(
    context: Context,
) : DrpyEngine {

    private val appContext = context.applicationContext
    private val http: OkHttpClient = CmsApi.defaultClient()

    private var quickJs: QuickJs? = null
    private var ruleJs: String? = null
    private var homeCache: JSONObject? = null

    companion object {
        private const val TAG = "QuickJsDrpy"
        private const val JS_TIMEOUT_MS = 30_000L
    }

    override fun loadRule(js: String) {
        ruleJs = js
        homeCache = null
        // 懒创建：第一次调用时再建 QuickJs
    }

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
        val json = evalForJson("(function(){return __drpyHome();})()") ?: return@withContext emptyList()
        parseVodList(json)
    }

    override suspend fun categories(): List<CategoryType> = withContext(Dispatchers.IO) {
        val json = evalForJson("(function(){return __drpyCategories();})()") ?: return@withContext emptyList()
        parseCategories(json)
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
        try {
            quickJs?.close()
        } catch (e: Exception) {
            Log.w(TAG, "QuickJs close 异常", e)
        }
        quickJs = null
        ruleJs = null
        homeCache = null
    }

    /**
     * 在 IO 线程同步执行 JS（QuickJS 是同步引擎，无需主线程）。
     * 返回 JSON 字符串，超时/异常返回 null。
     */
    private suspend fun evalForJson(expression: String): String? =
        withTimeoutOrNull(JS_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val qjs = ensureEngine()
                    val result = qjs.evaluate(expression)
                    result?.toString()
                }.onFailure {
                    LogBus.w(TAG, "JS 执行失败: ${it.message}")
                }.getOrNull()
            }
        }

    /**
     * JS 桥接口：QuickJS 的 set() 只接受接口绑定，实现类在下面。
     * JS 侧通过 __bridge.request(url, options) / __bridge.log(msg) 调用。
     */
    interface JsBridgeInterface {
        fun request(url: String, options: String?): String
        fun log(msg: String)
    }

    /**
     * JS 桥：提供同步 request(url, options) 给 drpy 规则。
     */
    inner class JsBridge : JsBridgeInterface {
        override fun request(url: String, options: String?): String {
            return runCatching {
                val opt = if (options.isNullOrBlank()) JSONObject() else JSONObject(options)
                val method = opt.optString("method", "GET").uppercase()
                val headers = opt.optJSONObject("headers")
                val body = opt.optString("body", null)

                val reqBuilder = Request.Builder().url(url)
                headers?.keys()?.forEach { k ->
                    reqBuilder.addHeader(k, headers.optString(k))
                }
                if (method == "POST") {
                    val mediaType = "application/x-www-form-urlencoded".toMediaTypeOrNull()
                    reqBuilder.post((body ?: "").toRequestBody(mediaType))
                } else {
                    reqBuilder.get()
                }
                http.newCall(reqBuilder.build()).execute().use { resp ->
                    resp.body?.string() ?: ""
                }
            }.onFailure {
                LogBus.w(TAG, "request 失败: $url ${it.message}")
            }.getOrDefault("")
        }

        override fun log(msg: String) {
            LogBus.d(TAG, "JS: $msg")
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

    private fun parseCategories(json: String): List<CategoryType> {
        return runCatching {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val c = arr.getJSONObject(i)
                CategoryType(
                    typeId = c.optString("type_id"),
                    typeName = c.optString("type_name"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun parseFilters(json: String): List<FilterGroup> {
        // TODO: 按实际 filter JSON 结构解析
        return emptyList()
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
