package com.movieshell.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.movieshell.core.log.LogBus
import com.movieshell.data.store.PlayerSettings
import com.movieshell.domain.model.PlayerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicLong

/**
 * v1.4.6 A1.1：首屏分段诊断胶水层（唯一可变状态持有者）。
 *
 * - 开关关时零开销：isEnabled 为 false 时不建 Session/Attempt、不挂 OkHttp
 *   EventListener；各 mark 入口先判开关，播放器核心不订阅开关 Flow。
 * - 会话：PlayerScreen 的 LaunchedEffect(playUrl) 入口 beginSession；
 *   内核切换 / 重试 / 换线由 EngineHost.play() 分发处 beginAttempt 另建 Attempt。
 * - v1.4.7：会话归属播放本身，不归属 effect——同一地址的 effect 重入
 *   （全屏跳页/返回小窗）不重建会话；effect 取消时只在播放器确实不再
 *   服务该地址时才 endSession，真离开另由 dispose 处的 endSession 收尾。
 *   打点被丢时记一行原因（同一原因每会话只记一次），不要静默丢。
 *   会话身份凭 id 认领，不凭 URL 猜：beginSession 返回会话 id，effect
 *   入口捕获它，finally 只收尾这个 id 的会话（旧 effect 的 finally 可能
 *   晚于新 effect 的 beginSession 跑，无参收尾会误杀新会话）；重入与重播
 *   凭 isContinuation 区分（播放器当时是否正在服务该地址），不凭 Attempt
 *   是否已终结——首帧一到 Attempt 即终结（SUCCESS），终结是播放中的常态，
 *   拿它当“新播放”信号会让首帧后切全屏丢浮层。
 * - 30 秒诊断超时只结束诊断（打汇总行），不影响播放：不触发重试、不换内核、不停播。
 * - 每个 mark 不拼串、不打行；汇总只在 Attempt 终结时拼一次（tag PlaybackDiagnostic，
 *   独立 tag 便于日志页按 tag 检索）；分片失败明细每 Attempt 最多 5 条。
 * - 引擎切换顺序保证：EngineHost.play() 先停旧引擎再建新 Attempt，旧引擎回调
 *   停表后不再打点；mark 先到先得，重复打点忽略。
 */
object PlaybackDiagnostic {

    private val clock: () -> Long = { testClock?.invoke() ?: SystemClock.elapsedRealtime() }
    private val lock = Any()

    @Volatile
    private var session: FirstScreenSession? = null

    @Volatile
    private var attempt: FirstScreenAttempt? = null

    private val sessionSeq = AtomicLong(0)
    private val attemptSeq = AtomicLong(0)
    private var timeoutGen: Int = 0

    /** beginSession 在开关关闭（未建会话）时返回的哨兵：永远匹配不到任何会话。 */
    const val NO_SESSION_ID = -1L

    /**
     * v1.4.7 仅单测：覆盖开关与时间源——纯 JUnit 下 PlayerSettings 改开关要
     * Context、SystemClock 是 stub，直接读都会炸；设备上两者始终为 null，
     * 走正常路径。
     */
    internal var enabledOverride: Boolean? = null
    internal var testClock: (() -> Long)? = null

    /**
     * 已记过的丢点原因键（会话 id + 原因）：同一原因每会话只记一行，原因
     * 交替出现也不重复记；会话更替时清空（v1.4.7）。
     */
    private val loggedDropKeys = mutableSetOf<String>()

    /** v1.3.21 同款懒初始化：对象级直接建 Handler 会让纯 JUnit 单测引用时炸 Stub。 */
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private val _snapshotFlow = MutableStateFlow<AttemptSnapshot?>(null)
    val snapshotFlow: StateFlow<AttemptSnapshot?> = _snapshotFlow.asStateFlow()

    val isEnabled: Boolean get() = enabledOverride ?: PlayerSettings.firstScreenTrace.value

