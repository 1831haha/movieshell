package com.movieshell.ui.theme

import kotlin.math.pow

/**
 * v1.2.14 E片：WCAG 对比度纯函数（无 Compose 依赖，可单测）。
 *
 * - 相对亮度与对比度按 WCAG 2.1 公式：L = 0.2126R + 0.7152G + 0.0722B
 *  （sRGB 通道先线性化），对比度 = (L1 + 0.05) / (L2 + 0.05)。
 * - 正文阈值 4.5:1，大字（18pt/24px 以上或 14pt/18.66px 以上粗体）3:1，
 *   非文本（边框、图标、状态点、进度轨）3:1。
 * - [blendOver] 把带透明度的前景色按 alpha 合成到不透明背景上，再算对比度——
 *   半透明文字的真实对比度看合成后的颜色，不是原色。
 */
object Contrast {

    /** sRGB 通道（0..255）线性化。 */
    fun linearize(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** ARGB 整数的相对亮度（忽略 alpha，调用方先用 [blendOver] 合成）。 */
    fun relativeLuminance(argb: Int): Double {
        val r = linearize((argb shr 16) and 0xFF)
        val g = linearize((argb shr 8) and 0xFF)
        val b = linearize(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** 两 ARGB 色的对比度（1.0..21.0）。 */
    fun contrastRatio(fgArgb: Int, bgArgb: Int): Double {
        val l1 = relativeLuminance(fgArgb)
        val l2 = relativeLuminance(bgArgb)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** 带透明度的前景色合成到不透明背景上，返回不透明 ARGB。 */
    fun blendOver(fgArgb: Int, bgArgb: Int): Int {
        val a = ((fgArgb ushr 24) and 0xFF) / 255.0
        fun mix(fg: Int, bg: Int): Int = (fg * a + bg * (1.0 - a) + 0.5).toInt()
        val r = mix((fgArgb shr 16) and 0xFF, (bgArgb shr 16) and 0xFF)
        val g = mix((fgArgb shr 8) and 0xFF, (bgArgb shr 8) and 0xFF)
        val b = mix(fgArgb and 0xFF, bgArgb and 0xFF)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 正文是否达标（默认 4.5:1，大字 3:1）。 */
    fun meetsText(fgArgb: Int, bgArgb: Int, large: Boolean = false): Boolean =
        contrastRatio(fgArgb, bgArgb) >= if (large) 3.0 else 4.5

    /** 非文本（边框、图标、状态点）是否达标（3:1）。 */
    fun meetsNonText(fgArgb: Int, bgArgb: Int): Boolean =
        contrastRatio(fgArgb, bgArgb) >= 3.0
}
