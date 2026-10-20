package com.movieshell.data.source

import android.content.Context
import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * v0.113.0：洛雪（LX）音乐脚本的 URL 解析引擎（androidx.javascriptengine V8）。
 *
 * LX 脚本是纯 URL 解析器：输入（平台 source + 歌曲 id + 音质），输出可播放直链。
 * 脚本跑在 `globalThis.lx` 运行时上，由本类提供 shim：
 * - `EVENT_NAMES = { inited: 'inited', request: 'request' }`
 * - `request(url, options)`：真 async——返回 Promise，宿主 OkHttp 经 MessagePort RPC
 *   异步执行（v0.113.0 前 QuickJS 版是 JS 线程同步阻塞，A2 的另一个源头）
 * - `on(eventName, handler)` / `send(eventName, data)`：脚本侧监听注册与事件上报；
 *   `inited` 事件经 MessagePort 单向直达宿主（[CompletableDeferred] 等待），
 *   不再有 QuickJS 版的"写全局变量 + host 轮询"（旧轮询曾把 JS 线程冻结 3-15s）
 * - `utils = {}`（空对象，脚本用到的子方法缺失时不崩）、`env = { platform: 'android' }`、`version = '1.0.0'`
 * - `console.*`：由 [JsSession] 的 setConsoleCallback 直通 LogBus（V8 isolate 自带 console 对象）
 *
 * 每个脚本实例一条私有 [JsSession]（独立 V8 isolate），脚本卡死只影响它自己；
 * resolveMusicUrl 超时后终止并剔除该实例，下次调用自动重建（比旧 lane 隔离更自愈）。
 *
 * 返回值归一化在 JS 侧一次完成：`(async () => { var r = await handler(arg); ... })()`
 * evaluateJavaScriptAsync 等 Promise 结算后拿到字符串——旧版"微任务排空未验证"的
 * 不确定性在 V8 上不复存在。
 *
 * 所有网络/JS 异常吞掉记 LogBus，返回 null/false，不抛异常。
 */
class LxMusicSourceEngine(context: Context) {

    private val appContext = context.applicationContext
    private val http: OkHttpClient = CmsApi.defaultClient()

    /** 按 scriptUrl 缓存已加载的实例：一个脚本只 load 一次，各自持有一条私有 JS 会话。 */
    private val engines = HashMap<String, LxInstance>()
    private val enginesLock = Any()

    /**
     * v0.109.0 B4②：loadScript check-then-act 并发双建修复。下载（IO 密集）不占锁；
     * 建引擎前在 Mutex 内二次确认——下载期间若已有并发协程先建好，直接复用。
     */
    private val scriptMutex = Mutex()

    private val hostJs by lazy {
        appContext.assets.open("jsbridge/host.js").bufferedReader().readText()
    }

    /** v0.113.0 A1：一个 LX 脚本 = 一条私有 V8 会话 + 其上缓存的 inited payload。 */
    private class LxInstance(val session: JsSession, val initedJson: String)

    /**
     * 下载并加载脚本（走 [CmsApi.defaultClient]），等 inited 事件，15s 超时；成功 true。
     * 已加载过的 scriptUrl 直接返回 true（不重复下载）。
     */
    suspend fun loadScript(scriptUrl: String): Boolean {
        if (scriptUrl.isBlank()) return false
        // 快路：已加载直接返回
        synchronized(enginesLock) { if (engines.containsKey(scriptUrl)) return true }
        return try {
            val script = downloadText(scriptUrl) ?: return false
            scriptMutex.withLock {
                // v0.109.0 B4②：下载期间可能已有并发协程先建好，二次确认避免重复建引擎
                if (synchronized(enginesLock) { engines.containsKey(scriptUrl) }) return@withLock true
                createEngine(scriptUrl, script) != null
            }
        } catch (e: Exception) {
            LogBus.w(TAG, "loadScript 失败: $scriptUrl ${e.message}")
            false
        }
    }

