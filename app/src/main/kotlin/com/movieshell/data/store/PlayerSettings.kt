package com.movieshell.data.store

import android.content.Context
import com.movieshell.core.log.LogBus
import com.movieshell.domain.model.PlayerEngine
import com.movieshell.domain.model.SubtitleStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放器设置持久化（v0.14.0）。
 *
 * v1.1.7 分层第 3 批：PlayerEngine 枚举与 SubtitleStyle 已移 domain/model/PlayerModels.kt，
 * 本文件只留持久化 object。去向定案：文档 §3.3（ui/player/）与 §3.4（data/player/）自相矛盾，
 * 且 data/source/subtitle/OnlineSubtitleKit 调 setSubtitleFor——进 ui/ 会让 data 依赖 ui，
 * 违背单向依赖；故本体留在 data/repo，第 4 批随 store 组走。
 *
 * v0.32.1：删掉全局"跳过片头/片尾"秒数——每部剧片头片尾时长都不一样，
 * 全局 N 秒对大多数剧都是错的，不再提供这个误导性设置。播放器默认不跳。
 */
object PlayerSettings {
    private const val PREFS = "movieshell_prefs"
    /** v0.16.2：按剧片头片尾（SkipSettingsDialog 用） */
    private const val PREFIX_INTRO = "skip_intro_vod_"
    private const val PREFIX_OUTRO = "skip_outro_vod_"
    /** v0.19.0：外挂字幕地址按剧记忆 */
    private const val PREFIX_SUBTITLE = "subtitle_vod_"
    /** v0.20.0：播放器内核 */
    private const val KEY_ENGINE = "player_engine"
    /** v0.21.0：字幕样式 */
    private const val KEY_SUB_SIZE = "subtitle_style_size"
    private const val KEY_SUB_COLOR = "subtitle_style_color"
    private const val KEY_SUB_EDGE = "subtitle_style_edge"
    private const val KEY_SUB_POS = "subtitle_style_pos"
    private const val KEY_SUB_OFFSET = "subtitle_style_offset"
    /** v0.27.7：重力感应自动横屏 */
    private const val KEY_GRAVITY_ROTATE = "gravity_rotate"
    private const val KEY_FIRST_SCREEN_TRACE = "first_screen_trace"
    /** v1.2.14 B片（9）：默认播放方向（0=跟随重力感应 1=横屏 2=竖屏） */
    private const val KEY_DEFAULT_ORIENTATION = "player_default_orientation"
    /** v1.2.14 B片（9）：播放器转场动画（0=系统默认 1=淡入 2=缩放 3=海报碎片） */
    private const val KEY_PLAYER_TRANSITION = "player_transition"
    /** v1.2.14 B片（3）：首选音频语言（空=不指定；zh/yue/en/ja/ko） */
    private const val KEY_AUDIO_LANG = "pref_audio_lang"
    /** v1.2.14 B片（3）：首选字幕语言（空=不指定；zh-Hans/zh-Hant/zh/en/ja/ko） */
    private const val KEY_SUBTITLE_LANG = "pref_subtitle_lang"
    /** v1.3.25 合并 5：Anime4K 画质增强档位（off/s/m/vl，默认 off） */
    private const val KEY_ANIME4K = "anime4k_mode"
    /** v0.74.0：倍速记忆 */
    private const val KEY_SPEED = "player_speed"
    /** v0.125.0：全屏画面比例档位（0=适应 1=裁剪 2=拉伸） */
    private const val KEY_RESIZE_MODE = "player_resize_mode"
    /** v0.255.0：拉伸默认档位一次性迁移标记（见 getResizeMode） */
    private const val KEY_RESIZE_MODE_MIGRATED = "player_resize_mode_migrated_v255"
    /** v0.125.0：歌词颜色 ARGB（0=跟随主题色） */
    /** v0.126.0：音乐在线搜索平台（LX code，默认 wy） */

    private val _engine = MutableStateFlow(PlayerEngine.MEDIA3)
    val engine: StateFlow<PlayerEngine> = _engine

