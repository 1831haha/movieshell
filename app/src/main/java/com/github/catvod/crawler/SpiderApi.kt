package com.github.catvod.crawler

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.JsonArray
import com.movieshell.data.repo.LogBus
import com.movieshell.data.source.CmsApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        val results = array.map { el ->
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
                    client.newCall(builder.build()).execute().use { resp ->
                        resp.body?.string().orEmpty()
                    }
                }.getOrDefault("")
            }
        }
        val out = JsonArray()
        results.forEach { out.add(runCatching { it.get(20, TimeUnit.SECONDS) }.getOrDefault("")) }
        return out.toString()
    }

    /**
     * WebView 解析：在主线程建 WebView 加载 url，页面完成后执行 script 取结果，
     * 30 秒超时。禁止在主线程调用（会死锁），此时直接返回空。
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
        Handler(Looper.getMainLooper()).post {
            var wv: WebView? = null
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
                                runCatching { view.destroy() }
                                latch.countDown()
                            }
                        }.onFailure { latch.countDown() }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        errorCode: Int,
                        description: String,
                        failingUrl: String,
                    ) {
                        latch.countDown()
                    }
                }
                wv.loadUrl(url)
            } catch (e: Exception) {
                LogBus.e(TAG, "webParse 建 WebView 失败：${e.message}")
                latch.countDown()
            } finally {
                // 超时兜底：30 秒后无论如何放行并销毁
                Handler(Looper.getMainLooper()).postDelayed({
                    if (latch.count > 0) {
                        latch.countDown()
                        runCatching { wv?.destroy() }
                    }
                }, 30_000)
            }
        }
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
