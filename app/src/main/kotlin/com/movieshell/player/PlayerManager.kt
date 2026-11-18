package com.movieshell.player

import android.content.Context
import com.movieshell.ui.components.AppToast
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.TypedValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flv.FlvExtractor
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.movieshell.core.log.LogBus
import com.movieshell.domain.model.PlayerEngine
import com.movieshell.core.util.StoragePaths
import com.movieshell.domain.model.SubtitleStyle
import com.movieshell.core.net.DEFAULT_UA
import com.movieshell.data.source.EmbyApi
import com.movieshell.data.source.SniffConfig
import com.movieshell.data.store.PlayerSettings
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ExoPlayer 单例封装。
 *
 * - [init] 幂等，多次调用只创建一次播放器；
 * - [play] 每次播放都用携带 headers 的 [DefaultHttpDataSource.Factory] 重新构建
 *   MediaSource，HLS / DASH / MP4 由 Media3 按 URL 自动适配；
 * - [release] 释放播放器，应用真正退出时调用。
 */
object PlayerManager {

    /** v0.294.42：当前接着画面的 PlayerView（全屏/小窗切换时 switchTargetView 用） */
    @Volatile var currentPlayerView: PlayerView? = null

    /** v1.0.6：播放器是否全屏（横/竖屏全屏都算），PlayerScreen 驱动，日志气泡据此隐藏。 */
    private val _isPlayerFullscreen = MutableStateFlow(false)
    val isPlayerFullscreen: StateFlow<Boolean> = _isPlayerFullscreen.asStateFlow()

    /** v1.0.6：PlayerScreen 全屏状态变化时调用，退出播放页时置 false。 */
    fun setPlayerFullscreen(v: Boolean) {
        _isPlayerFullscreen.value = v
    }

    /**
     * v1.1.19：当前流的真实画面尺寸（onVideoSizeChanged 推进；换流时清掉，
     * 防旧尺寸冒充新流）。libmpv 路不走这里，读不到时调用方用线路名推测。
     */
    private val _videoSize = MutableStateFlow<androidx.media3.common.VideoSize?>(null)
    val videoSize: StateFlow<androidx.media3.common.VideoSize?> = _videoSize.asStateFlow()

    /**
     * v1.1.19：当前流的实测下行码率（bit/s，DefaultBandwidthMeter 在
     * httpFactory 上采样推进；全走磁盘缓存、无网络流量时为 0）。
     */
    private val _bitrateEstimate = MutableStateFlow(0L)
    val bitrateEstimate: StateFlow<Long> = _bitrateEstimate.asStateFlow()

    /**
     * v1.2.14 B片（3）：当前流的音频轨道（onTracksChanged 推进；换流时清掉，
     * 防旧轨道冒充新流）。libmpv 路不走这里，读不到时为空。
     */
    private val _audioTracks = MutableStateFlow<List<TrackLanguage.AudioTrackInfo>>(emptyList())
    val audioTracks: StateFlow<List<TrackLanguage.AudioTrackInfo>> = _audioTracks.asStateFlow()

    /** v1.1.19：带宽表（init 里建；play() 里挂到 httpFactory 上测网速）。 */
    private var bandwidthMeter: androidx.media3.exoplayer.upstream.DefaultBandwidthMeter? = null

    /** v1.3.21 M17：带宽采样监听单独存一份，release() 才能摘掉它重建时再挂。 */
    private var bandwidthListener: androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener? = null


    // v0.99.0 P0-5：用浏览器 UA，自定义 App UA 会被影视 CDN/WAF 直接 403

    @Volatile
    private var exoPlayer: ExoPlayer? = null

    /** 当前 ExoPlayer 实例（[init] 之前为 null） */
    val player: ExoPlayer?
        get() = exoPlayer

    private var appContext: Context? = null
    private var onError: ((PlaybackException) -> Unit)? = null

    /** v0.100.0：当前播放地址/头/字幕（硬解失败自动切 libmpv 重播用） */
    private var lastUrl: String = ""
    private var lastHeaders: Map<String, String> = emptyMap()
    private var lastSubtitleUrl: String? = null
    /** v1.2.14：本次播放是否放行证书（硬解失败自动重播时原样带回） */
    private var lastIgnoreCert: Boolean = false

    /** v0.100.0：硬解失败自动 fallback 每次播放只做一次，play() 重置 */
    private var decoderFallbackDone: Boolean = false

