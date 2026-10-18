package com.movieshell.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.MusicApi
import com.movieshell.data.repo.MusicHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * v0.79.0：音乐播放器（独立 ExoPlayer 实例，与视频播放互不干扰）。
 *
 * 播放列表模式：playList(tracks, index) 后支持上一首/下一首/暂停/续播。
 */
object MusicPlayer {
    private var exo: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** v0.83.0：播放模式 */
    enum class RepeatMode { ALL, ONE, SHUFFLE }

    data class State(
        val track: MusicApi.Track? = null,
        val playing: Boolean = false,
        val loading: Boolean = false,
        // v0.83.0：进度
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val repeatMode: RepeatMode = RepeatMode.ALL,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var queue: List<MusicApi.Track> = emptyList()
    private var index: Int = -1

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(playing = isPlaying, loading = false)
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_BUFFERING) {
                _state.value = _state.value.copy(loading = true)
            }
            if (state == Player.STATE_READY) {
                _state.value = _state.value.copy(
                    durationMs = exo?.duration?.coerceAtLeast(0L) ?: 0L,
                )
            }
            // v0.83.0：播完按模式切歌
            if (state == Player.STATE_ENDED) {
                onTrackEnded()
            }
        }
    }

    @Synchronized
    private fun ensure(context: Context): ExoPlayer {
        exo?.let { return it }
        return ExoPlayer.Builder(context.applicationContext).build().also {
            it.addListener(listener)
            exo = it
        }
    }

    /** 播放列表从 index 开始 */
    fun playList(context: Context, tracks: List<MusicApi.Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        appCtx = context.applicationContext
        queue = tracks
        playAt(context, startIndex.coerceIn(tracks.indices))
    }

    private fun playAt(context: Context, i: Int) {
        if (i !in queue.indices) return
        index = i
        val track = queue[i]
        _state.value = State(track = track, playing = false, loading = true)
        scope.launch {
            // v0.81.0：本地音频直接播 content URI；在线歌曲实时取直链（不缓存）
            val mediaUri = if (track.isLocal) {
                track.localUri
            } else {
                runCatching { MusicApi.playUrl(track.urlId) }.getOrNull()
            }
            if (mediaUri.isNullOrBlank()) {
                LogBus.e("MusicPlayer", "取播放地址失败：${track.name}")
                _state.value = _state.value.copy(loading = false)
                return@launch
            }
            runCatching {
                val player = ensure(context)
                player.setMediaItem(MediaItem.fromUri(mediaUri))
                player.prepare()
                player.play()
                startProgressPoll(context)
                // v0.84.0：记一条播放历史
                launch { MusicHistoryStore.recordPlay(context.applicationContext, track) }
                LogBus.i("MusicPlayer", "播放：${track.name} - ${track.artist}")
            }.onFailure {
                LogBus.e("MusicPlayer", "播放失败：${it.message}")
                _state.value = _state.value.copy(loading = false)
            }
        }
    }

    fun toggle() {
        val p = exo ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    // v0.83.0：进度轮询（播放中每 500ms 推一次）
    private var progressJob: kotlinx.coroutines.Job? = null

    private fun startProgressPoll(context: Context) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                val p = exo ?: break
                if (p.isPlaying) {
                    _state.value = _state.value.copy(
                        positionMs = p.currentPosition.coerceAtLeast(0L),
                        durationMs = p.duration.coerceAtLeast(0L),
                    )
                }
            }
        }
    }

    fun seekTo(ms: Long) {
        exo?.seekTo(ms.coerceAtLeast(0L))
        _state.value = _state.value.copy(positionMs = ms.coerceAtLeast(0L))
    }

    fun cycleRepeatMode() {
        val next = when (_state.value.repeatMode) {
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.SHUFFLE
            RepeatMode.SHUFFLE -> RepeatMode.ALL
        }
        _state.value = _state.value.copy(repeatMode = next)
    }

    private fun onTrackEnded() {
        val ctx = appCtx ?: return
        when (_state.value.repeatMode) {
            RepeatMode.ONE -> playAt(ctx, index)
            RepeatMode.SHUFFLE -> {
                if (queue.size > 1) {
                    var n = index
                    while (n == index) n = (0 until queue.size).random()
                    playAt(ctx, n)
                } else playAt(ctx, index)
            }
            RepeatMode.ALL -> next(ctx)
        }
    }

    private var appCtx: Context? = null

    fun next(context: Context) {
        if (queue.isEmpty()) return
        playAt(context, (index + 1) % queue.size)
    }

    fun prev(context: Context) {
        if (queue.isEmpty()) return
        playAt(context, (index - 1 + queue.size) % queue.size)
    }

    fun stop() {
        progressJob?.cancel()
        exo?.stop()
        _state.value = State(repeatMode = _state.value.repeatMode)
        queue = emptyList()
        index = -1
    }

    /** 切 tab/退后台不自动停；App 退出时 MainActivity.onDestroy 可调 release */
    fun release() {
        exo?.release()
        exo = null
    }
}
