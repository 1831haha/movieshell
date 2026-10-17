package com.movieshell.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.MusicApi
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

    data class State(
        val track: MusicApi.Track? = null,
        val playing: Boolean = false,
        val loading: Boolean = false,
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
        queue = tracks
        playAt(context, startIndex.coerceIn(tracks.indices))
    }

    private fun playAt(context: Context, i: Int) {
        if (i !in queue.indices) return
        index = i
        val track = queue[i]
        _state.value = State(track = track, playing = false, loading = true)
        scope.launch {
            runCatching { MusicApi.playUrl(track.urlId) }
                .onSuccess { url ->
                    val player = ensure(context)
                    player.setMediaItem(MediaItem.fromUri(url))
                    player.prepare()
                    player.play()
                    LogBus.i("MusicPlayer", "播放：${track.name} - ${track.artist}")
                }
                .onFailure {
                    LogBus.e("MusicPlayer", "取播放地址失败：${it.message}")
                    _state.value = _state.value.copy(loading = false)
                }
        }
    }

    fun toggle() {
        val p = exo ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun next(context: Context) {
        if (queue.isEmpty()) return
        playAt(context, (index + 1) % queue.size)
    }

    fun prev(context: Context) {
        if (queue.isEmpty()) return
        playAt(context, (index - 1 + queue.size) % queue.size)
    }

    fun stop() {
        exo?.stop()
        _state.value = State()
        queue = emptyList()
        index = -1
    }

    /** 切 tab/退后台不自动停；App 退出时 MainActivity.onDestroy 可调 release */
    fun release() {
        exo?.release()
        exo = null
    }
}
