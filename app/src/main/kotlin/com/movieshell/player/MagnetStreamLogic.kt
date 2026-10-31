package com.movieshell.player

/**
 * v0.294.24：磁力转播纯逻辑（无 Android / libtorrent 依赖，单测覆盖）。
 */
object MagnetStreamLogic {
    private val VIDEO_EXT = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v", "mpg", "mpeg", "rmvb", "rm",
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

    fun statusFetching(elapsedSec: Int): String =
        if (elapsedSec < 5) "正在连接磁力网络…" else "正在获取磁力元数据（${elapsedSec}s）…"

    fun statusPeers(peers: Int, progressPct: Int): String =
        "已找到 $peers 个节点，缓冲中 $progressPct%"
}
