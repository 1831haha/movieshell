package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.model.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v0.35.0 章内阅读进度：落在 filesDir/reading_progress.json。
 * key=novelId，存 (chapterIndex, charOffset)。退出阅读器时保存，打开时恢复。
 */
object ReadingProgressStore {

    private const val TAG = "ReadingProgressStore"
    private const val FILE_NAME = "reading_progress.json"

    private val json = Json { ignoreUnknownKeys = true }

    private fun fileOf(context: Context): File = File(context.filesDir, FILE_NAME)

    // v0.211.0 审计修复：多路保存（翻页防抖、滚动停存、切章、
    // 换源、退出）原来无锁读-改-写，旧写可覆盖新写；同一把锁
    // 串行化 save/clear，后写为准。
    private val saveMutex = Mutex()

    /** 解析失败返回 null（与“文件不存在/空表”区分开），调用方
     *  据此决定是正常写盘还是先从备份恢复，绝不拿空表覆盖全书进度。 */
    private fun readAllOrNull(context: Context): List<ReadingProgress>? {
        val f = fileOf(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ReadingProgress.serializer()), f.readText())
        }.onFailure {
            Log.w(TAG, "进度读取失败", it)
            // v0.170.0：读坏先保全原文件，下次写盘不再悄悄清空
            AtomicFile.preserveCorrupt(f)
        }.getOrNull()
    }

    private fun readAll(context: Context): List<ReadingProgress> =
        readAllOrNull(context) ?: emptyList()

    /** 损坏时的恢复底本：先试 .corrupt.bak（preserveCorrupt 拷的那份），
     *  能解析就用它当底本，恢复不了才认栽——总比直接用空表抹掉全书强。 */
    private fun recoverBase(context: Context): List<ReadingProgress> {
        val bak = File(context.filesDir, "$FILE_NAME.corrupt.bak")
        if (!bak.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ReadingProgress.serializer()), bak.readText())
        }.getOrDefault(emptyList())
    }

    suspend fun save(context: Context, progress: ReadingProgress) = withContext(Dispatchers.IO) {
        saveMutex.withLock {
        runCatching {
            val fresh = progress.copy(updatedAt = System.currentTimeMillis())
            val base = readAllOrNull(context) ?: recoverBase(context)
            val rest = base.filterNot { it.novelId == fresh.novelId }
            // v0.170.0：原子写盘
            AtomicFile.writeText(
                fileOf(context),
                json.encodeToString(ListSerializer(ReadingProgress.serializer()), listOf(fresh) + rest),
            )
        }.onFailure { Log.w(TAG, "进度保存失败", it) }
        }
    }

    suspend fun load(context: Context, novelId: String): ReadingProgress? =
        withContext(Dispatchers.IO) {
            readAll(context).firstOrNull { it.novelId == novelId }
        }

    /**
     * v0.188.0 M25：一次读出全部进度，供 NovelStore 派生 lastReadChapter 用。
     * 阅读进度是唯一事实源；书架 index 里的 lastReadChapter 只作无进度时的回退值。
     */
    suspend fun loadAll(context: Context): Map<String, ReadingProgress> =
        withContext(Dispatchers.IO) {
            readAll(context).associateBy { it.novelId }
        }

    suspend fun clear(context: Context, novelId: String) = withContext(Dispatchers.IO) {
        saveMutex.withLock {
        runCatching {
            // v0.216.0（C9）：clear 原来用 readAll（读坏直接给空表），文件损坏时
            // 一次 clear 就把全书进度写成空表；改走 save 同款底本：读坏先试 .bak。
            val base = readAllOrNull(context) ?: recoverBase(context)
            val rest = base.filterNot { it.novelId == novelId }
            // v0.170.0：原子写盘
            AtomicFile.writeText(
                fileOf(context),
                json.encodeToString(ListSerializer(ReadingProgress.serializer()), rest),
            )
        }.onFailure { Log.w(TAG, "进度清除失败", it) }
        }
    }
}
