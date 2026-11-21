package com.movieshell.player

/**
 * v1.4.6 A1.1：首屏分段诊断——纯模型与格式化（无 Android 依赖，可单测）。
 *
 * 口径（定稿 a1-design-final.md §3/§4/§7）：
 * - T0 = EngineHost.play() 入口；端到端 = T0 → 首帧渲染，首帧未到则总计为 null；
 *   端到端 ≠ 各段之和，不许用加法拼总计。
 * - 各段耗时 = 终点 − 起点，任一端缺失则该段为 null，不是 0；唯一允许的 0 是
 *   预解析命中（解析段记 0ms + 备注）。
 * - 点事件段（首帧解码 S7、首帧渲染 S8）没有独立起点：起点取前序最近一段的
 *   终点（按段顺序往前找，找不到则取 T0）。首帧解码是条件项，取不到标未取到，
 *   Media3 用首个有效画面尺寸作近似代理并标“（近似）”；绝不拿“渲染 − 初始化”冒充。
 * - 缺失七态与日志/浮层/单测用同一词：未到该段 / 未取到 / 未发生（连接复用）/
 *   缓存命中 / 条件项未满足 / 超时未完成 / 预解析命中；DNS/建连失败另记
 *   “失败（异常类名）”，只记类名不记完整 URL。
 * - 时钟可注入：默认调用方传 SystemClock.elapsedRealtime()，单测传假时钟。
 */
enum class TraceStage(val label: String, val logLabel: String) {
    RESOLVE("解析", "解析"),
    DNS("DNS", "DNS"),
    CONNECT("建连", "建连"),
    OPEN("开流", "开流"),
    PREPARE("媒体准备", "媒体准备"),
    DECODE("解码器初始化", "解码"),
    FIRST_DECODE("首帧解码", "首帧解码"),
    FIRST_FRAME("首帧渲染", "首帧渲染"),
}

enum class TraceMissing(val label: String) {
    NOT_REACHED("未到该段"),
    UNAVAILABLE("未取到"),
    NOT_OCCURRED("未发生（连接复用）"),
    CACHE_HIT("缓存命中"),
    CONDITION_UNMET("条件项未满足"),
    TIMEOUT("超时未完成"),
    PREFETCH_HIT("预解析命中"),
}

enum class TraceOutcome(val label: String) {
    SUCCESS("成功"),
    FAILED("失败"),
    TIMEOUT("诊断超时"),
    CANCELLED("取消"),
}

enum class SegmentKind(val label: String) {
    MANIFEST("主清单"),
    SEGMENT("分片"),
    KEY("密钥"),
}

data class SegmentFailure(
    val host: String,
    val kind: SegmentKind,
    val errorClass: String,
    val requestIndex: Int,
)

data class StageMark(
    val startMs: Long? = null,
    val endMs: Long? = null,
    val measuredMs: Long? = null,
    val missing: TraceMissing? = null,
    val errorClass: String? = null,
    val approximate: Boolean = false,
)

/** 一次 Attempt 的不可变快照：浮层与日志只读它，不读可变状态。 */
data class AttemptSnapshot(
    val sessionId: Long,
    val attemptIndex: Int,
    val engine: String,
    val isLive: Boolean,
    val t0Ms: Long,
    val durations: Map<TraceStage, Long?>,
    val missing: Map<TraceStage, TraceMissing>,
    val errorClasses: Map<TraceStage, String>,
    val approximateStages: Set<TraceStage>,
    val responseWaitMs: Long?,
    val firstByteMs: Long?,
    val tlsMs: Long?,
    val filterDownloadMs: Long?,
    val filterCacheHit: Boolean,
    val segmentFailures: List<SegmentFailure>,
    val segmentFailureOverflow: Int,
    val outcome: TraceOutcome?,
    val finishedAtMs: Long?,
    val nowMs: Long,
    /** 首帧渲染点（绝对时钟）；端到端 = 它 − T0，与首帧渲染段耗时不同。 */
    val firstFrameEndMs: Long? = null,
) {
    /** 端到端 = T0 → 首帧渲染；首帧未到则为 null（标“未完成”）。 */
    val totalMs: Long? get() = firstFrameEndMs?.let { (it - t0Ms).coerceAtLeast(0L) }

    /** 浮层只在已有数据时出现：任一段有耗时/缺失/失败即算有数据。 */
    val hasAnyMark: Boolean
        get() = durations.values.any { it != null } || missing.isNotEmpty() ||
            errorClasses.isNotEmpty() || segmentFailures.isNotEmpty()
}

