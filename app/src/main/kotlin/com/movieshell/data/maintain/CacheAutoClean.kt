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
                runCatching { applicationContext.imageLoader.diskCache?.clear() }
                    .onFailure { LogBus.w("CacheAutoClean", "图片缓存清理失败：${it.message}") }
                LogBus.i("CacheAutoClean", "图片缓存超 1GB（${total / 1024 / 1024}MB），已清理")
            }
            return Result.success()
        }
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<Worker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresDeviceIdle(true)
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
