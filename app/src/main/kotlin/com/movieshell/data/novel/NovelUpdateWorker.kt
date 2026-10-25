package com.movieshell.data.novel

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.movieshell.data.download.Notify
import com.movieshell.data.repo.BookshelfGroupStore
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
        // v0.186.0 第 9 组：不再恒返回 success——有书可查且全部失败时返回 retry，
        // 让 WorkManager 按退避策略补跑；有更新或无更新时才 success，且只给真更新写角标。
        val checked = NovelUpdateChecker.checkAllDetailed(context, books)
        val result = checked.updated
        result.forEach { (id, n) -> NovelUpdateStore.set(context, id, n) }
        // v0.211.0 审计修复：后台原来从不写/清失败 id，失败页签与
        // 后台结果脱节——本轮查过的书：失败的进页签、查过的从旧
        // 失败集合里摘掉，未查的书（本地书等）原样保留。
        val checkedIds = books.filter { it.sourceKey != "local" }.map { it.id }.toSet()
        val oldFailed = BookshelfGroupStore.failedIds(context)
        BookshelfGroupStore.setFailedIds(context, (oldFailed - checkedIds) + checked.failedIds)
        if (result.isNotEmpty()) {
            Notify.novelUpdate(context, result.size, result.values.sum())
        }
        if (checked.checked > 0 && result.isEmpty() && checked.failed == checked.checked) {
            Log.w(TAG, "追更全部失败（${checked.failed}/${checked.checked}），请求重试")
            return Result.retry()
        }
        if (checked.failed > 0) {
            Log.w(TAG, "追更部分失败：${checked.failed}/${checked.checked}")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "NovelUpdateWorker"
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
