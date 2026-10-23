package com.movieshell.data.source.subtitle

import android.content.Context
import com.movieshell.data.repo.DetailSiteHolder
import com.movieshell.data.source.subtitle.OnlineSubtitle
import com.movieshell.data.source.subtitle.SubtitleSource
import com.movieshell.data.repo.DrpySourceManager
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.PhpSourceManager
import com.movieshell.data.repo.PlayerSettings
import com.movieshell.data.repo.PySourceManager
import com.movieshell.player.PlaySession
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
            // v0.186.0：在线字幕也写新 key，与播放页读的 key 一致才不会新旧混写。
            // 源 key 口径与播放页快照一致：订阅站取站点 key，否则取当前激活规则名；
            // 集数取 PlaySession 现值：播放页内下载时就是当前集；详情页内下载时
            // 是自动播放已起播的那集，尚未起播时为会话初值。
            val sourceKey = DetailSiteHolder.site?.key?.takeIf { it.isNotBlank() }
                ?: DrpySourceManager.activeRuleName()?.takeIf { it.isNotBlank() }
                ?: PySourceManager.activeRuleName()?.takeIf { it.isNotBlank() }
                ?: PhpSourceManager.activeRuleName()?.takeIf { it.isNotBlank() }
            PlayerSettings.setSubtitleFor(
                context, sourceKey, vodId, PlaySession.episodeIndex, url,
            )
        }
        LogBus.i("Subtitle", "在线字幕已下载：${file.name}（${content.length}字）")
        url
    }
}
