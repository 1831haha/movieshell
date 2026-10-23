package com.movieshell.data.download

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.movieshell.data.repo.ApiSourceStore
import com.movieshell.data.repo.SubRefreshStore
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.data.repo.WarehouseSync
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * 订阅自动刷新 Worker（v0.46.0）。
 *
 * 每天 1 次（有网时跑）：逐个启用的订阅调 SubSiteStore.refresh()，
 * 成功/失败都记到 SubRefreshStore，订阅管理页标"已失效"。单订阅失败
 * 只记状态不中断整体；静默执行，不发通知。
 */
class SubRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!SubRefreshStore.isEnabled(ctx)) return Result.success()
        // v0.188.0 M28：两种运行形态——① 周期任务（无 input）：全量刷新，
        // 上次失败的源排前面优先重试；② 补跑任务（input 带 retry_urls）：
        // 只刷上轮失败的那几个源，不重刷全部。补跑失败继续指数退避，
        // 最多补跑 2 次（4 小时 → 8 小时），之后等下一个 24 小时周期，
        // 不无限追打失败的订阅站。整轮仍返回 success（单源失败不让
        // WorkManager 把整轮当失败重跑，那会连成功源一起重刷）。
        val retryUrls = inputData.getStringArray(KEY_RETRY_URLS)?.toList().orEmpty()
        val retryAttempt = inputData.getInt(KEY_RETRY_ATTEMPT, 0)
        val isRetryRun = retryUrls.isNotEmpty()
        // v0.46.0：ApiSourceStore.load 是同步文件读，IO 线程调
        val allSubs = ApiSourceStore.load(ctx).filter { it.enabled }
        val subs = if (isRetryRun) {
            allSubs.filter { it.url in retryUrls }
        } else {
            val failedUrls = allSubs
                .filter { SubRefreshStore.statusOf(ctx, it.url)?.ok == false }
                .map { it.url }.toSet()
            // 失败源优先：上轮失败的排前面先刷，成功了立即恢复正常排位。
            allSubs.sortedByDescending { it.url in failedUrls }
        }
        Log.i(TAG, "订阅自动刷新开始，共 ${subs.size} 个" + if (isRetryRun) "（补跑第 $retryAttempt 次）" else "")
        var okCount = 0
        val failed = mutableListOf<String>()
        for (sub in subs) {
            if (isStopped) break
            // v0.122.0：刷新出多仓时同步子仓订阅记录（新增/消失对齐，子仓下次自动刷新会带上）
            val cache = runCatching { SubSiteStore.refresh(ctx, sub.url) }.getOrNull()
            val ok = cache?.isSuccess == true
            if (ok) {
                cache?.getOrNull()?.let {
                    if (it.warehouses.isNotEmpty()) {
                        WarehouseSync.syncChildren(ctx, sub, it.warehouses)
                    }
                }
                okCount++
            } else {
                failed += sub.url
            }
            SubRefreshStore.recordResult(ctx, sub.url, ok)
            Log.i(TAG, "订阅刷新${if (ok) "成功" else "失败"}：${sub.name}")
            delay(2000) // 礼貌间隔，别把订阅站打爆
        }
        Log.i(TAG, "订阅自动刷新完成：$okCount/${subs.size} 成功")
        if (failed.isNotEmpty() && retryAttempt < MAX_RETRY_ATTEMPTS) {
            // 下次延迟翻倍：首轮失败 4 小时后补跑、补跑失败 8 小时后再补跑一次。
            val delayHours = if (isRetryRun) {
                inputData.getLong(KEY_RETRY_DELAY_HOURS, RETRY_DELAY_HOURS_FIRST) * 2
            } else {
                RETRY_DELAY_HOURS_FIRST
            }
            enqueueRetry(ctx, failed, retryAttempt + 1, delayHours)
            Log.i(TAG, "订阅刷新失败 ${failed.size} 个，$delayHours 小时后补跑（第 ${retryAttempt + 1} 次）")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "SubRefresh"
        private const val NAME = "sub-refresh"

        /** v0.188.0 M28：失败源补跑任务的唯一名（与 24 小时周期任务分开）。 */
        private const val RETRY_NAME = "sub-refresh-retry"
        private const val KEY_RETRY_URLS = "retry_urls"
        private const val KEY_RETRY_ATTEMPT = "retry_attempt"
        private const val KEY_RETRY_DELAY_HOURS = "retry_delay_hours"
        private const val RETRY_DELAY_HOURS_FIRST = 4L
        private const val MAX_RETRY_ATTEMPTS = 2

        /** 排一次失败源补跑：只带失败源 URL，不重刷成功源；同名任务 REPLACE。 */
        private fun enqueueRetry(
            context: Context,
            urls: List<String>,
            attempt: Int,
            delayHours: Long,
        ) {
            val req = OneTimeWorkRequestBuilder<SubRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInitialDelay(delayHours, TimeUnit.HOURS)
                .setInputData(
                    workDataOf(
                        KEY_RETRY_URLS to urls.toTypedArray(),
                        KEY_RETRY_ATTEMPT to attempt,
                        KEY_RETRY_DELAY_HOURS to delayHours,
                    ),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(RETRY_NAME, ExistingWorkPolicy.REPLACE, req)
        }

        /** App 启动/开关切换时调用：开→排期，关→取消。 */
        fun reschedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!SubRefreshStore.isEnabled(context)) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<SubRefreshWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
