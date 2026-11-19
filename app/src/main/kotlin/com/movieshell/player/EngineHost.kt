package com.movieshell.player

import android.content.Context
import com.movieshell.ui.components.AppToast
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.ui.PlayerView
import com.movieshell.core.log.LogBus
import com.movieshell.domain.model.PlayerEngine
import com.movieshell.data.store.PlayerSettings
import com.movieshell.domain.model.SubtitleStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 双引擎路由（v0.20.0）：播放器界面只跟 EngineHost 打交道，
 * 实际走 Media3（默认）还是 libmpv 由 [PlayerSettings.engine] 决定。
 * Media3 侧沿用既有 PlayerManager（代码不动），libmpv 侧走 MpvPlayerManager。
 *
 * v0.21.0：[engineOverride] 为自动失败转移的临时覆盖（不持久化用户选择）；
 * 换地址/手动切内核时清掉。
 */
object EngineHost {

    private val _override = MutableStateFlow<PlayerEngine?>(null)

    /** 自动换内核的临时覆盖；null 表示用用户持久化选择 */
    val engineOverride: StateFlow<PlayerEngine?> = _override

    fun engine(): PlayerEngine = _override.value ?: PlayerSettings.engine.value

    /**
     * v1.3.21 H2+M39：实际在播的引擎——play() 分发成功后记，stop() 清掉。
     * route()/选音轨/字幕样式/截图一律按它走，不按用户偏好 [engine()]，
     * 否则失败转移后（偏好仍是 libmpv、实际在 Media3 播）读位置、暂停、
     * 切音轨全都发到错的引擎上。没在播时为 null，回落到 engine()。
     */
    @Volatile
    private var actualEngine: PlayerEngine? = null

    /** 实际在播的引擎，没在播时回落到用户偏好。 */
    private fun currentEngine(): PlayerEngine = actualEngine ?: engine()

    /** v0.21.0：设置/清除自动换内核覆盖（静默，不写偏好） */
    fun setEngineOverride(engine: PlayerEngine?) {
        _override.value = engine
    }

    /**
     * v0.186.0 第 5 组：硬解 fallback 已试过的内核。
     * PlayerManager 硬解切 libmpv 时记入，PlayerScreen 换内核判定时与本地
     * triedEngines 合并判断，避免 libmpv 再失败后又切回已试过的 Media3 空转。
     * 换地址时由 PlayerScreen 经 clearFallbackTriedEngines 清空。
     */
    private val fallbackTriedEngines = mutableSetOf<PlayerEngine>()

    fun markEngineTriedByFallback(engine: PlayerEngine) {
        fallbackTriedEngines.add(engine)
    }

    fun fallbackTriedEngines(): Set<PlayerEngine> = fallbackTriedEngines.toSet()

    fun clearFallbackTriedEngines() {
        fallbackTriedEngines.clear()
    }

    /**
     * v1.3.15：libmpv 已播失败过的地址 path（去 query，只留路径）。
     * 同一集换解析只换 auth_key、path 不变，仍认得；不同集 path 不同，不影响。
     * PlayerManager 编码 fallback 切 libmpv 前查它：已失败过的不再切回去，
     * 避免 Media3 与 libmpv 原地空转。退出播放页时由 PlayerScreen 清空。
     * v1.3.21 M15：插入序 LinkedHashMap，满 50 只淘汰最旧一条——
     * 之前 mutableSetOf 满了全清，50 条防乒乓记录一次性丢失。
     */
    private val libmpvFailedPaths = LinkedHashMap<String, Unit>()
    private const val LIBMPV_FAILED_MAX = 50

    fun markLibmpvFailed(url: String) {
        val key = url.substringBefore("?").trim()
        if (key.isBlank()) return
        // 已在表里：删了重放，刷新新近度——否则反复失败的旧地址一直占着
        // 最老位置，下一次新失败就先把它淘汰，防乒乓失效。
        if (libmpvFailedPaths.containsKey(key)) {
            libmpvFailedPaths.remove(key)
            libmpvFailedPaths[key] = Unit
            return
        }
        if (libmpvFailedPaths.size >= LIBMPV_FAILED_MAX) {
            libmpvFailedPaths.keys.firstOrNull()?.let { libmpvFailedPaths.remove(it) }
        }
        libmpvFailedPaths[key] = Unit
    }

    fun libmpvFailed(url: String): Boolean {
        val key = url.substringBefore("?").trim()
        return key.isNotBlank() && libmpvFailedPaths.containsKey(key)
    }

