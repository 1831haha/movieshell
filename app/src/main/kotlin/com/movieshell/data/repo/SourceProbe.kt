package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.source.PyApi
import com.movieshell.data.source.QuickJsDrpyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.File

/**
 * 站源批量探活（v0.24.0）。
 *
 * 对每个已启用源调一次 home()（8 秒超时），不碰全局激活状态：
 * JS 用全新 QuickJsDrpyEngine 实例加载该文件；PY 用 PyApi.home(绝对路径)；
 * 网页源/订阅走 HTTP GET。结果：正常（≤3s）/ 缓慢（3~8s）/ 失败（记 LogBus）。
 */
object SourceProbe {

    const val TIMEOUT_MS = 8_000L
    const val SLOW_MS = 3_000L

    sealed interface Result {
        data class Ok(val ms: Long) : Result
        data class Slow(val ms: Long) : Result
        data class Fail(val reason: String) : Result
        /** PHP 本地规则：手机端无解释器，不探 */
        object Unsupported : Result
    }

    suspend fun probeJs(context: Context, file: File): Result = withContext(Dispatchers.IO) {
        timed {
            val engine = QuickJsDrpyEngine(context.applicationContext)
            engine.loadRule(file.readText())
            engine.home()
        }
    }

    suspend fun probePy(context: Context, file: File): Result = withContext(Dispatchers.IO) {
        timed { PyApi().home(file.absolutePath) }
    }

    suspend fun probeHttp(url: String): Result = withContext(Dispatchers.IO) {
        timed {
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
    }

    private suspend fun timed(block: suspend () -> Any?): Result {
        val start = System.currentTimeMillis()
        return try {
            val r = withTimeoutOrNull(TIMEOUT_MS) { block() }
                ?: return Result.Fail("8 秒超时无响应").also {
                    LogBus.e("Probe", "探活超时")
                }
            val ms = System.currentTimeMillis() - start
            if (ms > SLOW_MS) Result.Slow(ms) else Result.Ok(ms)
        } catch (e: Exception) {
            val reason = e.message?.take(60) ?: "未知错误"
            LogBus.e("Probe", "探活失败：$reason")
            Result.Fail(reason)
        }
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
}
