package com.movieshell.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.StoragePaths
import com.movieshell.data.repo.SubtitleStyle
import dev.jdtech.mpv.MPVLib
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * libmpv 播放器封装（v0.20.0：可选第二引擎）。
 *
 * 基于 Maven Central 预编译 AAR `dev.jdtech.mpv:libmpv:0.5.1`（静态单例 API，
 * 内嵌 mpv v0.40.0），无需 NDK 自行编译；minSdk 26 与本项目一致。
 *
 * - 所有 MPVLib 调用都在单线程 executor 上串行执行（native 事件回调也排到该线程），
 *   避免并发与主线程阻塞；
 * - 进度/时长通过 observeProperty 缓存到 volatile 变量，UI 只读缓存、不做 JNI 轮询；
 * - 失败判定：MPV_EVENT_END_FILE 到达时读 `eof-reached`，非自然播完即视为失败，
 *   走与 Media3 统一的 onError 回调（播放器内的自动换线路重试复用同一链路）；
 * - 请求头（Referer 等）与 User-Agent、断点续播、外挂字幕都走 loadfile 的
 *   per-file options（mpv v0.40 签名：loadfile <url> <flags> <index> <options>），
 *   每次播放独立设置，不会泄漏到下一次播放。
 */
object MpvPlayerManager {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mpv-cmd").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val initialized = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)

    /**
     * v0.25.0：libmpv 初始化是否失败过（native 库缺失等）。
     * 一旦失败，本进程内视为该引擎不可用，EngineHost.play 直接回退 Media3，不再尝试。
     */
    private val initFailedFlag = AtomicBoolean(false)
    val initFailed: Boolean get() = initFailedFlag.get()

    @Volatile private var attachedSurface: Surface? = null
    @Volatile private var surfaceW: Int = 0
    @Volatile private var surfaceH: Int = 0
    @Volatile private var lastPositionMs: Long = 0L
    @Volatile private var lastDurationMs: Long = 0L
    @Volatile private var paused = true
    /** 手动 stop()/loadfile 后的 END_FILE 误报抑制窗口（毫秒时间戳） */
    @Volatile private var suppressErrorUntil: Long = 0L

