package com.movieshell.player

import android.content.Context
import android.content.Intent
import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** v0.294.29：主进程侧——拉起 :magnet 进程并经回环 HTTP 控制，不在主进程加载 libtorrent。 */
object MagnetRemote {
    private const val TAG = "XiaYingMagnet"
    sealed class Result { data class Ok(val url: String) : Result(); data class Fail(val message: String) : Result() }

    fun portFile(ctx: Context) = File(ctx.filesDir, "magnet_ctl_port")

    private fun get(port: Int, path: String, timeoutMs: Int = 3000): String? = runCatching {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }.getOrNull()

    private fun currentPort(ctx: Context): Int? {
        val t = runCatching { portFile(ctx).readText().trim() }.getOrNull() ?: return null
        val p = t.toIntOrNull() ?: return null
        return p.takeIf { get(it, "/healthz", 1000) != null }
    }

    /** 拉起磁力进程，返回控制端口；失败返回中文原因 */
    private suspend fun ensure(ctx: Context): Any = withContext(Dispatchers.IO) {
        currentPort(ctx)?.let { return@withContext it }
        runCatching { portFile(ctx).delete() }
        try {
            ctx.startService(Intent(ctx, MagnetService::class.java))
        } catch (t: Throwable) {
            return@withContext "无法启动磁力引擎：${t.message}"
        }
        repeat(60) {
            delay(250)
            val t = runCatching { portFile(ctx).readText().trim() }.getOrNull()
            if (t != null) {
                if (t.startsWith("fail:")) return@withContext "磁力引擎启动失败：${t.removePrefix("fail:")}"
                t.toIntOrNull()?.let { p -> if (get(p, "/healthz", 1000) != null) return@withContext p }
            }
        }
        "磁力引擎启动超时（独立进程可能在启动时崩溃）"
    }

    suspend fun prepare(ctx: Context, magnet: String, retry: Boolean = true): Result {
        val e = ensure(ctx)
        val port = e as? Int ?: return Result.Fail(e.toString())
        return withContext(Dispatchers.IO) {
                val first = get(port, "/ctl/prepare?m=" + URLEncoder.encode(magnet, "UTF-8"))
                    ?: return@withContext Result.Fail(crashMsg())
                if (retry && first.contains("\"restart\"")) {
                    // 旧进程正在退出：等端口失效后重新拉起
                    repeat(20) { if (get(port, "/healthz", 500) == null) return@repeat; delay(200) }
                    delay(300)
                    return@withContext prepare(ctx, magnet, retry = false)
                }
                var last = ""
                while (true) {
                    delay(1000)
                    val body = get(port, "/ctl/state") ?: return@withContext Result.Fail(crashMsg())
                    val o = runCatching { JSONObject(body) }.getOrNull() ?: continue
                    val m = o.optString("msg")
                    if (m.isNotBlank() && m != last) { LogBus.i(TAG, m); last = m }
                    when (o.optString("state")) {
                        "ready" -> return@withContext Result.Ok(o.optString("url"))
                        "fail" -> return@withContext Result.Fail(m.ifBlank { "磁力起播失败" })
                    }
                }
                @Suppress("UNREACHABLE_CODE") Result.Fail("")
        }
    }

    private fun crashMsg(): String {
        LogBus.e(TAG, "磁力进程意外退出（疑似原生崩溃），主程序未受影响")
        return "磁力引擎意外退出（已隔离，主程序未受影响）。可换网络重试，或用其他应用打开。下次启动会在日志里记录退出原因。"
    }

    fun cancelAsync(ctx: Context) {
        val c = ctx.applicationContext
        Thread({ currentPort(c)?.let { get(it, "/ctl/cancel", 1000) } }, "magnet-cancel").start()
    }

    fun releaseAsync(ctx: Context) {
        val c = ctx.applicationContext
        Thread({ currentPort(c)?.let { get(it, "/ctl/release", 1000) } }, "magnet-release").start()
    }

    fun activeEntries(ctx: Context): Set<String> {
        val p = currentPort(ctx) ?: return emptySet()
        val body = get(p, "/ctl/active", 1000) ?: return emptySet()
        return runCatching {
            val a = JSONObject(body).getJSONArray("active"); (0 until a.length()).map { a.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }
}
