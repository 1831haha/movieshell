package com.movieshell.player

/**
 * v0.18.0：m3u8 去广告过滤器。
 *
 * 原理：广告分片通常插在 #EXT-X-DISCONTINUITY 标记后，且 URL 含广告特征词
 * （ad/advertisement/gg 等），或时长异常短。过滤掉这些分片后重组播放列表。
 *
 * 保守策略：只在明确识别为广告时才删，避免误删正片。
 */
object M3u8AdFilter {

    /** URL 中出现这些词即判为广告分片 */
    private val AD_URL_KEYWORDS = listOf(
        "advertisement", "/ad/", "/ads/", "adserver",
        "doubleclick", "googlesyndication",
    )

    /**
     * 过滤 m3u8 播放列表中的广告分片。
     * @param content 原始 m3u8 文本
     * @param baseUrl 用于解析相对 URL（仅用于判断，不改写）
     * @return 过滤后的 m3u8 文本；若无需过滤则返回原文
     */
    fun filter(content: String, baseUrl: String = ""): String {
        if (!isM3u8(content)) return content

        val lines = content.lines()
        // 先快速检查：没有 DISCONTINUITY 且没有广告关键词，直接返回
        val hasDiscontinuity = lines.any { it.trim() == "#EXT-X-DISCONTINUITY" }
        val lower = content.lowercase()
        val hasAdKeyword = AD_URL_KEYWORDS.any { lower.contains(it) }
        if (!hasDiscontinuity && !hasAdKeyword) return content

        val out = StringBuilder()
        var discontinuityPending = false // 刚遇到 DISCONTINUITY，后续分片待判定
        var removed = 0

        for (line in lines) {
            val t = line.trim()
            when {
                t == "#EXT-X-DISCONTINUITY" -> {
                    // 先保留标记，等看到分片 URL 再决定是否整段删除
                    discontinuityPending = true
                    out.appendLine(line)
                }
                t.isEmpty() || t.startsWith("#") -> {
                    out.appendLine(line)
                    // 非 URL 行不影响待定状态（EXTINF 等）
                    if (!t.startsWith("#EXTINF") && !t.startsWith("#EXT-X-KEY")
                        && !t.startsWith("#EXT-X-MAP") && !t.startsWith("#EXT-X-PROGRAM")
                    ) {
                        // 其他标签行出现，取消待定（保守）
                        // 注意：不取消，广告块内也可能有标签
                    }
                }
                else -> {
                    // 分片 URL 行
                    val isAd = discontinuityPending && isAdSegment(t)
                    if (isAd) {
                        removed++
                        // 删除刚写入的 DISCONTINUITY 标记（回退一行）
                        removeLastDiscontinuity(out)
                        // 同时跳过它前面的 EXTINF（时长行）
                        removeLastExtInf(out)
                    } else {
                        out.appendLine(line)
                    }
                    discontinuityPending = false
                }
            }
        }

        return if (removed > 0) out.toString() else content
    }

    private fun isM3u8(content: String): Boolean =
        content.trimStart().startsWith("#EXTM3U")

    /** DISCONTINUITY 后的分片，URL 含广告特征才判为广告 */
    private fun isAdSegment(url: String): Boolean {
        val u = url.lowercase()
        return AD_URL_KEYWORDS.any { u.contains(it) }
    }

    private fun removeLastDiscontinuity(sb: StringBuilder) {
        val idx = sb.lastIndexOf("#EXT-X-DISCONTINUITY")
        if (idx >= 0) {
            // 删除从该行开始到末尾（该行之后只可能有 EXTINF，已单独处理）
            // 找到行首
            val lineStart = sb.lastIndexOf("\n", idx - 1).let { if (it < 0) 0 else it + 1 }
            // 找到行尾
            var lineEnd = sb.indexOf("\n", idx)
            lineEnd = if (lineEnd < 0) sb.length else lineEnd + 1
            sb.delete(lineStart, lineEnd)
        }
    }

    private fun removeLastExtInf(sb: StringBuilder) {
        val idx = sb.lastIndexOf("#EXTINF")
        if (idx >= 0) {
            val lineStart = sb.lastIndexOf("\n", idx - 1).let { if (it < 0) 0 else it + 1 }
            var lineEnd = sb.indexOf("\n", idx)
            lineEnd = if (lineEnd < 0) sb.length else lineEnd + 1
            // 确保这是最后一行附近（避免误删）
            val after = sb.substring(lineEnd).trim()
            if (after.isEmpty()) {
                sb.delete(lineStart, lineEnd)
            }
        }
    }
}
