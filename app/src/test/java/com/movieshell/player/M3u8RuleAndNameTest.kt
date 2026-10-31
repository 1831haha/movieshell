package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3u8RuleAndNameTest {
    private fun seg(n: Int, name: (Int) -> String, d: Double = 6.0) =
        (0 until n).joinToString("") { "#EXTINF:$d,\n${name(it)}\n" }

    private val rule = Regex("#EXT-X-DISCONTINUITY\\r*\\n*#EXTINF:3\\.0,[\\s\\S]*?#EXT-X-DISCONTINUITY")

    @Test fun `rule regex removes ad block keeps boundary`() {
        val m = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n" + seg(5, { "a$it.ts" }) +
            "#EXT-X-DISCONTINUITY\n" + seg(2, { "ad$it.ts" }, 3.0) + "#EXT-X-DISCONTINUITY\n" +
            seg(5, { "b$it.ts" }) + "#EXT-X-ENDLIST\n"
        val out = M3u8AdFilter.applyRuleRegex(m, listOf(rule))
        assertFalse(out.contains("ad0.ts"))
        assertEquals(10, out.lines().count { it.startsWith("#EXTINF") })
        assertEquals(1, out.lines().count { it.startsWith("#EXT-X-DISCONTINUITY") })
    }

    @Test fun `rule regex keeps key lines`() {
        val m = "#EXTM3U\n" + seg(5, { "a$it.ts" }) + "#EXT-X-DISCONTINUITY\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n" +
            "#EXTINF:3.0,\nad.ts\n#EXT-X-DISCONTINUITY\n" + seg(5, { "b$it.ts" })
        val re = Regex("#EXT-X-DISCONTINUITY\\r*\\n*(#EXT-X-KEY[^\\n]*\\n)?#EXTINF:3\\.0,[\\s\\S]*?#EXT-X-DISCONTINUITY")
        val out = M3u8AdFilter.applyRuleRegex(m, listOf(re))
        assertFalse(out.contains("ad.ts"))
        assertTrue(out.contains("#EXT-X-KEY:METHOD=AES-128"))
    }

    @Test fun `rule regex safety floor`() {
        val m = "#EXTM3U\n" + seg(4, { "a$it.ts" })
        val out = M3u8AdFilter.applyRuleRegex(m, listOf(Regex("#EXTINF[\\s\\S]*")))
        assertEquals(m, out)
    }

    @Test fun `non m3u8 untouched`() {
        assertEquals("hello", M3u8AdFilter.applyRuleRegex("hello", listOf(rule)))
    }

    @Test fun `name shape and number`() {
        assertEquals("#", M3u8AdFilter.nameShape("https://a/x/000123.ts?k=1"))
        assertEquals(M3u8AdFilter.nameShape("https://a/seg9.ts"), M3u8AdFilter.nameShape("https://a/seg10.ts"))
        assertEquals("H:32", M3u8AdFilter.nameShape("https://a/0123456789abcdef0123456789abcdef.ts"))
        assertEquals(123L, M3u8AdFilter.nameNumber("https://a/x/seg-0123.ts"))
        assertEquals(null, M3u8AdFilter.nameNumber("https://a/x/intro.ts"))
    }

    @Test fun `sequence jump`() {
        val main = (100L..120L).toList()
        assertTrue(M3u8AdFilter.sequenceJump(listOf(9000L, 9001L), main, 2))
        assertFalse(M3u8AdFilter.sequenceJump(listOf(121L, 122L), main, 2))
        assertFalse(M3u8AdFilter.sequenceJump(listOf(9000L), (1L..3L).toList(), 1))
    }

    private fun playlist(mid: (Int) -> String) = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n" +
        seg(8, { "https://c.com/v/%06d.ts".format(it) }) +
        "#EXT-X-DISCONTINUITY\n" + seg(2, mid, 4.0) + "#EXT-X-DISCONTINUITY\n" +
        seg(8, { "https://c.com/v/%06d.ts".format(it + 10) }) + "#EXT-X-ENDLIST\n"

    @Test fun `filename shape signal plus short group drops it`() {
        val out = M3u8AdFilter.filter(playlist { "https://c.com/v/zq$it.ts" }, "https://c.com/v/index.m3u8")
        assertFalse(out.contains("zq0.ts"))
    }

    @Test fun `same shape short group is kept`() {
        val out = M3u8AdFilter.filter(playlist { "https://c.com/v/%06d.ts".format(it + 8) }, "https://c.com/v/index.m3u8")
        assertTrue(out.contains("000008.ts"))
    }
}
