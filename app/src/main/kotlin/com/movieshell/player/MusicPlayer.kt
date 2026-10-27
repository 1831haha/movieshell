package com.movieshell.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
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
import com.movieshell.ui.market.PluginUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
     * v0.129.0：播放队列只读快照（列表 + 当前下标），供播放页「播放列表」弹窗展示。
     * 队列本体仍由 queue/index 持有，这里只是它们的同步镜像，写入点只有
     * playAt（切歌/起播）与 stop（清空），别在别处改。
     */
    data class QueueState(
        val tracks: List<MusicApi.Track> = emptyList(),
        val currentIndex: Int = -1,
    )

    private val _queueState = MutableStateFlow(QueueState())
    val queueState: StateFlow<QueueState> = _queueState

    /**
     * v0.117.0：本机已解析歌曲徽标缓存（urlId → 音源+音质）。列表行按 track.urlId 取，
     * 已播过的歌保留徽标，不必每首重新解析。
     */
    private val _badges = MutableStateFlow<Map<String, Pair<String, String>>>(emptyMap())
    val badges: StateFlow<Map<String, Pair<String, String>>> = _badges

    /**
     * v0.215.0：播过歌曲的时长（urlId 为空的本地歌用 id 作 key）——列表行
     * 据此显示时长；没播过的歌不编时长、不显示。进程重建后从 prefs 恢复。
     */
    private val _durations = MutableStateFlow<Map<String, Long>>(emptyMap())
    val durations: StateFlow<Map<String, Long>> = _durations

    fun durationKey(track: MusicApi.Track): String = track.urlId.ifBlank { track.id }

    fun restoreDurations(context: Context) {
        runCatching {
            val raw = context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DURATIONS_JSON, null) ?: return@runCatching
            val o = org.json.JSONObject(raw)
            val map = mutableMapOf<String, Long>()
            for (k in o.keys()) {
                val v = o.optLong(k, 0L)
                if (v > 0) map[k] = v
            }
            if (map.isNotEmpty()) _durations.value = map
        }
    }

    private fun recordDuration(track: MusicApi.Track, ms: Long) {
        if (ms <= 0) return
        val key = durationKey(track)
        if (key.isBlank() || _durations.value[key] == ms) return
        _durations.value = _durations.value + (key to ms)
        val ctx = appCtx ?: return
        runCatching {
            val o = org.json.JSONObject()
            for ((k, v) in _durations.value) o.put(k, v)
            ctx.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DURATIONS_JSON, o.toString()).apply()
        }
    }

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
                // v0.126.0：封面/匹配搜索都按曲目自身平台走（GD 不支持的平台自然空）
                val plat = MusicApi.normalizeSource(track.source).ifBlank { "wy" }
                // 有自带封面且能解析出图就不必匹配（顺手把结果记下防重复进）
                val own = if (track.picId.isBlank()) "" else {
                    runCatching { MusicApi.picImageUrl(track.picId, plat) }.getOrDefault("")
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
                    val hit = MusicApi.search(q, count = 5, platform = plat)
                        .firstOrNull { it.picId.isNotBlank() }
                        ?: return@runCatching ""
                    MusicApi.picImageUrl(hit.picId, plat)
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

    // v0.213.0 播错丢状态修复：队列原来只在内存，进程重建后播放、
    // 队列、首选源全丢；队列与下标持久化到 SharedPreferences（JSON），
    // 重建后恢复队列快照（不自动起播，用户点队列项再播），下标越界钳制。
    private const val KEY_QUEUE_JSON = "queue_json"
    private const val KEY_QUEUE_INDEX = "queue_index"
    // v0.215.0：播过歌曲的时长缓存（搜索结果本身不带时长，只能播到才知道）。
    private const val KEY_DURATIONS_JSON = "durations_json"
    private var queueRestored = false

    private fun persistQueue(context: Context?) {
        val ctx = context ?: appCtx ?: return
        runCatching {
            val arr = org.json.JSONArray()
            for (t in queue) {
                arr.put(org.json.JSONObject().apply {
                    put("id", t.id); put("name", t.name); put("artist", t.artist)
                    put("album", t.album); put("picId", t.picId); put("urlId", t.urlId)
                    put("localUri", t.localUri); put("source", t.source)
                })
            }
            ctx.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_QUEUE_JSON, arr.toString())
                .putInt(KEY_QUEUE_INDEX, index)
                .apply()
        }
    }

    /** 进程启动后恢复一次队列快照（仅队列与下标，不恢复播放态）。 */
    fun restoreQueue(context: Context) {
        if (queueRestored) return
        queueRestored = true
        if (queue.isNotEmpty()) return
        runCatching {
            val prefs = context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_QUEUE_JSON, null) ?: return@runCatching
            val arr = org.json.JSONArray(raw)
            val list = ArrayList<MusicApi.Track>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += MusicApi.Track(
                    id = o.optString("id"), name = o.optString("name"),
                    artist = o.optString("artist"), album = o.optString("album"),
                    picId = o.optString("picId"), urlId = o.optString("urlId"),
                    localUri = o.optString("localUri"), source = o.optString("source"),
                )
            }
            if (list.isNotEmpty()) {
                queue = list
                index = prefs.getInt(KEY_QUEUE_INDEX, -1).coerceIn(list.indices)
                _queueState.value = QueueState(queue, index)
                // v0.217.0（F26）：同时恢复当前曲目显示态（不起播）——原来只恢复
                // 队列／下标，迷你条不显示、播放页说“还没有播放歌曲”，恢复的队列
                // 根本没有入口能点播；现在迷你条与播放页先显示这首，点播入口见 playQueueItem。
                if (index in list.indices) {
                    _state.value = State(
                        track = list[index], playing = false, loading = false,
                        repeatMode = _state.value.repeatMode,
                    )
                }
            }
        }
    }

    /**
     * v0.213.0：删歌同步播放器——删掉正在播/在队列里的歌时，播放器
     * 原来完全不知道，删掉的歌播完、上下首还能播到它；删当前歌则
     * 停播并清显示态，删队列里的歌则剔出队列并修正下标。
     */
    fun onTrackDeleted(context: Context, trackId: String) {
        val cur = _state.value.track
        if (cur?.id == trackId) {
            playJob?.cancel()
            progressJob?.cancel()
            controller?.stop()
            _state.value = State(repeatMode = _state.value.repeatMode)
        }
        val at = queue.indexOfFirst { it.id == trackId }
        if (at < 0) return
        removeQueueAt(context, at)
    }
    // v0.85.0：播放请求代际——连接 service 是异步的，旧请求回来时若已被取代则丢弃
    // v0.215.0 补记：上一轮批量改动误删过本声明（只剩引用），编译不过，补回。
    private var playGen = 0

    /** v0.215.0：队列弹窗行内删除——删当前歌停播清态（同 onTrackDeleted 口径），
     * 其余剔出队列并修正下标、持久化；清空直接复用 stop。 */
    fun removeQueueItem(context: Context, at: Int) {
        if (at !in queue.indices) return
        if (at == index) {
            playJob?.cancel()
            progressJob?.cancel()
            controller?.stop()
            _state.value = State(repeatMode = _state.value.repeatMode)
        }
        removeQueueAt(context, at)
    }

    /**
     * v0.218.0（S14）：按下标删队列项的共用尾巴——原来 onTrackDeleted
     * 与 removeQueueItem 各抄一遍（剔出队列、修正下标、写队列态、持久化）；
     * 停播清态的条件两处各异（按 id／按下标），仍由调用方各自判断。
     */
    private fun removeQueueAt(context: Context, at: Int) {
        if (at !in queue.indices) return
        val wasCurrent = at == index
        queue = queue.toMutableList().also { it.removeAt(at) }
        if (wasCurrent) {
            index = -1
            _queueState.value = QueueState(queue, -1)
        } else {
            if (at < index) index -= 1
            _queueState.value = QueueState(queue, index)
        }
        persistQueue(context)
    }

    fun clearQueue() {
        stop()
    }
    // v0.146.0：上一轮 LX 解析是否撞上"只有试听"——供最终失败时给准提示（见 playAt）。
    // v0.215.0：上一轮各源失败原因原文（同上，供 Cookie 过期指路用）。
    private var lastTrialOnly = false
    private var lastLxFailures: List<String> = emptyList()
    // v0.131.0：当前解析协程句柄——切歌先 cancel 上一首未完成的解析，避免多代
    // 解析并发共用同一 V8 会话互踩、各自重试并重复上报失败
    private var playJob: Job? = null
    // v0.108.0 B1：手动切换音源的一次性指定源 id——resolveLxUrl 消费后清零，
    // 后续切歌仍按默认顺序（启用源列表顺序）解析
    private var forcedSourceId: String? = null

    /**
     * v0.117.0：列表页「切换音源」设置的首选源 id（null=自动，按启用顺序）。
     * 与 forcedSourceId 的区别：forced 是播放页一次性指定（消费即清），
     * preferred 持续生效，resolveLxUrl 排它最先试、失败回落默认顺序。
     * v0.213.0 播错丢状态修复：原来只是 @Volatile 内存变量，重启就丢，
     * 删/禁首选源后还成悬空 ID（芯片、弹窗、实际播放三处打架）；
     * 改 StateFlow + SharedPreferences 持久化，芯片跟 Flow 走、删禁源时清掉。
     */
    private val _preferredSourceId = MutableStateFlow<String?>(null)
    val preferredSourceIdFlow: StateFlow<String?> = _preferredSourceId
    private const val MUSIC_PREFS = "music_player_prefs"
    private const val KEY_PREFERRED_SOURCE = "preferred_source_id"
    private var preferredRestored = false

    fun setPreferredSource(context: Context, sourceId: String?) {
        _preferredSourceId.value = sourceId
        runCatching {
            context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PREFERRED_SOURCE, sourceId).apply()
        }
    }

    /** 进程启动后读一次持久化的首选源（重复调用只生效一次）。 */
    fun restorePreferred(context: Context) {
        if (preferredRestored) return
        preferredRestored = true
        _preferredSourceId.value = runCatching {
            context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PREFERRED_SOURCE, null)
        }.getOrNull()
    }

    /** 首选源被删/禁用时清掉，避免悬空 ID 让芯片与实际播放打架。 */
    fun clearPreferredSourceIf(context: Context, sourceId: String) {
        if (_preferredSourceId.value == sourceId) setPreferredSource(context, null)
    }

    /** v0.121.0：切换音源弹窗打勾用（null=自动）。 */
    fun preferredSourceId(): String? = _preferredSourceId.value

    /**
     * v0.180.0：当前 LX 音源自报的平台 code（墨澜/聚合类脚本的 inited sources 键）。
     * 首选源排最前，逐个脚本试，第一个报出平台的脚本说了算；内置 huibq
     * 没有脚本可报、脚本加载失败时返回空，调用方用已知平台兜底。
     * 复用本单例的引擎实例（与播放同一份脚本缓存），不另建 V8 会话。
     * v0.217.0（F32）：平台行跟随全部启用源——原来第一个报出平台的脚本
     * 直接 return，后面源独有的平台（如咪咕）永远不出现；改取并集、去重、排序，
     * 加载失败的源跳过并记日志。
     */
    suspend fun sourcePlatforms(context: Context): List<String> {
        val appContext = context.applicationContext
        val sources = runCatching { MusicSourceStore.list(appContext) }
            .getOrDefault(emptyList())
            .filter { it.enabled && !it.builtin && it.url.isNotBlank() }
            .let { list ->
                val preferred = _preferredSourceId.value
                if (preferred == null) list
                else list.sortedBy { if (it.id == preferred) 0 else 1 }
            }
        if (sources.isEmpty()) return emptyList()
        val engine = getOrCreateLxEngine(appContext)
        val all = linkedSetOf<String>()
        for (s in sources) {
            val ok = runCatching { engine.loadScript(s.url) }.getOrDefault(false)
            if (!ok) {
                LogBus.w("MusicPlayer", "平台枚举跳过加载失败的源：${s.name}")
                continue
            }
            val codes = runCatching { engine.getSourcePlatforms(s.url) }
                .getOrDefault(emptyList())
                .map { raw ->
                    MusicApi.normalizeSource(raw).ifBlank { raw.trim().lowercase() }
                }
                .filter { it.isNotBlank() }
                .distinct()
            all += codes
        }
        return all.sorted()
    }

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

    /**
     * v0.217.0（F27）：让播放器侧单例引擎失效某脚本（音源管理页保存 Cookie 后调）。
     * 引擎不存在时不新建——没建过就谈不上缓存，清管理页那台等于没清。
     */
    fun evictLxScript(scriptUrl: String) {
        runCatching { lxEngine?.evictScript(scriptUrl) }
    }

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

    /**
     * 用户可见的失败提示（门面内统一出口；调用处都在主线程协程里）。
     * v0.188.0：不再自建 Toast.makeText，委托共享入口 PluginUi.toast（L04 收敛）；
     * 这里只剩 appCtx 适配（MusicPlayer 持的是存起来的 applicationContext）。
     */
    private fun toast(msg: String) {
        appCtx?.let { PluginUi.toast(it, msg) }
    }

    /** 播放列表从 index 开始 */
    fun playList(context: Context, tracks: List<MusicApi.Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        appCtx = context.applicationContext
        queue = tracks
        playAt(context, startIndex.coerceIn(tracks.indices))
        persistQueue(context)
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
        // v0.213.0：本地歌原来也走 playAt 重播，进度直接丢掉；
        // 本地文件无需换源解析，直接拒绝，不动 forced、不重播。
        if (queue[index].isLocal) {
            toast("本地歌曲无需换源")
            return
        }
        forcedSourceId = sourceId
        playAt(context, index)
    }

    /** v0.213.0：切回自动后立即用默认顺序重解析当前歌（与选具体源对称）。 */
    fun replayCurrent(context: Context) {
        if (index in queue.indices) playAt(context, index)
    }

    /**
     * v0.126.0：跨平台同曲匹配缓存——(原 track.id, 目标平台 LX code) → 换平台后的
     * Track（显示信息沿用原曲，source/urlId/picId 换成目标平台的）。进程内缓存，
     * 切回/再切同首歌免重复搜；原曲自身也按 (id, 原平台) 登记一份，切回零搜索。
     */
    private val platformMatches = mutableMapOf<Pair<String, String>, MusicApi.Track>()

    /**
     * v0.126.0：把当前歌曲切到目标平台播放（与「切换音源」的脚本维度并存，互不影响）。
     * 按 歌名+歌手 在目标平台搜同曲（[MusicApi.pickSameSong] 挑选），命中则把队列
     * 当前项换成目标平台版本重播——playAt 会按新 source 走该平台的 LX 解析。
     * 未命中提示「该平台没有找到这首歌」并保持原播放。不做自动跨平台级联兜底。
     */
    fun playOnPlatform(context: Context, platformCode: String) {
        val cur = _state.value.track
        if (cur == null || cur.isLocal) {
            toast("当前没有可切换平台的歌曲")
            return
        }
        val target = MusicApi.normalizeSource(platformCode)
        val current = MusicApi.normalizeSource(cur.source).ifBlank { "wy" }
        if (target.isBlank() || target == current) {
            toast("当前已是${MusicApi.platformLabel(current) ?: current}")
            return
        }
        appCtx = context.applicationContext
        // v0.213.0 播错修复：跨平台搜索原来无代际保护，切歌后旧搜索
        // 返回仍把播放拉回旧歌；记下发起时代际，过期直接丢弃。
        val platformGen = playGen
        scope.launch {
            val key = cur.id to target
            if (!platformMatches.containsKey(cur.id to current)) {
                platformMatches[cur.id to current] = cur
            }
            val matched = platformMatches[key] ?: run {
                val candidates = runCatching {
                    MusicApi.search("${cur.name} ${cur.artist}", count = 10, platform = target)
                }.getOrDefault(emptyList())
                val hit = MusicApi.pickSameSong(cur, candidates)
                if (hit == null) {
                    // v0.246.0：失败分支也查代际——原来成功路径有 playGen 检查，
                    // 失败分支先 toast 再 return，切歌后旧搜索失败会误弹旧歌提示。
                    if (platformGen != playGen) return@launch
                    LogBus.w("MusicPlayer", "跨平台匹配未命中：${cur.name} → $target")
                    toast("该平台没有找到这首歌")
                    return@launch
                }
                cur.copy(source = target, urlId = hit.urlId, picId = hit.picId)
                    .also { platformMatches[key] = it }
            }
            if (platformGen != playGen) return@launch
            val qi = queue.indexOfFirst { it.id == cur.id }
            if (qi >= 0) {
                queue = queue.toMutableList().also { it[qi] = matched }
                playAt(context, qi)
            } else {
                playList(context, listOf(matched), 0)
            }
            toast("已切换到${MusicApi.platformLabel(target) ?: target}")
        }
    }

    private fun playAt(context: Context, i: Int) {
        if (i !in queue.indices) return
        // v0.131.0：切歌即取消上一首未完成的解析（见 playJob 声明处）
        playJob?.cancel()
        // v0.213.0：新起播先取消在途渐弱，尾巴不再暂停新歌。
        fadeJob?.cancel()
        // v0.213.0 播错修复：forced 原来是全局裸变量，本地歌不走
        // resolve、解析协程被取消时都没消费，下一首错用它解析；
        // 改在 playAt 入口捕获即清零，随本次解析参数传入，过期不泄漏。
        val forcedForThisPlay = forcedSourceId.also { forcedSourceId = null }
        // v0.213.0 播错修复：解析失败回滚用——失败前先记住旧下标，
        // 失败时把 index/queueState 恢复，队列弹窗不再高亮一首没在播的歌。
        val prevIndex = index
        index = i
        _queueState.value = QueueState(queue, i)
        persistQueue(context)
        val track = queue[i]
        val gen = ++playGen
        // v0.146.0：新地址没拿到手之前不切显示态——播放页仍以控制器实际在播的
        // 旧曲为准（旧曲在播显示暂停键），地址到手真切过去时再换显示；解析失败只提示。
        // （此前这里立刻把 state 切到新曲 loading，旧曲还在响，标题/进度/按钮三者错位。）
        // v0.125.0 补充A：无自带封面的歌在播放器 scope 里做海报匹配（切歌即触发，
        // 与页面组合无关，匹配结果进 matchedArt 供播放页读）
        ensureMatchedArt(track)
        playJob = scope.launch {
            // v0.81.0：本地音频直接播 content URI；在线歌曲实时取直链（不缓存）
            // v0.89.0：直链接口偶发 503，失败 1.5 秒后重试一次；封面与直链并发解析，不拖慢起播
            // v0.100.0：在线歌曲先走启用的 LX 音乐源解析（内置 huibq 纯 HTTP / 用户脚本），
            // 全部失败再回退 MusicApi.playUrl（GD Studio）
            // v0.126.0：整条解析链按曲目自身平台走（LX 取档/解析/huibq/GD 兜底同口径）
            val platform = MusicApi.normalizeSource(track.source).ifBlank { "wy" }
            val mediaDeferred = async {
                if (track.isLocal) {
                    Resolved(track.localUri, "本地文件", "")
                } else {
                    resolveLxUrl(context.applicationContext, track, forcedForThisPlay)
                        ?: try {
                            MusicApi.playUrl(track.urlId, platform).takeIf { it.isNotBlank() }
                                ?.let { Resolved(it, "GD Studio", "") }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LogBus.w("MusicPlayer", "取直链失败，重试一次：${track.name}（${e.message}）")
                            delay(1500)
                            runCatching { MusicApi.playUrl(track.urlId, platform) }.getOrNull()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { Resolved(it, "GD Studio", "") }
                        }
                }
            }
            val artDeferred = async {
                if (track.picId.isBlank()) "" else {
                    runCatching { MusicApi.picImageUrl(track.picId, platform) }.getOrDefault("")
                }
            }
            val resolved = mediaDeferred.await()
            if (resolved == null || resolved.url.isBlank()) {
                artDeferred.cancel()
                // v0.131.0：失败只报一次——已切歌的旧代静默退出，不记日志不 toast 不动 state
                if (gen != playGen) return@launch
                // v0.213.0 播错修复：解析失败把下标滚回旧值——否则队列
                // 弹窗高亮失败歌、下一首/播完自动切歌都从错位置算、队列里
                // 点高亮的失败歌还因 i==index 直接 no-op 点不动。
                // 注意：playList 换过队列时旧下标可能越界，越界则归 -1。
                val rollbackIndex = prevIndex.takeIf { it in queue.indices } ?: -1
                index = rollbackIndex
                _queueState.value = QueueState(queue, rollbackIndex)
                persistQueue(context)
                LogBus.e("MusicPlayer", "取播放地址失败：${track.name}")
                // v0.146.0：显示态没切过新曲，这里只提示，不动 state（旧曲该播还播）。
                // LX 链撞上试听且备用线路也没拿到时，说准"只有试听片段"而不是泛泛的失败。
                // v0.215.0：失败原因里带登录／过期／Cookie／验证字样时，指路到音源管理补 Cookie，
                // 不再静默降级成一句"请重试"。
                val cookieExpired = lastLxFailures.any { f ->
                    f.contains("登录") || f.contains("过期") || f.contains("验证") ||
                        f.contains("授权") || f.contains("cookie", ignoreCase = true) ||
                        f.contains("login", ignoreCase = true)
                }
                toast(
                    when {
                        lastTrialOnly -> "该歌曲只有试听片段"
                        cookieExpired -> "取不到播放地址，可能是音源 Cookie 过期，去音源管理里更新 Cookie 再试"
                        else -> "取不到播放地址，请重试"
                    },
                )
                return@launch
            }
            // v0.117.0：记下本曲的真实来源与音质（列表徽标 + 播放页展示用）；
            // gen 已过期（用户已切歌）时别把旧歌的来源盖到新歌上
            if (gen == playGen) {
                // v0.146.0：地址到手才切显示态（见 playAt 开头）。
                // v0.89.0：保留播放模式（之前每次切歌都重置回 ALL）
                _state.value = State(
                    track = track, playing = false, loading = true,
                    repeatMode = _state.value.repeatMode,
                )
                _badges.value = _badges.value + (durationKey(track) to (resolved.source to resolved.quality))
                _state.value = _state.value.copy(
                    resolvedSource = resolved.source,
                    resolvedQuality = resolved.quality,
                )
            }
            val mediaUri = resolved.url
            // v0.217.0（F34）：封面是装饰、直链是主链路——封面解析单独给 1.5 秒
            // 短超时，超时先播无封面版，不再让封面接口拖住起播；超时后取消
            // 封面任务，不留后台空转。直链与封面本就并发解析，多数情况下
            // 封面早已到手，超时只在封面异常慢时生效。
            val artworkUrl = try {
                kotlinx.coroutines.withTimeout(1500) { artDeferred.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                artDeferred.cancel()
                ""
            }
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
     * v0.124.0：逐档试的档位改按平台取脚本自报 qualitys——引擎侧 getQualityIds
     * 修好对象形状解析后，flac/flac24bit 等高档第一次真能进尝试序列（此前恒空、恒走
     * 兜底 320k→128k）。App 无手动音质上限设置，默认即最高档策略；逐档只在上一档
     * 明确失败（脚本返回空/报错）时才降，成功即停，不拖慢正常起播。
     * v0.126.0：平台不再写死 wy——按曲目自身平台（LX code）取档/解析，huibq 内置源
     * 同口径拼 url 路径；songId 必须是该平台自己的歌曲 id（跨平台切换时由
     * [playOnPlatform] 先匹配换 id），拿网易 id 去别的平台解析注定失败，不在此兜底。
     * v0.131.0：改收 Track 本体——songId/platform 在内部从 track 派生，脚本侧
     * musicInfo 补传 name/artist/album（interval 无时长字段传空串），缺字段会让
     * 部分后端（如 mg 星海）直接拒绝解析。并发治理同版：循环头与收尾 ensureActive，
     * 被切歌取消时 CE 穿出、不再记「全部失败」也不 toast。
     */
    private suspend fun resolveLxUrl(
        appContext: Context,
        track: MusicApi.Track,
        forced: String?,
    ): Resolved? {
        val songId = track.urlId
        val platform = MusicApi.normalizeSource(track.source).ifBlank { "wy" }
        // v0.146.0：每轮先清试听标记（源为空直接返回时也不能留上一首的值）。
        // v0.217.0（F30）：失败原因同理——早退路径（无 songId／无启用源）不清除，
        // 上一首的 Cookie 过期提示会串到下一首的网络失败上。
        lastTrialOnly = false
        lastLxFailures = emptyList()
        if (songId.isBlank()) return null
        // v0.108.0 B1：一次性指定源由 playAt 入口捕获传入（本地歌/取消
        // 路径不再泄漏到下一首）；该源不存在/已禁用时 sources 为空，
        // 自然回落为调用方 MusicApi.playUrl（GD Studio）兜底
        val preferred = _preferredSourceId.value
        val enabledIds = runCatching { MusicSourceStore.list(appContext) }
            .getOrDefault(emptyList())
            .filter { it.enabled }
        val sources = enabledIds.let { list ->
            when {
                // v0.213.0：指定源失败不再只剩 GD 兜底——注释早说回落
                // 默认顺序，代码却只剩 GD；指定源排最前、其余启用源随后。
                forced != null -> list.sortedBy { if (it.id == forced) 0 else 1 }
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
            currentCoroutineContext().ensureActive()
            // v0.213.0 播错修复：解析循环原来开头快照 enabled 源后不再
            // 重读注册表，删/禁源后在途解析仍能用已删源抢到播放权；
            // 每源尝试前重读一次启用态，已删/已禁直接跳过。
            val stillEnabled = runCatching { MusicSourceStore.list(appContext) }
                .getOrDefault(emptyList()).any { it.id == s.id && it.enabled }
            if (!stillEnabled) continue
            val qualities: List<String> = if (s.builtin) {
                LxMusicSourceEngine.BUILTIN_QUALITIES
            } else {
                runCatching { engine.loadScript(s.url) }.getOrDefault(false)
                // v0.126.0：按曲目平台取档，别把某平台独占档拿来对别的平台空试
                LxMusicSourceEngine.sortHighToLow(engine.getQualityIds(s.url, platform))
                    .ifEmpty { listOf("320k", "128k") }
            }
            var reason = "解析失败"
            for (q in qualities) {
                currentCoroutineContext().ensureActive()
                val url = runCatching {
                    if (s.builtin) resolveBuiltinHuibq(platform, songId, q)
                    else engine.resolveMusicUrl(
                        s.url, platform, songId, q,
                        name = track.name, singer = track.artist,
                        albumName = track.album, interval = "",
                    )
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
        currentCoroutineContext().ensureActive()
        // v0.146.0：记下本轮是否撞上试听——最终失败时才能说准"只有试听片段"。
        lastTrialOnly = failures.any { it.contains("试听") }
        // v0.215.0：本轮各源失败原因留给 playAt 最终提示用（Cookie 过期要指路到音源管理）。
        lastLxFailures = failures
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

    // v0.213.0 播错修复：渐弱原来是裸 launch 无句柄，视频联动暂停
    // 或睡眠定时后用户 2 秒内手动恢复，尾巴仍把歌按停；记句柄，
    // 新渐弱/新起播先取消旧渐弱。
    private var fadeJob: Job? = null

    /** v0.85.0：睡眠定时到期——2 秒线性渐弱后暂停，恢复音量 */
    fun fadeOutAndPause() {
        val c = controller ?: return
        fadeJob?.cancel()
        fadeJob = scope.launch {
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
                    val dur = c.duration.coerceAtLeast(0L)
                    _state.value = _state.value.copy(
                        positionMs = c.currentPosition.coerceAtLeast(0L),
                        durationMs = dur,
                    )
                    // v0.215.0：播到时长就记下来，供列表行显示（只在变化时写盘）。
                    _state.value.track?.let { recordDuration(it, dur) }
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
        if (MusicSleepTimer.consumeAfterEpisode()) {
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

    /** v0.129.0：播放列表弹窗点选跳播（点当前曲目不重播）。
     * v0.217.0（F26）：当前曲目正在播时仍不重播；其他情况（恢复后未起播、
     * 已暂停等）点按即重播——恢复的队列不再是“看得见、播不了”的孤儿。 */
    fun playQueueItem(context: Context, i: Int) {
        if (i in queue.indices && (i != index || _state.value.playing != true)) playAt(context, i)
    }

    fun stop() {
        // v0.217.0（F25）：停播必须同时取消解析任务、淡出任务并推进代际——
        // 原来只停进度与控制器，解析卡住时点关闭／清空，旧歌走完解析又自己播起来。
        playJob?.cancel()
        fadeJob?.cancel()
        playGen += 1
        progressJob?.cancel()
        controller?.stop()
        _state.value = State(repeatMode = _state.value.repeatMode)
        queue = emptyList()
        index = -1
        _queueState.value = QueueState()
        persistQueue(null)
    }

    /** 切 tab/退后台不自动停；App 退出时 MainActivity.onDestroy 可调 release */
    fun release() {
        // v0.217.0（F25）：release 同理——在途解析不得在释放后复活起播。
        playJob?.cancel()
        fadeJob?.cancel()
        playGen += 1
        progressJob?.cancel()
        controller?.release()
        controller = null
        // v0.109.0 B4①：随 MusicPlayer 一起释放复用的 LX 引擎
        runCatching { lxEngine?.release() }
        lxEngine = null
    }
}
