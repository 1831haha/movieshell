package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTypeSnifferTest {


    @Test fun `url suffixes`() {
        assertEquals(MediaTypeSniffer.Kind.HLS, MediaTypeSniffer.inferFromUrl("https://a.com/v/index.m3u8?sign=1"))
        assertEquals(MediaTypeSniffer.Kind.DASH, MediaTypeSniffer.inferFromUrl("https://a.com/v/manifest.mpd"))
        assertEquals(MediaTypeSniffer.Kind.PROGRESSIVE, MediaTypeSniffer.inferFromUrl("https://a.com/v/movie.mp4"))
    }

    @Test fun `suffixless hls path or query hints`() {
        assertEquals(MediaTypeSniffer.Kind.HLS, MediaTypeSniffer.inferFromUrl("https://a.com/m3u8/abcdef"))
        assertEquals(MediaTypeSniffer.Kind.HLS, MediaTypeSniffer.inferFromUrl("https://a.com/hls/123/play"))
        assertEquals(MediaTypeSniffer.Kind.HLS, MediaTypeSniffer.inferFromUrl("https://a.com/play?type=m3u8&id=1"))
        assertNull(MediaTypeSniffer.inferFromUrl("https://a.com/play?id=1"))
    }

    @Test fun `content type`() {
        assertEquals(MediaTypeSniffer.Kind.HLS, MediaTypeSniffer.inferFromContentType("application/vnd.apple.mpegurl; charset=utf-8"))
        assertEquals(MediaTypeSniffer.Kind.HLS, MediaTypeSniffer.inferFromContentType("audio/x-mpegURL"))
        assertEquals(MediaTypeSniffer.Kind.DASH, MediaTypeSniffer.inferFromContentType("application/dash+xml"))
        assertEquals(MediaTypeSniffer.Kind.PROGRESSIVE, MediaTypeSniffer.inferFromContentType("video/mp4"))
        assertNull(MediaTypeSniffer.inferFromContentType(null))
    }

    @Test fun `magic bytes`() {
        assertEquals(MediaTypeSniffer.Kind.HLS, MediaTypeSniffer.inferFromBytes("\uFEFF#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
        assertEquals(MediaTypeSniffer.Kind.DASH, MediaTypeSniffer.inferFromBytes("<?xml version=\"1.0\"?><MPD xmlns=\"x\">".toByteArray()))
        assertNull(MediaTypeSniffer.inferFromBytes(byteArrayOf(0, 0, 0, 0x18)))
    }

    @Test fun `playlist request detection for ad filter`() {
        assertTrue(AdFilteringDataSource.isPlaylistRequest("https://a.com/x.m3u8", null))
        assertFalse(AdFilteringDataSource.isPlaylistRequest("https://a.com/seg1.ts", mapOf("Content-Type" to listOf("application/vnd.apple.mpegurl"))))
        assertTrue(AdFilteringDataSource.isPlaylistRequest("https://a.com/play?id=1", mapOf("Content-Type" to listOf("application/x-mpegURL"))))
        assertFalse(AdFilteringDataSource.isPlaylistRequest("https://a.com/play?id=2", mapOf("Content-Type" to listOf("video/mp2t"))))
    }
}
