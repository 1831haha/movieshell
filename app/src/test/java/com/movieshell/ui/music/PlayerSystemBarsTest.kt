package com.movieshell.ui.music

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerSystemBarsTest {

    @Test fun noArtFallsBackToAtmosphereBase() {
        assertEquals(PlayerSystemBars.ATMOSPHERE_BASE, PlayerSystemBars.atmosphereBarColor(null))
        assertEquals(Color(0xFF07070D), PlayerSystemBars.ATMOSPHERE_BASE)
    }

    @Test fun artAverageIsDarkenedToMatchPageTopEdge() {
        // 页面顶部是模糊封面压 38% 黑渐变，系统栏色 = 平均色 × 0.62。
        val white = PlayerSystemBars.atmosphereBarColor(Color(0xFFFFFFFF))
        assertEquals(0.62f, white.red, 0.001f)
        assertEquals(0.62f, white.green, 0.001f)
        assertEquals(0.62f, white.blue, 0.001f)
        val blue = PlayerSystemBars.atmosphereBarColor(Color(0xFF336699))
        assertEquals(0x33 / 255f * 0.62f, blue.red, 0.001f)
        assertEquals(0x66 / 255f * 0.62f, blue.green, 0.001f)
        assertEquals(0x99 / 255f * 0.62f, blue.blue, 0.001f)
        assertEquals(1f, blue.alpha, 0.001f)
    }

    @Test fun averageColorOfPixels() {
        // 红绿蓝白四像素平均：每通道 (255+0+0+255)/4 = 127。
        val avg = PlayerSystemBars.averageColorOf(
            intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt()),
        )
        assertEquals(127 / 255f, avg.red, 0.001f)
        assertEquals(127 / 255f, avg.green, 0.001f)
        assertEquals(127 / 255f, avg.blue, 0.001f)
        assertEquals(PlayerSystemBars.ATMOSPHERE_BASE, PlayerSystemBars.averageColorOf(intArrayOf()))
    }

    @Test fun publishAndClearRoundTrip() {
        PlayerSystemBars.publish(Color(0xFF112233))
        assertEquals(Color(0xFF112233), PlayerSystemBars.barColor)
        PlayerSystemBars.clear()
        assertNull(PlayerSystemBars.barColor)
    }
}
