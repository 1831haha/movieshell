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
import com.movieshell.data.repo.MusicSourceStore
import com.movieshell.data.source.LxMusicSourceEngine
import com.movieshell.data.source.resolveBuiltinHuibq
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
        // v0.117.0：当前曲目的真实解析来源（音源名）与音质档（LX 档次 id，如 320k/flac）；
        // 列表行徽标、播放页展示都读它
        val resolvedSource: String = "",
        val resolvedQuality: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /**
     * v0.117.0：本机已解析歌曲徽标缓存（urlId → 音源+音质）。列表行按 track.urlId 取，
     * 已播过的歌保留徽标，不必每首重新解析。
     */
    private val _badges = MutableStateFlow<Map<String, Pair<String, String>>>(emptyMap())
    val badges: StateFlow<Map<String, Pair<String, String>>> = _badges

    /**
     * v0.125.0 补充A：播放页海报匹配结果（track.id → 封面图地址；空串=匹配过没
     * 找到，不重复搜）。v0.123.0 时匹配挂在播放页的 composition scope 上，用户
     * 一切歌词视图/离页，组合销毁就把协程取消（日志实锤 "The coroutine scope
     * left the composition"）、结果没落缓存；现挪到播放器自身 scope 里跑，
     * 页面只读本表，页面进出不影响匹配完成与缓存。
     */
    private val _matchedArt = MutableStateFlow<Map<String, String>>(emptyMap())
    val matchedArt: StateFlow<Map<String, String>> = _matchedArt
    private val artMatchInFlight = mutableSetOf<String>()

    private fun ensureMatchedArt(track: MusicApi.Track) {
        val id = track.id
        if (_matchedArt.value.containsKey(id)) return
        synchronized(artMatchInFlight) { if (!artMatchInFlight.add(id)) return }
        scope.launch {
            try {
                // 有自带封面且能解析出图就不必匹配（顺手把结果记下防重复进）
                val own = if (track.picId.isBlank()) "" else {
                    runCatching { MusicApi.picImageUrl(track.picId) }.getOrDefault("")
                }
                if (own.isNotBlank()) {
                    _matchedArt.value = _matchedArt.value + (id to own)
                    return@launch
                }
                val q = if (track.artist.isBlank() || track.artist == "本地") {
                    track.name
                } else {
                    "${track.name} ${track.artist}"
                }
                val found = runCatching {
                    val hit = MusicApi.search(q, count = 5)
                        .firstOrNull { it.picId.isNotBlank() }
                        ?: return@runCatching ""
                    MusicApi.picImageUrl(hit.picId)
                }.onFailure {
                    LogBus.w("MusicPlayer", "海报匹配失败：${it.message}")
                }.getOrDefault("")
                _matchedArt.value = _matchedArt.value + (id to found)
            } finally {
                synchronized(artMatchInFlight) { artMatchInFlight.remove(id) }
            }
        }
    }

    private var queue: List<MusicApi.Track> = emptyList()
    private var index: Int = -1
    // v0.85.0：播放请求代际——连接 service 是异步的，旧请求回来时若已被取代则丢弃
    private var playGen = 0
    // v0.108.0 B1：手动切换音源的一次性指定源 id——resolveLxUrl 消费后清零，
    // 后续切歌仍按默认顺序（启用源列表顺序）解析
    private var forcedSourceId: String? = null

    /**
     * v0.117.0：列表页「切换音源」设置的首选源 id（null=自动，按启用顺序）。
     * 与 forcedSourceId 的区别：forced 是播放页一次性指定（消费即清），
     * preferred 持续生效，resolveLxUrl 排它最先试、失败回落默认顺序。
     */
    @Volatile
    private var preferredSourceId: String? = null

    fun setPreferredSource(sourceId: String?) {
        preferredSourceId = sourceId
    }

    /** v0.121.0：切换音源弹窗打勾用（null=自动）。 */
    fun preferredSourceId(): String? = preferredSourceId

    /**
     * v0.109.0 B4①：引擎复用——之前 resolveLxUrl 每首歌 `new LxMusicSourceEngine`
     * 再 `release()`，实例级的脚本缓存（engines map，按 scriptUrl）永远架空，
     * 每首歌都重下载、重建 JS 上下文。引擎实例与 V8 isolate 按 scriptUrl
     * 绑定，不存在"单例 + 每次 loadScript 重载"的必要；选型：按持有者生命周期复用——
     * MusicPlayer（object 单例）持有一个引擎实例，随 MusicPlayer.release() 释放；
     * MusicSourceScreen 本就 remember 复用自己的实例。两者互不 release，
     * 不做跨持有者共享，避免 release 竞态。resolveLxUrl 调用都在 Main 线程的
     * scope 协程里，engines map 本身也有锁，线程安全。
     */
    private var lxEngine: LxMusicSourceEngine? = null

    private fun getOrCreateLxEngine(appContext: Context): LxMusicSourceEngine =
        lxEngine ?: LxMusicSourceEngine(appContext).also { lxEngine = it }

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
                // v0.109.0 A12：之前只记 LogBus，用户点歌静默无事发生——给可见 Toast
                toast("音乐服务连接失败，请重试")
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

    /**
     * v0.108.0 B1：手动切换音源——仅用指定源重新解析当前歌曲并播放。
     * 指定源解析失败时回落默认顺序（其他启用源 → GD Studio），不卡死；
     * 切歌后不持久，下一首仍按默认顺序。
     */
    fun playWithSource(context: Context, sourceId: String) {
        if (index !in queue.indices) {
            toast("当前没有播放的歌曲")
            return
        }
        forcedSourceId = sourceId
        playAt(context, index)
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
        // v0.125.0 补充A：无自带封面的歌在播放器 scope 里做海报匹配（切歌即触发，
        // 与页面组合无关，匹配结果进 matchedArt 供播放页读）
        ensureMatchedArt(track)
        scope.launch {
            // v0.81.0：本地音频直接播 content URI；在线歌曲实时取直链（不缓存）
            // v0.89.0：直链接口偶发 503，失败 1.5 秒后重试一次；封面与直链并发解析，不拖慢起播
            // v0.100.0：在线歌曲先走启用的 LX 音乐源解析（内置 huibq 纯 HTTP / 用户脚本），
            // 全部失败再回退 MusicApi.playUrl（GD Studio）
            val mediaDeferred = async {
                if (track.isLocal) {
                    Resolved(track.localUri, "本地文件", "")
                } else {
                    resolveLxUrl(context.applicationContext, track.urlId)
                        ?: try {
                            MusicApi.playUrl(track.urlId).takeIf { it.isNotBlank() }
                                ?.let { Resolved(it, "GD Studio", "") }
                        } catch (e: Exception) {
                            LogBus.w("MusicPlayer", "取直链失败，重试一次：${track.name}（${e.message}）")
                            delay(1500)
                            runCatching { MusicApi.playUrl(track.urlId) }.getOrNull()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { Resolved(it, "GD Studio", "") }
                        }
                }
            }
            val artDeferred = async {
                if (track.picId.isBlank()) "" else {
                    runCatching { MusicApi.picImageUrl(track.picId) }.getOrDefault("")
                }
            }
            val resolved = mediaDeferred.await()
            if (resolved == null || resolved.url.isBlank()) {
                artDeferred.cancel()
                LogBus.e("MusicPlayer", "取播放地址失败：${track.name}")
                _state.value = _state.value.copy(loading = false)
                toast("取不到播放地址，请重试")
                return@launch
            }
            // v0.117.0：记下本曲的真实来源与音质（列表徽标 + 播放页展示用）；
            // gen 已过期（用户已切歌）时别把旧歌的来源盖到新歌上
            if (gen == playGen) {
                _badges.value = _badges.value + (track.urlId to (resolved.source to resolved.quality))
                _state.value = _state.value.copy(
                    resolvedSource = resolved.source,
                    resolvedQuality = resolved.quality,
                )
            }
            val mediaUri = resolved.url
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
     * v0.117.0：解析结果三件套——直链、实际出数据的音源名、音质档 id（LX 档次）。
     * GD Studio 兜底时音质未知，quality 为空。
     */
    private data class Resolved(val url: String, val source: String, val quality: String)

    /**
     * v0.100.0：按顺序尝试启用的 LX 音乐源。内置源走纯 HTTP（huibq 模板），用户添加的
     * 脚本 URL 走 LxMusicSourceEngine。
     * v0.106.0：按该源实际档次从高到低试——内置 huibq 用自报两档（192k→128k）；
     * 脚本源先 loadScript 再读 inited 上报的 qualitys 降序试（未知 id 沉底）；
     * 脚本未上报档次时兜底 320k→128k（老行为）。单个失败换下一个，全部失败返回 null，
     * 调用方回退 MusicApi.playUrl。只换直链来源，不碰播放链路；本地歌曲不走这里。
     * v0.117.0：返回 Resolved（三件套）；首选项源（setPreferredSource）排到最前，
     * 失败仍回落到默认顺序，保证不卡死。
     * v0.124.0：逐档试的档位改按平台（wy）取脚本自报 qualitys——引擎侧 getQualityIds
     * 修好对象形状解析后，flac/flac24bit 等高档第一次真能进尝试序列（此前恒空、恒走
     * 兜底 320k→128k）。App 无手动音质上限设置，默认即最高档策略；逐档只在上一档
     * 明确失败（脚本返回空/报错）时才降，成功即停，不拖慢正常起播。
     */
    private suspend fun resolveLxUrl(appContext: Context, songId: String): Resolved? {
        if (songId.isBlank()) return null
        // v0.108.0 B1：一次性消费手动指定的源；该源不存在/已禁用时 sources 为空，
        // 自然回落为调用方 MusicApi.playUrl（GD Studio）兜底
        val forced = forcedSourceId.also { forcedSourceId = null }
        val preferred = preferredSourceId
        val sources = runCatching { MusicSourceStore.list(appContext) }
            .getOrDefault(emptyList())
            .filter { it.enabled }
            .let { list ->
                when {
                    forced != null -> list.filter { it.id == forced }
                    preferred != null -> list.sortedBy { if (it.id == preferred) 0 else 1 }
                    else -> list
                }
            }
        if (sources.isEmpty()) return null
        // v0.109.0 B4①：复用 MusicPlayer 生命周期内的引擎实例，不再每首歌 new+release
        val engine = getOrCreateLxEngine(appContext)
        // v0.120.0：逐源收集失败原因，全部失败时透传到日志并给用户可见提示
        val failures = ArrayList<String>()
        for (s in sources) {
            val qualities: List<String> = if (s.builtin) {
                LxMusicSourceEngine.BUILTIN_QUALITIES
            } else {
                runCatching { engine.loadScript(s.url) }.getOrDefault(false)
                // v0.124.0：按平台取档（播放恒走 wy），别把 tx 独占档拿来对 wy 空试
                LxMusicSourceEngine.sortHighToLow(engine.getQualityIds(s.url, "wy"))
                    .ifEmpty { listOf("320k", "128k") }
            }
            var reason = "解析失败"
            for (q in qualities) {
                val url = runCatching {
                    if (s.builtin) resolveBuiltinHuibq("wy", songId, q)
                    else engine.resolveMusicUrl(s.url, "wy", songId, q)
                }.getOrNull()?.takeIf { it.isNotBlank() }
                if (url != null) {
                    LogBus.i("MusicPlayer", "LX 解析成功：${s.name} $q")
                    return Resolved(url, s.name, q)
                }
                // 内置源失败原因不深挖（返回 null 无细分信息），脚本源读引擎诊断字段
                reason = if (s.builtin) "内置线路解析失败"
                else engine.lastFailureReason.ifBlank { "解析失败" }
            }
            failures += "${s.name}：$reason"
        }
        LogBus.w("MusicPlayer", "LX 源全部解析失败，回退 GD Studio：${failures.joinToString("；")}")
        toast("LX 解析失败，已回退备用线路")
        return null
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
        // v0.109.0 B4①：随 MusicPlayer 一起释放复用的 LX 引擎
        runCatching { lxEngine?.release() }
        lxEngine = null
    }
}
