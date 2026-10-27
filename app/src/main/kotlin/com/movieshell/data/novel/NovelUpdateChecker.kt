package com.movieshell.data.novel

import android.content.Context
import android.util.Log
import com.movieshell.data.model.Chapter
import com.movieshell.data.model.Novel
import com.movieshell.data.repo.NovelStore
import com.movieshell.data.repo.ReadingProgressStore
import kotlinx.coroutines.CancellationException
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
            if (newCount <= 0) {
                // v0.217.0（F49）：章数没变时再比末章标题——源方同数换章／
                // 改名时只比章数永远漏报；标题变了同样刷新目录并报更新
                // （角标计 1 章，表示“内容有变化”而非新增章数；刷新后
                // latestChapterTitle 已同步，下次检查不再重复报）。
                // 老书无 latestChapterTitle 时无法比对，按无更新处理，
                // 下次章数变化的刷新会把该字段补齐。
                val remoteLast = metas.lastOrNull()?.title.orEmpty()
                val localLast = novel.latestChapterTitle.orEmpty()
                if (remoteLast.isBlank() || localLast.isBlank() || remoteLast == localLast) {
                    return@runCatching Result.NoUpdate
                }
                val chapters = metas.map {
                    Chapter(novelId = novel.id, index = it.index, title = it.title, url = it.url)
                }
                if (!NovelStore.updateSource(context, novel.id, novel.sourceKey, chapters)) {
                    return@runCatching Result.Failed
                }
                return@runCatching Result.Updated(1)
            }
            // 有更新：本地目录同步到最新（复用换源的写入逻辑，源 key 不变）
            val chapters = metas.map {
                Chapter(novelId = novel.id, index = it.index, title = it.title, url = it.url)
            }
            // v0.186.0 第 9 组：换源写入失败时不谎报角标，直接按失败算
            if (!NovelStore.updateSource(context, novel.id, novel.sourceKey, chapters)) {
                return@runCatching Result.Failed
            }
            Result.Updated(newCount)
        }.onFailure {
            // v0.247.0 第 2 轮（取消语义，外层透传）：追更检查被取消时原样抛出，
            // 不当成检查失败计入失败数、触发 Worker 重试；checkPy/checkJson 里
            // 的 searchPy/chaptersPy 已透传取消，这里不能再吞掉。
            if (it is CancellationException) throw it
            Log.w(TAG, "追更检查失败《${novel.title}》", it)
        }.getOrDefault(Result.Failed)
    }

    /**
     * v0.215.0：批量换源——在指定源里搜书名、按追更同款 pickBest 匹配、
     * 拉目录替换（不挑具体结果，批量场景逐本点选不现实）。同源直接算成功；
     * 换源后把在读章标题同步成新目录同下标的标题，书架行不挂旧源标题。
     * 调用前确保 BookSourceManager / PyBookSourceManager 已 load。
     */
    suspend fun switchToSource(context: Context, novel: Novel, targetKey: String): Boolean =
        withContext(Dispatchers.IO) {
            if (targetKey == novel.sourceKey) return@withContext true
            runCatching {
                val isPy = targetKey.startsWith(PyBookSourceManager.SOURCE_KEY_PREFIX)
                val hits = if (isPy) {
                    val rule = PyBookSourceManager.rules.value.firstOrNull {
                        PyBookSourceManager.SOURCE_KEY_PREFIX + it.name == targetKey
                    } ?: return@runCatching false
                    NovelSourceEngine.searchPy(context, rule, novel.title)
                } else {
                    val source = BookSourceManager.sources.value.firstOrNull { it.key == targetKey }
                        ?: return@runCatching false
                    NovelSourceEngine.search(source, novel.title, allowGate = true)
                }
                val best = pickBest(hits, novel.title, novel.author.orEmpty())
                    ?: return@runCatching false
                val metas = if (isPy) {
                    NovelSourceEngine.chaptersPy(context, best)
                } else {
                    val source = BookSourceManager.sources.value.firstOrNull { it.key == targetKey }
                        ?: return@runCatching false
                    NovelSourceEngine.chapters(source, best)
                }
                if (metas.isEmpty()) return@runCatching false
                val chapters = metas.map {
                    Chapter(novelId = novel.id, index = it.index, title = it.title, url = it.url)
                }
                if (!NovelStore.updateSource(context, novel.id, targetKey, chapters)) {
                    return@runCatching false
                }
                // 在读章标题跟新目录对齐（只换标题，章内偏移不动）。
                val prog = ReadingProgressStore.load(context, novel.id)
                if (prog != null) {
                    val t = chapters.getOrNull(prog.chapterIndex)?.title.orEmpty()
                    if (t.isNotBlank() && t != prog.chapterTitle) {
                        ReadingProgressStore.save(context, prog.copy(chapterTitle = t))
                    }
                }
                true
            }.onFailure {
                // v0.247.0 第 2 轮（取消语义，外层透传）：换源被取消时原样抛出，
                // 不当成换源失败计入批量失败数；批量循环的下一轮限流入口本就会
                // 抛取消，循环照常中止，不会多算一本失败。
                if (it is CancellationException) throw it
            }.getOrDefault(false)
        }

    /** 批量检查（并发），返回有更新的书 id→新章节数。 */
    suspend fun checkAll(
        context: Context,
        novels: List<Novel>,
    ): Map<String, Int> = checkAllDetailed(context, novels).updated

    /** v0.186.0 第 9 组：批量检查加失败计数——Failed 不再被悄悄丢掉，Worker 据此决定重试。 */
    data class CheckAllResult(
        val updated: Map<String, Int>,
        val failed: Int,
        val checked: Int,
        // v0.197.0：失败的书 id（书架“更新失败”页签用）
        val failedIds: Set<String> = emptySet(),
    )

    suspend fun checkAllDetailed(
        context: Context,
        novels: List<Novel>,
    ): CheckAllResult = coroutineScope {
        val results = novels.filter { it.sourceKey != "local" }.map { novel ->
            async {
                // v0.109.0：走统一限流（NovelSourceEngine 的 Semaphore(4)），
                // 之前每本书一个 async 直调 search，绕过限流齐发被反爬封 IP
                novel.id to NovelSourceEngine.withSearchThrottle { check(context, novel) }
            }
        }.awaitAll()
        CheckAllResult(
            updated = results.mapNotNull { (id, r) ->
                (r as? Result.Updated)?.let { id to it.newCount }
            }.toMap(),
            failed = results.count { (_, r) -> r is Result.Failed },
            checked = results.size,
            failedIds = results.mapNotNull { (id, r) ->
                if (r is Result.Failed) id else null
            }.toSet(),
        )
    }

    private suspend fun checkJson(novel: Novel): List<ChapterMeta>? {
        val source = BookSourceManager.sources.value.firstOrNull { it.key == novel.sourceKey }
            ?: return null
        val hits = NovelSourceEngine.search(source, novel.title)
        val best = pickBest(hits, novel.title, novel.author.orEmpty()) ?: return null
        return NovelSourceEngine.chapters(source, best).ifEmpty { null }
    }

    private suspend fun checkPy(context: Context, novel: Novel): List<ChapterMeta>? {
        val name = PyBookSourceManager.fileNameOf(novel.sourceKey) ?: return null
        val rule = PyBookSourceManager.rules.value.firstOrNull { it.name == name } ?: return null
        val hits = NovelSourceEngine.searchPy(context, rule, novel.title)
        val best = pickBest(hits, novel.title, novel.author.orEmpty()) ?: return null
        return NovelSourceEngine.chaptersPy(context, best).ifEmpty { null }
    }

    /** 选最像的一条：标题完全一致且作者对得上优先，其次标题完全一致，
     *  其次标题互相包含且作者对得上，其次标题互相包含。
     *  v0.211.0 审计修复：原来最后兜底取第一条，同名/近似书会把
     *  别本书的目录整本写进本地书且不可逆——宁可判失败也不写错书。 */
    private fun pickBest(
        hits: List<NovelSearchResult>,
        title: String,
        author: String = "",
    ): NovelSearchResult? {
        if (hits.isEmpty()) return null
        val t = title.trim()
        val a = author.trim()
        val authorOk: (NovelSearchResult) -> Boolean = { hit ->
            a.isBlank() || hit.author.isNullOrBlank() ||
                hit.author!!.contains(a) || a.contains(hit.author!!)
        }
        return hits.firstOrNull { it.title.trim() == t && authorOk(it) }
            ?: hits.firstOrNull { it.title.trim() == t }
            ?: hits.firstOrNull { (it.title.contains(t) || t.contains(it.title)) && authorOk(it) }
            ?: hits.firstOrNull { it.title.contains(t) || t.contains(it.title) }
    }
}
