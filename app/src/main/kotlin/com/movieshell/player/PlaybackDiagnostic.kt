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
 * - 30 秒诊断超时只结束诊断（打汇总行），不影响播放：不触发重试、不换内核、不停播。
 * - 每个 mark 不拼串、不打行；汇总只在 Attempt 终结时拼一次（tag PlaybackDiagnostic，
 *   独立 tag 便于日志页按 tag 检索）；分片失败明细每 Attempt 最多 5 条。
 * - 引擎切换顺序保证：EngineHost.play() 先停旧引擎再建新 Attempt，旧引擎回调
 *   停表后不再打点；mark 先到先得，重复打点忽略。
 */
object PlaybackDiagnostic {

    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
    private val lock = Any()

    @Volatile
    private var session: FirstScreenSession? = null

    @Volatile
    private var attempt: FirstScreenAttempt? = null

    private val sessionSeq = AtomicLong(0)
    private val attemptSeq = AtomicLong(0)
    private var timeoutGen: Int = 0

    /** v1.3.21 同款懒初始化：对象级直接建 Handler 会让纯 JUnit 单测引用时炸 Stub。 */
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private val _snapshotFlow = MutableStateFlow<AttemptSnapshot?>(null)
    val snapshotFlow: StateFlow<AttemptSnapshot?> = _snapshotFlow.asStateFlow()

    val isEnabled: Boolean get() = PlayerSettings.firstScreenTrace.value

    /** 新会话：每次播放/重播/切集一次；旧 Attempt 未终结则按取消收尾（打汇总行）。 */
    fun beginSession(
        isLive: Boolean,
        pendingResolveMs: Long? = null,
        pendingPrefetchHit: Boolean = false,
    ) {
        if (!isEnabled) {
            clearSilently()
            return
        }
        val oldSnapshot: AttemptSnapshot?
        synchronized(lock) {
            oldSnapshot = finishCurrentLocked(TraceOutcome.CANCELLED)
            session = FirstScreenSession(
                id = sessionSeq.incrementAndGet(),
                isLive = isLive,
                pendingResolveMs = pendingResolveMs,
                pendingPrefetchHit = pendingPrefetchHit,
                clock = clock,
            )
            attempt = null
        }
        oldSnapshot?.let { logSnapshot(it) }
        publish()
    }

    /**
     * 新尝试：每次 EngineHost.play() 分发一次。无会话时建隐式会话
     * （磁力等不走 LaunchedEffect 的起播路径兜底）。
     */
    fun beginAttempt(engine: PlayerEngine, t0Ms: Long, isLive: Boolean) {
        if (!isEnabled) return
        val newAttempt: FirstScreenAttempt
        val oldSnapshot: AttemptSnapshot?
        synchronized(lock) {
            var current = session
            if (current == null) {
                current = FirstScreenSession(
                    id = sessionSeq.incrementAndGet(),
                    isLive = isLive,
                    clock = clock,
                )
                session = current
            }
            oldSnapshot = finishCurrentLocked(TraceOutcome.CANCELLED)
            newAttempt = current.newAttempt(engine.name, t0Ms, attemptSeq.incrementAndGet())
            attempt = newAttempt
            val gen = ++timeoutGen
            val attemptId = newAttempt.id
            mainHandler.postDelayed({ checkTimeout(gen, attemptId) }, FirstScreenAttempt.DIAGNOSTIC_TIMEOUT_MS)
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
        if (!isEnabled) {
            clearSilently()
            return
        }
        val snapshot: AttemptSnapshot?
        synchronized(lock) {
            snapshot = finishCurrentLocked(TraceOutcome.CANCELLED)
            session = null
            attempt = null
        }
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

    private inline fun withAttempt(block: (FirstScreenAttempt) -> Unit) {
        if (!isEnabled) return
        val current = attempt ?: return
        if (current.isFinished) return
        block(current)
        publish()
    }

    fun markStart(stage: TraceStage) = withAttempt { it.markStart(stage) }
    fun markEnd(stage: TraceStage) = withAttempt { it.markEnd(stage) }
    fun markPoint(stage: TraceStage, approximate: Boolean = false) =
        withAttempt { it.markPoint(stage, approximate = approximate) }
    fun markMeasured(stage: TraceStage, ms: Long, approximate: Boolean = false) =
        withAttempt { it.markMeasured(stage, ms, approximate) }

    fun markMissing(stage: TraceStage, missing: TraceMissing) = withAttempt { it.markMissing(stage, missing) }
    fun markFailed(stage: TraceStage, errorClass: String) = withAttempt { it.markFailed(stage, errorClass) }
    fun noteResponseWait(ms: Long) = withAttempt { it.noteResponseWait(ms) }
    fun noteFirstByte(ms: Long) = withAttempt { it.noteFirstByte(ms) }
    fun noteTls(ms: Long) = withAttempt { it.noteTls(ms) }
    fun noteFilterDownload(ms: Long, cacheHit: Boolean) = withAttempt { it.noteFilterDownload(ms, cacheHit) }

    /** 首个分片/清单加载开始：只第一次返回 true。 */
    fun noteLoadStarted(): Boolean {
        if (!isEnabled) return false
        val current = attempt ?: return false
        if (current.isFinished) return false
        val first = current.noteLoadStarted()
        if (first) publish()
        return first
    }

    /** HLS 分片失败明细（主清单 / 分片 / 密钥分开，只记 host 与异常类名）。 */
    fun noteSegmentFailure(uri: String, dataType: Int?, errorClass: String) {
        if (!isEnabled) return
        val host = runCatching { android.net.Uri.parse(uri).host.orEmpty() }.getOrDefault("")
        withAttempt { it.noteSegmentFailure(host, classifySegmentLoad(uri, dataType), errorClass) }
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
