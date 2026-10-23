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
    /** v0.41.0：瞬时速度 B/s（worker 内存态更新，落盘节流，只用于列表展示） */
    val speedBps: Long = 0L,
    /** 完成后的可播本地路径（m3u8=本地 index.m3u8；file=本地视频文件） */
    val localPath: String = "",
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** v0.171.0：实际完成时间（毫秒）。老记录为 0，自动清理时回退到 createdAt。 */
    val completedAt: Long = 0L,
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

    /** v0.171.0：正在被 Worker 实际下载的任务 id（删除任务等 Worker 真停的依据）。 */
    private val activeIds = mutableSetOf<String>()

    @Synchronized
    fun markActive(id: String) {
        activeIds += id
    }

    @Synchronized
    fun unmarkActive(id: String) {
        activeIds -= id
    }

    @Synchronized
    fun isActive(id: String): Boolean = id in activeIds

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
        // v0.63.0：启动时自动清理 N 天前的已完成任务（含文件）
        autoClearOldDone(context)
    }

    /** v0.63.0：清理 N 天前完成的任务。days=0 表示不清理。 */
    fun autoClearOldDone(context: Context) {
        val days = DownloadSettings.autoClearDays(context)
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val old = synchronized(lock) {
            // v0.171.0：按完成时间算，不再按入队时间——8 天前入队、昨天才下完
            // 的剧以前到期即被删；老记录无完成时间时回退到入队时间
            readAll(context).filter { it.status == DlStatus.DONE &&
                (if (it.completedAt > 0) it.completedAt else it.createdAt) < cutoff }
        }
        if (old.isEmpty()) return
        old.forEach { task ->
            runCatching {
                val dir = taskDir(context, task)
                if (dir.isDirectory) dir.deleteRecursively()
            }.onFailure { Log.w(TAG, "自动清理删文件失败", it) }
        }
        synchronized(lock) {
            val ids = old.map { it.id }.toSet()
            val rest = readAll(context).filterNot { it.id in ids }
            ids.forEach { lastPersisted.remove(it) }
            writeAll(context, rest)
            _tasks.value = rest
        }
        Log.i(TAG, "自动清理了 ${old.size} 个 $days 天前的已完成任务")
    }

    /** v0.63.0：按本地路径找任务（看完自动删用）。 */
    fun findByLocalPath(localPath: String): DownloadTask? =
        _tasks.value.firstOrNull { it.localPath.isNotBlank() && it.localPath == localPath }

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
            // v0.171.0：删记录的同时删文件（此前只删记录，xiaying/Download/<id>/
            // 下的视频与分片成孤儿，且此后自动清理再也认不到它们）
            val done = synchronized(lock) { readAll(context).filter { it.status == DlStatus.DONE } }
            done.forEach { task ->
                runCatching {
                    val dir = java.io.File(StoragePaths.downloadDir(context), task.id)
                    if (dir.isDirectory) dir.deleteRecursively()
                }.onFailure { Log.w(TAG, "清理已完成文件失败", it) }
            }
            synchronized(lock) {
                val ids = done.map { it.id }.toSet()
                val all = readAll(context).filterNot { it.id in ids }
                ids.forEach { lastPersisted.remove(it) }
                writeAll(context, all)
                _tasks.value = all
            }
        }
    }

    /**
     * v0.186.0 M19：DONE 存在性扫描（唯一扫描点：下载页进入时调，启动时不重复做）。
     * localPath 为空或文件已不存在（文件管理器删除、权限切换致根目录变化）时
     * 降级为 FAILED 并写明原因，不静默失败、不删记录；返回降级条数，供下载页提示。
     * 只动 DONE 任务；在途/暂停/失败任务不动。
     */
    suspend fun verifyDoneFiles(context: Context): Int = withContext(Dispatchers.IO) {
        val missing: List<DownloadTask>
        synchronized(lock) {
            val all = readAll(context)
            // v0.187.0 H15：当前根下找不到、但另一根下还有同名文件时不算
            // 丢失（只是权限切换导致根目录暂时变了），权限恢复后仍可找回
            missing = all.filter { it.status == DlStatus.DONE &&
                (it.localPath.isBlank() ||
                    (!java.io.File(it.localPath).isFile &&
                        !StoragePaths.existsUnderOtherRoot(context, it.localPath))) }
            if (missing.isNotEmpty()) {
                val ids = missing.map { it.id }.toSet()
                val rest = all.map { t ->
                    if (t.id in ids) t.copy(status = DlStatus.FAILED, error = "本地文件已丢失") else t
                }
                writeAll(context, rest)
                _tasks.value = rest
            }
        }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "下载 DONE 扫描：${missing.size} 条本地文件已丢失，已降级为失败")
        }
        missing.size
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
            // v0.187.0 H15：落盘的是逻辑根相对路径，内存里一律拼回当前根的
            // 绝对路径；存量绝对路径同样先剥已知根再拼当前根，换权限不错位
            .map { t ->
                if (t.localPath.isBlank()) t
                else t.copy(localPath = StoragePaths.resolveStoredPath(context, t.localPath))
            }
    }

    private fun writeAll(context: Context, list: List<DownloadTask>) {
        // v0.187.0 H15：落盘前把绝对路径换算成逻辑根相对路径（如
        // Download/xxx/index.m3u8），内存态与调用方看到的仍是绝对路径
        val stored = list.map { t ->
            if (t.localPath.isBlank()) t
            else t.copy(localPath = StoragePaths.toRelativePath(context, t.localPath))
        }
        prefs(context).edit()
            .putString(KEY_LIST, json.encodeToString(ListSerializer(DownloadTask.serializer()), stored))
            .apply()
    }
}