    /**
     * 新会话：每次播放/重播/切集一次；旧 Attempt 未终结则按取消收尾（打汇总行）。
     * v1.4.7：诊断会话归属播放本身，不归属 composable effect——同一地址的
     * effect 重入（全屏跳页/返回小窗）不重建会话：接续同一播放器时不重新调
     * play()，重建会话会把 Attempt 置空，此后全部打点被 withAttempt 以
     * attempt==null 丢掉（1.4.6 真机实证：两段点播＋一段直播全是未到该段）。
     * 只有“地址相同且是接续”才保留旧会话：isContinuation 由调用方在
     * beginSession 之前算（播放器当时是否正在服务该地址）——只凭 URL 分
     * 不清“同地址重入”与“同地址重播”，Attempt 是否已终结也不能当信号
     * （首帧一到 Attempt 即终结，终结是播放中的常态）。
     * 返回本次 beginSession 归属的会话 id（开关关闭时返回 NO_SESSION_ID）；
     * 调用方（effect）须在入口捕获它，finally 凭它认领收尾。
     */
    fun beginSession(
        url: String,
        isLive: Boolean,
        isContinuation: Boolean,
        pendingResolveMs: Long? = null,
        pendingPrefetchHit: Boolean = false,
    ): Long {
        if (!isEnabled) {
            clearSilently()
            return NO_SESSION_ID
        }
        val oldSnapshot: AttemptSnapshot?
        val sessionId: Long
        synchronized(lock) {
            val current = session
            if (current != null && current.url == url && isContinuation && current.latestAttempt != null) {
                // 同一播放的 effect 重入：留住原会话与 Attempt，解析待记值
                // 只在新会话上用，旧会话上已记过，直接丢弃。
                oldSnapshot = null
                sessionId = current.id
            } else {
                oldSnapshot = finishCurrentLocked(TraceOutcome.CANCELLED)
                val created = FirstScreenSession(
                    id = sessionSeq.incrementAndGet(),
                    isLive = isLive,
                    url = url,
                    pendingResolveMs = pendingResolveMs,
                    pendingPrefetchHit = pendingPrefetchHit,
                    clock = clock,
                )
                session = created
                attempt = null
                loggedDropKeys.clear()
                sessionId = created.id
            }
        }
        oldSnapshot?.let { logSnapshot(it) }
        publish()
        return sessionId
    }

    /**
     * 新尝试：每次 EngineHost.play() 分发一次。无会话时建隐式会话
     * （磁力等不走 LaunchedEffect 的起播路径兜底）。
     * v1.4.7：隐式会话也带地址——effect 随后以同一地址 beginSession 时
     * 才能认出是同一播放而不重建；会话已存在时地址只用于建隐式会话，
     * 不改现有会话（换地址由 effect 的 beginSession 负责）。
     */
    fun beginAttempt(engine: PlayerEngine, t0Ms: Long, isLive: Boolean, url: String = "") {
        if (!isEnabled) return
        val newAttempt: FirstScreenAttempt
        val oldSnapshot: AttemptSnapshot?
        synchronized(lock) {
            var current = session
            if (current == null) {
                current = FirstScreenSession(
                    id = sessionSeq.incrementAndGet(),
                    isLive = isLive,
                    url = url,
                    clock = clock,
                )
                session = current
                loggedDropKeys.clear()
            }
            oldSnapshot = finishCurrentLocked(TraceOutcome.CANCELLED)
            newAttempt = current.newAttempt(engine.name, t0Ms, attemptSeq.incrementAndGet())
            attempt = newAttempt
            val gen = ++timeoutGen
            val attemptId = newAttempt.id
            // v1.4.7：纯 JUnit 下 Looper 是 stub，postDelayed 会抛——30 秒超时
            // 只是诊断兜底，单测里不要它；设备上正常挂。吞异常时留一行，
            // 免得设备上超时兜底静默失效、超时后打点继续落却无人知晓。
            runCatching {
                mainHandler.postDelayed({ checkTimeout(gen, attemptId) }, FirstScreenAttempt.DIAGNOSTIC_TIMEOUT_MS)
            }.onFailure {
                LogBus.i("PlaybackDiagnostic", "首屏诊断 超时兜底挂载失败 ${it::class.java.simpleName}")
            }
        }
        oldSnapshot?.let { logSnapshot(it) }
        publish()
    }