    /**
     * v0.186.0 第 5 组：decoderFallbackDone 按地址重置，不在每次 play() 重置。
     * 同一地址同内核重拉不再重复触发硬解 fallback，避免 Media3 与 libmpv
     * 来回循环；换地址时重新给一次 fallback 机会。
     */
    /**
     * v1.3.21 M12：decoderFallbackUrl 存的是去 query 的 path，与
     * EngineHost.libmpvFailed 的 key（substringBefore("?")）同一口径——
     * 同一集换解析只换 auth_key 时不再视为新地址重置 fallback 额度。
     * 纯函数，可单测。
     */
    internal fun fallbackAddressKey(url: String): String = url.substringBefore("?").trim()

    private var decoderFallbackUrl: String = ""
    private var noVideoReported: String = ""

    /** v0.100.0：m3u8/ts 磁盘缓存（App 级单例；同目录建多个 SimpleCache 会抛异常） */
    @Volatile
    private var videoCache: SimpleCache? = null

    /**
     * v1.2.14：第 6 项——建缓存时用的设置快照（上限 MB 与保留天数）。
     * 与当前设置不一致时，下次起播 [ensureVideoCacheSettings] 重建缓存生效。
     */
    private var cacheCapMbAtBuild: Int = VideoCacheSettings.DEFAULT_CAP_MB
    private var cacheMaxAgeDaysAtBuild: Int = VideoCacheSettings.DEFAULT_MAX_AGE_DAYS

    @Volatile private var firstFrameAt = 0L
    /**
     * v1.3.21：懒初始化——对象级直接 `Handler(Looper.getMainLooper())`
     * 会让纯 JUnit 单测在引用 PlayerManager 的纯函数（如
     * fallbackAddressKey）时就触发 <clinit> 抛 "Stub!"（无 Robolectric
     * 时 Looper.getMainLooper() 是桩实现）；懒加载后只有真在设备上
     * 跑播放逻辑时才建 Handler，单测引用纯函数不再炸。
     */
    private val mainHandler: android.os.Handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var noFrameGen = 0

