package com.movieshell.player

import com.movieshell.player.TrackLanguage.AudioTrackInfo
import org.junit.Assert.*
import org.junit.Test

class TrackLanguageTest {
    private fun track(id: String, lang: String, selected: Boolean = false) =
        AudioTrackInfo(id, lang, "", "audio/mp4a-latm", selected)

    @Test fun normalizeStripsRegionAndAliases() {
        assertEquals("zh", TrackLanguage.normalizeLanguage("zh-Hans"))
        assertEquals("zh", TrackLanguage.normalizeLanguage("zh_CN"))
        assertEquals("zh", TrackLanguage.normalizeLanguage("chi"))
        assertEquals("zh", TrackLanguage.normalizeLanguage("zho"))
        assertEquals("zh", TrackLanguage.normalizeLanguage("cmn"))
        assertEquals("en", TrackLanguage.normalizeLanguage("eng"))
        assertEquals("ja", TrackLanguage.normalizeLanguage("jpn"))
        assertEquals("ko", TrackLanguage.normalizeLanguage("kor"))
        assertEquals("", TrackLanguage.normalizeLanguage("und"))
        assertEquals("", TrackLanguage.normalizeLanguage(""))
        assertEquals("yue", TrackLanguage.normalizeLanguage("yue"))
    }

    @Test fun matchesAcrossCodeVariants() {
        assertTrue(TrackLanguage.languageMatches("zh", "cmn"))
        assertTrue(TrackLanguage.languageMatches("zh", "chi"))
        assertTrue(TrackLanguage.languageMatches("en", "eng"))
        assertTrue(TrackLanguage.languageMatches("zh", "zh-Hans"))
        assertFalse(TrackLanguage.languageMatches("zh", "yue"))
        assertFalse(TrackLanguage.languageMatches("yue", "zh"))
        assertFalse(TrackLanguage.languageMatches("en", ""))
        assertFalse(TrackLanguage.languageMatches("", "en"))
    }

    @Test fun pickPreferredFindsFirstMatch() {
        val tracks = listOf(track("0:0", "eng"), track("0:1", "cmn"), track("0:2", "jpn"))
        assertEquals("0:1", TrackLanguage.pickPreferredAudioTrack(tracks, "zh")?.id)
        assertEquals("0:0", TrackLanguage.pickPreferredAudioTrack(tracks, "en")?.id)
        assertNull(TrackLanguage.pickPreferredAudioTrack(tracks, "ko"))
        assertNull(TrackLanguage.pickPreferredAudioTrack(tracks, ""))
    }

    @Test fun displayNamePrefersLabelThenLanguage() {
        assertEquals("国语", TrackLanguage.displayName(track("0:0", "cmn").copy(label = "国语")))
        assertEquals("中文", TrackLanguage.displayName(track("0:0", "chi")))
        assertEquals("粤语", TrackLanguage.displayName(track("0:0", "yue")))
        assertEquals("音轨", TrackLanguage.displayName(track("0:0", "")))
        assertEquals("音轨", TrackLanguage.displayName(track("0:0", "deu")))
        assertEquals("音轨", TrackLanguage.displayName(track("0:0", "fra")))
    }
}
