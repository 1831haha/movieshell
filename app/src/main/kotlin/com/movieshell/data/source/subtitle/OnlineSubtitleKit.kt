package com.movieshell.data.source.subtitle

import android.content.Context
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PlayerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 在线字幕下载应用单入口（v0.28.0）。
 *
 * 下载→存到 filesDir/subtitles（持久化）→按剧记忆（复用 PlayerSettings 字幕记忆）。
 * 播放器侧拿到 fileUrl 后走现有 currentSubtitleUrl/replayWithSubtitle 链路，
 * 样式/偏移/双引擎加载全部复用，不另起一套。
 */
object OnlineSubtitleKit {

    /** 下载并落盘，返回可直接喂播放器的 file:// 地址；失败抛异常（调用方 Toast）。 */
    suspend fun downloadAndSave(
        context: Context,
        source: SubtitleSource,
        item: OnlineSubtitle,
        vodId: String?,
    ): String = withContext(Dispatchers.IO) {
        val (content, ext) = source.download(item)
        val dir = File(context.filesDir, "subtitles").also { it.mkdirs() }
        val safeExt = ext.ifBlank { "srt" }
        val file = File(dir, "${source.id}_${item.sid}.$safeExt")
        file.writeText(content, Charsets.UTF_8)
        val url = "file://${file.absolutePath}"
        if (!vodId.isNullOrBlank()) {
            PlayerSettings.setSubtitleFor(context, vodId, url)
        }
        LogBus.i("Subtitle", "在线字幕已下载：${file.name}（${content.length}字）")
        url
    }
}
