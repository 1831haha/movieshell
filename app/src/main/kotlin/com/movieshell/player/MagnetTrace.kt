package com.movieshell.player

import android.content.Context
import com.movieshell.data.repo.LogBus
import java.io.File

/**
 * v0.294.33：:magnet 进程的日志转发 + 落盘「最后步骤」。
 * 主进程轮询 /ctl/state 时把新日志带回主日志；进程原生崩溃后主进程读 magnet_trace.txt 看崩在哪一步。
 */
object MagnetTrace {
    private val lines = ArrayList<String>()
    @Volatile private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "magnet_trace.txt").also { runCatching { it.writeText("") } }
    }

    fun log(tag: String, msg: String) {
        LogBus.i(tag, msg)
        val line = "$tag\t$msg"
        synchronized(lines) { lines += line; if (lines.size > 400) lines.subList(0, 200).clear() }
        file?.let { f -> runCatching { f.appendText(java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()) + " " + line + "\n") } }
    }

    /** 自第 [since] 条以来的新日志与新的游标 */
    fun since(since: Int): Pair<List<String>, Int> = synchronized(lines) {
        val s = since.coerceIn(0, lines.size)
        lines.subList(s, lines.size).toList() to lines.size
    }

    fun file(context: Context) = File(context.filesDir, "magnet_trace.txt")
}
