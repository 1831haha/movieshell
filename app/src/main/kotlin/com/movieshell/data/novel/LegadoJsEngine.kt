package com.movieshell.data.novel

import android.util.Log
import com.movieshell.data.repo.LogBus
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DEFAULT_UA_CHROME120
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeJavaArray
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v0.199.0：阅读式 JS 书源引擎。
 *
 * 为什么不用现成的 V8：视频源那套 isolate 只有异步宿主调用（MessagePort Promise），
 * 而阅读的 JS 是同步调 java.ajax 拿结果、同步改 result，两者模型不通，硬套要改写
 * 用户规则原文。阅读原生用的就是 Rhino（JVM 同步 JS 引擎），这里直接用同款，
 * 规则原文不用改就能跑。
 *
 * 安全：ClassShutter 只放行本包的桥类，脚本碰不到任意 Java 类；指令数上限防死循环；
 * 所有网络走共享 OkHttp 单例（带内存 CookieJar，与旧引擎同会话）。
 *
 * v1 范围：搜索/目录/正文三条链 + searchUrl JS + loginCheckJs + 源变量 + 正文/目录分页。
 * 不在 v1：发现页（书架无入口）、详情封面 JS（侠影不单独拉详情页）、jsLib、
 * WebView 验证（startBrowserAwait 走 VerifyGate 全屏 WebView 验证通道，见 VerifyGateDialog）。
 * 源变量落盘（filesDir/novel_source_vars.json），重启后重定向域名记忆仍在。
 *
 * 调用约束：Rhino 求值与 java.ajax 都是同步阻塞，调用方必须在 IO 线程
 * （NovelSourceEngine 的 JS 分支统一包 withContext(Dispatchers.IO)）。
 */
object LegadoJsEngine {

    private const val TAG = "LegadoJs"

    /** 单次求值指令上限（内容 JS 含上百条替换，放宽到 1000 万，死循环仍会被截停）。 */
    private const val INSTRUCTION_LIMIT = 10_000_000

    /** 源变量：源 key → 变量 JSON 字符串。 */
    object SourceVariableStore {
        private val vars = ConcurrentHashMap<String, String>()
        private var dir: File? = null
        private const val FILE_NAME = "novel_source_vars.json"

        /** v0.200.0：App 启动时注入 filesDir，落盘读回；未注入时只放内存。 */
        fun init(filesDir: File) {
            dir = filesDir
            runCatching {
                val f = File(filesDir, FILE_NAME)
                if (!f.exists()) return@runCatching
                val o = JSONObject(f.readText())
                o.keys().forEach { k -> vars[k] = o.optString(k) }
            }
        }

        fun get(key: String): String = vars[key].orEmpty()

        /** 值变了返回 true（供登录校验判断是否需要重取），并同步落盘。 */
        fun set(key: String, value: String): Boolean {
            if (vars[key] == value) return false
            vars[key] = value
            persist()
            return true
        }

        private fun persist() {
            val d = dir ?: return
            runCatching {
                val o = JSONObject()
                vars.forEach { (k, v) -> o.put(k, v) }
                File(d, FILE_NAME).writeText(o.toString())
            }
        }
    }

    /** 一次规则求值共享的可变状态（跟随单次搜索/目录/正文调用走，不跨调用）。 */
    class JsState(val sourceKey: String) {
        /** java.getString / setContent 操作的当前页面源码。 */
        var currentSrc: String = ""
        var lastPage: BookRuleEngine.PageResult? = null
        var baseUrl: String = ""
        var variableChanged: Boolean = false
    }

    /** 规则求值的上下文。 */
    data class JsCtx(
        val source: BookSource,
        val baseUrl: String,
        val bookName: String = "",
        val bookUrl: String = "",
        val bookAuthor: String = "",
        val chapterTitle: String = "",
        val chapterUrl: String = "",
        val keyword: String = "",
        val page: Int = 1,
    )

