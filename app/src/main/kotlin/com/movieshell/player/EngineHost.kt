package com.movieshell.player

import android.content.Context
import androidx.media3.common.PlaybackException
import com.movieshell.data.repo.PlayerEngine
import com.movieshell.data.repo.PlayerSettings

/**
 * 双引擎路由（v0.20.0）：播放器界面只跟 EngineHost 打交道，
 * 实际走 Media3（默认）还是 libmpv 由 [PlayerSettings.engine] 决定。
 * Media3 侧沿用既有 PlayerManager（代码不动），libmpv 侧走 MpvPlayerManager。
 */
object EngineHost {

    fun engine(): PlayerEngine = PlayerSettings.engine.value

    fun play(
        context: Context,
        url: String,
        headers: Map<String, String> = emptyMap(),
        startPositionMs: Long = 0L,
        subtitleUrl: String? = null,
    ) {
        route(
            media3 = {
                PlayerManager.init(context)
                PlayerManager.play(url, headers, startPositionMs, subtitleUrl)
            },
            libmpv = {
                MpvPlayerManager.init(context)
                MpvPlayerManager.play(url, headers, startPositionMs, subtitleUrl)
            },
        )
    }

    fun pause() = route({ PlayerManager.pause() }, { MpvPlayerManager.pause() })

    fun resume() = route({ PlayerManager.resume() }, { MpvPlayerManager.resume() })

    fun seekTo(ms: Long) = route({ PlayerManager.seekTo(ms) }, { MpvPlayerManager.seekTo(ms) })

    fun stop() = route({ PlayerManager.stop() }, { MpvPlayerManager.stop() })

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

    private inline fun <T> route(media3: () -> T, libmpv: () -> T): T =
        if (engine() == PlayerEngine.MEDIA3) media3() else libmpv()
}
