package com.movieshell.data.repo

import android.content.Context
import kotlinx.coroutines.Dispatchers
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

    private fun fileOf(context: Context) = java.io.File(context.filesDir, FILE_NAME)

    private fun readFile(context: Context): List<BookGroup> = runCatching {
        val f = fileOf(context)
        if (!f.exists()) return emptyList()
        json.decodeFromString(GroupFile.serializer(), f.readText()).groups
    }.getOrDefault(emptyList())

    private fun writeFile(context: Context, groups: List<BookGroup>) {
        runCatching {
            fileOf(context).writeText(
                json.encodeToString(GroupFile.serializer(), GroupFile(groups)),
            )
        }.onFailure { LogBus.w("BookGroup", "写入失败: ${it.message}") }
    }

    suspend fun list(context: Context): List<BookGroup> = withContext(Dispatchers.IO) {
        readFile(context)
    }

    suspend fun create(context: Context, name: String): BookGroup = withContext(Dispatchers.IO) {
        val n = name.trim().take(12).ifBlank { return@withContext BookGroup("", "") }
        val groups = readFile(context).toMutableList()
        val g = BookGroup(id = "g${System.currentTimeMillis()}", name = n)
        groups.add(g)
        writeFile(context, groups)
        g
    }

    suspend fun rename(context: Context, id: String, name: String) = withContext(Dispatchers.IO) {
        val n = name.trim().take(12).ifBlank { return@withContext }
        writeFile(context, readFile(context).map { if (it.id == id) it.copy(name = n) else it })
    }

    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        writeFile(context, readFile(context).filterNot { it.id == id })
    }

    /** 移入分组：增量添加，不踢出其他分组。 */
    suspend fun addNovels(context: Context, id: String, novelIds: Collection<String>) =
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

    /** 从指定分组移出。 */
    suspend fun removeNovels(context: Context, id: String, novelIds: Collection<String>) =
        withContext(Dispatchers.IO) {
            writeFile(
                context,
                readFile(context).map { g ->
                    if (g.id == id) g.copy(novelIds = g.novelIds - novelIds.toSet()) else g
                },
            )
        }

    /** 删书时调用：所有自定义分组里清掉这本书。 */
    suspend fun removeNovelEverywhere(context: Context, novelId: String) =
        withContext(Dispatchers.IO) {
            val groups = readFile(context)
            if (groups.none { it.novelIds.contains(novelId) }) return@withContext
            writeFile(context, groups.map { it.copy(novelIds = it.novelIds - novelId) })
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

    fun setSelectedGroup(context: Context, id: String) {
        prefs(context).edit().putString(KEY_GROUP, id).apply()
    }
}
