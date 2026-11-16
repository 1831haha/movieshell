package com.movieshell.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.14 E片：对比度回归——每组先断言改前不达标、再断言改后达标，
 * 数值与本次全仓审计的 Python 验算一致（误差 ±0.05）。
 */
class ContrastTest {

    private val lightSurface = 0xFFFFF8F5.toInt()
    private val darkSurface = 0xFF19120C.toInt()
    private val playerBg = 0xFF07070D.toInt()
    private val delta = 0.05

    @Test
    fun sanityBlackWhiteAndSameColor() {
        assertEquals(21.0, Contrast.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, Contrast.contrastRatio(lightSurface, lightSurface), 0.01)
    }

    @Test
    fun translucentSecondaryTextLight() {
        // 封面占位首字 / 副标题：onSurfaceVariant 0.5 浅色底只有 2.50:1，0.8 提到 5.14:1。
        val before = Contrast.blendOver(0x8051443B.toInt(), lightSurface)
        assertEquals(2.50, Contrast.contrastRatio(before, lightSurface), delta)
        assertFalse(Contrast.meetsText(before, lightSurface))
        val after = Contrast.blendOver(0xCC51443B.toInt(), lightSurface)
        assertEquals(5.14, Contrast.contrastRatio(after, lightSurface), delta)
        assertTrue(Contrast.meetsText(after, lightSurface))
    }

    @Test
    fun inputBorderOutlineVariantVsOutline() {
        // 输入框/搜索框/Chip 边框：outlineVariant 浅色底 1.62:1（< 3:1），outline 4.27:1。
        assertEquals(1.62, Contrast.contrastRatio(0xFFD6C3B6.toInt(), lightSurface), delta)
        assertFalse(Contrast.meetsNonText(0xFFD6C3B6.toInt(), lightSurface))
        assertEquals(4.27, Contrast.contrastRatio(0xFF847469.toInt(), lightSurface), delta)
        assertTrue(Contrast.meetsNonText(0xFF847469.toInt(), lightSurface))
        // 深色同理：outlineVariant 1.98:1 不达标，outline 5.86:1 达标。
        assertFalse(Contrast.meetsNonText(0xFF51443B.toInt(), darkSurface))
        assertTrue(Contrast.meetsNonText(0xFF9E8E82.toInt(), darkSurface))
    }

    @Test
    fun whiteTextOnSuccessGreen() {
        // 详情页选中 Tab 圆 / 线路 Chip / NEW 角标：白字压 #22C55E 只有 2.28:1，
        // 改深 #15803D 后 5.02:1。
        assertEquals(2.28, Contrast.contrastRatio(0xFFFFFFFF.toInt(), 0xFF22C55E.toInt()), delta)
        assertFalse(Contrast.meetsText(0xFFFFFFFF.toInt(), 0xFF22C55E.toInt()))
        assertEquals(5.02, Contrast.contrastRatio(0xFFFFFFFF.toInt(), 0xFF15803D.toInt()), delta)
        assertTrue(Contrast.meetsText(0xFFFFFFFF.toInt(), 0xFF15803D.toInt()))
    }

    @Test
    fun successGreenAsText() {
        // 更新行绿字：#22C55E 浅色底 2.17:1；浅色改 #146C2E 6.22:1，深色改 #3DD68C 9.88:1。
        assertFalse(Contrast.meetsText(0xFF22C55E.toInt(), lightSurface))
        assertTrue(Contrast.meetsText(0xFF146C2E.toInt(), lightSurface))
        assertTrue(Contrast.meetsText(0xFF3DD68C.toInt(), darkSurface))
    }

    @Test
    fun disabledButtonContent() {
        // M3 默认禁用字 onSurface 38%：浅色 2.34:1；AppButton/TechDialog 改 65% 后 5.27:1。
        val before = Contrast.blendOver(0x61221A14.toInt(), lightSurface)
        assertEquals(2.34, Contrast.contrastRatio(before, lightSurface), delta)
        assertFalse(Contrast.meetsText(before, lightSurface))
        val after = Contrast.blendOver(0xA6221A14.toInt(), lightSurface)
        assertEquals(5.27, Contrast.contrastRatio(after, lightSurface), delta)
        assertTrue(Contrast.meetsText(after, lightSurface))
        // 深色 65% 同样达标（6.59:1）。
        val darkAfter = Contrast.blendOver(0xA6EFE0D6.toInt(), darkSurface)
        assertTrue(Contrast.meetsText(darkAfter, darkSurface))
    }

