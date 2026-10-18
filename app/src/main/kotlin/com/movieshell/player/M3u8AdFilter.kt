package com.movieshell.player

import com.movieshell.data.repo.LogBus

/**
 * v0.18.0：m3u8 去广告过滤器（URI 关键词版）。
 * v0.97.0：升级为播放列表分片级分析。
 *
 * 判定思路参考 DsPlayer 的 fixAdM3u8Ai（drpy 生态 m3u8 去广告算法）：
 * 按 #EXT-X-DISCONTINUITY 把分片切组，对比各组分片时长与正片时长中位数
 * 的偏离度，综合判定广告分片。本实现为自研代码，仅借鉴"分组 + 时长偏离"
 * 的判定思路，未搬运其代码。
 *
 * 三信号综合判定（保守：至少两项命中才剔除，宁可漏过不误伤正片）：
 *  a) URI/路径命中广告关键词；
 *  b) 分片组时长中位数明显偏离全片时长中位数（<50% 或 >200%）；
 *  c) 单独成组、数量少（1~3 片）、插在正片中间、且偏短的分片组。
 *
 * 剔除方式：从返回给 ExoPlayer 的播放列表文本中删掉整组分片行
 * （EXTINF + 附属标签 + URI），并删掉该组 opening 的 DISCONTINUITY 标记；
 * #EXT-X-TARGETDURATION / #EXT-X-MEDIA-SEQUENCE 保持原值（自洽），
 * #EXT-X-DISCONTINUITY-SEQUENCE 按删除的标记数递减。
 *
 * 已知局限（照实）：
 *  - 只处理 Media3 链路；libmpv 链路拦截不了；
 *  - 只有 URL 以 .m3u8 结尾的播放列表会走过滤，无后缀 m3u8 走不到；
 *  - 加密分片（#EXT-X-KEY）一律不碰，直接放行；
 *  - 单组播放列表（无 DISCONTINUITY）基本不会触发剔除。
 */
object M3u8AdFilter {

    /** URL 中出现这些词即为广告关键词信号（v0.94 按 drpy 口径补 advert/adsrv/popad；v0.97 补 dai/pubads/adbreak） */
    private val AD_URL_KEYWORDS = listOf(
        "advert", "/ad/", "/ads/", "adserver",
        "doubleclick", "googlesyndication", "adsrv", "popad",
        "dai.google", "pubads", "adbreak",
    )

    private data class Segment(
        val groupId: Int,
        val duration: Double, // 秒；<0 表示未知
        val uri: String,
        val lineStart: Int, // EXTINF 行号
        val lineEnd: Int, // URI 行号，含
        val protected: Boolean, // EXT-X-KEY 覆盖的加密分片，一律放行
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

        // 快速通道：无分界标记且无关键词，直接返回
        val lower = content.lowercase()
        val hasBoundary = lines.any { it.trim() == "#EXT-X-DISCONTINUITY" }
        if (!hasBoundary && AD_URL_KEYWORDS.none { lower.contains(it) }) return content

        val parsed = parseSegments(lines)
        val segments = parsed.segments
        if (segments.size < 4) return content // 样本太少，不做统计判定

        val knownDurations = segments.mapNotNull { it.duration.takeIf { d -> d >= 0 } }.sorted()
        if (knownDurations.isEmpty()) return content
        val p50 = medianOf(knownDurations)

        val firstGroup = segments.first().groupId
        val lastGroup = segments.last().groupId
        val groupIds = segments.map { it.groupId }

        val removedSegIdx = mutableSetOf<Int>()
        val removedBoundaryLines = mutableSetOf<Int>()
        var seqAdjust = 0

        for (gid in groupIds.distinct()) {
            val idxs = segments.indices.filter { segments[it].groupId == gid }
            if (idxs.any { segments[it].protected }) continue // 加密组一律不碰
            val gDurs = idxs.mapNotNull { segments[it].duration.takeIf { d -> d >= 0 } }.sorted()
            val gMed = if (gDurs.isNotEmpty()) medianOf(gDurs) else -1.0
            val sigA = idxs.any { isAdUrl(segments[it].uri) }
            val sigB = segments.size >= 5 && gMed >= 0 &&
                (gMed < 0.5 * p50 || gMed > 2.0 * p50)
            val sigC = idxs.size in 1..3 && gid != firstGroup && gid != lastGroup &&
                gMed >= 0 && gMed < p50
            if (listOf(sigA, sigB, sigC).count { it } >= 2) {
                removedSegIdx.addAll(idxs)
                parsed.groupBoundary[gid]?.let { boundaryLine ->
                    removedBoundaryLines.add(boundaryLine)
                    seqAdjust++
                }
            }
        }

        if (removedSegIdx.isEmpty()) return content
        // 兜底：剩余分片不能少于 3 片，避免抽空播放列表
        if (segments.size - removedSegIdx.size < 3) return content

        val skipLines = mutableSetOf<Int>()
        for (si in removedSegIdx) {
            val s = segments[si]
            for (ln in s.lineStart..s.lineEnd) skipLines.add(ln)
        }
        skipLines.addAll(removedBoundaryLines)

        val out = StringBuilder()
        for ((idx, line) in lines.withIndex()) {
            if (idx in skipLines) continue
            var l = line
            if (l.trim().startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")) {
                l.trim().substringAfter(":").trim().toIntOrNull()?.let { n ->
                    l = "#EXT-X-DISCONTINUITY-SEQUENCE:${maxOf(0, n - seqAdjust)}"
                }
            }
            out.appendLine(l)
        }
        LogBus.i(
            "AdFilter",
            "m3u8 分片级去广告：剔除 ${removedSegIdx.size} 个疑似广告分片" +
                "（${segments.size} 片 / ${groupIds.distinct().size} 组）",
        )
        return out.toString()
    }

