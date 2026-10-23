package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.model.ReadingProgress
import kotlinx.coroutines.Dispatchers
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

    private fun readAll(context: Context): List<ReadingProgress> {
        val f = fileOf(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ReadingProgress.serializer()), f.readText())
        }.onFailure {
            Log.w(TAG, "进度读取失败", it)
            // v0.170.0：读坏先保全原文件，下次写盘不再悄悄清空
            AtomicFile.preserveCorrupt(f)
        }.getOrDefault(emptyList())
    }

    suspend fun save(context: Context, progress: ReadingProgress) = withContext(Dispatchers.IO) {
        runCatching {
            val fresh = progress.copy(updatedAt = System.currentTimeMillis())
            val rest = readAll(context).filterNot { it.novelId == fresh.novelId }
            // v0.170.0：原子写盘
            AtomicFile.writeText(
                fileOf(context),
                json.encodeToString(ListSerializer(ReadingProgress.serializer()), listOf(fresh) + rest),
            )
        }.onFailure { Log.w(TAG, "进度保存失败", it) }
    }

    suspend fun load(context: Context, novelId: String): ReadingProgress? =
        withContext(Dispatchers.IO) {
            readAll(context).firstOrNull { it.novelId == novelId }
        }

    suspend fun clear(context: Context, novelId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val rest = readAll(context).filterNot { it.novelId == novelId }
            // v0.170.0：原子写盘
            AtomicFile.writeText(
                fileOf(context),
                json.encodeToString(ListSerializer(ReadingProgress.serializer()), rest),
            )
        }.onFailure { Log.w(TAG, "进度清除失败", it) }
    }
}