    fun clearLibmpvFailed() {
        libmpvFailedPaths.clear()
    }

    /** v1.3.17：App 是否在后台（PlayerScreen 的生命周期观察者维护）。
     * 后台时拒绝新的起播——失败重试的延迟任务不得在后台把已暂停的播放
     * 重新拉起来；回到前台（ON_START）即清掉。 */
    @Volatile var backgrounded = false

    /**
     * 起播（v0.25.0 加固：永不抛异常）。
     * - 地址非法 → 记日志 + Toast，返回 false；
     * - libmpv 初始化曾失败 → 直接禁用该引擎回退 Media3（不走自动切换），Toast 提示；
     * - 引擎起播抛异常 → 记 e 级诊断日志，返回 false（调用方决定 Toast/重试）。
     * 返回 true 表示已提交给引擎（不代表已出画面，播放失败走 onError 回调）。
     */
    fun play(
        context: Context,
        url: String,
        headers: Map<String, String> = emptyMap(),
        startPositionMs: Long = 0L,
        subtitleUrl: String? = null,
        // v0.294.5：直播（含 HLS 直播/rtmp/flv 直播）——Media3 不走视频磁盘缓存
        live: Boolean = false,
        // v1.2.14：Emby 自签证书播放（MediaServerScreen.playItem 带来，
        // 该台服务器的 EmbyServer.ignoreCert）；其他入口默认关。
        ignoreCert: Boolean = false,
    ): Boolean {
        // v1.3.17：后台拒绝起播（见 backgrounded 注释）
        if (backgrounded) {
            LogBus.i("Play", "后台中，拒绝起播")
            return false
        }
        val t0 = SystemClock.elapsedRealtime()
        appCtx = context.applicationContext
        if (url.isNotBlank() && url == MagnetRemote.lastUrl) { MagnetRemote.keepAsync(context); ensureHeartbeat() }
        // v0.294.36：开始播非磁力内容 → 立刻结束磁力进程（种子、迅雷任务、通知一起停）
        if (url != MagnetRemote.lastUrl && MagnetRemote.isRunning(context)) {
            LogBus.i("Play", "播放非磁力内容，停止后台磁力任务")
            MagnetRemote.lastUrl = ""
            MagnetRemote.cancelAsync(context)
        }
        if (!isPlayableUrl(url)) {
            LogBus.w("Play", "播放地址非法，已拒绝：${url.take(80)}")
            // v0.106.0：NBY-XMYAES 这类源返回的加密/自定义 scheme 串解不了也播不了，
            // 别只说"无效"让用户懵——说清楚是"该源返回的地址格式暂不支持"
            val msg = if (url.isBlank()) "未获取到播放地址"
            else "该源返回的地址格式暂不支持播放"
            AppToast.show(context, msg, long = false)
            return false
        }
        // v1.3.21 L28：stopGen++ 与 currentUrl 移到校验之后——非法地址的
        // 一次起播不再取消前一次 stop() 挂起的磁力释放。
        stopGen++
        currentUrl = url
        // v0.152.0：RTMP 只能走 libmpv（Media3 不吃 rtmp），且不改用户持久化选择。
        // 未装插件时直接拒绝、不切内核；libmpv 初始化失败过时也不回退 Media3（回退也播不了）。
        // v0.162.0：原生 rtp:// 同理（组播只在局域网 WiFi 下有意义，蜂窝下播不了属环境限制，
        // 不是代码能保证的；不做 /status 探活，播了才知道）。
        // v1.2.2：rtsp:// 同理（Media3 不吃 rtsp；广东 IPTV 这类运营商流走 libmpv/FFmpeg）。
        val isRtmp = url.trim().startsWith("rtmp://", true)
        val isRtp = url.trim().startsWith("rtp://", true)
        val isRtsp = url.trim().startsWith("rtsp://", true)
        if (isRtmp || isRtp || isRtsp) {
            val proto = when {
                isRtmp -> "RTMP"
                isRtp -> "RTP"
                else -> "RTSP"
            }
            if (!MpvPluginLoader.isInstalled(context)) {
                LogBus.w("Play", "$proto 需 libmpv 插件，未安装已拒绝")
                AppToast.show(context, "$proto 需 libmpv 插件，请先到插件市场安装", long = true)
                return false
            }
            if (MpvPlayerManager.initFailed) {
                if (MpvPlayerManager.initFailedMissingPlugin && MpvPluginLoader.isInstalled(context)) {
                    // v1.1.31：上次失败是缺插件，现在已补装——清标记重试
                    MpvPlayerManager.clearInitFailed()
                    LogBus.i("Play", "$proto：libmpv 插件已补装，重试初始化")
                } else {
                    LogBus.w("Play", "$proto 需 libmpv，但 libmpv 初始化失败过，已拒绝")
                    AppToast.show(context, "libmpv 不可用，$proto 无法播放", long = false)
                    return false
                }
            }
            if (engine() == PlayerEngine.MEDIA3) {
                setEngineOverride(PlayerEngine.LIBMPV)
                LogBus.i("Play", "$proto 走 libmpv（临时覆盖，不改用户设置）")
            }
        }
        var eng = engine()
        // v1.2.14：自签 Emby 播放——libmpv 没有按次放行证书的口子（tls-verify
        // 是实例级选项，播完再改回有竞态），本次改走 Media3（OkHttp 放行
        // 数据源）；用户持久化的内核偏好不动，下次非自签播放仍走原内核。
        // 已有自动换内核覆盖时不插手（硬解失败回退 libmpv 的重试走原路，
        // 再翻回 Media3 会撞上已知坏解码原地空转）。
        if (shouldBypassCert(ignoreCert, url) && eng == PlayerEngine.LIBMPV && _override.value == null) {
            LogBus.i("Play", "自签 Emby 播放切回 Media3（libmpv 不支持按次放行证书）")
            AppToast.show(context, "自签证书播放已切回 Media3", long = false)
            eng = PlayerEngine.MEDIA3
            // v1.3.21 H8：只改局部变量不写覆盖，route() 仍按偏好走 libmpv——
            // 证书分支同时写覆盖（与 actualEngine 双保险），换地址/手动切核
            // 心时由既有清理点复位。
            setEngineOverride(PlayerEngine.MEDIA3)
        }
        if (eng == PlayerEngine.LIBMPV && MpvPlayerManager.initFailed) {
            if (MpvPlayerManager.initFailedMissingPlugin && MpvPluginLoader.isInstalled(context)) {
                // v1.1.31：上次失败是缺插件，现在已补装——清标记重试初始化，不用重启 App
                MpvPlayerManager.clearInitFailed()
                LogBus.i("Play", "libmpv 插件已补装，重试初始化")
            } else {
                LogBus.w("Play", "libmpv 已禁用（初始化失败过），回退 Media3")
                AppToast.show(context, "libmpv 不可用，已切回 Media3", long = false)
                setEngineOverride(PlayerEngine.MEDIA3)
                eng = PlayerEngine.MEDIA3
            }
        }
        // v1.3.21 H2：分发前先停另一引擎——正常切集/换线路时旧引擎
        // 不停，双引擎会同时响。直接调各引擎 stop，不走 EngineHost.stop()
        //（后者带磁力 4 秒释放簿记，换内核/重试是“停→马上再播”，不算离开）。
        if (eng == PlayerEngine.MEDIA3) MpvPlayerManager.stop() else PlayerManager.stop()
        return try {
            // v1.3.20：按地址补默认请求头（Bilibili 直链缺 Referer 会 403），
            // 双内核共用同一份；规则已给的头不动。
            val effHeaders = PlayHeaders.withSiteDefaults(url, headers)
            // v1.3.21 L1：引擎 play() 返回是否真的提交起播（静默早返时
            // 为 false），原样返回，不再无条件 true。
            val dispatched = if (eng == PlayerEngine.MEDIA3) {
                PlayerManager.init(context)
                PlayerManager.play(url, effHeaders, startPositionMs, subtitleUrl, live, ignoreCert = ignoreCert)
            } else {
                MpvPlayerManager.init(context)
                MpvPlayerManager.play(url, effHeaders, startPositionMs, subtitleUrl, live)
            }
            actualEngine = if (dispatched) eng else null
            val cost = SystemClock.elapsedRealtime() - t0
            // v0.25.0：结构化诊断日志（URL 脱敏：只留 host + 后缀）
            LogBus.i(
                "Play",
                "start engine=$eng host=${urlHost(url)} ext=${urlExt(url)} " +
                    "cost=${cost}ms pos=${startPositionMs}ms sub=${!subtitleUrl.isNullOrBlank()}",
            )
            dispatched
        } catch (t: Throwable) {
            actualEngine = null
            LogBus.e("Play", "起播异常 engine=$eng ${t::class.java.simpleName}: ${t.message}")
            false
        }
    }

