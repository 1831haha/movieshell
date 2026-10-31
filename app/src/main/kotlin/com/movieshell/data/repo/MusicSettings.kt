package com.movieshell.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v0.218.0（S13）：音乐侧设置——原来音乐搜索平台与歌词颜色混在
 * 视频播放器的 PlayerSettings（movieshell_prefs）里，改音乐设置要动
 * 视频文件；现在迁到独立的 music_settings，PlayerSettings 只留引擎、
 * 字幕、倍速、画面比例、重力感应、跳过与下载相关。
 *
 * 迁移：首次 init 时若新文件里没有该 key、旧文件里有，就把旧值拷
 * 过来再读；旧 key 留在原文件不动（降级回旧版也不丢设置）。
 */

/**
 * v0.294.12：正在播放页布局 / 动态效果。
 * 经典=当前默认；唱片=圆形黑胶旋转；沉浸=大模糊封面氛围；
 * 频谱=封面下方律动条；卡片=紧凑小封面。
 */
enum class MusicPlayerLayout(
    val label: String,
    val description: String,
) {
    CLASSIC("经典", "封面边缘炫彩柔光脉冲"),
    VINYL("唱片", "黑胶旋转 + 炫彩反光扫光"),
    IMMERSIVE("沉浸", "流动炫彩极光氛围"),
    SPECTRUM("频谱", "炫彩多色律动频谱条"),
    CARD("炫彩", "旋转封面 + 轨道粒子光环"),
}

object MusicSettings {

    private const val PREFS = "music_settings"
    private const val OLD_PREFS = "movieshell_prefs"
    private const val KEY_LYRIC_COLOR = "lyric_color"
    private const val KEY_MUSIC_PLATFORM = "music_search_platform"
    private const val KEY_PLAYER_LAYOUT = "player_layout"

    private val _lyricColor = MutableStateFlow(0)
    val lyricColor: StateFlow<Int> = _lyricColor

    private val _musicPlatform = MutableStateFlow("wy")
    val musicPlatform: StateFlow<String> = _musicPlatform

    private val _playerLayout = MutableStateFlow(MusicPlayerLayout.CLASSIC)
    val playerLayout: StateFlow<MusicPlayerLayout> = _playerLayout

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
        // 一次性迁移：新文件缺 key、旧文件有值时拷过来。
        val migrate = prefs.edit()
        var needMigrate = false
        if (!prefs.contains(KEY_LYRIC_COLOR) && old.contains(KEY_LYRIC_COLOR)) {
            migrate.putInt(KEY_LYRIC_COLOR, old.getInt(KEY_LYRIC_COLOR, 0))
            needMigrate = true
        }
        if (!prefs.contains(KEY_MUSIC_PLATFORM) && old.contains(KEY_MUSIC_PLATFORM)) {
            migrate.putString(KEY_MUSIC_PLATFORM, old.getString(KEY_MUSIC_PLATFORM, "wy"))
            needMigrate = true
        }
        if (needMigrate) migrate.apply()
        _lyricColor.value = prefs.getInt(KEY_LYRIC_COLOR, 0)
        // v0.180.0 口径（原 PlayerSettings.init）：别名先归一，认不出的
        // code 原样保留（小写）——音源以后报出新平台时，已选平台不能被
        // normalize 的“认不出”打回 wy。
        val savedPlatform = prefs.getString(KEY_MUSIC_PLATFORM, "wy").orEmpty()
        _musicPlatform.value = MusicApi.normalizeSource(savedPlatform)
            .ifBlank { savedPlatform.trim().lowercase() }
            .ifBlank { "wy" }
        // v0.294.12：播放界面布局
        val savedLayout = prefs.getString(KEY_PLAYER_LAYOUT, null)
        _playerLayout.value = runCatching { MusicPlayerLayout.valueOf(savedLayout!!) }
            .getOrDefault(MusicPlayerLayout.CLASSIC)
    }

    /** v0.126.0：记忆音乐搜索平台（LX code，归一后落盘）。
     *  v0.180.0：认不出的 code 原样落盘，不打回 wy。 */
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

    /** v0.294.12：记忆正在播放页布局 / 动态效果。 */
    fun setPlayerLayout(context: Context, layout: MusicPlayerLayout) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PLAYER_LAYOUT, layout.name).apply()
        _playerLayout.value = layout
    }
}
