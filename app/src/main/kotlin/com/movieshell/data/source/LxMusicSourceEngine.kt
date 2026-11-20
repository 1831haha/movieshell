package com.movieshell.data.source

import android.content.Context
import com.movieshell.core.log.LogBus
import com.movieshell.core.net.HttpClient
import kotlinx.coroutines.CancellationException
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * v0.113.0：洛雪（LX）音乐脚本的 URL 解析引擎（androidx.javascriptengine V8）。
 *
 * LX 脚本是纯 URL 解析器：输入（平台 source + 歌曲 id + 音质），输出可播放直链。
 * 脚本跑在 `globalThis.lx` 运行时上，由本类提供 shim：
 * - `EVENT_NAMES = { inited: 'inited', request: 'request' }`
 * - `request(url, options[, callback])`：真 async——返回 Promise（带 callback 时按
 *   LX 协议回调 (err, resp)），宿主 OkHttp 经 MessagePort RPC 异步执行
 * - `on(eventName, handler)` / `send(eventName, data)`：脚本侧监听注册与事件上报；
 *   `inited` 事件经 MessagePort 单向直达宿主（[CompletableDeferred] 等待），
 *   不再有 QuickJS 版的"写全局变量 + host 轮询"（旧轮询曾把 JS 线程冻结 3-15s）
 * - `utils.crypto.md5`（接 host.js 的纯 JS 同步 md5，聚合类脚本签名要用）、`env = { platform: 'android' }`、`version = '1.0.0'`；
 *   `utils.crypto.aesEncrypt`/`utils.buffer` 未提供，需要它们的个别后端会自行失败、脚本多后端兜底
 * - `currentScriptInfo.rawScript`（v0.118.0）：注入脚本文本本身——墨澜/聚合类脚本靠它
 *   解析头部 `/*! ... */` 块里的 @tx_cookie/@wy_cookie 声明（[LxCookieKit] 写回同处）
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
 *
 * v1.2.18：直链验活——[resolveMusicUrl] 返回前先验链接（见伴生对象
 * [verifyAudioUrl]），死链按解析失败返回 null，调用方逐档/逐源回落链继续
 * 试；带明确媒体后缀的直链不验（v0.294.9 一次性地址保护）。
 */
class LxMusicSourceEngine(context: Context) {

    private val appContext = context.applicationContext
    private val http: OkHttpClient = CmsApi.defaultClient()

    /** 按 scriptUrl 缓存已加载的实例：一个脚本只 load 一次，各自持有一条私有 JS 会话。 */
    private val engines = HashMap<String, LxInstance>()
    private val enginesLock = Any()

