package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.PyApi
import com.movieshell.data.source.QuickJsDrpyEngine
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 站源批量探活（v0.24.0；v0.86.0 硬超时重写）。
 *
 * 对每个已启用源调一次 home()，不碰全局激活状态：
 * JS 用全新 QuickJsDrpyEngine 实例加载该文件；PY 用 PyApi.home(绝对路径)；
 * 网页源/订阅走 HTTP GET。结果：正常（≤3s）/ 缓慢（3~8s）/ 失败（记 LogBus）。
 *
 * v0.86.0：withTimeoutOrNull 是协作式取消——probePy 卡在 Chaquopy JNI、
 * probeJs 卡在 QuickJS native 时超时永远不返回，一个卡住 awaitAll 整体卡死。
 * 改为有界线程池 + Future.get(8s) 硬超时，超时 cancel(true) 后记失败；
 * 泄漏的线程是 daemon，随进程退出回收。并发用 Semaphore(6) 限流。
 */
object SourceProbe {

    const val TIMEOUT_MS = 8_000L
    const val SLOW_MS = 3_000L
    /** v0.86.0：探活并发上限（Python 解释器 GIL 下别一起上） */
    private const val MAX_CONCURRENCY = 6

    sealed interface Result {
        data class Ok(val ms: Long) : Result
        data class Slow(val ms: Long) : Result
        data class Fail(val reason: String) : Result
        /** PHP 本地规则：手机端无解释器，不探 */
        object Unsupported : Result
    }

    /** v0.86.0：daemon 线程池，卡死的探活线程不阻止进程退出 */
    private val pool = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "source-probe").apply { isDaemon = true }
    }
    private val semaphore = Semaphore(MAX_CONCURRENCY)

    /** 注意：阻塞式调用方须包在 withContext(Dispatchers.IO) 里 */
    fun probeJs(context: Context, file: File): Result = guarded {
        val engine = QuickJsDrpyEngine(context.applicationContext)
        engine.loadRule(file.readText())
        runBlocking { engine.home() }
    }

    fun probePy(context: Context, file: File): Result = guarded {
        runBlocking { PyApi().home(file.absolutePath) }
    }

    fun probeHttp(url: String): Result = guarded {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .build()
        CmsApi.defaultClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            // 只读前 64KB 验连通性，不下全包
            resp.body?.byteStream()?.use { it.read(ByteArray(65536)) }
                ?: throw IllegalStateException("空响应")
        }
    }

    /** 限流 + 硬超时 */
    private fun guarded(block: () -> Any?): Result {
        semaphore.acquire()
        try {
            return hardTimeout(block)
        } finally {
            semaphore.release()
        }
    }

    private fun hardTimeout(block: () -> Any?): Result {
        val start = System.currentTimeMillis()
        val future = pool.submit(Callable<Any?> { block() })
        return try {
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val ms = System.currentTimeMillis() - start
            if (ms > SLOW_MS) Result.Slow(ms) else Result.Ok(ms)
        } catch (e: TimeoutException) {
            future.cancel(true)
            LogBus.e("Probe", "探活超时")
            Result.Fail("8秒超时无响应")
        } catch (e: Exception) {
            // ExecutionException 包一层，cause 才是真实原因
            val reason = (e.cause ?: e).message?.take(60) ?: "未知错误"
            LogBus.e("Probe", "探活失败：$reason")
            Result.Fail(reason)
        }
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
}