    @Volatile
    private var errorListener: ((String) -> Unit)? = null

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {}
        override fun eventProperty(property: String, value: Long) {}
        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "time-pos" -> lastPositionMs = (value * 1000).toLong().coerceAtLeast(0L)
                "duration" -> lastDurationMs = (value * 1000).toLong().coerceAtLeast(0L)
            }
        }
        override fun eventProperty(property: String, value: Boolean) {
            if (property == "pause") paused = value
        }
        override fun eventProperty(property: String, value: String) {}
        override fun event(eventId: Int) {
            // native 事件线程 → 排到 executor 串行处理，保证 eof-reached 读取时序
            if (eventId == MPVLib.MPV_EVENT_END_FILE && !destroyed.get()) {
                runCatching { executor.execute { handleEndFile() } }
            }
        }
    }

    /**
     * 幂等初始化。重操作全在后台线程；init 与 play 都排在同一 executor，
     * 先调 init 再调 play 即保证顺序（PlayerScreen/EngineHost 均如此调用）。
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized.get() || destroyed.get()) return
        val app = context.applicationContext
        executor.execute {
            if (!initialized.compareAndSet(false, true)) return@execute
            try {
                MPVLib.create(app)
                MPVLib.setOptionString("vo", "gpu")
                MPVLib.setOptionString("hwdec", "mediacodec")
                MPVLib.setOptionString("ao", "audiotrack")
                MPVLib.setOptionString("osd-level", "0")
                MPVLib.setOptionString("input-default-bindings", "no")
                MPVLib.setOptionString("idle", "yes")
                MPVLib.setOptionString("cache", "yes")
                MPVLib.setOptionString("demuxer-readahead-secs", "30")
                MPVLib.init()
                MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE)
                MPVLib.observeProperty("duration", MPVLib.MPV_FORMAT_DOUBLE)
                MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
                MPVLib.addObserver(observer)
                attachedSurface?.let { MPVLib.attachSurface(it) }
                LogBus.i("MpvPlayer", "libmpv 初始化完成")
            } catch (t: Throwable) {
                initialized.set(false)
                initFailedFlag.set(true)
                LogBus.e("MpvPlayer", "libmpv 初始化失败：${t.message}")
            }
        }
    }

    /** MpvVideoView surface 就绪/销毁时调用 */
    fun onSurfaceReady(surface: Surface) {
        attachedSurface = surface
        executor.execute {
            if (initialized.get() && !destroyed.get()) {
                runCatching { MPVLib.attachSurface(surface) }
                    .onFailure { LogBus.w("MpvPlayer", "attachSurface 失败：${it.message}") }
            }
        }
    }

    fun onSurfaceLost() {
        attachedSurface = null
        executor.execute {
            if (initialized.get() && !destroyed.get()) {
                runCatching { MPVLib.detachSurface() }
                    .onFailure { LogBus.w("MpvPlayer", "detachSurface 失败：${it.message}") }
            }
        }
    }

    fun onSurfaceSize(width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
    }

    /**
     * 播放 [url]。headers 中的 "User-Agent" 走独立的 user-agent 选项
     * （UA 常含逗号，不能塞进 http-header-fields 的逗号分隔列表）。
     */
    fun play(
        url: String,
        headers: Map<String, String> = emptyMap(),
        startPositionMs: Long = 0L,
        subtitleUrl: String? = null,
    ) {
        if (url.isBlank()) {
            LogBus.e("MpvPlayer", "播放地址为空，已拒绝")
            return
        }
        LogBus.i("MpvPlayer", "播放: ${url.take(120)}${if (url.length > 120) "..." else ""}")
        suppressErrorUntil = System.currentTimeMillis() + 2000
        lastPositionMs = 0L
        lastDurationMs = 0L
        executor.execute {
            if (!initialized.get() || destroyed.get()) {
                mainHandler.post { errorListener?.invoke("libmpv 初始化失败，请切回 Media3") }
                return@execute
            }
            runCatching {
                MPVLib.command(
                    arrayOf("loadfile", url, "replace", "-1", buildFileOptions(headers, startPositionMs, subtitleUrl))
                )
                MPVLib.setPropertyBoolean("pause", false)
            }.onFailure { LogBus.e("MpvPlayer", "loadfile 失败：${it.message}") }
        }
    }

    fun pause() {
        executor.execute {
            runCatching { MPVLib.setPropertyBoolean("pause", true) }
                .onFailure { LogBus.w("MpvPlayer", "pause 失败：${it.message}") }
        }
    }

    fun resume() {
        executor.execute {
            runCatching { MPVLib.setPropertyBoolean("pause", false) }
                .onFailure { LogBus.w("MpvPlayer", "resume 失败：${it.message}") }
        }
    }

    fun seekTo(ms: Long) {
        executor.execute {
            runCatching {
                MPVLib.command(arrayOf("seek", (ms / 1000.0).toString(), "absolute"))
            }.onFailure { LogBus.w("MpvPlayer", "seek 失败：${it.message}") }
        }
    }

    fun stop() {
        suppressErrorUntil = System.currentTimeMillis() + 2000
        paused = true
        executor.execute {
            runCatching { MPVLib.command(arrayOf("stop")) }
                .onFailure { LogBus.w("MpvPlayer", "stop 失败：${it.message}") }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        executor.execute {
            runCatching { MPVLib.setPropertyDouble("speed", speed.toDouble()) }
                .onFailure { LogBus.w("MpvPlayer", "倍速设置失败：${it.message}") }
        }
    }

    /**
     * 画面比例：0=适应（保持原始比例），1=填充（拉伸填满），2=裁剪（等比裁剪填满），
     * 与播放器界面「适应/填充/裁剪」三档对应。
     */
    fun setAspectMode(mode: Int) {
        executor.execute {
            if (!initialized.get() || destroyed.get()) return@execute
            runCatching {
                when (mode) {
                    1 -> {
                        val w = surfaceW
                        val h = surfaceH
                        val aspect = if (w > 0 && h > 0) w.toDouble() / h else 16.0 / 9.0
                        // 必须用 US locale：小数点在某些语言环境下是逗号，mpv 解析会错
                        MPVLib.setPropertyString("video-aspect-override", "%.4f".format(Locale.US, aspect))
                        MPVLib.setPropertyDouble("panscan", 0.0)
                    }
                    2 -> {
                        MPVLib.setPropertyString("video-aspect-override", "-1")
                        MPVLib.setPropertyDouble("panscan", 1.0)
                    }
                    else -> {
                        MPVLib.setPropertyString("video-aspect-override", "-1")
                        MPVLib.setPropertyDouble("panscan", 0.0)
                    }
                }
            }.onFailure { LogBus.w("MpvPlayer", "画面比例设置失败：${it.message}") }
        }
    }

    /** 当前进度/时长（observeProperty 缓存，UI 线程直接读） */
    val currentPosition: Long get() = lastPositionMs
    val duration: Long get() = lastDurationMs

    /** 是否正在播放（供按 Home 自动进画中画判断） */
    val isPlaying: Boolean get() = initialized.get() && !destroyed.get() && !paused

    /** 设置播放错误回调，传 null 取消 */
    fun setOnErrorListener(listener: ((String) -> Unit)?) {
        errorListener = listener
    }

    /**
     * v0.21.0：字幕样式（选项名以 mpv stable 手册为准，行为以源码 sd_ass.c 核对）。
     * 大小按 16sp=基准线性映射；颜色 #AARRGGBB；描边/阴影二选一；
     * 位置 = sub-align-y（top/bottom 对齐）+ sub-pos=100（mpv 源码里 100-sub_pos 传给
     * libass，100=该对齐方式的默认位置）。
     * 注：原生 ASS 字幕的位置/对齐只在 --sub-ass-override=force 时被覆盖，
     * 默认跟字幕脚本自带排版（srt/vtt 转 ASS 的则全量生效）。
     */
    fun applySubtitleStyle(style: SubtitleStyle) {
        executor.execute {
            if (!initialized.get() || destroyed.get()) return@execute
            runCatching {
                MPVLib.setPropertyDouble("sub-font-size", style.sizeSp * 3.0)
                MPVLib.setPropertyString("sub-color", mpvColor(style.color))
                when (style.edge) {
                    SubtitleStyle.EDGE_OUTLINE -> {
                        MPVLib.setPropertyDouble("sub-border-size", 3.0)
                        MPVLib.setPropertyDouble("sub-shadow-offset", 0.0)
                        MPVLib.setPropertyString("sub-border-color", "#FF000000")
                    }
                    SubtitleStyle.EDGE_SHADOW -> {
                        MPVLib.setPropertyDouble("sub-border-size", 0.0)
                        MPVLib.setPropertyDouble("sub-shadow-offset", 3.0)
                    }
                    else -> {
                        MPVLib.setPropertyDouble("sub-border-size", 0.0)
                        MPVLib.setPropertyDouble("sub-shadow-offset", 0.0)
                    }
                }
                MPVLib.setPropertyString(
                    "sub-align-y",
                    if (style.position == SubtitleStyle.POS_TOP) "top" else "bottom",
                )
                MPVLib.setPropertyDouble("sub-pos", 100.0)
                // 注：时间偏移不在这里设——SubtitleKit.prepare 已把偏移 bake 进字幕文件，双引擎统一
            }.onFailure { LogBus.w("MpvPlayer", "字幕样式设置失败：${it.message}") }
        }
    }

    /**
     * v0.21.0：截图到 xiaying/Screenshots（mpv screenshot-to-file 命令，同步落盘）。
     * 返回保存的文件，失败返回 null。调用方在 IO 线程调（命令本身线程安全）。
     */
    fun screenshot(context: Context, title: String): File? {
        if (!initialized.get() || destroyed.get()) return null
        return runCatching {
            val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40).ifBlank { "截图" }
            val file = File(
                StoragePaths.screenshotsDir(context),
                "侠影_${safe}_${System.currentTimeMillis()}.png",
            )
            MPVLib.command(arrayOf("screenshot-to-file", file.absolutePath))
            if (file.exists() && file.length() > 0) {
                LogBus.i("MpvPlayer", "截图已保存：${file.absolutePath}")
                file
            } else null
        }.getOrElse {
            LogBus.w("MpvPlayer", "截图失败：${it.message}")
            null
        }
    }

    private fun mpvColor(argb: Int): String =
        "#%02X%02X%02X%02X".format(
            (argb ushr 24) and 0xFF,
            (argb ushr 16) and 0xFF,
            (argb ushr 8) and 0xFF,
            argb and 0xFF,
        )

    /** 释放；只执行一次，应用真正退出时调用 */
    @Synchronized
    fun release() {
        if (!destroyed.compareAndSet(false, true)) return
        executor.execute {
            runCatching {
                MPVLib.removeObserver(observer)
                runCatching { MPVLib.detachSurface() }
                MPVLib.destroy()
                LogBus.i("MpvPlayer", "libmpv 已释放")
            }.onFailure { LogBus.w("MpvPlayer", "libmpv 释放异常：${it.message}") }
            executor.shutdown()
        }
    }

    /**
     * END_FILE 处理：自然播完（eof-reached=true）不做任何事；
     * 异常结束则回调 onError（调用方走自动换线路重试）。
     * 手动 stop()/loadfile 触发的 END_FILE 在抑制窗口内，直接忽略。
     */
    private fun handleEndFile() {
        if (System.currentTimeMillis() < suppressErrorUntil) return
        val eof = runCatching { MPVLib.getPropertyBoolean("eof-reached") }.getOrNull() == true
        if (!eof) {
            LogBus.w("MpvPlayer", "播放异常结束（非自然播完），触发错误回调")
            mainHandler.post { errorListener?.invoke("libmpv 播放失败") }
        }
    }

    /**
     * 组装 loadfile 的 per-file options：
     * start（每次显式设置，否则 mpv 会沿用上一次的值）、user-agent、
     * http-header-fields、外挂字幕。含逗号的值用 mpv 的 %<utf8字节数>%<原文> 转义。
     */
    private fun buildFileOptions(
        headers: Map<String, String>,
        startPositionMs: Long,
        subtitleUrl: String?,
    ): String {
        val opts = ArrayList<String>()
        opts += if (startPositionMs > 0) "start=${startPositionMs / 1000.0}" else "start=none"
        val rest = headers.toMutableMap()
        rest.remove("User-Agent")?.takeIf { it.isNotBlank() }?.let { ua ->
            opts += "user-agent=${q(ua)}"
        }
        if (rest.isNotEmpty()) {
            val fields = rest.entries.joinToString(",") { (k, v) -> "$k: $v" }
            opts += "http-header-fields=${q(fields)}"
        }
        subtitleUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { sub ->
            opts += "sub-file=${q(sub)}"
            opts += "sub-visibility=yes"
        }
        return opts.joinToString(",")
    }

    private fun q(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return "%${bytes.size}%$value"
    }
}