/**
 * 一次播放尝试（一个内核的一次起播）。线程安全：mark 可能来自 OkHttp 网络线程、
 * Media3 回调线程与 mpv executor，内部统一加锁；先到先得，后到的重复打点忽略。
 */
class FirstScreenAttempt(
    val id: Long,
    val engine: String,
    val t0Ms: Long,
    val isLive: Boolean,
    private val clock: () -> Long = { 0L },
) {
    companion object {
        /** 30 秒诊断超时：只结束诊断，不影响播放（定稿 §5）。 */
        const val DIAGNOSTIC_TIMEOUT_MS = 30_000L

        /** 每 Attempt 最多记 5 条分片失败明细，超出只记总数（定稿 §5）。 */
        const val MAX_SEGMENT_FAILURE_LINES = 5
    }

    private val lock = Any()
    private val marks = LinkedHashMap<TraceStage, StageMark>()
    private var responseWaitMs: Long? = null
    private var firstByteMs: Long? = null
    private var tlsMs: Long? = null
    private var filterDownloadMs: Long? = null
    private var filterCacheHit: Boolean = false
    private val segmentFailures = mutableListOf<SegmentFailure>()
    private var segmentFailureOverflow: Int = 0
    private var failureRequestIndex: Int = 0
    private var loadStarted: Boolean = false
    private var mainCallClaimed: Boolean = false
    private var outcome: TraceOutcome? = null
    private var finishedAtMs: Long? = null

    /** 是否已终结（首帧到 / 失败 / 超时 / 取消）。 */
    val isFinished: Boolean get() = synchronized(lock) { outcome != null }

    /** 30 秒未出首帧渲染即超时（只由诊断胶水层定时检查，不自动触发）。 */
    fun isTimedOut(nowMs: Long = clock()): Boolean = synchronized(lock) {
        outcome == null && nowMs - t0Ms > DIAGNOSTIC_TIMEOUT_MS
    }

    fun markStart(stage: TraceStage, atMs: Long = clock()) {
        synchronized(lock) {
            if (outcome != null) return
            val cur = marks[stage] ?: StageMark()
            if (cur.startMs == null && cur.measuredMs == null) {
                marks[stage] = cur.copy(startMs = atMs, missing = null, errorClass = null)
            }
        }
    }

    fun markEnd(stage: TraceStage, atMs: Long = clock()) {
        synchronized(lock) {
            if (outcome != null) return
            val cur = marks[stage] ?: StageMark()
            if (cur.endMs == null && cur.measuredMs == null) {
                marks[stage] = cur.copy(endMs = atMs, missing = null, errorClass = null)
            }
        }
    }

    /**
     * 点事件（首帧解码 / 首帧渲染）：只记终点，起点在算耗时时取前序最近一段的
     * 终点，找不到取 T0。终点只记第一次。approximate=true 时浮层/日志标“（近似）”。
     */
    fun markPoint(stage: TraceStage, atMs: Long = clock(), approximate: Boolean = false) {
        synchronized(lock) {
            if (outcome != null) return
            val cur = marks[stage] ?: StageMark()
            if (cur.endMs == null && cur.measuredMs == null) {
                marks[stage] = cur.copy(endMs = atMs, missing = null, errorClass = null, approximate = approximate)
            }
        }
    }

    /** 直接给耗时（如解码器 initializationDurationMs、解析段实测耗时）。 */
    fun markMeasured(stage: TraceStage, ms: Long, approximate: Boolean = false) {
        synchronized(lock) {
            if (outcome != null) return
            val cur = marks[stage] ?: StageMark()
            if (cur.measuredMs == null) {
                marks[stage] = cur.copy(
                    measuredMs = ms.coerceAtLeast(0L),
                    missing = null,
                    errorClass = null,
                    approximate = approximate,
                )
            }
        }
    }

    /** 预解析命中：唯一允许的 0ms + 备注（两者同记，markMissing 单独调会被 measuredMs 挡住）。 */
    fun markPrefetchHit() {
        synchronized(lock) {
            if (outcome != null) return
            val cur = marks[TraceStage.RESOLVE] ?: StageMark()
            if (cur.measuredMs == null) {
                marks[TraceStage.RESOLVE] = cur.copy(measuredMs = 0L, missing = TraceMissing.PREFETCH_HIT)
            }
        }
    }

    fun markMissing(stage: TraceStage, missing: TraceMissing) {
        synchronized(lock) {
            if (outcome != null) return
            val cur = marks[stage] ?: StageMark()
            if (cur.startMs == null && cur.endMs == null && cur.measuredMs == null &&
                cur.missing == null && cur.errorClass == null
            ) {
                marks[stage] = cur.copy(missing = missing)
            }
        }
    }

    /** 失败：只记异常类名，不记完整 URL（定稿 §4）。 */
    fun markFailed(stage: TraceStage, errorClass: String) {
        synchronized(lock) {
            if (outcome != null) return
            val cur = marks[stage] ?: StageMark()
            if (cur.errorClass == null && cur.measuredMs == null) {
                marks[stage] = cur.copy(errorClass = errorClass, missing = null)
            }
        }
    }

    /** 开流子项：响应等待（请求发出 → 响应头开始）。 */
    fun noteResponseWait(ms: Long) {
        synchronized(lock) { if (responseWaitMs == null) responseWaitMs = ms.coerceAtLeast(0L) }
    }

    /** 开流子项：首字节（请求发出 → responseBodyStart）。 */
    fun noteFirstByte(ms: Long) {
        synchronized(lock) { if (firstByteMs == null) firstByteMs = ms.coerceAtLeast(0L) }
    }

    /** 建连子项：TLS 握手耗时（不单独立段，日志备注里另记）。 */
    fun noteTls(ms: Long) {
        synchronized(lock) { if (tlsMs == null) tlsMs = ms.coerceAtLeast(0L) }
    }

    /** mpv 过滤下载备注段：不进主段、不参与总计（定稿 §3.3）。 */
    fun noteFilterDownload(ms: Long, cacheHit: Boolean) {
        synchronized(lock) {
            if (filterDownloadMs == null) {
                filterDownloadMs = ms.coerceAtLeast(0L)
                filterCacheHit = cacheHit
            }
        }
    }

    /** 首个分片/清单加载开始：只第一次返回 true（开流结束与媒体准备起点只记首次）。 */
    fun noteLoadStarted(): Boolean = synchronized(lock) {
        if (loadStarted) false else {
            loadStarted = true
            true
        }
    }

    /** OkHttp 主请求认领：第一个创建的 EventListener 才是主清单请求。 */
    fun claimMainCall(): Boolean = synchronized(lock) {
        if (mainCallClaimed) false else {
            mainCallClaimed = true
            true
        }
    }

    /** HLS 分片失败明细：超 5 条只记总数；requestIndex 在本 Attempt 内递增。 */
    fun noteSegmentFailure(host: String, kind: SegmentKind, errorClass: String) {
        synchronized(lock) {
            if (outcome != null) return
            failureRequestIndex++
            if (segmentFailures.size < MAX_SEGMENT_FAILURE_LINES) {
                segmentFailures.add(SegmentFailure(host, kind, errorClass, failureRequestIndex))
            } else {
                segmentFailureOverflow++
            }
        }
    }

    /** 终结：幂等；未到段按终结原因补缺失态（超时→超时未完成，其余→未到该段）。 */
    fun finish(result: TraceOutcome, atMs: Long = clock()): AttemptSnapshot = synchronized(lock) {
        if (outcome == null) {
            outcome = result
            finishedAtMs = atMs
            val fill = if (result == TraceOutcome.TIMEOUT) TraceMissing.TIMEOUT else TraceMissing.NOT_REACHED
            for (stage in TraceStage.values()) {
                val cur = marks[stage]
                if (cur == null || (cur.startMs == null && cur.endMs == null && cur.measuredMs == null &&
                        cur.missing == null && cur.errorClass == null)
                ) {
                    marks[stage] = (cur ?: StageMark()).copy(missing = fill)
                }
            }
        }
        snapshotLocked()
    }

    fun snapshot(nowMs: Long = clock()): AttemptSnapshot = synchronized(lock) { snapshotLocked(nowMs) }

    private fun snapshotLocked(nowMs: Long = clock()): AttemptSnapshot {
        val durations = LinkedHashMap<TraceStage, Long?>()
        val missing = LinkedHashMap<TraceStage, TraceMissing>()
        val errorClasses = LinkedHashMap<TraceStage, String>()
        val approximateStages = mutableSetOf<TraceStage>()
        for (stage in TraceStage.values()) {
            val mark = marks[stage]
            durations[stage] = mark?.let { durationLocked(stage, it) }
            mark?.missing?.let { missing[stage] = it }
            mark?.errorClass?.let { errorClasses[stage] = it }
            if (mark?.approximate == true) approximateStages.add(stage)
        }
        return AttemptSnapshot(
            sessionId = -1L,
            attemptIndex = -1,
            engine = engine,
            isLive = isLive,
            t0Ms = t0Ms,
            durations = durations,
            missing = missing,
            errorClasses = errorClasses,
            approximateStages = approximateStages,
            responseWaitMs = responseWaitMs,
            firstByteMs = firstByteMs,
            tlsMs = tlsMs,
            filterDownloadMs = filterDownloadMs,
            filterCacheHit = filterCacheHit,
            segmentFailures = segmentFailures.toList(),
            segmentFailureOverflow = segmentFailureOverflow,
            outcome = outcome,
            finishedAtMs = finishedAtMs,
            nowMs = nowMs,
            firstFrameEndMs = marks[TraceStage.FIRST_FRAME]?.endMs,
        )
    }

    /**
     * 段耗时：measuredMs 优先；否则终点 − 起点；点事件段（首帧解码/首帧渲染）
     * 起点取前序最近一段的终点，找不到取 T0；任一端缺失则为 null。
     */
    private fun durationLocked(stage: TraceStage, mark: StageMark): Long? {
        mark.measuredMs?.let { return it }
        val end = mark.endMs ?: return null
        val start = mark.startMs ?: pointStartLocked(stage)
        return (end - start).coerceAtLeast(0L)
    }

    private fun pointStartLocked(stage: TraceStage): Long {
        val order = TraceStage.values()
        val idx = order.indexOf(stage)
        for (i in idx - 1 downTo 0) {
            marks[order[i]]?.endMs?.let { return it }
        }
        return t0Ms
    }
}

