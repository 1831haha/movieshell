package com.movieshell.data.source

import android.content.Context
import app.cash.quickjs.QuickJs
import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URI
import kotlin.coroutines.EmptyCoroutineContext

/**
 * v0.100.0：洛雪（LX）音乐脚本的 URL 解析引擎。
 *
 * LX 脚本是纯 URL 解析器：输入（平台 source + 歌曲 id + 音质），输出可播放直链。
 * 脚本跑在 `globalThis.lx` 运行时上，由本类提供 shim：
 * - `EVENT_NAMES = { inited: 'inited', request: 'request' }`
 * - `request(url, options)`：同步桥，立即返回 `{ statusCode, body, headers }`
 *   （body 是字符串；脚本里 `await lx.request(...)` 对非 Promise 值同样正常工作）
 * - `on(eventName, handler)` / `send(eventName, data)`：脚本侧监听注册与事件上报；
 *   host 侧捕获 `inited` 事件的 payload（内含 sources/qualitys）
 * - `utils = {}`（空对象，脚本用到的子方法缺失时不崩）、`env = { platform: 'android' }`、`version = '1.0.0'`
 *
 * 线程安全：app.cash.quickjs 的 JSContext 只能绑一个线程，所有 evaluate 强制走
 * [QuickJsDrpyEngine.jsDispatcher]（全 App 共享的 "quickjs-worker" 单线程），
 * 绝不在 Dispatchers.IO 上直接 evaluate（照抄 QuickJsDrpyEngine v0.99.x 的单线程化约束）。
 *
 * 返回值归一化说明：request handler 的返回值可能是字符串、对象（含 url 字段）或 Promise，
 * 做尽力解析——字符串直接用；对象取 `.url`（兼容 `data.url` 包裹）；Promise 则尝试在 JS 侧
 * await 后捕获。**如实声明：app.cash.quickjs 0.9.2 的微任务排空行为未经验证，
 * async 脚本返回的 Promise 是否能结算待真机验证**；若某脚本始终返回 Promise 而拿不到
 * 直链，优先用 [resolveBuiltinHuibq]（纯 HTTP，不走脚本）。
 *
 * 所有网络/JS 异常吞掉记 LogBus，返回 null/false，不抛异常。
 */
class LxMusicSourceEngine(context: Context) {

    private val appContext = context.applicationContext
    private val http: OkHttpClient = CmsApi.defaultClient()

    /** 按 scriptUrl 缓存已加载的 QuickJs 实例：一个脚本只 load 一次。 */
    private val engines = HashMap<String, LxInstance>()
    private val enginesLock = Any()

    private data class LxInstance(
        val qjs: QuickJs,
        val initedJson: String?,
    )

    /**
     * 下载并加载脚本（走 [CmsApi.defaultClient]），等 inited 事件，15s 超时；成功 true。
     * 已加载过的 scriptUrl 直接返回 true（不重复下载）。
     */
    suspend fun loadScript(scriptUrl: String): Boolean {
        if (scriptUrl.isBlank()) return false
        synchronized(enginesLock) { if (engines.containsKey(scriptUrl)) return true }
        return try {
            val script = downloadText(scriptUrl) ?: return false
            val initedJson = createEngine(scriptUrl, script)
            initedJson != null
        } catch (e: Exception) {
            LogBus.w(TAG, "loadScript 失败: $scriptUrl ${e.message}")
            false
        }
    }

    /**
     * 解析直链：按 scriptUrl 取缓存的 QuickJs（未加载则先 loadScript），调 request 事件取 musicUrl，
     * 15s 超时，失败返回 null。
     *
     * 调用参数固定为 `{action:'musicUrl', source, info:{songmid: songId}, quality}`，
     * 其中 songId 直接用 Track.urlId（网易 id，可当 wy 平台 songId 用）。
     */
    suspend fun resolveMusicUrl(
        scriptUrl: String,
        source: String,
        songId: String,
        quality: String,
    ): String? {
        if (scriptUrl.isBlank() || songId.isBlank()) return null
        return try {
            if (!loadScript(scriptUrl)) return null
            withTimeout(JS_TIMEOUT_MS) {
                withContext(QuickJsDrpyEngine.jsDispatcher) {
                    val inst = synchronized(enginesLock) { engines[scriptUrl] } ?: return@withContext null
                    callRequestHandler(inst.qjs, source, songId, quality)
                }
            }
        } catch (e: TimeoutCancellationException) {
            LogBus.w(TAG, "resolveMusicUrl 超时: $source/$songId")
            null
        } catch (e: Exception) {
            LogBus.w(TAG, "resolveMusicUrl 失败: ${e.message}")
            null
        }
    }

