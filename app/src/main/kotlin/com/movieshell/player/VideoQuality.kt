package com.movieshell.player

/**
 * v1.1.19：清晰度口径——播放页效果图的“4K / 1080P / 720P”落代码时的唯一算法。
 *
 * 优先级：真实播放流尺寸 > 线路名推测 > “自动”。
 * 真实尺寸来自 PlayerManager.videoSize（Media3 的 onVideoSizeChanged）；
 * 线路名只是参考（效果图里线路自带的 data-q 本就是演示占位值），名字里
 * 写“4K”的线路，实际流未必是 4K（播放器还把自适应上限封在 1080p）。
 * libmpv 路拿不到真实尺寸时，同样退到线路名推测。
 */
object VideoQuality {
    /** 真实尺寸 → 清晰度档位（按短边取整：3840×2160=4K、1920×1080=1080P、1280×720=720P）。 */
    fun fromSize(width: Int, height: Int): String {
        val h = minOf(width, height)
        return when {
            h >= 2160 -> "4K"
            h >= 1080 -> "1080P"
            h >= 720 -> "720P"
            h >= 480 -> "480P"
            h >= 360 -> "360P"
            else -> "流畅"
        }
    }

    /**
     * 线路名 → 推测档位。只认写明的数字与常见叫法，认不出返回 null（不编档位）：
     * 4K/2160→4K；1080/蓝光/超清→1080P；720/高清→720P；480/标清/流畅→480P。
     */
    fun parseLineHint(lineName: String): String? {
        val n = lineName.lowercase()
        return when {
            "4k" in n || "2160" in n -> "4K"
            "1080" in n || "蓝光" in n || "超清" in n -> "1080P"
            "720" in n || "高清" in n -> "720P"
            "480" in n || "标清" in n || "流畅" in n -> "480P"
            else -> null
        }
    }

    /** 显示用的清晰度：真实尺寸优先，拿不到时用线路名推测，再拿不到显示“自动”。 */
    fun label(width: Int, height: Int, lineName: String?): String =
        if (width > 0 && height > 0) fromSize(width, height)
        else lineName?.let { parseLineHint(it) } ?: "自动"

    /** 分辨率角标：真实尺寸才显示（“1920×1080”），拿不到显示空串，调用方隐藏。 */
    fun resolutionText(width: Int, height: Int): String =
        if (width > 0 && height > 0) "${width}×${height}" else ""

    /** 码率（bit/s）→ 网速文案（“8.1MB/s”）；0 表示当前无网络流量，显示短横线。 */
    fun bitrateText(bitsPerSec: Long): String {
        val bytes = bitsPerSec / 8
        return when {
            bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fMB/s", bytes / 1_000_000.0)
            bytes >= 1_000 -> "${bytes / 1_000}KB/s"
            bytes > 0 -> "${bytes}B/s"
            else -> "—"
        }
    }
}
