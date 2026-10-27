package com.github.catvod.crawler

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.JsonArray
import com.movieshell.data.repo.LogBus
import com.movieshell.data.source.CmsApi
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * v0.8.0：jar 里 spider 调用的宿主静态工具。
 *
 * dex 实测结论：getAddress/getPort/log/multiReq/webParse 均为 invoke-static
 * 直接调用，且无 get()/getInstance() 之类的中转引用——所以全部做成
 * @JvmStatic，签名与 dex 里的引用逐一对齐。
 */
object SpiderApi {
    private const val TAG = "SpiderApi"
    private var appContext: Context? = null
    private val client by lazy { CmsApi.defaultClient() }
    private val pool = Executors.newFixedThreadPool(4)

    /** SpiderEngine.init 时调用。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 本地代理地址：壳子不跑本地代理服务，返回空（依赖它的 spider 播放会优雅失败）。 */
    @JvmStatic
    fun getAddress(lan: Boolean): String = ""

    /** 本地代理端口：同上，返回空。 */
    @JvmStatic
    fun getPort(): String = ""

    @JvmStatic
    fun log(msg: String) {
        LogBus.i(TAG, msg)
    }

    /**
     * 并行请求。约定（CatVod 常见形态）：数组每项为
     * {"url":..., "method":"GET"|"POST", "headers":{...}, "body":...}，
     * 返回各响应体的 JSON 字符串数组。
     */
    @JvmStatic
    fun multiReq(array: JsonArray): String {
        // v0.247.0（F21 复核）：超时取消必须覆盖“取消先发生、Call 后创建”的窗口——
        // 只记 Call 不够：超时线程读到 null、随后工作线程才创建并启动 Call，请求
        // 就在超时后仍跑起来。做法是取消标志 + 原子引用双保险：超时方先置标志
        // 再取 Call 取消；工作方先登记 Call 再查标志取消。两边无论谁先谁后，
        // Call 一定被取消（已完成的 cancel 是空操作）。原子数组保证跨线程可见性。
        // 注意：标准 Java 原子包没有 AtomicBooleanArray，用 AtomicIntegerArray 以 0/1 当标志。
        val calls = AtomicReferenceArray<Call>(array.size())
        val cancelled = AtomicIntegerArray(array.size())
        val results = array.mapIndexed { idx, el ->
            pool.submit<String> {
                runCatching {
                    val o = el.asJsonObject
                    val url = o.get("url")?.asString.orEmpty()
                    if (url.isBlank()) return@runCatching ""
                    val builder = Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0")
                    o.getAsJsonObject("headers")?.entrySet()?.forEach { (k, v) ->
                        runCatching { builder.header(k, v.asString) }
                    }
                    val body = o.get("body")?.asString.orEmpty()
                    if (o.get("method")?.asString.equals("POST", true)) {
                        builder.post(body.toRequestBody("application/json".toMediaType()))
                    }
                    val call = client.newCall(builder.build())
                    calls.set(idx, call)
                    if (cancelled.get(idx) == 1) call.cancel()
                    call.execute().use { resp ->
                        resp.body?.string().orEmpty()
                    }
                }.getOrDefault("")
            }
        }
        val out = JsonArray()
        // v0.247.0（F21）：原来逐个 get(20 秒)，N 个请求最长等 N×20 秒；
        // 改按一份 20 秒总预算等全部，预算耗尽或超时的 future 直接取消、空串占位。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        results.forEachIndexed { idx, f ->
            val remain = deadline - System.nanoTime()
            val value = if (remain <= 0) {
                cancelled.set(idx, 1)
                calls.get(idx)?.cancel()
                f.cancel(true)
                ""
            } else {
                runCatching { f.get(remain, TimeUnit.NANOSECONDS) }.getOrElse {
                    cancelled.set(idx, 1)
                    calls.get(idx)?.cancel()
                    f.cancel(true)
                    ""
                }
            }
            out.add(value)
        }
        return out.toString()
    }

    /**
     * WebView 解析：在主线程建 WebView 加载 url，页面完成后执行 script 取结果，
     * 30 秒超时。禁止在主线程调用（会死锁），此时直接返回空。
     * v0.247.0（F46）：调用方约定走后台线程——jar 内 spider 经 dex 直接调用，
     * 应用内无其他调用点，主线程守卫是最后防线。
     */
    @JvmStatic
    fun webParse(url: String, script: String): String {
        if (url.isBlank()) return ""
        if (Looper.myLooper() == Looper.getMainLooper()) {
            LogBus.w(TAG, "webParse 禁止在主线程调用")
            return ""
        }
        val ctx = appContext ?: return ""
        val latch = CountDownLatch(1)
        var result = ""
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            var wv: WebView? = null
            var finished = false
            var timeoutTask: Runnable? = null
            // v0.247.0（F22）：成功/失败/超时任一先到即收尾——清掉 30 秒兜底任务
            // （原来成功后延迟任务仍在主线程队列里捏 WebView 引用 30 秒）、
            // 销毁 WebView、放行等待线程；重复收尾无害（latch 归零后 countDown 是空操作）。
            // 收尾只在主线程回调与超时任务里执行，finished 无跨线程竞争。
            fun finish() {
                if (finished) return
                finished = true
                timeoutTask?.let { mainHandler.removeCallbacks(it) }
                runCatching { wv?.destroy() }
                latch.countDown()
            }
            try {
                wv = WebView(ctx)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        runCatching {
                            view.evaluateJavascript(script) {
                                // v0.246.0：evaluateJavascript 回调是 JSON 编码值，
                                // 原来只 trim('"') 去首尾引号，\n \" \\ 等转义原样残留、
                                // 含引号/换行的返回值失真。改按 JSON 字符串解码，
                                // 非字符串（数字/布尔/null/对象）原样返回，解码失败回退旧逻辑。
                                result = decodeJsValue(it.orEmpty())
                                finish()
                            }
                        }.onFailure { finish() }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        errorCode: Int,
                        description: String,
                        failingUrl: String,
                    ) {
                        finish()
                    }
                }
                timeoutTask = Runnable { finish() }
                mainHandler.postDelayed(timeoutTask, 30_000)
                wv.loadUrl(url)
            } catch (e: Exception) {
                // v0.247.0（F31）：原来只记 message，无堆栈定位不了。
                LogBus.e(TAG, "webParse 建 WebView 失败：${e.message}", e)
                finish()
            }
        }
        // v0.247.0（F46）：调用方约定走后台线程——jar 内 spider 经 dex 直接调用，
        // 应用内无其他调用点，主线程守卫是最后防线；等待 32 秒 = 30 秒超时兜底 +
        // 2 秒主线程调度余量，不是 30 秒的笔误。
        latch.await(32, TimeUnit.SECONDS)
        return result
    }

    /**
     * v0.246.0：解码 evaluateJavascript 回调值。回调是 JSON 编码：
     * 字符串带引号 + 转义（\n \" \\ \uXXXX），数字/布尔/null/对象无引号。
     * 字符串走 JSONObject 解码转义；其他原样；解码失败回退 trim 引号。
     */
    private fun decodeJsValue(raw: String): String {
        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return runCatching {
                org.json.JSONObject("{\"v\":$raw}").getString("v")
            }.getOrDefault(raw.trim('"'))
        }
        return raw
    }
}