    /**
     * v0.103.0：取某脚本 inited 上报的音质 id 列表。
     *
     * 从 engines 锁内只读该 scriptUrl 的 initedJson（createEngine 里存的 payload），
     * 解析 `sources[].qualitys[].id` 展平去重（兼容 qualitys 条目为字符串的写法）；
     * 脚本未加载过 / 无 inited / 解析失败返回空 list。
     * 只读 map + 解析 JSON，任一线程可调。
     */
    fun getQualityIds(scriptUrl: String): List<String> {
        val initedJson = synchronized(enginesLock) {
            engines[scriptUrl]?.initedJson
        } ?: return emptyList()
        if (initedJson.isBlank()) return emptyList()
        return runCatching {
            val seen = LinkedHashSet<String>()
            val sources = JSONObject(initedJson).optJSONArray("sources")
            for (i in 0 until (sources?.length() ?: 0)) {
                val qualitys = sources?.optJSONObject(i)?.optJSONArray("qualitys")
                for (j in 0 until (qualitys?.length() ?: 0)) {
                    val id = when (val q = qualitys?.opt(j)) {
                        is JSONObject -> q.optString("id")
                        is String -> q
                        else -> ""
                    }
                    if (id.isNotBlank()) seen.add(id)
                }
            }
            seen.toList()
        }.onFailure {
            LogBus.w(TAG, "getQualityIds 解析失败: $scriptUrl ${it.message}")
        }.getOrDefault(emptyList())
    }

    /** 关闭所有缓存的 QuickJs 上下文（在 JS 线程执行，避免与 evaluate 并发）。 */
    fun release() {
        val all = synchronized(enginesLock) {
            val list = engines.values.toList()
            engines.clear()
            list
        }
        QuickJsDrpyEngine.jsDispatcher.dispatch(
            EmptyCoroutineContext,
            Runnable { all.forEach { runCatching { it.qjs.close() } } },
        )
    }

    // ---------------- 内部：网络 ----------------