    /**
     * v0.120.0：最近一次 resolveMusicUrl 的失败原因（短中文诊断，不记敏感值）。
     * 每次调用入口清零；引擎按持有者生命周期复用、并发串行，按调用覆盖即可。
     */
    var lastFailureReason: String = ""
        private set

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
     * 调用参数按 LX 标准协议组装（v0.124.0）：`{action:'musicUrl', source,
     * info:{musicInfo:{songmid,id}, type: quality}}`——脚本读的是 info.musicInfo 与
     * info.type；旧的自造形状（info 只有 songmid、音质放顶层 quality）会让墨澜/聚合
     * 类脚本取到 undefined 直接抛错。顶层 quality 与 info.songmid 一并保留作兼容。
     * songId 直接用 Track.urlId（网易 id，可当 wy 平台 songId 用）。
     *
     * v0.131.0：musicInfo 补全脚本实读字段——name（或 songName）、singer、
     * interval、albumName（或 album）、source（平台 code）随歌透传，缺了会让
     * mg 星海等后端回「需要name+singer」；kg 额外 put hash=songId、kw 额外
     * put rid=songId（两平台多个后端优先读这两个字段）。无数据的字段传空串，
     * 字段要在、值可空。
     */
    suspend fun resolveMusicUrl(
        scriptUrl: String,
        source: String,
        songId: String,
        quality: String,
        name: String = "",
        singer: String = "",
        albumName: String = "",
        interval: String = "",
    ): String? {
        lastFailureReason = ""
        if (scriptUrl.isBlank() || songId.isBlank()) {
            lastFailureReason = "参数为空"
            return null
        }
        if (!loadScript(scriptUrl)) {
            lastFailureReason = "脚本加载失败"
            return null
        }
        val inst = synchronized(enginesLock) { engines[scriptUrl] } ?: run {
            lastFailureReason = "脚本实例为空"
            return null
        }
        return try {
            // 归一化在 JS 侧一次完成（字符串/.url/data.url），V8 等 Promise 结算
            val musicInfo = JSONObject()
                .put("songmid", songId)
                .put("id", songId)
                .put("name", name)
                .put("singer", singer)
                .put("albumName", albumName)
                .put("interval", interval)
                .put("source", source)
            if (source == "kg") musicInfo.put("hash", songId)
            if (source == "kw") musicInfo.put("rid", songId)
            val arg = JSONObject()
                .put("action", "musicUrl")
                .put("source", source)
                .put(
                    "info",
                    JSONObject()
                        .put("musicInfo", musicInfo)
                        .put("type", quality)
                        .put("songmid", songId),
                )
                .put("quality", quality)
            val callJs = """
                (async function(){
                  try {
                    var ls = (globalThis.lx && globalThis.lx.__listeners && globalThis.lx.__listeners['request']) || [];
                    if (!ls.length) return '';
                    var arg = $arg;
                    var r = await ls[0](arg);
                    if (typeof r === 'string') return r;
                    if (r && typeof r.url === 'string') return r.url;
                    if (r && r.data && typeof r.data.url === 'string') return r.data.url;
                    return '';
                  } catch(e) { return '__SCRIPT_ERROR__:' + (e && e.message ? e.message : String(e)); }
                })()
            """.trimIndent()
            val result = inst.session.eval(callJs, JS_TIMEOUT_MS)
            // v0.146.0：脚本抛错不再吞成空结果——把报错原文透传给 lastFailureReason，
            // 调用方才能按原因提示（如"只有试听"），此前一律只剩"脚本返回空结果"。
            if (result.startsWith("__SCRIPT_ERROR__:")) {
                lastFailureReason = result.removePrefix("__SCRIPT_ERROR__:")
                    .ifBlank { "脚本返回空结果" }
                return null
            }
            val url = result.takeIf { it.isNotBlank() && it.startsWith("http") }
            if (url == null) {
                lastFailureReason = "脚本返回空结果"
                return null
            }
            // v1.2.18：直链验活——死链当解析失败，调用方逐档/逐源回落链继续试，
            // 不再把死链交给播放器 5 秒后才报错。
            if (!verifyAudioUrl(url)) {
                lastFailureReason = "直链验活失败"
                LogBus.w(TAG, "直链验活失败，换下一档/源")
                return null
            }
            url
        } catch (e: CancellationException) {
            // v0.294.17：调用方取消（切歌）时 Kotlin 侧不再等，但 JS 里的 Promise 链照跑——
            // 日志实锤连点后十来条解析链在同一 isolate 里并发（各自把 26 个后端轮一遍），
            // 抢网络、拖慢真正要播的那首。取消即终止该实例，下次调用按脚本自动重建（约百毫秒）。
            LogBus.d(TAG, "resolveMusicUrl 已取消，终止在途脚本：$source/$songId")
            evict(scriptUrl, inst)
            throw e
        } catch (e: JsSessionTimeoutException) {
            lastFailureReason = "解析超时"
            LogBus.w(TAG, "resolveMusicUrl 超时（isolate 已终止）: $source/$songId")
            evict(scriptUrl, inst)
            null
        } catch (e: JsSessionClosedException) {
            lastFailureReason = "解析会话已失效"
            LogBus.w(TAG, "resolveMusicUrl 会话不可用: ${e.message}")
            evict(scriptUrl, inst)
            null
        } catch (e: Exception) {
            lastFailureReason = "解析异常：${e.message ?: "未知错误"}"
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

    /** v0.118.0：脚本内容变更（cookie 写回）后公开剔除，下次调用按新脚本重建。 */
    fun evictScript(scriptUrl: String) {
        val inst = synchronized(enginesLock) { engines.remove(scriptUrl) } ?: return
        runCatching { inst.session.close() }
    }

    /**
     * v0.103.0：取某脚本 inited 上报的音质 id 列表（全部平台展平去重，管理页 chips 用）。
     * v0.124.0：[getQualityIds] 的按平台重载才是播放/测试的取档口径，本函数只做展示。
     */
    fun getQualityIds(scriptUrl: String): List<String> = parseQualityIds(scriptUrl, null)

    /**
     * v0.124.0：取某脚本 inited 上报的**指定平台**音质 id 列表（播放逐档试用）。
     *
     * 真凶修复：LX 协议里 inited 的 `sources` 是以平台为键的**对象**
     * （`{wy:{qualitys:["128k","320k","flac"]}, tx:{...}}`，墨澜/聚合脚本实文本已核），
     * 旧实现按 JSONArray 解析永远拿到空——播放只能走兜底 320k→128k，flac 以上从没
     * 请求过，这才是"DS 能听高音质、这里不行"的根子。兼容个别脚本的数组形状与
     * qualitys 条目为 `{id}` 对象的写法。脚本未加载 / 无 inited / 解析失败返回空 list。
     */
    fun getQualityIds(scriptUrl: String, source: String): List<String> =
        parseQualityIds(scriptUrl, source)

    private fun parseQualityIds(scriptUrl: String, onlySource: String?): List<String> {
        val initedJson = synchronized(enginesLock) {
            engines[scriptUrl]?.initedJson
        } ?: return emptyList()
        if (initedJson.isBlank()) return emptyList()
        return runCatching {
            val seen = LinkedHashSet<String>()
            fun collect(qualitys: JSONArray?) {
                for (j in 0 until (qualitys?.length() ?: 0)) {
                    val id = when (val q = qualitys?.opt(j)) {
                        is JSONObject -> q.optString("id")
                        is String -> q
                        else -> ""
                    }
                    if (id.isNotBlank()) seen.add(id)
                }
            }
            when (val sources = JSONObject(initedJson).opt("sources")) {
                is JSONObject -> {
                    if (onlySource != null) {
                        collect(sources.optJSONObject(onlySource)?.optJSONArray("qualitys"))
                    } else {
                        sources.keys().forEach { key ->
                            collect(sources.optJSONObject(key)?.optJSONArray("qualitys"))
                        }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until sources.length()) {
                        collect(sources.optJSONObject(i)?.optJSONArray("qualitys"))
                    }
                }
            }
            seen.toList()
        }.onFailure {
            LogBus.w(TAG, "getQualityIds 解析失败: $scriptUrl ${it.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * v0.180.0：某脚本 inited 自报的平台 code 列表（墨澜/聚合类脚本的 sources 键）。
     * 这是音乐页平台行的真源——平台分类跟随音源走，不在 UI 里写死五家；
     * 音源以后多一个平台，这里就多一个 code，UI 不用改。脚本未加载返回空，
     * 调用方用已知平台兜底；键的顺序以 inited 原文为准，不在这里重排。
     */
    fun getSourcePlatforms(scriptUrl: String): List<String> {
        val initedJson = synchronized(enginesLock) {
            engines[scriptUrl]?.initedJson
        } ?: return emptyList()
        if (initedJson.isBlank()) return emptyList()
        return runCatching {
            when (val sources = JSONObject(initedJson).opt("sources")) {
                is JSONObject -> {
                    val keys = mutableListOf<String>()
                    sources.keys().forEach { k ->
                        if (k.isNotBlank()) keys.add(k.trim())
                    }
                    keys
                }
                else -> emptyList()
            }
        }.onFailure {
            LogBus.w(TAG, "平台列表解析失败: $scriptUrl ${it.message}")
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
                    // v0.118.0：注入脚本文本供 currentScriptInfo.rawScript——
                    // 墨澜/聚合类脚本靠它解析头部 @tx_cookie/@wy_cookie 声明
                    LX_SHIM.replace("__LX_RAW_SCRIPT__", JSONObject.quote(script)),
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
            // v1.4.3：lx.request 走零重试 client（HttpClient.lxClient），并落实
            // 脚本声明的单后端 timeout——此前 rpcHttpx 根本不读 options.timeout，
            // 脚本写的 8000/10000 全被静默忽略，挂起的后端只能等 defaultClient 的
            // 15s 读超时再被重试 3 次（2026-11-20 16:19:16 实证）。per-call timeout
            // 直接覆盖本次调用；超时抛 SocketTimeoutException，进 catch 记 status=-1，
            // 脚本侧 httpFetch 再转「网络错误」进 2 分钟熔断。
            val call = HttpClient.lxClient().newCall(reqBuilder.build())
            val timeoutMs = opt.optInt("timeout", 0)
            if (timeoutMs > 0) call.timeout().timeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            call.execute().use { resp ->
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
            utils: {
              crypto: {
                // host.js 在引导序列里先于本 shim 注入纯 JS 同步 md5（__hMd5）
                md5: function(str) {
                  try { return globalThis.__hMd5 ? globalThis.__hMd5(String(str)) : ''; }
                  catch(e) { return ''; }
                }
              },
              buffer: {}
            },
            currentScriptInfo: { rawScript: __LX_RAW_SCRIPT__ },
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
            request: function(url, options, callback) {
              var p = (async function() {
                var raw = await __hrpc({ kind: 'httpx', url: String(url), options: JSON.stringify(options || {}) });
                var res = {};
                try { res = JSON.parse(raw); } catch(e) { res = { status: -1, body: '', headers: {} }; }
                return { statusCode: res.status, body: res.body, headers: res.headers || {} };
              })();
              // v0.124.0：LX 协议的 request 支持回调式 (url, options, (err, resp)=>{})，
              // 墨澜/聚合类脚本的 httpFetch 正是回调式——旧 shim 只返 Promise、回调
              // 永不触发，脚本内所有后端请求都挂死到 15s 超时被 evict。
              if (typeof callback === 'function') {
                p.then(
                  function(res) { callback(null, res); },
                  function(err) { callback(err || new Error('request failed'), null); }
                );
              }
              return p;
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

        /**
         * v1.2.18：直链验活——脚本"解析成功"只代表拿到链接字符串，死链要到播放器
         * 拉流才报错（《天龙八部之宿敌》实证：oiapi 404 链接进播放器，5 秒后才报
         * ERROR_CODE_IO_BAD_HTTP_STATUS；重解析一次还是同一条死链）。解析到链接
         * 后先验一下，死链当解析失败，调用方现有的逐档/逐源回落链继续往下试，
         * 不再把死链交给播放器。验活只发 headers、不读 body；最坏 8 秒。
         *
         * 口径跟嗅探结算保持一致（SniffEngine）：
         * - 带明确媒体后缀的直链（.mp3/.flac/.m4a…）不验——v0.294.9 一次性
         *   地址保护：部分签名地址访问一次即失效，探测会把真正起播的那次请求作废。
         * - 其余（API 式地址，如 oiapi/vkeys/星海）用 Range 首字节验：2xx 且不是
         *   错误页（见 [isAliveMusicResponse]）才算活；Range 被拒（400/403/405/416）
         *   时退一步用普通 GET 只取响应头再判一次，防误杀不支持 Range 的站。
         * - 验活本身失败（超时/断连）按死链处理：调用方还有下一档/下一源/GD 兜底，
         *   误杀一档只降档、不静默。链接可能带 key，日志里一律不记 URL 本身。
         */
        internal suspend fun verifyAudioUrl(url: String): Boolean {
            if (SniffEngine.hasClearMediaSuffix(url)) return true
            return try {
                withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { probeAudioUrl(url) }
                } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBus.d(TAG, "直链验活异常: ${e.message}")
                false
            }
        }

        /** 验活单次探测（IO 线程）：Range 首字节，Range 被拒时退普通 GET 只取头。 */
        private fun probeAudioUrl(url: String): Boolean {
            val rangeReq = Request.Builder().url(url).header("Range", "bytes=0-1").get().build()
            probeOnce(rangeReq)?.let { return it }
            val getReq = Request.Builder().url(url).get().build()
            return probeOnce(getReq) ?: false
        }

        /**
         * 单次探测：返回判定结果；Range 被拒（400/403/405/416）返回 null 让调用方
         * 换普通 GET；网络异常也返回 null（调用方再试一次普通 GET，不行即死链）。
         */
        private fun probeOnce(req: Request): Boolean? {
            return runCatching {
                verifyClient.newCall(req).execute().use { resp ->
                    if (resp.code in setOf(400, 403, 405, 416)) return null
                    isAliveMusicResponse(
                        resp.code,
                        resp.header("Content-Type"),
                        resp.header("Content-Range"),
                        resp.header("Content-Length"),
                    )
                }
            }.getOrNull()
        }

        /**
         * v1.2.18：直链验活判定（纯函数，可单元测试）。
         * 活 = 2xx，且 Content-Type 不是错误页（text/html、application/json、
         * text/plain、JS/CSS、图片——错误接口常以 200 回 JSON/文字报错，如 xcvts
         * "接口已下架或不存在"），且总量/长度不为 0。Content-Type 缺失按活处理
         * （fail-open：部分 CDN 不回 CT）；Range 响应的 Content-Length 是区间
         * 长度、不是总量，总量只看 Content-Range 的 "/" 后面。
         */
        internal fun isAliveMusicResponse(
            code: Int,
            contentType: String?,
            contentRange: String?,
            contentLength: String?,
        ): Boolean {
            if (code !in 200..299) return false
            val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (ct.isNotEmpty() && (ct in DENY_CT || ct.startsWith("image/"))) return false
            contentRange?.substringAfter("/", "")?.toLongOrNull()?.let { total ->
                if (total <= 0) return false
            } ?: contentLength?.toLongOrNull()?.let { len ->
                if (len <= 0) return false
            }
            return true
        }

        /** 验活拒绝的 Content-Type：错误页与非音频内容（在结算拒绝集上加 text/plain）。 */
        private val DENY_CT = setOf(
            "text/html", "application/xhtml+xml",
            "application/json", "text/plain", "text/xml",
            "application/javascript", "text/javascript", "text/css",
        )

        /** 验活整单上限（毫秒）：Range 与普通 GET 两次尝试共用，超了按死链处理。 */
        private const val VERIFY_TIMEOUT_MS = 8_000L

        /** 验活专用 client：紧超时（3/3/5 秒）、零重试；从默认 client 派生，保留 UA/cookie/日志拦截器。 */
        private val verifyClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            CmsApi.defaultClient().newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
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
        val url = runCatching {
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
        }.getOrNull() ?: return@withContext null
        // v1.2.18：直链验活（与脚本源同口径），死链返回 null 让调用方继续回落。
        if (!LxMusicSourceEngine.verifyAudioUrl(url)) {
            LogBus.w("LxMusic", "内置 huibq 直链验活失败")
            return@withContext null
        }
        url
    }
