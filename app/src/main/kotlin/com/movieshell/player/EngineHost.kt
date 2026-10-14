package com.movieshell.player

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.ui.PlayerView
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