    private data class Parsed(
        val segments: List<Segment>,
        /** groupId -> 该组 opening 的 DISCONTINUITY 行号（组 0 无） */
        val groupBoundary: Map<Int, Int>,
    )

    /** 解析分片序列：按 DISCONTINUITY 切组，记录每片的行区间与加密保护态 */
    private fun parseSegments(lines: List<String>): Parsed {
        val segments = mutableListOf<Segment>()
        val groupBoundary = mutableMapOf<Int, Int>()
        var groupId = 0
        var pendingBoundary: Int? = null
        var keyActive = false // 其后分片受 EXT-X-KEY 保护，直到 METHOD=NONE
        var extInfIdx = -1
        var extInfDur = -1.0

        for ((idx, raw) in lines.withIndex()) {
            val t = raw.trim()
            when {
                t == "#EXT-X-DISCONTINUITY" -> {
                    groupId++
                    pendingBoundary = idx
                }
                t.startsWith("#EXT-X-KEY") -> {
                    val method = Regex("METHOD=([^,]+)").find(t)
                        ?.groupValues?.get(1)?.trim()?.uppercase()
                    // 无 METHOD 视为加密（保守：按保护处理）
                    keyActive = method != "NONE"
                }
                t.startsWith("#EXTINF:") -> {
                    // 上一个 EXTINF 后没等到 URI，视为游离行，直接覆盖
                    extInfIdx = idx
                    extInfDur = t.substringAfter(":").substringBefore(",")
                        .trim().toDoubleOrNull() ?: -1.0
                }
                t.isEmpty() || t.startsWith("#") -> {
                    // 标签行直通；EXTINF 与 URI 之间的附属行（KEY/MAP/BYTERANGE）
                    // 自然落在 [extInfIdx, URI行] 区间内，随分片一起处理
                }
                else -> {
                    // URI 行：分片成形（必须前面有 EXTINF）
                    if (extInfIdx >= 0) {
                        if (!groupBoundary.containsKey(groupId) && pendingBoundary != null) {
                            groupBoundary[groupId] = pendingBoundary
                            pendingBoundary = null
                        }
                        segments.add(
                            Segment(
                                groupId = groupId,
                                duration = extInfDur,
                                uri = t,
                                lineStart = extInfIdx,
                                lineEnd = idx,
                                protected = keyActive,
                            ),
                        )
                    }
                    extInfIdx = -1
                    extInfDur = -1.0
                }
            }
        }
        return Parsed(segments, groupBoundary)
    }

    private fun isM3u8(content: String): Boolean =
        content.trimStart().startsWith("#EXTM3U")

    private fun isAdUrl(url: String): Boolean {
        val u = url.lowercase()
        return AD_URL_KEYWORDS.any { u.contains(it) }
    }

    private fun medianOf(sorted: List<Double>): Double {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2]
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}
