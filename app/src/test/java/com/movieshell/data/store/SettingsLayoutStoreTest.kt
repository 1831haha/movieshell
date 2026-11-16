package com.movieshell.data.store

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.2.7：设置页活布局——布局表的搬家与补齐逻辑（纯函数，不碰 Context/磁盘）。
 * 重点锁两条：①已搬走的行重启后不回默认容器、不重影；②搬家不改原表。
 * v1.2.13：TMDB Key 成第 6 个可移项（默认在 page 末尾）；以后再加项，期望值里的
 * 默认布局与补齐结果要跟着改，别只改实现不改测试。
 * v1.2.14 B片：默认播放方向 / 转场动画 / 首选音频语言 / 首选字幕语言成第 7–10 个
 * 可移项（默认在 page 末尾，顺序同 ALL_ITEMS）。
 */
class SettingsLayoutStoreTest {

    private val default = SettingsLayoutStore.DEFAULT_LAYOUT
    private val main = SettingsLayoutStore.MAIN_PLAYBACK
    private val page = SettingsLayoutStore.PAGE_PLAYBACK

    @Test fun normalizeNullIsDefault() {
        assertEquals(default, SettingsLayoutStore.normalize(null))
    }

    @Test fun normalizeEmptyStringIsDefault() {
        assertEquals(default, SettingsLayoutStore.normalize(""))
    }

    @Test fun normalizeKeepsSavedOrder() {
        val raw = """{"main:playback":[],"page:playback_settings":["max_parallel","engine","autoplay","adfilter","gravity"]}"""
        val got = SettingsLayoutStore.normalize(raw)
        assertEquals(emptyList<String>(), got[main])
        assertEquals(
            listOf(
                "max_parallel", "engine", "autoplay", "adfilter", "gravity", "tmdb_key",
                "def_orientation", "transition", "audio_lang", "subtitle_lang",
            ),
            got[page],
        )
    }

    @Test fun normalizeDropsUnknownIdsAndRestoresMissing() {
        // 存里只有 engine 在 page，缺了其余四项、还多了一个野 id。
        val raw = """{"page:playback_settings":["engine","bogus"]}"""
        val got = SettingsLayoutStore.normalize(raw)
        assertEquals(listOf("gravity"), got[main])
        assertEquals(
            listOf(
                "engine", "autoplay", "adfilter", "max_parallel", "tmdb_key",
                "def_orientation", "transition", "audio_lang", "subtitle_lang",
            ),
            got[page],
        )
    }

    @Test fun normalizeDeduplicatesAcrossContainers() {
        // 存坏了：同一行写在两个容器里，只留第一次出现的位置。
        val raw = """{"main:playback":["gravity"],"page:playback_settings":["gravity","engine"]}"""
        val got = SettingsLayoutStore.normalize(raw)
        assertEquals(listOf("gravity"), got[main])
        assertEquals(
            listOf(
                "engine", "autoplay", "adfilter", "max_parallel", "tmdb_key",
                "def_orientation", "transition", "audio_lang", "subtitle_lang",
            ),
            got[page],
        )
    }

    @Test fun moveWithinContainerReorders() {
        val moved = SettingsLayoutStore.moveInLayout(default, "adfilter", page, 0)
        assertEquals(
            listOf(
                "adfilter", "engine", "autoplay", "max_parallel", "tmdb_key",
                "def_orientation", "transition", "audio_lang", "subtitle_lang",
            ),
            moved[page],
        )
        assertEquals(listOf("gravity"), moved[main])
    }

    @Test fun moveAcrossContainersAppendsToEnd() {
        val moved = SettingsLayoutStore.moveInLayout(default, "gravity", page, Int.MAX_VALUE)
        assertEquals(emptyList<String>(), moved[main])
        assertEquals(
            listOf(
                "engine", "autoplay", "adfilter", "max_parallel", "tmdb_key",
                "def_orientation", "transition", "audio_lang", "subtitle_lang", "gravity",
            ),
            moved[page],
        )
    }

    @Test fun moveAcrossContainersInsertsAtIndex() {
        val moved = SettingsLayoutStore.moveInLayout(default, "gravity", page, 1)
        assertEquals(
            listOf(
                "engine", "gravity", "autoplay", "adfilter", "max_parallel", "tmdb_key",
                "def_orientation", "transition", "audio_lang", "subtitle_lang",
            ),
            moved[page],
        )
    }

    @Test fun moveBackToMain() {
        val emptied = SettingsLayoutStore.moveInLayout(default, "gravity", page, 0)
        val back = SettingsLayoutStore.moveInLayout(emptied, "engine", main, 0)
        assertEquals(listOf("engine"), back[main])
        assertEquals(
            listOf(
                "gravity", "autoplay", "adfilter", "max_parallel", "tmdb_key",
                "def_orientation", "transition", "audio_lang", "subtitle_lang",
            ),
            back[page],
        )
    }

    @Test fun moveUnknownIsNoop() {
        assertEquals(default, SettingsLayoutStore.moveInLayout(default, "bogus", page, 0))
        assertEquals(default, SettingsLayoutStore.moveInLayout(default, "gravity", "nowhere", 0))
    }

    @Test fun moveDoesNotMutateInput() {
        val before = default[page]!!
        SettingsLayoutStore.moveInLayout(default, "engine", main, 0)
        assertEquals(
            listOf(
                "engine", "autoplay", "adfilter", "max_parallel", "tmdb_key",
                "def_orientation", "transition", "audio_lang", "subtitle_lang",
            ),
            before,
        )
    }
}
