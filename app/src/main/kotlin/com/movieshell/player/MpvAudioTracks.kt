package com.movieshell.player

import com.movieshell.player.TrackLanguage.AudioTrackInfo
import org.json.JSONArray

/**
 * v1.3.25 合并 7（DsPlayer 对照第 7 名）：libmpv 音轨切换——track-list 解析（纯函数部分）。
 *
 * mpv 的 track-list 是节点属性，getPropertyString("track-list") 直接给
 * JSON 数组串（沙箱 mpv 0.37 实测：每条含 id/type/src-id/title/lang/
 * selected/codec；audio 条目的 id 就是 aid 用的轨道号）。只取
 * type=="audio" 的条目，id 记成 "mpv:<id>"，与 Media3 的 "组下标:轨下标"
 * 互不串（EngineHost.selectAudioTrack 按实际引擎路由，只认自家命名空间）。
 * 坏 JSON/缺字段的条目跳过不炸——解复用器给什么形状都可能。
 */
object MpvAudioTracks {

    /** mpv 轨道 id 在 AudioTrackInfo 里的命名空间前缀。 */
    const val MPV_ID_PREFIX = "mpv:"

    /** track-list JSON 串 → 音轨列表。空/坏串返回空列表，不抛异常。 */
    fun parseTrackList(json: String?): List<AudioTrackInfo> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<AudioTrackInfo>()
        for (i in 0 until array.length()) {
            runCatching {
                val item = array.optJSONObject(i) ?: return@runCatching
                if (item.optString("type") != "audio") return@runCatching
                val id = item.optInt("id", -1)
                if (id < 0) return@runCatching
                out.add(
                    AudioTrackInfo(
                        id = MPV_ID_PREFIX + id,
                        language = item.optString("lang", ""),
                        label = item.optString("title", ""),
                        mimeType = item.optString("codec", ""),
                        selected = item.optBoolean("selected", false),
                    ),
                )
            }
        }
        return out
    }

    /** "mpv:<id>" → mpv 轨道号；不是 mpv 命名空间或解析失败返回 null。 */
    fun trackIdFromMpvId(id: String): Int? {
        if (!id.startsWith(MPV_ID_PREFIX)) return null
        return id.removePrefix(MPV_ID_PREFIX).toIntOrNull()
    }
}
