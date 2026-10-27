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
    private var readCrashDone = false

    /** 尽早调用（MainActivity.onCreate 最前面）。 */
    fun init(context: android.content.Context? = null) {
        synchronized(lock) {
            if (handlerInstalled) return
            handlerInstalled = true
        }
        // v0.25.0：崩溃捕获已收敛到 CrashHandler（Application 里更早安装），
        // 这里只做"上次崩溃读回内存"，不再装第二套 handler。
        context?.let { readLastCrash(it) }
    }

    /**
     * v0.25.0：把最近一次崩溃文件读回内存（供日志页查看），最多 200 行。
     * CrashHandler 写的是 xiaying/crash/ 下按时间命名的文件。幂等，重复调用不重复灌。
     */
    fun readLastCrash(context: android.content.Context) {
        synchronized(lock) {
            if (readCrashDone) return
            readCrashDone = true
        }
        runCatching {
            val f = CrashHandler.latest(context) ?: return
            // v0.246.0：原来全文件 readText() 后才 take(200) 行，异常大日志白占内存。
            // 改流式读前 200 行，读到就停。
            // v0.246.0 补（GPT 审）：按原始行数限 200 行、再滤空行——
            // 原来是“前 200 行里滤空行”，上一版改成“攒够 200 条非空行”语义变了，
            // 前面空行多时会多读很长一段。
            val lines = ArrayList<String>(200)
            var rawCount = 0
            f.bufferedReader().use { reader ->
                while (rawCount < 200) {
                    val line = reader.readLine() ?: break
                    rawCount++
                    if (line.isNotBlank()) lines.add(line.take(500))
                }
            }
            if (lines.isEmpty()) return
            synchronized(lock) {
                deque.addLast(Entry(System.currentTimeMillis(), 'E', "CrashLog", "=== 上次崩溃：${f.name} ==="))
                lines.forEach { line ->
                    deque.addLast(Entry(System.currentTimeMillis(), 'E', "CrashLog", line))
                }
                while (deque.size > MAX) deque.removeFirst()
                _entries.value = deque.toList()
            }
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
