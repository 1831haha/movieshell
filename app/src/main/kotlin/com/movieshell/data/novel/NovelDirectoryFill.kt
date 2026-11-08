package com.movieshell.data.novel

import android.content.Context
import com.movieshell.data.store.NovelStore
import com.movieshell.domain.model.Chapter
import com.movieshell.domain.model.ChapterMeta
import com.movieshell.domain.model.Novel
import com.movieshell.domain.model.NovelSearchResult
import com.movieshell.data.novel.NovelSourceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * v0.249.0：先入架后补目录——目录获取跑在应用级作用域，不绑找书页／详情页生命周期，
 * 离开页面继续取；同本书同时只跑一个补取任务（按 id 去重）。
 * 取消只发生在进程死亡时：状态留在 LOADING，下次进书架由恢复入口再触发一次。
 */
object NovelDirectoryFill {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    /** 补取终态通知（READY 或 FAILED）：书架订阅后重读，行状态实时跟上。 */
    private val _completed = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val completed = _completed.asSharedFlow()

    /** 入架时的精确补取：用用户点的那条搜索结果直接拉目录（不走标题重搜，不会认错同名书）。 */
    fun fillExact(context: Context, sourceKey: String, item: NovelSearchResult, novelId: String) {
        launchDeduped(context, novelId) {
            val (metas, cover) = fetchMetas(context, sourceKey, item, item.cover)
            if (metas.isEmpty()) {
                NovelStore.markDirectoryState(context, novelId, NovelStore.DIR_FAILED)
                _completed.tryEmit(novelId)
                return@launchDeduped
            }
            val chapters = metas.map {
                Chapter(
                    novelId = novelId,
                    index = it.index,
                    title = it.title,
                    content = "",
                    url = it.url,
                    // v1.1.3：段评评论链接跟着目录走——漏了这行，后台补目录的书就没有段评图标。
                    commentUrl = it.commentUrl,
                )
            }
            if (!NovelStore.updateSource(context, novelId, sourceKey, chapters, cover = cover)) {
                NovelStore.markDirectoryState(context, novelId, NovelStore.DIR_FAILED)
            }
            _completed.tryEmit(novelId)
        }
    }

    /** 书架恢复／重试入口：优先凭入架时存的 remoteId 精确拉目录（不搜标题，不会认错同名书）；
     * 老数据无 remoteId 时才走标题匹配，且同等候选多于一条时宁可失败、不自动选第一条。 */
    fun refillFromShelf(context: Context, novel: Novel) {
        launchDeduped(context, novel.id) {
            val metas: List<ChapterMeta>? = if (novel.remoteId.isNotBlank()) {
                fetchMetasByRemoteId(context, novel)
            } else {
                fetchMetasByUniqueTitle(context, novel)
            }
            if (metas.isNullOrEmpty()) {
                NovelStore.markDirectoryState(context, novel.id, NovelStore.DIR_FAILED)
            } else {
                val chapters = metas.map {
                    Chapter(
                        novelId = novel.id,
                        index = it.index,
                        title = it.title,
                        content = "",
                        url = it.url,
                        // v1.1.3：同上，书架恢复补目录也要带上评论链接。
                        commentUrl = it.commentUrl,
                    )
                }
                if (!NovelStore.updateSource(context, novel.id, novel.sourceKey, chapters)) {
                    NovelStore.markDirectoryState(context, novel.id, NovelStore.DIR_FAILED)
                }
            }
            _completed.tryEmit(novel.id)
        }
    }

    /** 精确路径：remoteId 即详情地址／规则内 id，直接拉目录。 */
    private suspend fun fetchMetasByRemoteId(context: Context, novel: Novel): List<ChapterMeta>? {
        val probe = NovelSearchResult(
            sourceKey = novel.sourceKey,
            remoteId = novel.remoteId,
            title = novel.title,
            author = novel.author,
        )
        val engine = NovelSourceEngine.novelEngineForKey(context, novel.sourceKey) ?: return null
        return engine.chapters(probe).ifEmpty { null }
    }

