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

    /** v0.125.0：记忆歌词颜色（0=跟随主题）。 */
    fun setLyricColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LYRIC_COLOR, color).apply()
        _lyricColor.value = color
    }
}
