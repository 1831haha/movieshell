package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpvAdFilterTest {

    @Test
    fun resolveUris_relativeSegmentBecomesAbsolute() {
        val content = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10.0,\nseg/0001.ts\n#EXTINF:10.0,\nseg/0002.ts\n"
        val out = MpvAdFilter.resolveUris(content, "https://cdn.example.com/vod/abc/index.m3u8")
        assertEquals(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10.0,\nhttps://cdn.example.com/vod/abc/seg/0001.ts\n" +
                "#EXTINF:10.0,\nhttps://cdn.example.com/vod/abc/seg/0002.ts\n",
            out,
        )
    }

    @Test
    fun resolveUris_absoluteUnchangedAndKeyUriResolved() {
        val content = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:10.0,\nhttps://cdn.example.com/a.ts\n"
        val out = MpvAdFilter.resolveUris(content, "https://cdn.example.com/vod/index.m3u8")
        assertEquals(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://cdn.example.com/vod/key.bin\"\n" +
                "#EXTINF:10.0,\nhttps://cdn.example.com/a.ts\n",
            out,
        )
    }

    @Test
    fun resolveUris_parentTraversalResolved() {
        val content = "#EXTM3U\n#EXTINF:10.0,\n../shared/0001.ts\n"
        val out = MpvAdFilter.resolveUris(content, "https://cdn.example.com/vod/abc/index.m3u8")
        assertEquals(
            "#EXTM3U\n#EXTINF:10.0,\nhttps://cdn.example.com/vod/shared/0001.ts\n",
            out,
        )
    }

    @Test
    fun pickBestVariant_highestBandwidth() {
        val content = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\nmid.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\nhigh.m3u8\n"
        assertEquals(
            "https://cdn.example.com/vod/high.m3u8",
            MpvAdFilter.pickBestVariant(content, "https://cdn.example.com/vod/master.m3u8"),
        )
    }

    @Test
    fun pickBestVariant_mediaPlaylistReturnsNull() {
        val content = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10.0,\n0001.ts\n"
        assertNull(MpvAdFilter.pickBestVariant(content, "https://cdn.example.com/vod/index.m3u8"))
    }
}
