package com.movieshell.player.danmaku

import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.ServicePluginManager
import com.movieshell.data.source.CmsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * v0.146.0：弹幕本地源——播放器弹幕钩子的一半。
 * 从本机运行中的 danmu-service（市场服务 id "danmu-service"）取弹幕：
 * GET http://127.0.0.1:<port>/danmaku?title=<标题>&episode=<集数>，
 * 回 JSON 数组 [{timeMs, text, color:"#RRGGBB", mode:"scroll"|"top"|"bottom"}]。
 * 服务未运行/超时/解析失败一律静默返回空（无本地弹幕不影响播放，更不影响内置源）。
 * 开关只有一个（DanmakuSettings.enabled），PlayerScreen 只在开关打开时取数；
 * 合并去重见 DanmakuSource.fetchMerged。
 */
object DanmakuLocalSource {
    private const val TAG = "Danmaku"
    private const val SERVICE_ID = "danmu-service"

    private val client = CmsApi.defaultClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class Item(
        val timeMs: Long = 0L,
        val text: String = "",
        val color: String = "#FFFFFF",
        val mode: String = "scroll",
    )

    suspend fun fetch(title: String, episode: Int = 1): List<Danmaku> = withContext(Dispatchers.IO) {
        try {
            val port = ServicePluginManager.portOf(SERVICE_ID) ?: return@withContext emptyList()
            val url = "http://127.0.0.1:$port/danmaku" +
                "?title=${java.net.URLEncoder.encode(title, "UTF-8")}&episode=$episode"
            val body: String = withTimeoutOrNull(15_000) {
                val req = Request.Builder().url(url).header("User-Agent", "movieshell/1.0").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            } ?: return@withContext emptyList()
            val items = json.decodeFromString<List<Item>>(body)
            val result = items.mapNotNull { it ->
                if (it.text.isBlank() || it.timeMs < 0) return@mapNotNull null
                val rgb = it.color.removePrefix("#").toLongOrNull(16)?.and(0xFFFFFF)
                    ?: 0xFFFFFFL
                val type = when (it.mode) {
                    "top" -> Danmaku.TYPE_TOP
                    "bottom" -> Danmaku.TYPE_BOTTOM
                    else -> Danmaku.TYPE_SCROLL
                }
                Danmaku(
                    text = it.text.take(60),
                    timeMs = it.timeMs,
                    color = (0xFF000000L or rgb).toInt(),
                    type = type,
                )
            }.sortedBy { it.timeMs }
            LogBus.i(TAG, "本地弹幕：$title E$episode -> ${result.size} 条")
            result
        } catch (e: Exception) {
            LogBus.w(TAG, "本地弹幕拉取失败：${e.message}")
            emptyList()
        }
    }
}
