package com.movieshell.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.MusicApi
import com.movieshell.data.repo.MusicHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * v0.79.0：音乐播放器；v0.85.0 改为门面——ExoPlayer 归 MusicService（前台服务）
 * 持有并建 MediaSession，这里经 SessionToken + MediaController 连接操控。
 *
 * 公开 API 与之前一致（state / playList / next / prev / toggle / seekTo /
 * cycleRepeatMode / stop / release），现有 UI 零改动。
 *
 * 播放列表模式：playList(tracks, index) 后支持上一首/下一首/暂停/续播。
 * 在线歌曲的直链仍按首实时解析（时效签名，绝不缓存），取到后 setMediaItem。
 */
object MusicPlayer {
    private var controller: MediaController? = null
    private var connecting = false
    private val pendingBlocks = mutableListOf<(MediaController) -> Unit>()
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
    // v0.85.0：播放请求代际——连接 service 是异步的，旧请求回来时若已被取代则丢弃
    private var playGen = 0

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(playing = isPlaying, loading = false)
        }

        // v0.89.0：之前缺这块——流加载失败（CDN 抽风/网络抖动）时 UI 会永远卡在 loading，
        // 这里落 loading 并给用户一句可读的反馈
        override fun onPlayerError(error: PlaybackException) {
            LogBus.e("MusicPlayer", "播放器错误：${error.errorCodeName} ${error.message}")
            _state.value = _state.value.copy(playing = false, loading = false)
            toast("播放失败，请重试")
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_BUFFERING) {
                _state.value = _state.value.copy(loading = true)
            }
            if (state == Player.STATE_READY) {
                _state.value = _state.value.copy(
                    durationMs = controller?.duration?.coerceAtLeast(0L) ?: 0L,
                )
            }
            // v0.83.0：播完按模式切歌
            if (state == Player.STATE_ENDED) {
                onTrackEnded()
            }
        }
    }

    /** 拿到 controller 后执行；连接中则排队，连好后按序执行。 */
    private fun withController(context: Context, block: (MediaController) -> Unit) {
        synchronized(this) {
            controller?.let { block(it); return }
            pendingBlocks += block
            if (connecting) return
            connecting = true
        }
        val appContext = context.applicationContext
        scope.launch {
            val c = connect(appContext)
            val blocks: List<(MediaController) -> Unit>
            synchronized(this@MusicPlayer) {
                connecting = false
                blocks = pendingBlocks.toList()
                pendingBlocks.clear()
                if (c != null) controller = c
            }
            if (c == null) {
                LogBus.e("MusicPlayer", "音乐服务连接失败")
                _state.value = _state.value.copy(loading = false)
                return@launch
            }
            c.addListener(listener)
            blocks.forEach { it(c) }
        }
    }

    private suspend fun connect(appContext: Context): MediaController? {
        val future = runCatching {
            val token = SessionToken(appContext, ComponentName(appContext, MusicService::class.java))
            MediaController.Builder(appContext, token).buildAsync()
        }.onFailure {
            LogBus.e("MusicPlayer", "创建 MediaController 失败：${it.message}")
        }.getOrNull() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { future.get(10, TimeUnit.SECONDS) }
                .onFailure { LogBus.e("MusicPlayer", "连接音乐服务超时：${it.message}") }
                .getOrNull()
        }
    }

    private fun MusicApi.Track.toMediaItem(uri: String, artworkUrl: String = ""): MediaItem {
        val artwork = artworkUrl.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        val metadata = MediaMetadata.Builder()
            .setTitle(name)
            .setArtist(artist)
            .setArtworkUri(artwork)
            .build()
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }

    /** 用户可见的失败提示（门面内统一出口；调用处都在主线程协程里） */
    private fun toast(msg: String) {
        runCatching {
            appCtx?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
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
        val gen = ++playGen
        // v0.89.0：保留播放模式（之前每次切歌都重置回 ALL）
        _state.value = State(
            track = track, playing = false, loading = true,
            repeatMode = _state.value.repeatMode,
        )
        scope.launch {
            // v0.81.0：本地音频直接播 content URI；在线歌曲实时取直链（不缓存）
            // v0.89.0：直链接口偶发 503，失败 1.5 秒后重试一次；封面与直链并发解析，不拖慢起播
            val mediaDeferred = async {
                if (track.isLocal) {
                    track.localUri
                } else {
                    try {
                        MusicApi.playUrl(track.urlId)
                    } catch (e: Exception) {
                        LogBus.w("MusicPlayer", "取直链失败，重试一次：${track.name}（${e.message}）")
                        delay(1500)
                        runCatching { MusicApi.playUrl(track.urlId) }.getOrNull()
                    }
                }
            }
            val artDeferred = async {
                if (track.picId.isBlank()) "" else {
                    runCatching { MusicApi.picImageUrl(track.picId) }.getOrDefault("")
                }
            }
            val mediaUri = mediaDeferred.await()
            if (mediaUri.isNullOrBlank()) {
                artDeferred.cancel()
                LogBus.e("MusicPlayer", "取播放地址失败：${track.name}")
                _state.value = _state.value.copy(loading = false)
                toast("取不到播放地址，请重试")
                return@launch
            }
            val artworkUrl = artDeferred.await()
            withController(context) { c ->
                if (gen != playGen) return@withController
                runCatching {
                    c.setMediaItem(track.toMediaItem(mediaUri, artworkUrl))
                    c.prepare()
                    c.play()
                    startProgressPoll()
                    // v0.84.0：记一条播放历史
                    launch { MusicHistoryStore.recordPlay(context.applicationContext, track) }
                    LogBus.i("MusicPlayer", "播放：${track.name} - ${track.artist}")
                }.onFailure {
                    LogBus.e("MusicPlayer", "播放失败：${it.message}")
                    _state.value = _state.value.copy(loading = false)
                    toast("播放失败，请重试")
                }
            }
        }
    }

    /**
     * v0.91.0：controller 断开（如进程被回收后 service 还在播）时不再静默 return——
     * 有 track 说明后台有播放任务，给 toast 反馈并尝试重连恢复后再执行这次切换。
     */
    fun toggle() {
        val c = controller
        if (c != null) {
            if (c.isPlaying) c.pause() else c.play()
            return
        }
        val ctx = appCtx ?: return
        if (_state.value.track == null) return
        toast("正在重连…")
        withController(ctx) { if (it.isPlaying) it.pause() else it.play() }
    }

    /** v0.85.0：睡眠定时到期——2 秒线性渐弱后暂停，恢复音量 */
    fun fadeOutAndPause() {
        val c = controller ?: return
        scope.launch {
            val steps = 10
            repeat(steps) { i ->
                c.volume = 1f - (i + 1) / steps.toFloat()
                delay(200)
            }
            c.pause()
            c.volume = 1f
        }
    }

    // v0.83.0：进度轮询（播放中每 500ms 推一次）
    private var progressJob: Job? = null

    private fun startProgressPoll() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                delay(500)
                val c = controller ?: break
                if (c.isPlaying) {
                    _state.value = _state.value.copy(
                        positionMs = c.currentPosition.coerceAtLeast(0L),
                        durationMs = c.duration.coerceAtLeast(0L),
                    )
                }
            }
        }
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0L))
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
        // v0.85.0：睡眠定时「播完本首停止」优先于播放模式，直接停
        if (SleepTimer.consumeAfterEpisode()) {
            controller?.pause()
            _state.value = _state.value.copy(playing = false)
            return
        }
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

    /** v0.85.0：供 MusicService 通知栏上下首——队列多于一首才露出按钮 */
    internal fun hasNext(): Boolean = queue.size > 1

    /** v0.85.0：供 MusicService 通知栏上下首——队列多于一首才露出按钮 */
    internal fun hasPrev(): Boolean = queue.size > 1

    fun stop() {
        progressJob?.cancel()
        controller?.stop()
        _state.value = State(repeatMode = _state.value.repeatMode)
        queue = emptyList()
        index = -1
    }

    /** 切 tab/退后台不自动停；App 退出时 MainActivity.onDestroy 可调 release */
    fun release() {
        progressJob?.cancel()
        controller?.release()
        controller = null
    }
}
