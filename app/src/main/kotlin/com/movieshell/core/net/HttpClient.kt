package com.movieshell.core.net

import android.webkit.CookieManager
import com.movieshell.core.log.LogBus
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * v1.1.4 分层第 0 批：全仓共享 OkHttp 客户端（原 CmsApi 伴生对象）。
 *
 * 只收两个进程级共享单例（defaultClient / drpyClient）；以下两个有意例外不并入，
 * 各自私有 client 不动（见各处 v0.112.0 B1 注释）：
 * - DownloadQueueWorker：大文件下载不得加 callTimeout；
 * - LlmClient：15/60/15/90 超时 + retryOnConnectionFailure=false。
 *
 * 拦截器链与内存 CookieJar 随客户端一起迁入；LogBus 已先行移入 core/log，
 * core 层不反向依赖 data 层。
 */
object HttpClient {

    private const val TAG = "HttpClient"

    private const val TIMEOUT_S = 15L
    /**
     * v0.109.0：整 call 上限。审计过全部 defaultClient() 调用点：全是短 GET/HEAD
     *（CMS/探活/解析/嗅探 HEAD/音乐解析），无 SSE/流式/大文件下载（那些走各自私有
     * client）；最坏单链路 3 次重试 × 15s + 退避 1.5s ≈ 46.5s < 60s，保守取值。
     */
    private const val CALL_TIMEOUT_S = 60L
    /** v0.257.0：drpy 规则请求专用超时（见 drpyClient，单位秒）。 */
    private const val DRPY_CONNECT_TIMEOUT_S = 3L
    private const val DRPY_READ_TIMEOUT_S = 4L
    private const val DRPY_WRITE_TIMEOUT_S = 4L
    private const val DRPY_CALL_TIMEOUT_S = 5L
    /** v0.257.0：drpy 单请求整 call 上限（毫秒，供 V8DrpyEngine 算剩余预算时比较）。 */
    internal const val DRPY_CALL_TIMEOUT_MS = DRPY_CALL_TIMEOUT_S * 1000L
    /** 重试退避基数：500ms → 1s 指数退避（阻塞拦截器里小睡可接受）。 */
    private const val RETRY_BACKOFF_BASE_MS = 500L

    /**
     * v0.109.0：进程级单例。之前每次调用 build 一个新 OkHttpClient，
     * 连接池/Dispatcher 全丢，频繁建连 churn；现在全 App 共用一个。
     */
    fun defaultClient(): OkHttpClient = sharedClient

