package com.movieshell.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v0.79.0：虎牙直播间列表 API（总站模式：分类→直播间列表→选房间播放）。
 *
 * - 列表接口（虎牙公开）：https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&gameId={gid}&page={n}
 *   不带 gameId = 全部；pageSize=120。
 * - 播放走网关：https://live.ottiptv.cc/huya/{房间号}（网关实时生成防盗链 token，URL 无时效性）。
 */
object HuyaApi {
    private const val TAG = "HuyaApi"

    /** 虎牙总站入口频道在插件里的 url 标记（LiveScreen 点它进直播间列表而非直接播放） */
    const val PORTAL_URL = "huya://portal"

    /** 播放网关 */
    fun playUrl(roomId: String): String = "https://live.ottiptv.cc/huya/$roomId"

    data class Category(val name: String, val gameId: Int?)

    /** v0.79.0：gameId 均经实测验证（2026-10-17，附在线房间数） */
    val CATEGORIES = listOf(
        Category("全部", null),
        Category("英雄联盟", 1), // 646 间在线
        Category("王者荣耀", 2336), // 601 间在线
        Category("和平精英", 3203), // 258 间在线
        Category("户外", 2165), // 271 间在线
        Category("颜值", 2168), // 24 间在线
    )

    data class Room(
        val roomId: String,
        val nick: String,
        val title: String,
        val cover: String,
        val avatar: String,
        val viewers: String,
        val gameName: String,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 拉取直播间列表（page 从 1 开始） */
    suspend fun fetchRooms(gameId: Int?, page: Int): List<Room> = withContext(Dispatchers.IO) {
        val url = buildString {
            append("https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage")
            if (gameId != null) append("&gameId=").append(gameId)
            append("&page=").append(page)
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("虎牙列表请求失败：${resp.code}")
            resp.body?.string().orEmpty()
        }
        val rooms = mutableListOf<Room>()
        runCatching {
            val datas = JSONObject(body).optJSONObject("data")?.optJSONArray("datas")
                ?: return@runCatching
            for (i in 0 until datas.length()) {
                val o = datas.optJSONObject(i) ?: continue
                val roomId = o.optString("profileRoom").trim()
                if (roomId.isBlank()) continue
                rooms += Room(
                    roomId = roomId,
                    nick = o.optString("nick"),
                    title = o.optString("introduction").ifBlank { o.optString("roomName") },
                    cover = o.optString("screenshot").let {
                        if (it.startsWith("//")) "https:$it" else it
                    },
                    avatar = o.optString("avatar180").let {
                        if (it.startsWith("//")) "https:$it" else it
                    },
                    viewers = formatViewers(o.optString("totalCount")),
                    gameName = o.optString("gameFullName"),
                )
            }
        }.onFailure {
            LogBus.w(TAG, "虎牙列表解析失败：${it.message}")
        }
        LogBus.i(TAG, "虎牙列表：gameId=$gameId page=$page，${rooms.size} 间")
        rooms
    }

    private fun formatViewers(raw: String): String {
        val n = raw.toLongOrNull() ?: return ""
        return when {
            n >= 10000 -> String.format("%.1f万", n / 10000.0)
            else -> n.toString()
        }
    }
}
