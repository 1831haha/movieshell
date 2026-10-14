package com.movieshell.data.download

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.movieshell.data.repo.DlStatus
import com.movieshell.data.repo.DownloadStore
import com.movieshell.data.repo.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 下载调度单入口（v0.26.0）。
 *
 * - enqueue：任务入库（QUEUED）→ pump()
 * - pump：按设置并行数起 DownloadQueueWorker（KEEP，跑完即退；有新任务再 pump）
 * - pause/resume/retry/delete：改状态位 + pump，Worker 协作式响应
 */
object DownloadManager {

    private const val TAG = "Download"
    private const val QUEUE_NAME = "dl-queue"

    /** 入队一批任务并启动消费。 */
    suspend fun enqueue(context: Context, tasks: List<DownloadTask>) {
        if (tasks.isEmpty()) return
        DownloadStore.addTasks(context, tasks)
        Log.i(TAG, "入队 ${tasks.size} 个任务")
        pump(context)
    }

    /** 确保消费协程在跑：已在跑的不重复起。 */
    fun pump(context: Context) {
        val wm = WorkManager.getInstance(context)
        val n = DownloadStore.maxParallel(context)
        repeat(n) { i ->
            val req = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
                .addTag(QUEUE_NAME)
                .build()
            // 同名 KEEP：该槽位已有未完成的 Worker 则不重复起
            wm.enqueueUniqueWork("$QUEUE_NAME-$i", ExistingWorkPolicy.KEEP, req)
        }
    }

    fun pause(context: Context, id: String) {
        val t = DownloadStore.get(id) ?: return
        if (t.status == DlStatus.DOWNLOADING || t.status == DlStatus.QUEUED) {
            DownloadStore.setStatus(context, id, DlStatus.PAUSED)
        }
    }

    fun resume(context: Context, id: String) {
        val t = DownloadStore.get(id) ?: return
        if (t.status == DlStatus.PAUSED || t.status == DlStatus.FAILED) {
            DownloadStore.setStatus(context, id, DlStatus.QUEUED)
            pump(context)
        }
    }

    /** 失败重试：保留已下进度，重新排队。 */
    fun retry(context: Context, id: String) = resume(context, id)

    /** 删除任务（含文件）。 */
    suspend fun delete(context: Context, id: String) {
        pause(context, id)
        // 给 Worker 一个协作退出的机会
        withContext(Dispatchers.IO) {
            var tries = 0
            while (DownloadStore.get(id)?.status == DlStatus.DOWNLOADING && tries < 20) {
                kotlinx.coroutines.delay(200)
                tries++
            }
            DownloadStore.remove(context, id, deleteFiles = true)
        }
    }

    /** 是否 m3u8 地址。 */
    fun isM3u8(url: String): Boolean {
        val u = url.substringBefore('?').lowercase()
        return u.contains(".m3u8")
    }
}
