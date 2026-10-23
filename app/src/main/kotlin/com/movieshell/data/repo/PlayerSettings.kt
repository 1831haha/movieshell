package com.movieshell.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** v0.20.0：播放器内核（Media3 默认，libmpv 可选，记忆选择） */
enum class PlayerEngine { MEDIA3, LIBMPV }

/**
 * v0.21.0：外挂字幕样式（双引擎共用，存在 movieshell_prefs）。
 * - edge: 0=无 1=描边 2=阴影
 * - position: 0=底部 1=顶部
 * - offsetMs: 字幕时间偏移，±10000ms
 */
data class SubtitleStyle(
    val sizeSp: Int = 16,
    val color: Int = 0xFFFFFFFF.toInt(),
    val edge: Int = EDGE_OUTLINE,
    val position: Int = POS_BOTTOM,
    val offsetMs: Long = 0L,
) {
    companion object {
        const val EDGE_NONE = 0
        const val EDGE_OUTLINE = 1
        const val EDGE_SHADOW = 2
        const val POS_BOTTOM = 0
        const val POS_TOP = 1
    }
}

/**
 * 播放器设置持久化（v0.14.0）。
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
    /** v0.74.0：倍速记忆 */
    private const val KEY_SPEED = "player_speed"
    /** v0.125.0：全屏画面比例档位（0=适应 1=裁剪 2=拉伸） */
    private const val KEY_RESIZE_MODE = "player_resize_mode"
    /** v0.125.0：歌词颜色 ARGB（0=跟随主题色） */
    private const val KEY_LYRIC_COLOR = "lyric_color"
    /** v0.126.0：音乐在线搜索平台（LX code，默认 wy） */
    private const val KEY_MUSIC_PLATFORM = "music_search_platform"

    private val _engine = MutableStateFlow(PlayerEngine.MEDIA3)
    val engine: StateFlow<PlayerEngine> = _engine

    private val _subtitleStyle = MutableStateFlow(SubtitleStyle())
    val subtitleStyle: StateFlow<SubtitleStyle> = _subtitleStyle

    /** v0.27.7：重力感应自动横屏（默认开） */
    private val _gravityRotate = MutableStateFlow(true)
    val gravityRotate: StateFlow<Boolean> = _gravityRotate

    /** v0.125.0：歌词颜色（0=跟随主题 primary，正值=ARGB 预设色） */
    private val _lyricColor = MutableStateFlow(0)
    val lyricColor: StateFlow<Int> = _lyricColor

    /** v0.126.0：音乐在线搜索平台（LX code，归一后取值，认不出回 wy） */
    private val _musicPlatform = MutableStateFlow("wy")
    val musicPlatform: StateFlow<String> = _musicPlatform

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
        _lyricColor.value = prefs.getInt(KEY_LYRIC_COLOR, 0)
        // v0.180.0：别名先归一，认不出的 code 原样保留（小写）——
        // 音源以后报出新平台时，已选平台不能被 normalize 的“认不出”打回 wy。
        val savedPlatform = prefs.getString(KEY_MUSIC_PLATFORM, "wy").orEmpty()
        _musicPlatform.value = MusicApi.normalizeSource(savedPlatform)
            .ifBlank { savedPlatform.trim().lowercase() }
            .ifBlank { "wy" }
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

    /** v0.74.0：取记忆的倍速（默认 1.0x）。 */
    fun getSpeed(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_SPEED, 1.0f).takeIf { it > 0f } ?: 1.0f

    /** v0.74.0：记忆倍速。 */
    fun setSpeed(context: Context, speed: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_SPEED, speed).apply()
    }

    /**
     * v0.125.0：取记忆的全屏画面比例档位（0=适应 1=裁剪 2=拉伸）。
     * 从没选过默认 2（拉伸铺满）——v0.123/124 的裁剪铺满会切掉画面上下约两成，
     * 用户反馈"卡边"，默认改拉伸；选过的一直按存的档位走。
     */
    fun getResizeMode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_RESIZE_MODE, 2).coerceIn(0, 2)

    /** v0.125.0：记忆全屏画面比例档位。 */
    fun setResizeMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_RESIZE_MODE, mode.coerceIn(0, 2)).apply()
    }

    /** v0.126.0：记忆音乐搜索平台（LX code，归一后落盘）。
     *  v0.180.0：认不出的 code 原样落盘，不打回 wy（见 init 的注释）。 */
    fun setMusicPlatform(context: Context, code: String) {
        val lx = MusicApi.normalizeSource(code)
            .ifBlank { code.trim().lowercase() }
            .ifBlank { "wy" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MUSIC_PLATFORM, lx).apply()
        _musicPlatform.value = lx
    }

    /** v0.125.0：记忆歌词颜色（0=跟随主题）。 */
    fun setLyricColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LYRIC_COLOR, color).apply()
        _lyricColor.value = color
    }
}
