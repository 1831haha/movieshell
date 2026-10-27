package com.movieshell.data.download

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.movieshell.data.repo.DlStatus
import com.movieshell.data.repo.DownloadStore
import com.movieshell.data.repo.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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

    /** pump 内部 IO 域：查 worker 运行状态要走 binder，不能占调用方线程（设置页 onClick 也调 pump）。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // v0.247.0（F35）：pump 的“查状态→入队”必须原子——原来两次并发 pump 都查到
    // 未运行、都 REPLACE 入队，后一次会顶掉前一次刚入队的 worker；且查状态超时
    // 时按未运行处理，REPLACE 可能取消一个其实正在跑的 worker。
    // 现在同一把锁串行化全部 pump 调用，查不到状态时跳过本轮、不盲目 REPLACE。
    // 锁能闭合竞态的前提（复核论证）：QUEUE_NAME-i 全仓只由 pump 入队——
    // 查询时可见的 ENQUEUED/RUNNING/BLOCKED 都会跳过；查询后唯一能新出现
    // 的是已完成（REPLACE 清理无害），因为新 worker 只能来自一次入队，而入队
    // 全走这把锁；WorkManager 自身的重调度（重试、进程死后恢复）只会把已可见
    // 的运行中 worker 变回 ENQUEUED，不会凭空造出查询时不可见的运行中 worker。
    // 若将来新增入队点，必须同样持这把锁，否则上述论证失效。
    private val pumpMutex = Mutex()

    /** 入队一批任务并启动消费。 */
    suspend fun enqueue(context: Context, tasks: List<DownloadTask>) {
        if (tasks.isEmpty()) return
        DownloadStore.addTasks(context, tasks)
        Log.i(TAG, "入队 ${tasks.size} 个任务")
        pump(context)
    }

    /**
     * 确保消费协程在跑：已在跑的不重复起。
     *
     * v0.41.0 修：之前用 ExistingWorkPolicy.KEEP，但 unique 名在 worker 跑完后
     * 仍占着，KEEP 永远不再起新 worker——第一批下完后新任务就再也下不动。
     * 现在查实际运行状态，无 ENQUEUED/RUNNING/BLOCKED 才起新 worker。
     */
    fun pump(context: Context) {
        val app = context.applicationContext
        ioScope.launch {
            pumpMutex.withLock {
                val wm = WorkManager.getInstance(app)
                val n = DownloadStore.maxParallel(app)
                repeat(n) { i ->
                    val name = "$QUEUE_NAME-$i"
                    val infos = runCatching {
                        wm.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
                    }.getOrNull()
                    if (infos == null) {
                        Log.w(TAG, "查 worker 状态失败，跳过 $name（下次 pump 再试）")
                        return@repeat
                    }
                    val running = infos.any {
                        it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.BLOCKED
                    }
                    if (!running) {
                        val req = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
                            .addTag(QUEUE_NAME)
                            .build()
                        // 锁内已确认无运行中任务，REPLACE 只清理旧的已完成记录
                        wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
                    }
                }
            }
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
            // v0.171.0：等 Worker 真停（active 标记消失）再删目录，不再只看状态位——
            // pause() 后状态立刻变 PAUSED，旧条件 status==DOWNLOADING 马上退出，
            // Worker 还在写盘时目录就被删，写盘随后又把孤儿目录建回来。
            // Worker 在分片/64KB 块边界查 alive()，暂停后最多一个块即停；
            // 30 秒仍未停则继续删（Worker 下次查 alive 时即停，不再续写新分片）
            while (DownloadStore.isActive(id) && tries < 150) {
                kotlinx.coroutines.delay(200)
                tries++
            }
            if (DownloadStore.isActive(id)) {
                Log.w(TAG, "删除等待 Worker 停止超时，仍继续删记录与文件：$id")
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
