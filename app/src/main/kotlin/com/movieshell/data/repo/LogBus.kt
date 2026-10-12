package com.movieshell.data.repo

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.7.2：应用内运行日志总线。
 *
 * - 线程安全环形缓冲，保留最近 MAX 条（2000）；
 * - 每条 = 时间戳 + 级别(D/I/W/E) + tag + 内容；
 * - d/i/w/e() 静态方法；同时写一份到 logcat；
 * - entries：StateFlow，日志悬浮窗 UI 订阅；
 * - init()：接入未捕获异常（记下来再交给原 handler，不吞崩溃）。
 *
 * 埋点约定：tag 用类名/模块名（如 "SiteRepo"、"ParseApi"、"Player"、"Sub"、"StoragePaths"）。
 */
object LogBus {
    const val MAX = 2000

    data class Entry(
        val time: Long,
        val level: Char,
        val tag: String,
        val msg: String,
    )

    private val lock = Any()
    private val deque = ArrayDeque<Entry>()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private var handlerInstalled = false
    private var logFile: java.io.File? = null

    /** 尽早调用（MainActivity.onCreate 最前面）。 */
    fun init(context: android.content.Context? = null) {
        synchronized(lock) {
            if (handlerInstalled) return
            handlerInstalled = true
        }
        // v0.14.3：崩溃日志写文件，重启后不丢
        context?.let {
            logFile = java.io.File(it.filesDir, "crash.log")
            // 启动时把上次的崩溃日志读回内存
            runCatching {
                val f = logFile!!
                if (f.exists() && f.length() > 0) {
                    val text = f.readText()
                    if (text.isNotBlank()) {
                        synchronized(lock) {
                            deque.addLast(Entry(System.currentTimeMillis(), 'E', "CrashLog", "=== 上次崩溃日志 ==="))
                            text.lineSequence().take(200).forEach { line ->
                                if (line.isNotBlank()) {
                                    deque.addLast(Entry(System.currentTimeMillis(), 'E', "CrashLog", line.take(500)))
                                }
                            }
                            while (deque.size > MAX) deque.removeFirst()
                            _entries.value = deque.toList()
                        }
                    }
                    f.writeText("") // 读完清空，避免重复
                }
            }
        }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val crashMsg = "未捕获异常 thread=${t.name}: ${Log.getStackTraceString(e)}"
                e("AndroidRuntime", crashMsg)
                // 同步写文件，确保崩溃前落盘
                runCatching {
                    logFile?.appendText(
                        "${java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} E/AndroidRuntime: ${crashMsg.take(2000)}\n"
                    )
                }
            } catch (_: Exception) {
                // 记录本身不能再抛
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun add(level: Char, tag: String, msg: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, msg)
        synchronized(lock) {
            deque.addLast(entry)
            while (deque.size > MAX) deque.removeFirst()
            _entries.value = deque.toList()
        }
        // 同时写一份到 logcat；单元测试环境下 android.util.Log 是 stub，吞掉
        runCatching {
            when (level) {
                'E' -> Log.e(tag, msg)
                'W' -> Log.w(tag, msg)
                'I' -> Log.i(tag, msg)
                else -> Log.d(tag, msg)
            }
        }
    }

    fun d(tag: String, msg: String) = add('D', tag, msg)
    fun i(tag: String, msg: String) = add('I', tag, msg)
    fun w(tag: String, msg: String) = add('W', tag, msg)
    fun e(tag: String, msg: String) = add('E', tag, msg)

    fun snapshot(): List<Entry> = synchronized(lock) { deque.toList() }

    fun clear() = synchronized(lock) {
        deque.clear()
        _entries.value = emptyList()
    }

    /** 导出 txt 用：每行 "MM-dd HH:mm:ss.SSS L/tag: msg"。 */
    fun dumpText(): String = buildString {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        for (e in snapshot()) {
            append(fmt.format(Date(e.time)))
                .append(' ')
                .append(e.level)
                .append('/')
                .append(e.tag)
                .append(": ")
                .append(e.msg)
                .append('\n')
        }
    }
}
