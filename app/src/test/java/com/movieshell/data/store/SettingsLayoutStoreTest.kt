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
 * v1.2.16：数据组 12 项成第 11–22 个可移项（默认全在 main:data，顺序同设置页原顺序）。
 * v1.3.25 合并 5：Anime4K 画质增强成第 23 个可移项（默认在 page 末尾，顺序同 ALL_ITEMS）。
 * v1.4.6 A1.1：首屏诊断成第 24 个可移项（默认在 page 末尾、anime4k 之后，顺序同 ALL_ITEMS）。
 * v1.2.19：数据行进数据设置子页面（moveDataRowIntoDataPage 锁住），子页面只认
 * 自家行——串页的 move 直接拦下，存坏了的布局由 normalize 搬回主组。
 */
class SettingsLayoutStoreTest {

    private val default = SettingsLayoutStore.DEFAULT_LAYOUT
    private val main = SettingsLayoutStore.MAIN_PLAYBACK
    private val page = SettingsLayoutStore.PAGE_PLAYBACK
    private val dataPage = SettingsLayoutStore.PAGE_DATA

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
                "def_orientation", "player_transition", "audio_lang", "subtitle_lang", "anime4k", "first_screen_trace",
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
                "def_orientation", "player_transition", "audio_lang", "subtitle_lang", "anime4k", "first_screen_trace",
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
                "def_orientation", "player_transition", "audio_lang", "subtitle_lang", "anime4k", "first_screen_trace",
            ),
            got[page],
        )
    }

    @Test fun moveWithinContainerReorders() {
        val moved = SettingsLayoutStore.moveInLayout(default, "adfilter", page, 0)
        assertEquals(
            listOf(
                "adfilter", "engine", "autoplay", "max_parallel", "tmdb_key",
                "def_orientation", "player_transition", "audio_lang", "subtitle_lang", "anime4k", "first_screen_trace",
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
                "def_orientation", "player_transition", "audio_lang", "subtitle_lang", "anime4k", "first_screen_trace", "gravity",
            ),
            moved[page],
        )
    }

    @Test fun moveAcrossContainersInsertsAtIndex() {
        val moved = SettingsLayoutStore.moveInLayout(default, "gravity", page, 1)
        assertEquals(
            listOf(
                "engine", "gravity", "autoplay", "adfilter", "max_parallel", "tmdb_key",
                "def_orientation", "player_transition", "audio_lang", "subtitle_lang", "anime4k", "first_screen_trace",
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
                "def_orientation", "player_transition", "audio_lang", "subtitle_lang", "anime4k", "first_screen_trace",
            ),
            back[page],
        )
    }

    @Test fun normalizePutsDataItemsInMainData() {
        val data = SettingsLayoutStore.DATA_ITEMS
        assertEquals(12, data.size)
        val got = SettingsLayoutStore.normalize(null)
        assertEquals(data, got[SettingsLayoutStore.MAIN_DATA])
    }

    @Test fun moveDataRowIntoPlaybackGroup() {
        val moved = SettingsLayoutStore.moveInLayout(
            default, "data_webdav", SettingsLayoutStore.MAIN_PLAYBACK, Int.MAX_VALUE,
        )
        assertEquals(listOf("gravity", "data_webdav"), moved[SettingsLayoutStore.MAIN_PLAYBACK])
        assertEquals(
            SettingsLayoutStore.DATA_ITEMS - "data_webdav",
            moved[SettingsLayoutStore.MAIN_DATA],
        )
    }

    @Test fun movePlaybackRowIntoDataGroup() {
        val moved = SettingsLayoutStore.moveInLayout(
            default, "gravity", SettingsLayoutStore.MAIN_DATA, 0,
        )
        assertEquals(emptyList<String>(), moved[SettingsLayoutStore.MAIN_PLAYBACK])
        assertEquals(
            listOf("gravity") + SettingsLayoutStore.DATA_ITEMS,
            moved[SettingsLayoutStore.MAIN_DATA],
        )
    }

    @Test fun moveDataRowIntoDataPage() {
        // v1.2.19：数据行拖进数据设置子页面（播放行不进这页）。
        val moved = SettingsLayoutStore.moveInLayout(
            default, "data_webdav", dataPage, Int.MAX_VALUE,
        )
        assertEquals(listOf("data_webdav"), moved[dataPage])
        assertEquals(
            SettingsLayoutStore.DATA_ITEMS - "data_webdav",
            moved[SettingsLayoutStore.MAIN_DATA],
        )
    }

    @Test fun moveDataRowIntoPlaybackPageRefused() {
        // v1.2.19：数据行进不了播放设置子页面，原表不动。
        val moved = SettingsLayoutStore.moveInLayout(
            default, "data_webdav", page, Int.MAX_VALUE,
        )
        assertEquals(default, moved)
    }

    @Test fun movePlaybackRowIntoDataPageRefused() {
        // v1.2.19：播放行进不了数据设置子页面，原表不动。
        val moved = SettingsLayoutStore.moveInLayout(
            default, "gravity", dataPage, Int.MAX_VALUE,
        )
        assertEquals(default, moved)
    }

    @Test fun normalizeRepairsMisplacedRows() {
        // v1.2.19：存坏了——数据行在播放页、播放行在数据页，都搬回各自的主组。
        val raw = """{"page:playback_settings":["engine","data_webdav"],"page:data_settings":["gravity"]}"""
        val got = SettingsLayoutStore.normalize(raw)
        assertEquals(
            listOf(
                "engine", "autoplay", "adfilter", "max_parallel", "tmdb_key",
                "def_orientation", "player_transition", "audio_lang", "subtitle_lang", "anime4k", "first_screen_trace",
            ),
            got[page],
        )
        assertEquals(emptyList<String>(), got[dataPage])
        assertEquals(listOf("gravity"), got[main])
        // 串页的数据行搬回数据组（补在末尾），12 项一项不少。
        val data = got[SettingsLayoutStore.MAIN_DATA]!!
        assertEquals(12, data.size)
        assertEquals("data_webdav", data.last())
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
                "def_orientation", "player_transition", "audio_lang", "subtitle_lang", "anime4k", "first_screen_trace",
            ),
            before,
        )
    }
}
