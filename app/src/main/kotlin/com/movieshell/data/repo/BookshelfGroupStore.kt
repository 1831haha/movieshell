package com.movieshell.data.repo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * v0.98.0：书架自定义分组。
 * - Novel 本体不动，外部映射表 groupId -> novelIds，落 filesDir/bookshelf_groups.json，
 *   省得数据迁移。删书时顺手清理映射。
 * - 智能分组（全部/在读/养肥/已读完）是算出来的，不进这个文件。
 * - 书架偏好（列表/网格、排序、当前分组）放 bookshelf_prefs。
 */
@Serializable
data class BookGroup(
    val id: String,
    val name: String,
    val novelIds: List<String> = emptyList(),
)

@Serializable
private data class GroupFile(
    val groups: List<BookGroup> = emptyList(),
)

object BookshelfGroupStore {
    private const val FILE_NAME = "bookshelf_groups.json"
    private const val PREFS = "bookshelf_prefs"
    private const val KEY_VIEW = "view_mode" // 0=列表 1=网格
    private const val KEY_SORT = "sort_mode" // 0=最近阅读 1=更新时间 2=书名 3=阅读进度
    private const val KEY_GROUP = "selected_group" // all=全部，其余为智能/自定义分组 id
    // v0.197.0：上次追更检查失败的书 id（书架“更新失败”页签用，逗号分隔存 prefs）
    private const val KEY_FAILED = "update_failed_ids"

    const val VIEW_LIST = 0
    const val VIEW_GRID = 1
    const val SORT_RECENT = 0
    const val SORT_UPDATED = 1
    const val SORT_TITLE = 2
    const val SORT_PROGRESS = 3

    /** 智能分组 id（算出来的，不持久化）。 */
    const val GROUP_ALL = "all"
    const val GROUP_READING = "reading"
    const val GROUP_FATTEN = "fatten"
    const val GROUP_FINISHED = "finished"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * v0.186.0：书架分组读改写同一把锁；偏好单值读写是 SharedPreferences 原子操作，不进锁。
     */
    private val mutex = Mutex()

    private fun fileOf(context: Context) = java.io.File(context.filesDir, FILE_NAME)

    private fun readFile(context: Context): List<BookGroup> = runCatching {
        val f = fileOf(context)
        if (!f.exists()) return emptyList()
        json.decodeFromString(GroupFile.serializer(), f.readText()).groups
    }.onFailure {
        LogBus.w("BookGroup", "读取失败")
        // v0.170.0：读坏先保全原文件，下次写盘不再悄悄清空
        AtomicFile.preserveCorrupt(fileOf(context))
    }.getOrDefault(emptyList())

    private fun writeFile(context: Context, groups: List<BookGroup>): Boolean {
        val ok = runCatching {
            // v0.170.0：原子写盘（临时文件 + fsync + 改名），写中途被杀不留截断文件
            AtomicFile.writeText(
                fileOf(context),
                json.encodeToString(GroupFile.serializer(), GroupFile(groups)),
            )
        }.onFailure { LogBus.w("BookGroup", "写入失败: ${it.message}") }.getOrDefault(false)
        if (!ok) LogBus.w("BookGroup", "写入失败")
        return ok
    }

    suspend fun list(context: Context): List<BookGroup> = mutex.withLock {
        withContext(Dispatchers.IO) {
            readFile(context)
        }
    }

    suspend fun create(context: Context, name: String): BookGroup = mutex.withLock {
        withContext(Dispatchers.IO) {
            val n = name.trim().take(12).ifBlank { return@withContext BookGroup("", "") }
            val groups = readFile(context).toMutableList()
            val g = BookGroup(id = "g${System.currentTimeMillis()}", name = n)
            groups.add(g)
            // 写盘失败返回空分组：调用方已按 id 为空判失败并提示，不谎报已创建
            if (writeFile(context, groups)) g else BookGroup("", "")
        }
    }

    suspend fun rename(context: Context, id: String, name: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val n = name.trim().take(12).ifBlank { return@withContext }
            writeFile(context, readFile(context).map { if (it.id == id) it.copy(name = n) else it })
        }
    }

    suspend fun delete(context: Context, id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            writeFile(context, readFile(context).filterNot { it.id == id })
        }
    }

    /** 移入分组：增量添加，不踢出其他分组。 */
    suspend fun addNovels(context: Context, id: String, novelIds: Collection<String>) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (novelIds.isEmpty()) return@withContext
                writeFile(
                    context,
                    readFile(context).map { g ->
                        if (g.id == id) g.copy(novelIds = (g.novelIds + novelIds).distinct())
                        else g
                    },
                )
            }
        }

    /** 从指定分组移出。 */
    suspend fun removeNovels(context: Context, id: String, novelIds: Collection<String>) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                writeFile(
                    context,
                    readFile(context).map { g ->
                        if (g.id == id) g.copy(novelIds = g.novelIds - novelIds.toSet()) else g
                    },
                )
            }
        }

    /** 删书时调用：所有自定义分组里清掉这本书。 */
    suspend fun removeNovelEverywhere(context: Context, novelId: String) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val groups = readFile(context)
                if (groups.none { it.novelIds.contains(novelId) }) return@withContext
                writeFile(context, groups.map { it.copy(novelIds = it.novelIds - novelId) })
            }
        }

    // ---------------- 偏好 ----------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun viewMode(context: Context): Int = prefs(context).getInt(KEY_VIEW, VIEW_LIST)

    fun setViewMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_VIEW, mode).apply()
    }

    fun sortMode(context: Context): Int = prefs(context).getInt(KEY_SORT, SORT_RECENT)

    fun setSortMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_SORT, mode).apply()
    }

    fun selectedGroup(context: Context): String =
        prefs(context).getString(KEY_GROUP, GROUP_ALL) ?: GROUP_ALL

    /** v0.197.0：上次检查失败的书 id（页签筛选）；检查跑完即整体替换，不逐条攒。 */
    fun failedIds(context: Context): Set<String> =
        prefs(context).getString(KEY_FAILED, "").orEmpty()
            .split(',').filter { it.isNotBlank() }.toSet()

    fun setFailedIds(context: Context, ids: Set<String>) {
        prefs(context).edit().putString(KEY_FAILED, ids.joinToString(",")).apply()
    }

    fun setSelectedGroup(context: Context, id: String) {
        prefs(context).edit().putString(KEY_GROUP, id).apply()
    }
}