    /** 发给 JS 的响应包装：脚本里调 result.body() / result.url()。 */
    class StrResponse(private val bodyText: String, private val urlText: String) {
        fun body(): String = bodyText
        fun url(): String = urlText
    }

    /** book / chapter 的 JS 对象标记（求值时转成 NativeObject）。 */
    data class JsObj(val props: Map<String, String>)

    /** cookie 桥：清掉该地址 host 在 OkHttp Jar 与 WebView 里的 Cookie（验证重进用）。 */
    class CookieBridge {
        fun removeCookie(url: String) {
            CmsApi.clearCookies(url)
        }
    }

    class SourceBridge(private val sourceKey: String, private val state: JsState) {
        fun getVariable(): String = SourceVariableStore.get(sourceKey)
        fun setVariable(value: String) {
            if (SourceVariableStore.set(sourceKey, value)) state.variableChanged = true
        }
    }

    class CryptoBridge(transformation: String, keyStr: String, ivStr: String) {
        // JCE 里 PKCS7Padding 等价写法是 PKCS5Padding。
        private val realTransformation = transformation.replace("PKCS7Padding", "PKCS5Padding")
        private val keySpec = SecretKeySpec(keyStr.toByteArray(Charsets.UTF_8), "AES")
        private val ivSpec = IvParameterSpec(ivStr.toByteArray(Charsets.UTF_8))

        fun decryptStr(input: String): String = runCatching {
            val raw = runCatching { Base64.getDecoder().decode(input) }
                .getOrElse { input.toByteArray(Charsets.UTF_8) }
            val cipher = Cipher.getInstance(realTransformation)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            String(cipher.doFinal(raw), Charsets.UTF_8)
        }.getOrDefault("")

        fun encryptStr(input: String): String = runCatching {
            val cipher = Cipher.getInstance(realTransformation)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            Base64.getEncoder().encodeToString(cipher.doFinal(input.toByteArray(Charsets.UTF_8)))
        }.getOrDefault("")
    }

    class JavaBridge(private val state: JsState, private val ua: String?) {
        private val client = CmsApi.defaultClient()

        /** 同步抓取，返回正文；同时记 lastPage（供 getStrResponse）。 */
        fun ajax(url: String): String {
            val page = fetchRaw(url) ?: return ""
            state.lastPage = page
            return page.body
        }

        fun getStrResponse(): StrResponse {
            val p = state.lastPage
            return if (p == null) StrResponse("", "") else StrResponse(p.body, p.url)
        }

        /** 在 currentSrc 上跑一条链式规则（正文 JS 里取 .page-content 用）。 */
        fun getString(rule: String): String {
            if (state.currentSrc.isBlank()) return ""
            val doc = Jsoup.parse(state.currentSrc, state.baseUrl)
            return evalChain(doc, state.baseUrl, rule) { _, _ -> null }.firstText()
        }

        fun setContent(content: String) {
            state.currentSrc = content
        }

