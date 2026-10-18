package com.movieshell.data.novel

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.movieshell.data.download.Notify
import com.movieshell.data.repo.NovelStore
import com.movieshell.data.repo.NovelUpdateStore
import java.util.concurrent.TimeUnit

/**
 * v0.85.0：小说自动追更——每天一次，后台复用 NovelUpdateChecker 的手动检查逻辑。
 *
 * 有更新则写 NovelUpdateStore（书架自动打"＋N章"角标，走已有通道）并弹通知，
 * 点通知进书架。手动"检查更新"按钮保留，两边互不干扰。
 */
class NovelUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        BookSourceManager.load(context)
        PyBookSourceManager.load(context)
        val books = NovelStore.list(context)
        val result = NovelUpdateChecker.checkAll(context, books)
        result.forEach { (id, n) -> NovelUpdateStore.set(context, id, n) }
        if (result.isNotEmpty()) {
            Notify.novelUpdate(context, result.size, result.values.sum())
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "novel_update_daily"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<NovelUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}