    private val _subtitleStyle = MutableStateFlow(SubtitleStyle())
    val subtitleStyle: StateFlow<SubtitleStyle> = _subtitleStyle

    /** v0.27.7：重力感应自动横屏（默认开） */
    private val _gravityRotate = MutableStateFlow(true)
    val gravityRotate: StateFlow<Boolean> = _gravityRotate

    /** v1.4.6 A1.1：首屏分段诊断（默认关，仅新播放会话生效；关时零开销）。 */
    private val _firstScreenTrace = MutableStateFlow(false)
    val firstScreenTrace: StateFlow<Boolean> = _firstScreenTrace

    /**
     * v1.2.14 B片（9）：默认播放方向——进播放器时锁定的方向。
     * 0=跟随系统/重力感应（默认，保持现有行为）1=横屏 2=竖屏。
     * 横屏/竖屏只在进页时锁一次，用户仍可用顶栏切屏按钮手动改；
     * 嵌入模式（详情页小窗）不碰方向。
     */
    const val ORIENTATION_FOLLOW = 0
    const val ORIENTATION_LANDSCAPE = 1
    const val ORIENTATION_PORTRAIT = 2
    private val _defaultOrientation = MutableStateFlow(ORIENTATION_FOLLOW)
    val defaultOrientation: StateFlow<Int> = _defaultOrientation

    /**
     * v1.2.14 B片（9）：播放器打开/关闭的转场动画。
     * 0=系统默认（默认，保持现有淡入+位移）1=淡入 2=缩放 3=海报碎片
     * （碎片是进场时的碎片拼贴揭示，退场仍走淡出）。
     */
    const val TRANSITION_SYSTEM = 0
    const val TRANSITION_FADE = 1
    const val TRANSITION_SCALE = 2
    const val TRANSITION_SHARDS = 3
    private val _transitionMode = MutableStateFlow(TRANSITION_SYSTEM)
    val transitionMode: StateFlow<Int> = _transitionMode

    /**
     * v1.2.14 B片（3）：首选音频语言（空=不指定）。
     * 起播时喂给 Media3 的首选语言 + 轨道变化后按码匹配兜底；
     * 多音轨时也可在播放器齿轮页手动选，手动选择优先于本偏好。
     */
    private val _audioLanguage = MutableStateFlow("")
    val audioLanguage: StateFlow<String> = _audioLanguage

    /**
     * v1.3.25 合并 5：Anime4K 画质增强档位（存 key 字符串，off/s/m/vl；
     * 档位含义见 player/Anime4K）。仅 libmpv 引擎生效。
     */
    private val _anime4kMode = MutableStateFlow("off")
    val anime4kMode: StateFlow<String> = _anime4kMode

    /**
     * v1.2.14 B片（3）：首选字幕语言（空=不指定）。
     * 只影响在线字幕搜索结果的排序（匹配的排前面），不自动下载；
     * 起播时不自动重搜——没点过就是没选过。
     */
    private val _subtitleLanguage = MutableStateFlow("")
    val subtitleLanguage: StateFlow<String> = _subtitleLanguage

    /**
     * v0.294.5：直播自动播放——进直播页时是否自动起播上次/首个频道。
     * 0=始终（默认，保持老行为）1=仅 Wi‑Fi 2=关闭（需点一下才播）。
     */
    const val LIVE_AUTOPLAY_ALWAYS = 0
    const val LIVE_AUTOPLAY_WIFI = 1
    const val LIVE_AUTOPLAY_OFF = 2
    private const val KEY_LIVE_AUTOPLAY = "live_autoplay_mode"
    private val _liveAutoplay = MutableStateFlow(LIVE_AUTOPLAY_ALWAYS)
    val liveAutoplay: StateFlow<Int> = _liveAutoplay

