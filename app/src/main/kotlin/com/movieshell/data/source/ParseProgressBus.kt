package com.movieshell.data.source

import com.movieshell.data.store.ParseStore
import com.movieshell.domain.model.ParseConfig
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

/**
 * v1.2.8：解析失败自动换线——记下“哪条解析线路给哪个片回了播不了的地址”，
 * 下次重解析直接跳过它，换下一条线出结果。
 *
 * - 触发点：播放器重试链耗尽、认定当前地址彻底播不了时（[com.movieshell.player.PlayerScreen.tryNextLine]），
 *   按结果地址反查命中线路并记死；不是解析一失败就记（同内核重拉/清 Referer/换内核
 *   还有机会救回来，那时记死会误伤一条本来能用的线）。
 * - 作用域：单个视频地址（各视频线路同集共用同一集地址时才互相影响；换集/换片不受影响）。
 * - 不是永久拉黑：与 ParseCache 同 TTL（10 分钟），到期自动恢复；全部线路都被记死时
 *   [ParseApi] 回退到全部线路再试一次，不留空。
 * - 记死的同时给该线路记一次软失败（3 次连着软失败才 30 分钟短暂停用，见 ParseStore），
 *   让它的排序分往下走——解析时“先到先得”的竞速里它不再总是最快胜出。
 * 纯 Kotlin，可单元测试；调用方不得在这里放 Android Context。
 */
object ParseDeadLines {
    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_HITS = 200

    private data class Hit(val videoUrl: String, val line: String, val lineUrl: String, val at: Long)

    /** 结果地址 → 命中记录（播放失败时按结果地址反查是哪条线给的） */
    private val byResult = ConcurrentHashMap<String, Hit>()
    /** 视频地址 → 该片已记死的线路名 */
    private val dead = ConcurrentHashMap<String, MutableSet<String>>()
    /** 视频地址 → 首次记死时间（TTL 起点） */
    private val deadAt = ConcurrentHashMap<String, Long>()

    /** 线路名归一化：空白名统一记成 "JSON"（与 ParseApi.parseJson 的兜底一致），两侧同规则才对得上。 */
    fun normLineName(name: String): String = name.trim().ifBlank { "JSON" }

    /** 解析命中时登记：结果地址是事后反查的唯一线索。 */
    fun noteHit(videoUrl: String, resultUrl: String, line: String, lineUrl: String, now: Long = System.currentTimeMillis()) {
        if (videoUrl.isBlank() || resultUrl.isBlank()) return
        if (byResult.size >= MAX_HITS) {
            byResult.entries.removeIf { now - it.value.at > TTL_MS }
        }
        byResult[resultUrl] = Hit(videoUrl.trim(), normLineName(line), lineUrl.trim(), now)
    }

    /**
     * 播放失败时按结果地址反查并记死该线路；同时记一次软失败。
     * 查不到记录（非解析出的地址，如直链/嗅探）或记录已过期时返回 false，什么都不记。
     */
    fun markDeadByResult(resultUrl: String, now: Long = System.currentTimeMillis()): Boolean {
        val hit = byResult[resultUrl] ?: return false
        if (now - hit.at > TTL_MS) {
            byResult.remove(resultUrl)
            return false
        }
        dead.compute(hit.videoUrl) { _, s -> (s ?: mutableSetOf()).also { it.add(hit.line) } }
        deadAt.putIfAbsent(hit.videoUrl, now)
        if (hit.lineUrl.isNotBlank()) ParseStore.recordSoftFail(hit.lineUrl)
        return true
    }

    /** 该片当前已记死的线路名（过期整批清除）。 */
    fun deadLinesFor(videoUrl: String, now: Long = System.currentTimeMillis()): Set<String> {
        val at = deadAt[videoUrl.trim()] ?: return emptySet()
        if (now - at > TTL_MS) {
            dead.remove(videoUrl.trim())
            deadAt.remove(videoUrl.trim())
            return emptySet()
        }
        return dead[videoUrl.trim()]?.toSet().orEmpty()
    }

    fun clear() {
        byResult.clear()
        dead.clear()
        deadAt.clear()
    }
}
