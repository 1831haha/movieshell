package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvAudioTracksTest {

    /** 沙箱 mpv 0.37 实测的 track-list 形状：2 条音频 + 1 条视频 + 1 条字幕。 */
    private val realTrackListJson = """
        [
          {"id":1,"type":"audio","src-id":1,"title":"English","lang":"eng","audio-channels":1,"default":true,"selected":true,"codec":"aac"},
          {"id":2,"type":"audio","src-id":2,"title":"日本語","lang":"jpn","audio-channels":1,"default":false,"selected":false,"codec":"aac"},
          {"id":3,"type":"video","src-id":3,"title":"","lang":"","selected":true,"codec":"h264"},
          {"id":4,"type":"sub","src-id":4,"title":"中字","lang":"chi","selected":false,"codec":"ass"}
        ]
    """.trimIndent()

    @Test
    fun parseTrackList_keepsOnlyAudioTracks() {
        val tracks = MpvAudioTracks.parseTrackList(realTrackListJson)
        assertEquals(2, tracks.size)
        assertEquals("mpv:1", tracks[0].id)
        assertEquals("eng", tracks[0].language)
        assertEquals("English", tracks[0].label)
        assertEquals("aac", tracks[0].mimeType)
        assertTrue(tracks[0].selected)
        assertEquals("mpv:2", tracks[1].id)
        assertEquals("jpn", tracks[1].language)
        assertEquals("日本語", tracks[1].label)
        assertTrue(!tracks[1].selected)
    }

    @Test
    fun parseTrackList_badInputReturnsEmpty() {
        assertTrue(MpvAudioTracks.parseTrackList(null).isEmpty())
        assertTrue(MpvAudioTracks.parseTrackList("").isEmpty())
        assertTrue(MpvAudioTracks.parseTrackList("not json").isEmpty())
        assertTrue(MpvAudioTracks.parseTrackList("[]").isEmpty())
        assertTrue(MpvAudioTracks.parseTrackList("{}").isEmpty())
    }

    @Test
    fun parseTrackList_skipsBadItemsButKeepsGoodOnes() {
        // 缺 id 的音频条目、缺 type 的条目跳过；缺 lang/title 的不跳（空串兜底）。
        val json = """
            [
              {"type":"audio","title":"无 id"},
              {"id":7,"title":"无 type"},
              {"id":8,"type":"audio","selected":true},
              {"id":9,"type":"audio","lang":"en","title":"English","codec":"ac3","selected":false}
            ]
        """.trimIndent()
        val tracks = MpvAudioTracks.parseTrackList(json)
        assertEquals(2, tracks.size)
        assertEquals("mpv:8", tracks[0].id)
        assertEquals("", tracks[0].language)
        assertEquals("", tracks[0].label)
        assertTrue(tracks[0].selected)
        assertEquals("mpv:9", tracks[1].id)
        assertEquals("ac3", tracks[1].mimeType)
    }

    @Test
    fun trackIdFromMpvId_namespaceOnly() {
        assertEquals(2, MpvAudioTracks.trackIdFromMpvId("mpv:2"))
        assertEquals(1, MpvAudioTracks.trackIdFromMpvId("mpv:1"))
        assertNull(MpvAudioTracks.trackIdFromMpvId("0:1"))
        assertNull(MpvAudioTracks.trackIdFromMpvId("mpv:abc"))
        assertNull(MpvAudioTracks.trackIdFromMpvId("mpv:"))
        assertNull(MpvAudioTracks.trackIdFromMpvId(""))
    }
}
