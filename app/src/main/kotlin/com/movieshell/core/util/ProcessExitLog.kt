package com.movieshell.core.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.movieshell.core.log.LogBus
import java.io.File

/**
 * v0.294.29：读系统记录的历史进程退出原因（Android 11+），把上次以来的
 * 崩溃/原生崩溃/ANR/被杀写进运行日志；原生崩溃的 tombstone 摘要存到
 * filesDir/last_native_crash.txt，方便用户在日志里直接看到。
 */
object ProcessExitLog {
    private const val PREF = "exit_log"
    private const val KEY = "last_ts"

    fun isMagnetProcess(ctx: Context): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
        else runCatching { File("/proc/self/cmdline").readText().trim('\u0000', ' ') }.getOrDefault("")
        return name.endsWith(":magnet")
    }

    /** v0.294.38：系统回收 / 主动结束 说人话，别都像崩溃 */
    fun label(r: Int, desc: String?): String = when {
        desc?.contains("clean_up_mem") == true || desc?.contains("lmk", true) == true || r == 3 -> "系统回收内存（切到后台时）"
        r == 2 -> "被结束（多为侠影主动停止旧磁力任务）"
        else -> reasonName(r)
    }

    /** 最近一次 :magnet 退出（[sinceMs] 之后）的说明；没有返回 null */
    fun lastMagnetExit(ctx: android.content.Context, sinceMs: Long): Pair<Int, String>? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return null
        val e = runCatching { am.getHistoricalProcessExitReasons(null, 0, 10) }.getOrNull()
            ?.firstOrNull { it.processName.endsWith(":magnet") && it.timestamp >= sinceMs } ?: return null
        return e.reason to label(e.reason, e.description)
    }

    fun reasonName(r: Int): String = when (r) {
        1 -> "主动退出"; 2 -> "信号终止"; 3 -> "内存不足被杀"; 4 -> "Java 崩溃"; 5 -> "原生崩溃"
        6 -> "无响应(ANR)"; 7 -> "初始化失败"; 8 -> "权限变化"; 9 -> "资源超限"; 10 -> "用户请求"
        11 -> "用户停止"; 12 -> "依赖进程死亡"; 13 -> "其他"; 14 -> "冻结"; else -> "未知($r)"
    }

    /** 从 tombstone（文本或 protobuf）里抽出可读片段 */
    fun printable(bytes: ByteArray, maxLines: Int = 40): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) sb.append(c.toChar()) else {
                if (sb.length >= 6) out += sb.toString()
                sb.setLength(0)
                if (out.size >= maxLines) break
            }
        }
        if (sb.length >= 6 && out.size < maxLines) out += sb.toString()
        return out
    }

    fun logAsync(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val c = ctx.applicationContext
        Thread({ runCatching { log(c) } }, "exit-log").also { it.isDaemon = true; it.start() }
    }

    private fun log(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val last = sp.getLong(KEY, 0L)
        val list: List<ApplicationExitInfo> = am.getHistoricalProcessExitReasons(null, 0, 10)
        var newest = last
        for (e in list.sortedBy { it.timestamp }) {
            if (e.timestamp <= last) continue
            newest = maxOf(newest, e.timestamp)
            val r = e.reason
            if (r == ApplicationExitInfo.REASON_EXIT_SELF || r == ApplicationExitInfo.REASON_USER_REQUESTED) continue
            val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.timestamp))
            LogBus.w("ExitInfo", "上次退出 $time 进程 ${e.processName}：${label(r, e.description)} ${e.description.orEmpty()}")
            if (r == ApplicationExitInfo.REASON_CRASH_NATIVE || r == ApplicationExitInfo.REASON_ANR) {
                val bytes = runCatching { e.traceInputStream?.use { it.readBytes() } }.getOrNull() ?: continue
                val lines = printable(bytes)
                runCatching {
                    File(ctx.filesDir, "last_native_crash.txt").writeText("$time ${e.processName} ${reasonName(r)}\n" + lines.joinToString("\n"))
                }
                lines.take(15).forEach { LogBus.w("ExitInfo", it) }
            }
        }
        if (newest > last) sp.edit().putLong(KEY, newest).apply()
    }
}
