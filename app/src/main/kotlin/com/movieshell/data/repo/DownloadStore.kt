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
    // v0.247.0（DS11）：206 错位后删断点失败时置位——任务目录的文件操作已不可信，
    // 标记存在任务记录（私有存储）里，不依赖目录操作成败；置位后跳过一切认领、
    // 从头下载，直到某次完整下载成功才清除。老记录无此字段回落 false。
    val resumeBlocked: Boolean = false,
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

    // v0.246.0：任务列表修改统一基准——内存 _tasks.value 为准。
    // 原来各入口各自 readAll，update 节流没落盘的进度会被
    // claimNext/addTasks/remove 等从盘读回的旧值覆盖。调用方须已持 lock。
    // v0.246.0 三轮（GPT 审）：用 inited 区分“未初始化”与“已初始化但空列表”——
    // 原来用 isNotEmpty 判断，空列表时每次都回退读盘，写盘失败或外部写入时
    // 可能重新引入旧数据；且本应用是单进程，跨进程场景不作兼容承诺。
    // v0.246.0 四轮（GPT 审）：初始化屏障——原来 init 放后台协程后，
    // Worker 可能先认领（QUEUED→DOWNLOADING），init 后跑又把状态覆回 PAUSED，
    // 锁只保证临界区串行、不保证时序。改所有入口首次调用先 ensure init：
    // 谁先拿到锁谁把 init 做完（幂等），认领/修改只在 init 完成后发生。
    // init 内 autoClear/sweep 也在同一把锁内（可重入），不存在“init 半截、认领插队”。
    private fun baseList(context: Context): MutableList<DownloadTask> {
        if (!inited) init(context)
        return _tasks.value.toMutableList()
    }

    // v0.246.0：init 改走 lock——原来 init 用 @Synchronized（this 锁），
    // 其他修改入口用 lock，两把锁不一致，init 与修改可能交错。
    fun init(context: Context) {
        synchronized(lock) {
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
            // v0.188.0：启动时扫一次无记录的 Download/<id>/ 孤儿目录（L25）——
            // 删文件与删记录同块仍防不住“删记录后、删文件前进程被杀”的窗口，
            // 启动扫是第二道：无记录的任务目录只可能是残留，直接清掉。
            sweepOrphanDirs(context)
        }
    }

    /** v0.188.0：删 Download/ 下无任务记录的子目录（只删目录，不碰文件）。 */
    private fun sweepOrphanDirs(context: Context) {
        val ids = synchronized(lock) { baseList(context).map { it.id }.toSet() }
        val root = StoragePaths.downloadDir(context)
        val orphans = root.listFiles { f -> f.isDirectory && f.name !in ids }.orEmpty()
        if (orphans.isEmpty()) return
        var n = 0
        orphans.forEach { dir ->
            runCatching { if (dir.deleteRecursively()) n++ }
                .onFailure { Log.w(TAG, "孤儿下载目录清理失败", it) }
        }
        if (n > 0) Log.i(TAG, "启动清理了 $n 个无记录的下载孤儿目录")
    }

    /** v0.63.0：清理 N 天前完成的任务。days=0 表示不清理。 */
    fun autoClearOldDone(context: Context) {
        val days = DownloadSettings.autoClearDays(context)
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val old = synchronized(lock) {
            // v0.171.0：按完成时间算，不再按入队时间——8 天前入队、昨天才下完
            // 的剧以前到期即被删；老记录无完成时间时回退到入队时间
            baseList(context).filter { it.status == DlStatus.DONE &&
                (if (it.completedAt > 0) it.completedAt else it.createdAt) < cutoff }
        }
        if (old.isEmpty()) return
        // v0.188.0：删文件与删记录放同一 synchronized 块（L25），中途崩溃
        // 不再出现“文件已删记录还在”或反过来的跨步骤窗口。
        synchronized(lock) {
            val ids = old.map { it.id }.toSet()
            old.forEach { task ->
                runCatching {
                    val dir = java.io.File(StoragePaths.downloadDir(context), task.id)
                    if (dir.isDirectory) dir.deleteRecursively()
                }.onFailure { Log.w(TAG, "自动清理删文件失败", it) }
            }
            val rest = baseList(context).filterNot { it.id in ids }
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
                val all = baseList(context)
                tasks.forEach { t ->
                    if (all.none { it.id == t.id }) all.add(0, t)
                }
                writeAll(context, all)
                _tasks.value = all
            }
        }
    }

    /** 更新任务；进度变化 <5% 且状态未变时只刷内存不落盘。
     * v0.246.0：基准改内存 _tasks.value——原来每次 readAll 从磁盘读，
     * 节流没落盘的小进度（<5%）会被磁盘旧值覆盖，重启后进度回退甚至卡 0%。
     * 内存里找不到时才回退读盘一次（init 未跑或跨进程场景）。 */
    fun update(context: Context, id: String, force: Boolean = false, fn: (DownloadTask) -> DownloadTask) {
        synchronized(lock) {
            val all = baseList(context)
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

    /** 原子认领：最早入队的 QUEUED → DOWNLOADING；无任务返回 null。
     * v0.246.0：基准改内存——原来 readAll 从盘读，update 节流没落盘的进度
     * 会被认领写回的旧值覆盖。 */
    fun claimNext(context: Context): DownloadTask? {
        synchronized(lock) {
            val all = baseList(context)
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
            // v0.188.0：删文件与删记录放同一 synchronized 块（L25）
            synchronized(lock) {
                if (deleteFiles) {
                    val task = baseList(context).firstOrNull { it.id == id }
                    if (task != null) {
                        runCatching {
                            val dir = java.io.File(StoragePaths.downloadDir(context), task.id)
                            if (dir.isDirectory) dir.deleteRecursively()
                        }.onFailure { Log.w(TAG, "删除下载文件失败", it) }
                    }
                }
                val all = baseList(context).filterNot { it.id == id }
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
            // v0.188.0：删文件与删记录放同一 synchronized 块（L25）
            synchronized(lock) {
                val done = baseList(context).filter { it.status == DlStatus.DONE }
                val ids = done.map { it.id }.toSet()
                done.forEach { task ->
                    runCatching {
                        val dir = java.io.File(StoragePaths.downloadDir(context), task.id)
                        if (dir.isDirectory) dir.deleteRecursively()
                    }.onFailure { Log.w(TAG, "清理已完成文件失败", it) }
                }
                val all = baseList(context).filterNot { it.id in ids }
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
            val all = baseList(context)
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

    // v0.247.0（DS11 复核）：同步确认式更新——与 update 相同，但写盘用
    // commit()（同步落盘、返回是否成功），专供隔离标记这类“丢了会跨进程
    // 失守”的小字段。返回 true 当且仅当记录已确认落盘；任务不存在
    // 或写盘失败返回 false。调用方在 IO 线程用（commit 会阻塞），
    // 内存态无论写盘成败都会更新（本进程内重试仍读到新值）。
    // 保证范围：应用进程重启场景（进程退出/被杀后重进仍可读到），
    // 不是断电或底层存储故障下的绝对保证。
    fun updateSync(context: Context, id: String, fn: (DownloadTask) -> DownloadTask): Boolean {
        synchronized(lock) {
            val all = baseList(context)
            val i = all.indexOfFirst { it.id == id }
            if (i < 0) return false
            val nt = fn(all[i])
            all[i] = nt
            _tasks.value = all.toList()
            lastPersisted[id] = nt.status to nt.progress
            return writeAllSync(context, all)
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

    // 与 writeAll 同内容，改走 commit() 同步落盘并返回结果。
    private fun writeAllSync(context: Context, list: List<DownloadTask>): Boolean {
        val stored = list.map { t ->
            if (t.localPath.isBlank()) t
            else t.copy(localPath = StoragePaths.toRelativePath(context, t.localPath))
        }
        return prefs(context).edit()
            .putString(KEY_LIST, json.encodeToString(ListSerializer(DownloadTask.serializer()), stored))
            .commit()
    }
}