    private val sharedClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        buildDefaultClient()
    }

    private fun buildDefaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        // v0.99.0 P1-1：拦截器链——UA 统一补、IO 异常重试、内存 CookieJar、简易日志
        .cookieJar(memoryCookieJar)
        .addInterceptor(uaInterceptor)
        .addInterceptor(retryInterceptor)
        .addInterceptor(simpleLogInterceptor)
        .build()

    /**
     * v0.257.0：drpy 规则请求专用 client（隔离于 defaultClient，只供 V8DrpyEngine 用）。
     * 背景：规则 JS 单次执行上限 15 秒，而 defaultClient 单请求最坏约 46.5 秒
     * （15 秒乘 3 次重试加退避），一个挂起的请求就能拖垮整个会话
     * （13:12 首页全空实证）。本 client：连接 3 秒、读取 4 秒、写入 4 秒、
     * 整 call 5 秒、零重试、retryOnConnectionFailure=false；保留 UA 拦截器、
     * 内存 CookieJar 与简易日志（规则请求没 UA 会被部分站拒、会话 Cookie
     * 不能丢），只去掉重试拦截器。累计预算（单次执行 12 秒）由
     * V8DrpyEngine 侧控制，不在这里做。
     */
    fun drpyClient(): OkHttpClient = drpySharedClient

    private val drpySharedClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .connectTimeout(DRPY_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(DRPY_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(DRPY_WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(DRPY_CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .cookieJar(memoryCookieJar)
            .addInterceptor(uaInterceptor)
            .addInterceptor(simpleLogInterceptor)
            .build()
    }

    /**
     * 内存 CookieJar：host → cookies，需要登录态的源保持会话。
     * v0.110.0：synchronized + mutableMapOf → ConcurrentHashMap，读写无锁；
     * host 维度 LRU 上限 200（超限淘汰最久未用，见 SniffPlayHeaders 同款 200 上限），
     * 进程单例不再无限增长。
     * saveFromResponse 语义：仅当 Set-Cookie 非空时整 host 覆盖；
     * v0.112.0 修 B6：之前无条件覆盖，无 Set-Cookie 的响应会把该 host 已有 cookie 清空。
     * v0.110.1：匿名 object 内禁止声明类（'Class' is prohibited here），Entry 移到 companion 作用域。
     */
    private data class CookieEntry(val cookies: List<Cookie>, @Volatile var lastUsed: Long)

    private val memoryCookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, CookieEntry>()
        private val maxHosts = 200

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isNotEmpty()) {
                store[url.host] = CookieEntry(cookies, System.currentTimeMillis())
                if (store.size > maxHosts) evictOldest()
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val e = store[url.host] ?: return emptyList()
            e.lastUsed = System.currentTimeMillis()
            return e.cookies
        }

        /** 淘汰最久未用的 host（近似 LRU；只在超限写入时触发，扫描成本可忽略）。 */
        private fun evictOldest() {
            var oldestKey: String? = null
            var oldestTime = Long.MAX_VALUE
            for ((host, e) in store) {
                if (e.lastUsed < oldestTime) {
                    oldestTime = e.lastUsed
                    oldestKey = host
                }
            }
            oldestKey?.let { store.remove(it) }
        }

        /** v0.201.0：验证通道回写 Cookie（按名合并，不丢该 host 已有项）。 */
        fun put(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val old = store[host]?.cookies.orEmpty()
            val merged = old.filter { o -> cookies.none { it.name == o.name } } + cookies
            store[host] = CookieEntry(merged, System.currentTimeMillis())
        }

        /** v0.201.0：清掉某 host 的全部 Cookie（验证重进时不带旧 Cookie）。 */
        fun clear(host: String) {
            store.remove(host)
        }
    }

    /**
     * v0.201.0：把 WebView 拿到的 Cookie（"a=b; c=d" 形）写进共享 Jar，
     * 后续 OkHttp 请求自动带上。解析失败的段直接丢弃，不影响其它段。
     */
    fun putCookies(url: String, cookieHeader: String) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val cookies = cookieHeader.split(";").mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) null else Cookie.parse(httpUrl, trimmed)
        }
        if (cookies.isNotEmpty()) memoryCookieJar.put(httpUrl, cookies)
    }

    /** v0.201.0：清掉某地址 host 在 OkHttp Jar 与 WebView 里的 Cookie。 */
    fun clearCookies(url: String) {
        val host = url.toHttpUrlOrNull()?.host ?: return
        memoryCookieJar.clear(host)
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
    }

    /** UA 拦截器：没带 UA 的请求统一补浏览器 UA，不覆盖调用方显式设置的 */
    private val uaInterceptor = Interceptor { chain ->
        val req = chain.request()
        if (req.header("User-Agent") == null) {
            chain.proceed(req.newBuilder().header("User-Agent", DEFAULT_UA).build())
        } else {
            chain.proceed(req)
        }
    }

    /**
     * 重试拦截器：IO 异常（超时/断连）最多重试 2 次；HTTP 错误码不重试。
     * 注意：Interceptor 是阻塞上下文，这里不能用协程 delay，只做小睡退避。
     * v0.109.0：加指数退避（500ms→1s），之前无间隔连发 3 次容易把对面打成限流。
     * v0.110.0 评估结论：保留 Thread.sleep——Interceptor.proceed 是同步阻塞 API，
     * OkHttp 自身 retryOnConnectionFailure 同样阻塞 dispatcher 线程；默认 64 并发、
     * 单 call 最坏阻塞 0.5s+1s=1.5s（3 次尝试，远低于 callTimeout 60s），耗尽概率
     * 可忽略。改成 delay 要把整条调用链改成挂起结构，代价大于收益。
     * v0.111.0 B5：加两项检查——①已取消的 call 不再睡 0.5s/1s 后重试，
     * 直接抛；②非幂等方法（POST/PUT/PATCH/DELETE）一次失败就抛，不重试。
     */
    private val retryInterceptor = Interceptor { chain ->
        var lastError: IOException? = null
        repeat(3) { attempt ->
            if (chain.call().isCanceled()) throw IOException("Canceled")  // 已取消直接抛，不重试
            try {
                return@Interceptor chain.proceed(chain.request())
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e  // 取消表现为 IOException("Canceled")，不睡不重试
                val m = chain.request().method
                if (!m.equals("GET", ignoreCase = true) && !m.equals("HEAD", ignoreCase = true)) throw e  // 非幂等不重试
                lastError = e
                LogBus.w(TAG, "请求 IO 异常，重试 ${attempt + 1}/3：${chain.request().url}")
                if (attempt < 2) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_BASE_MS shl attempt)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        // 被中断就别再睡了，把原始异常抛出去结束重试
                        throw e
                    }
                }
            }
        }
        // v0.188.0 M30：重试计数落日志——此前每次重试都记了
        // “重试 N/3”，但 3 次耗尽时无收尾日志，排障时分不清是
        // “没重试”还是“重试耗尽”。耗尽时补一条。
        LogBus.w(TAG, "请求重试耗尽（3 次）：${chain.request().url}")
        throw lastError ?: IOException("unknown network error")
    }

    /** 简易日志拦截器（不引入 logging-interceptor 依赖）：方法 + URL + 状态码 */
    private val simpleLogInterceptor = Interceptor { chain ->
        val req = chain.request()
        val resp = chain.proceed(req)
        LogBus.d(TAG, "${req.method} ${req.url} → ${resp.code}")
        resp
    }
}