    /** v0.294.38：解码器名 / 初始化失败写进日志 */
    private val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
        ) { LogBus.i("Player", "视频解码器：$decoderName") }
        override fun onAudioDecoderInitialized(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
        ) { LogBus.i("Player", "音频解码器：$decoderName") }
        override fun onVideoCodecError(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, videoCodecError: Exception,
        ) { LogBus.e("Player", "视频解码出错：${videoCodecError.message}") }
    }

    /** 就绪且在播、选中了视频轨，6 秒仍没首帧 → 认定解不了画面，换 libmpv */
    private fun scheduleNoFrameCheck() {
        val gen = ++noFrameGen
        val started = android.os.SystemClock.elapsedRealtime()
        mainHandler.postDelayed({
            if (gen != noFrameGen) return@postDelayed
            val p = exoPlayer ?: return@postDelayed
            if (firstFrameAt >= started || !p.isPlaying) return@postDelayed
            val hasVideo = p.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            if (hasVideo && p.videoSize.width == 0) listenerSwitch("就绪 6 秒仍没有画面（有声音）")
        }, 6000)
    }
    private fun listenerSwitch(reason: String) = switchToMpvImpl(reason)

    private val listener = object : Player.Listener {
        // v0.294.37：本机磁力 / 迅雷地址记首帧与画面尺寸，便于排查黑屏
        override fun onRenderedFirstFrame() {
            firstFrameAt = android.os.SystemClock.elapsedRealtime()
            if (decoderFallbackUrl.startsWith("http://127.0.0.1")) LogBus.i("Player", "本机磁力流：首帧已渲染")
        }
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width > 0) _videoSize.value = videoSize
            if (decoderFallbackUrl.startsWith("http://127.0.0.1") && videoSize.width > 0)
                LogBus.i("Player", "本机磁力流：画面 ${videoSize.width}×${videoSize.height}")
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) scheduleNoFrameCheck()
            if (decoderFallbackUrl.startsWith("http://127.0.0.1"))
                LogBus.i("Player", "本机磁力流：状态 " + when (playbackState) { 1 -> "空闲"; 2 -> "缓冲中"; 3 -> "就绪"; 4 -> "结束"; else -> "$playbackState" })
        }
        // v0.294.35：只有音轨没有视频轨（听书等）→ 通知详情页改用音乐播放器
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            if (tracks.groups.isEmpty()) return
            // v1.2.14 B片（3）：音频轨道推给 UI（齿轮页音轨选择用）
            _audioTracks.value = AudioTracks.fromTracks(tracks)
            // v0.294.38：记录每条轨道（类型 / 编码 / 分辨率 / 设备是否支持 / 是否选中）
            tracks.groups.forEach { g ->
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    val type = when (g.type) { C.TRACK_TYPE_VIDEO -> "视频"; C.TRACK_TYPE_AUDIO -> "音频"; C.TRACK_TYPE_TEXT -> "字幕"; else -> "其他" }
                    LogBus.i("Player", "轨道 $type ${f.sampleMimeType} ${f.codecs ?: ""} ${if (f.width > 0) "${f.width}×${f.height}" else ""} " +
                        "支持=${g.isTrackSupported(i)} 选中=${g.isTrackSelected(i)}")
                }
            }
            // 有视频轨但设备解不了（如 HEVC 10bit / 高规格）→ 自动换 libmpv 软解
            val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            if (videoGroups.isNotEmpty() && videoGroups.none { g -> (0 until g.length).any { g.isTrackSupported(it) } }) {
                val f = videoGroups.first().getTrackFormat(0)
                switchToMpv("视频编码 ${f.sampleMimeType} ${f.codecs ?: ""} 本机硬解不支持")
                return
            }
            val hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
            val hasAudio = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
            if (hasAudio && !hasVideo && decoderFallbackUrl.isNotBlank() && noVideoReported != decoderFallbackUrl) {
                noVideoReported = decoderFallbackUrl
                LogBus.i("Player", "没有视频轨，只有音频")
                AudioRoute.reportNoVideo(decoderFallbackUrl)
            }
        }

        private fun switchToMpv(reason: String) = switchToMpvImpl(reason)
    }

    private fun switchToMpvImpl(reason: String) {
        run {
            if (decoderFallbackDone) return
            // v1.3.15：libmpv 已在本集失败过，不再切回去空转；留 Media3 真正试一次解码。
            // v1.3.21 M13：被拦住也不再静默返回——调 onError 让失败进换线路链，
            // 与 listener2 解码失败分支一致；M14：被拦住不置 decoderFallbackDone，
            // 唯一一次 fallback 机会只在真正发起切换时才消耗。
            if (lastUrl.isNotBlank() && EngineHost.libmpvFailed(lastUrl)) {
                LogBus.i("Player", "$reason；libmpv 已在本集失败过，不再自动切过去，留 Media3 尝试")
                onError?.invoke(
                    PlaybackException(
                        "$reason；libmpv 已在本集失败过", null,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    ),
                )
                return
            }
            val app = appContext ?: run {
                onError?.invoke(
                    PlaybackException(
                        "$reason；播放器上下文已释放", null,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    ),
                )
                return
            }
            if (!MpvPluginLoader.isInstalled(app)) {
                LogBus.w("Player", "$reason；未装 libmpv 插件，无法软解")
                AppToast.show(app, "该视频编码本机解不了（只有声音），请在插件市场安装 libmpv 播放器后重试", long = true)
                onError?.invoke(
                    PlaybackException(
                        "$reason；未安装 libmpv 插件", null,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    ),
                )
                return
            }
            // v1.3.21 M14：到这里才真正发起切换，此时才消耗本次 fallback 机会。
            decoderFallbackDone = true
            LogBus.i("Player", "$reason，自动切 libmpv 重播")
            AppToast.show(app, "视频编码本机不支持，已自动切换 libmpv 播放", long = false)
            val pos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
            exoPlayer?.stop()
            EngineHost.markEngineTriedByFallback(PlayerEngine.MEDIA3)
            EngineHost.setEngineOverride(PlayerEngine.LIBMPV)
            EngineHost.play(app, lastUrl, lastHeaders, pos, lastSubtitleUrl, ignoreCert = lastIgnoreCert)
        }
    }

    private val listener2 = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            LogBus.e("Player", "播放器错误：${error.errorCodeName} ${error.message}")
            // v0.100.0：硬解初始化失败 → 自动切 libmpv 重播（每次播放只做一次）。
            // 播放页另有通用换内核逻辑（EngineHost.setOnErrorListener），这里接管后不再外抛，
            // 避免两处重复切换；libmpv 再失败会走播放页逻辑回到 Media3 后试其他线路。
            if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED &&
                !decoderFallbackDone
            ) {
                val app = appContext
                // v1.3.15：libmpv 已在本集失败过，不再切回去空转；落到下面的 onError 走换线路。
                // v1.3.21 M14：被拦住时不置 decoderFallbackDone，唯一一次机会
                // 只在下面真正发起切换时才消耗。
                val mpvAlreadyFailed = lastUrl.isNotBlank() && EngineHost.libmpvFailed(lastUrl)
                if (mpvAlreadyFailed) {
                    LogBus.i("Player", "硬解初始化失败；libmpv 已在本集失败过，不再自动切过去")
                } else if (app != null && MpvPluginLoader.isInstalled(app)) {
                    decoderFallbackDone = true
                    AppToast.show(app, "硬解失败，已自动切换 libmpv 重播", long = false)
                    LogBus.i("Player", "硬解初始化失败，自动切 libmpv 重播")
                    // v0.186.0 第 5 组：换内核三步固定——先取断点，再显式停
                    // ExoPlayer，最后改覆盖起播；同时把已试过的 Media3 记入
                    // EngineHost 的 fallback 记账，播放页换内核判定不再切回它。
                    val pos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                    exoPlayer?.stop()
                    EngineHost.markEngineTriedByFallback(PlayerEngine.MEDIA3)
                    EngineHost.setEngineOverride(PlayerEngine.LIBMPV)
                    EngineHost.play(app, lastUrl, lastHeaders, pos, lastSubtitleUrl, ignoreCert = lastIgnoreCert)
                    return
                } else if (app != null) {
                    AppToast.show(app, "硬解失败，可在设置安装 libmpv 插件切换", long = false)
                }
            }
            onError?.invoke(error)
        }
    }

    /** 幂等初始化，传入任意 Context 均可（内部取 applicationContext） */
    @Synchronized
    fun init(context: Context) {
        if (exoPlayer != null) return
        val app = context.applicationContext
        appContext = app
        // v1.1.19：带宽表——play() 里挂到 httpFactory 上做 TransferListener，
        // 采样到的码率推进 bitrateEstimate，供播放页顶栏显示网速。
        if (bandwidthMeter == null) {
            bandwidthMeter = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(app).build()
            val listener = object : androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener {
                override fun onBandwidthSample(elapsedMs: Int, bytesTransferred: Long, bitrateEstimate: Long) {
                    _bitrateEstimate.value = bitrateEstimate
                }
            }
            bandwidthListener = listener
            bandwidthMeter?.addEventListener(
                android.os.Handler(android.os.Looper.getMainLooper()),
                listener,
            )
        }
        // v0.102.0 P2-4：自适应码率上限 1080p（默认开启自适应，这里只封顶分辨率）
        val trackSelector = DefaultTrackSelector(app)
        trackSelector.setParameters(
            trackSelector.buildUponParameters().setMaxVideoSize(1920, 1080),
        )
        exoPlayer = ExoPlayer.Builder(app)
            .setTrackSelector(trackSelector)
            // v0.294.5：接管音频焦点（来电/别的 App 放声时暂停，结束后按系统规则恢复）
            // 并在耳机拔出/蓝牙断开时自动暂停，不外放。
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also {
                it.addListener(listener)
                it.addListener(listener2)
                it.addAnalyticsListener(analytics)
            }
    }

    /** 设置播放错误回调，传 null 取消 */
    fun setOnErrorListener(listener: ((PlaybackException) -> Unit)?) {
        onError = listener
    }

    /**
     * 播放 [url]。headers 会作为本次请求的默认请求头；
     * 其中 "User-Agent" 会单独走工厂的 UA 通道（保证真正生效，
     * 不受 [DefaultHttpDataSource.Factory] 内置 UA 覆盖）。
     *
     * @param startPositionMs 起播位置（毫秒），用于断点续播，<=0 表示从头播。
     * @param subtitleUrl 外挂字幕地址（.srt/.vtt/.ass，http(s) 或 file:// 本地），为空则不挂字幕。
     * @return v1.3.21 L1：是否真的提交了起播——播放器/上下文为空、地址为空时
     * 同步拒绝返回 false，EngineHost.play() 据此回 false，不再谎报成功。
     */
    fun play(
        url: String,
        headers: Map<String, String> = emptyMap(),
        startPositionMs: Long = 0L,
        subtitleUrl: String? = null,
        live: Boolean = false,
        // v1.2.14：Emby 自签证书播放（EngineHost 带来，该台服务器的
        // EmbyServer.ignoreCert）；其他入口默认关，链路一丝不变。
        ignoreCert: Boolean = false,
    ): Boolean {
        // v0.102.0 P2-6：视频开播联动暂停音乐——只暂停不自动恢复，恢复走音乐迷你条手动。
        // MusicPlayer 无 fun pause()（只读），用现有 fadeOutAndPause（保留队列/状态）。
        // v0.109.0 A5：联动条件覆盖"解析中"态——点歌后 loading=true/playing=false 时播视频，
        // 音乐直链稍后回来会盖过视频声，故 playing||loading 都暂停；
        // controller 为空时 fadeOutAndPause 内部 ?: return 直接收敛。
        val ms = MusicPlayer.state.value
        if (ms.playing || ms.loading) {
            MusicPlayer.fadeOutAndPause()
        }
        val player = exoPlayer ?: return false
        val app = appContext ?: return false
        // v0.12.1：空 URL 直接拒掉，避免 ExoPlayer 抛异常闪退
        if (url.isBlank()) {
            LogBus.e("Player", "播放地址为空，已拒绝")
            return false
        }
        // v0.100.0：记下本次播放参数，供硬解失败自动切 libmpv 重播
        // v0.186.0 第 5 组：decoderFallbackDone 只在地址变化时重置
        lastUrl = url
        lastHeaders = headers
        lastSubtitleUrl = subtitleUrl
        lastIgnoreCert = ignoreCert
        // v1.1.19：换流清掉旧的尺寸与码率，顶栏不拿上一条流的数据冒充这一条。
        _videoSize.value = null
        _bitrateEstimate.value = 0L
        // v1.2.14 B片（3）：换流清掉旧音轨；首选音频语言喂给选择器，起播即按
        // 偏好选轨（无偏好时传 null 走默认轨）。手动选轨的 override 由
        // selectAudioTrack 写，偏好只在无 override 时生效。
        _audioTracks.value = emptyList()
        runCatching {
            val pref = PlayerSettings.audioLanguage.value.ifBlank { null }
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(pref)
                .build()
        }.onFailure { LogBus.w("Player", "首选音频语言应用失败：${it.message}") }
        // v1.3.21 M12：fallback 地址按去 query 的 path 记，与 libmpvFailed 同口径。
        if (fallbackAddressKey(url) != decoderFallbackUrl) {
            decoderFallbackUrl = fallbackAddressKey(url)
            decoderFallbackDone = false
        }
        // v0.14.5：记录播放地址（截断防刷屏）
        LogBus.i("Player", "播放: ${url.take(120)}${if (url.length > 120) "..." else ""}")
        // v0.294.7：键名规整（小写 user-agent 也认作 UA，不再被默认 UA 顶掉）
        val rest = PlayHeaders.normalize(headers).toMutableMap()
        val ua = rest.remove("User-Agent")?.takeIf { it.isNotBlank() } ?: DEFAULT_UA
        // v1.2.14：自签 Emby 播放走 OkHttp 放行数据源——media3-datasource-okhttp
        // 已在依赖里，OkHttpDataSource.Factory 直接吃放行证书的 OkHttpClient，
        // 作用域只限本次播放的 factory；超时走该 client 自身的 15 秒口径，
        // 跨协议重定向 OkHttp 默认即跟随；其他播放走原 DefaultHttpDataSource。
        val httpFactory: androidx.media3.datasource.HttpDataSource.Factory =
            if (EngineHost.shouldBypassCert(ignoreCert, url)) {
                OkHttpDataSource.Factory(EmbyApi.insecurePlaybackClient)
                    .setUserAgent(ua)
                    // v1.1.19：带宽表挂上游——只计量真正走网络的流量（缓存命中不经过这里），
                    // 采样码率进 bitrateEstimate，供顶栏显示网速。
                    .setTransferListener(bandwidthMeter)
            } else {
                DefaultHttpDataSource.Factory()
                    .setUserAgent(ua)
                    .setConnectTimeoutMs(DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS)
                    // v0.294.34：本机磁力边下边播（127.0.0.1）读数据要等下载，放宽到 3 分钟，别在缓冲中途超时
                    .setReadTimeoutMs(
                        if (url.startsWith("http://127.0.0.1")) 180_000 else DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS,
                    )
                    .setAllowCrossProtocolRedirects(true)
                    // v1.1.19：带宽表挂上游——只计量真正走网络的流量（缓存命中不经过这里），
                    // 采样码率进 bitrateEstimate，供顶栏显示网速。
                    .setTransferListener(bandwidthMeter)
            }
        if (rest.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(rest)
        }
        // v0.18.0：m3u8 去广告——包装 DataSource，过滤播放列表中的广告分片
        // v0.21.0：外层包 DefaultDataSource，字幕时间偏移后的 file:// 本地字幕也能读
        // v0.97.0：受 SniffConfig.adFilterEnabled 总开关门控（顶栏溢出菜单可关，改完即时生效）
        SniffConfig.ensureLoaded(app)
        // v0.100.0：m3u8 播放列表/ts 分片磁盘缓存（上限与保留时长可调，见 VideoCacheSettings；缓存出错时忽略缓存走网络）
        // v0.109.0 A11：去广告包在缓存外层——缓存里存的是过滤前的原文，
        // 每次读出再按当前 adFilterEnabled 过滤，"改完即时生效"才真正成立；
        // 之前 CacheDataSource 在外层时，被过滤改写过的列表原文会写入 SimpleCache，
        // 关掉总开关后缓存仍按过滤版 serving。
        // v1.2.14：第 6 项——上限/保留时长改过时，先按新设置重建缓存再起播
        // （旧源正要被替换，此时重建不丢正在播的分片；设置没变时不动作）。
        ensureVideoCacheSettings(app)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(videoCache(app))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(app, httpFactory))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // v0.294.5：直播流不进磁盘缓存——直播分片只播一次，写缓存纯属白耗闪存、
        // 还会把上限内的点播缓存挤掉；非 http(s) 协议同样直连上游。
        val bypassCache = live || isLiveLikeUrl(url)
        val upstreamFactory: androidx.media3.datasource.DataSource.Factory =
            if (bypassCache) DefaultDataSource.Factory(app, httpFactory) else cacheFactory
        if (bypassCache) LogBus.i("Player", "直播流：不走视频磁盘缓存")
        val adFilterFactory = AdFilteringDataSource.Factory(
            upstreamFactory,
            enabledProvider = { SniffConfig.adFilterEnabled },
        )
        // v0.19.0：显式声明 FLV 提取器（HTTP-FLV 直播/点播），不依赖默认工厂的隐含行为
        val extractorsFactory = ExtractorsFactory {
            arrayOf<Extractor>(FlvExtractor()) + DefaultExtractorsFactory().createExtractors()
        }
        // v0.294.7（H6）：无后缀 HLS/DASH 显式声明 MIME（URL 推断或起播前探测结果），
        // 否则 Media3 按后缀当普通文件、报无法识别格式。看不出类型时不设，行为同旧版。
        val mime = when (MediaTypeSniffer.hint(url)) {
            MediaTypeSniffer.Kind.HLS -> MimeTypes.APPLICATION_M3U8
            MediaTypeSniffer.Kind.DASH -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        if (mime != null && !url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".mpd") }) {
            LogBus.i("Player", "按识别结果声明流类型：$mime")
        }
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .apply { if (mime != null) setMimeType(mime) }
            .apply {
                // v0.19.0：外挂字幕（srt/vtt）
                val sub = subtitleUrl?.trim().orEmpty()
                if (sub.isNotEmpty()) {
                    subtitleMimeType(sub)?.let { mime ->
                        setSubtitleConfigurations(
                            listOf(
                                MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                                    .setMimeType(mime)
                                    .setLanguage("zh")
                                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                    .build()
                            )
                        )
                        LogBus.i("Player", "挂载外挂字幕：$mime")
                    }
                }
            }
            .build()
        val mediaSource = DefaultMediaSourceFactory(app, extractorsFactory)
            .setDataSourceFactory(adFilterFactory)
            .createMediaSource(mediaItem)
        player.setMediaSource(mediaSource)
        player.prepare()
        if (startPositionMs > 0) player.seekTo(startPositionMs)
        player.playWhenReady = true
        return true
    }

    /** v0.294.5：非 http(s) 的流式协议（rtmp/rtsp/rtp/udp/srt）一律视为直播 */
    private fun isLiveLikeUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        return listOf("rtmp://", "rtmps://", "rtsp://", "rtp://", "udp://", "srt://", "mms://")
            .any { u.startsWith(it) }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume(startPositionMs: Long = -1L) {
        // v0.294.51：stop() 后的 Media3 处于 IDLE、ENDED 后 play() 单独
        // 调用不起播——这正是“回小窗暂停后点播放没反应”的第二道保险：
        // IDLE/ENDED 时先 prepare() 再 play()，READY 暂停时只 play()，
        // 不动用户有意暂停的状态判断（isPlaying 仍由调用方看）。
        // v1.3.21 L8：IDLE/ENDED 时 prepare() 会回到默认起播位置而丢断点；
        // 调用方传了 startPositionMs（>=0）时 prepare 后 seek 回去。
        // 直播调用方不传（默认 -1），仍回直播沿。
        val p = exoPlayer ?: return
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
            p.prepare()
            if (startPositionMs >= 0) p.seekTo(startPositionMs)
        }
        p.play()
    }

    fun seekTo(ms: Long) {
        exoPlayer?.seekTo(ms)
    }

    /**
     * v1.2.14 B片（3）：手动选音轨（齿轮页音轨选择用）。
     *
     * @param trackId AudioTracks.fromTracks 给的 "组下标:轨下标"，null=回到自动
     * （清掉本类型的 override，重新按首选语言/默认选轨）。
     * id 对不上当前流的轨道时直接忽略，不报错、不乱切。
     * 注意：override 只对含完全相同 TrackGroup 的媒体生效（Media3 规则），
     * 切集后旧 override 自动失效，不会串到下一集。
     */
    fun selectAudioTrack(trackId: String?) {
        val player = exoPlayer ?: return
        runCatching {
            val params = player.trackSelectionParameters.buildUpon()
            if (trackId == null) {
                params.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            } else {
                val groupIndex = trackId.substringBefore(":").toIntOrNull() ?: return
                val trackIndex = trackId.substringAfter(":", "").toIntOrNull() ?: return
                val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
                if (group.type != C.TRACK_TYPE_AUDIO || trackIndex !in 0 until group.length) return
                params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            }
            player.trackSelectionParameters = params.build()
            LogBus.i("Player", "音轨切换：${trackId ?: "自动"}")
        }.onFailure { LogBus.w("Player", "音轨切换失败：${it.message}") }
    }

    fun stop() {
        exoPlayer?.stop()
    }

    /**
     * v0.100.0：App 级视频磁盘缓存单例。SimpleCache 同一目录只允许一个实例，
     * 这里双重检查锁保证单例；生命周期与 App 一致，不释放。
     * v1.2.14：第 6 项——上限与保留时长读 VideoCacheSettings（默认 500MB / 30 天），
     * 驱逐走 VideoCacheEvictor（LRU 尺寸加超期清理）。
     */
    private fun videoCache(app: Context): SimpleCache {
        return videoCache ?: synchronized(this) {
            videoCache ?: SimpleCache(
                File(app.cacheDir, "video_cache"),
                VideoCacheEvictor(
                    VideoCacheSettings.capBytes(VideoCacheSettings.capMb(app)),
                    VideoCacheSettings.maxAgeMs(VideoCacheSettings.maxAgeDays(app)),
                ),
                StandaloneDatabaseProvider(app),
            ).also {
                videoCache = it
                cacheCapMbAtBuild = VideoCacheSettings.capMb(app)
                cacheMaxAgeDaysAtBuild = VideoCacheSettings.maxAgeDays(app)
            }
        }
    }

    /**
     * v1.2.14：第 6 项——上限或保留时长改动后，下次起播重建缓存生效。
     *
     * 只在 play() 里调：此时旧 MediaSource 正要被新源替换，旧源的
     * CacheDataSource 即便还读着已释放的缓存，也会走
     * FLAG_IGNORE_CACHE_ON_ERROR 回上游，不会崩、不断播；
     * 正在播的分片本身不删——超期清理只清 N 天没碰过的分片，
     * 正在读的分片读前刚被触摸过，时间戳是新的。
     * 设置没变时直接返回，不动缓存。
     */
    private fun ensureVideoCacheSettings(app: Context) {
        if (videoCache == null) return
        if (cacheCapMbAtBuild == VideoCacheSettings.capMb(app) &&
            cacheMaxAgeDaysAtBuild == VideoCacheSettings.maxAgeDays(app)
        ) return
        releaseCache()
        LogBus.i("Player", "视频缓存设置已改，下次播放起用新上限与保留时长重建缓存")
    }

    /**
     * v0.101.0：关闭视频磁盘缓存单例（设置页清理缓存用；下次播放 [videoCache] 懒加载重建）。
     * 注意顺序：必须先 release 再删 video_cache 目录——删开着的 SimpleCache 会损坏其索引。
     */
    @Synchronized
    fun releaseCache() {
        runCatching { videoCache?.release() }
        videoCache = null
    }

    /** 释放播放器；释放后如需继续播放必须重新 [init] */
    @Synchronized
    fun release() {
        // v1.3.21 M17：两个监听器 + 统计监听 + 主线程待办 + 带宽监听全摘，
        // 再复位本次播放的 fallback/断点状态；重 init 后同一 URL 能重新 fallback。
        exoPlayer?.removeListener(listener)
        exoPlayer?.removeListener(listener2)
        exoPlayer?.removeAnalyticsListener(analytics)
        mainHandler.removeCallbacksAndMessages(null)
        bandwidthListener?.let { bandwidthMeter?.removeEventListener(it) }
        bandwidthListener = null
        bandwidthMeter = null
        exoPlayer?.release()
        exoPlayer = null
        appContext = null
        onError = null
        lastUrl = ""
        lastHeaders = emptyMap()
        lastSubtitleUrl = null
        lastIgnoreCert = false
        decoderFallbackUrl = ""
        decoderFallbackDone = false
        noVideoReported = ""
    }

    /**
     * v0.21.0：字幕样式应用到 PlayerView 的内嵌字幕视图。
     * 位置用底部内边距比例实现：底部=贴底，顶部=顶到 0.85。
     */
    fun applySubtitleStyle(playerView: PlayerView, style: SubtitleStyle) {
        val sv = playerView.subtitleView ?: return
        val edgeType = when (style.edge) {
            SubtitleStyle.EDGE_OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            SubtitleStyle.EDGE_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            else -> CaptionStyleCompat.EDGE_TYPE_NONE
        }
        sv.setStyle(
            CaptionStyleCompat(
                style.color, Color.TRANSPARENT, Color.TRANSPARENT,
                edgeType, Color.BLACK, null,
            )
        )
        sv.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, style.sizeSp.toFloat())
        sv.setBottomPaddingFraction(
            if (style.position == SubtitleStyle.POS_TOP) 0.85f else 0.08f
        )
    }

    /**
     * v0.21.0：当前画面截图（系统 MediaMetadataRetriever 抽帧）。
     * 必须在 IO 线程调用；返回保存的文件，失败返回 null。
     * 只支持 http(s) 直链（磁力/m3u8 可能抽帧失败，走 runCatching 兜底）。
     */
    fun screenshotToFile(
        context: Context,
        url: String,
        headers: Map<String, String>,
        positionMs: Long,
        title: String,
    ): File? {
        if (!url.startsWith("http", ignoreCase = true)) return null
        return runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                if (headers.isEmpty()) mmr.setDataSource(url) else mmr.setDataSource(url, headers)
                val frame: Bitmap =
                    mmr.getFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: return@runCatching null
                // v0.112.0：frame 包装 try/finally——compress 抛异常时也要 recycle，
                // 否则 Bitmap 泄漏。外层 try/finally 管 mmr.release() 保持不变。
                try {
                    val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40).ifBlank { "截图" }
                    val file = File(
                        StoragePaths.screenshotsDir(context),
                        "侠影_${safe}_${System.currentTimeMillis()}.png",
                    )
                    FileOutputStream(file).use { out ->
                        frame.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    LogBus.i("Player", "截图已保存：${file.absolutePath}")
                    file
                } finally {
                    frame.recycle()
                }
            } finally {
                runCatching { mmr.release() }
            }
        }.getOrElse {
            LogBus.w("Player", "截图失败：${it.message}")
            null
        }
    }

    /**
     * 外挂字幕地址 -> MIME：.srt / .vtt / .ass，其他返回 null（不挂）。
     * v0.21.0：.ass 走 MimeTypes.TEXT_SSA（Media3 1.4.1 内建 SsaParser，已实查源码）。
     */
    private fun subtitleMimeType(url: String): String? {
        val lower = url.substringBefore("?").lowercase()
        return when {
            lower.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            lower.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> null
        }
    }
}
