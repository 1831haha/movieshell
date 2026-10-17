package com.movieshell.data.repo

import android.content.Context

/**
 * v0.61.0 阅读器设置持久化（movieshell_prefs）。
 * 字号 / 夜间 / 衬线 / 行距 / 亮度，退出阅读器不丢失。
 */
object ReaderSettings {

    private const val PREFS = "movieshell_prefs"
    private const val KEY_FONT_SIZE = "reader_font_size"
    private const val KEY_NIGHT = "reader_night"
    private const val KEY_SERIF = "reader_serif"
    private const val KEY_LINE_RATIO = "reader_line_ratio"
    private const val KEY_BRIGHTNESS = "reader_brightness"
    private const val KEY_VOLUME_PAGE = "reader_volume_page"

    data class Settings(
        val fontSize: Int = 18,
        val nightMode: Boolean = false,
        val serifFont: Boolean = false,
        val lineHeightRatio: Float = 1.8f,
        val brightness: Float = -1f, // -1=跟随系统
        /** v0.72.0：音量键翻页（默认关） */
        val volumePageTurn: Boolean = false,
    )

    fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            fontSize = p.getInt(KEY_FONT_SIZE, 18).coerceIn(14, 24),
            nightMode = p.getBoolean(KEY_NIGHT, false),
            serifFont = p.getBoolean(KEY_SERIF, false),
            lineHeightRatio = p.getFloat(KEY_LINE_RATIO, 1.8f).coerceIn(1.2f, 2.4f),
            brightness = p.getFloat(KEY_BRIGHTNESS, -1f),
            volumePageTurn = p.getBoolean(KEY_VOLUME_PAGE, false),
        )
    }

    fun save(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FONT_SIZE, s.fontSize)
            .putBoolean(KEY_NIGHT, s.nightMode)
            .putBoolean(KEY_SERIF, s.serifFont)
            .putFloat(KEY_LINE_RATIO, s.lineHeightRatio)
            .putFloat(KEY_BRIGHTNESS, s.brightness)
            .putBoolean(KEY_VOLUME_PAGE, s.volumePageTurn)
            .apply()
    }
}
