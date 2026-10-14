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
 * v0.16.2：片头/片尾跳过改为按剧单独设置（用户纠正：每部剧的开头/结束时间都不一样）。
 * - 全局默认值：skipIntroSec / skipOutroSec（新剧的初始值）
 * - 按剧覆盖：vodId → intro/outro 秒数，存在则用剧的，不存在用全局
 */
object PlayerSettings {
    private const val PREFS = "movieshell_prefs"
    private const val KEY_SKIP_INTRO = "skip_intro_sec"
    private const val KEY_SKIP_OUTRO = "skip_outro_sec"
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

    private val _skipIntroSec = MutableStateFlow(0)
    val skipIntroSec: StateFlow<Int> = _skipIntroSec

    private val _skipOutroSec = MutableStateFlow(0)
    val skipOutroSec: StateFlow<Int> = _skipOutroSec

    private val _engine = MutableStateFlow(PlayerEngine.MEDIA3)
    val engine: StateFlow<PlayerEngine> = _engine

    private val _subtitleStyle = MutableStateFlow(SubtitleStyle())
    val subtitleStyle: StateFlow<SubtitleStyle> = _subtitleStyle

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _skipIntroSec.value = prefs.getInt(KEY_SKIP_INTRO, 0).coerceIn(0, 600)
        _skipOutroSec.value = prefs.getInt(KEY_SKIP_OUTRO, 0).coerceIn(0, 600)
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
    }

    /** 全局默认片头（新源初始值）。 */
    fun setSkipIntro(context: Context, sec: Int) {
        val v = sec.coerceIn(0, 600)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SKIP_INTRO, v).apply()
        _skipIntroSec.value = v
    }

    /** 全局默认片尾（新源初始值）。 */
    fun setSkipOutro(context: Context, sec: Int) {
        val v = sec.coerceIn(0, 600)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SKIP_OUTRO, v).apply()
        _skipOutroSec.value = v
    }

    /**
     * 取某部剧的片头跳过秒数：有按剧设置用剧的，否则用全局默认。
     * vodId 为空时直接返回全局。
     */
    fun getSkipIntroFor(context: Context, vodId: String?): Int {
        if (vodId.isNullOrBlank()) return _skipIntroSec.value
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = PREFIX_INTRO + vodId.hashCode()
        return if (prefs.contains(key)) prefs.getInt(key, 0).coerceIn(0, 600)
        else _skipIntroSec.value
    }

    /**
     * 取某部剧的片尾跳过秒数：有按剧设置用剧的，否则用全局默认。
     * vodId 为空时直接返回全局。
     */
    fun getSkipOutroFor(context: Context, vodId: String?): Int {
        if (vodId.isNullOrBlank()) return _skipOutroSec.value
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = PREFIX_OUTRO + vodId.hashCode()
        return if (prefs.contains(key)) prefs.getInt(key, 0).coerceIn(0, 600)
        else _skipOutroSec.value
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
}
