package com.movieshell.data.repo

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * v0.98.0：阅读统计。书架顶部数据条的数据来源。
 * - 今日阅读时长 / 本周阅读章节数 / 连续阅读天数（打卡）。
 * - 落 filesDir/reading_stats.json，按天记秒数与章节数；断一天打卡清零。
 * - 只做轻量读写，不阻塞书架首屏。
 */
@Serializable
data class DayStat(
    val seconds: Long = 0L,
    val chapters: Int = 0,
)

@Serializable
private data class StatsFile(
    val days: Map<String, DayStat> = emptyMap(),
    val streak: Int = 0,
    val lastDay: String = "",
)

/** 书架数据条用的聚合结果。 */
data class ReadingStats(
    val todaySeconds: Long = 0L,
    val weekChapters: Int = 0,
    val streak: Int = 0,
)

object ReadingStatsStore {
    private const val FILE_NAME = "reading_stats.json"
    private const val KEEP_DAYS = 90L
    private val json = Json { ignoreUnknownKeys = true }

    // SimpleDateFormat 非线程安全，每次新建实例
    private fun dayFmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun fileOf(context: Context) = java.io.File(context.filesDir, FILE_NAME)

    private fun readFile(context: Context): StatsFile = runCatching {
        val f = fileOf(context)
        if (!f.exists()) return StatsFile()
        json.decodeFromString(StatsFile.serializer(), f.readText())
    }.getOrDefault(StatsFile())

    private fun dayKey(timeMs: Long): String = dayFmt().format(Date(timeMs))

    /** 本周一起点（00:00）。 */
    private fun weekStartMs(now: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val offset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon->0 ... Sun->6
        c.add(Calendar.DAY_OF_MONTH, -offset)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * 记录一次阅读会话。时长按天切分累加；章节数记到结束当天。
     * 打卡：lastDay 是昨天 → streak+1；是今天 → 不变；否则 → 1。
     */
    suspend fun recordSession(
        context: Context,
        startMs: Long,
        endMs: Long,
        chaptersRead: Int,
    ) = withContext(Dispatchers.IO) {
        if (endMs <= startMs) return@withContext
        val data = readFile(context)
        val days = data.days.toMutableMap()
        // 时长按天切分
        var cur = startMs
        while (cur < endMs) {
            val c = Calendar.getInstance().apply { timeInMillis = cur }
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            c.add(Calendar.DAY_OF_MONTH, 1)
            val dayEnd = minOf(c.timeInMillis, endMs)
            val key = dayKey(cur)
            val old = days[key] ?: DayStat()
            days[key] = old.copy(seconds = old.seconds + (dayEnd - cur) / 1000)
            cur = dayEnd
        }
        // 章节记到结束当天
        if (chaptersRead > 0) {
            val endKey = dayKey(endMs - 1)
            val old = days[endKey] ?: DayStat()
            days[endKey] = old.copy(chapters = old.chapters + chaptersRead)
        }
        // 打卡连续天数
        val today = dayKey(endMs)
        val yesterday = dayKey(endMs - 24 * 3600 * 1000)
        val streak = when (data.lastDay) {
            today -> data.streak
            yesterday -> data.streak + 1
            else -> 1
        }
        // 只留近期，文件不膨胀
        val cutoff = dayKey(endMs - KEEP_DAYS * 24 * 3600 * 1000)
        val pruned = days.filterKeys { it >= cutoff }
        runCatching {
            fileOf(context).writeText(
                json.encodeToString(StatsFile.serializer(), StatsFile(pruned, streak, today)),
            )
        }.onFailure { LogBus.w("ReadingStats", "写入失败: ${it.message}") }
    }

    /** 书架数据条读取：一次读文件，轻量。 */
    suspend fun loadStats(context: Context): ReadingStats = withContext(Dispatchers.IO) {
        val data = readFile(context)
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        val yesterday = dayKey(now - 24 * 3600 * 1000)
        val weekStart = weekStartMs(now)
        var weekChapters = 0
        val fmt = dayFmt()
        data.days.forEach { (k, v) ->
            val t = runCatching { fmt.parse(k)?.time }.getOrNull() ?: return@forEach
            if (t >= weekStart) weekChapters += v.chapters
        }
        // 打卡断了就显示 0，不拿旧数字忽悠
        val streak = if (data.lastDay == today || data.lastDay == yesterday) data.streak else 0
        ReadingStats(
            todaySeconds = data.days[today]?.seconds ?: 0L,
            weekChapters = weekChapters,
            streak = streak,
        )
    }
}
