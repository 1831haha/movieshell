package com.movieshell.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.movieshell.data.repo.LogBus

/**
 * ExoPlayer 单例封装。
 *
 * - [init] 幂等，多次调用只创建一次播放器；
 * - [play] 每次播放都用携带 headers 的 [DefaultHttpDataSource.Factory] 重新构建
 *   MediaSource，HLS / DASH / MP4 由 Media3 按 URL 自动适配；
 * - [release] 释放播放器，应用真正退出时调用。
 */
object PlayerManager {

    private const val USER_AGENT = "MovieShell/0.1.0 (Android)"

    @Volatile
    private var exoPlayer: ExoPlayer? = null

    /** 当前 ExoPlayer 实例（[init] 之前为 null） */
    val player: ExoPlayer?
        get() = exoPlayer

    private var appContext: Context? = null
    private var onError: ((PlaybackException) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            LogBus.e("Player", "播放器错误：${error.errorCodeName} ${error.message}")
            onError?.invoke(error)
        }
    }

    /** 幂等初始化，传入任意 Context 均可（内部取 applicationContext） */
    @Synchronized
    fun init(context: Context) {
        if (exoPlayer != null) return
        val app = context.applicationContext
        appContext = app
        exoPlayer = ExoPlayer.Builder(app).build().also {
            it.addListener(listener)
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
     */
    fun play(url: String, headers: Map<String, String> = emptyMap(), startPositionMs: Long = 0L) {
        val player = exoPlayer ?: return
        val app = appContext ?: return
        val rest = headers.toMutableMap()
        val ua = rest.remove("User-Agent")?.takeIf { it.isNotBlank() } ?: USER_AGENT
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setConnectTimeoutMs(DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS)
            .setReadTimeoutMs(DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS)
            .setAllowCrossProtocolRedirects(true)
        if (rest.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(rest)
        }
        val mediaSource = DefaultMediaSourceFactory(app)
            .setDataSourceFactory(httpFactory)
            .createMediaSource(MediaItem.fromUri(url))
        player.setMediaSource(mediaSource)
        player.prepare()
        if (startPositionMs > 0) player.seekTo(startPositionMs)
        player.playWhenReady = true
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume() {
        exoPlayer?.play()
    }

    fun seekTo(ms: Long) {
        exoPlayer?.seekTo(ms)
    }

    fun stop() {
        exoPlayer?.stop()
    }

    /** 释放播放器；释放后如需继续播放必须重新 [init] */
    @Synchronized
    fun release() {
        exoPlayer?.removeListener(listener)
        exoPlayer?.release()
        exoPlayer = null
        appContext = null
        onError = null
    }
}
