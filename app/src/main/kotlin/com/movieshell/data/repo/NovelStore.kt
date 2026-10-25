package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.model.Chapter
import com.movieshell.data.model.Novel
import com.movieshell.data.model.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v0.35.0 小说书架：落在 filesDir/novels/ 下。
 * - index.json：全部 Novel 元信息（数组）
 * - {id}.json：该书的章节列表
 *
 * suspend + Dispatchers.IO，异常吞掉打日志。
 */
object NovelStore {

    private const val TAG = "NovelStore"
    private const val DIR_NAME = "novels"
    private const val INDEX_NAME = "index.json"

    private val json = Json { ignoreUnknownKeys = true }

    // v0.194.0：索引写锁——index.json 是所有书共享的，快速连点多本书时
    // 两个 add 并发读-改-写，后写的盖掉先写的（用户实锤：多本只剩一本）。
    // add/remove/updateSource 三处写索引共用同一把锁，读侧不加锁。
    private val indexMutex = Mutex()

    /**
     * v0.194.0：站内导航标题——有些规则把目录页底部的站内导航
     * （首页/电脑版/书架）也当章节抓回来，目录里多出这几行（真机实锤）。
     * 规则侧修是正道，但壳子在这里兜底：标题 exactly 是导航词的章直接丢掉。
     * 章节正经标题不可能就叫这几个字，误杀风险可忽略。
     */
    private val NAV_TITLES = setOf(
        "首页", "电脑版", "书架", "上一页", "下一页", "上一章", "下一章",
        "返回首页", "返回", "手机版", "电脑端", "触屏版", "目录",
    )

    fun isNavChapterTitle(title: String): Boolean = title.trim() in NAV_TITLES

    private fun dirOf(context: Context): File = File(context.filesDir, DIR_NAME).also { it.mkdirs() }
    private fun indexFile(context: Context): File = File(dirOf(context), INDEX_NAME)
    private fun chaptersFile(context: Context, novelId: String): File =
        File(dirOf(context), "$novelId.json")