    private fun checkTimeout(gen: Int, attemptId: Long) {
        val current = attempt ?: return
        if (gen != timeoutGen || current.id != attemptId) return
        if (current.isTimedOut()) {
            finishAttempt(TraceOutcome.TIMEOUT)
        }
    }

    /** 终结当前 Attempt（成功 / 失败 / 超时 / 取消），打汇总行 + 分片失败明细行。 */
    fun finishAttempt(outcome: TraceOutcome) {
        if (!isEnabled) return
        val snapshot: AttemptSnapshot?
        synchronized(lock) {
            timeoutGen++
            snapshot = finishCurrentLocked(outcome)
        }
        if (snapshot != null) {
            logSnapshot(snapshot)
            publish()
        }
    }

    private fun logSnapshot(snapshot: AttemptSnapshot) {
        LogBus.i("PlaybackDiagnostic", formatLogLine(snapshot))
        for (line in formatSegmentFailureLines(snapshot)) {
            LogBus.i("PlaybackDiagnostic", line)
        }
    }

    /** 会话结束（地址切换 / 退出播放页）：未终结的 Attempt 按取消收尾，清快照。 */
    fun endSession() {
        endSessionLocked(expectedSessionId = null)
    }

    /**
     * v1.4.7 P0：只收尾指定会话（凭创建/接管时捕获的 id 认领）。
     * 旧 effect 的 finally 可能晚于新 effect 的 beginSession 跑，无参收尾
     * 会误杀新会话；id 不符（或已无会话）时是 no-op。注意 finally 里现读
     * 当前会话 id 再传不算数——必须用 beginSession 返回时捕获的 id。
     * NO_SESSION_ID 永远匹配不到任何会话，也是 no-op。
     */
    fun endSession(expectedSessionId: Long) {
        endSessionLocked(expectedSessionId)
    }

    /**
     * 收尾的唯一实现：expectedSessionId 为 null 时收尾当前会话（dispose
     * 路径，真离开时才调）；非 null 时只收尾 id 相符的会话。开关关闭时
     * 静默清（不打汇总行），与旧行为一致。
     */
    private fun endSessionLocked(expectedSessionId: Long?) {
        if (!isEnabled) {
            var matched = expectedSessionId == null
            synchronized(lock) {
                if (expectedSessionId != null) {
                    matched = session?.id == expectedSessionId
                }
                if (matched) {
                    session = null
                    attempt = null
                    timeoutGen++
                    loggedDropKeys.clear()
                }
            }
            if (matched) _snapshotFlow.value = null
            return
        }
        val snapshot: AttemptSnapshot?
        var matched = true
        synchronized(lock) {
            if (expectedSessionId != null && session?.id != expectedSessionId) {
                matched = false
                snapshot = null
            } else {
                snapshot = finishCurrentLocked(TraceOutcome.CANCELLED)
                session = null
                attempt = null
                loggedDropKeys.clear()
            }
        }
        if (!matched) return
        if (snapshot != null) {
            logSnapshot(snapshot)
        }
        publish()
    }

    private fun clearSilently() {
        synchronized(lock) {
            session = null
            attempt = null
            timeoutGen++
            loggedDropKeys.clear()
        }
        _snapshotFlow.value = null
    }

    /** 锁内终结当前 Attempt；已终结或无 Attempt 时返回 null（不重复打行）。 */
    private fun finishCurrentLocked(outcome: TraceOutcome): AttemptSnapshot? {
        val current = attempt ?: return null
        if (current.isFinished) return null
        val finished = current.finish(outcome)
        val currentSession = session
        return if (currentSession != null) {
            finished.copy(
                sessionId = currentSession.id,
                attemptIndex = currentSession.attemptCount,
            )
        } else {
            finished
        }
    }

    private fun publish() {
        _snapshotFlow.value = session?.snapshotLatest()
    }

    private fun currentAttempt(): FirstScreenAttempt? = attempt