    fun setLiveAutoplay(context: Context, mode: Int) {
        val m = mode.coerceIn(0, 2)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LIVE_AUTOPLAY, m).apply()
        _liveAutoplay.value = m
    }

    /** 当前网络下是否允许直播自动起播 */
    fun liveAutoplayAllowed(context: Context): Boolean = when (_liveAutoplay.value) {
        LIVE_AUTOPLAY_OFF -> false
        LIVE_AUTOPLAY_WIFI -> runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps != null && (
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                )
        }.getOrDefault(false)
        else -> true
    }

    /** v0.125.0：歌词颜色（0=跟随主题 primary，正值=ARGB 预设色） */
    /** v0.126.0：音乐在线搜索平台（LX code，归一后取值，认不出回 wy） */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _engine.value = runCatching {
            PlayerEngine.valueOf(prefs.getString(KEY_ENGINE, PlayerEngine.MEDIA3.name)!!)
        }.getOrDefault(PlayerEngine.MEDIA3)
        _subtitleStyle.value = SubtitleStyle(
            sizeSp = prefs.getInt(KEY_SUB_SIZE, 16).coerceIn(12, 32),
            color = prefs.getInt(KEY_SUB_COLOR, 0xFFFFFFFF.toInt()),
            edge = prefs.getInt(KEY_SUB_EDGE, SubtitleStyle.EDGE_OUTLINE).coerceIn(0, 2),
            position = prefs.getInt(KEY_SUB_POS, SubtitleStyle.POS_BOTTOM).coerceIn(0, 1),
            offsetMs = prefs.getLong(KEY_SUB_OFFSET, 0L).coerceIn(-10_000L, 10_000L),
        )
        _gravityRotate.value = prefs.getBoolean(KEY_GRAVITY_ROTATE, true)
        _firstScreenTrace.value = prefs.getBoolean(KEY_FIRST_SCREEN_TRACE, false)
        _defaultOrientation.value = prefs.getInt(KEY_DEFAULT_ORIENTATION, ORIENTATION_FOLLOW).coerceIn(0, 2)
        _transitionMode.value = prefs.getInt(KEY_PLAYER_TRANSITION, TRANSITION_SYSTEM).coerceIn(0, 3)
        _audioLanguage.value = prefs.getString(KEY_AUDIO_LANG, "").orEmpty()
        _subtitleLanguage.value = prefs.getString(KEY_SUBTITLE_LANG, "").orEmpty()
        _anime4kMode.value = prefs.getString(KEY_ANIME4K, "off").orEmpty()
            .takeIf { it in setOf("off", "s", "m", "vl") } ?: "off"
        _liveAutoplay.value = prefs.getInt(KEY_LIVE_AUTOPLAY, LIVE_AUTOPLAY_ALWAYS).coerceIn(0, 2)
    }

    /**
     * 取某部剧的片头跳过秒数：没设置过就返回 0（不跳）。
     * v0.32.1：删掉全局默认值 fallback——全局 N 秒对大多数剧是错的。
     */
    fun getSkipIntroFor(context: Context, vodId: String?): Int {
        if (vodId.isNullOrBlank()) return 0
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(PREFIX_INTRO + vodId.hashCode(), 0).coerceIn(0, 600)
    }

    /**
     * 取某部剧的片尾跳过秒数：没设置过就返回 0（不跳）。
     * v0.32.1：删掉全局默认值 fallback。
     */
    fun getSkipOutroFor(context: Context, vodId: String?): Int {
        if (vodId.isNullOrBlank()) return 0
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(PREFIX_OUTRO + vodId.hashCode(), 0).coerceIn(0, 600)
    }

    /** 设置某部剧的片头跳过秒数。 */
    fun setSkipIntroFor(context: Context, vodId: String, sec: Int) {
        val v = sec.coerceIn(0, 600)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(PREFIX_INTRO + vodId.hashCode(), v).apply()
        LogBus.i("PlayerSettings", "剧 $vodId 片头跳过设为 ${v}s")
    }

    /** 设置某部剧的片尾跳过秒数。 */
    fun setSkipOutroFor(context: Context, vodId: String, sec: Int) {
        val v = sec.coerceIn(0, 600)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(PREFIX_OUTRO + vodId.hashCode(), v).apply()
        LogBus.i("PlayerSettings", "剧 $vodId 片尾跳过设为 ${v}s")
    }

    /**
     * v0.19.0：取某部剧的外挂字幕地址（.srt/.vtt），未设置返回 ""。
     * vodId 为空直接返回 ""。
     */
    fun getSubtitleFor(context: Context, vodId: String?): String {
        if (vodId.isNullOrBlank()) return ""
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREFIX_SUBTITLE + vodId.hashCode(), "").orEmpty()
    }

    /** v0.19.0：设置某部剧的外挂字幕地址（空字符串表示清除）。 */
    fun setSubtitleFor(context: Context, vodId: String, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREFIX_SUBTITLE + vodId.hashCode(), url.trim()).apply()
        LogBus.i("PlayerSettings", "剧 $vodId 字幕设为 ${url.trim().take(80)}")
    }

    /**
     * v0.186.0 M18：按源按集的新 key。
     * 旧签名（只传 vodId）仍保留给现有调用点，改签名会断编译且播放页暂无 sourceKey 可传，
     * 播放页迁移另起一波（见本块末尾说明）；新签名调用点一律用下面这组重载，不许新旧混写。
     * 新 key = sourceKey + "|" + vodId + "|" + episodeIndex（不再用 32 位 hashCode，
     * 不同源同 vodId、hash 碰撞的不同剧不再互相覆盖/误读）。
     */
    fun showKey(sourceKey: String?, vodId: String, episodeIndex: Int): String =
        "${sourceKey.orEmpty()}|$vodId|$episodeIndex"

    private fun legacyKey(prefix: String, vodId: String): String = prefix + vodId.hashCode()

    /** v0.186.0 M18：新 key 取片头跳过秒数；新 key 无记录时把旧 key 值迁移复制过来。 */
    fun getSkipIntroFor(context: Context, sourceKey: String?, vodId: String?, episodeIndex: Int): Int {
        if (vodId.isNullOrBlank()) return 0
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = PREFIX_INTRO + showKey(sourceKey, vodId, episodeIndex)
        if (prefs.contains(key)) return prefs.getInt(key, 0).coerceIn(0, 600)
        val legacy = legacyKey(PREFIX_INTRO, vodId)
        if (!prefs.contains(legacy)) return 0
        val v = prefs.getInt(legacy, 0).coerceIn(0, 600)
        prefs.edit().putInt(key, v).apply()
        return v
    }

    /** v0.186.0 M18：新 key 取片尾跳过秒数；迁移口径同片头。 */
    fun getSkipOutroFor(context: Context, sourceKey: String?, vodId: String?, episodeIndex: Int): Int {
        if (vodId.isNullOrBlank()) return 0
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = PREFIX_OUTRO + showKey(sourceKey, vodId, episodeIndex)
        if (prefs.contains(key)) return prefs.getInt(key, 0).coerceIn(0, 600)
        val legacy = legacyKey(PREFIX_OUTRO, vodId)
        if (!prefs.contains(legacy)) return 0
        val v = prefs.getInt(legacy, 0).coerceIn(0, 600)
        prefs.edit().putInt(key, v).apply()
        return v
    }

    /** v0.186.0 M18：新 key 设片头跳过秒数；只写新 key，不动旧 key。 */
    fun setSkipIntroFor(context: Context, sourceKey: String?, vodId: String, episodeIndex: Int, sec: Int) {
        val v = sec.coerceIn(0, 600)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(PREFIX_INTRO + showKey(sourceKey, vodId, episodeIndex), v).apply()
        LogBus.i("PlayerSettings", "剧 $vodId 第 $episodeIndex 集片头跳过设为 ${v}s")
    }

    /** v0.186.0 M18：新 key 设片尾跳过秒数；只写新 key，不动旧 key。 */
    fun setSkipOutroFor(context: Context, sourceKey: String?, vodId: String, episodeIndex: Int, sec: Int) {
        val v = sec.coerceIn(0, 600)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(PREFIX_OUTRO + showKey(sourceKey, vodId, episodeIndex), v).apply()
        LogBus.i("PlayerSettings", "剧 $vodId 第 $episodeIndex 集片尾跳过设为 ${v}s")
    }

    /** v0.186.0 M18：新 key 取外挂字幕地址；新 key 无记录时把旧 key 值迁移复制过来。 */
    fun getSubtitleFor(context: Context, sourceKey: String?, vodId: String?, episodeIndex: Int): String {
        if (vodId.isNullOrBlank()) return ""
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = PREFIX_SUBTITLE + showKey(sourceKey, vodId, episodeIndex)
        if (prefs.contains(key)) return prefs.getString(key, "").orEmpty()
        val legacy = legacyKey(PREFIX_SUBTITLE, vodId)
        if (!prefs.contains(legacy)) return ""
        val v = prefs.getString(legacy, "").orEmpty()
        prefs.edit().putString(key, v).apply()
        return v
    }

    /** v0.186.0 M18：新 key 设外挂字幕地址；只写新 key，不动旧 key。 */
    fun setSubtitleFor(context: Context, sourceKey: String?, vodId: String, episodeIndex: Int, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREFIX_SUBTITLE + showKey(sourceKey, vodId, episodeIndex), url.trim()).apply()
        LogBus.i("PlayerSettings", "剧 $vodId 第 $episodeIndex 集字幕设为 ${url.trim().take(80)}")
    }

    /**
     * v0.186.0 M18：设置页清理入口用——删掉全部旧形 key，返回删掉条数。
     * 旧形判定：前缀 + 纯数字 hash（不含竖线分隔符）；新 key 一律含竖线，不会被误删。
     * 本函数不自动调用，须由设置页清理动作显式触发并向用户说明条数，
     * 否则仍走旧签名的调用点在清理后会丢掉按剧记忆；迁移不走本函数，
     * 首次被新 key 读到时上面各 getter 已把旧值复制过去。
     */
    fun clearLegacyPerShowKeys(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefixes = listOf(PREFIX_INTRO, PREFIX_OUTRO, PREFIX_SUBTITLE)
        val legacy = prefs.all.keys.filter { key ->
            val prefix = prefixes.firstOrNull { key.startsWith(it) } ?: return@filter false
            val rest = key.removePrefix(prefix)
            rest.isNotEmpty() && rest.toIntOrNull() != null && !rest.contains("|")
        }
        if (legacy.isEmpty()) return 0
        val editor = prefs.edit()
        legacy.forEach { editor.remove(it) }
        editor.apply()
        LogBus.i("PlayerSettings", "清理旧字幕/跳过 key ${legacy.size} 条")
        return legacy.size
    }

    /** v0.20.0：切换播放器内核（持久化，下次打开 App 仍有效）。 */
    fun setEngine(context: Context, engine: PlayerEngine) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENGINE, engine.name).apply()
        _engine.value = engine
        LogBus.i("PlayerSettings", "播放器内核设为 $engine")
    }

    /** v0.21.0：保存字幕样式（双引擎共用）。 */
    fun setSubtitleStyle(context: Context, style: SubtitleStyle) {
        val s = style.copy(
            sizeSp = style.sizeSp.coerceIn(12, 32),
            offsetMs = style.offsetMs.coerceIn(-10_000L, 10_000L),
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SUB_SIZE, s.sizeSp)
            .putInt(KEY_SUB_COLOR, s.color)
            .putInt(KEY_SUB_EDGE, s.edge.coerceIn(0, 2))
            .putInt(KEY_SUB_POS, s.position.coerceIn(0, 1))
            .putLong(KEY_SUB_OFFSET, s.offsetMs)
            .apply()
        _subtitleStyle.value = s
        LogBus.i("PlayerSettings", "字幕样式已保存：${s.sizeSp}sp 偏移${s.offsetMs}ms")
    }

    /** v0.27.7：重力感应自动横屏开关。 */
    fun setGravityRotate(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_GRAVITY_ROTATE, on).apply()
        _gravityRotate.value = on
    }

    /** v1.4.6 A1.1：首屏分段诊断开关（仅新播放会话生效）。 */
    fun setFirstScreenTrace(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FIRST_SCREEN_TRACE, on).apply()
        _firstScreenTrace.value = on
    }

    /** v1.2.14 B片（9）：默认播放方向（0 跟随 / 1 横屏 / 2 竖屏）。 */
    fun setDefaultOrientation(context: Context, mode: Int) {
        val m = mode.coerceIn(0, 2)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_DEFAULT_ORIENTATION, m).apply()
        _defaultOrientation.value = m
    }

    /** v1.2.14 B片（9）：播放器转场动画（0 系统默认 / 1 淡入 / 2 缩放 / 3 海报碎片）。 */
    fun setTransitionMode(context: Context, mode: Int) {
        val m = mode.coerceIn(0, 3)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PLAYER_TRANSITION, m).apply()
        _transitionMode.value = m
    }

    /** v1.2.14 B片（3）：首选音频语言（空=不指定）。 */
    fun setAudioLanguage(context: Context, code: String) {
        val v = code.trim()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_AUDIO_LANG, v).apply()
        _audioLanguage.value = v
    }

    /** v1.3.25 合并 5：设置 Anime4K 档位（非法 key 回落到 off）。 */
    fun setAnime4KMode(context: Context, key: String) {
        val v = if (key in setOf("off", "s", "m", "vl")) key else "off"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ANIME4K, v).apply()
        _anime4kMode.value = v
    }

    /** v1.2.14 B片（3）：首选字幕语言（空=不指定）。 */
    fun setSubtitleLanguage(context: Context, code: String) {
        val v = code.trim()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUBTITLE_LANG, v).apply()
        _subtitleLanguage.value = v
    }

    /** v0.74.0：取记忆的倍速（默认 1.0x）。 */
    fun getSpeed(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_SPEED, 1.0f).takeIf { it > 0f } ?: 1.0f

    /**
     * v1.1.19：倍速的共享流——播放页胶囊与播放器内倍速菜单/齿轮页共用同一
     * 个速度源，任一处改速，另一处立即跟上；旧 getSpeed 只读盘，没有流。
     * 首次调用时从盘里载入一次，之后只由 setSpeed 推进。
     */
    private val _speed = MutableStateFlow(1.0f)
    private var speedLoaded = false
    fun speedFlow(context: Context): StateFlow<Float> {
        if (!speedLoaded) {
            _speed.value = getSpeed(context)
            speedLoaded = true
        }
        return _speed
    }

    /** v0.74.0：记忆倍速。 */
    fun setSpeed(context: Context, speed: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_SPEED, speed).apply()
        _speed.value = speed
    }

    /**
     * v0.125.0：取记忆的全屏画面比例档位（0=适应 1=裁剪 2=拉伸）。
     * v0.255.0：默认改回 0（适应，不拉伸不变形）——拉伸是 v0.125 起的默认，
     * 从没进过"画面比例"菜单的用户手机里存的正是 2，光改默认值修不到他们；
     * 所以第一次读到存档 2 时一次性迁成 0 并打标记，之后用户再手动选拉伸
     * 不会被二次迁移盖掉。裁剪/拉伸仍在菜单里，想铺满可再选。
     */
    fun getResizeMode(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getInt(KEY_RESIZE_MODE, 0).coerceIn(0, 2)
        if (!prefs.getBoolean(KEY_RESIZE_MODE_MIGRATED, false)) {
            prefs.edit().putBoolean(KEY_RESIZE_MODE_MIGRATED, true).apply()
            if (stored == 2) {
                prefs.edit().putInt(KEY_RESIZE_MODE, 0).apply()
                return 0
            }
        }
        return stored
    }

    /** v0.125.0：记忆全屏画面比例档位。 */
    fun setResizeMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_RESIZE_MODE, mode.coerceIn(0, 2)).apply()
    }

}
