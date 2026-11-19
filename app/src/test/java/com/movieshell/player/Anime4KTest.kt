package com.movieshell.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Anime4KTest {

    @Test
    fun shaderChains_matchUpstreamTemplates() {
        // S 档 = 上游 Mode C；M 档 = Template/GLSL_Windows_Low-end；
        // VL 档 = Template/GLSL_Windows_High-end（均按上游原文核对）。
        assertEquals(
            listOf(
                "Anime4K_Clamp_Highlights.glsl",
                "Anime4K_Restore_CNN_S.glsl",
                "Anime4K_Upscale_CNN_x2_S.glsl",
            ),
            Anime4K.shaderFiles(Anime4K.Mode.S),
        )
        assertEquals(
            listOf(
                "Anime4K_Clamp_Highlights.glsl",
                "Anime4K_Restore_CNN_M.glsl",
                "Anime4K_Upscale_CNN_x2_M.glsl",
                "Anime4K_AutoDownscalePre_x2.glsl",
                "Anime4K_AutoDownscalePre_x4.glsl",
                "Anime4K_Upscale_CNN_x2_S.glsl",
            ),
            Anime4K.shaderFiles(Anime4K.Mode.M),
        )
        assertEquals(
            listOf(
                "Anime4K_Clamp_Highlights.glsl",
                "Anime4K_Restore_CNN_VL.glsl",
                "Anime4K_Upscale_CNN_x2_VL.glsl",
                "Anime4K_AutoDownscalePre_x2.glsl",
                "Anime4K_AutoDownscalePre_x4.glsl",
                "Anime4K_Upscale_CNN_x2_M.glsl",
            ),
            Anime4K.shaderFiles(Anime4K.Mode.VL),
        )
        assertTrue(Anime4K.shaderFiles(Anime4K.Mode.OFF).isEmpty())
    }

    @Test
    fun shaderChain_usesColonSeparator() {
        // 沙箱 mpv 0.37 实测：":" 拆成多项，";" 会被当成一条路径。
        val chain = Anime4K.shaderChain(File("/data/anime4k"), Anime4K.Mode.S)
        assertEquals(
            "/data/anime4k/Anime4K_Clamp_Highlights.glsl" +
                ":/data/anime4k/Anime4K_Restore_CNN_S.glsl" +
                ":/data/anime4k/Anime4K_Upscale_CNN_x2_S.glsl",
            chain,
        )
        assertEquals("", Anime4K.shaderChain(File("/data/anime4k"), Anime4K.Mode.OFF))
    }

    @Test
    fun allShaderFiles_coversEightFiles() {
        assertEquals(8, Anime4K.ALL_SHADER_FILES.size)
        assertEquals(8, Anime4K.ALL_SHADER_FILES.distinct().size)
    }

    @Test
    fun fromKey_unknownFallsBackToOff() {
        assertEquals(Anime4K.Mode.M, Anime4K.fromKey("m"))
        assertEquals(Anime4K.Mode.VL, Anime4K.fromKey("vl"))
        assertEquals(Anime4K.Mode.OFF, Anime4K.fromKey("bogus"))
        assertEquals(Anime4K.Mode.OFF, Anime4K.fromKey(""))
    }
}
