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
import com.movieshell.data.repo.ExecutableTrust
import com.movieshell.data.repo.FollowItem
import com.movieshell.data.repo.FollowStore
import com.movieshell.data.repo.PhpSourceManager
import com.movieshell.data.repo.PySourceManager
import com.movieshell.data.repo.SiteRepository
import com.movieshell.data.repo.SpiderEngine
import com.movieshell.data.repo.StoragePaths
import com.movieshell.data.repo.SubSiteEngines
import com.movieshell.data.repo.SubSiteStore
import com.movieshell.data.source.PyApi
import com.movieshell.data.source.V8DrpyEngine
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
        // B3：Worker 进程里 Application 已跑过 XiaYingApp.onCreate，
        // 这里再显式 init 一次作双保险（三个 init 都是幂等的），
        // 防止将来 Application 初始化顺序变动又把 Worker 进程漏掉。
        SubSiteEngines.init(ctx)
        SpiderEngine.init(ctx)
        PhpSourceManager.init(ctx)
        if (!FollowStore.isCheckEnabled(ctx)) return Result.success()
        val items = FollowStore.readSync(ctx)
        Log.i(TAG, "追剧更新检测开始，共 ${items.size} 部")
        var failed = 0
        val failedNames = mutableListOf<String>()
        for (item in items) {
            if (isStopped) break
            // v0.87.0：已完结的不再检查
            if (item.finished) continue
            // B3：检测失败与没更新分开——失败只记失败统计，不推进
            // 完结计时、不标已完结；没更新才走原来的 7 天完结逻辑。
            val outcome = runCatching {
                withContext(Dispatchers.IO) { episodeOutcome(ctx, item) }
            }.getOrElse { CheckOutcome.Failed(it.message ?: it::class.java.simpleName) }
            when (outcome) {
                is CheckOutcome.Failed -> {
                    failed++
                    failedNames.add(item.name)
                    Log.w(TAG, "检测失败（不是没更新）：${item.name}：${outcome.reason}")
                }
                is CheckOutcome.NoData -> {
                    Log.i(TAG, "检测无数据（源文件缺失或详情为空）：${item.name}")
                }
                is CheckOutcome.Count -> {
                    val count = outcome.count
                    if (count > item.episodeCount) {
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
                    } else {
                        // v0.87.0：集数连续 7 天不变，视为已完结。
                        // v0.188.0 M24：老数据无计时起点时，本次即把起点写回 FollowStore，
                        // 不再每次以“现在”重算导致差值恒为 0；取不到集数（count 为 null）
                        // 说明本次根本没验到“不变”，不推进完结计时。
                        val existing = item.lastEpisodeChangeAt.takeIf { it > 0 }
                        val base = if (existing != null) {
                            existing
                        } else {
                            val now = System.currentTimeMillis()
                            FollowStore.backfillEpisodeChangeBase(ctx, item.id, now)
                            now
                        }
                        if (System.currentTimeMillis() - base > FINISH_AFTER_MS) {
                            FollowStore.markFinished(ctx, item.id)
                            Log.i(TAG, "《${item.name}》7 天无更新，标为已完结")
                        }
                    }
                }
            }
            delay(1500) // 礼貌间隔，别把源打爆
        }
        // B3：失败统计落私有 prefs（不是日志里一句就没了），下次检测
        // 覆盖；失败数大于 0 时在日志里点名，绝不把失败说成没更新。
        runCatching {
            ctx.getSharedPreferences(STATS_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
                .putInt(KEY_FAILED_COUNT, failed)
                .putString(KEY_FAILED_NAMES, failedNames.joinToString("、"))
                .apply()
        }
        if (failed > 0) {
            Log.w(TAG, "追剧更新检测完成：$failed 部失败（不是没更新）：${failedNames.joinToString("、")}")
        } else {
            Log.i(TAG, "追剧更新检测完成：无失败")
        }
        return Result.success()
    }

    /** B3：检测结果三态，调用方不得把 Failed 当成没更新。 */
    private sealed interface CheckOutcome {
        data class Count(val count: Int) : CheckOutcome
        data object NoData : CheckOutcome
        data class Failed(val reason: String) : CheckOutcome
    }

    private fun subKeyOf(item: FollowItem): String =
        if (item.sourceType == "sub") item.sourceKey else ""

    /** 各源取集数；取不到返回 NoData，执行失败返回 Failed（不抛到外层当空）。 */
    private suspend fun episodeOutcome(context: Context, item: FollowItem): CheckOutcome {
        return when (item.sourceType) {
            "sub" -> {
                val site = SubSiteStore.findSite(context, item.sourceKey)
                    ?.let { (subUrl, rec) -> SubSiteStore.toSite(subUrl, rec) }
                    ?: return CheckOutcome.NoData
                val repo = SiteRepository()
                val detail = repo.detail(site, item.vodId)
                val size = repo.parseEpisodes(detail).size
                if (size > 0) {
                    CheckOutcome.Count(size)
                } else {
                    // SiteRepository.detail 失败时按站记了原因，空详情且有
                    // 原因 = 执行失败；无原因 = 源确实没数据。
                    val reason = repo.lastFailureReason(site)
                    if (reason != null) CheckOutcome.Failed(reason) else CheckOutcome.NoData
                }
            }
            "drpy" -> {
                // S2：追剧检测是后台执行，读私有可信副本，不读公开文件。
                val publicFile = File(StoragePaths.jsDir(context), item.sourceKey)
                if (!publicFile.isFile) return CheckOutcome.NoData
                val trusted = ExecutableTrust.trustedFile(
                    context.filesDir,
                    publicFile,
                    "js:${item.sourceKey}",
                    allowRetrust = false,
                ) ?: return CheckOutcome.Failed("drpy 规则信任校验失败")
                val js = trusted.readText()
                // v0.113.0：V8 isolate 线程安全，loadRule 无线程亲和要求；维持主线程派发无妨
                val engine = suspendCancellableCoroutine<V8DrpyEngine?> { cont ->
                    Handler(Looper.getMainLooper()).post {
                        try {
                            val e = V8DrpyEngine(context)
                            e.loadRule(js)
                            cont.resume(e)
                        } catch (e: Throwable) {
                            cont.resume(null)
                        }
                    }
                } ?: return CheckOutcome.Failed("V8 引擎创建失败")
                try {
                    val size = SiteRepository().parseEpisodes(engine.detail(item.vodId)).size
                    if (size > 0) {
                        CheckOutcome.Count(size)
                    } else {
                        val reason = engine.lastError
                        if (reason != null) CheckOutcome.Failed(reason) else CheckOutcome.NoData
                    }
                } finally {
                    // v0.113.0：追剧检查用完即释放（release 即时终止 isolate，任意线程可调）
                    engine.release()
                }
            }
            "py" -> {
                PySourceManager.init(context)
                // S2：同 drpy，后台执行读私有可信副本。
                val publicFile = File(StoragePaths.pyDir(context), item.sourceKey)
                if (!publicFile.isFile) return CheckOutcome.NoData
                val trusted = ExecutableTrust.trustedFile(
                    context.filesDir,
                    publicFile,
                    "py:${item.sourceKey}",
                    allowRetrust = false,
                ) ?: return CheckOutcome.Failed("PY 规则信任校验失败")
                val detail = runCatching {
                    PyApi().detail(trusted.absolutePath, item.vodId)
                }.getOrElse { return CheckOutcome.Failed(it.message ?: it::class.java.simpleName) }
                val size = SiteRepository().parseEpisodes(detail).size
                if (size > 0) CheckOutcome.Count(size) else CheckOutcome.NoData
            }
            else -> CheckOutcome.NoData
        }
    }

    companion object {
        private const val TAG = "FollowCheck"
        private const val NAME = "follow-check"
        /** v0.87.0：集数连续 7 天不变视为已完结 */
        private const val FINISH_AFTER_MS = 7L * 24 * 60 * 60 * 1000
        /** B3：上次检测的失败统计（私有 prefs，只供日志页/排障查，不进 UI）。 */
        private const val STATS_PREFS = "follow_check_stats"
        private const val KEY_LAST_CHECK_AT = "last_check_at"
        private const val KEY_FAILED_COUNT = "failed_count"
        private const val KEY_FAILED_NAMES = "failed_names"

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