    /**
     * v0.103.0：支持 file:// scheme——读本地脚本文件。
     * 以 file:// 开头的 url 改走 File(URI(url)).readText()（判空/判存在），
     * 失败返回 null 走现有日志分支；http(s) 逻辑不动。
     */
    private suspend fun downloadText(url: String): String? = withContext(Dispatchers.IO) {
        if (url.startsWith("file://")) {
            return@withContext runCatching {
                val f = File(URI(url))
                if (!f.isFile) {
                    LogBus.w(TAG, "本地脚本不存在: $url")
                    return@withContext null
                }
                f.readText().takeIf { it.isNotBlank() }
            }.onFailure {
                LogBus.w(TAG, "本地脚本读取失败: $url ${it.message}")
            }.getOrNull()
        }
        runCatching {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    LogBus.w(TAG, "脚本下载 HTTP ${resp.code}: $url")
                    return@withContext null
                }
                resp.body?.string()?.takeIf { it.isNotBlank() }
            }
        }.onFailure {
            LogBus.w(TAG, "脚本下载失败: $url ${it.message}")
        }.getOrNull()
    }

    // ---------------- 内部：引擎创建（必须在 jsDispatcher 线程内调用） ----------------

    /**
     * 在 JS 线程创建 QuickJs 实例：绑定 `__lxBridge` 接口桥 → 跑 lx shim →
     * evaluate 脚本 → 等 `inited` 事件 payload。成功返回 payload JSON，失败 null。
     */
    private suspend fun createEngine(scriptUrl: String, script: String): String? {
        return try {
            withTimeout(JS_TIMEOUT_MS) {
                withContext(QuickJsDrpyEngine.jsDispatcher) {
                    runCatching {
                        val qjs = QuickJs.create()
                        try {
                            qjs.set("__lxBridge", LxBridgeInterface::class.java, LxBridge())
                            qjs.evaluate(LX_SHIM, "lx-shim.js")
                            qjs.evaluate(script, "lx-script.js")
                            val payload = waitInited(qjs)
                            synchronized(enginesLock) {
                                engines[scriptUrl] = LxInstance(qjs, payload)
                            }
                            LogBus.i(TAG, "LX 脚本已加载: $scriptUrl")
                            payload
                        } catch (e: Exception) {
                            runCatching { qjs.close() }
                            throw e
                        }
                    }.onFailure {
                        LogBus.w(TAG, "LX 脚本加载失败: $scriptUrl ${it.message}")
                    }.getOrNull()
                }
            }
        } catch (e: TimeoutCancellationException) {
            LogBus.w(TAG, "LX 脚本加载超时: $scriptUrl")
            null
        }
    }

    /**
     * 轮询 `__lxInitedPayload`（shim 里 send('inited') 时写入），最多等到超时。
     * LX 脚本通常在模块初始化时同步 send inited，轮询只为兼容延迟上报的脚本。
     */
    private fun waitInited(qjs: QuickJs): String? {
        val deadline = System.currentTimeMillis() + JS_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val v = runCatching {
                qjs.evaluate("typeof __lxInitedPayload === 'undefined' ? null : __lxInitedPayload")?.toString()
            }.getOrNull()
            if (!v.isNullOrBlank() && v != "null" && v != "undefined") return v
            Thread.sleep(120)
        }
        LogBus.w(TAG, "未收到 inited 事件（脚本可能不支持 lx 运行时协议）")
        return null
    }

    // ---------------- 内部：request 事件调用（必须在 jsDispatcher 线程内调用） ----------------

    /**
     * 取出脚本注册的 request handler，以 musicUrl 参数调用，尽力归一化返回值。
     * 步骤：①调用 handler，结果存 `__lxResult`；②同步归一化（字符串/.url/data.url）；
     * ③若是 thenable，尝试 JS 侧 await 后再归一化（微任务排空行为未经验证，见类 KDoc）。
     */
    private fun callRequestHandler(qjs: QuickJs, source: String, songId: String, quality: String): String? {
        val callJs = """
            (function(){
              try {
                var ls = (globalThis.lx && globalThis.lx.__listeners && globalThis.lx.__listeners['request']) || [];
                if (!ls.length) { globalThis.__lxErr = 'no request listener'; return null; }
                var arg = {action:'musicUrl', source:${JSONObject.quote(source)}, info:{songmid:${JSONObject.quote(songId)}}, quality:${JSONObject.quote(quality)}};
                globalThis.__lxResult = ls[0](arg);
                globalThis.__lxErr = null;
              } catch(e) { globalThis.__lxErr = String(e && e.message || e); }
              return null;
            })()
        """.trimIndent()
        runCatching { qjs.evaluate(callJs) }.onFailure {
            LogBus.w(TAG, "request handler 调用异常: ${it.message}")
            return null
        }

        // ① 同步归一化
        normalizeResult(qjs, "__lxResult")?.let { return it }

        // ② thenable：尝试 JS 侧 await 后再归一化
        val isThenable = runCatching {
            qjs.evaluate("(function(){var r=globalThis.__lxResult;return (r && typeof r.then === 'function') ? '1':'0';})()")
                ?.toString() == "1"
        }.getOrDefault(false)
        if (isThenable) {
            runCatching {
                qjs.evaluate("(async function(){ try { globalThis.__lxSettled = await globalThis.__lxResult; } catch(e){ globalThis.__lxErr = String(e && e.message || e); } })()")
            }
            normalizeResult(qjs, "__lxSettled")?.let { return it }
            LogBus.w(TAG, "Promise 未结算（quickjs 0.9.2 微任务排空行为未经验证，待真机验证）")
        }

        qjs.evaluate("globalThis.__lxErr")?.toString()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { LogBus.w(TAG, "request handler 报错: $it") }
        return null
    }

    /** 从 JS 全局变量名取值：字符串直接用；对象取 .url，兼容 data.url 包裹。 */
    private fun normalizeResult(qjs: QuickJs, varName: String): String? {
        return runCatching {
            val v = qjs.evaluate(
                "(function(){var r=globalThis.$varName;" +
                    "if(typeof r==='string')return r;" +
                    "if(r&&typeof r.url==='string')return r.url;" +
                    "if(r&&r.data&&typeof r.data.url==='string')return r.data.url;" +
                    "return null;})()",
            )?.toString()
            v?.takeIf { it.isNotBlank() && it != "null" && it.startsWith("http") }
        }.getOrNull()
    }

    // ---------------- lx 运行时 shim ----------------

    /**
     * host 提供的 `globalThis.lx`。request 同步走 `__lxBridge.http`（JSON 契约）；
     * send('inited', payload) 时把 payload 写入 `__lxInitedPayload` 供 host 轮询；
     * request 事件监听器存在 `lx.__listeners['request']`。
     */
    private val LX_SHIM = """
        (function(){
          if (globalThis.lx) return;
          var listeners = {};
          globalThis.lx = {
            EVENT_NAMES: { inited: 'inited', request: 'request' },
            version: '1.0.0',
            env: { platform: 'android' },
            utils: {},
            __listeners: listeners,
            on: function(eventName, handler) {
              (listeners[eventName] = listeners[eventName] || []).push(handler);
            },
            send: function(eventName, data) {
              if (eventName === 'inited') {
                try { globalThis.__lxInitedPayload = JSON.stringify(data); }
                catch(e) { globalThis.__lxInitedPayload = String(data); }
              }
            },
            request: function(url, options) {
              var raw = __lxBridge.http(String(url), JSON.stringify(options || {}));
              var res = {};
              try { res = JSON.parse(raw); } catch(e) {}
              return { statusCode: res.status, body: res.body, headers: res.headers || {} };
            }
          };
        })();
    """.trimIndent()

    // ---------------- JS 桥（接口式 set，照抄 QuickJsDrpyEngine 的桥接模式） ----------------

    /** QuickJS 的 set() 只接受接口绑定，实现类在下面。 */
    interface LxBridgeInterface {
        /** 同步 HTTP：返回 JSON 字符串 `{status, body, headers}`（body 为响应文本；出错 status=-1）。 */
        fun http(url: String, optionsJson: String): String
        fun log(msg: String)
    }

    /** lx.request 的同步网络实现：在 JS 线程同步 execute，占住 JS 线程直到返回/超时。 */
    inner class LxBridge : LxBridgeInterface {
        override fun http(url: String, optionsJson: String): String {
            val result = JSONObject()
            try {
                val opt = runCatching {
                    if (optionsJson.isBlank()) JSONObject() else JSONObject(optionsJson)
                }.getOrDefault(JSONObject())
                val method = opt.optString("method", "GET").uppercase()
                val headers = opt.optJSONObject("headers")
                val bodyText = opt.optString("body", "")

                val reqBuilder = Request.Builder().url(url)
                headers?.keys()?.forEach { k -> reqBuilder.header(k, headers.optString(k)) }
                when (method) {
                    "POST", "PUT", "PATCH" -> {
                        val ct = headers?.optString("Content-Type")?.ifBlank { null }
                            ?: headers?.optString("content-type")?.ifBlank { null }
                            ?: "application/x-www-form-urlencoded"
                        reqBuilder.method(
                            method,
                            bodyText.toRequestBody(ct.toMediaTypeOrNull()),
                        )
                    }
                    "GET" -> reqBuilder.get()
                    else -> reqBuilder.method(method, null)
                }
                http.newCall(reqBuilder.build()).execute().use { resp ->
                    result.put("status", resp.code)
                    result.put("body", resp.body?.string() ?: "")
                    val hs = JSONObject()
                    resp.headers.forEach { hs.put(it.first, it.second) }
                    result.put("headers", hs)
                }
            } catch (e: Exception) {
                LogBus.w(TAG, "lx.request 失败: $url ${e.message}")
                result.put("status", -1)
                result.put("body", "")
                result.put("headers", JSONObject())
            }
            return result.toString()
        }

        override fun log(msg: String) {
            LogBus.d(TAG, "LX: $msg")
        }
    }

    companion object {
        private const val TAG = "LxMusic"
        private const val JS_TIMEOUT_MS = 15_000L
    }
}

