package com.movieshell.player

import android.content.Context
import com.movieshell.ui.components.AppToast
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.ui.PlayerView
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PlayerEngine
import com.movieshell.data.repo.PlayerSettings
import com.movieshell.data.repo.SubtitleStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

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
    ): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        appCtx = context.applicationContext
        stopGen++
        currentUrl = url
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
        // v0.152.0：RTMP 只能走 libmpv（Media3 不吃 rtmp），且不改用户持久化选择。
        // 未装插件时直接拒绝、不切内核；libmpv 初始化失败过时也不回退 Media3（回退也播不了）。
        // v0.162.0：原生 rtp:// 同理（组播只在局域网 WiFi 下有意义，蜂窝下播不了属环境限制，
        // 不是代码能保证的；不做 /status 探活，播了才知道）。
        val isRtmp = url.trim().startsWith("rtmp://", true)
        val isRtp = url.trim().startsWith("rtp://", true)
        if (isRtmp || isRtp) {
            val proto = if (isRtmp) "RTMP" else "RTP"
            if (!MpvPluginLoader.isInstalled(context)) {
                LogBus.w("Play", "$proto 需 libmpv 插件，未安装已拒绝")
                AppToast.show(context, "$proto 需 libmpv 插件，请先到插件市场安装", long = true)
                return false
            }
            if (MpvPlayerManager.initFailed) {
                LogBus.w("Play", "$proto 需 libmpv，但 libmpv 初始化失败过，已拒绝")
                AppToast.show(context, "libmpv 不可用，$proto 无法播放", long = false)
                return false
            }
            if (engine() == PlayerEngine.MEDIA3) {
                setEngineOverride(PlayerEngine.LIBMPV)
                LogBus.i("Play", "$proto 走 libmpv（临时覆盖，不改用户设置）")
            }
        }
        var eng = engine()
        if (eng == PlayerEngine.LIBMPV && MpvPlayerManager.initFailed) {
            LogBus.w("Play", "libmpv 已禁用（初始化失败过），回退 Media3")
            AppToast.show(context, "libmpv 不可用，已切回 Media3", long = false)
            setEngineOverride(PlayerEngine.MEDIA3)
            eng = PlayerEngine.MEDIA3
        }
        return try {
            if (eng == PlayerEngine.MEDIA3) {
                PlayerManager.init(context)
                PlayerManager.play(url, headers, startPositionMs, subtitleUrl, live)
            } else {
                MpvPlayerManager.init(context)
                MpvPlayerManager.play(url, headers, startPositionMs, subtitleUrl)
            }
            val cost = SystemClock.elapsedRealtime() - t0
            // v0.25.0：结构化诊断日志（URL 脱敏：只留 host + 后缀）
            LogBus.i(
                "Play",
                "start engine=$eng host=${urlHost(url)} ext=${urlExt(url)} " +
                    "cost=${cost}ms pos=${startPositionMs}ms sub=${!subtitleUrl.isNullOrBlank()}",
            )
            true
        } catch (t: Throwable) {
            LogBus.e("Play", "起播异常 engine=$eng ${t::class.java.simpleName}: ${t.message}")
            false
        }
    }

    /** 播放地址合法性：非空 + 协议白名单（http/https/magnet/file/本地绝对路径 + v0.152.0 的 rtmp + v0.162.0 的 rtp）。
     * v0.218.0（S12）：pics:// 不再算可播——原来白名单含 pics，起播又静默
     * 拒绝，两处对同一地址结论相反；看图不走视频引擎（走 PicsViewerScreen
     * 分流），这里排除后 pics 进来走上面的非法地址分支，给可见提示。 */
    fun isPlayableUrl(url: String): Boolean {
        val u = url.trim()
        if (u.isBlank()) return false
        return u.startsWith("http://", true) || u.startsWith("https://", true) ||
            u.startsWith("magnet:", true) || u.startsWith("file://", true) ||
            u.startsWith("rtmp://", true) || u.startsWith("rtp://", true) ||
            u.startsWith("/")
    }

    /** URL 脱敏：host（日志不记完整地址）。 */
    private fun urlHost(url: String): String =
        runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")

    /** URL 后缀（m3u8/mp4/flv 等，用于定位格式相关问题）。 */
    private fun urlExt(url: String): String =
        url.substringBefore("?").substringAfterLast(".", "").take(8)

    fun pause() = route({ PlayerManager.pause() }, { MpvPlayerManager.pause() })

    fun resume() = route({ PlayerManager.resume() }, { MpvPlayerManager.resume() })

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

    fun stop() {
        route({ PlayerManager.stop() }, { MpvPlayerManager.stop() })
        // v0.294.36：停播后 8 秒内没有重新读流就结束磁力进程
        // v0.294.38：换内核 / 重试是「停 → 马上再播同一地址」，不算离开：2 秒内又播了就不释放
        // v0.294.42：列表连点下一磁力时 prepare 可能略晚于 stop——多等几秒，preparing/keep 会作废释放
        val gen = ++stopGen
        currentUrl = ""
        val c = appCtx ?: return
        Thread({
            Thread.sleep(4000)
            if (gen != stopGen || MagnetRemote.preparing) return@Thread
            // 真正离开：清 lastUrl，避免心跳线程继续 keep 把 releaseWhenIdle 顶掉后无人再释放
            if (currentUrl.isBlank()) MagnetRemote.lastUrl = ""
            MagnetRemote.releaseAsync(c)
        }, "magnet-release-delay").also { it.isDaemon = true }.start()
    }

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
    @Volatile private var beating = false

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
        if (beating) return
        beating = true
        Thread({
            try {
                var idle = 0
                while (true) {
                    Thread.sleep(3000)
                    val c = appCtx ?: break
                    val u = currentUrl
                    // v0.294.42：preparing 时也续命（列表连点尚未 play）；停播后 currentUrl 已空则不再 keep
                    val keep = MagnetRemote.preparing ||
                        System.currentTimeMillis() < MagnetRemote.pendingKeepUntil ||
                        (u.isNotBlank() && u == MagnetRemote.lastUrl)
                    if (keep) { idle = 0; MagnetRemote.keepAsync(c) }
                    else if (++idle >= 5) break
                }
            } finally { beating = false }
        }, "magnet-heartbeat").also { it.isDaemon = true }.start()
    }

    fun setPlaybackSpeed(speed: Float) = route(
        { PlayerManager.player?.setPlaybackSpeed(speed) },
        { MpvPlayerManager.setPlaybackSpeed(speed) },
    )

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
     */
    fun setOnErrorListener(listener: ((String) -> Unit)?) {
        PlayerManager.setOnErrorListener(
            listener?.let { l -> { e: PlaybackException -> l(e.message ?: "播放出错") } }
        )
        MpvPlayerManager.setOnErrorListener(listener)
    }

    /**
     * v0.21.0：字幕样式应用（双引擎）。Media3 需传当前 PlayerView；
     * libmpv 走 mpv 属性（sub-font-size/sub-color/sub-border 等）。
     */
    fun applySubtitleStyle(style: SubtitleStyle, playerView: PlayerView?) {
        if (engine() == PlayerEngine.MEDIA3) {
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
    ): File? = if (engine() == PlayerEngine.MEDIA3) {
        PlayerManager.screenshotToFile(context, url, headers, positionMs, title)
    } else {
        MpvPlayerManager.screenshot(context, title)
    }

    private inline fun <T> route(media3: () -> T, libmpv: () -> T): T =
        if (engine() == PlayerEngine.MEDIA3) media3() else libmpv()
}