    private fun readIndex(context: Context): List<Novel> {
        val f = indexFile(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Novel.serializer()), f.readText())
        }.onFailure {
            Log.w(TAG, "书架索引读取失败", it)
            // v0.170.0：读坏先保全原文件，下次写盘不再悄悄清空
            AtomicFile.preserveCorrupt(f)
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(context: Context, list: List<Novel>) {
        runCatching {
            // v0.170.0：原子写盘
            AtomicFile.writeText(
                indexFile(context),
                json.encodeToString(ListSerializer(Novel.serializer()), list),
            )
        }.onFailure { Log.w(TAG, "书架索引写入失败", it) }
    }

    /**
     * v0.188.0 M25：书架读侧派生——lastReadChapter 以阅读进度为准（进度文件是唯一事实源），
     * 无进度记录时回退 index 原值。阅读器只写进度，不再双写 index，书架分组不再与续读各说各话。
     * updateProgress 保留作兼容回退写（备份恢复等旧调用方），不作为事实源。
     */
    suspend fun list(context: Context): List<Novel> = withContext(Dispatchers.IO) {
        val all = readIndex(context).sortedByDescending { it.addedAt }
        val progressById = ReadingProgressStore.loadAll(context)
        all.map { n ->
            progressById[n.id]?.let {
                n.copy(
                    lastReadChapter = it.chapterIndex,
                    // v0.214.0：进度里的在读章标题一并派生到书架行。
                    lastReadChapterTitle = it.chapterTitle.takeIf { t -> t.isNotBlank() },
                )
            } ?: n
        }
    }

    suspend fun get(context: Context, id: String): Novel? = withContext(Dispatchers.IO) {
        val n = readIndex(context).firstOrNull { it.id == id } ?: return@withContext null
        ReadingProgressStore.load(context, id)?.let {
            n.copy(
                lastReadChapter = it.chapterIndex,
                lastReadChapterTitle = it.chapterTitle.takeIf { t -> t.isNotBlank() },
            )
        } ?: n
    }

    /** 幂等添加：同 id 已存在则保留进度只更新元信息。 */
    suspend fun addNovel(context: Context, novel: Novel, chapters: List<Chapter>): Boolean =
        withContext(Dispatchers.IO) {
            indexMutex.withLock {
            runCatching {
                // v0.211.0 审计修复：重复入架原来用空目录全量覆盖旧章节
                // 文件，已缓存的正文一次清光。先按标题读旧缓存，新章
                // content 为空时沿用旧正文（与 updateSource 同款 graft）。
                val oldByTitle = runCatching {
                    val cf = chaptersFile(context, novel.id)
                    if (!cf.isFile) emptyList()
                    else json.decodeFromString(ListSerializer(Chapter.serializer()), cf.readText())
                }.getOrDefault(emptyList()).associateBy { it.title }
                val fixed = chapters.mapIndexed { i, c ->
                    val keep = c.content.ifBlank { oldByTitle[c.title]?.content.orEmpty() }
                    c.copy(novelId = novel.id, index = i, content = keep)
                }
                // v0.170.0：原子写盘；写盘失败如实返回 false，不谎报导入成功
                if (!AtomicFile.writeText(
                        chaptersFile(context, novel.id),
                        json.encodeToString(ListSerializer(Chapter.serializer()), fixed),
                    )
                ) return@runCatching false
                val old = readIndex(context).firstOrNull { it.id == novel.id }
                val merged = novel.copy(
                    totalChapters = fixed.size,
                    lastReadChapter = old?.lastReadChapter ?: 0,
                    // v0.214.0：入架即记最新章标题；重复入架目录变长时也刷新。
                    latestChapterTitle = fixed.lastOrNull()?.title?.takeIf { it.isNotBlank() }
                        ?: old?.latestChapterTitle,
                    addedAt = old?.addedAt ?: System.currentTimeMillis(),
                )
                val rest = readIndex(context).filterNot { it.id == novel.id }
                writeIndex(context, listOf(merged) + rest)
                true
            }.onFailure { Log.w(TAG, "小说导入失败", it) }.getOrDefault(false)
            }
        }

    /** v0.83.0：换源——替换某书的目录与源 key，保留加入时间与阅读进度。返回新目录。 */
    suspend fun updateSource(
        context: Context,
        novelId: String,
        sourceKey: String,
        chapters: List<Chapter>,
    ): Boolean = withContext(Dispatchers.IO) {
        indexMutex.withLock {
        runCatching {
            // v0.186.0 第 9 组：先查索引存在再写章节——索引不存在直接返回 false，
            // 章节文件一动不动，不再出现章节已换、索引未换、调用方以为失败原数据还在。
            if (readIndex(context).none { it.id == novelId }) return@runCatching false
            // v0.107.0 P0-6：追更/换源只带目录不带正文——content 为空的新章节不许覆盖
            // 已有缓存正文（按标题匹配，防源方目录乱序 graft 错章）
            val cf = chaptersFile(context, novelId)
            val oldByTitle = runCatching {
                if (!cf.isFile) emptyList()
                else json.decodeFromString(ListSerializer(Chapter.serializer()), cf.readText())
            }.onFailure {
                Log.w(TAG, "旧章节读取失败", it)
                // v0.170.0：读坏先保全原文件
                AtomicFile.preserveCorrupt(cf)
            }.getOrDefault(emptyList()).associateBy { it.title }
            val fixed = chapters.mapIndexed { i, c ->
                val keep = c.content.ifBlank { oldByTitle[c.title]?.content.orEmpty() }
                c.copy(novelId = novelId, index = i, content = keep)
            }
            // v0.170.0：原子写盘；写盘失败如实返回 false
            if (!AtomicFile.writeText(
                    chaptersFile(context, novelId),
                    json.encodeToString(ListSerializer(Chapter.serializer()), fixed),
                )
            ) return@runCatching false
            val list = readIndex(context).toMutableList()
            val i = list.indexOfFirst { it.id == novelId }
            if (i < 0) return@runCatching false
            list[i] = list[i].copy(
                sourceKey = sourceKey,
                totalChapters = fixed.size,
                // v0.214.0：换源/追更写目录时同步最新章标题，书架行不用再翻目录。
                latestChapterTitle = fixed.lastOrNull()?.title?.takeIf { it.isNotBlank() },
            )
            writeIndex(context, list)
            // v0.211.0 审计修复：换源后进度不重映射、角标不清——
            // 新目录变短时旧下标越界，阅读器回第 0 章、书架还显示
            // 旧章数，旧源角标还挂在新源书上。下标越界钳制到末章，
            // 角标一律清掉重算（追更检查会在写盘后重新打角标）。
            val prog = ReadingProgressStore.load(context, novelId)
            // v0.216.0（C8）：钳制只改下标不清标题——复用旧进度 copy，新标题取
            // 新目录对应章；新目录为空时不写这条进度（不留指向不存在第 0 章的脏进度）。
            if (prog != null && fixed.isNotEmpty() && prog.chapterIndex >= fixed.size) {
                val newIndex = (fixed.size - 1).coerceAtLeast(0)
                ReadingProgressStore.save(
                    context,
                    prog.copy(
                        chapterIndex = newIndex,
                        charOffset = 0,
                        chapterTitle = fixed[newIndex].title,
                    ),
                )
            }
            NovelUpdateStore.clear(context, novelId)
            true
        }.onFailure { Log.w(TAG, "换源失败", it) }.getOrDefault(false)
        }
    }

    /** v0.215.0：清除已缓存章节正文（目录保留）——净化规则改动后，
     * 已缓存的旧正文不会自己变新，须清掉、下次打开重拉并走新规则。返回清掉的书本数。 */
    suspend fun clearCachedContents(context: Context): Int = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            var n = 0
            for (novel in readIndex(context)) {
                val cf = chaptersFile(context, novel.id)
                if (!cf.isFile) continue
                val chapters = runCatching {
                    json.decodeFromString(ListSerializer(Chapter.serializer()), cf.readText())
                }.getOrNull() ?: continue
                if (chapters.none { it.content.isNotBlank() }) continue
                if (AtomicFile.writeText(
                        cf,
                        json.encodeToString(ListSerializer(Chapter.serializer()), chapters.map { it.copy(content = "") }),
                    )
                ) n++
            }
            n
        }
    }


    /** v0.211.0 审计修复：源 key 变了（改地址/重命名），书架上
     *  用旧 key 的书要跟着迁，否则静默断源。返回迁移本数。 */
    suspend fun migrateSourceKey(context: Context, oldKey: String, newKey: String): Int =
        withContext(Dispatchers.IO) {
            indexMutex.withLock {
                val list = readIndex(context)
                if (list.none { it.sourceKey == oldKey }) return@withContext 0
                val migrated = list.map {
                    if (it.sourceKey == oldKey) it.copy(sourceKey = newKey) else it
                }
                writeIndex(context, migrated)
                list.count { it.sourceKey == oldKey }
            }
        }

    /** v0.211.0 审计修复：删源前给用户看受影响本数（删源确认用）。 */
    suspend fun countBySourceKey(context: Context, key: String): Int =
        withContext(Dispatchers.IO) {
            readIndex(context).count { it.sourceKey == key }
        }

    suspend fun removeNovel(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        indexMutex.withLock {
        runCatching {
            writeIndex(context, readIndex(context).filterNot { it.id == id })
            chaptersFile(context, id).delete()
            true
        }.onFailure { Log.w(TAG, "删除小说失败", it) }.getOrDefault(false)
        }
    }

    /**
     * v0.186.0 第 9 组：删书唯一入口——书架页与搜索页两条删除路径共用，
     * 清理范围一致：本体章节 + 阅读进度 + 逐条书签 + 全部分组 + 追更角标。
     * 本体删除失败时直接返回 false，不动其余清理项，避免书还在、进度先没了。
     * 同包内直接调用各 Store 的已有公开函数，不改那些文件。
     */
    suspend fun deleteNovelCascade(context: Context, id: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!removeNovel(context, id)) return@withContext false
            ReadingProgressStore.clear(context, id)
            // BookmarkStore 无整书 clear，用 list 加 remove 逐条删（书架页原有写法）
            BookmarkStore.list(context, id).forEach { bm ->
                BookmarkStore.remove(context, id, bm.createdAt)
            }
            BookshelfGroupStore.removeNovelEverywhere(context, id)
            NovelUpdateStore.clear(context, id)
            // v0.211.0 审计修复：删书原来不碰 update_failed_ids，
            // 失败页签计数永远含死 id。删书时同步清掉该 id。
            val failed = BookshelfGroupStore.failedIds(context)
            if (id in failed) BookshelfGroupStore.setFailedIds(context, failed - id)
            true
        }

    suspend fun chapters(context: Context, novelId: String): List<Chapter> =
        withContext(Dispatchers.IO) {
            val f = chaptersFile(context, novelId)
            if (!f.isFile) return@withContext emptyList()
            runCatching {
                json.decodeFromString(ListSerializer(Chapter.serializer()), f.readText())
            }.onFailure {
                Log.w(TAG, "章节读取失败", it)
                // v0.170.0：读坏先保全原文件
                AtomicFile.preserveCorrupt(f)
            }.getOrDefault(emptyList())
                // v0.194.0：已入库的老书读盘时也滤掉导航章（不用重加就干净），
                // 并按过滤后的位置重排 index，与阅读器的位置口径一致。
                .filterNot { isNavChapterTitle(it.title) }
                .mapIndexed { i, c -> c.copy(index = i) }
        }

    suspend fun chapter(context: Context, novelId: String, index: Int): Chapter? =
        withContext(Dispatchers.IO) {
            chapters(context, novelId).getOrNull(index)
        }

    /** 更新读到第几章（书架分组用）。 */
    suspend fun updateProgress(context: Context, novelId: String, chapterIndex: Int) =
        withContext(Dispatchers.IO) {
            runCatching {
                val all = readIndex(context)
                writeIndex(context, all.map {
                    if (it.id == novelId) it.copy(lastReadChapter = chapterIndex) else it
                })
            }.onFailure { Log.w(TAG, "阅读进度更新失败", it) }
        }

    /** v0.214.0：单本追更开关（详情页拨动；关掉的书手动/后台检查都跳过）。 */
    suspend fun setUpdateEnabled(context: Context, novelId: String, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            runCatching {
                val all = readIndex(context)
                writeIndex(context, all.map {
                    if (it.id == novelId) it.copy(updateEnabled = enabled) else it
                })
            }.onFailure { Log.w(TAG, "追更开关更新失败", it) }
        }

    /** 更新单章正文（在线书源按需拉取后缓存）。
     *  v0.211.0 审计修复：调用方传的是过滤导航章后的下标，
     *  原来直接按磁盘原始 index 匹配，含导航章的老书会把正文
     *  写到错一格的章节。先按过滤口径定位目标章，再按它在
     *  磁盘里的原始 index 写回。 */
    suspend fun updateChapterContent(context: Context, novelId: String, index: Int, content: String) =
        withContext(Dispatchers.IO) {
            runCatching {
                val f = chaptersFile(context, novelId)
                if (!f.isFile) return@runCatching
                val list = json.decodeFromString(ListSerializer(Chapter.serializer()), f.readText())
                val target = list.filterNot { isNavChapterTitle(it.title) }.getOrNull(index)
                    ?: return@runCatching
                val updated = list.map { if (it.index == target.index) it.copy(content = content) else it }
                // v0.170.0：原子写盘
                AtomicFile.writeText(
                    f,
                    json.encodeToString(ListSerializer(Chapter.serializer()), updated),
                )
            }.onFailure { Log.w(TAG, "章节正文缓存失败", it) }
        }

}
