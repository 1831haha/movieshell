package com.movieshell.data.source

import android.content.Context
import android.util.Base64
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.FilterGroup
import com.movieshell.data.model.FilterOption
import com.movieshell.data.model.Vod
import com.movieshell.data.model.VodDetail
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.StoragePaths
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    @Volatile
    private var session: JsSession? = null

    /**
     * v0.187.0 H04：最近一次调用的失败原因（null = 最近一次成功或未跑过）。
     * eval 超时/会话关闭/普通异常、引导失败、解析失败时写；eval 成功、换规则、释放时清。
     * 规则合法返回空列表不写（空列表本身不是失败），调用方凭它区分“没数据”与“执行失败”。
     */
    @Volatile
    override var lastError: String? = null
        private set

    /**
     * 建会话串行锁：两个并发 ensureSession 只能建出一个，另一个复用它。
     * 建会话期间 loadRule/release 若介入，epoch 变化会让本次建好的会话在发布前
     * 被丢弃并 close，不把旧规则会话发布给新规则调用方。
     */
    private val sessionMutex = Mutex()
    private val sessionFieldLock = Any()
    @Volatile
    private var sessionEpoch = 0

    private val hostJs by lazy { assetText("jsbridge/host.js") }
    private val shimJs by lazy { assetText("drpy/shim.js") }

    companion object {
        private const val TAG = "V8Drpy"
        private const val JS_TIMEOUT_MS = 15_000L
        /** v0.110.0：home 缓存窗口 60s（原 10s）。规则标识 = 引擎实例本身，切源建新实例。 */
        private const val HOME_CACHE_MS = 60_000L
        /** v0.135.0：home 首调为空时重试前的等待，给规则未完成的异步取数留落地时间。 */
        private const val HOME_RETRY_DELAY_MS = 1_200L
        /** v0.109.0 P0-1：query() 单次返回元素上限，防超大页面 JSON 爆炸。 */
        private const val MAX_QUERY_ELEMENTS = 5000
    }

    private fun assetText(path: String): String =
        appContext.assets.open(path).bufferedReader().readText()

    override fun loadRule(js: String) {
        // v0.140.0：import 内联展开（见 RuleImportExpander）。规则与 lib 文件
        // 都在 StoragePaths.jsDir()（xiaying/js/）下，相对路径按该目录解析；
        // canonical 路径校验防 ../ 逃出规则目录。
        val expanded = RuleImportExpander.expand(js) { spec ->
            runCatching {
                val dir = StoragePaths.jsDir(appContext)
                val f = File(dir, spec)
                if (f.canonicalPath.startsWith(dir.canonicalPath) && f.isFile) f.readText() else null
            }.getOrNull()
        }
        if (expanded.inlined.isNotEmpty()) {
            LogBus.i(TAG, "import 已内联：${expanded.inlined.joinToString()}（规则目录 xiaying/js/）")
        }
        if (expanded.missing.isNotEmpty()) {
            LogBus.e(
                TAG,
                "import 找不到文件，已跳过（请把文件放进 xiaying/js/）：" +
                    expanded.missing.joinToString(),
            )
        }
        expanded.warnings.forEach { LogBus.w(TAG, it) }
        ruleJs = expanded.js
        lastError = null
        // v0.110.0：换规则即失效 home 缓存，避免旧规则的 home 串给新规则。
        sharedHomeJson = null
        sharedHomeAt = 0L
        homeFallbackCache = null
        // isolate.close() 线程安全、即时生效；下次调用时用新规则重建会话。
        // epoch 先加：建会话中途换规则时，建好的旧规则会话在发布前被丢弃并 close。
        sessionEpoch++
        val old = synchronized(sessionFieldLock) { session.also { session = null } }
        runCatching { old?.close() }
        // v0.116.0：老同步规则 async 兼容提示——启发式扫描，未 await 的调用只会静默空数据
        warnIfLegacySyncRule(js)
    }

    /**
     * v0.116.0：扫描规则里疑似未加 await 的 async 调用（request/fetch/post/pdfh/pdfa/pd）。
     * 老同步写法 `var html = request(url)` 在 V8 下拿到的是 Promise 对象、数据全空且无报错，
     * 排障极痛。启发式：同一行 `await` 之后出现才算合规；function 定义、同名遮蔽、行尾注释
     * 粗略排除。只报 Warning 不拦截（疑似，不是定罪）。
     */
    private fun warnIfLegacySyncRule(js: String) {
        val call = Regex("""(?<![\w$.])(request|fetch|post|pdfh|pdfa|pd)\s*\(""")
        var count = 0
        js.lines().forEachIndexed { idx, raw ->
            var line = raw.trimStart()
            if (line.startsWith("//")) return@forEachIndexed
            line = line.substringBefore("//") // 粗略去行尾注释（字符串里的 // 误伤可接受）
            for (m in call.findAll(line)) {
                val name = m.groupValues[1]
                if (Regex("""\bfunction\s+$name\s*\(""").containsMatchIn(line)) continue // 自带定义
                // await 直接修饰合法；async 函数里 return 一个 Promise 也合法（自动吸收）
                val before = line.substring(maxOf(0, m.range.first - 9), m.range.first)
                    .trimEnd()
                if (before.endsWith("await") || before.endsWith("return")) continue
                count++
                if (count <= 5) LogBus.d(TAG, "疑似未 await：第 ${idx + 1} 行 `$name(`")
            }
        }
        if (count > 0) {
            LogBus.w(
                TAG,
                "规则疑似老同步写法：$count 处 async 调用未加 await，" +
                    "数据可能静默为空（对照改法：写源技能 drpy-v8-async-rules §2）",
            )
        }
    }

    /**
     * 惰性建会话：注册宿主 RPC（http/query/gunzip）→ bootstrap host.js + shim + 规则。
     * 串行化：并发调用在 sessionMutex 内排队，先建成的被后来者复用，不会各自
     * bootstrap 出两个会话；失效会话只断引用，延迟回收由 JsSession 负责 close。
     */
    private suspend fun ensureSession(): JsSession? = sessionMutex.withLock {
        val current = synchronized(sessionFieldLock) { session }
        if (current != null) {
            if (!current.isInvalid) return@withLock current
            synchronized(sessionFieldLock) { if (session === current) session = null }
        }
        val rule = ruleJs ?: run {
            lastError = "未载入规则"
            return@withLock null
        }
        val epoch = sessionEpoch
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
        if (!s.bootstrap(portName, scripts)) {
            s.close()
            val reason = "V8 引擎引导失败: ${s.lastError}"
            lastError = reason
            LogBus.e(TAG, reason)
            return@withLock null
        }
        // 建会话期间换规则或 release 过：刚建好的会话已过时，close 后不发布。
        if (sessionEpoch != epoch || ruleJs != rule) {
            s.close()
            lastError = "会话在发布前已失效（换规则或释放）"
            return@withLock null
        }
        synchronized(sessionFieldLock) { session = s }
        LogBus.i(TAG, "V8 引擎已就绪")
        s
    }

    override suspend fun home(): List<Vod> = withContext(Dispatchers.IO) {
        val json = sharedHomeJson() ?: return@withContext emptyList()
        val list = parseVodList(json)
        // 有数据直接返回；规则报错（error 字段）保持原口径交空，不进兜底
        if (list.isNotEmpty() || homeHasError(json)) return@withContext list
        // v0.135.0：调用成功但推荐为空 → 两级兜底（见 homeFallback）
        homeFallback()
    }

    /**
     * v0.135.0：home 推荐为空时的两级兜底，仅在「__drpyHome 调用成功但 list 为空」
     * 时触发（规则报错/引导失败在 home() 已按原口径交空，不进这里）。
     * 背景：老同步写法规则在 V8 下首页取数有竞态，首调常返回空 list，要等分类调用
     * 把规则内部状态跑热后二调才有数据（真机实证「全部空白、切分类才出、切回又有」）；
     * PyApi 早有同类兜底（homeContent 无推荐改调 homeVideoContent），drpy 这边补齐。
     * 一级：清共享缓存，延迟 [HOME_RETRY_DELAY_MS] 后重调一次 __drpyHome，list 与
     * class 都以第二次为准（categories() 的共享缓存随之刷新）。
     * 二级：重试仍空且有分类时，用第一个分类的第一页顶上。分类走 category() 直调
     * eval、不经 home()，不会递归触发本兜底；分类失败/为空就交空。
     * 兜底结果按 sharedHomeAt 世代缓存：同一份 home JSON 世代内重复调 home() 直接
     * 复用，不重复付重试与分类的代价；缓存过期重拉后仍为空才会重新兜底。
     */
    private suspend fun homeFallback(): List<Vod> {
        homeFallbackCache?.let { (at, list) ->
            if (at == sharedHomeAt) return list
        }
        LogBus.i(TAG, "home 首调推荐为空，清缓存重试一次")
        sharedHomeJson = null
        sharedHomeAt = 0L
        delay(HOME_RETRY_DELAY_MS)
        val json = sharedHomeJson() ?: return emptyList()
        var list = parseVodList(json)
        if (list.isEmpty() && !homeHasError(json)) {
            val firstCat = parseCategoriesFromHome(json).firstOrNull()
            if (firstCat != null) {
                list = category(firstCat.typeId, 1, emptyMap())
                LogBus.i(TAG, "home 无推荐，用第一分类顶上：${firstCat.typeName} ${list.size} 条")
            }
        }
        homeFallbackCache = sharedHomeAt to list
        return list
    }

    /** __drpyHome 返回 {"error":...} 表示规则自身报错（shim 的 errJson 契约）。 */
    private fun homeHasError(json: String): Boolean =
        runCatching { JSONObject(json).has("error") }.getOrDefault(false)

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

    /** v0.135.0：home 兜底结果（sharedHomeAt 世代 → 列表），见 homeFallback。 */
    @Volatile
    private var homeFallbackCache: Pair<Long, List<Vod>>? = null

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
                val reason = "规则返回 error: ${o.optString("error")}"
                lastError = reason
                LogBus.w(TAG, reason)
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
        }.onFailure {
            val reason = "规则结果解析失败: ${it.message}"
            lastError = reason
            LogBus.w(TAG, reason)
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
        lastError = null
        sessionEpoch++
        val old = synchronized(sessionFieldLock) { session.also { session = null } }
        runCatching { old?.close() }
    }

    /**
     * evaluate 导出入口（返回 JSON 字符串的 Promise）。失败返回 null：
     * - 超时/会话终止 → 会话置 null，下次调用重建（新 isolate，无残留死循环）；
     * - 普通 JS 异常 → isolate 未损坏，保留会话（单次规则错误不牵连后续调用）。
     */
    private suspend fun evalForJson(expression: String): String? {
        val s = ensureSession() ?: return null
        return try {
            val result = s.eval(expression, JS_TIMEOUT_MS)
            if (result.isBlank() || result == "null" || result == "undefined") {
                // v0.187.0 H04：此前这里静默返回 null，调用方无法区分“规则返回空”
                // 与“执行失败”。JS 连有效 JSON 串都没交出，按执行失败记原因。
                val reason = "JS 执行返回空（规则无返回或返回 null）"
                lastError = reason
                LogBus.w(TAG, reason)
                null
            } else {
                lastError = null
                result
            }
        } catch (e: JsSessionTimeoutException) {
            val reason = "JS 执行超时，会话已标失效并延迟回收: ${e.message}"
            lastError = reason
            LogBus.w(TAG, reason)
            // 只断开仍是本次会话的引用：其间若已建成新会话，不得把它误清。
            // 本次会话的 close 由 JsSession 延迟回收负责，这里不再同步 close。
            synchronized(sessionFieldLock) { if (session === s) session = null }
            null
        } catch (e: JsSessionClosedException) {
            val reason = "JS 会话不可用: ${e.message}"
            lastError = reason
            LogBus.w(TAG, reason)
            synchronized(sessionFieldLock) { if (session === s) session = null }
            null
        } catch (e: Exception) {
            val reason = "JS 执行失败: ${e.message}"
            lastError = reason
            LogBus.w(TAG, reason)
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
                val reason = "规则返回 error: ${o.optString("error")}"
                lastError = reason
                LogBus.w(TAG, reason)
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
        }.onFailure {
            val reason = "规则结果解析失败: ${it.message}"
            lastError = reason
            LogBus.w(TAG, reason)
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
                val reason = "规则返回 error: ${o.optString("error")}"
                lastError = reason
                LogBus.w(TAG, reason)
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
        }.onFailure {
            val reason = "规则结果解析失败: ${it.message}"
            lastError = reason
            LogBus.w(TAG, reason)
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
        }.onFailure {
            val reason = "详情解析失败: ${it.message}"
            lastError = reason
            LogBus.w(TAG, reason)
        }.getOrDefault(VodDetail())
    }

    private fun parsePlayUrl(json: String): String {
        return runCatching {
            val o = JSONObject(json)
            o.optString("url", o.optString("play_url", ""))
        }.onFailure {
            val reason = "播放地址解析失败: ${it.message}"
            lastError = reason
            LogBus.w(TAG, reason)
        }.getOrDefault("")
    }
}
