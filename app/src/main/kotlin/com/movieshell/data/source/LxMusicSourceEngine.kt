package com.movieshell.data.source

import android.content.Context
import android.os.SystemClock
import app.cash.quickjs.QuickJs
import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URI

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
 * - `console = {log,info,warn,error,debug}`：转 LogBus（v0.108.0：墨澜等脚本调 console，
 *   QuickJS 默认无 console 定义致脚本加载抛 ReferenceError，全 LX 源加载失败）
 *
 * 线程模型（v0.111.0）：本 holder 独占一条 JS 线程（[JsEngineThreads]），多个 LX
 * 脚本实例的 QuickJs 上下文继续串行跑在这条线程上，语义不变，只是从原来的
 * 全 App 共享线程搬到了自己的线程。任一 LX 脚本的慢网络/死循环只影响音乐解析，
 * 不卡 drpy。
 *
 * v0.99.1 单线程化原因依旧有效：app.cash.quickjs 的 JSContext 只能绑一个线程，
 * 所有 evaluate 强制走自己的独占线程，绝不在 Dispatchers.IO 上直接 evaluate。
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

    /**
     * v0.109.0 B4②：loadScript check-then-act 并发双建修复。旧代码锁外检查、
     * 锁外下载建引擎，两个并发协程会各下载一次、各 evaluate 一次。
     * 修：下载（IO 密集）不占锁；建引擎前在 Mutex 内二次确认——下载期间若已有
     * 并发协程先建好，直接复用，不再重复 evaluate。
     */
    private val scriptMutex = Mutex()

    /**
     * v0.111.0：一引擎一线程——本 holder 的独占 JS 线程（JsEngineThreads），懒建、
     * release 后可懒重建。用 @Volatile + 锁内双重赋值，禁裸 var。
     */
    @Volatile
    private var dispatcher: ExecutorCoroutineDispatcher? = null
    private val dispatcherLock = Any()
    private fun engineDispatcher(): ExecutorCoroutineDispatcher =
        synchronized(dispatcherLock) {
            dispatcher ?: JsEngineThreads.acquireDispatcher("lx").also { dispatcher = it }
        }

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
        // 快路：已加载直接返回
        synchronized(enginesLock) { if (engines.containsKey(scriptUrl)) return true }
        return try {
            val script = downloadText(scriptUrl) ?: return false
            scriptMutex.withLock {
                // v0.109.0 B4②：下载期间可能已有并发协程先建好，二次确认避免重复建引擎
                if (synchronized(enginesLock) { engines.containsKey(scriptUrl) }) return@withLock true
                val initedJson = createEngine(scriptUrl, script)
                initedJson != null
            }
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
                withContext(engineDispatcher()) {
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

    /**
     * 关闭所有缓存的 QuickJs 上下文（在自己的 JS 独占线程上执行，避免与 evaluate 并发）。
     * 收尾走 [JsEngineThreads.retireDispatcher]：线程上先逐个 close，3s 未执行
     * （线程已死）则后备直接归还许可，不漏信号量。
     * release 后线程归还 JsEngineThreads；若本 holder 被复用，engineDispatcher() 懒重建新线程。
     */
    fun release() {
        val all = synchronized(enginesLock) {
            val list = engines.values.toList()
            engines.clear()
            list
        }
        val d = synchronized(dispatcherLock) {
            dispatcher.also { dispatcher = null }
        } ?: return
        JsEngineThreads.retireDispatcher(d) {
            all.forEach { runCatching { it.qjs.close() } }
        }
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

    // ---------------- 内部：引擎创建（必须在本 holder 独占 JS 线程内调用） ----------------

    /**
     * 在 JS 线程创建 QuickJs 实例：绑定 `__lxBridge` 接口桥 → 跑 lx shim →
     * evaluate 脚本 → 等 `inited` 事件 payload。成功返回 payload JSON，失败 null。
     */
    private suspend fun createEngine(scriptUrl: String, script: String): String? {
        return try {
            withTimeout(JS_TIMEOUT_MS) {
                withContext(engineDispatcher()) {
                    runCatching {
                        val qjs = QuickJs.create()
                        try {
                            qjs.set("__lxBridge", LxBridgeInterface::class.java, LxBridge())
                            qjs.evaluate(LX_SHIM, "lx-shim.js")
                            qjs.set("__lxConsole", LxConsoleInterface::class.java, LxConsole())
                            qjs.evaluate(CONSOLE_SHIM, "lx-console.js")
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
     * v0.109.0 B4③（如实结论，不硬改挂起式等待），v0.111.0 线程口径更新：
     * waitInited 运行在本 holder 的独占 JS 线程（[JsEngineThreads]，不再是全 App 共享线程）
     * 上——createEngine 在 `withContext(engineDispatcher())` 里先
     * `qjs.evaluate(script)` 再调 waitInited。`lx.send('inited')` 把 payload 写入
     * `__lxInitedPayload` 发生在同线程的 evaluate 返回之前，所以"挂起等待 init 完成"
     * 救不了：单线程上下文里没有别的线程能让 inited"稍后到达"，app.cash.quickjs
     * 也不会在 sleep 期间泵事件循环。旧 15s Thread.sleep 轮询纯粹把本 holder 的
     * JS 执行冻结 15s（且 withTimeout 的取消也中断不了 Thread.sleep）。
     * 替代方案：evaluate 返回后立即检查一次（同步上报的脚本必命中），再给一个
     * 很短的有界轮询（3s，兼容极少数延迟上报），deadline 走单调时钟
     * SystemClock.elapsedRealtime()（不受墙钟跳变影响）。外层 createEngine 的
     * withTimeout(15s) 仍是总兜底。
     */
    private fun waitInited(qjs: QuickJs): String? {
        readInitedPayload(qjs)?.let { return it }
        val deadline = SystemClock.elapsedRealtime() + INIT_POLL_BUDGET_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(120)
            readInitedPayload(qjs)?.let { return it }
        }
        LogBus.w(TAG, "未收到 inited 事件（脚本可能不支持 lx 运行时协议）")
        return null
    }

    /** 读 shim 在 send('inited') 时写入的 __lxInitedPayload。 */
    private fun readInitedPayload(qjs: QuickJs): String? {
        val v = runCatching {
            qjs.evaluate("typeof __lxInitedPayload === 'undefined' ? null : __lxInitedPayload")?.toString()
        }.getOrNull()
        return v?.takeIf { it.isNotBlank() && it != "null" && it != "undefined" }
    }

    // ---------------- 内部：request 事件调用（必须在本 holder 独占 JS 线程内调用） ----------------

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
     * v0.108.0：`globalThis.console` 垫片。QuickJS 默认没有 console，墨澜等 LX 脚本
     * 调 console.log/info/warn/error/debug 会抛 ReferenceError 致整个脚本加载失败。
     * 多参数按空格拼接，对象走 JSON.stringify；全部转 LogBus（沿用本文件 "LX: " 口径）。
     */
    private val CONSOLE_SHIM = """
        (function(){
          if (globalThis.console) return;
          function fmt(a) {
            var out = [];
            for (var i = 0; i < a.length; i++) {
              var x = a[i];
              try { out.push((x !== null && typeof x === 'object') ? JSON.stringify(x) : String(x)); }
              catch (e) { out.push(String(x)); }
            }
            return out.join(' ');
          }
          globalThis.console = {
            log: function(){ __lxConsole.log(fmt(arguments)); },
            info: function(){ __lxConsole.info(fmt(arguments)); },
            warn: function(){ __lxConsole.warn(fmt(arguments)); },
            error: function(){ __lxConsole.error(fmt(arguments)); },
            debug: function(){ __lxConsole.debug(fmt(arguments)); }
          };
        })();
    """.trimIndent()

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

    /**
     * v0.108.0：console 桥。QuickJS 的 set() 只接受接口绑定，
     * 五个方法分别转 LogBus 的 d/w/e（log/info/debug 走 d，warn 走 w，error 走 e）。
     */
    interface LxConsoleInterface {
        fun log(msg: String)
        fun info(msg: String)
        fun warn(msg: String)
        fun error(msg: String)
        fun debug(msg: String)
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

    /** console.* 的宿主实现：log/info/debug → LogBus.d，warn → LogBus.w，error → LogBus.e。 */
    inner class LxConsole : LxConsoleInterface {
        override fun log(msg: String) = LogBus.d(TAG, "LX console: $msg")
        override fun info(msg: String) = LogBus.d(TAG, "LX console: $msg")
        override fun warn(msg: String) = LogBus.w(TAG, "LX console: $msg")
        override fun error(msg: String) = LogBus.e(TAG, "LX console: $msg")
        override fun debug(msg: String) = LogBus.d(TAG, "LX console: $msg")
    }

    companion object {
        private const val TAG = "LxMusic"
        private const val JS_TIMEOUT_MS = 15_000L

        /**
         * v0.109.0 B4③：waitInited 有界轮询预算。同步上报的脚本在 evaluate 返回时
         * payload 已就绪；这 3s 只为兼容极少数延迟上报，超时后外层 withTimeout(15s)
         * 仍兜底。把原来最长 15s 的 JS 线程冻结压到 3s。
         */
        private const val INIT_POLL_BUDGET_MS = 3_000L

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
