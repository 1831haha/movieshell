package com.movieshell.data.novel

import android.content.Context
import android.util.Log
import com.movieshell.data.model.Chapter
import com.movieshell.data.model.Novel
import com.movieshell.data.repo.NovelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * v0.84.0：追更检查——用书原来的源按书名重搜，拉最新目录，
 * 章节数比本地多就视为有更新，顺手把本地目录刷新到最新。
 */
object NovelUpdateChecker {
    private const val TAG = "NovelUpdateChecker"

    sealed interface Result {
        /** 本地 TXT / 源已删，不检查 */
        data object Skipped : Result
        /** 没新章节 */
        data object NoUpdate : Result
        /** 有 newCount 个新章节（本地目录已同步刷新） */
        data class Updated(val newCount: Int) : Result
        /** 搜不到 / 目录拉取失败 */
        data object Failed : Result
    }

    /** 检查一本书。调用前确保 BookSourceManager / PyBookSourceManager 已 load。 */
    suspend fun check(context: Context, novel: Novel): Result = withContext(Dispatchers.IO) {
        if (novel.sourceKey == "local") return@withContext Result.Skipped
        runCatching {
            val metas: List<ChapterMeta> = if (
                novel.sourceKey.startsWith(PyBookSourceManager.SOURCE_KEY_PREFIX)
            ) {
                checkPy(context, novel) ?: return@runCatching Result.Failed
            } else {
                checkJson(novel) ?: return@runCatching Result.Failed
            }
            if (metas.isEmpty()) return@runCatching Result.Failed
            val newCount = metas.size - novel.totalChapters
            if (newCount <= 0) return@runCatching Result.NoUpdate
            // 有更新：本地目录同步到最新（复用换源的写入逻辑，源 key 不变）
            val chapters = metas.map {
                Chapter(novelId = novel.id, index = it.index, title = it.title, url = it.url)
            }
            NovelStore.updateSource(context, novel.id, novel.sourceKey, chapters)
            Result.Updated(newCount)
        }.onFailure { Log.w(TAG, "追更检查失败《${novel.title}》", it) }
            .getOrDefault(Result.Failed)
    }

    /** 批量检查（并发），返回有更新的书 id→新章节数。 */
    suspend fun checkAll(
        context: Context,
        novels: List<Novel>,
    ): Map<String, Int> = coroutineScope {
        novels.filter { it.sourceKey != "local" }.map { novel ->
            async {
                novel.id to check(context, novel)
            }
        }.awaitAll().mapNotNull { (id, r) ->
            (r as? Result.Updated)?.let { id to it.newCount }
        }.toMap()
    }

    private suspend fun checkJson(novel: Novel): List<ChapterMeta>? {
        val source = BookSourceManager.sources.value.firstOrNull { it.key == novel.sourceKey }
            ?: return null
        val hits = NovelSourceEngine.search(source, novel.title)
        val best = pickBest(hits, novel.title) ?: return null
        return NovelSourceEngine.chapters(source, best).ifEmpty { null }
    }

    private suspend fun checkPy(context: Context, novel: Novel): List<ChapterMeta>? {
        val name = PyBookSourceManager.fileNameOf(novel.sourceKey) ?: return null
        val rule = PyBookSourceManager.rules.value.firstOrNull { it.name == name } ?: return null
        val hits = NovelSourceEngine.searchPy(context, rule, novel.title)
        val best = pickBest(hits, novel.title) ?: return null
        return NovelSourceEngine.chaptersPy(context, best).ifEmpty { null }
    }

    /** 选最像的一条：标题完全一致优先，其次互相包含，其次第一条。 */
    private fun pickBest(
        hits: List<NovelSearchResult>,
        title: String,
    ): NovelSearchResult? {
        if (hits.isEmpty()) return null
        val t = title.trim()
        return hits.firstOrNull { it.title.trim() == t }
            ?: hits.firstOrNull { it.title.contains(t) || t.contains(it.title) }
            ?: hits.firstOrNull()
    }
}