/** 一次播放会话：每次播放/重播/切集一个 Session，内核切换另建 Attempt。 */
class FirstScreenSession(
    val id: Long,
    val isLive: Boolean,
    pendingResolveMs: Long? = null,
    pendingPrefetchHit: Boolean = false,
    private val clock: () -> Long = { 0L },
) {
    private val attempts = mutableListOf<FirstScreenAttempt>()
    private var pendingResolveApplied: Boolean = false
    private val pendingResolveMs: Long? = pendingResolveMs
    private val pendingPrefetchHit: Boolean = pendingPrefetchHit

    val latestAttempt: FirstScreenAttempt? get() = synchronized(attempts) { attempts.lastOrNull() }
    val attemptCount: Int get() = synchronized(attempts) { attempts.size }

    fun newAttempt(engine: String, t0Ms: Long, attemptId: Long): FirstScreenAttempt {
        val attempt = FirstScreenAttempt(attemptId, engine, t0Ms, isLive, clock)
        synchronized(attempts) { attempts.add(attempt) }
        // 解析段只在首个 Attempt 上记：预解析命中记 0ms + 备注；实测解析记实测耗时；
        // 直播无解析段，记未到该段；已解析地址进入的会话不经过解析，不记。
        if (!pendingResolveApplied) {
            pendingResolveApplied = true
            when {
                pendingPrefetchHit -> attempt.markPrefetchHit()
                pendingResolveMs != null -> attempt.markMeasured(TraceStage.RESOLVE, pendingResolveMs)
                isLive -> attempt.markMissing(TraceStage.RESOLVE, TraceMissing.NOT_REACHED)
            }
        } else if (isLive) {
            attempt.markMissing(TraceStage.RESOLVE, TraceMissing.NOT_REACHED)
        }
        return attempt
    }

    fun snapshotLatest(nowMs: Long = clock()): AttemptSnapshot? {
        val attempt = latestAttempt ?: return null
        val snap = attempt.snapshot(nowMs)
        return snap.copy(sessionId = id, attemptIndex = attempts.indexOf(attempt) + 1)
    }
}

