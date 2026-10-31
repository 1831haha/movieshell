package com.movieshell.player

import android.content.Context
import java.io.File

/** v0.294.26：磁力缓存（filesDir/magnet_cache）占用统计、上限与自动清理。 */
object MagnetCache {
    const val DEFAULT_CAP_GB = 2
    val CAP_CHOICES_GB = listOf(1, 2, 5, 10)
    const val MAX_AGE_MS = 3L * 24 * 3600 * 1000
    private const val PREF = "magnet_cache"
    private const val KEY_CAP = "cap_gb"

    data class Entry(val name: String, val size: Long, val mtime: Long)

    /**
     * 纯逻辑：选出要删的顶层条目。先删超过 [maxAgeMs] 的，再按最旧优先删到总量 ≤ [capBytes]；
     * [keep] 中的（正在播放）一律不删。
     */
    fun selectToDelete(entries: List<Entry>, now: Long, capBytes: Long, maxAgeMs: Long, keep: Set<String>): List<String> {
        val out = linkedSetOf<String>()
        entries.filter { it.name !in keep && now - it.mtime > maxAgeMs }.forEach { out += it.name }
        var total = entries.filter { it.name !in out }.sumOf { it.size }
        for (e in entries.filter { it.name !in keep && it.name !in out }.sortedBy { it.mtime }) {
            if (total <= capBytes) break
            out += e.name; total -= e.size
        }
        return out.toList()
    }

    fun dir(context: Context) = File(context.filesDir, "magnet_cache")

    fun capGb(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_CAP, DEFAULT_CAP_GB)

    fun setCapGb(context: Context, gb: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_CAP, gb).apply()
    }

    private fun sizeOf(f: File): Long = if (f.isFile) f.length() else f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    private fun mtimeOf(f: File): Long = if (f.isFile) f.lastModified() else f.walkBottomUp().maxOfOrNull { it.lastModified() } ?: f.lastModified()

    fun totalBytes(context: Context): Long = dir(context).takeIf { it.exists() }?.let { sizeOf(it) } ?: 0L

    fun entries(context: Context): List<Entry> =
        dir(context).listFiles()?.map { Entry(it.name, sizeOf(it), mtimeOf(it)) } ?: emptyList()

    /** 按上限/过期清理；返回删掉的条目数 */
    fun prune(context: Context, keep: Set<String>, now: Long = System.currentTimeMillis()): Int {
        val cap = capGb(context).toLong() * 1024 * 1024 * 1024
        val del = selectToDelete(entries(context), now, cap, MAX_AGE_MS, keep)
        del.forEach { runCatching { File(dir(context), it).deleteRecursively() } }
        return del.size
    }

    fun format(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format("%.0f MB", bytes / (1L shl 20).toDouble())
        bytes > 0 -> "${bytes / 1024} KB"
        else -> "0 B"
    }
}
