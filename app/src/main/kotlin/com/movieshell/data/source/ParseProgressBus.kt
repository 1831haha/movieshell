package com.movieshell.data.source

import com.movieshell.data.model.ParseConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * v0.294.7（L2 / M7）：解析进度与解析失败的全局通道（纯 Kotlin，UI 订阅）。
 * - [state]：正在解析时非空——当前线路名、已开始第几条 / 总条数；UI 显示
 *   「正在尝试 W3 (3/16)…」，可取消或指定线路；
 * - [failure]：最近一次解析全部失败（非用户取消）——UI 弹解析失败对话框，提供手动嗅探。
 */
object ParseProgressBus {

    data class State(
        val id: Long,
        val lines: List<ParseConfig>,
        val current: String,
        val index: Int,
        val total: Int,
        val picked: Boolean = false,
        val startedAt: Long = System.currentTimeMillis(),
    )

    data class Failure(val pageUrl: String, val reason: String, val at: Long = System.currentTimeMillis())

    sealed class Control {
        data object Cancel : Control()
        data class Pick(val line: ParseConfig) : Control()
    }

    class Session internal constructor(val id: Long, val lines: List<ParseConfig>) {
        val control = CompletableDeferred<Control>()
        internal val started = AtomicInteger(0)
    }

    private val ids = AtomicLong(0)
    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()
    private val _failure = MutableStateFlow<Failure?>(null)
    val failure: StateFlow<Failure?> = _failure.asStateFlow()

    @Volatile private var session: Session? = null

    fun begin(lines: List<ParseConfig>): Session {
        val s = Session(ids.incrementAndGet(), lines)
        session = s
        _failure.value = null
        _state.value = State(s.id, lines, current = lines.firstOrNull()?.name.orEmpty(), index = 0, total = lines.size)
        return s
    }

    fun onLineStart(s: Session, line: ParseConfig, picked: Boolean = false) {
        if (session !== s) return
        val idx = if (picked) (s.lines.indexOfFirst { it.url == line.url } + 1).coerceAtLeast(1)
        else s.started.incrementAndGet()
        _state.value = _state.value?.copy(current = line.name.ifBlank { "线路" }, index = idx.coerceAtMost(s.lines.size.coerceAtLeast(1)), picked = picked)
    }

    fun end(s: Session) {
        if (session === s) {
            session = null
            _state.value = null
        }
    }

    /** UI：取消当前解析 */
    fun cancel() { session?.control?.complete(Control.Cancel) }

    /** UI：只用指定线路解析 */
    fun pick(line: ParseConfig) { session?.control?.complete(Control.Pick(line)) }

    fun reportFailure(pageUrl: String, reason: String = "所有解析线路都没拿到可播地址") {
        _failure.value = Failure(pageUrl, reason)
    }

    fun clearFailure() { _failure.value = null }

    // v0.294.7（H7）：其它页面（播放器菜单 / 错误框）请求打开网页手动嗅探
    private val _manualSniff = MutableStateFlow<String?>(null)
    val manualSniff: StateFlow<String?> = _manualSniff.asStateFlow()
    fun requestManualSniff(pageUrl: String) { if (pageUrl.isNotBlank()) _manualSniff.value = pageUrl }
    fun consumeManualSniff() { _manualSniff.value = null }
}

/**
 * v0.294.7（L1）：解析结果短缓存（10 分钟）。键 = 视频地址 + 线路集合；
 * 播放出错时按结果地址失效（[invalidateResult]），下次重新解析。
 */
object ParseCache {
    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX = 100

    data class Entry(val url: String, val headers: Map<String, String>, val line: String, val at: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    fun key(videoUrl: String, lineUrls: List<String>): String =
        videoUrl.trim() + "|" + lineUrls.map { it.trim() }.sorted().joinToString(",").hashCode()

    fun get(key: String, now: Long = System.currentTimeMillis()): Entry? {
        val e = map[key] ?: return null
        if (now - e.at > TTL_MS) { map.remove(key); return null }
        return e
    }

    fun put(key: String, url: String, headers: Map<String, String>, line: String, now: Long = System.currentTimeMillis()) {
        if (url.isBlank()) return
        if (map.size >= MAX) {
            map.entries.minByOrNull { it.value.at }?.let { map.remove(it.key) }
        }
        map[key] = Entry(url, headers, line, now)
    }

    fun invalidateResult(resultUrl: String) {
        if (resultUrl.isBlank()) return
        map.entries.removeIf { it.value.url == resultUrl }
    }

    fun clear() = map.clear()
}