    /**
     * 解析直链：按 scriptUrl 取缓存实例（未加载则先 loadScript），在 JS 侧 await
     * request 事件取 musicUrl，15s 硬截止，失败返回 null。
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
        if (!loadScript(scriptUrl)) return null
        val inst = synchronized(enginesLock) { engines[scriptUrl] } ?: return null
        return try {
            // 归一化在 JS 侧一次完成（字符串/.url/data.url），V8 等 Promise 结算
            val callJs = """
                (async function(){
                  try {
                    var ls = (globalThis.lx && globalThis.lx.__listeners && globalThis.lx.__listeners['request']) || [];
                    if (!ls.length) return '';
                    var arg = {action:'musicUrl', source:${JSONObject.quote(source)}, info:{songmid:${JSONObject.quote(songId)}}, quality:${JSONObject.quote(quality)}};
                    var r = await ls[0](arg);
                    if (typeof r === 'string') return r;
                    if (r && typeof r.url === 'string') return r.url;
                    if (r && r.data && typeof r.data.url === 'string') return r.data.url;
                    return '';
                  } catch(e) { return ''; }
                })()
            """.trimIndent()
            val result = inst.session.eval(callJs, JS_TIMEOUT_MS)
            result.takeIf { it.isNotBlank() && it.startsWith("http") }
        } catch (e: JsSessionTimeoutException) {
            LogBus.w(TAG, "resolveMusicUrl 超时（isolate 已终止）: $source/$songId")
            evict(scriptUrl, inst)
            null
        } catch (e: JsSessionClosedException) {
            LogBus.w(TAG, "resolveMusicUrl 会话不可用: ${e.message}")
            evict(scriptUrl, inst)
            null
        } catch (e: Exception) {
            LogBus.w(TAG, "resolveMusicUrl 失败: ${e.message}")
            null
        }
    }

    /** 超时/会话死亡后剔除实例并终止 isolate：下次 resolveMusicUrl 自动重建（自愈）。 */
    private fun evict(scriptUrl: String, inst: LxInstance) {
        synchronized(enginesLock) {
            if (engines[scriptUrl] === inst) engines.remove(scriptUrl)
        }
        runCatching { inst.session.close() }
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

    /** 关闭所有缓存的 JS 会话：逐条即时终止 isolate（任意线程可调）。 */
    fun release() {
        val all = synchronized(enginesLock) {
            val list = engines.values.toList()
            engines.clear()
            list
        }
        all.forEach { runCatching { it.session.close() } }
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

    // ---------------- 内部：引擎创建 ----------------

    /**
     * 创建脚本实例：新建私有 [JsSession]，注册 httpx RPC，bootstrap host.js + lx shim +
     * 脚本，经 MessagePort 单向事件等 `inited` payload（15s 超时）。成功返回 payload JSON。
     */
    private suspend fun createEngine(scriptUrl: String, script: String): String? {
        val s = JsSession(TAG)
        s.registerRpc("httpx") { payload -> rpcHttpx(payload) }
        val initedDeferred = CompletableDeferred<String>()
        s.onEvent = { kind, value ->
            if (kind == "inited" && !initedDeferred.isCompleted) {
                initedDeferred.complete(value)
            }
        }
        return try {
            val portName = JsSession.newPortName()
            val ok = s.bootstrap(
                portName,
                listOf(
                    "globalThis.__PORT_NAME = ${JSONObject.quote(portName)};",
                    hostJs,
                    LX_SHIM,
                    script,
                ),
            )
            if (!ok) {
                LogBus.w(TAG, "LX 脚本引导失败: $scriptUrl ${s.lastError}")
                s.close()
                return null
            }
            val inited = withTimeoutOrNull(INIT_TIMEOUT_MS) { initedDeferred.await() }
            if (inited == null) {
                LogBus.w(TAG, "未收到 inited 事件（脚本可能不支持 lx 运行时协议）: $scriptUrl")
                s.close()
                return null
            }
            synchronized(enginesLock) { engines[scriptUrl] = LxInstance(s, inited) }
            LogBus.i(TAG, "LX 脚本已加载: $scriptUrl")
            inited
        } catch (e: Exception) {
            LogBus.w(TAG, "LX 脚本加载失败: $scriptUrl ${e.message}")
            runCatching { s.close() }
            null
        }
    }

    /** lx.request 的宿主 RPC（kind='httpx'）：返回 JSON `{status, body, headers}`；失败 status=-1。 */
    private suspend fun rpcHttpx(payload: JSONObject): String = withContext(Dispatchers.IO) {
        val result = JSONObject()
        try {
            val optionsJson = payload.optString("options")
            val opt = runCatching {
                if (optionsJson.isBlank()) JSONObject() else JSONObject(optionsJson)
            }.getOrDefault(JSONObject())
            val url = payload.optString("url")
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
            LogBus.w(TAG, "lx.request 失败: ${payload.optString("url")} ${e.message}")
            result.put("status", -1)
            result.put("body", "")
            result.put("headers", JSONObject())
        }
        result.toString()
    }

    // ---------------- lx 运行时 shim（真 async） ----------------

    /**
     * host 提供的 `globalThis.lx`。request 经 `__hrpc{kind:'httpx'}` 真 async（Promise）；
     * send('inited', payload) 经 `__hsend` 单向直达宿主；request 事件监听器存
     * `lx.__listeners['request']`。console 由 JsSession 的 setConsoleCallback 提供。
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
                try { __hsend('inited', JSON.stringify(data)); }
                catch(e) { __hsend('inited', String(data)); }
              }
            },
            request: async function(url, options) {
              var raw = await __hrpc({ kind: 'httpx', url: String(url), options: JSON.stringify(options || {}) });
              var res = {};
              try { res = JSON.parse(raw); } catch(e) { res = { status: -1, body: '', headers: {} }; }
              return { statusCode: res.status, body: res.body, headers: res.headers || {} };
            }
          };
        })();
    """.trimIndent()

    companion object {
        private const val TAG = "LxMusic"
        private const val JS_TIMEOUT_MS = 15_000L

        /** 等 inited 事件的上限（脚本同步上报时瞬时到达；延迟上报最多等 15s）。 */
        private const val INIT_TIMEOUT_MS = 15_000L

        /**
         * v0.106.0：内置 huibq 自报档次（管理页 chips 口径：流畅/标准），从高到低试播。
         * 不硬编码假设——这是该源声明的实际档次，不是猜的。
         */
        val BUILTIN_QUALITIES: List<String> = listOf("192k", "128k")

        /** 档次排序权重（未知 id 排最后）；管理页 chips 与试播共用同一口径 */
        fun qualityRank(id: String): Int = when (id) {
            "128k" -> 0
            "192k" -> 1
            "320k" -> 2
            "flac" -> 3
            "flac24bit", "hires", "atmos", "atmos_plus", "master" -> 4
            else -> 5
        }

        /**
         * v0.106.0：从高到低试播排序——已知档次按权重降序（超高清→流畅），
         * 未知 id 沉底（不假设能播，播不了再往下走）。
         */
        fun sortHighToLow(ids: List<String>): List<String> =
            ids.sortedWith(
                compareByDescending<String> { qualityRank(it) < 5 }
                    .thenByDescending { qualityRank(it) },
            )
    }
}

/**
 * v0.100.0：内置 huibq 纯 HTTP 解析（不走脚本，100% 可用）。
 *
 * GET `https://lxmusicapi.onrender.com/url/{source}/{songId}/{quality}`，
 * header `X-Request-Key: share-v3`；source ∈ {kw,kg,tx,wy,mg}；
 * quality 取 "192k"/"128k"（v0.106.0：按内置源自报档次透传，不再只认 320k/128k）；
 * 从返回 JSON 取 url 字段（兼容 data.url 包裹），失败 null。
 */
suspend fun resolveBuiltinHuibq(source: String, songId: String, quality: String): String? =
    withContext(Dispatchers.IO) {
        val src = source.lowercase()
        if (src !in setOf("kw", "kg", "tx", "wy", "mg")) return@withContext null
        if (songId.isBlank()) return@withContext null
        val q = when (quality) {
            "320k" -> "320k"
            "192k" -> "192k"
            else -> "128k"
        }
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