        fun md5Encode(input: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun base64Decode(input: String): String = runCatching {
            String(Base64.getDecoder().decode(input), Charsets.UTF_8)
        }.getOrDefault("")

        fun createSymmetricCrypto(transformation: String, key: String, iv: String): CryptoBridge =
            CryptoBridge(transformation, key, iv)

        fun log(message: String) {
            Log.d(TAG, message)
        }

        fun toast(message: String) {
            Log.d(TAG, "toast: $message")
        }

        /**
         * v0.201.0：Cloudflare/WebView 验证——在 IO 线程挂起等用户在全屏
         * WebView 里完成验证（最长 180 秒），拿到 HTML 后把 Cookie 回写共享 Jar，
         * 返回验证后的响应；超时/取消返回空响应，规则求值按空结果处理。
         */
        fun startBrowserAwait(url: String, title: String): StrResponse {
            val result = runCatching {
                kotlinx.coroutines.runBlocking { VerifyGate.awaitVerify(url, title, ua) }
            }.getOrNull() ?: return StrResponse("", url)
            if (result.cookieHeader.isNotBlank()) {
                CmsApi.putCookies(result.url, result.cookieHeader)
            }
            return StrResponse(result.html, result.url)
        }

        /** 按最后一次响应的落地 URL 重算 baseUrl（取 scheme://host/）。 */
        fun initUrl() {
            val u = state.lastPage?.url ?: return
            state.baseUrl = runCatching {
                val parsed = java.net.URL(u)
                "${parsed.protocol}://${parsed.host}/"
            }.getOrDefault(state.baseUrl)
        }

        /**
         * 原始请求：URL 可带 ,{...} 选项（方法/表单体/编码/附加头）。
         * 注意：表单体按原样发（JS 里已拼好 key/page，不做 {{}} 替换）。
         */
        fun fetchRaw(urlWithOptions: String): BookRuleEngine.PageResult? {
            val req = BookRuleEngine.buildSearchRequest(urlWithOptions, "")
            return runCatching {
                val builder = Request.Builder()
                    .url(req.url)
                    .header("User-Agent", ua ?: DEFAULT_UA_CHROME120)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                req.headers.forEach { (k, v) -> builder.header(k, v) }
                if (req.method == "POST" && req.body != null) {
                    val charset = runCatching { Charset.forName(req.charsetName) }
                        .getOrDefault(Charsets.UTF_8)
                    builder.post(
                        req.body.toByteArray(charset)
                            .toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                    )
                }
                client.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val bytes = resp.body?.bytes() ?: return null
                    BookRuleEngine.PageResult(
                        BookRuleEngine.decodeBytes(bytes),
                        resp.request.url.toString(),
                    )
                }
            }.getOrNull()
        }
    }