    /** 兜底路径（仅老数据无 remoteId）：标题精确匹配，作者优先；
     * 同等候选多于一条时返回 null——歧义不自动选，宁可失败。 */
    private suspend fun fetchMetasByUniqueTitle(context: Context, novel: Novel): List<ChapterMeta>? {
        val t = novel.title.trim()
        if (t.isEmpty()) return null
        val engine = NovelSourceEngine.novelEngineForKey(context, novel.sourceKey) ?: return null
        val hits: List<NovelSearchResult> = engine.search(t)
        val exact = hits.filter { it.title.trim() == t }
        if (exact.isEmpty()) return null
        val a = novel.author?.trim().orEmpty()
        val preferred = if (a.isBlank()) exact else exact.filter { hit ->
            val ha = hit.author?.trim().orEmpty()
            ha.isBlank() || ha.contains(a) || a.contains(ha)
        }
        val best = when {
            preferred.size == 1 -> preferred.first()
            preferred.isEmpty() && exact.size == 1 -> exact.first()
            else -> return null
        }
        return engine.chapters(best).ifEmpty { null }
    }

    /** 等目录就绪：READY 立刻 true；LOADING 轮询至终态；FAILED／超时／书不在架 false。 */
    suspend fun awaitReady(context: Context, novelId: String, timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            when (NovelStore.get(context, novelId)?.dirState) {
                NovelStore.DIR_READY -> return true
                NovelStore.DIR_FAILED -> return false
                null -> return false
                else -> {
                    if (System.currentTimeMillis() >= deadline) return false
                    delay(500)
                }
            }
        }
    }

    private fun launchDeduped(context: Context, novelId: String, block: suspend () -> Unit) {
        synchronized(jobs) {
            if (jobs[novelId]?.isActive == true) return
            val job = scope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    // 取消不是失败：LOADING 保留，下次进书架再恢复，不误标 FAILED。
                    throw e
                } catch (e: Exception) {
                    // 兜底：未预期的异常也落成 FAILED，不留永久 LOADING。
                    runCatching {
                        NovelStore.markDirectoryState(context, novelId, NovelStore.DIR_FAILED)
                    }
                    _completed.tryEmit(novelId)
                } finally {
                    synchronized(jobs) {
                        // 已有新任务接替时不动它；否则清掉已完成的旧任务。
                        if (jobs[novelId]?.isActive != true) jobs.remove(novelId)
                    }
                }
            }
            jobs[novelId] = job
        }
    }

    /** v0.220.0 原 joinShelf 内联逻辑平移：走统一限流 + 空结果重试一次。
     * 每次拉取都先拿限流许可；空结果 1 秒后重试一次，两次都空才判失败。
     * 返回目录 + 可能补到的封面。 */
    private suspend fun fetchMetas(
        context: Context,
        sourceKey: String,
        item: NovelSearchResult,
        cover: String?,
    ): Pair<List<ChapterMeta>, String?> {
        var lastMetas: List<ChapterMeta> = emptyList()
        var lastCover = cover
        repeat(2) { attempt ->
            val (ms, cv) = NovelSourceEngine.withSearchThrottle {
                val engine = NovelSourceEngine.novelEngineForKey(context, sourceKey)
                    ?: return@withSearchThrottle emptyList<ChapterMeta>() to cover
                val m = engine.chapters(item)
                // v0.200.0：搜索没封面时，用详情封面 JS 补一张（只多一次详情页请求）；
                // PY 源没有详情封面链，detailCover 回 null，原样保留旧封面。
                var c = cover
                if (c.isNullOrBlank()) {
                    c = engine.detailCover(item) ?: c
                }
                m to c
            }
            lastMetas = ms
            lastCover = cv
            if (ms.isNotEmpty()) return ms to cv
            if (attempt == 0) delay(1000)
        }
        return lastMetas to lastCover
    }
}
