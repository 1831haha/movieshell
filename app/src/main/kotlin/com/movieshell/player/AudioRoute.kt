package com.movieshell.player

import com.movieshell.util.safeIndex

import android.content.Context
import com.movieshell.data.model.Episode
import com.movieshell.data.model.Site
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.MusicApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * v0.294.35：听书 / 纯音频影视源改用音乐播放器播放（布局、歌词区、后台播放、通知控制）。
 * 各集变成播放队列，上一首 / 下一首即上一集 / 下一集；每集播放时再走影视源解析。
 */
object AudioRoute {
    const val VOD_SOURCE = "vod"

    private val AUDIO_EXT = Regex("""\.(mp3|m4a|aac|flac|ogg|oga|opus|wav|wma|ape|amr|m4b)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val AUDIO_WORDS = listOf("听书", "有声", "评书", "相声", "广播", "播客", "电台", "音频", "小说", "曲艺", "戏曲")

    /** 地址看起来是纯音频 */
    fun isAudioUrl(url: String): Boolean {
        val u = url.substringBefore('$').trim()
        if (u.startsWith("magnet:", true)) return false
        return AUDIO_EXT.containsMatchIn(u.substringBefore("?").let { if (AUDIO_EXT.containsMatchIn(it)) it else u })
    }

    /** 站点名 / 分类名像听书源 */
    fun isAudioCategory(vararg names: String?): Boolean =
        names.any { n -> !n.isNullOrBlank() && AUDIO_WORDS.any { n.contains(it) } }

    private data class Session(val site: Site?, val byUrl: Map<String, Episode>, val first: Pair<String, String>?)
    @Volatile private var session: Session? = null

    /** 打开音乐播放页的请求（AppNavGraph 订阅） */
    private val _open = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openRequests: SharedFlow<Unit> = _open

    /** 视频播放器发现没有视频轨（Media3 onTracksChanged） */
    private val _noVideo = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val noVideo: SharedFlow<String> = _noVideo
    fun reportNoVideo(url: String) { _noVideo.tryEmit(url) }

    fun play(
        context: Context,
        site: Site?,
        title: String,
        poster: String,
        episodes: List<Episode>,
        startIndex: Int,
        resolvedStartUrl: String?,
    ) {
        if (episodes.isEmpty()) return
        val start = startIndex.safeIndex(episodes.size)
        session = Session(
            site,
            episodes.associateBy { it.url },
            resolvedStartUrl?.let { episodes[start].url to it },
        )
        val tracks = episodes.mapIndexed { i, e ->
            MusicApi.Track(
                id = "vod:" + e.url.hashCode().toString(16) + ":" + i,
                name = e.name.ifBlank { "第${i + 1}集" },
                artist = title,
                album = title,
                picId = poster,
                urlId = e.url,
                source = VOD_SOURCE,
            )
        }
        LogBus.i("AudioRoute", "纯音频内容，改用音乐播放器：$title（${tracks.size} 集，从第 ${start + 1} 集）")
        EngineHost.stop()
        MusicPlayer.playList(context, tracks, start)
        _open.tryEmit(Unit)
    }

    suspend fun resolve(context: Context, track: MusicApi.Track): String? {
        val s = session
        s?.first?.let { (ep, url) -> if (ep == track.urlId) { session = s.copy(first = null); return url } }
        val ep = s?.byUrl?.get(track.urlId) ?: Episode(url = track.urlId)
        return runCatching { resolvePlayUrl(context, s?.site, ep) }
            .onFailure { LogBus.w("AudioRoute", "解析失败：${it.message}") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: track.urlId.takeIf { it.startsWith("http") }
    }
}
