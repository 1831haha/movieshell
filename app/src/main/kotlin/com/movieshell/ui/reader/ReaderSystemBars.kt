package com.movieshell.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * v1.3.1：阅读器系统栏跟随页底色——状态栏与底部导航栏的底色跟阅读页底色走
 * （对标阅读 APP：系统栏底色与页面同色，图标深浅随底色明暗），不再是 App
 * 主题底色。图 1 侠影原来上下两条黑边，就是 M3 深色底色；图 2 阅读通体
 * 暖黄，就是系统栏跟了页底色。
 *
 * 单一事实来源：ReaderScreen 发布当前页底色，MainActivity 在阅读路由上
 * 消费；离页时清掉，其它路由仍按 M3 色板走。状态栏/导航栏的图标深浅由
 * MainActivity 现成的 luminance 判断负责，这里只给底色。
 */
object ReaderSystemBars {
    /** 当前阅读页底色；null = 不在阅读器内。 */
    var barColor by mutableStateOf<Color?>(null)
        private set

    fun publish(color: Color) {
        barColor = color
    }

    fun clear() {
        barColor = null
    }

    /** 阅读背景主题 → 页面底色（原 ReaderScreen 内的 when 块，抽出来可单测）。 */
    fun readerBgColor(bgTheme: Int, followDark: Boolean): Color = when (bgTheme) {
        0 -> Color(0xFFFFFFFF)
        2 -> Color(0xFFE8F0E4)
        3 -> Color(0xFF121212)
        4 -> if (followDark) Color(0xFF121212) else Color(0xFFFFFFFF)
        5 -> Color(0xFFDCE8EF)
        6 -> Color(0xFFD8D4CA)
        7 -> Color(0xFF20242B)
        8 -> Color(0xFFFBE9EC)
        9 -> Color(0xFFE9E4F3)
        10 -> Color(0xFF1E2B26)
        else -> Color(0xFFF7F3E8)
    }

    /** 底色亮 → 系统栏图标用深色（isAppearanceLight* = true）。 */
    fun useDarkIcons(bg: Color): Boolean = bg.luminance() > 0.5f
}