    /**
     * v1.4.7：打点被丢时记一行（tag PlaybackDiagnostic），不要静默丢——
     * 1.4.6 真机上解码器回调明明触发了、打点全丢，日志里却没有任何痕迹，
     * 只能靠猜。同一原因只记一行（key 含会话 id，新会话重新记）。
     */
    private fun logDropOnce(reason: String, what: String) {
        // 会话 id 只读一次：key 与日志正文用同一个值，免得会话切换时
        // key 对旧会话、正文写新会话。
        val sessionId = session?.id ?: NO_SESSION_ID
        val key = "$sessionId:$reason"
        val first = synchronized(lock) { loggedDropKeys.add(key) }
        if (!first) return
        LogBus.i("PlaybackDiagnostic", "首屏诊断 打点被丢 $what 原因=$reason 会话#$sessionId")
    }

    private inline fun withAttempt(what: String, block: (FirstScreenAttempt) -> Unit) {
        if (!isEnabled) {
            logDropOnce("开关已关", what)
            return
        }
        val current = attempt
        if (current == null) {
            logDropOnce(if (session == null) "无会话" else "无尝试", what)
            return
        }
        if (current.isFinished) {
            logDropOnce("尝试已终结", what)
            return
        }
        block(current)
        publish()
    }

    fun markStart(stage: TraceStage) = withAttempt("打点 ${stage.label}") { it.markStart(stage) }
    fun markEnd(stage: TraceStage) = withAttempt("打点 ${stage.label}") { it.markEnd(stage) }
    fun markPoint(stage: TraceStage, approximate: Boolean = false) =
        withAttempt("打点 ${stage.label}") { it.markPoint(stage, approximate = approximate) }
    fun markMeasured(stage: TraceStage, ms: Long, approximate: Boolean = false) =
        withAttempt("打点 ${stage.label}") { it.markMeasured(stage, ms, approximate) }

    fun markMissing(stage: TraceStage, missing: TraceMissing) =
        withAttempt("打点 ${stage.label}") { it.markMissing(stage, missing) }
    fun markFailed(stage: TraceStage, errorClass: String) =
        withAttempt("打点 ${stage.label}") { it.markFailed(stage, errorClass) }
    fun noteResponseWait(ms: Long) = withAttempt("响应等待") { it.noteResponseWait(ms) }
    fun noteFirstByte(ms: Long) = withAttempt("首字节") { it.noteFirstByte(ms) }
    fun noteTls(ms: Long) = withAttempt("TLS") { it.noteTls(ms) }
    fun noteFilterDownload(ms: Long, cacheHit: Boolean) =
        withAttempt("过滤下载") { it.noteFilterDownload(ms, cacheHit) }

    /** 首个分片/清单加载开始：只第一次返回 true。 */
    fun noteLoadStarted(): Boolean {
        if (!isEnabled) {
            logDropOnce("开关已关", "开流开始")
            return false
        }
        val current = attempt
        if (current == null) {
            logDropOnce(if (session == null) "无会话" else "无尝试", "开流开始")
            return false
        }
        if (current.isFinished) {
            logDropOnce("尝试已终结", "开流开始")
            return false
        }
        val first = current.noteLoadStarted()
        if (first) publish()
        return first
    }

    /** HLS 分片失败明细（主清单 / 分片 / 密钥分开，只记 host 与异常类名）。 */
    fun noteSegmentFailure(uri: String, dataType: Int?, errorClass: String) {
        if (!isEnabled) {
            logDropOnce("开关已关", "分片失败")
            return
        }
        val host = runCatching { android.net.Uri.parse(uri).host.orEmpty() }.getOrDefault("")
        withAttempt("分片失败") { it.noteSegmentFailure(host, classifySegmentLoad(uri, dataType), errorClass) }
    }

    /**
     * 给本次播放新建的 OkHttp 客户端挂 EventListener（浅拷贝，共享单例不动）。
     * 关时 / 无 Attempt 时原样返回。工厂每次建 Call 时取当前 Attempt，旧引擎
     * 停表后不再建新 Call，不会串到新 Attempt 上。
     */
    fun maybeWrapClient(base: OkHttpClient): OkHttpClient {
        if (!isEnabled) return base
        return base.newBuilder()
            .eventListenerFactory {
                currentAttempt()?.let { FirstScreenOkHttpListener(it) } ?: EventListener.NONE
            }
            .build()
    }
}
