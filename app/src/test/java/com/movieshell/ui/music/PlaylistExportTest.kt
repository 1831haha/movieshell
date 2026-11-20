package com.movieshell.ui.music

import com.movieshell.data.source.MusicApi
import com.movieshell.data.store.MusicPlaylist
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.3.33：歌单导出（应用内部 JSON 格式，只承诺本应用备份/迁移，不承诺跨平台导出）。 */
class PlaylistExportTest {

    @Test
    fun buildPlaylistExportJson_roundTrip() {
        val playlist = MusicPlaylist(
            id = "1",
            name = "我的歌单",
            tracks = listOf(
                MusicApi.Track(id = "10", name = "歌 A", artist = "歌手 A", album = "专辑 A", picId = "p1", urlId = "10", source = "wy"),
                MusicApi.Track(id = "20", name = "歌 B", artist = "歌手 B", album = "", picId = "", urlId = "20", source = "wy"),
            ),
            createdAt = 0L,
        )
        val json = Json.parseToJsonElement(buildPlaylistExportJson(playlist)).jsonObject
        assertEquals("movieshell", json["app"]!!.jsonPrimitive.content)
        assertEquals("playlist", json["kind"]!!.jsonPrimitive.content)
        assertEquals(1, json["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("我的歌单", json["name"]!!.jsonPrimitive.content)
        val tracks = json["tracks"]!!.jsonArray
        assertEquals(2, tracks.size)
        assertEquals("歌 A", tracks[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("20", tracks[1].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildPlaylistExportJson_emptyPlaylist() {
        val playlist = MusicPlaylist(id = "2", name = "空歌单", tracks = emptyList())
        val json = Json.parseToJsonElement(buildPlaylistExportJson(playlist)).jsonObject
        assertEquals("空歌单", json["name"]!!.jsonPrimitive.content)
        assertEquals(0, json["tracks"]!!.jsonArray.size)
    }
}
