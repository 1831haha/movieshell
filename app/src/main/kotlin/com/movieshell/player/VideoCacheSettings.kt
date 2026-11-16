package com.movieshell.player

import android.content.Context
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import com.movieshell.core.log.LogBus

/**
 * v1.2.14：第 6 项——视频磁盘缓存（SimpleCache）上限与保留时长。
 *
 * 上限默认 500MB（与 v0.100.0 写死值一致，老用户升级不丢缓存）；
 * 保留时长默认 30 天（0 = 不限，只受上限约束）。
 * 两项都只记 SharedPreferences，真正生效在 SimpleCache 重建时——
 * 驱逐器在建缓存时定死，播放中途重建会丢正在播的分片，所以
 * PlayerManager 在下次起播时按新设置重建缓存，不用重启 App。
 */
object VideoCacheSettings {

    const val DEFAULT_CAP_MB = 500
    val CAP_CHOICES_MB = listOf(100, 200, 500, 1000, 2000)

    const val DEFAULT_MAX_AGE_DAYS = 30
    const val AGE_UNLIMITED_DAYS = 0
    val MAX_AGE_CHOICES_DAYS = listOf(7, 14, 30, 90, AGE_UNLIMITED_DAYS)

    private const val PREF = "video_cache"
    private const val KEY_CAP_MB = "cap_mb"
    private const val KEY_MAX_AGE_DAYS = "max_age_days"

    /** 纯逻辑：MB 档位换算成字节（小于 1MB 的非法值按 1MB 算）。 */
    fun capBytes(capMb: Int): Long = capMb.coerceAtLeast(1).toLong() * 1024 * 1024

    /** 纯逻辑：保留天数换算成毫秒；0 或负数 = 不限，返回 null。 */
    fun maxAgeMs(days: Int): Long? =
        if (days <= 0) null else days.toLong() * 24 * 3600 * 1000

    /** 纯逻辑：档位循环切换，到末尾回绕到首档；当前值不在档位里时从首档开始。 */
    fun nextInCycle(choices: List<Int>, current: Int): Int {
        if (choices.isEmpty()) return current
        return choices[(choices.indexOf(current) + 1).mod(choices.size)]
    }

    /**
     * 纯逻辑：该分片是否已超保留期。
     * lastTouchMs 小于等于 0（含没记时间的分片）不算过期——
     * 没时间戳的分片宁可多留，不误删。
     */
    fun isStale(lastTouchMs: Long, now: Long, maxAgeMs: Long): Boolean =
        lastTouchMs > 0 && now - lastTouchMs > maxAgeMs

    /** 纯逻辑：从一列条目里挑出超期的；maxAgeMs 为 null（不限）时一个不挑。 */
    fun <T> selectStale(items: List<T>, lastTouchOf: (T) -> Long, now: Long, maxAgeMs: Long?): List<T> {
        if (maxAgeMs == null) return emptyList()
        return items.filter { isStale(lastTouchOf(it), now, maxAgeMs) }
    }

    fun formatCapMb(mb: Int): String =
        if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else "$mb MB"

    fun formatAge(days: Int): String = if (days <= 0) "不限" else "$days 天"

    fun capMb(context: Context): Int {
        val stored = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(KEY_CAP_MB, DEFAULT_CAP_MB)
        return if (stored in CAP_CHOICES_MB) stored else DEFAULT_CAP_MB
    }

    fun setCapMb(context: Context, mb: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CAP_MB, mb).apply()
    }

    fun maxAgeDays(context: Context): Int {
        val stored = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_AGE_DAYS, DEFAULT_MAX_AGE_DAYS)
        return if (stored in MAX_AGE_CHOICES_DAYS) stored else DEFAULT_MAX_AGE_DAYS
    }

    fun setMaxAgeDays(context: Context, days: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MAX_AGE_DAYS, days).apply()
    }
}

/**
 * v1.2.14：第 6 项——上限（LRU）加保留时长双约束的缓存驱逐器。
 *
 * 尺寸走 LeastRecentlyUsedCacheEvictor 原逻辑；超期分片在两处清掉：
 * 建缓存完成时（清上次遗留的旧分片），以及每次写进新分片时。
 * 清理走 Cache.removeSpan 官方接口，不直接删文件，不坏 SimpleCache 索引。
 * 正在读的分片读前刚被触摸过，时间戳是新的，不会进清理名单。
 */
class VideoCacheEvictor(maxBytes: Long, private val maxAgeMs: Long?) : CacheEvictor {

    private val lru = LeastRecentlyUsedCacheEvictor(maxBytes)

    /**
     * onCacheInitialized 没有缓存参数，这里存引用备用。
     * SimpleCache 1.4.1 建缓存时先对已有分片逐个调 onSpanAdded（带缓存引用），
     * 再调 onCacheInitialized——所以初始化回调时引用已就绪；
     * 空缓存没分片可清，引用为空也无妨。
     */
    private var cacheRef: Cache? = null

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {
        sweepStale()
    }

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        cacheRef = cache
        lru.onStartFile(cache, key, position, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        cacheRef = cache
        // 先交给 LRU 记新分片，再清超期分片：清掉的分片走 onSpanRemoved，
        // LRU 内部的已用字节随之扣减；若刚记的分片本身已超期，清掉时同样
        // 走通知，尺寸账不错乱。顺序不能反——先清后记会让 LRU 登记一个
        // 已被删掉的分片，之后驱逐它时删不动、账也扣不掉，会死循环。
        lru.onSpanAdded(cache, span)
        sweepStale()
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        lru.onSpanRemoved(cache, span)
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        cacheRef = cache
        lru.onSpanTouched(cache, oldSpan, newSpan)
    }

    /** 清掉所有超保留期的分片；先收集再逐个删，不在遍历里改集合。 */
    private fun sweepStale() {
        val cache = cacheRef ?: return
        val maxAge = maxAgeMs ?: return
        val now = System.currentTimeMillis()
        val all = mutableListOf<CacheSpan>()
        for (key in cache.keys) {
            all += cache.getCachedSpans(key)
        }
        val stale = VideoCacheSettings.selectStale(all, { it.lastTouchTimestamp }, now, maxAge)
        if (stale.isEmpty()) return
        for (span in stale) {
            runCatching { cache.removeSpan(span) }
                .onFailure { LogBus.w("Player", "视频缓存清超期分片失败：${it.message}") }
        }
        LogBus.i("Player", "视频缓存清掉 ${stale.size} 个超期分片")
    }
}
