package com.movieshell.player

/**
 * v0.294.24：磁力转播纯逻辑（无 Android / libtorrent 依赖，单测覆盖）。
 * v0.294.27：补公共 tracker、硬超时文案、可取消准备。
 */
object MagnetStreamLogic {
    private val VIDEO_EXT = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v", "mpg", "mpeg", "rmvb", "rm",
    )

    /** 公开 tracker：无 tr= 的 magnet 只能靠 DHT，国内移动网常卡住。 */
    val PUBLIC_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "udp://tracker1.bt.moack.co.kr:80/announce",
        "http://tracker.openbittorrent.com:80/announce",
        "udp://explodie.org:6969/announce",
        "https://tracker.tamersunion.org:443/announce",
        "https://tracker.gbitt.info:443/announce",
        "http://tracker.bt4g.com:2095/announce",
        "https://tracker.lilithraws.org:443/announce",
        "http://tracker.opentrackr.org:1337/announce",
    )

    data class FileCand(val index: Int, val name: String, val size: Long)

    /** 选最大的视频文件；没有视频后缀则退回最大文件。 */
    fun pickPlayFile(files: List<FileCand>): FileCand? {
        if (files.isEmpty()) return null
        val videos = files.filter { VIDEO_EXT.contains(it.name.substringAfterLast('.', "").lowercase()) }
        return (videos.ifEmpty { files }).maxByOrNull { it.size }
    }

    data class Range(val start: Long, val end: Long) // inclusive end

    /** 解析 Range: bytes=START-END / START- ；非法返回 null（整文件）。 */
    fun parseRange(header: String?, total: Long): Range? {
        if (header.isNullOrBlank() || total <= 0) return null
        val m = Regex("""bytes=(\d*)-(\d*)""").find(header.trim()) ?: return null
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        val start = if (a.isEmpty()) 0L else a.toLongOrNull() ?: return null
        val end = when {
            b.isEmpty() -> total - 1
            else -> (b.toLongOrNull() ?: return null).coerceAtMost(total - 1)
        }
        if (start < 0 || start >= total || end < start) return null
        return Range(start, end)
    }

    /**
     * 给 magnet 补上缺失的公共 tracker（已有的不重复）。
     * 仅处理 magnet:? 开头；其它原样返回。
     */
    fun enrichMagnet(magnet: String, trackers: List<String> = PUBLIC_TRACKERS): String {
        val m = magnet.trim()
        if (!m.startsWith("magnet:", ignoreCase = true)) return m
        val lower = m.lowercase()
        val missing = trackers.filter { tr ->
            val enc = java.net.URLEncoder.encode(tr, Charsets.UTF_8.name())
            !lower.contains(tr.lowercase()) && !lower.contains(enc.lowercase())
        }
        if (missing.isEmpty()) return m
        val suffix = missing.joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, Charsets.UTF_8.name()) }
        return m + suffix
    }

    /** 从 magnet 抽出 infohash hex（40 或 32 位 base32 原样大写/小写保留）；失败 null。 */
    fun infoHashHex(magnet: String): String? {
        val m = Regex("""xt=urn:btih:([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE).find(magnet) ?: return null
        val raw = m.groupValues[1]
        return when (raw.length) {
            40 -> raw.lowercase()
            32 -> raw.uppercase() // base32，留给调用方；引擎侧仍可用 parseMagnetUri
            else -> null
        }
    }

    fun statusFetching(elapsedSec: Int, dhtNodes: Long = -1): String {
        val dht = if (dhtNodes >= 0) "，DHT $dhtNodes" else ""
        return if (elapsedSec < 5) "正在连接磁力网络$dht…"
        else "正在获取磁力元数据（${elapsedSec}s$dht）…"
    }

    fun statusFetchingPeers(elapsedSec: Int, dhtNodes: Long, peers: Int): String {
        val parts = mutableListOf("${elapsedSec}s")
        if (dhtNodes >= 0) parts += "DHT $dhtNodes"
        if (peers >= 0) parts += "节点 $peers"
        return "正在获取磁力元数据（" + parts.joinToString("，") + "）…"
    }

    /** .torrent 字节是否像合法种子（bencode 字典且含 info） */
    fun looksLikeTorrent(b: ByteArray?): Boolean =
        b != null && b.size > 64 && b[0] == 'd'.code.toByte() && String(b, 0, minOf(b.size, 4096), Charsets.ISO_8859_1).contains("4:info")

    fun statusTimeout(timeoutSec: Int): String =
        "获取磁力元数据超时（${timeoutSec}s）。资源可能无人做种，或当前网络访问 DHT/tracker 受限。可换 Wi‑Fi/关 VPN 重试，或用其他应用打开。"

    fun statusPeers(peers: Int, progressPct: Int): String =
        "已找到 $peers 个节点，缓冲中 $progressPct%"
}
