package com.movieshell.data.source.subtitle

import com.movieshell.data.source.subtitle.OnlineSubtitle
import org.junit.Assert.*
import org.junit.Test

class SubtitleLanguageTest {
    private fun sub(sid: String, lang: String) =
        OnlineSubtitle(sourceId = "subhd", sid = sid, title = "t$sid", language = lang)

    @Test fun matchesSimplified() {
        assertTrue(SubtitleLanguage.matchesLanguage("简体中文", "zh-Hans"))
        assertTrue(SubtitleLanguage.matchesLanguage("中文", "zh-Hans"))
        assertTrue(SubtitleLanguage.matchesLanguage("中英双语", "zh-Hans"))
        assertFalse(SubtitleLanguage.matchesLanguage("繁体中文", "zh-Hans"))
        assertFalse(SubtitleLanguage.matchesLanguage("英文", "zh-Hans"))
    }

    @Test fun matchesTraditionalAndGenericChinese() {
        assertTrue(SubtitleLanguage.matchesLanguage("繁体中文", "zh-Hant"))
        assertTrue(SubtitleLanguage.matchesLanguage("繁體中文", "zh-Hant"))
        assertFalse(SubtitleLanguage.matchesLanguage("简体中文", "zh-Hant"))
        assertTrue(SubtitleLanguage.matchesLanguage("简体中文", "zh"))
        assertTrue(SubtitleLanguage.matchesLanguage("繁体中文", "zh"))
        assertFalse(SubtitleLanguage.matchesLanguage("英文", "zh"))
    }

    @Test fun matchesEnglishJapaneseKorean() {
        assertTrue(SubtitleLanguage.matchesLanguage("英文", "en"))
        assertTrue(SubtitleLanguage.matchesLanguage("中英双语", "en"))
        assertTrue(SubtitleLanguage.matchesLanguage("English", "en"))
        assertTrue(SubtitleLanguage.matchesLanguage("日语", "ja"))
        assertTrue(SubtitleLanguage.matchesLanguage("韩语", "ko"))
        assertFalse(SubtitleLanguage.matchesLanguage("日语", "ko"))
        assertFalse(SubtitleLanguage.matchesLanguage("", "en"))
        assertFalse(SubtitleLanguage.matchesLanguage("简体中文", ""))
    }

    @Test fun sortPutsPreferredFirstAndKeepsOrder() {
        val results = listOf(
            sub("1", "英文"),
            sub("2", "简体中文"),
            sub("3", "繁体中文"),
            sub("4", "中英双语"),
        )
        val sorted = SubtitleLanguage.sortByPreferredLanguage(results, "zh-Hans")
        assertEquals(listOf("2", "4", "1", "3"), sorted.map { it.sid })
    }

    @Test fun sortIsNoopWithoutPreferenceOrMatch() {
        val results = listOf(sub("1", "英文"), sub("2", "日语"))
        assertEquals(results, SubtitleLanguage.sortByPreferredLanguage(results, ""))
        assertEquals(results, SubtitleLanguage.sortByPreferredLanguage(results, "ko"))
    }
}
