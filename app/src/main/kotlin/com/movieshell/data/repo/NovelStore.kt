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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

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

    // v0.249.0：目录状态常量（与 Novel.dirState 对应，见 NovelModels）。
    const val DIR_READY = "READY"
    const val DIR_LOADING = "LOADING"
    const val DIR_FAILED = "FAILED"

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

    // v0.247.0 复核：章节文件“备份→写新版→更新索引→清理/回滚”事务小助手。
    // 调用方必须已持有 indexMutex——addNovel/updateSource/clearCachedContents 本就持锁，
    // updateChapterContent 与章节读取 chapters() 本轮也纳入同一锁，所以同一本书的事务不可能交错，
    // 固定 .bak 名不会出现两路互相删备份、恢复错乱。
    private fun backupFile(cf: File): File = File(cf.absolutePath + ".bak")

    // v0.247.0 复核：章节事务的预写日志（WAL）。崩溃一致性不能只凭“.bak 与正本共存”推断——
    // 旧文件改名→写新正本→索引写盘前崩溃时，两者共存但索引仍是旧版，删备份就丢了恢复旧章的机会。
    // 因此事务开始前先落一条日志；恢复时不只比对索引字段，更比对事务 id——
    // 索引条目只有写上本次事务 id 才算本次提交，旧索引字段碰巧相等也绝不会误判。
    // 例外：日志读坏按未提交处理（日志本身原子写盘，读坏概率极低）。
    @Serializable
    private data class ChapterTxJournal(
        val novelId: String,
        val txId: String,
        val sourceKey: String,
        val totalChapters: Int,
        val latestChapterTitle: String?,
    )

    private fun journalFile(context: Context, novelId: String): File =
        File(dirOf(context), "$novelId.journal")

    private fun writeTxJournal(context: Context, expected: ChapterTxJournal): Boolean =
        AtomicFile.writeText(
            journalFile(context, expected.novelId),
            json.encodeToString(ChapterTxJournal.serializer(), expected),
        )

    private fun readTxJournal(context: Context, novelId: String): ChapterTxJournal? =
        runCatching {
            json.decodeFromString(ChapterTxJournal.serializer(), journalFile(context, novelId).readText())
        }.onFailure { Log.w(TAG, "章节事务日志读取失败", it) }.getOrNull()

    // 事务开始时的崩溃恢复（调用方必须已持有 indexMutex）。返回 false = 状态异常，调用方应直接失败。
    // 幂等保证：提交路径先删备份再删日志（日志删失败下次重算仍是提交路径）；
    // 回滚路径先回滚、成功后才删日志（回滚失败保留日志与备份，下次重试；
    // 崩溃在回滚与删日志之间时，下次仍走日志分支：备份已空，按留正本规则删日志，结果一致）。
    private fun recoverTx(context: Context, novelId: String): Boolean {
        val cf = chaptersFile(context, novelId)
        val backup = backupFile(cf)
        val journal = journalFile(context, novelId)
        if (!journal.isFile) {
            // 无日志：老版本残留或从未崩溃。新版事务一定先写日志再备份，
            // 所以“无日志、有备份”只可能来自日志机制上线前的遗留崩溃，
            // 备份是唯一旧数据的情形（正本缺失）必须恢复；
            // 两者共存的歧义情形按“保旧数据”处理：恢复备份（新正本是未提交事务的产物）。
            if (!backup.isFile) return true
            if (!restoreChapters(cf, backup, hadOld = true)) return false
            Log.w(TAG, "章节备份恢复（无日志遗留）：已恢复旧章节文件 ${cf.absolutePath}")
            return true
        }
        val expected = readTxJournal(context, novelId)
        val entry = readIndex(context).firstOrNull { it.id == novelId }
        // 提交判定只认事务 id：索引条目写上了本次事务 id 才算本次提交；
        // 旧索引三个字段碰巧与期望值全相等时，事务 id 一定对不上，不会误判。
        // 日志读坏按未提交处理。
        val committed = expected != null && entry != null &&
            entry.id == expected.novelId && entry.lastTxId == expected.txId
        if (committed) {
            finishTx(context, novelId, backup)
            return true
        }
        // 索引未提交：先回滚，成功后才删日志——回滚失败时保留日志与备份，下次恢复重试。
        // 崩溃在回滚与删日志之间时，下次恢复仍走日志分支：备份已空，按留正本规则删日志，结果一致。
        // 备份缺失时不回滚：正本可能是已回滚的旧内容（删掉会丢数据），
        // 也可能是未提交的新内容（留着至少书还能读，下次成功事务会覆盖），两种情形都应留着正本——
        // 这是有意接受的一致性降级，不是完全恢复（无备份时已无从证明正本新旧）。
        if (backup.isFile && !restoreChapters(cf, backup, hadOld = true)) return false
        if (!journal.delete()) {
            Log.w(TAG, "章节事务日志删除失败（下次恢复会重算）：${journal.absolutePath}")
        }
        return true
    }

    // 写新版前把旧正本改名备份（零拷贝）。返回 false = 备份失败，调用方应直接失败不动旧数据。
    private fun backupChapters(cf: File, backup: File): Boolean {
        if (!cf.isFile) return true
        if (!cf.renameTo(backup)) {
            Log.w(TAG, "章节备份失败：${cf.absolutePath} 未能改名为 ${backup.absolutePath}")
            return false
        }
        return true
    }

    // 失败回滚：删掉新正本、把备份改回来；返回是否完成（调用方已在失败路径上，结果只用于恢复流程）。
    // hadOld=false（首次导入）时只需删新文件。
    private fun restoreChapters(cf: File, backup: File, hadOld: Boolean): Boolean {
        if (!hadOld) {
            if (cf.isFile && !cf.delete()) {
                // 删不掉就隔离改名，避免未提交文件继续冒充有效章节文件；
                // 改名也失败才返回失败（日志保留，下次恢复重试）。
                val quarantined = File(cf.absolutePath + ".uncommitted")
                if (!cf.renameTo(quarantined)) {
                    Log.w(TAG, "失败回滚：新章节文件既删不掉也隔离不了 ${cf.absolutePath}")
                    return false
                }
                Log.w(TAG, "失败回滚：新章节文件无法删除，已隔离为 ${quarantined.absolutePath}")
            }
            return true
        }
        if (cf.isFile && !cf.delete()) {
            Log.w(TAG, "失败回滚：新章节文件删除失败 ${cf.absolutePath}，旧备份无法恢复")
            return false
        }
        if (!backup.renameTo(cf)) {
            Log.w(TAG, "失败回滚：章节备份恢复失败 ${backup.absolutePath} -> ${cf.absolutePath}")
            return false
        }
        return true
    }

    // 成功后清理备份。返回是否清掉——调用方必须先清备份、成功后再删日志，
    // 顺序不能反：先删日志后清备份时，崩溃在两者之间会留下“无日志、有备份”，
    // 下次恢复按遗留规则恢复旧版，就会覆盖已提交的新章节。
    // 清理失败不算事务失败——数据已一致，日志保留供下次恢复重试清理。
    private fun cleanupBackup(backup: File): Boolean {
        if (!backup.isFile) return true
        if (!backup.delete()) {
            Log.w(TAG, "章节备份清理失败，保留事务日志供下次恢复重试：${backup.absolutePath}")
            return false
        }
        return true
    }

    // 提交收尾：先清备份、成功后再删日志（顺序见 cleanupBackup 注释）。
    // 语义界定：收尾失败 ≠ 事务失败——索引写盘成功即已提交，清理只是待重试；
    // 调用方不得据此回滚（abortTx 只在 writeIndex 失败、索引未提交时调用）。
    // 本函数无返回值，三处调用点均忽略收尾结果、直接返回成功。
    private fun finishTx(context: Context, novelId: String, backup: File) {
        if (!cleanupBackup(backup)) return
        if (!journalFile(context, novelId).delete()) {
            Log.w(TAG, "章节事务日志删除失败（下次恢复会重算）")
        }
    }

    // 事务内失败收尾：回滚章节文件，成功后才删日志，返回 false 供调用方直接返回。
    // 回滚失败时保留日志与备份，下次恢复重试；日志删失败也只记日志——
    // 下次恢复时备份缺失则留着正本（recoverTx 规则），不会丢已恢复的数据。
    private fun abortTx(context: Context, novelId: String, cf: File, backup: File, hadOld: Boolean): Boolean {
        if (!restoreChapters(cf, backup, hadOld)) return false
        if (!journalFile(context, novelId).delete()) {
            Log.w(TAG, "章节事务日志删除失败（下次恢复会处理）")
        }
        return false
    }

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

    // v0.246.0：返回写盘成败——原来返回 Unit、失败只记日志，
    // removeNovel 无法知道索引写没写成，照样删章节文件，书还在、章节先没了。
    private fun writeIndex(context: Context, list: List<Novel>): Boolean {
        return runCatching {
            // v0.170.0：原子写盘
            AtomicFile.writeText(
                indexFile(context),
                json.encodeToString(ListSerializer(Novel.serializer()), list),
            )
        }.onFailure { Log.w(TAG, "书架索引写入失败", it) }.getOrDefault(false)
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
            val p = progressById[n.id]
            if (p != null) {
                n.copy(
                    lastReadChapter = p.chapterIndex,
                    // v0.214.0：进度里的在读章标题一并派生到书架行。
                    lastReadChapterTitle = p.chapterTitle.takeIf { t -> t.isNotBlank() },
                    // v0.217.0（F46）：进度记录存在即已开始；无记录时显式
                    // 置 false，免得索引里残留的旧值在进度清掉后还赖着。
                    hasStartedReading = true,
                )
            } else {
                n.copy(hasStartedReading = false)
            }
        }
    }

    suspend fun get(context: Context, id: String): Novel? = withContext(Dispatchers.IO) {
        val n = readIndex(context).firstOrNull { it.id == id } ?: return@withContext null
        val p = ReadingProgressStore.load(context, id)
        if (p != null) {
            n.copy(
                lastReadChapter = p.chapterIndex,
                lastReadChapterTitle = p.chapterTitle.takeIf { t -> t.isNotBlank() },
                hasStartedReading = true,
            )
        } else {
            n.copy(hasStartedReading = false)
        }
    }

    /** 幂等添加：同 id 已存在则保留进度只更新元信息。
     * v0.217.0（F48）：空目录拒绝入架——原来空列表也照写空章节文件，
     * 发现页／重复入架一旦传空目录，旧书的章节连同缓存正文一次清光；
     * 现在空目录直接返回 false，调用方按失败提示，不动旧数据。 */
    suspend fun addNovel(context: Context, novel: Novel, chapters: List<Chapter>): Boolean =
        withContext(Dispatchers.IO) {
            if (chapters.isEmpty()) return@withContext false
            indexMutex.withLock {
            runCatching {
                // v0.247.0 复核：事务开始先做崩溃恢复（日志比对或遗留备份处理），
                // 再读旧缓存做 graft——否则 graft 读到的可能是残缺状态。
                val chaptersCf = chaptersFile(context, novel.id)
                val chaptersBackup = backupFile(chaptersCf)
                if (!recoverTx(context, novel.id)) return@runCatching false
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
                val old = readIndex(context).firstOrNull { it.id == novel.id }
                // v0.247.0 复核：本次事务唯一 id，写入索引条目与事务日志——
                // 恢复时只认事务 id，不认字段相等，旧索引碰巧相等也不会误判为已提交。
                val txId = UUID.randomUUID().toString()
                val merged = novel.copy(
                    totalChapters = fixed.size,
                    lastReadChapter = old?.lastReadChapter ?: 0,
                    // v0.214.0：入架即记最新章标题；重复入架目录变长时也刷新。
                    latestChapterTitle = fixed.lastOrNull()?.title?.takeIf { it.isNotBlank() }
                        ?: old?.latestChapterTitle,
                    addedAt = old?.addedAt ?: System.currentTimeMillis(),
                    lastTxId = txId,
                    // v0.249.0：写进真实目录的书一定是 READY（空目录壳只走 insertShell）。
                    dirState = DIR_READY,
                )
                // v0.247.0 复核：动章节文件前先落事务日志（索引写盘后应有的字段）——
                // 日志写失败直接 abort，旧数据一动不动。
                if (!writeTxJournal(
                        context,
                        ChapterTxJournal(novel.id, txId, merged.sourceKey, merged.totalChapters, merged.latestChapterTitle),
                    )
                ) return@runCatching false
                // v0.247.0（F08 复核）：写新版前备份旧正本；备份失败不动旧数据，顺手删日志。
                val hadOld = chaptersCf.isFile
                if (!backupChapters(chaptersCf, chaptersBackup)) {
                    if (!journalFile(context, novel.id).delete()) {
                        Log.w(TAG, "章节事务日志删除失败（下次恢复会处理）")
                    }
                    return@runCatching false
                }
                // v0.170.0：原子写盘；写盘失败如实返回 false，不谎报导入成功
                if (!AtomicFile.writeText(
                        chaptersCf,
                        json.encodeToString(ListSerializer(Chapter.serializer()), fixed),
                    )
                ) {
                    // 章节写失败：恢复旧文件，旧索引继续指向旧内容
                    return@runCatching abortTx(context, novel.id, chaptersCf, chaptersBackup, hadOld)
                }
                val rest = readIndex(context).filterNot { it.id == novel.id }
                // v0.247.0（F08）：索引写盘失败如实返回 false——原来忽略返回值直接报 true，
                // 导入成功但书目不在索引里。
                // v0.247.0（F08 复核）：失败时恢复旧章节文件（首次导入则删掉新文件），
                // 旧索引继续指向旧内容；成功后删日志、清备份。
                if (!writeIndex(context, listOf(merged) + rest)) {
                    return@runCatching abortTx(context, novel.id, chaptersCf, chaptersBackup, hadOld)
                }
                // 索引已提交：先清备份、成功后再删日志（顺序见 cleanupBackup 注释）。
                finishTx(context, novel.id, chaptersBackup)
                true
            }.onFailure { Log.w(TAG, "小说导入失败", it) }.getOrDefault(false)
            }
        }

    /** v0.249.0：先入架的空目录壳——只写索引条目（dirState=LOADING），不写章节文件、
     * 不碰旧数据；id 已存在直接返回 false（调用方按已在架处理）。
     * 空目录壳绝不走 addNovel（v0.217.0 F48 的空目录拒写仍有效）。 */
    suspend fun insertShell(context: Context, novel: Novel): Boolean =
        withContext(Dispatchers.IO) {
            indexMutex.withLock {
                val current = readIndex(context)
                if (current.any { it.id == novel.id }) return@withLock false
                val shell = novel.copy(
                    totalChapters = 0,
                    latestChapterTitle = null,
                    dirState = DIR_LOADING,
                )
                writeIndex(context, listOf(shell) + current)
            }
        }

    /** v0.249.0：只改目录状态（FAILED 标记／成功后的封面补齐走这里，不动章节文件）。
     * id 不在索引里返回 false，调用方忽略即可（书可能已被删）。 */
    suspend fun markDirectoryState(
        context: Context,
        novelId: String,
        state: String,
        cover: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            val list = readIndex(context).toMutableList()
            val i = list.indexOfFirst { it.id == novelId }
            if (i < 0) return@withLock false
            list[i] = list[i].copy(
                dirState = state,
                cover = cover?.takeIf { it.isNotBlank() } ?: list[i].cover,
            )
            writeIndex(context, list)
        }
    }

    /** v0.249.0 收尾：章节文件是否真实存在且非空（“[]”不算有目录）。 */
    fun hasChaptersFile(context: Context, novelId: String): Boolean {
        val cf = chaptersFile(context, novelId)
        return cf.isFile && cf.length() > 2
    }

    /** v0.83.0：换源——替换某书的目录与源 key，保留加入时间与阅读进度。返回新目录。 */
    suspend fun updateSource(
        context: Context,
        novelId: String,
        sourceKey: String,
        chapters: List<Chapter>,
        // v0.249.0：目录补写时顺带补封面（仅搜索无封面时传入，其余调用方不传即不动）。
        cover: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        indexMutex.withLock {
        runCatching {
            // v0.186.0 第 9 组：先查索引存在再写章节——索引不存在直接返回 false，
            // 章节文件一动不动，不再出现章节已换、索引未换、调用方以为失败原数据还在。
            if (readIndex(context).none { it.id == novelId }) return@runCatching false
            // v0.107.0 P0-6：追更/换源只带目录不带正文——content 为空的新章节不许覆盖
            // 已有缓存正文（按标题匹配，防源方目录乱序 graft 错章）
            val cf = chaptersFile(context, novelId)
            // v0.247.0 复核：同 addNovel，事务开始先做崩溃恢复，再读旧缓存。
            val backup = backupFile(cf)
            if (!recoverTx(context, novelId)) return@runCatching false
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
            // v0.247.0（F08 复核）：同 addNovel，写新版前备份旧正本；索引失败时恢复，
            // 避免旧索引元数据（旧源、旧章数）配新章节内容。
            // v0.247.0 复核：动章节文件前先落事务日志（索引写盘后应有的字段 + 本次事务 id）。
            val txId = UUID.randomUUID().toString()
            if (!writeTxJournal(
                    context,
                    ChapterTxJournal(
                        novelId,
                        txId,
                        sourceKey,
                        fixed.size,
                        fixed.lastOrNull()?.title?.takeIf { it.isNotBlank() },
                    ),
                )
            ) return@runCatching false
            val hadOld = cf.isFile
            if (!backupChapters(cf, backup)) {
                if (!journalFile(context, novelId).delete()) {
                    Log.w(TAG, "章节事务日志删除失败（下次恢复会处理）")
                }
                return@runCatching false
            }
            // v0.170.0：原子写盘；写盘失败如实返回 false
            if (!AtomicFile.writeText(
                    cf,
                    json.encodeToString(ListSerializer(Chapter.serializer()), fixed),
                )
            ) {
                return@runCatching abortTx(context, novelId, cf, backup, hadOld)
            }
            val list = readIndex(context).toMutableList()
            val i = list.indexOfFirst { it.id == novelId }
            if (i < 0) {
                // 索引条目没了：同样恢复旧章节文件，不留新内容配旧索引
                return@runCatching abortTx(context, novelId, cf, backup, hadOld)
            }
            list[i] = list[i].copy(
                sourceKey = sourceKey,
                totalChapters = fixed.size,
                // v0.214.0：换源/追更写目录时同步最新章标题，书架行不用再翻目录。
                latestChapterTitle = fixed.lastOrNull()?.title?.takeIf { it.isNotBlank() },
                lastTxId = txId,
                // v0.249.0：写进真实目录即 READY（先入架的壳、更新目录的书都经此归位）。
                dirState = DIR_READY,
                cover = cover?.takeIf { it.isNotBlank() } ?: list[i].cover,
            )
            // v0.247.0（F08 同类）：索引写盘失败直接返回 false，不再往下算进度——
            // 原来忽略返回值，章节已换、索引未换，调用方还以为换源成功。
            // v0.247.0（F08 复核）：失败时恢复旧章节文件，成功后先清备份再删日志。
            if (!writeIndex(context, list)) {
                return@runCatching abortTx(context, novelId, cf, backup, hadOld)
            }
            finishTx(context, novelId, backup)
            // v0.211.0 审计修复：换源后进度不重映射、角标不清——
            // 新目录变短时旧下标越界，阅读器回第 0 章、书架还显示
            // 旧章数，旧源角标还挂在新源书上。下标越界钳制到末章，
            // 角标一律清掉重算（追更检查会在写盘后重新打角标）。
            val prog = ReadingProgressStore.load(context, novelId)
            // v0.216.0（C8）：钳制只改下标不清标题——复用旧进度 copy，新标题取
            // 新目录对应章；新目录为空时不写这条进度（不留指向不存在第 0 章的脏进度）。
            // v0.217.0（F44）：同下标也要按新目录重算标题——换源后旧章名还挂在
            // 进度上，书架行显示的是旧源的章名；只重算标题，不动下标与段内偏移
            // （追更刷新同源目录时下标与偏移本来就是对的，不能顺手清掉）。
            if (prog != null && fixed.isNotEmpty()) {
                if (prog.chapterIndex >= fixed.size) {
                    val newIndex = (fixed.size - 1).coerceAtLeast(0)
                    ReadingProgressStore.save(
                        context,
                        prog.copy(
                            chapterIndex = newIndex,
                            charOffset = 0,
                            chapterTitle = fixed[newIndex].title,
                        ),
                    )
                } else if (prog.chapterTitle != fixed[prog.chapterIndex].title) {
                    ReadingProgressStore.save(
                        context,
                        prog.copy(chapterTitle = fixed[prog.chapterIndex].title),
                    )
                }
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
     *  用旧 key 的书要跟着迁，否则静默断源。返回迁移本数；
     *  v0.247.0（F09）：写盘失败返回 -1——原来忽略写盘结果，
     *  调用方无从知道迁移没落盘，书架还指着旧 key。 */
    suspend fun migrateSourceKey(context: Context, oldKey: String, newKey: String): Int =
        withContext(Dispatchers.IO) {
            indexMutex.withLock {
                val list = readIndex(context)
                if (list.none { it.sourceKey == oldKey }) return@withContext 0
                // v0.247.0 复核：copy 只改 sourceKey，原样保留 lastTxId——
                // 迁移不碰章节文件与事务日志，不影响任何在途/崩溃事务的提交判定；
                // 且与章节事务共用同一把锁，不会与事务中途交错。
                val migrated = list.map {
                    if (it.sourceKey == oldKey) it.copy(sourceKey = newKey) else it
                }
                if (!writeIndex(context, migrated)) return@withContext -1
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
            // v0.246.0：索引写盘失败直接返回 false，不删章节——
            // 原来不管写没写成都删章节，索引失败时书还在、章节先没了。
            if (!writeIndex(context, readIndex(context).filterNot { it.id == id })) return@runCatching false
            val cf = chaptersFile(context, id)
            cf.delete()
            // v0.247.0 复核：删书时顺手清 .bak 与事务日志——残留备份虽由下次事务的恢复逻辑处理，
            // 但书已删就不该再留它的旧章节备份与未完成事务日志。
            backupFile(cf).delete()
            journalFile(context, id).delete()
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
            // v0.247.0 复核：读章节也进 indexMutex——读坏时 preserveCorrupt 会移动文件，
            // 原来不持锁，可能与备份事务交错。内部调用方 chapter() 不持锁，无重入死锁。
            indexMutex.withLock {
            val f = chaptersFile(context, novelId)
            if (!f.isFile) return@withLock emptyList()
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
        }

    suspend fun chapter(context: Context, novelId: String, index: Int): Chapter? =
        withContext(Dispatchers.IO) {
            chapters(context, novelId).getOrNull(index)
        }

    /** 更新读到第几章（书架分组用）。
     * v0.217.0（F41）：读-改-写进索引锁——原来两处直接读盘改写，与
     * addNovel／updateSource 的锁内写盘并发时互相覆盖，进度或开关会丢。 */
    suspend fun updateProgress(context: Context, novelId: String, chapterIndex: Int) =
        withContext(Dispatchers.IO) {
            runCatching {
                indexMutex.withLock {
                    val all = readIndex(context)
                    // v0.247.0（F08 同类）：写盘失败记日志——原来返回值被忽略，
                    // 进度丢了还没任何痕迹。best-effort 更新，不改返回类型。
                    if (!writeIndex(context, all.map {
                        if (it.id == novelId) it.copy(lastReadChapter = chapterIndex) else it
                    })) Log.w(TAG, "阅读进度更新失败：索引写盘失败")
                }
            }.onFailure { Log.w(TAG, "阅读进度更新失败", it) }
        }

    /** v0.214.0：单本追更开关（详情页拨动；关掉的书手动/后台检查都跳过）。
     * v0.217.0（F41）：同上，读-改-写进索引锁。 */
    suspend fun setUpdateEnabled(context: Context, novelId: String, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            runCatching {
                indexMutex.withLock {
                    val all = readIndex(context)
                    // v0.247.0（F08 同类）：同上，开关丢了记日志，不改返回类型。
                    if (!writeIndex(context, all.map {
                        if (it.id == novelId) it.copy(updateEnabled = enabled) else it
                    })) Log.w(TAG, "追更开关更新失败：索引写盘失败")
                }
            }.onFailure { Log.w(TAG, "追更开关更新失败", it) }
        }

    /** 更新单章正文（在线书源按需拉取后缓存）。
     *  v0.211.0 审计修复：调用方传的是过滤导航章后的下标，
     *  原来直接按磁盘原始 index 匹配，含导航章的老书会把正文
     *  写到错一格的章节。先按过滤口径定位目标章，再按它在
     *  磁盘里的原始 index 写回。 */
    suspend fun updateChapterContent(context: Context, novelId: String, index: Int, content: String) =
        withContext(Dispatchers.IO) {
            // v0.247.0 复核：单章正文写盘也纳入 indexMutex——原来不持锁，
            // 可能与 addNovel/updateSource 的备份事务交错（固定 .bak 名、章节文件共用）。
            // 唯一调用方是阅读器（不持锁），函数内不调其他持锁入口，无死锁。
            indexMutex.withLock {
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

}
