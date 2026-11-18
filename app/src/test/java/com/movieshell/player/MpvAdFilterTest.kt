package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun resolveUris_keepsBaseQueryOnSameHostSegments() {
        val content = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10.0,\nseg/0001.ts\n#EXTINF:10.0,\nseg/0002.ts\n"
        val out = MpvAdFilter.resolveUris(
            content,
            "https://cdn.example.com/vod/abc/index.m3u8?auth_key=1794993106-abc-0-def",
        )
        assertEquals(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10.0,\nhttps://cdn.example.com/vod/abc/seg/0001.ts?auth_key=1794993106-abc-0-def\n" +
                "#EXTINF:10.0,\nhttps://cdn.example.com/vod/abc/seg/0002.ts?auth_key=1794993106-abc-0-def\n",
            out,
        )
    }

    @Test
    fun resolveUris_noQueryAddedToCrossHostSegment() {
        val content = "#EXTM3U\n#EXTINF:10.0,\nhttps://other.example.com/a.ts\n"
        val out = MpvAdFilter.resolveUris(
            content,
            "https://cdn.example.com/vod/index.m3u8?auth_key=xyz",
        )
        assertEquals(
            "#EXTM3U\n#EXTINF:10.0,\nhttps://other.example.com/a.ts\n",
            out,
        )
    }

    @Test
    fun resolveUris_segmentOwnQueryNotClobbered() {
        val content = "#EXTM3U\n#EXTINF:10.0,\nseg/0001.ts?t=abc\n"
        val out = MpvAdFilter.resolveUris(
            content,
            "https://cdn.example.com/vod/index.m3u8?auth_key=xyz",
        )
        assertEquals(
            "#EXTM3U\n#EXTINF:10.0,\nhttps://cdn.example.com/vod/seg/0001.ts?t=abc\n",
            out,
        )
    }

    @Test
    fun resolveUris_keyUriKeepsBaseQuery() {
        val content = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:10.0,\n0001.ts\n"
        val out = MpvAdFilter.resolveUris(
            content,
            "https://cdn.example.com/vod/index.m3u8?auth_key=xyz",
        )
        assertEquals(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://cdn.example.com/vod/key.bin?auth_key=xyz\"\n" +
                "#EXTINF:10.0,\nhttps://cdn.example.com/vod/0001.ts?auth_key=xyz\n",
            out,
        )
    }

    @Test
    fun pickBestVariant_keepsBaseQuery() {
        val content = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\nhigh.m3u8\n"
        assertEquals(
            "https://cdn.example.com/vod/high.m3u8?auth_key=xyz",
            MpvAdFilter.pickBestVariant(content, "https://cdn.example.com/vod/master.m3u8?auth_key=xyz"),
        )
    }

    @Test
    fun pickBestVariant_mediaPlaylistReturnsNull() {
        val content = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10.0,\n0001.ts\n"
        assertNull(MpvAdFilter.pickBestVariant(content, "https://cdn.example.com/vod/index.m3u8"))
    }

    @Test
    fun stripLeadingJunk_leadingBlankLineRemoved() {
        assertEquals(
            "#EXTM3U\n#EXTINF:4.0,\nindex0.ts\n",
            MpvAdFilter.stripLeadingJunk("\n#EXTM3U\n#EXTINF:4.0,\nindex0.ts\n"),
        )
    }

    @Test
    fun stripLeadingJunk_bomAndSpacesRemoved() {
        assertEquals(
            "#EXTM3U\n",
            MpvAdFilter.stripLeadingJunk("\uFEFF  \n#EXTM3U\n"),
        )
    }

    @Test
    fun stripLeadingJunk_cleanFileUntouched() {
        val content = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:4.0,\nindex0.ts\n"
        assertEquals(content, MpvAdFilter.stripLeadingJunk(content))
    }

    @Test
    fun ensureHlsMarkers_minimalPlaylistGetsBoth() {
        val content = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:9.6,\nindex0.ts\n#EXTINF:10.2,\nindex1.ts\n#EXT-X-ENDLIST\n"
        assertEquals(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:11\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXTINF:9.6,\nindex0.ts\n#EXTINF:10.2,\nindex1.ts\n#EXT-X-ENDLIST\n",
            MpvAdFilter.ensureHlsMarkers(content),
        )
    }

    @Test
    fun ensureHlsMarkers_existingMarkersKeptEndlistAppended() {
        val content = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:5\n#EXTINF:10.0,\nindex0.ts\n"
        assertEquals(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:5\n" +
                "#EXTINF:10.0,\nindex0.ts\n#EXT-X-ENDLIST\n",
            MpvAdFilter.ensureHlsMarkers(content),
        )
    }

    @Test
    fun ensureHlsMarkers_minimalWithoutEndlistGetsMarkersAndEndlist() {
        val content = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:9.6,\nindex0.ts\n#EXTINF:10.2,\nindex1.ts\n"
        assertEquals(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:11\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXTINF:9.6,\nindex0.ts\n#EXTINF:10.2,\nindex1.ts\n#EXT-X-ENDLIST\n",
            MpvAdFilter.ensureHlsMarkers(content),
        )
    }

    @Test
    fun ensureHlsMarkers_endlistNotDuplicated() {
        val content = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10.0,\nindex0.ts\n#EXT-X-ENDLIST\n"
        val out = MpvAdFilter.ensureHlsMarkers(content)
        assertEquals(1, out.lines().count { it.trim() == "#EXT-X-ENDLIST" })
    }

    @Test
    fun ensureHlsMarkers_masterPlaylistUntouched() {
        val content = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=5000000\n high.m3u8\n"
        assertEquals(content, MpvAdFilter.ensureHlsMarkers(content))
    }

    @Test
    fun isDefinitelyNotPlaylist_suffixlessHlsIsNotExcluded() {
        assertEquals(false, MpvAdFilter.isDefinitelyNotPlaylist("https://cdn.example.com/play.php?id=123"))
        assertEquals(false, MpvAdFilter.isDefinitelyNotPlaylist("https://cdn.example.com/m3u8/abc"))
    }

    @Test
    fun isDefinitelyNotPlaylist_progressiveSuffixExcluded() {
        assertEquals(true, MpvAdFilter.isDefinitelyNotPlaylist("https://cdn.example.com/v/abc.mp4"))
        assertEquals(true, MpvAdFilter.isDefinitelyNotPlaylist("https://cdn.example.com/v/abc.ts"))
        assertEquals(true, MpvAdFilter.isDefinitelyNotPlaylist("https://cdn.example.com/v/abc.m4s"))
    }

    @Test
    fun isDefinitelyNotPlaylist_m3u8SuffixNotExcluded() {
        assertEquals(false, MpvAdFilter.isDefinitelyNotPlaylist("https://cdn.example.com/vod/index.m3u8"))
    }

    @Test
    fun isDefinitelyNotPlaylist_nonHttpExcluded() {
        assertEquals(true, MpvAdFilter.isDefinitelyNotPlaylist("file:///sdcard/index.m3u8"))
        assertEquals(true, MpvAdFilter.isDefinitelyNotPlaylist("magnet:?xt=urn:btih:abc"))
    }

    @Test
    fun isHttpUrl_onlyHttpHttps() {
        assertEquals(true, MpvAdFilter.isHttpUrl("https://cdn.example.com/a.ts"))
        assertEquals(true, MpvAdFilter.isHttpUrl("http://cdn.example.com/a.ts"))
        assertEquals(false, MpvAdFilter.isHttpUrl("file:///sdcard/a.ts"))
        assertEquals(false, MpvAdFilter.isHttpUrl("ftp://cdn.example.com/a.ts"))
        assertEquals(false, MpvAdFilter.isHttpUrl("seg/0001.ts"))
    }

    @Test
    fun resolveUris_fileSegmentDroppedWithExtinf() {
        val content = "#EXTM3U\n#EXTINF:10.0,\nhttps://cdn.example.com/a.ts\n" +
            "#EXTINF:10.0,\nfile:///sdcard/evil.ts\n#EXTINF:10.0,\nhttps://cdn.example.com/c.ts\n"
        val out = MpvAdFilter.resolveUris(content, "https://cdn.example.com/vod/index.m3u8")
        assertEquals(
            "#EXTM3U\n#EXTINF:10.0,\nhttps://cdn.example.com/a.ts\n" +
                "#EXTINF:10.0,\nhttps://cdn.example.com/c.ts\n",
            out,
        )
    }

    @Test
    fun resolveUris_ftpSegmentDropped() {
        val content = "#EXTM3U\n#EXTINF:10.0,\nftp://cdn.example.com/a.ts\n#EXTINF:10.0,\nhttps://cdn.example.com/b.ts\n"
        val out = MpvAdFilter.resolveUris(content, "https://cdn.example.com/vod/index.m3u8")
        assertEquals(
            "#EXTM3U\n#EXTINF:10.0,\nhttps://cdn.example.com/b.ts\n",
            out,
        )
    }

    @Test
    fun resolveUris_fileKeyUriLineDropped() {
        val content = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"file:///sdcard/key.bin\"\n" +
            "#EXTINF:10.0,\nhttps://cdn.example.com/a.ts\n"
        val out = MpvAdFilter.resolveUris(content, "https://cdn.example.com/vod/index.m3u8")
        assertEquals(
            "#EXTM3U\n#EXTINF:10.0,\nhttps://cdn.example.com/a.ts\n",
            out,
        )
    }

    @Test
    fun cacheKey_sameUrlDifferentCookieDifferentKey() {
        val url = "https://cdn.example.com/vod/abc/index.m3u8"
        val a = MpvAdFilter.cacheKey(url, mapOf("Cookie" to "__pugs=aaa", "Referer" to "https://x/"))
        val b = MpvAdFilter.cacheKey(url, mapOf("Cookie" to "__pugs=bbb", "Referer" to "https://x/"))
        assertNotEquals(a, b)
    }

    @Test
    fun cacheKey_sameHeadersSameKeyRegardlessOfOrder() {
        val url = "https://cdn.example.com/vod/abc/index.m3u8"
        val a = MpvAdFilter.cacheKey(url, mapOf("Cookie" to "c=1", "Referer" to "https://x/"))
        val b = MpvAdFilter.cacheKey(url, mapOf("Referer" to "https://x/", "Cookie" to "c=1"))
        assertEquals(a, b)
    }

    @Test
    fun cacheKey_ignoresUnrelatedHeaders() {
        val url = "https://cdn.example.com/vod/abc/index.m3u8"
        val a = MpvAdFilter.cacheKey(url, mapOf("Cookie" to "c=1"))
        val b = MpvAdFilter.cacheKey(url, mapOf("Cookie" to "c=1", "User-Agent" to "UA/1.0", "X-Trace" to "zzz"))
        assertEquals(a, b)
    }

    @Test
    fun cacheKey_differentUrlDifferentKey() {
        val a = MpvAdFilter.cacheKey("https://cdn.example.com/a.m3u8", mapOf("Cookie" to "c=1"))
        val b = MpvAdFilter.cacheKey("https://cdn.example.com/b.m3u8", mapOf("Cookie" to "c=1"))
        assertNotEquals(a, b)
    }
}
