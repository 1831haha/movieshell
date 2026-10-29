package com.movieshell.data.maintain

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil.imageLoader
import com.movieshell.data.repo.LogBus
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v0.162.0：图片缓存自动清理（修正版）。
 *
 * 只碰 image_cache，不碰 video_cache——视频是 SimpleCache，
 * 播放中删文件会坏索引，视频只走设置页手动清理（已做 release 再删）。
 * 触发后不做 LRU 逐文件删除：Coil 磁盘缓存是 DiskLruCache，
 * 外部直接删文件会坏 journal；超阈值时走 Coil 官方 clear() 一次清掉，
 * 图片下次用时重下，安全优先于“留 70%”。
 */
object CacheAutoClean {

    private const val THRESHOLD_BYTES = 1L shl 30
    private const val IMAGE_CACHE_DIR = "image_cache"

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val img = File(applicationContext.cacheDir, IMAGE_CACHE_DIR)
            val total = img.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            if (total >= THRESHOLD_BYTES) {
                // v0.247.0（F23）：diskCache 为空时原来 ?.clear() 不抛异常、
                // runCatching 照样算成功，1GB 文件还在却记“已清理”；现在空对象
                // 直接记失败走重试，不再谎报成功（沿用 F71 的失败重试口径）。
                val diskCache = applicationContext.imageLoader.diskCache
                if (diskCache == null) {
                    LogBus.w("CacheAutoClean", "图片缓存超 1GB（${total / 1024 / 1024}MB），磁盘缓存对象为空、无法清理，下次调度重试")
                    return Result.retry()
                }
                // v0.217.0（F71）：clear() 失败不再记“已清理”也不再返回
                // 成功——原来失败只记一条警告，紧接着还是“已清理”加成功，
                // 缓存一直超 1GB 不见少、系统还以为干成了，不会重试。
                val cleared = runCatching { diskCache.clear() }
                    .onFailure { LogBus.w("CacheAutoClean", "图片缓存清理失败：${it.message}") }
                    .isSuccess
                if (!cleared) {
                    LogBus.w("CacheAutoClean", "图片缓存超 1GB（${total / 1024 / 1024}MB），清理失败，下次调度重试")
                    return Result.retry()
                }
                LogBus.i("CacheAutoClean", "图片缓存超 1GB（${total / 1024 / 1024}MB），已清理")
            }
            return Result.success()
        }
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<Worker>(1, TimeUnit.DAYS)
            .setConstraints(
                // v0.275.0（T43）：原来同时要求电量不低＋设备空闲，
                // 每天一次的清理常因“不够空闲”迟迟不跑；只留电量约束，
                // 空闲约束去掉，清理本身只是超 1GB 时清一次图片缓存。
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "cache_auto_clean_image_only",
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }
}
