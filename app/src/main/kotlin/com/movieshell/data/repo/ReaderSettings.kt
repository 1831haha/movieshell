package com.movieshell.data.repo

import android.content.Context

/**
 * v0.61.0 阅读器设置持久化（movieshell_prefs）。
 * 字号 / 夜间 / 衬线 / 行距 / 亮度，退出阅读器不丢失。
 * v0.89.0：加背景主题（bgTheme）与翻页模式（pageMode）；nightMode 字段保留做兼容，
 * UI 入口废弃——打开即按旧 nightMode 迁移为 bgTheme，之后只读写 bgTheme。
 */
object ReaderSettings {

    private const val PREFS = "movieshell_prefs"
    private const val KEY_FONT_SIZE = "reader_font_size"
    private const val KEY_NIGHT = "reader_night"
    private const val KEY_SERIF = "reader_serif"
    private const val KEY_LINE_RATIO = "reader_line_ratio"
    private const val KEY_BRIGHTNESS = "reader_brightness"
    private const val KEY_VOLUME_PAGE = "reader_volume_page"
    private const val KEY_BG_THEME = "reader_bg_theme"
    private const val KEY_FONT_FAMILY = "reader_font_family"
    private const val KEY_PAGE_MODE = "reader_page_mode"

    data class Settings(
        val fontSize: Int = 18,
        /** v0.89.0 起废弃：由 bgTheme 代替；load 时按 bgTheme 回填，仅做兼容 */
        val nightMode: Boolean = false,
        /**
         * v0.194.0追加：字体 0=系统默认 1=无衬线 2=衬线 3=等宽 4=手写。
         * 旧版只有 serifFont 开关；迁移：有新 key 用新 key，没有则旧开关
         * 开=衬线、关=无衬线（老用户选择不变）。
         */
        val fontFamily: Int = 1,
        val lineHeightRatio: Float = 1.8f,
        val brightness: Float = -1f, // -1=跟随系统
        /** v0.72.0：音量键翻页；v0.193.0 起默认开（用户要求“加上”，旧版默认关藏得太深） */
        val volumePageTurn: Boolean = true,
        /** v0.89.0：背景主题 0=白 1=米黄 2=护眼绿 3=夜间黑；v0.193.0 加 4=跟随系统 */
        val bgTheme: Int = 1,
        /** v0.89.0：翻页模式 0=上下滚动 1=左右覆盖 2=仿真翻页 */
        val pageMode: Int = 1,
    )

    fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 老用户迁移：没有 bgTheme key 时，按旧 nightMode 换算（开=夜间黑，关=米黄）
        val bgTheme = if (p.contains(KEY_BG_THEME)) {
            p.getInt(KEY_BG_THEME, 1).coerceIn(0, 4)
        } else {
            if (p.getBoolean(KEY_NIGHT, false)) 3 else 1
        }
        return Settings(
            fontSize = p.getInt(KEY_FONT_SIZE, 18).coerceIn(14, 24),
            nightMode = bgTheme == 3,
            fontFamily = if (p.contains(KEY_FONT_FAMILY)) {
                p.getInt(KEY_FONT_FAMILY, 1).coerceIn(0, 4)
            } else if (p.getBoolean(KEY_SERIF, false)) 2 else 1,
            lineHeightRatio = p.getFloat(KEY_LINE_RATIO, 1.8f).coerceIn(1.2f, 2.4f),
            brightness = p.getFloat(KEY_BRIGHTNESS, -1f),
            volumePageTurn = p.getBoolean(KEY_VOLUME_PAGE, true),
            bgTheme = bgTheme,
            pageMode = p.getInt(KEY_PAGE_MODE, 1).coerceIn(0, 2),
        )
    }

    fun save(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FONT_SIZE, s.fontSize)
            .putBoolean(KEY_NIGHT, s.nightMode)
            .putInt(KEY_FONT_FAMILY, s.fontFamily)
            .putFloat(KEY_LINE_RATIO, s.lineHeightRatio)
            .putFloat(KEY_BRIGHTNESS, s.brightness)
            .putBoolean(KEY_VOLUME_PAGE, s.volumePageTurn)
            .putInt(KEY_BG_THEME, s.bgTheme)
            .putInt(KEY_PAGE_MODE, s.pageMode)
            .apply()
    }
}
