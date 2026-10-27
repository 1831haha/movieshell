package com.movieshell.data.novel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v0.95.0 数据迁移：旧书架/书签/进度 JSON 若存在，首次启动备份为 .bak。
 *
 * v0.95 的实体层沿用既有文件（novels/index.json、novels/{id}.json、
 * reading_progress.json、novel_bookmarks.json、阅读配置），无需改写数据；
 * 这里只做一次性备份 + 标记，避免将来结构变更时无回退点。
 */
object NovelMigration {

    private const val TAG = "NovelMigration"
    private const val MARKER = "novel_migration_v95.done"

    private val backupTargets = listOf(
        "novels/index.json",
        "reading_progress.json",
        "novel_bookmarks.json",
    )

    /** 幂等：已迁移直接返回 true；备份失败返回 false（标记不写，下次启动重试）。
     * v0.246.0：原来备份失败只记日志、返回值恒 true，调用方以为迁移成功。 */
    suspend fun runOnce(context: Context): Boolean = withContext(Dispatchers.IO) {
        val marker = File(context.filesDir, MARKER)
        if (marker.isFile) return@withContext true
        runCatching {
            for (rel in backupTargets) {
                val src = File(context.filesDir, rel)
                if (src.isFile) {
                    val bak = File(context.filesDir, "$rel.bak")
                    if (!bak.isFile) src.copyTo(bak)
                }
            }
            marker.writeText(System.currentTimeMillis().toString())
            true
        }.onFailure { Log.w(TAG, "小说数据备份失败", it) }.getOrDefault(false)
    }
}
