package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** 下载任务状态 */
object DlStatus {
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val PAUSED = "PAUSED"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
}

/** 下载任务。kind：m3u8=分片下载后写本地 index.m3u8；file=直链单文件。 */
@Serializable
data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val pic: String = "",
    /** 入队时已 resolve 的可播地址 */
    val url: String = "",
    val kind: String = "file",
    val status: String = DlStatus.QUEUED,
    /** 0..1 */
    val progress: Float = 0f,
    val totalSeg: Int = 0,
    val doneSeg: Int = 0,
    val totalBytes: Long = 0L,
    val doneBytes: Long = 0L,
    /** 完成后的可播本地路径（m3u8=本地 index.m3u8；file=本地视频文件） */
    val localPath: String = "",
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 下载任务 Store（v0.26.0，单一入口）。
 *
 * 内存 StateFlow + SharedPreferences 持久化；claimNext() 原子认领下一个排队任务，
 * 供多个 DownloadQueueWorker 并发消费（并行数见 maxParallel）。
 */
object DownloadStore {

    private const val TAG = "Download"
    private const val PREFS = "download_prefs"
    private const val KEY_LIST = "download_list"
    private const val KEY_MAX_PARALLEL = "download_max_parallel"

    private val json = Json { ignoreUnknownKeys = true }

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    /** 上次落盘的进度快照（节流持久化用） */
    private val lastPersisted = mutableMapOf<String, Pair<String, Float>>()

    private var inited = false
    private val lock = Any()

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        inited = true
        val all = readAll(context).map {
            // 进程重建：上次下到一半的任务回到 PAUSED，不自动续下
            if (it.status == DlStatus.DOWNLOADING || it.status == DlStatus.QUEUED)
                it.copy(status = DlStatus.PAUSED) else it
        }
        writeAll(context, all)
        _tasks.value = all
    }

    fun get(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    suspend fun addTasks(context: Context, tasks: List<DownloadTask>) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val all = readAll(context).toMutableList()
                tasks.forEach { t ->
                    if (all.none { it.id == t.id }) all.add(0, t)
                }
                writeAll(context, all)
                _tasks.value = all
            }
        }
    }

    /** 更新任务；进度变化 <5% 且状态未变时只刷内存不落盘。 */
    fun update(context: Context, id: String, force: Boolean = false, fn: (DownloadTask) -> DownloadTask) {
        synchronized(lock) {
            val all = readAll(context).toMutableList()
            val i = all.indexOfFirst { it.id == id }
            if (i < 0) return
            val nt = fn(all[i])
            all[i] = nt
            _tasks.value = all.toList()
            val prev = lastPersisted[id]
            if (force || prev == null || prev.first != nt.status ||
                kotlin.math.abs(prev.second - nt.progress) >= 0.05f
            ) {
                lastPersisted[id] = nt.status to nt.progress
                writeAll(context, all)
            }
        }
    }

    fun setStatus(context: Context, id: String, status: String, error: String = "") {
        update(context, id, force = true) { it.copy(status = status, error = error) }
    }

    /** 原子认领：最早入队的 QUEUED → DOWNLOADING；无任务返回 null。 */
    fun claimNext(context: Context): DownloadTask? {
        synchronized(lock) {
            val all = readAll(context).toMutableList()
            val i = all.indexOfFirst { it.status == DlStatus.QUEUED }
            if (i < 0) return null
            val nt = all[i].copy(status = DlStatus.DOWNLOADING, error = "")
            all[i] = nt
            lastPersisted[nt.id] = nt.status to nt.progress
            writeAll(context, all)
            _tasks.value = all.toList()
            return nt
        }
    }

    suspend fun remove(context: Context, id: String, deleteFiles: Boolean) {
        withContext(Dispatchers.IO) {
            val task = synchronized(lock) { readAll(context).firstOrNull { it.id == id } }
            if (deleteFiles && task != null) {
                runCatching {
                    val dir = taskDir(context, task)
                    if (dir.isDirectory) dir.deleteRecursively()
                }.onFailure { Log.w(TAG, "删除下载文件失败", it) }
            }
            synchronized(lock) {
                val all = readAll(context).filterNot { it.id == id }
                lastPersisted.remove(id)
                writeAll(context, all)
                _tasks.value = all
            }
        }
    }

    suspend fun clearDone(context: Context) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val all = readAll(context).filterNot { it.status == DlStatus.DONE }
                writeAll(context, all)
                _tasks.value = all
            }
        }
    }

    fun taskDir(context: Context, task: DownloadTask): java.io.File =
        java.io.File(StoragePaths.downloadDir(context), task.id).also { it.mkdirs() }

    fun maxParallel(context: Context): Int =
        prefs(context).getInt(KEY_MAX_PARALLEL, 2).coerceIn(1, 3)

    fun setMaxParallel(context: Context, n: Int) {
        prefs(context).edit().putInt(KEY_MAX_PARALLEL, n.coerceIn(1, 3)).apply()
    }

    // ---------- 内部 ----------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readAll(context: Context): List<DownloadTask> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(DownloadTask.serializer()), raw) }
            .onFailure { Log.w(TAG, "下载列表解析失败", it) }
            .getOrDefault(emptyList())
    }

    private fun writeAll(context: Context, list: List<DownloadTask>) {
        prefs(context).edit()
            .putString(KEY_LIST, json.encodeToString(ListSerializer(DownloadTask.serializer()), list))
            .apply()
    }
}