    private val rhinoFactory = object : ContextFactory() {
        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (instructionCount > INSTRUCTION_LIMIT) throw Error("JS 规则执行超限")
        }
    }

    /** 求值一段 JS，bindings 按名进 scope；返回 Rhino 原生值（用 toJsString/toJsStringList 转）。 */
    fun evalScript(script: String, bindings: Map<String, Any?>, prelude: String = ""): Any? {
        if (script.isBlank()) return null
        val full = if (prelude.isBlank()) script else prelude + "\n" + script
        val cx = rhinoFactory.enterContext()
        try {
            cx.optimizationLevel = -1
            // v0.226.0：规则里 let 这类现代写法在默认语言版本下可能直接语法错误，
            // 整段求值失败又只记系统 Log，搜书就静默空；改走 ES6 并记进悬浮窗。
            cx.setLanguageVersion(Context.VERSION_ES6)
            cx.setClassShutter(ClassShutter { name ->
                name.startsWith("com.movieshell.data.novel.") ||
                    name == "java.lang.String" || name == "java.lang.Integer" ||
                    name == "java.lang.Double" || name == "java.lang.Long" ||
                    name == "java.lang.Boolean"
            })
            cx.setInstructionObserverThreshold(50000)
            val scope = cx.initStandardObjects()
            bindings.forEach { (k, v) ->
                if (v == null) return@forEach
                val jsValue = when (v) {
                    is JsObj -> {
                        val o = cx.newObject(scope)
                        v.props.forEach { (pk, pv) -> ScriptableObject.putProperty(o, pk, pv) }
                        o
                    }
                    else -> Context.javaToJS(v, scope)
                }
                ScriptableObject.putProperty(scope, k, jsValue)
            }
            return runCatching { cx.evaluateString(scope, full, "rule", 1, null) }
                .onFailure { LogBus.w(TAG, "JS 求值失败: ${it.message}") }
                .getOrNull()
        } finally {
            Context.exit()
        }
    }

    /** v0.200.0：jsLib 前置代码（内联形式；URL 形式暂不支持，返回空）。 */
    fun jsLibPrelude(source: BookSource): String {
        val lib = source.jsLib
        return if (lib.startsWith("http")) "" else lib
    }

    /** 内部求值统一入口：自动带 jsLib 前置代码。 */
    private fun evalJs(script: String, ctx: JsCtx, state: JsState, result: Any?, src: String): Any? =
        evalScript(script, bindings(ctx, state, result, src), jsLibPrelude(ctx.source))

    /**
     * v0.200.0：规则里的 {{book.name}} / {{book.bookUrl}} / {{book.author}} /
     * {{chapter.title}} / {{chapter.url}} / {{title}} 模板替换（阅读式写法，如封面 JS）。
     * 只替换规则字符串本身，不碰求值产出的正文。
     */
    fun substituteTemplates(rule: String, ctx: JsCtx): String {
        if (!rule.contains("{{")) return rule
        return rule.replace("{{book.name}}", ctx.bookName)
            .replace("{{book.bookUrl}}", ctx.bookUrl)
            .replace("{{book.author}}", ctx.bookAuthor)
            .replace("{{chapter.title}}", ctx.chapterTitle)
            .replace("{{chapter.url}}", ctx.chapterUrl)
            .replace("{{title}}", ctx.chapterTitle)
    }

    /** 一次求值的绑定表：result/src/baseUrl/java/source/cookie/title/book/chapter/key/page。 */
    fun bindings(ctx: JsCtx, state: JsState, result: Any?, src: String): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "baseUrl" to ctx.baseUrl,
            "source" to SourceBridge(ctx.source.key, state),
            "cookie" to CookieBridge(),
            "java" to JavaBridge(state, ctx.source.headerUa.ifBlank { null }),
            "src" to src,
            "title" to ctx.chapterTitle,
            "key" to ctx.keyword,
            "page" to ctx.page,
            "book" to JsObj(
                mapOf(
                    "name" to ctx.bookName,
                    "bookUrl" to ctx.bookUrl,
                    "author" to ctx.bookAuthor,
                ),
            ),
            "chapter" to JsObj(
                mapOf(
                    "title" to ctx.chapterTitle,
                    "url" to ctx.chapterUrl,
                ),
            ),
        )
        if (result != null) map["result"] = result
        return map
    }

    fun toJsString(value: Any?): String = when (value) {
        null, is Undefined -> ""
        is String -> value
        is Number -> if (value is Double && value == kotlin.math.floor(value)) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        is Boolean -> value.toString()
        is NativeJavaObject -> toJsString(value.unwrap())
        is NativeArray -> toJsStringList(value).joinToString(",")
        is NativeJavaArray -> toJsStringList(value).joinToString(",")
        else -> value.toString()
    }

    fun toJsStringList(value: Any?): List<String> = when (value) {
        null, is Undefined -> emptyList()
        is String -> listOf(value)
        is NativeArray, is NativeJavaArray -> {
            val arr = value as Scriptable
            val len = (ScriptableObject.getProperty(arr, "length") as? Number)?.toLong() ?: 0L
            (0 until len).mapNotNull { i ->
                val v = arr.get(i.toInt(), arr)
                if (v === Scriptable.NOT_FOUND) null else toJsString(v)
            }
        }
        is NativeJavaObject -> toJsStringList(value.unwrap())
        is Array<*> -> value.map { toJsString(it) }
        is List<*> -> value.map { toJsString(it) }
        else -> listOf(toJsString(value))
    }

    /** 拆 <js> 前缀：返回（JS 代码？，余下规则）。 */
    fun splitJsPrefix(rule: String): Pair<String?, String> {
        val t = rule.trim()
        if (!t.startsWith("<js>")) return null to rule
        val end = t.indexOf("</js>")
        if (end < 0) return t.removePrefix("<js>") to ""
        return t.substring(4, end) to t.substring(end + 5)
    }

    /** 链式求值结果：元素列 / 文本列 / 单文本。 */
    sealed interface ChainResult {
        fun firstText(): String
        fun allTexts(): List<String>
        fun elems(): List<Element>
    }

    private data class Elems(val list: List<Element>) : ChainResult {
        override fun firstText(): String = list.firstOrNull()?.text().orEmpty()
        override fun allTexts(): List<String> = list.map { it.text() }
        override fun elems(): List<Element> = list
    }

    private data class Texts(val list: List<String>) : ChainResult {
        override fun firstText(): String = list.firstOrNull().orEmpty()
        override fun allTexts(): List<String> = list
        override fun elems(): List<Element> = emptyList()
    }

    /**
     * 链式规则求值（阅读 CSS 链子集，够 999 用）：
     * - 段与段之间用 @ 分隔；段可以是 CSS 选择器、tag.xxx、纯数字下标、!N 排除下标、
     *   末尾 .N 下标（如 .info.0、ul.list.0）、text/html/属性名；
     * - 段尾带 !N 表示去掉第 N 个（如 tag.a!0）；
     * - @js: 段：把当前值交给 JS（文本列多值时交数组，单值交字符串），返回新值；
     * - 规则整体可带 ## 正则替换链（先去尾部 ###，再按 base + 正则/替换成对切分，
     *   落单的正则按删除处理）；
     * - base 为空时用根元素的整页 HTML 作正则输入（详情作者那种 ##正则##$1 规则）。
     */
    fun evalChain(
        root: Element,
        pageUrl: String,
        rule: String,
        jsEval: (script: String, input: Any?) -> Any?,
    ): ChainResult {
        var r = rule.trim()
        if (r.isEmpty()) return Texts(emptyList())
        if (r.length > 3 && r.endsWith("###")) r = r.dropLast(3)
        val hashParts = r.split("##")
        val base = hashParts[0]
        val transforms = hashParts.drop(1)
        var result: ChainResult = if (base.contains("&&") && !base.contains("@js:")) {
            val parts = base.split("&&").map { evalChainBase(root, pageUrl, it.trim(), jsEval) }
            Texts(listOf(parts.joinToString("") { it.firstText() }))
        } else {
            evalChainBase(root, pageUrl, base, jsEval)
        }
        var i = 0
        while (i < transforms.size) {
            val regex = transforms[i]
            val replacement = transforms.getOrNull(i + 1).orEmpty()
            if (regex.isNotEmpty()) {
                result = when (result) {
                    is Texts -> Texts(result.list.map { applyRegex(it, regex, replacement) })
                    is Elems -> Texts(result.list.map { applyRegex(it.text(), regex, replacement) })
                }
            }
            i += 2
        }
        return result
    }

    /**
     * ## 替换链语义（按 999 全部用例反推）：
     * - 替换含 $N 引用时：取首次匹配并展开分组（如作者 ##作者：(.*)<br />##$1、页码 ##_(\d+)/##$1）；
     * - 替换不含 $N 时：全局替换（如简介的空白转 <br />）；
     * - 落单的正则（无替换）：按删除处理（如字数 ##字数：）。
     * 正则写坏时原样返回，不炸掉整条规则。
     */
    private fun applyRegex(text: String, regex: String, replacement: String): String {
        if (regex.isEmpty()) return text
        return runCatching {
            val re = Regex(regex)
            if (Regex("\\$\\d").containsMatchIn(replacement)) {
                val m = re.find(text) ?: return ""
                Regex("\\$(\\d+)").replace(replacement) { mr ->
                    val idx = mr.groupValues[1].toIntOrNull() ?: 0
                    if (idx < m.groupValues.size) m.groupValues[idx] else ""
                }
            } else {
                re.replace(text) { replacement }
            }
        }.getOrDefault(text)
    }

    private fun evalChainBase(
        root: Element,
        pageUrl: String,
        part: String,
        jsEval: (script: String, input: Any?) -> Any?,
    ): ChainResult {
        if (part.isBlank()) return Texts(listOf(root.outerHtml()))
        var current: ChainResult = Elems(listOf(root))
        for (segRaw in part.split("@")) {
            val seg = segRaw.trim()
            if (seg.isEmpty()) continue
            current = when {
                seg == "text" -> when (current) {
                    is Elems -> Texts(current.list.map { it.text() })
                    is Texts -> current
                }
                seg == "html" -> when (current) {
                    is Elems -> Texts(current.list.map { it.html() })
                    is Texts -> current
                }
                // 属性段：href/src 做绝对地址补全；其它属性名直取。
                // 注意：带横杠的 class 选择器（如 .chapter-list）以 . 开头，不算属性，
                // 只有 data- 开头或不含选择器字符的带横杠词才算属性。
                seg == "href" || seg == "src" || seg == "title" || seg == "alt" ||
                    seg == "content" || seg == "value" || seg.startsWith("data-") ||
                    (seg.contains("-") && !seg.startsWith(".") && !seg.contains(".") &&
                        !seg.contains("#") && !seg.contains("[") && !seg.contains(" ") &&
                        !seg.contains(">")) -> when (current) {
                    is Elems -> Texts(
                        current.list.map {
                            val v = it.attr(seg).trim()
                            if (seg == "href" || seg == "src") absolutize(pageUrl, v) else v
                        },
                    )
                    is Texts -> current
                }
                seg.startsWith("js:") -> {
                    val script = seg.removePrefix("js:")
                    val input: Any? = when (current) {
                        is Texts -> if (current.list.size == 1) current.list[0] else current.list.toTypedArray()
                        is Elems -> current.list.map { it.outerHtml() }.toTypedArray()
                    }
                    val out = jsEval(script, input)
                    when (out) {
                        null -> Texts(emptyList())
                        else -> {
                            val list = toJsStringList(out)
                            Texts(list)
                        }
                    }
                }
                seg.all { it.isDigit() } -> {
                    val idx = seg.toIntOrNull() ?: 0
                    when (current) {
                        is Elems -> Elems(listOfNotNull(current.list.getOrNull(idx)))
                        is Texts -> Texts(listOfNotNull(current.list.getOrNull(idx)))
                    }
                }
                seg.matches(Regex("^(.*)!(\\d+)$")) -> {
                    val m = Regex("^(.*)!(\\d+)$").find(seg)!!
                    val drop = m.groupValues[2].toIntOrNull() ?: 0
                    val selected = selectSegment(current, m.groupValues[1])
                    when (selected) {
                        is Elems -> Elems(selected.list.filterIndexed { i, _ -> i != drop })
                        is Texts -> Texts(selected.list.filterIndexed { i, _ -> i != drop })
                    }
                }
                seg.matches(Regex("^(.*)\\.(\\d+)$")) -> {
                    val m = Regex("^(.*)\\.(\\d+)$").find(seg)!!
                    val idx = m.groupValues[2].toIntOrNull() ?: 0
                    val selected = selectSegment(current, m.groupValues[1])
                    when (selected) {
                        is Elems -> Elems(listOfNotNull(selected.list.getOrNull(idx)))
                        is Texts -> Texts(listOfNotNull(selected.list.getOrNull(idx)))
                    }
                }
                else -> selectSegment(current, seg, pageUrl)
            }
        }
        return current
    }

    /** 选择器段：tag.xxx 按标签名，其余按 CSS；对文本列的选择器段直接判空。 */
    private fun selectSegment(current: ChainResult, seg: String, pageUrl: String = ""): ChainResult {
        if (current !is Elems) return Texts(emptyList())
        val css = if (seg.startsWith("tag.")) seg.removePrefix("tag.") else seg
        if (css.isBlank()) return current
        val found = current.list.flatMap { el ->
            runCatching { el.select(css).toList() }.getOrDefault(emptyList())
        }
        return Elems(found)
    }

    /** 绝对地址补全（href/src 用；属性段在 evalChainBase 里直接处理，见下）。 */
    private fun absolutize(pageUrl: String, href: String): String {
        if (href.isBlank()) return ""
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        return runCatching {
            java.net.URL(java.net.URL(pageUrl), href).toString()
        }.getOrDefault(href)
    }

    /**
     * 文本规则求值（字段级）：支持 <js> 前缀 + 余下链/JSON 路径。
     * root 为元素或整页 Document；pageUrl 用于补全相对地址。
     */
    fun evalTextRule(
        root: Element,
        pageUrl: String,
        rule: String,
        ctx: JsCtx,
        state: JsState,
    ): String {
        if (rule.isBlank()) return ""
        val (js, rest) = splitJsPrefix(substituteTemplates(rule, ctx))
        if (js != null) {
            val out = evalJs(
                js,
                ctx,
                state,
                result = root.outerHtml(),
                src = root.outerHtml(),
            )
            val s = toJsString(out)
            if (rest.isBlank()) return s
            if (rest.trimStart().startsWith("$.")) return evalJsonPath(s, rest.trim())
            val doc = Jsoup.parse(s, pageUrl)
            return evalChain(doc, pageUrl, rest) { script, input ->
                evalJs(script, ctx, state, result = input, src = s)
            }.firstText()
        }
        return evalChain(root, pageUrl, rule) { script, input ->
            evalJs(script, ctx, state, result = input, src = "")
        }.firstText()
    }

    /** 列表规则求值（bookList/chapterList）：<js> 前缀先跑，余下按 CSS 链取元素列。 */
    fun evalListRule(
        html: String,
        pageUrl: String,
        rule: String,
        ctx: JsCtx,
        state: JsState,
    ): List<Element> {
        var h = html
        var r = substituteTemplates(rule.trim(), ctx)
        val (js, rest) = splitJsPrefix(r)
        if (js != null) {
            val out = evalJs(js, ctx, state, result = h, src = h)
            h = toJsString(out)
            r = rest
        }
        if (r.isBlank()) return emptyList()
        val doc = Jsoup.parse(h, pageUrl)
        return evalChain(doc, pageUrl, r) { script, input ->
            evalJs(script, ctx, state, result = input, src = h)
        }.elems()
    }

    /** JSON 路径（$.a.b[0].c 形式，供封面那种 JS 后接 JSON 路径的规则）。 */
    fun evalJsonPath(json: String, path: String): String {
        if (json.isBlank() || path.isBlank()) return ""
        return runCatching {
            var cur: Any? = JSONObject(json)
            val clean = path.trim().removePrefix("$.").removePrefix("$")
            for (token in clean.split(".")) {
                if (token.isBlank()) continue
                val key = token.substringBefore("[")
                val indexes = Regex("\\[(\\d+)\\]").findAll(token).map { it.groupValues[1].toInt() }.toList()
                if (key.isNotEmpty()) {
                    cur = when (cur) {
                        is JSONObject -> if (cur.has(key)) cur.get(key) else return ""
                        is Map<*, *> -> cur[key] ?: return ""
                        else -> return ""
                    }
                }
                for (idx in indexes) {
                    cur = when (cur) {
                        is org.json.JSONArray -> if (idx < cur.length()) cur.get(idx) else return ""
                        is List<*> -> cur.getOrNull(idx) ?: return ""
                        else -> return ""
                    }
                }
            }
            when (cur) {
                null, JSONObject.NULL -> ""
                is String -> cur
                else -> cur.toString()
            }
        }.getOrDefault("")
    }

    /**
     * 登录校验：跑 loginCheckJs，返回最终页面。
     * 自动验证成功或源变量变化时，用 refetch 重取一次（只重取一次，不递归），
     * 对应原版注释“自动验证成功后重新获取 / 搜索时重定向新网址需要重新获取搜索内容”。
     */
    suspend fun runLoginCheck(
        source: BookSource,
        page: BookRuleEngine.PageResult,
        ctx: JsCtx,
        state: JsState,
        refetch: (suspend () -> BookRuleEngine.PageResult?)?,
    ): BookRuleEngine.PageResult = withContext(Dispatchers.IO) {
        // v0.217.0（F50）：Rhino 求值与 java.ajax 都是同步阻塞，调用约束原来
        // 只写在注释里（“调用方必须在 IO 线程”），UI 触发的新调用点一旦
        // 漏包 IO 就直接在主线程跑校验；入口自己包进 IO，已包过的调用点
        // 嵌套进 IO 仍是 IO，无额外代价，约束从此由代码保证。
        val js = substituteTemplates(source.loginCheckJs, ctx)
        if (js.isBlank()) return@withContext page
        state.lastPage = page
        val out = evalJs(
            js,
            ctx.copy(baseUrl = page.url),
            state,
            result = StrResponse(page.body, page.url),
            src = page.body,
        )
        var resultPage = page
        when (out) {
            is StrResponse -> resultPage = BookRuleEngine.PageResult(out.body(), out.url())
            is NativeJavaObject -> {
                (out.unwrap() as? StrResponse)?.let {
                    resultPage = BookRuleEngine.PageResult(it.body(), it.url())
                }
            }
            is String -> if (out.isNotBlank()) resultPage = page.copy(body = out)
            else -> {
                val s = toJsString(out)
                if (s.isNotBlank() && s != page.body) resultPage = page.copy(body = s)
            }
        }
        val needRefetch = state.variableChanged ||
            (resultPage.body != page.body && resultPage.body.length < 200)
        if (needRefetch && refetch != null) {
            state.variableChanged = false
            return@withContext refetch() ?: resultPage
        }
        resultPage
    }

    /**
     * 分页 URL 求值，返回 URL 列：
     * - “第一行\n<js>...</js>”形（目录分页）：先行求值，再把结果数组交 JS；
     * - 纯 <js>：直接求值取数组；
     * - 其余：按链求值取文本列。
     */
    fun evalNextUrls(
        html: String,
        pageUrl: String,
        rule: String,
        ctx: JsCtx,
        state: JsState,
    ): List<String> {
        val t = substituteTemplates(rule.trim(), ctx)
        if (t.isEmpty()) return emptyList()
        val jsStart = t.indexOf("<js>")
        if (jsStart > 0) {
            val first = t.substring(0, jsStart).trim()
            val jsEnd = t.indexOf("</js>", jsStart)
            val js = if (jsEnd < 0) t.substring(jsStart + 4) else t.substring(jsStart + 4, jsEnd)
            val doc = Jsoup.parse(html, pageUrl)
            val stage1 = evalChain(doc, pageUrl, first) { script, input ->
                evalJs(script, ctx, state, result = input, src = html)
            }.firstText()
            val out = evalJs(
                js,
                ctx,
                state,
                result = arrayOf(stage1),
                src = html,
            )
            return toJsStringList(out).filter { it.isNotBlank() }
        }
        val (js, rest) = splitJsPrefix(t)
        if (js != null) {
            if (rest.isBlank()) {
                val out = evalJs(js, ctx, state, result = html, src = html)
                return toJsStringList(out).filter { it.isNotBlank() }
            }
            val s = toJsString(evalJs(js, ctx, state, result = html, src = html))
            val doc = Jsoup.parse(s, pageUrl)
            return evalChain(doc, pageUrl, rest) { script, input ->
                evalJs(script, ctx, state, result = input, src = s)
            }.allTexts().filter { it.isNotBlank() }
        }
        val doc = Jsoup.parse(html, pageUrl)
        return evalChain(doc, pageUrl, t) { script, input ->
            evalJs(script, ctx, state, result = input, src = html)
        }.allTexts().filter { it.isNotBlank() }
    }

    /** 该源是否用到 JS 能力（任一规则含 <js>/@js:，或带登录校验）。 */
    fun isJsSource(source: BookSource): Boolean {
        if (source.loginCheckJs.isNotBlank()) return true
        if (source.searchUrl.trimStart().startsWith("<js>")) return true
        val rules = listOf(
            source.ruleSearchList,
            source.ruleSearchName,
            source.ruleSearchAuthor,
            source.ruleSearchBookUrl,
            source.ruleSearchCover,
            source.ruleTocList,
            source.ruleTocTitle,
            source.ruleTocUrl,
            source.ruleTocNext,
            source.ruleContent,
            source.ruleContentReplace,
            source.ruleContentNext,
        )
        return rules.any { it.contains("<js>") || it.contains("@js:") }
    }
}
