package com.movieshell.data.download

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
        // v0.46.0：ApiSourceStore.load 是同步文件读，IO 线程调
        val subs = ApiSourceStore.load(ctx).filter { it.enabled }
        Log.i(TAG, "订阅自动刷新开始，共 ${subs.size} 个")
        var okCount = 0
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
            }
            SubRefreshStore.recordResult(ctx, sub.url, ok)
            Log.i(TAG, "订阅刷新${if (ok) "成功" else "失败"}：${sub.name}")
            delay(2000) // 礼貌间隔，别把订阅站打爆
        }
        Log.i(TAG, "订阅自动刷新完成：$okCount/${subs.size} 成功")
        return Result.success()
    }

    companion object {
        private const val TAG = "SubRefresh"
        private const val NAME = "sub-refresh"

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
