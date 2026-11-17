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
        // Compose Color 按 8 位通道存储（单步 1/255≈0.0039），允差取 0.005。
        val white = PlayerSystemBars.atmosphereBarColor(Color(0xFFFFFFFF))
        assertEquals(0.62f, white.red, 0.005f)
        assertEquals(0.62f, white.green, 0.005f)
        assertEquals(0.62f, white.blue, 0.005f)
        val blue = PlayerSystemBars.atmosphereBarColor(Color(0xFF336699))
        assertEquals(0x33 / 255f * 0.62f, blue.red, 0.005f)
        assertEquals(0x66 / 255f * 0.62f, blue.green, 0.005f)
        assertEquals(0x99 / 255f * 0.62f, blue.blue, 0.005f)
        assertEquals(1f, blue.alpha, 0.005f)
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

    @Test fun noArtBarColorMatchesAtmosphereTopEdge() {
        // 品牌首色青 0xFF4DD0E1：0.32 铺底 + 顶部黑 0.38，两层叠加后约 (0.071, 0.173, 0.197)。
        val normal = PlayerSystemBars.noArtBarColor(Color(0xFF4DD0E1), immersive = false)
        assertEquals(0.071f, normal.red, 0.005f)
        assertEquals(0.173f, normal.green, 0.005f)
        assertEquals(0.197f, normal.blue, 0.005f)
        // 沉浸黑渐变顶部只有 0.18，颜色更亮。
        val immersive = PlayerSystemBars.noArtBarColor(Color(0xFF4DD0E1), immersive = true)
        assertEquals(0.095f, immersive.red, 0.005f)
        assertEquals(0.229f, immersive.green, 0.005f)
        assertEquals(0.260f, immersive.blue, 0.005f)
        assertEquals(PlayerSystemBars.ATMOSPHERE_BASE, PlayerSystemBars.noArtBarColor(null, false))
    }

    @Test fun compositeOverBlendsByAlpha() {
        val blended = PlayerSystemBars.compositeOver(
            Color(red = 1f, green = 0f, blue = 0f, alpha = 0.5f),
            Color(red = 0f, green = 0f, blue = 1f, alpha = 1f),
        )
        assertEquals(0.5f, blended.red, 0.005f)
        assertEquals(0f, blended.green, 0.005f)
        assertEquals(0.5f, blended.blue, 0.005f)
    }

    @Test fun publishAndClearRoundTrip() {
        PlayerSystemBars.publish(Color(0xFF112233))
        assertEquals(Color(0xFF112233), PlayerSystemBars.barColor)
        PlayerSystemBars.clear()
        assertNull(PlayerSystemBars.barColor)
    }
}
