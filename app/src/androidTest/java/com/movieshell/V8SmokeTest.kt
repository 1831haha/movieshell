package com.movieshell

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.movieshell.data.source.JsRuntime
import com.movieshell.data.source.V8DrpyEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.113.0：V8 drpy 引擎真机冒烟测试（instrumentation 直跑引擎，绕开 UI）。
 *
 * 验证完整 async 链路：JsRuntime sandbox 连接 → V8 isolate bootstrap（host.js +
 * shim.js + 规则）→ JS await request → MessagePort RPC → 宿主 OkHttp → Promise
 * resolve → home() 返回真实数据。
 *
 * 设备能力不足（WebView 过旧）时打印明确原因并跳过断言（不算失败）。
 */
@RunWith(AndroidJUnit4::class)
class V8SmokeTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun drpyHomeSmoke() = runBlocking {
        JsRuntime.init(ctx)
        log("jsSourcesSupported = ${JsRuntime.jsSourcesSupported}")

        val engine = V8DrpyEngine(ctx)
        try {
            val ruleJs = ctx.assets.open("drpy/sp002.js").bufferedReader().readText()
            engine.loadRule(ruleJs)
            val t0 = System.currentTimeMillis()
            val vods = engine.home()
            val cost = System.currentTimeMillis() - t0
            log("home() 返回 ${vods.size} 条，耗时 ${cost}ms")
            vods.take(3).forEach { log("sample: ${it.vodName} | ${it.vodRemarks}") }

            if (!JsRuntime.jsSourcesSupported) {
                log("设备不支持 V8 sandbox，跳过断言（不算失败）")
                return@runBlocking
            }
            assertTrue("home() 应返回非空列表，实际 ${vods.size} 条", vods.isNotEmpty())

            val cats = engine.categories()
            log("categories() 返回 ${cats.size} 个：${cats.take(5).joinToString("/") { it.typeName }}")
            assertTrue("categories() 应非空", cats.isNotEmpty())
        } finally {
            engine.release()
        }
    }

    @Test
    fun drpySearchSmoke() = runBlocking {
        JsRuntime.init(ctx)
        val engine = V8DrpyEngine(ctx)
        try {
            val ruleJs = ctx.assets.open("drpy/sp002.js").bufferedReader().readText()
            engine.loadRule(ruleJs)
            val vods = engine.search("哪吒")
            log("search('哪吒') 返回 ${vods.size} 条")
            if (JsRuntime.jsSourcesSupported) {
                // 搜索依赖远端 MeiliSearch，网络波动允许空结果：只验证引擎不抛、可重入
                val again = engine.search("哪吒")
                log("search 可重入，第二次返回 ${again.size} 条")
            }
        } finally {
            engine.release()
        }
    }

    private fun log(msg: String) {
        android.util.Log.i("V8Smoke", msg)
    }
}