    @Test
    fun probeDots() {
        // 探测状态点（3:1）：通 #3DD68C 浅色 1.79 → #15803D 4.78；
        // 慢 #E8B45A 1.80 → #9A6B0F 4.46；不通 #FF5A5F 2.91 → #D92D20 4.60。
        assertFalse(Contrast.meetsNonText(0xFF3DD68C.toInt(), lightSurface))
        assertTrue(Contrast.meetsNonText(0xFF15803D.toInt(), lightSurface))
        assertTrue(Contrast.meetsNonText(0xFF15803D.toInt(), darkSurface))
        assertFalse(Contrast.meetsNonText(0xFFE8B45A.toInt(), lightSurface))
        assertTrue(Contrast.meetsNonText(0xFF9A6B0F.toInt(), lightSurface))
        assertTrue(Contrast.meetsNonText(0xFF9A6B0F.toInt(), darkSurface))
        assertFalse(Contrast.meetsNonText(0xFFFF5A5F.toInt(), lightSurface))
        assertTrue(Contrast.meetsNonText(0xFFD92D20.toInt(), lightSurface))
        assertTrue(Contrast.meetsNonText(0xFFD92D20.toInt(), darkSurface))
        // 未测灰 #6B7385 两套主题都达标（4.53/3.90），不动。
        assertTrue(Contrast.meetsNonText(0xFF6B7385.toInt(), lightSurface))
        assertTrue(Contrast.meetsNonText(0xFF6B7385.toInt(), darkSurface))
    }

    @Test
    fun statusRingColors() {
        // 状态环（3:1）：就绪 #4FA866 浅色 2.81 → #2E8B47 4.08；
        // 加载中 #D9822B 浅色 2.79 → #9C6B00 4.43；失败 #DE3730 两套都达标，不动。
        assertFalse(Contrast.meetsNonText(0xFF4FA866.toInt(), lightSurface))
        assertTrue(Contrast.meetsNonText(0xFF2E8B47.toInt(), lightSurface))
        assertTrue(Contrast.meetsNonText(0xFF2E8B47.toInt(), darkSurface))
        assertFalse(Contrast.meetsNonText(0xFFD9822B.toInt(), lightSurface))
        assertTrue(Contrast.meetsNonText(0xFF9C6B00.toInt(), lightSurface))
        assertTrue(Contrast.meetsNonText(0xFF9C6B00.toInt(), darkSurface))
        assertTrue(Contrast.meetsNonText(0xFFDE3730.toInt(), lightSurface))
        assertTrue(Contrast.meetsNonText(0xFFDE3730.toInt(), darkSurface))
    }

    @Test
    fun okSemanticColorLight() {
        // ok 语义色浅色 #2E8B47 作文字只有 4.08:1，改 #2E7D32 后 4.88:1。
        assertEquals(4.08, Contrast.contrastRatio(0xFF2E8B47.toInt(), lightSurface), delta)
        assertFalse(Contrast.meetsText(0xFF2E8B47.toInt(), lightSurface))
        assertTrue(Contrast.meetsText(0xFF2E7D32.toInt(), lightSurface))
    }

    @Test
    fun musicPlayerSecondaryTextOnDarkBg() {
        // 音乐播放页（底恒为 #07070D）：白字 0.35 只有 3.11:1，0.6 提到 7.33:1。
        val before = Contrast.blendOver(0x59FFFFFF.toInt(), playerBg)
        assertEquals(3.11, Contrast.contrastRatio(before, playerBg), delta)
        assertFalse(Contrast.meetsText(before, playerBg))
        val after = Contrast.blendOver(0x99FFFFFF.toInt(), playerBg)
        assertEquals(7.33, Contrast.contrastRatio(after, playerBg), delta)
        assertTrue(Contrast.meetsText(after, playerBg))
    }

    @Test
    fun readerSecondaryText() {
        // 阅读页次要字：fg 0.6 浅米底 3.81:1，0.75 提到 5.94:1。
        val before = Contrast.blendOver(0x992B2B2B.toInt(), 0xFFF7F3E8.toInt())
        assertEquals(3.81, Contrast.contrastRatio(before, 0xFFF7F3E8.toInt()), delta)
        assertFalse(Contrast.meetsText(before, 0xFFF7F3E8.toInt()))
        val after = Contrast.blendOver(0xBF2B2B2B.toInt(), 0xFFF7F3E8.toInt())
        assertEquals(5.94, Contrast.contrastRatio(after, 0xFFF7F3E8.toInt()), delta)
        assertTrue(Contrast.meetsText(after, 0xFFF7F3E8.toInt()))
    }

    @Test
    fun detailStarsAndEpisodeChip() {
        // 剧评实心星：#F59E0B 浅色 2.05 → #8B5E00 5.41。
        assertFalse(Contrast.meetsText(0xFFF59E0B.toInt(), lightSurface))
        assertTrue(Contrast.meetsText(0xFF8B5E00.toInt(), lightSurface))
        // 选集 Chip：白字压 #A855F7 3.96 → #7B2C9C 7.83。
        assertFalse(Contrast.meetsText(0xFFFFFFFF.toInt(), 0xFFA855F7.toInt()))
        assertTrue(Contrast.meetsText(0xFFFFFFFF.toInt(), 0xFF7B2C9C.toInt()))
    }
}
