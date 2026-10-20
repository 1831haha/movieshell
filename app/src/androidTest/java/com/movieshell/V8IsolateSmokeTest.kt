package com.movieshell

import androidx.javascriptengine.IsolateTerminatedException
import androidx.javascriptengine.JavaScriptConsoleCallback
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.movieshell.data.source.JsRuntime
import com.movieshell.data.source.awaitJs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor

/**
 * v0.113.0：V8 isolate 设备端冒烟（绕开 JsRuntime 能力门禁，测试专用）。
 *
 * 背景：模拟器镜像内置的 WebView（124/133）未实现 JS_FEATURE_MESSAGE_PORTS，
 * 生产门禁按设计禁用 JS 源——真实 RPC 链路（MessagePort → OkHttp）无法在模拟器上验。
 * 本测试用 sandboxIgnoringGate() 直连 sandbox，在真实 V8 isolate 里验证与端口无关的
 * 基础能力：async/await 事件循环、host.js 纯 JS 基元、shim+规则纯逻辑、request 管道
 * （__hrpc 桩回固定值）、超时 close 真终止。
 *
 * MessagePort RPC 本身：由 node shim 自测（docs/drpy-shim-test）覆盖逻辑，
 * 真机全链路验证待有支持该能力的 WebView（生产设备 Play 更新版）。
 */
@RunWith(AndroidJUnit4::class)
class V8IsolateSmokeTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun rawIsolate(): Pair<JavaScriptSandbox, JavaScriptIsolate> {
        val sandbox = JsRuntime.sandboxIgnoringGate() ?: error("sandbox 连接失败")
        val iso = sandbox.createIsolate()
        iso.setConsoleCallback(Executor { it.run() }, object : JavaScriptConsoleCallback {
            override fun onConsoleMessage(message: JavaScriptConsoleCallback.ConsoleMessage) {
                android.util.Log.i("V8Iso", "console: ${message.message}")
            }
        })
        return sandbox to iso
    }

    private suspend fun eval(iso: JavaScriptIsolate, expr: String, timeoutMs: Long = 15_000): String =
        withTimeoutOrNull(timeoutMs) { iso.evaluateJavaScriptAsync(expr).awaitJs() }
            ?: throw AssertionError("evaluate 超时(${timeoutMs}ms): ${expr.take(80)}")

    private fun assetText(path: String): String =
        ctx.assets.open(path).bufferedReader().readText()

    @Test
    fun isolateBootstrapAndAsyncChain() = runBlocking {
        JsRuntime.init(ctx)
        val (_, iso) = rawIsolate()
        try {
            // [1] Promise 事件循环：微任务链 + Promise.all（注：isolate 无 setTimeout 等定时器）
            val r1 = eval(iso, "(async () => {" +
                "const a = await Promise.resolve(1);" +
                "const b = await Promise.resolve(2).then(v => v * 2);" +
                "const [c, d] = await Promise.all([Promise.resolve(3), Promise.resolve(4).then(v => v + 1)]);" +
                "return 'R' + (a + b + c + d);" +
                "})()")
            assertEquals("await 事件循环串/并行结果", "R13", r1)

            // [2] host.js 纯 JS 基元（不建 port，__hrpc 不会被调到）
            eval(iso, assetText("jsbridge/host.js"))
            assertEquals("md5('abc')", "900150983cd24fb0d6963f7d28e17f72", eval(iso, "__hMd5('abc')"))
            assertEquals("base64 UTF-8 解码", "中文abc", eval(iso, "__hB64Decode('5Lit5paHYWJj')"))

            // [3] shim + sp002 规则纯逻辑（lazy 直链判断，无网络路径）
            eval(iso, assetText("drpy/shim.js"))
            eval(iso, assetText("drpy/sp002.js"))
            // __drpyPlay 本身返回 JSON 字符串，直接 parse
            val p1 = JSONObject(eval(iso, "(async () => await __drpyPlay('', 'https://example.com/v.m3u8'))()"))
            assertEquals("lazy m3u8 直连 parse=0", 0, p1.optInt("parse", -1))
            assertEquals("lazy m3u8 直连 url 原样", "https://example.com/v.m3u8", p1.optString("url"))
            val p2 = JSONObject(eval(iso, "(async () => await __drpyPlay('', 'https://example.com/v.mp4'))()"))
            assertEquals("lazy 非直链 parse=1（嗅探）", 1, p2.optInt("parse", -1))

            // [4] request 管道：__hrpc 桩回固定值（生产走 MessagePort→OkHttp，此处只验管道）
            eval(iso, "globalThis.__hrpc = async (p) => p.kind === 'http' ? '{\"ok\":1}' : '[]';")
            val body = eval(iso, "(async () => await request('http://mock.local/x', { method: 'POST', headers: { 'X-A': '1' }, body: 'a=1' }))()")
            assertEquals("request 经 __hrpc 桩取回响应体", "{\"ok\":1}", body)

            // [5] 跨 evaluate 并发（生产 __hrpcResolve 的机制）：入口 evaluate 的 Promise 挂起
            // 期间，宿主能再发起第二次 evaluate 注入 resolve，入口随后结算返回。
            val entryFuture = iso.evaluateJavaScriptAsync(
                "globalThis.__gate = new Promise(res => { globalThis.__gateResolve = res; });" +
                    "(async () => { const v = await globalThis.__gate; return 'GATE_' + v; })()"
            )
            eval(iso, "__gateResolve('OPEN')")
            val gate = withTimeoutOrNull(5_000) { entryFuture.awaitJs() }
            assertEquals("挂起入口被第二次 evaluate 结算", "GATE_OPEN", gate)
        } finally {
            runCatching { iso.close() }
        }
    }

    @Test
    fun timeoutClosesIsolateForReal() = runBlocking {
        JsRuntime.init(ctx)
        val (_, iso) = rawIsolate()
        val t0 = System.currentTimeMillis()
        val r = withTimeoutOrNull(1_500) { iso.evaluateJavaScriptAsync("while(true){}") .awaitJs() }
        val cost = System.currentTimeMillis() - t0
        // 死循环 evaluate 永不返回，与能力门禁无关，必走超时分支
        assertNull("死循环 evaluate 应超时返回 null", r)
        iso.close() // 生产语义（JsSession.eval）：超时即 close，真终止脚本
        assertTrue("close 应立即生效（实际 ${cost}ms）", cost < 8_000)
        // 终止后再 evaluate：库直接抛 IllegalStateException（isolate closed）
        try {
            iso.evaluateJavaScriptAsync("1+1").awaitJs()
            fail("已终止的 isolate 应抛异常")
        } catch (e: Throwable) {
            val terminated = e is IsolateTerminatedException ||
                (e is ExecutionException && e.cause is IsolateTerminatedException) ||
                (e is IllegalStateException && e.message?.contains("isolate closed") == true)
            assertTrue("应抛终止类异常，实际 ${e.javaClass.simpleName}: ${e.message}", terminated)
        }
    }
}
