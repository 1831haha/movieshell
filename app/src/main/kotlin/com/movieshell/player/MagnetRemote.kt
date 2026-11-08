package com.movieshell.player

import android.content.Context
import android.content.Intent
import com.movieshell.core.log.LogBus
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

    @Volatile var preparing = false
    /** v0.294.42：prepare 刚成功到 play() 接上之前仍续命（约 15s） */
    @Volatile var pendingKeepUntil = 0L
    @Volatile private var logPort = -1
    @Volatile private var logCursor = 0

    suspend fun prepare(ctx: Context, magnet: String, retry: Boolean = true): Result {
        preparing = true
        // v0.294.42：一点播就续命，不等播放器真正起播（列表连点时还没 currentUrl）
        keepAsync(ctx)
        EngineHost.noteMagnetInterest(ctx)
        try { return prepare0(ctx, magnet, retry) } finally { preparing = false }
    }

    private suspend fun prepare0(ctx: Context, magnet: String, retry: Boolean): Result {
        if (!EnginePlugins.btInstalled(ctx) && !EnginePlugins.xlInstalled(ctx)) return Result.Fail(EnginePlugins.NEED_ANY)
        val e = ensure(ctx)
        val port = e as? Int ?: return Result.Fail(e.toString())
        return withContext(Dispatchers.IO) {
                if (!EnginePlugins.btInstalled(ctx) && !EnginePlugins.xlInstalled(ctx)) {
                    return@withContext Result.Fail(EnginePlugins.NEED_ANY)
                }
                val first = get(port, "/ctl/prepare?prefer=" + EnginePlugins.prefer(ctx) + "&m=" + URLEncoder.encode(magnet, "UTF-8"))
                if (first == null) {
                    val why = crashMsg(ctx)
                    // v0.294.42：旧进程正在退出时不要立刻「请回到侠影后重试」——等它死透再拉起重试
                    if (retry && (why.contains("已停止") || why.contains("被结束") || why.contains("请回到侠影"))) {
                        LogBus.i(TAG, "磁力进程正在退出，等待后重试：$why")
                        repeat(25) { if (get(port, "/healthz", 400) == null) return@repeat; delay(200) }
                        delay(400)
                        return@withContext prepare(ctx, magnet, retry = false)
                    }
                    return@withContext Result.Fail(why)
                }
                if (retry && first.contains("\"restart\"")) {
                    expectStop()
                    // 旧进程正在退出：等端口失效后重新拉起
                    repeat(20) { if (get(port, "/healthz", 500) == null) return@repeat; delay(200) }
                    delay(300)
                    return@withContext prepare(ctx, magnet, retry = false)
                }
                // v0.294.39：日志游标跨次保留（同一磁力进程），不再把旧日志整段重放
                if (logPort != port) { logPort = port; logCursor = 0 }
                var cursor = logCursor
                while (true) {
                    delay(700)
                    val body = get(port, "/ctl/state?since=$cursor")
                    if (body == null) {
                        val why = crashMsg(ctx)
                        if (retry && (why.contains("已停止") || why.contains("被结束") || why.contains("请回到侠影"))) {
                            LogBus.i(TAG, "轮询时磁力进程退出，等待后重试：$why")
                            repeat(25) { if (get(port, "/healthz", 400) == null) return@repeat; delay(200) }
                            delay(400)
                            return@withContext prepare(ctx, magnet, retry = false)
                        }
                        return@withContext Result.Fail(why)
                    }
                    val o = runCatching { JSONObject(body) }.getOrNull() ?: continue
                    val m = o.optString("msg")
                    // v0.294.33：:magnet 进程日志逐条转回主日志（含迅雷每一步）
                    o.optJSONArray("logs")?.let { a ->
                        for (i in 0 until a.length()) {
                            val parts = a.getString(i).split('\t', limit = 3)
                            if (parts.size == 3) {
                                LogBus.addAt(parts[0].toLongOrNull() ?: System.currentTimeMillis(), 'I', parts[1], parts[2])
                            } else LogBus.i(TAG, parts.last())
                        }
                    }
                    cursor = o.optInt("next", cursor)
                    logCursor = cursor
                    when (o.optString("state")) {
                        "ready" -> {
                            lastUrl = o.optString("url")
                            pendingKeepUntil = System.currentTimeMillis() + 15_000
                            return@withContext Result.Ok(lastUrl)
                        }
                        "fail" -> return@withContext Result.Fail(m.ifBlank { "磁力起播失败" })
                    }
                }
                @Suppress("UNREACHABLE_CODE") Result.Fail("")
        }
    }

    /** v0.294.37：我们主动结束磁力进程时标记，之后几秒内的断连不算崩溃 */
    @Volatile private var expectedStopUntil = 0L
    fun expectStop() { expectedStopUntil = System.currentTimeMillis() + 6000 }

    private fun crashMsg(ctx: Context): String {
        if (System.currentTimeMillis() < expectedStopUntil) {
            LogBus.i(TAG, "已停止旧磁力任务")
            return "已停止旧磁力任务"
        }
        // v0.294.38：先问系统退出原因——切到后台被回收内存不算崩溃
        val since = System.currentTimeMillis() - 60_000
        var ex: Pair<Int, String>? = null
        for (i in 0 until 6) {
            ex = com.movieshell.data.repo.ProcessExitLog.lastMagnetExit(ctx, since)
            if (ex != null) break
            Thread.sleep(300)
        }
        if (ex != null && ex.first != android.app.ApplicationExitInfo.REASON_CRASH_NATIVE &&
            ex.first != android.app.ApplicationExitInfo.REASON_CRASH
        ) {
            LogBus.w(TAG, "磁力进程退出：${ex.second}")
            return "磁力引擎被${ex.second}，请回到侠影后重试"
        }
        LogBus.e(TAG, "磁力进程意外退出（疑似原生崩溃），主程序未受影响")
        // 崩溃前最后几步 + 系统记录的退出原因
        runCatching {
            MagnetTrace.file(ctx).readLines().takeLast(8).forEach { LogBus.w("MagnetTrace", it) }
        }
        val c = ctx.applicationContext
        Thread({
            Thread.sleep(1500)
            com.movieshell.data.repo.ProcessExitLog.logAsync(c)
        }, "magnet-exitinfo").start()
        return "磁力引擎意外退出（已隔离，主程序未受影响）。可换网络重试，或用其他应用打开。下次启动会在日志里记录退出原因。"
    }

    /** v0.294.34：播放器轮询的实时缓冲信息（不在主线程调用） */
    fun liveInfo(ctx: Context): String {
        val p = currentPort(ctx) ?: return ""
        val body = get(p, "/ctl/state", 800) ?: return ""
        return runCatching { JSONObject(body).optString("live") }.getOrDefault("")
    }

    /** 最近一次交给播放器的磁力播放地址 */
    @Volatile var lastUrl: String = ""

    fun isRunning(ctx: Context): Boolean = currentPort(ctx) != null

    /** 结束磁力进程并等它退出（最多约 3 秒），供清理缓存前调用 */
    fun stopBlocking(ctx: Context) {
        val p = currentPort(ctx) ?: return
        expectStop()
        get(p, "/ctl/cancel", 1000)
        repeat(15) { if (get(p, "/healthz", 300) == null) return; Thread.sleep(200) }
    }

    fun cancelAsync(ctx: Context) {
        val c = ctx.applicationContext
        expectStop()
        Thread({ currentPort(c)?.let { get(it, "/ctl/cancel", 1000) } }, "magnet-cancel").start()
    }

    /** 同一磁力地址重新起播（换内核等）：作废磁力进程里待执行的「离开后停止」 */
    fun keepAsync(ctx: Context) {
        val c = ctx.applicationContext
        Thread({ currentPort(c)?.let { get(it, "/ctl/keep", 1000) } }, "magnet-keep").start()
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