/**
 * HLS 请求分类：dataType 优先（Media3 C.DATA_TYPE_MANIFEST=4、DATA_TYPE_DRM=3），
 * 未知时按 URI 后缀分（.m3u8→主清单，.key→密钥，其余→分片）。
 */
fun classifySegmentLoad(uri: String, dataType: Int?): SegmentKind {
    if (dataType == 4) return SegmentKind.MANIFEST
    if (dataType == 3) return SegmentKind.KEY
    val path = uri.substringBefore("?").lowercase()
    return when {
        path.endsWith(".m3u8") -> SegmentKind.MANIFEST
        path.endsWith(".key") -> SegmentKind.KEY
        else -> SegmentKind.SEGMENT
    }
}

/** 段值文本：耗时 / 失败（类名）/ 缺失态词；近似段加“（近似）”。 */
fun formatStageValue(snapshot: AttemptSnapshot, stage: TraceStage): String {
    snapshot.durations[stage]?.let { ms ->
        val approx = if (stage in snapshot.approximateStages) "（近似）" else ""
        val prefetch = if (stage == TraceStage.RESOLVE &&
            snapshot.missing[stage] == TraceMissing.PREFETCH_HIT
        ) "（预解析命中）" else ""
        return "${ms}ms$approx$prefetch"
    }
    snapshot.errorClasses[stage]?.let { return "失败（$it）" }
    snapshot.missing[stage]?.let { return it.label }
    return TraceMissing.NOT_REACHED.label
}