    /** 播放地址合法性：非空 + 协议白名单（http/https/magnet/file/本地绝对路径 + v0.152.0 的 rtmp + v0.162.0 的 rtp + v1.2.2 的 rtsp）。
     * v0.218.0（S12）：pics:// 不再算可播——原来白名单含 pics，起播又静默
     * 拒绝，两处对同一地址结论相反；看图不走视频引擎（走 PicsViewerScreen
     * 分流），这里排除后 pics 进来走上面的非法地址分支，给可见提示。 */
    fun isPlayableUrl(url: String): Boolean {
        val u = url.trim()
        if (u.isBlank()) return false
        return u.startsWith("http://", true) || u.startsWith("https://", true) ||
            u.startsWith("magnet:", true) || u.startsWith("file://", true) ||
            u.startsWith("rtmp://", true) || u.startsWith("rtp://", true) ||
            u.startsWith("rtsp://", true) ||
            u.startsWith("/")
    }

    /**
     * v1.2.14：本次播放是否放行证书——只当调用方显式传了 ignoreCert
     * （Emby 服务器开关，MediaServerScreen.playItem 带来）且地址是 https；
     * http 地址没有 TLS 可放行，其他播放一律走原链路。纯函数，可单测。
     */
    fun shouldBypassCert(ignoreCert: Boolean, url: String): Boolean =
        ignoreCert && url.trim().startsWith("https://", true)

