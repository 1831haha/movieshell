package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DEFAULT_UA
import com.movieshell.data.source.PyApi
import com.movieshell.data.source.V8DrpyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * JS 用全新 V8DrpyEngine 实例加载该文件；PY 用 PyApi.home(绝对路径)；
 * 网页源/订阅走 HTTP GET。结果：正常（≤3s）/ 缓慢（3~8s）/ 失败（记 LogBus）。
 *
 * v0.86.0：withTimeoutOrNull 是协作式取消——probePy 卡在 Chaquopy JNI 时超时永远
 * 不返回，一个卡住 awaitAll 整体卡死。改为有界线程池 + Future.get(8s) 硬超时，
 * 超时 cancel(true) 后记失败；泄漏的线程是 daemon，随进程退出回收。并发用 Semaphore(6) 限流。
 *
 * v0.113.0：probeJs 的 V8DrpyEngine 持独立 V8 isolate（WebView 独立进程），
 * engine.release() 即时终止 isolate；即便规则死循环也不会卡宿主线程（isolate 内部
 * 卡死由 15s 硬截止兜底终止）。cancel(true) 之后的 release() 真正生效。
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
    fun probeJs(context: Context, file: File): Result {
        val engine = V8DrpyEngine(context.applicationContext)
        return try {
            guarded(label = file.name, onTimeout = { runCatching { engine.release() } }) {
                engine.loadRule(file.readText())
                runBlocking { engine.home() }
            }
        } finally {
            // v0.113.0：探活用完即释放（release 即时终止 isolate，任意线程可调）；
            // release 幂等，超时回调里调过也安全，重复调无害
            runCatching { engine.release() }
        }
    }

    fun probePy(context: Context, file: File): Result = guarded(label = file.name) {
        runBlocking { PyApi().home(file.absolutePath) }
    }

    fun probeHttp(url: String): Result = guarded(label = url) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA)
            .build()
        CmsApi.defaultClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            // 只读前 64KB 验连通性，不下全包
            resp.body?.byteStream()?.use { it.read(ByteArray(65536)) }
                ?: throw IllegalStateException("空响应")
        }
    }

    /**
     * 限流 + 硬超时。
     * v0.111.0 A5：onTimeout 在 TimeoutException 分支最先执行，让调用方先直接
     * cancel 引擎在途的网络 call（release 幂等），中断不到引擎线程的
     * future.cancel(true) 之后再调。
     */
    // v0.142.0：label 透传到超时日志——此前 130 个源批量检测时满屏只有
    // "探活超时"四个字、连哪个源超时都看不出，没法定位（2026-10-21 日志实测）。
    private fun guarded(label: String = "", onTimeout: () -> Unit = {}, block: () -> Any?): Result {
        semaphore.acquire()
        try {
            return hardTimeout(label, onTimeout, block)
        } finally {
            semaphore.release()
        }
    }

    private fun hardTimeout(label: String = "", onTimeout: () -> Unit = {}, block: () -> Any?): Result {
        val start = System.currentTimeMillis()
        val future = pool.submit(Callable<Any?> { block() })
        return try {
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val ms = System.currentTimeMillis() - start
            if (ms > SLOW_MS) Result.Slow(ms) else Result.Ok(ms)
        } catch (e: TimeoutException) {
            onTimeout()
            future.cancel(true)
            LogBus.e("Probe", "探活超时" + if (label.isNotBlank()) "：${label.take(80)}" else "")
            Result.Fail("8秒超时无响应")
        } catch (e: Exception) {
            // ExecutionException 包一层，cause 才是真实原因
            val reason = (e.cause ?: e).message?.take(60) ?: "未知错误"
            LogBus.e("Probe", "探活失败：$reason")
            Result.Fail(reason)
        }
    }

    /** v0.90.0：检测任务——UI 与 Worker 共用同一套目标构建，不复制两份 */
    data class ProbeTask(
        val kind: String,
        val name: String,
        val label: String,
        val probe: () -> Result,
    )

    /**
     * v0.90.0：构建检测目标（与站源页"开始检测"同口径）：
     * 已启用的 JS/PY 规则、网页源、订阅接口站；PHP 本地规则无解释器记不支持。
     * 纯读盘/读 Store，不碰 UI 状态，Worker 内可直接调用。
     */
    fun buildTasks(context: Context): List<ProbeTask> {
        val tasks = mutableListOf<ProbeTask>()
        DrpySourceManager.listRules().forEach { r ->
            if (SourceStateStore.isRuleEnabled("js", r.name)) {
                val f = File(StoragePaths.jsDir(context), r.name)
                tasks += ProbeTask("js", r.name, r.name) { probeJs(context, f) }
            }
        }
        PySourceManager.listRules().forEach { r ->
            if (SourceStateStore.isRuleEnabled("py", r.name)) {
                val f = File(StoragePaths.pyDir(context), r.name)
                tasks += ProbeTask("py", r.name, r.name) { probePy(context, f) }
            }
        }
        StoragePaths.phpDir(context).listFiles { f -> f.isFile }.orEmpty()
            .sortedBy { it.name }
            .forEach { f -> tasks += ProbeTask("php", f.name, f.name) { Result.Unsupported } }
        WebSourceManager.listSites().forEach { s ->
            if (SourceStateStore.isRuleEnabled("web", s.fileName)) {
                tasks += ProbeTask("web", s.fileName, s.fileName) { probeHttp(s.url) }
            }
        }
        ApiSourceStore.load(context).filter { it.enabled }.forEach { e ->
            tasks += ProbeTask("sub", e.url, e.name) { probeHttp(e.url) }
        }
        return tasks
    }

    /**
     * v0.90.0：批量跑完，每个完成回调一次。
     * 并发由 guarded 内 Semaphore(6) 限流，这里只做 IO 扇出。
     */
    suspend fun probeAll(
        tasks: List<ProbeTask>,
        onResult: suspend (ProbeTask, Result) -> Unit,
    ) = coroutineScope {
        tasks.map { task ->
            async(Dispatchers.IO) {
                val res = task.probe()
                onResult(task, res)
            }
        }.awaitAll()
    }

}
