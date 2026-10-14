package com.movieshell.data.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.movieshell.data.repo.FollowItem
import com.movieshell.data.repo.FollowStore
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SiteRepository
import com.movieshell.data.repo.StoragePaths
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.data.source.PyApi
import com.movieshell.data.source.QuickJsDrpyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 追剧更新检测 Worker（v0.26.0）。
 *
 * 每天 2 次（12 小时周期，仅 WiFi/有网时跑）：逐个追剧调 detail() 数集数，
 * 变多则标 NEW + 发通知。单源失败只记日志不中断整体。
 */
class FollowCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!FollowStore.isCheckEnabled(ctx)) return Result.success()
        val items = FollowStore.readSync(ctx)
        Log.i(TAG, "追剧更新检测开始，共 ${items.size} 部")
        for (item in items) {
            if (isStopped) break
            runCatching {
                val count = withContext(Dispatchers.IO) { episodeCount(ctx, item) }
                if (count != null && count > item.episodeCount) {
                    val added = FollowStore.markNew(ctx, item.id, count)
                    if (added) {
                        Notify.followUpdate(
                            ctx,
                            "《${item.name}》有更新",
                            "更新至 $count 集",
                            item.vodId,
                            subKeyOf(item),
                        )
                        Log.i(TAG, "《${item.name}》${item.episodeCount}→$count 集")
                    }
                }
            }.onFailure { Log.w(TAG, "检测失败：${item.name}", it) }
            delay(1500) // 礼貌间隔，别把源打爆
        }
        return Result.success()
    }

    private fun subKeyOf(item: FollowItem): String =
        if (item.sourceType == "sub") item.sourceKey else ""

    /** 各源取集数；取不到返回 null（不算失败）。 */
    private suspend fun episodeCount(context: Context, item: FollowItem): Int? {
        return when (item.sourceType) {
            "sub" -> {
                val site = SubSiteStore.findSite(context, item.sourceKey)
                    ?.let { (subUrl, rec) -> SubSiteStore.toSite(subUrl, rec) }
                    ?: return null
                val detail = SiteRepository().detail(site, item.vodId)
                SiteRepository().parseEpisodes(detail).size.takeIf { it > 0 }
            }
            "drpy" -> {
                val f = File(StoragePaths.jsDir(context), item.sourceKey)
                if (!f.isFile) return null
                val js = f.readText()
                // loadRule 必须主线程：抛到主线程执行后挂起等结果
                val detail = suspendCancellableCoroutine { cont ->
                    Handler(Looper.getMainLooper()).post {
                        runCatching {
                            val engine = QuickJsDrpyEngine(context)
                            engine.loadRule(js)
                            cont.resume(Result.success(engine))
                        }.onFailure { cont.resume(Result.failure(it)) }
                    }
                }.getOrNull() ?: return null
                SiteRepository().parseEpisodes(detail.detail(item.vodId)).size.takeIf { it > 0 }
            }
            "py" -> {
                PySourceManager.init(context)
                val rule = File(StoragePaths.pyDir(context), item.sourceKey)
                if (!rule.isFile) return null
                val detail = PyApi().detail(rule.absolutePath, item.vodId)
                SiteRepository().parseEpisodes(detail).size.takeIf { it > 0 }
            }
            else -> null
        }
    }

    companion object {
        private const val TAG = "FollowCheck"
        private const val NAME = "follow-check"

        /** App 启动时调用：开→排期，关→取消。 */
        fun reschedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!FollowStore.isCheckEnabled(context)) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<FollowCheckWorker>(12, TimeUnit.HOURS)
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