    /** URL 脱敏：host（日志不记完整地址）。 */
    private fun urlHost(url: String): String =
        runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")

    /** URL 后缀（m3u8/mp4/flv 等，用于定位格式相关问题）。 */
    private fun urlExt(url: String): String =
        url.substringBefore("?").substringAfterLast(".", "").take(8)

    fun pause() = route({ PlayerManager.pause() }, { MpvPlayerManager.pause() })

    /**
     * v1.3.17：两个引擎都暂停——切后台时不知道实际在播的是哪一个，
     * 只停「当前」引擎可能漏掉另一个（与 stop 双停同理）。
     */
    fun pauseAll() {
        PlayerManager.pause()
        MpvPlayerManager.pause()
    }

    // v1.3.21 L8：把断点带进 resume——Media3 在 IDLE/ENDED 时 prepare
    // 会回到默认起播位置；点播传位置、直播不传（默认 -1，回直播沿）。
    fun resume(startPositionMs: Long = -1L) =
        route({ PlayerManager.resume(startPositionMs) }, { MpvPlayerManager.resume() })

    /**
     * v0.27.7：当前是否正在播放（双引擎统一）。Media3 直接读 PlayerManager 的
     * 播放器状态，不另起一套；libmpv 走 MpvPlayerManager。
     */
    fun isPlayingNow(): Boolean = route(
        { PlayerManager.player?.isPlaying == true },
        { MpvPlayerManager.isPlaying },
    )

    /** v0.27.7：播放/暂停切换（双引擎统一）。 */
    fun togglePlayPause() {
        if (isPlayingNow()) pause() else resume()
    }

    fun seekTo(ms: Long) = route({ PlayerManager.seekTo(ms) }, { MpvPlayerManager.seekTo(ms) })

    /**
     * v1.3.21 M11：UI 侧的引擎写操作走 EngineHost 转发，不直调单引擎——
     * 画面比例只对当前在播的 libmpv 生效；Media3 的比例由 PlayerView
     * 自身属性管，不走这里。
     */
    fun setAspectMode(idx: Int) {
        if (currentEngine() == PlayerEngine.LIBMPV) MpvPlayerManager.setAspectMode(idx)
    }

    /**
     * v1.3.21 M11：UI 侧的引擎初始化走 EngineHost 转发。当前只有 libmpv
     * 需要在建画面前进 init（Media3 的 init 在 EngineHost.play 里做）。
     */
    fun initIfNeeded(context: Context) {
        MpvPlayerManager.init(context)
    }