/**
 * v0.100.0：内置 huibq 纯 HTTP 解析（不走脚本，100% 可用）。
 *
 * GET `https://lxmusicapi.onrender.com/url/{source}/{songId}/{quality}`，
 * header `X-Request-Key: share-v3`；source ∈ {kw,kg,tx,wy,mg}；
 * quality 取 "320k"/"128k"；从返回 JSON 取 url 字段（兼容 data.url 包裹），失败 null。
 */
suspend fun resolveBuiltinHuibq(source: String, songId: String, quality: String): String? =
    withContext(Dispatchers.IO) {
        val src = source.lowercase()
        if (src !in setOf("kw", "kg", "tx", "wy", "mg")) return@withContext null
        if (songId.isBlank()) return@withContext null
        val q = if (quality == "320k") "320k" else "128k"
        runCatching {
            val req = Request.Builder()
                .url("https://lxmusicapi.onrender.com/url/$src/$songId/$q")
                .header("X-Request-Key", "share-v3")
                .get()
                .build()
            CmsApi.defaultClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val text = resp.body?.string() ?: return@withContext null
                val o = JSONObject(text)
                val url = o.optString("url").ifBlank {
                    o.optJSONObject("data")?.optString("url").orEmpty()
                }
                url.takeIf { it.isNotBlank() && it.startsWith("http") }
            }
        }.onFailure {
            LogBus.w("LxMusic", "内置 huibq 解析失败: $src/$songId ${it.message}")
        }.getOrNull()
    }
