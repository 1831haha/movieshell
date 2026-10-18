package com.movieshell.data.source

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.movieshell.data.download.Notify
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.ProbeRecord
import com.movieshell.data.repo.ProbeReport
import com.movieshell.data.repo.SourceProbe
import com.movieshell.data.repo.SourceProbeStore
import com.movieshell.data.repo.toRecord
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * v0.90.0：站源批量检测后台化。
 *
 * 点"开始检测"后 enqueue 唯一任务，用户可切走甚至退后台；检测继续跑，
 * 跑完写 SourceProbeStore 并弹通知。探测引擎与站源页共用 SourceProbe
 *（线程池硬超时 + Semaphore(6) 限流），不复制两份。
 *
 * 进度经 setProgress 上报（done/total/label），站源页检测 tab 订阅展示。
 * 取消走 cancelUniqueWork，真取消（进行中的探活受 8 秒硬超时兜底）。
 */
class SourceProbeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val tasks = SourceProbe.buildTasks(context)
        val total = tasks.size
        val records = Collections.synchronizedList(mutableListOf<ProbeRecord>())
        val done = AtomicInteger(0)
        SourceProbe.probeAll(tasks) { task, res ->
            records += res.toRecord(task.kind, task.name, task.label)
            val d = done.incrementAndGet()
            setProgress(workDataOf("done" to d, "total" to total, "label" to task.label))
        }
        SourceProbeStore.saveReport(context, ProbeReport(System.currentTimeMillis(), records))
        val ok = records.count { it.status == "OK" }
        val slow = records.count { it.status == "SLOW" }
        val fail = records.count { it.status == "FAIL" }
        LogBus.i("Probe", "后台检测完成：正常 $ok / 缓慢 $slow / 失败 $fail")
        Notify.probeDone(context, total, ok, slow, fail)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "source_probe"

        fun start(context: Context) {
            val req = OneTimeWorkRequestBuilder<SourceProbeWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** 是否有检测任务在排队/运行（阻塞读，调用方自行放后台线程） */
        fun isRunningBlocking(context: Context): Boolean = runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME)
                .get(5, TimeUnit.SECONDS)
        }.getOrNull()?.any {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        } == true
    }
}