    fun stop() {
        // v1.3.17：两个引擎都停——只停「当前」引擎时，失败转移来回切后
        // 实际在播的可能是另一个（mpv 的分片播放列表还在自推进），
        // 退出播放页就停不干净。停空闲引擎是 no-op。
        PlayerManager.stop()
        MpvPlayerManager.stop()
        actualEngine = null
        // v0.294.36：停播后 8 秒内没有重新读流就结束磁力进程
        // v0.294.38：换内核 / 重试是「停 → 马上再播同一地址」，不算离开：2 秒内又播了就不释放
        // v0.294.42：列表连点下一磁力时 prepare 可能略晚于 stop——多等几秒，preparing/keep 会作废释放
        val gen = ++stopGen
        currentUrl = ""
        val c = appCtx ?: return
        // v1.3.21 M18：4 秒延迟释放改走共享主线程 Handler，不再每次起一个
        // 睡 4 秒的线程（快切集会堆线程）；代次校验不变，过期直接丢弃。
        releaseHandler.postDelayed({
            if (gen != stopGen || MagnetRemote.preparing) return@postDelayed
            // 真正离开：清 lastUrl，避免心跳线程继续 keep 把 releaseWhenIdle 顶掉后无人再释放
            if (currentUrl.isBlank()) MagnetRemote.lastUrl = ""
            MagnetRemote.releaseAsync(c)
        }, MAGNET_RELEASE_DELAY_MS)
    }

    /** 停播后磁力释放的延迟（v0.294.36 口径：8 秒内无重读流才释放，这里等 4 秒再判）。 */
    private const val MAGNET_RELEASE_DELAY_MS = 4000L

    /** 共享主线程 Handler：只挂磁力延迟释放任务，不起新线程。 */
    private val releaseHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var appCtx: Context? = null
    @Volatile private var stopGen = 0
    @Volatile private var currentUrl = ""

    /**
     * v0.294.40：这个地址是否正被当前内核播放（在播或暂停、已加载）——
     * 全屏跳页 / 返回小窗时据此直接接续同一个播放器，不重新打开地址。
     */
    fun isActiveFor(url: String): Boolean = url.isNotBlank() && url == currentUrl && (
        // v0.294.41：两个内核都看——偏好 libmpv 但实际回退在 Media3 播时，原来只查 libmpv，判成「没在播」
        PlayerManager.player?.let { it.mediaItemCount > 0 && it.playbackState != androidx.media3.common.Player.STATE_IDLE && it.playbackState != androidx.media3.common.Player.STATE_ENDED } == true ||
            MpvPlayerManager.hasFile
        )
    /** v1.3.21 L33：check-then-set 改原子 compareAndSet，并发调用只起一个心跳线程。 */
    private val beating = AtomicBoolean(false)

    /** 心跳总超时：preparing 挂起也不许永远续命，超 60 秒即退出。 */
    private const val HEARTBEAT_TIMEOUT_MS = 60_000L

    /**
     * v0.294.39：播放器会话（在播或暂停）期间每 3 秒给磁力进程发心跳。
     * 全屏跳页 / 旋转 / 换内核是「停 → 再播同一地址」，心跳不断，磁力不会被当成离开而停掉。
     */
    /** v0.294.42：磁力 prepare 一开始就挂心跳，不必等 play() */
    fun noteMagnetInterest(context: Context) {
        appCtx = context.applicationContext
        ensureHeartbeat()
    }

    private fun ensureHeartbeat() {
        if (!beating.compareAndSet(false, true)) return
        Thread({
            try {
                var idle = 0
                val startedAt = SystemClock.elapsedRealtime()
                while (true) {
                    Thread.sleep(3000)
                    val c = appCtx ?: break
                    val u = currentUrl
                    // 正在播的磁力流（当前地址就是磁力 lastUrl）是心跳的本职——
                    // 必须整个会话续命，不许超时退出。
                    val playingKeep = u.isNotBlank() && u == MagnetRemote.lastUrl
                    // v1.3.21 M18：总超时只针对 preparing 挂起——preparing 一直
                    // 为真、又没真正播起来时 60 秒退出；正在播时不退出，否则
                    // 长磁力流 60 秒后断流（无任何调用方会在播中途重挂心跳，
                    // noteMagnetInterest 只在 prepare 时调一次）。
                    if (!playingKeep &&
                        SystemClock.elapsedRealtime() - startedAt > HEARTBEAT_TIMEOUT_MS
                    ) break
                    // v0.294.42：preparing 时也续命（列表连点尚未 play）；停播后 currentUrl 已空则不再 keep
                    val keep = MagnetRemote.preparing ||
                        System.currentTimeMillis() < MagnetRemote.pendingKeepUntil ||
                        playingKeep
                    if (keep) { idle = 0; MagnetRemote.keepAsync(c) }
                    else if (++idle >= 5) break
                }
            } finally { beating.set(false) }
        }, "magnet-heartbeat").also { it.isDaemon = true }.start()
    }

