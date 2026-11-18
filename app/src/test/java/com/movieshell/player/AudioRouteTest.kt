package com.movieshell.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteTest {

    @Test fun `isAudioUrl matches extension in path only`() {
        assertTrue(AudioRoute.isAudioUrl("https://cdn.example.com/a.mp3?x=1"))
        assertTrue(AudioRoute.isAudioUrl("https://cdn.example.com/a.MP3"))
        assertTrue(AudioRoute.isAudioUrl("https://cdn.example.com/a.m4a#frag"))
        assertTrue(AudioRoute.isAudioUrl("https://cdn.example.com/a.flac"))
    }

    @Test fun `isAudioUrl ignores audio suffix in query or fragment`() {
        assertFalse(AudioRoute.isAudioUrl("https://cdn.example.com/v.mp4?file=.mp3"))
        assertFalse(AudioRoute.isAudioUrl("https://cdn.example.com/v.mp4?x=.mp3"))
        assertFalse(AudioRoute.isAudioUrl("https://cdn.example.com/v.mp4#.mp3"))
        assertFalse(AudioRoute.isAudioUrl("https://cdn.example.com/v.mp4"))
    }

    @Test fun `isAudioUrl rejects magnet links`() {
        assertFalse(AudioRoute.isAudioUrl("magnet:?xt=urn:btih:abc&dn=track.mp3"))
    }
}
