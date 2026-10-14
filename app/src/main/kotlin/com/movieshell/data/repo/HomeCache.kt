package com.movieshell.data.repo

import android.content.Context
import com.movieshell.data.model.CategoryType
import com.movieshell.data.model.Vod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v0.25.0：首页数据落盘缓存（缓存先行，冷启动 200ms 内可渲染）。
 *
 * - 按数据源 key 分文件存（订阅站 key / 本地规则名），文件名做安全转义；
 * - 30 分钟内有效；过期/损坏返回 null；
 * - 存私有 filesDir/home_cache，无需存储权限，读写快。
 */
object HomeCache {
    /** 缓存有效期 30 分钟 */
    const val VALID_MS = 30L * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class Entry(
        val savedAt: Long = 0L,
        val vods: List<Vod> = emptyList(),
        val cats: List<CategoryType> = emptyList(),
    )

    /** 读缓存（附带是否新鲜）；损坏返回 null。 */
    fun load(context: Context, key: String): Pair<EntryData, Boolean>? {
        val e = read(context, key) ?: return null
        return EntryData(e.vods, e.cats) to
            (System.currentTimeMillis() - e.savedAt < VALID_MS)
    }

    data class EntryData(val vods: List<Vod>, val cats: List<CategoryType>)

    /** 存推荐列表（合并保留已有分类）。 */
    fun saveVods(context: Context, key: String, vods: List<Vod>) {
        val cur = read(context, key) ?: Entry()
        write(context, key, cur.copy(savedAt = System.currentTimeMillis(), vods = vods))
    }

    /** 存分类（合并保留已有推荐）。 */
    fun saveCats(context: Context, key: String, cats: List<CategoryType>) {
        val cur = read(context, key) ?: Entry()
        write(context, key, cur.copy(savedAt = System.currentTimeMillis(), cats = cats))
    }

    /** 清某源缓存（源被删/禁用时调用，可选）。 */
    fun clear(context: Context, key: String) {
        runCatching { file(context, key).delete() }
    }

    private fun file(context: Context, key: String): File {
        val dir = File(context.filesDir, "home_cache").also { it.mkdirs() }
        val safe = key.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(80).ifBlank { "default" }
        return File(dir, "$safe.json")
    }

    private fun read(context: Context, key: String): Entry? =
        runCatching { json.decodeFromString(Entry.serializer(), file(context, key).readText()) }
            .getOrNull()

    private fun write(context: Context, key: String, e: Entry) {
        runCatching { file(context, key).writeText(json.encodeToString(Entry.serializer(), e)) }
    }
}
