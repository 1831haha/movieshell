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
        val vodsSavedAt: Long = 0L,
        val catsSavedAt: Long = 0L,
        val vods: List<Vod> = emptyList(),
        val cats: List<CategoryType> = emptyList(),
    )

    /**
     * 读缓存（附带各自的新鲜度）；损坏返回 null。
     * v0.217.0（F7）：推荐和分类各记自己的保存时间，不再共用一个 savedAt——
     * 分类刚刷新完，半小时前的旧推荐不能再被判成新鲜。
     * （老文件没有这两个字段，解码默认 0，即判过期，重拉一次即可。）
     */
    fun load(context: Context, key: String): LoadResult? {
        val e = read(context, key) ?: return null
        val now = System.currentTimeMillis()
        return LoadResult(
            data = EntryData(e.vods, e.cats),
            vodsFresh = now - e.vodsSavedAt < VALID_MS,
            catsFresh = now - e.catsSavedAt < VALID_MS,
        )
    }

    data class EntryData(val vods: List<Vod>, val cats: List<CategoryType>)
    data class LoadResult(val data: EntryData, val vodsFresh: Boolean, val catsFresh: Boolean)

    /** 存推荐列表（合并保留已有分类；只刷推荐自己的保存时间）。 */
    fun saveVods(context: Context, key: String, vods: List<Vod>) {
        val cur = read(context, key) ?: Entry()
        write(context, key, cur.copy(vodsSavedAt = System.currentTimeMillis(), vods = vods))
    }

    /** 存分类（合并保留已有推荐；只刷分类自己的保存时间）。 */
    fun saveCats(context: Context, key: String, cats: List<CategoryType>) {
        val cur = read(context, key) ?: Entry()
        write(context, key, cur.copy(catsSavedAt = System.currentTimeMillis(), cats = cats))
    }

    // v0.52.0：分类视频列表缓存——详情页返回时分类内容秒回，不用白屏等网络。
    // 按 key+typeId 分文件存（一个分类一个文件，互不干扰）。

    @Serializable
    private data class CatEntry(
        val savedAt: Long = 0L,
        val vods: List<Vod> = emptyList(),
    )

    /** 存某分类的视频列表。 */
    fun saveCategoryVods(context: Context, key: String, typeId: String, vods: List<Vod>) {
        val e = CatEntry(savedAt = System.currentTimeMillis(), vods = vods)
        runCatching {
            // v0.170.0：原子写盘（缓存可再生，读坏不保全，只防写一半）
            AtomicFile.writeText(catFile(context, key, typeId), json.encodeToString(CatEntry.serializer(), e))
        }
    }

    /** 读某分类的视频列表（附带是否新鲜）；没有/损坏返回 null。 */
    fun loadCategoryVods(context: Context, key: String, typeId: String): Pair<List<Vod>, Boolean>? {
        val e = runCatching {
            json.decodeFromString(CatEntry.serializer(), catFile(context, key, typeId).readText())
        }.getOrNull() ?: return null
        if (e.vods.isEmpty()) return null
        return e.vods to (System.currentTimeMillis() - e.savedAt < VALID_MS)
    }

    private fun catFile(context: Context, key: String, typeId: String): File {
        val dir = File(context.filesDir, "home_cache").also { it.mkdirs() }
        val safe = safeKey(key)
        val safeTid = typeId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(40).ifBlank { "0" }
        return File(dir, "${safe}_cat_${safeTid}.json")
    }

    /**
     * v0.217.0（F6）：分类文件名前缀的唯一算法——clear() 必须复用它推导前缀，
     * 不许另写一个截断长度（之前 clear 用 take(80)、catFile 用 take(60)，
     * 长 key 的分类文件删不掉，删源重装会读到旧分类缓存）。
     * 主文件 file() 的 take(80) 是另一套名字，不动它（动了会丢弃老主文件）。
     */
    private fun safeKey(key: String): String =
        key.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(60).ifBlank { "default" }

    /** 清某源缓存（源被删/禁用时调用，可选）。 */
    fun clear(context: Context, key: String) {
        runCatching {
            val dir = File(context.filesDir, "home_cache")
            val safe = safeKey(key)
            file(context, key).delete()
            // v0.215.0：原来只删主文件，分类缓存（${safe}_cat_*.json）全留成孤儿；
            // 删源后重装同名源会先读到旧分类缓存。主文件与分类文件一起清。
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith("${safe}_cat_")) f.delete()
            }
        }
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
        runCatching {
            // v0.170.0：原子写盘（缓存可再生，读坏不保全，只防写一半）
            AtomicFile.writeText(file(context, key), json.encodeToString(Entry.serializer(), e))
        }
    }
}
