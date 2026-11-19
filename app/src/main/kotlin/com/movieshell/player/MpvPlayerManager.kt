package com.movieshell.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.movieshell.core.log.LogBus
import com.movieshell.core.util.StoragePaths
import com.movieshell.data.store.PlayerSettings
import com.movieshell.domain.model.SubtitleStyle
import dev.jdtech.mpv.MPVLib
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    private var appContext: Context? = null

    /**
     * v0.25.0：libmpv 初始化是否失败过（native 库缺失等）。
     * 一旦失败，本进程内视为该引擎不可用，EngineHost.play 直接回退 Media3，不再尝试。
     */
    private val initFailedFlag = AtomicBoolean(false)
    val initFailed: Boolean get() = initFailedFlag.get()
    // v1.1.31：初始化失败是否因为插件没装——是才允许装完后重试；原生加载失败等其他原因仍 sticky
    private val initFailedMissingPluginFlag = AtomicBoolean(false)
    val initFailedMissingPlugin: Boolean get() = initFailedMissingPluginFlag.get()

    @Volatile private var attachedSurface: Surface? = null
    @Volatile private var surfaceW: Int = 0
    @Volatile private var surfaceH: Int = 0
    /** v0.208.0：最近一次画面档位（-1=未设置过）；拉伸档依赖容器尺寸，surface 尺寸变化时需重算。 */
    @Volatile private var currentAspectMode: Int = -1
    @Volatile private var lastPositionMs: Long = 0L
    // v1.3.25 审查修（TeleAgent L2）：本次起播时 mpv 日志长度基线——mpvLogTail
    // 只读这之后的新内容，旧集的解码失败行不进下一集的判定。
    @Volatile private var logTailBaselineBytes: Long = 0L
    @Volatile private var lastDurationMs: Long = 0L
    @Volatile private var paused = true
    /** v0.152.0：失败诊断用——最近一次起播的 host/后缀/起播时刻（jdtech MPVLib 的
     *  END_FILE 回调只给事件 id、不给原因，mpv 内部错误串拿不到，只能用这些
     *  已观测到的量拼失败画像：未出画面 vs 播一段后中断、起播后多久断）。 */
    @Volatile private var lastPlayHost: String = ""
    @Volatile private var lastPlayExt: String = ""
    @Volatile private var lastPlayStartMs: Long = 0L
    /**
     * v0.107.0 P0-3：我们自己 stop()/loadfile 会产生一次 END_FILE，用计数显式忽略，
     * 替代原来的 2 秒时间窗口——时间窗口会把 404/拒连的快速失败也吞掉，errorListener 永不触发。
     * 用计数而非布尔：快速连播两次会产生两次 END_FILE。消费只发生在单线程 executor 上，
     * 主线程只做 increment/decrement，AtomicInteger 足够，无需 CAS 循环。
     */
    private val expectEndFileCount = AtomicInteger(0)
    /**
     * v1.3.17：起播代次——stop() 每次 +1；play() 在 executor 里真正
     * loadfile 前核对代次，代次变了说明这次起播是停播前排队的旧任务
     * （MpvAdFilter 下载 m3u8 会堵住单线程 executor 数秒，stop 命令
     * 排在后面，旧 loadfile 会在 stop 之后才执行、把已停的播又拉起来）。
     */
    @Volatile private var playGen = 0
    /**
     * M35：起播请求序号——每次 play() +1；下载线程与 executor 任务执行前
     * 都核对自己仍是最新一次起播，否则直接丢弃。playGen 只防 stop 后的
     * 旧任务，不防“用户又点了一集”的新任务，两者并存。
     */
    private val playSeq = AtomicInteger(0)
    /**
     * M31：过滤下载串行锁——下载已移出 mpv executor（见 play()），但
     * MpvAdFilter 的缓存写盘非原子，并发下载同一地址会互相截断；下载
     * 线程之间仍用这把锁串行，stop/pause/seek 不走这把锁、不被堵。
     */
    private val filterLock = Any()
    /** M29：本次播放是否见过展开成多条的播放列表（末段 END_FILE 判定用）。 */
    @Volatile private var sawExpandedPlaylist = false
    /** M32：最近一次字幕样式——init 完成时重放一次（见 init()）。 */
    @Volatile private var lastSubtitleStyle: SubtitleStyle? = null
    /** v1.3.25 合并 5：Anime4K 着色器是否已从 assets 拷到 filesDir（只拷一次）。 */
    @Volatile private var anime4kInstalled = false
    /** 是否有已加载的文件（首播前不计忽略，避免把真正的失败吞掉） */
    @Volatile var hasFile = false
        private set
    /**
     * v1.3.21：下载进行中（M31 后下载在独立线程）——此期间点的 seek 先记
     * 下，loadfile 成功后补发；否则 seek 落在空闲 mpv 上直接丢了。
     * 只有最新一单下载拥有它（序号比对），被新起播顶掉时不乱清。
     */
    @Volatile private var downloadInFlight = false
    /** 下载窗口内点的 seek（毫秒），loadfile 成功后补发；stop() 时清掉。 */
    @Volatile private var pendingSeekMs: Long? = null
    @Volatile
    private var errorListener: ((String) -> Unit)? = null

    /**
     * v1.3.21：展开检测的错误标记——本地 m3u8 被当成播放列表展开且进度
     * 不动时，诊断线程以此外加错误回调，PlayerScreen 见标记直接跳过
     * 同内核重拉/清 Referer（再试只会再展开一次），直奔换内核。
     * 标记只作链路内识别，落进日志前由调用方剥掉。
     */
    internal const val PLAYLIST_EXPANDED_MARKER = "playlist-expanded"

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {}
        override fun eventProperty(property: String, value: Long) {
            // v1.3.25 合并 7：aid 变了 = 音轨切了（含 mpv 自己按默认选轨），
            // 重新读 track-list 更新齿轮页的选中态。
            if (property == "aid") runCatching { executor.execute { refreshAudioTracks() } }
        }
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
            // v1.3.25 合并 7：文件载入后轨道表才完整，读 track-list 推给 UI。
            if (eventId == MPVLib.MPV_EVENT_FILE_LOADED && !destroyed.get()) {
                runCatching { executor.execute { refreshAudioTracks() } }
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
        appContext = app
        executor.execute {
            if (!initialized.compareAndSet(false, true)) return@execute
            try {
                // v0.29.0：libmpv 已插件化，先加载插件的 .so；未安装抛异常走下面的
                // initFailed 兜底（EngineHost 回退 Media3）。
                if (!MpvPluginLoader.ensureLoaded(app)) {
                    throw IllegalStateException("libmpv 插件未安装")
                }
                MPVLib.create(app)
                MPVLib.setOptionString("vo", "gpu")
                MPVLib.setOptionString("hwdec", "mediacodec")
                MPVLib.setOptionString("ao", "audiotrack")
                MPVLib.setOptionString("osd-level", "0")
                MPVLib.setOptionString("input-default-bindings", "no")
                MPVLib.setOptionString("idle", "yes")
                MPVLib.setOptionString("cache", "yes")
                MPVLib.setOptionString("demuxer-readahead-secs", "30")
                // v1.3.16：本地过滤后 m3u8 用 file 协议打开时，ffmpeg 的
                // protocol_whitelist 只剩 file,crypto,data，https 分片全被拒
                // （1.3.15 真机 mpv 日志实证：分片逐个 Failed to open）。
                // 显式放开，demuxer 与 stream 两层都设——分片由 HLS 解复用器
                // 经 demuxer 的 AVFormatContext 打开，stream 层是兜底。
                MPVLib.setOptionString("demuxer-lavf-o", "protocol_whitelist=" + MPV_PROTOCOL_WHITELIST)
                MPVLib.setOptionString("stream-lavf-o", "protocol_whitelist=" + MPV_PROTOCOL_WHITELIST)
                // L30：把 lavf 认分线从默认 26 放宽到 25。注意这只是无害的
                // 门槛放宽，不解决当前问题——1.3.19 已证实分片问题与认分线
                // 无关（probe 根本不认干净的本地文件），真正绕过靠 play()
                // 里 demuxer-lavf-format 强制 + 展开诊断兜底；保留该选项
                // 只因无害，不动解复用器顺序。
                MPVLib.setOptionString("demuxer-lavf-probescore", MPV_LAVF_PROBESCORE)
                // v1.2.2：RTSP 走 TCP 传输——运营商 IPTV 的 UDP 在手机网下常被拦，
                // TCP 更稳；只影响 RTSP 解复用，其他流不动。
                MPVLib.setOptionString("rtsp-transport", "tcp")
                // v1.3.15：mpv 自己的日志落文件——END_FILE 回调不给原因，失败黑盒；
                // 失败时把末尾打进 LogBus 才能定根因。写文件失败不影响播放。
                runCatching {
                    val logFile = File(app.filesDir, "mpv/mpv.log")
                    logFile.parentFile?.mkdirs()
                    // L26：mpv 手册写明 log-file 启动时截断旧文件，删一次是
                    // 廉价双保险——init 没跑完就崩时也不留巨型日志。
                    runCatching { if (logFile.exists()) logFile.delete() }
                    MPVLib.setOptionString("log-file", logFile.absolutePath)
                }.onFailure { LogBus.w("MpvPlayer", "mpv 日志文件设置失败：" + (it.message ?: "")) }
                MPVLib.init()
                MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE)
                MPVLib.observeProperty("duration", MPVLib.MPV_FORMAT_DOUBLE)
                MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
                // v1.3.25 合并 7：监听 aid，音轨切换后刷新选中态（见 observer）。
                MPVLib.observeProperty("aid", MPVLib.MPV_FORMAT_INT64)
                MPVLib.addObserver(observer)
                // M38：初始化时画面可能已在排队期间销毁——直接 attach 会抛
                // 异常进 catch、把 initFailed 粘滞成永久不可用。改走
                // bindSurface（内部全程 runCatching，只记日志），失败留给
                // 后续 onSurfaceReady 重绑，不标记初始化失败。
                attachedSurface?.let { bindSurface(it) }
                // M32：init 完成前调 setAspectMode/applySubtitleStyle 会被
                // execIfReady 静默丢掉——初始化一完成就把当前档位与字幕
                // 样式重放一次（仍排 executor，不与 init 任务抢顺序）。
                if (currentAspectMode >= 0) setAspectMode(currentAspectMode)
                lastSubtitleStyle?.let { applySubtitleStyle(it) }
                // v1.3.25 合并 5：init 完成即按当前档位挂 Anime4K（M32 同款重放；
                // applyAnime4K 内部排 executor，不与 init 任务抢顺序）。
                applyAnime4K(Anime4K.fromKey(PlayerSettings.anime4kMode.value))
                LogBus.i("MpvPlayer", "libmpv 初始化完成")
            } catch (t: Throwable) {
                initialized.set(false)
                initFailedFlag.set(true)
                // v1.3.21 M33：可恢复性按加载异常定，不按文件在不在定——缺插件
                // （ensureLoaded 抛错）或 .so 加载失败（UnsatisfiedLinkError
                // 一类，含损坏/架构不对）都算可恢复，用户补装/重装插件后
                // EngineHost 重试；其他初始化失败仍粘滞。
                initFailedMissingPluginFlag.set(isPluginLoadFailure(t))
                LogBus.e("MpvPlayer", "libmpv 初始化失败：${t.message}")
            }
        }
    }

    /**
     * v1.3.21 M33：这次初始化失败是不是插件加载失败。缺插件与 .so 损坏都
     * 走加载异常（ensureLoaded 抛 IllegalStateException、System.load 抛
     * UnsatisfiedLinkError）；选项/观察者等其他失败不算。
     */
    private fun isPluginLoadFailure(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is LinkageError) return true
            if (cur is IllegalStateException && cur.message?.contains("插件未安装") == true) return true
            cur = cur.cause
        }
        return false
    }

    /**
     * v1.1.31：起播失败后用户才装上插件——清掉失败标记，让下次起播重试初始化。
     * 只由 EngineHost 在"缺插件导致失败、且现在已装上"时调用；其他原因的失败仍保持
     * sticky，不反复重试。
     */
    fun clearInitFailed() {
        initFailedFlag.set(false)
        initFailedMissingPluginFlag.set(false)
    }

    /**
     * v0.294.41：libmpv 还没初始化（插件未加载）时不碰 MPVLib——否则类初始化里 loadLibrary 失败，
     * 这个类会永久处于错误状态，之后真正初始化也报 NoClassDefFoundError（日志里的「初始化失败：dev.jdtech.mpv.MPVLib」）。
     */
    private fun execIfReady(block: () -> Unit) {
        if (!initialized.get() || destroyed.get()) return
        executor.execute { if (initialized.get() && !destroyed.get()) block() }
    }

    /**
     * 把画面绑到指定 Surface（先卸旧再挂新，避免全屏/小窗切换后 vo 仍指着已销毁的旧面）。
     * M36：返回是否绑成功——失败不再只记日志，调用方（onSurfaceReady）可重试并回调
     * errorListener，否则黑屏有声、用户无感知、无重试入口。
     */
    private fun bindSurface(surface: Surface): Boolean {
        runCatching {
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.detachSurface()
        }
        return runCatching {
            MPVLib.attachSurface(surface)
            MPVLib.setOptionString("force-window", "yes")
            MPVLib.setPropertyString("vo", "gpu")
            if (surfaceW > 0 && surfaceH > 0) MPVLib.setPropertyString("android-surface-size", "${surfaceW}x$surfaceH")
        }.onFailure { LogBus.w("MpvPlayer", "attachSurface 失败：${it.message}") }.isSuccess
    }

    /** v0.294.43：返回小窗后每秒记录一次播放状态，共 5 次。M26：诊断任务带代次与
     * 地址快照——stop/切集后旧任务不再归属本次播放；每次读数前复核代次与 hasFile，
     * 读数本身仍排进 executor（不直接调 MPVLib）。 */
    fun diagnose(tag: String) {
        if (!initialized.get() || !hasFile) return
        val gen = playGen
        val host = lastPlayHost
        Thread({
            repeat(5) { i ->
                Thread.sleep(1000)
                if (gen != playGen || !initialized.get() || destroyed.get() || !hasFile) return@Thread
                executor.execute {
                    if (gen != playGen || !hasFile) return@execute
                    val info = runCatching {
                        "pause=${MPVLib.getPropertyBoolean("pause")} time=${MPVLib.getPropertyDouble("time-pos")} " +
                            "vo=${MPVLib.getPropertyString("vo-configured")} idle=${MPVLib.getPropertyBoolean("core-idle")} " +
                            "surface=${attachedSurface?.isValid} hasFile=$hasFile"
                    }.getOrElse { "读取失败：${it.message}" }
                    LogBus.i("MpvPlayer", "$tag 诊断 ${i + 1}/5 [$host]：$info")
                }
            }
        }, "mpv-diag").also { it.isDaemon = true }.start()
    }

    /** MpvVideoView surface 就绪/销毁时调用 */
    fun onSurfaceReady(surface: Surface) {
        attachedSurface = surface
        executor.execute {
            if (!initialized.get() || destroyed.get()) return@execute
            // 可能在排队期间又换了更新的画面——只绑当前这一块
            if (attachedSurface !== surface) return@execute
            // M36：绑不上只记日志等于黑屏有声——失败先重试一次，仍失败
            // 才回调 errorListener，让失败可见、可进换内核重试链。
            val bound = bindSurface(surface) || bindSurface(surface)
            if (bound) {
                LogBus.i("MpvPlayer", "画面已接到新的视图")
            } else {
                mainHandler.post { errorListener?.invoke("画面绑定失败（libmpv attachSurface），请重试或切回 Media3") }
            }
        }
    }

    /**
     * v0.294.40：跳全屏页时新页面的画面先建好、旧小窗的画面后销毁——原来旧画面销毁时无条件 detach，
     * 把刚接到全屏页的画面也摘掉了（全屏黑屏只有声音）。只有销毁的正是当前接着的那块画面才摘。
     * v0.294.42：旧面销毁时即便「不摘」，也强制把 vo 重新绑到幸存画面——否则 EGL/原生窗
     * 随旧 SurfaceView 一起没了，日志写着接到了新视图，小窗却是黑的。
     */
    fun onSurfaceLost(surface: Surface? = null) {
        if (surface != null && attachedSurface != null && surface !== attachedSurface) {
            val keep = attachedSurface
            LogBus.i("MpvPlayer", "旧视图销毁，当前画面已在新视图上，不摘")
            executor.execute {
                if (!initialized.get() || destroyed.get()) return@execute
                if (keep == null || attachedSurface !== keep || !keep.isValid) return@execute
                bindSurface(keep)
                runCatching { MPVLib.setPropertyBoolean("pause", paused) }
                LogBus.i("MpvPlayer", "旧视图已销毁，已重新接到当前画面")
            }
            return
        }
        attachedSurface = null
        executor.execute {
            if (initialized.get() && !destroyed.get()) {
                runCatching {
                    MPVLib.setPropertyString("vo", "null")
                    MPVLib.setOptionString("force-window", "no")
                    MPVLib.detachSurface()
                }
                    .onFailure { LogBus.w("MpvPlayer", "detachSurface 失败：${it.message}") }
            }
        }
    }

    fun onSurfaceSize(width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        // v0.294.44：旋转/切小窗后告诉 mpv 新画面尺寸，否则按旧尺寸渲染（顶部花屏、画面缩在角落）
        execIfReady {
            runCatching { MPVLib.setPropertyString("android-surface-size", "${width}x$height") }
                .onFailure { LogBus.w("MpvPlayer", "设置画面尺寸失败：${it.message}") }
            LogBus.i("MpvPlayer", "画面尺寸：${width}x$height")
        }
        // v0.208.0：拉伸档的比例取自容器尺寸——横竖屏切换时档位效应先跑、surface 后改尺寸，
        // 之前只在档位切换时算一次，横屏后仍用竖屏 16:9 盒子比例，拉伸也铺不满；
        // 尺寸变化时把当前拉伸档重算一遍（适应/裁剪不依赖尺寸，无需重算）。
        if (currentAspectMode == 2) setAspectMode(2)
    }

    /**
     * 播放 [url]。headers 中的 "User-Agent" 走独立的 user-agent 选项
     * （UA 常含逗号，不能塞进 http-header-fields 的逗号分隔列表）。
     * v1.1.31：点播 m3u8 先走 MpvAdFilter 拉列表过滤后播本地文件（失败回原地址）；
     * 直播不走过滤。
     * L1：返回 false 表示同步拒绝（地址为空、已释放、起播线程起不来），
     * true 表示起播任务已提交（不代表已出画面，失败走 onError 回调）。
     * M31：过滤下载（最长 16 秒）不在 mpv executor 上跑，改走独立下载
     * 线程，下载完只把 loadfile 一步提交给 executor——下载期间 stop/
     * pause/seek 不再排队等 16 秒。下载线程之间用 filterLock 串行
     * （缓存写盘非原子，并发写同一地址会互相截断）。
     * M35：每次 play() 递增 playSeq；下载线程起步、拿锁后、下载完三处
     * 都核对自己仍是最新请求，否则直接丢弃，快点连播不再积压下载。
     */
    fun play(
        url: String,
        headers: Map<String, String> = emptyMap(),
        startPositionMs: Long = 0L,
        subtitleUrl: String? = null,
        live: Boolean = false,
    ): Boolean {
        if (url.isBlank()) {
            LogBus.e("MpvPlayer", "播放地址为空，已拒绝")
            return false
        }
        if (destroyed.get()) {
            LogBus.e("MpvPlayer", "已释放，拒绝起播")
            return false
        }
        LogBus.i("MpvPlayer", "播放: ${url.take(120)}${if (url.length > 120) "..." else ""}")
        // v0.154.0：paused 不能只靠 mpv 的 pause 属性回调同步——mpv 默认本就不是暂停，
        // 起播时再设 pause=false 不会触发属性变化事件，本地 paused 会一直卡在 true，
        // 导致界面 isPlaying 恒为 false、中央大三角播着还挂着。起播即同步置 false，
        // 回调仍保留作兜底；loadfile 失败时在 onFailure 里置回 true。
        paused = false
        lastPositionMs = 0L
        lastDurationMs = 0L
        sawExpandedPlaylist = false
        // v1.3.25 审查修（TeleAgent L2）：记下本次起播时日志文件长度，mpvLogTail
        // 只读这之后的新内容——同一 mpv 实例内切集不重启，旧集的解码失败行
        // 会留在日志尾里，下一集网络失败时误判成解码失败、提前跳步换内核。
        logTailBaselineBytes = appContext?.let { ctx ->
            runCatching { File(ctx.filesDir, "mpv/mpv.log").length() }.getOrDefault(0L)
        } ?: 0L
        // v1.3.25 合并 7：新文件起播先清掉上一文件的旧音轨，新的等
        // FILE_LOADED 后由 refreshAudioTracks() 推上来，不拿旧的冒充。
        PlayerManager.publishMpvAudioTracks(emptyList())
        lastPlayHost = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        lastPlayExt = url.substringBefore("?").substringAfterLast(".", "").take(8)
        lastPlayStartMs = SystemClock.elapsedRealtime()
        // v1.3.17：起播代次快照——executor 里 loadfile 前核对，过期丢弃
        val gen = playGen
        val seq = playSeq.incrementAndGet()
        downloadInFlight = true
        return runCatching {
            Thread({
                if (isStalePlayRequest(seq, playSeq.get()) || gen != playGen) {
                    clearDownloadFlagIfCurrent(seq)
                    return@Thread
                }
                val filteredPath = appContext?.let { ctx ->
                    synchronized(filterLock) {
                        // 拿锁后复核——等锁期间可能已有更新的起播排进来。
                        if (isStalePlayRequest(seq, playSeq.get()) || gen != playGen) null
                        else runCatching { MpvAdFilter.prepareFilteredPlaylist(ctx, url, headers, live) }.getOrNull()
                    }
                }
                // H1：过滤最长 16 秒，期间 stop() 可能已把代次 +1——下载
                // 完、loadfile 前再核对一次代次与请求序号，过期直接丢弃。
                if (isStalePlayRequest(seq, playSeq.get()) || gen != playGen) {
                    LogBus.i("MpvPlayer", "起播已过期（过滤期间有新起播/停播），丢弃：${lastPlayHost}")
                    clearDownloadFlagIfCurrent(seq)
                    return@Thread
                }
                runCatching {
                    executor.execute { loadFilteredFile(gen, seq, filteredPath, url, headers, startPositionMs, subtitleUrl) }
                }.onFailure { LogBus.w("MpvPlayer", "起播任务投递失败：${it.message}") }
            }, "mpv-play-download").apply { isDaemon = true }.start()
            true
        }.getOrDefault(false)
    }

    /**
     * v1.3.21：只有最新一单下载能清下载标志——旧单被新起播顶掉时不清，
     * 否则新单下载中途标志被误清，seek 又落回空闲 mpv。
     */
    private fun clearDownloadFlagIfCurrent(seq: Int) {
        if (seq == playSeq.get()) downloadInFlight = false
    }

    /**
     * 下载完成后的 loadfile 一步（只跑在 mpv executor 上）。H1 的二次核对
     * 在这里做：loadfile 前再核对代次与请求序号，过期直接丢弃，stop 期间
     * 的旧起播到不了 loadfile；M35 的序号核对与 H1 的代次核对并存。
     */
    private fun loadFilteredFile(
        gen: Int,
        seq: Int,
        filteredPath: String?,
        url: String,
        headers: Map<String, String>,
        startPositionMs: Long,
        subtitleUrl: String?,
    ) {
        if (!initialized.get() || destroyed.get()) {
            clearDownloadFlagIfCurrent(seq)
            mainHandler.post { errorListener?.invoke("libmpv 初始化失败，请切回 Media3") }
            return
        }
        if (gen != playGen || isStalePlayRequest(seq, playSeq.get())) {
            clearDownloadFlagIfCurrent(seq)
            LogBus.i("MpvPlayer", "起播已过期（期间已停播/有新起播），丢弃：${lastPlayHost}")
            return
        }
        val playUrl = filteredPath ?: url
        val loaded = runCatching {
            // v1.3.20：本地 m3u8 起播前把 demuxer-lavf-format 强制成
            // hls——lavf_check_file 里 forced_format 直接满分 100，
            // 不走探测；非 m3u8 起播前清掉，免得 mp4 也被强制成 hls。
            // setOptionString 同步生效、loadfile 异步入队，值在起播前
            // 设好、只会被下一次起播改写，没有“设完立刻清”的竞态。
            // 故障十三（2026-11-18 真机）：强制确实到 probe 层
            // （lavf 两次 100 分 forced），但分片打不开时 hls 解复用
            // 器照样放弃、mpv 退回 playlist 逐条播——强制只过探测关，
            // 不过打开关；展开由 1.3.21 的诊断检测兜底（见
            // logFilteredPlaylistDiagnosis），不再指望强制本身。
            runCatching {
                if (filteredPath != null) MPVLib.setOptionString("demuxer-lavf-format", "hls")
                else MPVLib.setOptionString("demuxer-lavf-format", "")
            }.onFailure { LogBus.w("MpvPlayer", "demuxer-lavf-format 设置失败：${it.message}") }
            MPVLib.command(
                arrayOf("loadfile", playUrl, "replace", "-1", buildFileOptions(headers, startPositionMs, subtitleUrl))
            )
            // 下载已移出 executor：下载期间用户点的暂停不再排在 loadfile
            // 之后——按本地 paused 现值设，不把刚点的暂停顶掉。
            MPVLib.setPropertyBoolean("pause", paused)
            // v1.3.20：本地 m3u8 起播后打一次解复用器诊断（后台线程，
            // 不堵 mpv executor）——playlist-count/duration/file-format
            // + mpv 日志里的 probe 行，下次真机日志直接定性到底是
            // lavf 接了还是 playlist 接了。
            // v1.3.21：诊断线程带代次，顺带做展开检测。
            if (filteredPath != null) logFilteredPlaylistDiagnosis(gen)
        }.onSuccess {
            // v0.107.0 P0-3：replace 会结束旧文件（若有），其 END_FILE 计一次忽略；
            // 命令发出后再计数，保证与 handleEndFile（同 executor 串行）的先后顺序
            if (hasFile) expectEndFileCount.incrementAndGet()
            hasFile = true
        }.onFailure {
            paused = true
            LogBus.e("MpvPlayer", "loadfile 失败：${it.message}")
            mainHandler.post { errorListener?.invoke("libmpv loadfile 失败：${it.message?.take(100)}") }
        }.isSuccess
        // 下载窗口结束：之后点的 seek 正常走 executor，不再进 pending。
        clearDownloadFlagIfCurrent(seq)
        // v1.3.21：补发下载窗口内点的 seek（M31 把下载移出 executor 后，
        // 窗口内的 seek 不再排在 loadfile 之后，不补就丢了）。被新起播
        // 顶掉时不补——pending 留给新单的 loadfile。
        if (loaded && seq == playSeq.get()) {
            pendingSeekMs?.let { ms ->
                pendingSeekMs = null
                runCatching { MPVLib.command(arrayOf("seek", (ms / 1000.0).toString(), "absolute")) }
                    .onFailure { LogBus.w("MpvPlayer", "补发下载窗口内的 seek 失败：${it.message}") }
            }
        }
    }

    fun pause() {
        // v0.154.0：与 play() 同理，本地状态同步置，不只等回调
        paused = true
        execIfReady {
            runCatching { MPVLib.setPropertyBoolean("pause", true) }
                .onFailure { LogBus.w("MpvPlayer", "pause 失败：${it.message}") }
        }
    }

    fun resume() {
        paused = false
        execIfReady {
            runCatching { MPVLib.setPropertyBoolean("pause", false) }
                .onFailure { LogBus.w("MpvPlayer", "resume 失败：${it.message}") }
        }
    }

    fun seekTo(ms: Long) {
        // v1.3.21：下载进行中时还没有文件可 seek（M31 后下载在独立线程，
        // seek 不再排在 loadfile 之后）——先记下，loadfile 成功后补发。
        if (downloadInFlight) {
            pendingSeekMs = ms
            return
        }
        execIfReady {
            runCatching {
                MPVLib.command(arrayOf("seek", (ms / 1000.0).toString(), "absolute"))
            }.onFailure { LogBus.w("MpvPlayer", "seek 失败：${it.message}") }
        }
    }

    fun stop() {
        val hadFile = hasFile
        // v1.3.17：代次 +1，让已排队、还没执行的旧起播任务在 loadfile 前丢弃
        playGen++
        // L29：调用栈只在 debug 包下取——release 包每次 stop 都建
        // Throwable 只是为了记一行日志，白白烧性能。
        if (hadFile && com.movieshell.BuildConfig.DEBUG) {
            val who = Throwable().stackTrace.drop(1)
                .filter { it.className.startsWith("com.movieshell") && !it.className.contains("MpvPlayerManager") && !it.className.contains("EngineHost") }
                .take(3).joinToString(" ← ") { it.className.substringAfterLast('.').substringBefore('$') + "." + it.methodName + ":" + it.lineNumber }
            LogBus.i("MpvPlayer", "停止播放（stop），调用方：$who")
        }
        hasFile = false
        paused = true
        // L29：停播后位置/时长同步清零——否则 duration() 仍读旧值，
        // 退出保存逻辑在 stop 之后读时长会用旧时长存盘。
        lastPositionMs = 0L
        lastDurationMs = 0L
        sawExpandedPlaylist = false
        // v1.3.25 合并 7：停播清掉音轨列表，免得齿轮页还挂着上一文件的轨。
        PlayerManager.publishMpvAudioTracks(emptyList())
        // v1.3.21：停播丢弃下载窗口内未补发的 seek，不串到下一集。
        pendingSeekMs = null
        execIfReady {
            runCatching { MPVLib.command(arrayOf("stop")) }
                // v0.107.0 P0-3：命令发出后才计数；命令本身失败则不会有 END_FILE，不计数
                .onSuccess {
                    if (hadFile) expectEndFileCount.incrementAndGet()
                    // v1.3.17：stop 只停当前条，不清播放列表——分片被展开成
                    // 播放列表时余下的分片还在队列里；一并清空才算真停。
                    runCatching { MPVLib.command(arrayOf("playlist-clear")) }
                }
                .onFailure { LogBus.w("MpvPlayer", "stop 失败：${it.message}") }
        }
    }

    /**
     * v1.3.25 合并 7：读 mpv track-list 刷音轨列表，推给 PlayerManager 的
     * 同一条 StateFlow（齿轮页音轨选择与 Media3 共用）。只在 executor 上
     * 调（MPVLib 调用串行）；读失败留旧值，不弹错。
     * 沙箱 mpv 0.37 实测：getPropertyString("track-list") 给 JSON 数组串，
     * 每条含 id/type/title/lang/selected/codec。
     */
    private fun refreshAudioTracks() {
        if (!initialized.get() || destroyed.get()) return
        runCatching {
            val list = MpvAudioTracks.parseTrackList(MPVLib.getPropertyString("track-list"))
            PlayerManager.publishMpvAudioTracks(list)
            if (list.isNotEmpty()) LogBus.i("MpvPlayer", "音轨列表：" + list.size + " 条")
        }.onFailure { LogBus.w("MpvPlayer", "读 track-list 失败：" + (it.message ?: "")) }
    }

    /**
     * v1.3.25 合并 7：libmpv 音轨切换。trackId 是 MpvAudioTracks 给的
     * "mpv:<id>"，null=回到自动（aid=auto）。id 对不上当前流直接忽略，
     * 不报错、不乱切。切完靠 aid 属性变化事件触发 refreshAudioTracks()
     * 更新选中态。沙箱 mpv 0.37 实测：set aid=2 与 aid=auto 均生效。
     */
    fun selectAudioTrack(trackId: String?) {
        val mpvId = trackId?.let { MpvAudioTracks.trackIdFromMpvId(it) }
        if (trackId != null && mpvId == null) return
        execIfReady {
            runCatching {
                if (mpvId == null) MPVLib.setPropertyString("aid", "auto")
                else MPVLib.setPropertyInt("aid", mpvId)
                LogBus.i("MpvPlayer", "音轨切换：" + (trackId ?: "自动"))
            }.onFailure { LogBus.w("MpvPlayer", "音轨切换失败：" + (it.message ?: "")) }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        // L25：0/负数/极大值直传 speed 会让 mpv 行为未定义——钳到 0.25–4。
        val clamped = clampPlaybackSpeed(speed)
        execIfReady {
            runCatching { MPVLib.setPropertyDouble("speed", clamped.toDouble()) }
                .onFailure { LogBus.w("MpvPlayer", "倍速设置失败：${it.message}") }
        }
    }

    /**
     * 画面比例：0=适应（保持原始比例），1=裁剪（等比裁剪填满），2=拉伸（不等比
     * 铺满容器），与播放器界面「适应/裁剪/拉伸」三档及 Media3 的 FIT/ZOOM/FILL
     * 一一对应（v0.125.0 起下标与界面统一，旧版 1/2 语义互换）。
     * 拉伸的 mpv 等价实现：把 video-aspect-override 设为当前画面容器（Surface）
     * 的宽高比——等于命令 mpv 按容器比例改写视频原始比例输出，不裁边、轻微
     * 变形，与 RESIZE_MODE_FILL 同义；panscan 保持 0（不裁剪）。
     */
    fun setAspectMode(mode: Int) {
        currentAspectMode = mode
        execIfReady {
            if (!initialized.get() || destroyed.get()) return@execIfReady
            runCatching {
                when (mode) {
                    1 -> {
                        // 裁剪：原始比例 + panscan 拉满，等比裁掉溢出边
                        MPVLib.setPropertyString("video-aspect-override", "-1")
                        MPVLib.setPropertyDouble("panscan", 1.0)
                    }
                    2 -> {
                        // 拉伸：比例改写为容器比例，不等比铺满
                        val w = surfaceW
                        val h = surfaceH
                        val aspect = if (w > 0 && h > 0) w.toDouble() / h else 16.0 / 9.0
                        // 必须用 US locale：小数点在某些语言环境下是逗号，mpv 解析会错
                        MPVLib.setPropertyString("video-aspect-override", "%.4f".format(Locale.US, aspect))
                        MPVLib.setPropertyDouble("panscan", 0.0)
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
     * v1.3.25 合并 5：Anime4K 画质增强。首次用时把 assets/anime4k 里的 9 个
     * 着色器拷到 filesDir/anime4k（mpv 只能读文件路径，读不了 assets），
     * 再设 glsl-shaders 属性；OFF 设空串卸载。只在 executor 上调。
     */
    fun applyAnime4K(mode: Anime4K.Mode) {
        execIfReady {
            val ctx = appContext ?: return@execIfReady
            runCatching {
                val dir = File(ctx.filesDir, "anime4k")
                if (!anime4kInstalled) anime4kInstalled = installAnime4KShaders(ctx, dir)
                MPVLib.setPropertyString("glsl-shaders", Anime4K.shaderChain(dir, mode))
                LogBus.i("MpvPlayer", "Anime4K：" + mode.label)
            }.onFailure { LogBus.w("MpvPlayer", "Anime4K 设置失败：" + (it.message ?: "")) }
        }
    }

    /**
     * v1.3.25 合并 5：把 assets/anime4k 的着色器拷到 filesDir/anime4k
     *（缺失的才拷）。全拷成就算装好；拷失败返回 false，下次再试，
     * 不阻塞播放（着色器是画质增强，不是起播必需）。
     * v1.3.25 审查修（GPT L-02）：原来直接往目标文件写，拷一半失败会留
     * 残缺文件，下次因 exists() 跳过复制却返回成功。改成先写 .tmp、
     * 再原子改名，失败删 tmp；0 字节的目标文件视为残缺，重拷。
     */
    private fun installAnime4KShaders(ctx: Context, dir: File): Boolean {
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            for (name in Anime4K.ALL_SHADER_FILES) {
                val target = File(dir, name)
                if (!target.exists() || target.length() == 0L) {
                    target.delete()
                    val tmp = File(dir, name + ".tmp")
                    try {
                        ctx.assets.open("anime4k/" + name).use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (!tmp.renameTo(target)) {
                            tmp.delete()
                            return@runCatching false
                        }
                    } catch (e: Exception) {
                        tmp.delete()
                        throw e
                    }
                }
            }
            true
        }.onFailure { LogBus.w("MpvPlayer", "Anime4K 着色器安装失败：" + (it.message ?: "")) }
            .getOrDefault(false)
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
        // M32：先记下最新样式——init 未完成时 execIfReady 会静默丢掉
        // 这次调用，init 完成时按这里记的值重放一次。
        lastSubtitleStyle = style
        execIfReady {
            if (!initialized.get() || destroyed.get()) return@execIfReady
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
     * v0.21.0：截图到 xiaying/Screenshots，返回保存的文件，失败返回 null。
     * 调用方在 IO 线程调。
     * M27/V6：截图命令排进 executor（不与 loadfile/stop 并发），且 mpv
     * 手册写明截图编码/落盘默认就是异步的（standalone 命令恒异步），
     * 命令返回不代表文件已写好——轮询确认文件存在且非空再报成功，
     * 最多等 2 秒；调用线程最多等 5 秒。
     */
    fun screenshot(context: Context, title: String): File? {
        if (!initialized.get() || destroyed.get()) return null
        return runCatching {
            val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40).ifBlank { "截图" }
            val file = File(
                StoragePaths.screenshotsDir(context),
                "侠影_${safe}_${System.currentTimeMillis()}.png",
            )
            val saved = AtomicBoolean(false)
            val done = CountDownLatch(1)
            val task = Runnable {
                runCatching {
                    MPVLib.command(arrayOf("screenshot-to-file", file.absolutePath))
                    var waited = 0L
                    while (waited <= 2000L) {
                        if (file.exists() && file.length() > 0) {
                            saved.set(true)
                            break
                        }
                        Thread.sleep(100)
                        waited += 100L
                    }
                }.onFailure { LogBus.w("MpvPlayer", "截图命令失败：${it.message}") }
                done.countDown()
            }
            // 已在 executor 线程上时直接跑，否则排队——避免自己等自己死锁。
            if (Thread.currentThread().name == "mpv-cmd") task.run() else executor.execute(task)
            done.await(5, TimeUnit.SECONDS)
            if (saved.get()) {
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
        if (!initialized.get()) return
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
     * v1.3.17：当前 mpv 播放列表条数（读不到按 1 计）。正常单片播放恒为
     * 1 条；>1 说明分片被展开成播放列表（见 ensureHlsMarkers 注释）。
     */
    private fun playlistCount(): Int = runCatching {
        MPVLib.getPropertyString("playlist-count")?.trim()?.toIntOrNull() ?: 1
    }.getOrDefault(1)

    /**
     * END_FILE 处理：自然播完（eof-reached=true）同步置 paused（界面不再以为在播）；
     * 异常结束则回调 onError（调用方走自动换线路重试）。
     * v0.107.0 P0-3：我们自己 stop()/loadfile 触发的按计数忽略；
     * 快速失败（404/拒连，eof-reached=false）直接走错误路径，不再被时间窗口吞掉。
     */
    private fun handleEndFile() {
        hasFile = false
        val eof = runCatching { MPVLib.getPropertyBoolean("eof-reached") }.getOrNull() == true
        if (!eof) {
            // v1.3.21 H9：自己 stop()/loadfile 触发的 END_FILE 只吞一次，
            // 与分片过渡抑制合一判定——先消费 expectEndFileCount（>0 则
            // decrement 并 return），再看是不是分片过渡。之前计数在函数
            // 开头、eof 判定之前，悬空的 +1 会连自然播完都吞掉（paused
            // 不置位、播完链路不触发），也可能被多条分片过渡养着、吞掉
            // 后面的真失败。
            if (expectEndFileCount.get() > 0) {
                expectEndFileCount.decrementAndGet()
                return
            }
            // v1.3.17：播放列表里还有不止一条——这是分片被当独立条目展开
            // （极简 m3u8 没被 lavf 认出，见 MpvAdFilter.ensureHlsMarkers），
            // 本次 END_FILE 只是分片过渡，不是失败：不回调 onError、不触发
            // 换内核重试，让 mpv 继续播下一条。正常单片播放列表恒为 1 条，
            // 不会误伤；真失败时列表只剩当前一条，照常走错误路径。
            if (playlistCount() > 1) {
                sawExpandedPlaylist = true
                hasFile = true
                LogBus.i("MpvPlayer", "分片过渡（播放列表多条），忽略本次 END_FILE")
                return
            }
            // M29：展开播到最后一段时 END_FILE 的 eof 常为 false——位置
            // 已接近时长，或列表刚由多条缩回 1 条，都是自然播完，不
            // 是失败：不回调 errorListener，不触发换内核重试。
            if (isFinalSegmentNaturalEnd(lastPositionMs, lastDurationMs, sawExpandedPlaylist)) {
                paused = true
                LogBus.i("MpvPlayer", "展开播放列表末段结束，判自然播完（位置 ${lastPositionMs}ms/时长 ${lastDurationMs}ms）")
                return
            }
            // v0.154.0：异常结束即不在播，本地状态同步置 true，免得重试间隙界面仍显示在播
            paused = true
            // v0.152.0：不再只回一句"libmpv 播放失败"——2026-10-22 直播案（libmpv 5 秒后断、
            // Media3 同地址能播）就卡在这里定不了根因。拼上已观测的失败画像：
            // host/后缀、播到第几秒、是否出画面、起播后多久断，日志与失败提示同文。
            val elapsed = if (lastPlayStartMs > 0) SystemClock.elapsedRealtime() - lastPlayStartMs else 0L
            val phase = if (lastPositionMs <= 0) "未出画面" else "播到${lastPositionMs / 1000}秒后中断"
            val where = listOf(lastPlayHost, lastPlayExt).filter { it.isNotBlank() }.joinToString(" ")
            val detail = "libmpv 播放失败" +
                (if (where.isNotBlank()) "（$where" else "（") +
                "，$phase，起播后${elapsed}ms，eof=false）"
            LogBus.w("MpvPlayer", "播放异常结束（非自然播完）：$detail")
            // v1.3.15：把 mpv 日志末尾打进 LogBus——END_FILE 不给原因，这是唯一真凶来源。
            // v1.3.25 合并 6：日志尾里有解码失败行时给错误串打解码标记，播放页
            // handlePlayFailure 认标记直换内核，不烧同内核重拉与清 Referer。
            var decodeFailed = false
            runCatching {
                val tail = mpvLogTail(appContext)
                if (tail.isNotBlank()) {
                    LogBus.w("MpvPlayer", "mpv 日志末尾：\n$tail")
                    decodeFailed = PlayFailureClass.mpvLogHasDecodeFailure(tail)
                }
            }
            val report = if (decodeFailed) "${PlayFailureClass.DECODE_FAILED_MARKER}|$detail" else detail
            mainHandler.post { errorListener?.invoke(report) }
        } else {
            // v0.217.0（F23）：自然播完同样置 paused——原来只有异常结束置，
            // 自然播完 isPlaying 仍为 true，中央大播放键出不来；
            // 若 mpv 本身会回调 pause 属性，此处置 true 是幂等的，无副作用。
            paused = true
        }
    }

    /**
     * v1.3.15：读 mpv 日志文件末尾（失败诊断用）。文件可能很大，只读最后 16KB；
     * 读不到/文件不存在返回空串，不抛异常。
     * v1.3.25 审查修（TeleAgent L2）：只读本次起播（logTailBaselineBytes）
     * 之后的新内容，上限仍是最后 16KB——旧集的解码失败行不再误触发下一集。
     */
    private fun mpvLogTail(context: Context?): String {
        val ctx = context ?: return ""
        return runCatching {
            val f = File(ctx.filesDir, "mpv/mpv.log")
            if (!f.exists() || f.length() <= 0L) return ""
            val len = f.length()
            val start = maxOf(logTailBaselineBytes, len - 16_384L)
            val take = (len - start).toInt().coerceAtLeast(0)
            if (take <= 0) return ""
            val buf = ByteArray(take)
            var off = 0
            f.inputStream().use { input ->
                var skipped = 0L
                while (skipped < start) {
                    val s = input.skip(start - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                while (off < take) {
                    val r = input.read(buf, off, take - off)
                    if (r < 0) break
                    off += r
                }
            }
            mpvLogTailText(buf.decodeToString(endIndex = off))
        }.getOrDefault("")
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
        val rest = PlayHeaders.normalize(headers).toMutableMap()
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

    /**
     * v1.3.20：本地 m3u8 起播后约 2 秒打解复用器诊断（后台线程，不堵
     * mpv executor）。playlist-count=1 且 duration 完整 ⇒ lavf-hls
     * 接管；>1 且 duration 只有几秒 ⇒ 仍是 playlist 展开。
     * v1.3.21：带代次 [gen]——stop/切集后旧线程的数据不再归属本次
     * 播放（M26）；并做展开检测：本地 m3u8 被展开成播放列表且进度
     * 长期不动时，说明分片拉不到、hls 解复用器已放弃，静默黑屏不
     * 会触发任何错误——此时主动回调一次错误，让 PlayerScreen 的
     * 重试链切到 Media3（OkHttp 那路通常能拿到分片）。只回调一次。
     */
    private fun logFilteredPlaylistDiagnosis(gen: Int) {
        Thread({
            runCatching {
                Thread.sleep(2000)
                if (gen != playGen) return@runCatching
                val count = playlistCount()
                val durMs = runCatching { MPVLib.getPropertyDouble("duration") }.getOrNull()
                    ?.let { (it * 1000).toLong() } ?: -1L
                val fmt = runCatching { MPVLib.getPropertyString("file-format") }.getOrNull()
                val probe = mpvProbeLines(appContext)
                // 嵌套双引号会骗过 check_imports 的去字符串：条件文案先抽 val 再拼。
                val fmtText = fmt ?: "?"
                val probeSuffix = if (probe.isNotBlank()) " | probe: $probe" else ""
                LogBus.i(
                    "MpvPlayer",
                    "m3u8 解复用诊断：playlist-count=$count duration=${durMs}ms file-format=$fmtText$probeSuffix",
                )
                if (count <= 1) return@runCatching
                // 展开已确认：5 秒查一次进度，8 秒再查一次——两次都不动
                // 才判失败（慢启动的首个分片可能 5 秒内还没出画面）。
                Thread.sleep(3000)
                if (gen != playGen || !hasFile) return@runCatching
                if (currentPosition < 2000L) {
                    fireExpansionError()
                    return@runCatching
                }
                Thread.sleep(3000)
                if (gen != playGen || !hasFile) return@runCatching
                if (playlistCount() > 1 && currentPosition < 3000L) fireExpansionError()
            }
        }, "mpv-hls-diag").apply { isDaemon = true }.start()
    }

    /** 展开检测命中：带标记回调一次错误，调用方见标记直奔换内核。 */
    private fun fireExpansionError() {
        LogBus.w("MpvPlayer", "本地 m3u8 被当成播放列表展开且进度不动，分片拉不到，判失败切内核")
        mainHandler.post {
            errorListener?.invoke("$PLAYLIST_EXPANDED_MARKER|本地 m3u8 被当成播放列表展开，分片拉不到")
        }
    }

    /**
     * v1.3.20：从 mpv 日志里捞解复用器探测行（诊断用）。只读最后 64KB；
     * 读不到返回空串。文本过滤见 [extractMpvProbeLines]（可单测）。
     */
    private fun mpvProbeLines(context: Context?): String {
        val ctx = context ?: return ""
        return runCatching {
            val f = File(ctx.filesDir, "mpv/mpv.log")
            if (!f.exists() || f.length() <= 0L) return ""
            val take = minOf(f.length(), 65_536L).toInt()
            val buf = ByteArray(take)
            var off = 0
            f.inputStream().use { input ->
                var skipped = 0L
                val toSkip = f.length() - take
                while (skipped < toSkip) {
                    val s = input.skip(toSkip - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                while (off < take) {
                    val r = input.read(buf, off, take - off)
                    if (r < 0) break
                    off += r
                }
            }
            extractMpvProbeLines(buf.decodeToString(endIndex = off))
        }.getOrDefault("")
    }
}

/**
 * v1.3.16：ffmpeg 协议白名单——本地 m3u8 里的 https 分片必须放行。
 * file/crypto/data 是 mpv 播本地文件时的默认白名单，只列这三项时
 * https 分片会被拒；http/https/tcp/tls 是拉分片要用的传输层。
 */
internal const val MPV_PROTOCOL_WHITELIST = "file,http,https,tcp,tls,crypto,data"

/**
 * v1.3.18：lavf 解复用器的最低认分。HLS 探测满分是 100
 * （AVPROBE_SCORE_MAX），mpv 默认认分线是 26；写 25 只是放宽门槛、
 * 无害。1.3.19 真机证实分片问题与认分线无关（probe 根本不认干净的
 * 本地文件），真正绕过靠 demuxer-lavf-format 强制（见 play()）。
 */
internal const val MPV_LAVF_PROBESCORE = "25"

/**
 * v1.3.15：取 mpv 日志文本的末尾若干行（失败诊断用）。纯函数，可单测；
 * 空文本返回空串，超长先截到 maxChars 再取行。
 */
internal fun mpvLogTailText(text: String, maxLines: Int = 30, maxChars: Int = 8192): String {
    if (text.isBlank()) return ""
    val tail = text.takeLast(maxChars)
    return tail.lines().filter { it.isNotBlank() }.takeLast(maxLines).joinToString("\n")
}

/**
 * v1.3.20：从 mpv 日志文本里捞解复用器探测行（纯函数，可单测）。
 * 命中：lavf 的 "Found 'x' at score="、"No format found"、demux 的
 * "Detected file format"、"Trying demuxers"。只取最后 maxLines 行，
 * 每行截到 200 字符。
 */
internal fun extractMpvProbeLines(text: String, maxLines: Int = 6): String {
    if (text.isBlank()) return ""
    val hits = text.lines().filter { line ->
        (line.contains("Found '") && line.contains("score=")) ||
            line.contains("No format found") ||
            line.contains("Detected file format") ||
            line.contains("Trying demuxers")
    }.map { it.trim().take(200) }
    return hits.takeLast(maxLines).joinToString(" | ")
}

/** L25：倍速钳到 0.25–4，0/负数/极大值不直传 mpv。纯函数，可单测。 */
internal fun clampPlaybackSpeed(speed: Float): Float = speed.coerceIn(0.25f, 4f)

/** M35：起播请求已不是最新一次（有更新的 play() 排进来）即过期。纯函数，可单测。 */
internal fun isStalePlayRequest(seq: Int, latestSeq: Int): Boolean = seq != latestSeq

/**
 * M29：展开播放列表末段的 END_FILE 是否算自然播完。纯函数，可单测。
 * 位置已接近时长（2 秒内）即自然结束；列表刚由多条缩回 1 条且已播出
 * 位置（positionMs > 0）也算——末段打不开时位置一般为 0，不会误判。
 */
internal fun isFinalSegmentNaturalEnd(positionMs: Long, durationMs: Long, sawExpandedPlaylist: Boolean): Boolean {
    if (durationMs > 0 && positionMs > 0 && positionMs >= durationMs - 2000L) return true
    if (sawExpandedPlaylist && positionMs > 0) return true
    return false
}