    fun setPlaybackSpeed(speed: Float) = route(
        { PlayerManager.player?.setPlaybackSpeed(speed) },
        { MpvPlayerManager.setPlaybackSpeed(speed) },
    )

    /**
     * v1.3.25 合并 7：当前流的音频轨道——双引擎都推到 PlayerManager.audioTracks
     *（Media3 走 Tracks 监听，libmpv 走 track-list 解析）。界面只跟 EngineHost
     * 打交道，不直接碰 PlayerManager。
     */
    val audioTracks: StateFlow<List<TrackLanguage.AudioTrackInfo>>
        get() = PlayerManager.audioTracks

    /** v1.3.25 合并 7：手动选音轨；按实际在播的引擎路由（Media3 走 PlayerManager，libmpv 走 MpvPlayerManager）。 */
    fun selectAudioTrack(trackId: String?) {
        if (currentEngine() == PlayerEngine.MEDIA3) {
            PlayerManager.selectAudioTrack(trackId)
        } else {
            MpvPlayerManager.selectAudioTrack(trackId)
        }
    }

    /** v1.3.25 合并 5：Anime4K 画质增强（仅 libmpv 引擎生效；Media3 不支持自定义 GLSL）。 */
    fun setAnime4K(mode: Anime4K.Mode) {
        MpvPlayerManager.applyAnime4K(mode)
    }

    fun currentPosition(): Long = route(
        { PlayerManager.player?.currentPosition ?: 0L },
        { MpvPlayerManager.currentPosition },
    )

    fun duration(): Long = route(
        { (PlayerManager.player?.duration ?: 0L).coerceAtLeast(0L) },
        { MpvPlayerManager.duration },
    )

    /**
     * 统一错误回调：Media3 的 PlaybackException 适配成消息字符串，
     * 与 libmpv 的字符串回调对齐，播放器内的自动换线路重试复用同一链路。
     * v1.3.21 M7：token 门控——监听只有一个槽位，两个 PlayerScreen 实例
     * 短暂共存时先 dispose 的实例不得注销后一个实例的监听；只有持有
     * 当前 token 的实例才能清掉它（见 clearOnErrorListener）。
     */
    @Volatile private var errorListenerToken: Any? = null
    @Volatile private var errorListener: ((String) -> Unit)? = null

    fun setOnErrorListener(token: Any, listener: ((String) -> Unit)?) {
        errorListenerToken = token
        errorListener = listener
        PlayerManager.setOnErrorListener(
            listener?.let { l -> { e: PlaybackException -> l(e.message ?: "播放出错") } }
        )
        MpvPlayerManager.setOnErrorListener(listener)
    }

    /** 只有 token 与当前注册的一致时才注销；别人的监听不动。 */
    fun clearOnErrorListener(token: Any) {
        if (errorListenerToken != token) return
        errorListenerToken = null
        errorListener = null
        PlayerManager.setOnErrorListener(null)
        MpvPlayerManager.setOnErrorListener(null)
    }

    /**
     * v0.21.0：字幕样式应用（双引擎）。Media3 需传当前 PlayerView；
     * libmpv 走 mpv 属性（sub-font-size/sub-color/sub-border 等）。
     */
    fun applySubtitleStyle(style: SubtitleStyle, playerView: PlayerView?) {
        if (currentEngine() == PlayerEngine.MEDIA3) {
            playerView?.let { PlayerManager.applySubtitleStyle(it, style) }
        } else {
            MpvPlayerManager.applySubtitleStyle(style)
        }
    }

    /**
     * v0.21.0：当前画面截图，存 xiaying/Screenshots。
     * Media3 用系统抽帧，libmpv 用 screenshot-to-file；IO 线程调用，失败返回 null。
     */
    fun screenshot(
        context: Context,
        title: String,
        url: String,
        headers: Map<String, String>,
        positionMs: Long,
    ): File? = if (currentEngine() == PlayerEngine.MEDIA3) {
        PlayerManager.screenshotToFile(context, url, headers, positionMs, title)
    } else {
        MpvPlayerManager.screenshot(context, title)
    }

    private inline fun <T> route(media3: () -> T, libmpv: () -> T): T =
        if (currentEngine() == PlayerEngine.MEDIA3) media3() else libmpv()
}
