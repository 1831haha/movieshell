package com.movieshell.data.store

import com.movieshell.domain.model.PlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.8 A2：按频道偏好表（live_channel_prefs）纯函数单测，不碰 SharedPreferences。
 * 形态：{频道 id: {engine, scale, lastUsed}}，缺省字段不存 = 跟随全局；
 * 整表上限 200 条，超了按 lastUsed 最旧淘汰。
 */
class ChannelPrefTest {

    private val idA = "m3u:央视|https://example.com/cctv1.m3u8"
    private val idB = "m3u:卫视|https://example.com/hunan.m3u8"

    @Test
    fun `parse empty or bad json yields empty table`() {
        assertTrue(LiveStore.parseChannelPrefsJson(null).isEmpty())
        assertTrue(LiveStore.parseChannelPrefsJson("").isEmpty())
        assertTrue(LiveStore.parseChannelPrefsJson("not json").isEmpty())
        assertTrue(LiveStore.parseChannelPrefsJson("{}").isEmpty())
    }

    @Test
    fun `round trip keeps engine scale lastUsed`() {
        val table = mapOf(
            idA to ChannelPref(PlayerEngine.LIBMPV, 1, 1_700_000_000_000L),
            idB to ChannelPref(PlayerEngine.MEDIA3, null, 1_700_000_000_001L),
        )
        val parsed = LiveStore.parseChannelPrefsJson(
            LiveStore.serializeChannelPrefsJson(table),
        )
        assertEquals(ChannelPref(PlayerEngine.LIBMPV, 1, 1_700_000_000_000L), parsed[idA])
        assertEquals(ChannelPref(PlayerEngine.MEDIA3, null, 1_700_000_000_001L), parsed[idB])
    }

    @Test
    fun `unset fields are not stored and parse back as follow-global`() {
        val raw = LiveStore.serializeChannelPrefsJson(
            mapOf(idA to ChannelPref(engine = null, scale = 2, lastUsed = 42L)),
        )
        // engine 缺省：JSON 里没有 engine 键，不编一个默认值出来。
        assertTrue(!raw.contains("LIBMPV") && !raw.contains("MEDIA3"))
        assertEquals(ChannelPref(engine = null, scale = 2, lastUsed = 42L), LiveStore.parseChannelPrefsJson(raw)[idA])
    }

    @Test
    fun `bad entry is skipped not fatal`() {
        val raw = """{"$idA":{"engine":"LIBMPV","scale":1,"lastUsed":7},"bad":{"engine":"NO_SUCH_ENGINE","scale":9}}"""
        val parsed = LiveStore.parseChannelPrefsJson(raw)
        assertEquals(ChannelPref(PlayerEngine.LIBMPV, 1, 7L), parsed[idA])
        // 坏条目两项都无效，不进表。
        assertNull(parsed["bad"])
    }

    @Test
    fun `scale out of range is dropped`() {
        val raw = """{"$idA":{"scale":5,"lastUsed":7}}"""
        assertNull(LiveStore.parseChannelPrefsJson(raw)[idA])
        val rawOk = """{"$idA":{"engine":"MEDIA3","scale":5,"lastUsed":7}}"""
        assertEquals(ChannelPref(PlayerEngine.MEDIA3, null, 7L), LiveStore.parseChannelPrefsJson(rawOk)[idA])
    }

    @Test
    fun `prune keeps newest 200 and drops oldest lastUsed`() {
        val table = (0 until 201).associate { i ->
            "ch$i" to ChannelPref(PlayerEngine.MEDIA3, null, i.toLong())
        }
        val pruned = LiveStore.pruneChannelPrefs(table)
        assertEquals(LiveStore.MAX_CHANNEL_PREFS, pruned.size)
        // lastUsed 最旧（ch0）被淘汰，最新 200 条都在。
        assertNull(pruned["ch0"])
        assertEquals(ChannelPref(PlayerEngine.MEDIA3, null, 200L), pruned["ch200"])
        assertEquals(ChannelPref(PlayerEngine.MEDIA3, null, 1L), pruned["ch1"])
    }

    @Test
    fun `prune does nothing at or under limit`() {
        val table = (0 until 200).associate { i ->
            "ch$i" to ChannelPref(null, 1, i.toLong())
        }
        assertEquals(200, LiveStore.pruneChannelPrefs(table).size)
        assertEquals(table, LiveStore.pruneChannelPrefs(table))
    }

    @Test
    fun `touch refreshes lastUsed and keeps engine and scale`() {
        val table = mapOf(idA to ChannelPref(PlayerEngine.LIBMPV, 1, 100L))
        val touched = LiveStore.touchChannelPref(table, idA, now = 200L)
        assertEquals(ChannelPref(PlayerEngine.LIBMPV, 1, 200L), touched[idA])
    }

    @Test
    fun `touch without entry creates nothing`() {
        val table = mapOf(idA to ChannelPref(PlayerEngine.MEDIA3, null, 100L))
        assertEquals(table, LiveStore.touchChannelPref(table, idB, now = 200L))
    }

    @Test
    fun `touch keeps watched channel ahead of stale one at prune`() {
        // 常看的台（lastUsed 被起播刷新）不被淘汰，久未看的先被淘汰。
        val table = (0 until 200).associate { i ->
            "ch$i" to ChannelPref(PlayerEngine.MEDIA3, null, i.toLong())
        } + mapOf(idA to ChannelPref(PlayerEngine.LIBMPV, 1, 0L))
        val touched = LiveStore.touchChannelPref(table, idA, now = 1_000L)
        val pruned = LiveStore.pruneChannelPrefs(touched)
        assertEquals(200, pruned.size)
        assertEquals(ChannelPref(PlayerEngine.LIBMPV, 1, 1_000L), pruned[idA])
        assertNull(pruned["ch0"])
    }
}