/** 汇总行（定稿 §7.1 格式），只在 Attempt 终结时拼一次。 */
fun formatLogLine(snapshot: AttemptSnapshot): String {
    val parts = StringBuilder()
    parts.append("首屏诊断 会话#${snapshot.sessionId} 尝试#${snapshot.attemptIndex} ")
    parts.append(if (snapshot.isLive) "直播 " else "点播 ")
    parts.append(snapshot.engine)
    for (stage in TraceStage.values()) {
        parts.append(" ${stage.logLabel}=${formatStageValue(snapshot, stage)}")
    }
    val total = snapshot.totalMs
    parts.append(" 总计=${if (total == null) "未完成" else "${total}ms"}")
    snapshot.tlsMs?.let { parts.append("（TLS=${it}ms）") }
    snapshot.filterDownloadMs?.let {
        parts.append("（过滤下载=${it}ms${if (snapshot.filterCacheHit) "，缓存命中" else ""}）")
    }
    when (snapshot.outcome) {
        TraceOutcome.FAILED -> parts.append(" 失败")
        TraceOutcome.TIMEOUT -> parts.append(" 诊断超时")
        TraceOutcome.CANCELLED -> parts.append(" 取消")
        else -> Unit
    }
    return parts.toString()
}

/** 分片失败明细行（每 Attempt 最多 5 条，超出只记总数）。 */
fun formatSegmentFailureLines(snapshot: AttemptSnapshot): List<String> {
    val lines = snapshot.segmentFailures.map { failure ->
        "首屏诊断 会话#${snapshot.sessionId} 尝试#${snapshot.attemptIndex} " +
            "分片失败#${failure.requestIndex} ${failure.host} ${failure.kind.label} ${failure.errorClass}"
    }
    if (snapshot.segmentFailureOverflow > 0) {
        return lines + ("首屏诊断 会话#${snapshot.sessionId} 尝试#${snapshot.attemptIndex} " +
            "分片失败另有${snapshot.segmentFailureOverflow}条未列出")
    }
    return lines
}

/** 浮层行：首行 + 每段一行 + 总计行 + 失败段/备注行（定稿 §7.2 最小集）。 */
fun formatOverlayLines(snapshot: AttemptSnapshot): List<String> {
    val lines = mutableListOf<String>()
    lines.add("首屏诊断 · ${if (snapshot.isLive) "直播" else "点播"} · ${snapshot.engine}")
    for (stage in TraceStage.values()) {
        lines.add("${stage.label}：${formatStageValue(snapshot, stage)}")
        if (stage == TraceStage.CONNECT) {
            snapshot.tlsMs?.let { lines.add("TLS：${it}ms") }
        }
    }
    val total = snapshot.totalMs
    lines.add("总计：${if (total == null) "未完成" else "${total}ms"}")
    snapshot.filterDownloadMs?.let {
        lines.add("过滤下载：${it}ms${if (snapshot.filterCacheHit) "（缓存命中）" else ""}")
    }
    if (snapshot.segmentFailures.isNotEmpty()) {
        val first = snapshot.segmentFailures.first()
        lines.add("分片失败：${first.host} ${first.kind.label} ${first.errorClass}")
        if (snapshot.segmentFailures.size > 1 || snapshot.segmentFailureOverflow > 0) {
            lines.add("另有${snapshot.segmentFailures.size - 1 + snapshot.segmentFailureOverflow}条分片失败")
        }
    }
    return lines
}
