package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3u8AdFilterTest {

    private fun playlist(vararg groups: List<Pair<Double, String>>): String = buildString {
        appendLine("#EXTM3U"); appendLine("#EXT-X-TARGETDURATION:10"); appendLine("#EXT-X-MEDIA-SEQUENCE:0")
        groups.forEachIndexed { gi, g ->
            if (gi > 0) appendLine("#EXT-X-DISCONTINUITY")
            g.forEach { (d, u) -> appendLine("#EXTINF:$d,"); appendLine(u) }
        }
        appendLine("#EXT-X-ENDLIST")
    }

    private fun main(n: Int, start: Int) = (start until start + n).map { 10.0 to "https://cdn.main.com/v/1000k/hls/seg$it.ts" }

    @Test fun `ad group from other host inserted mid playlist is removed`() {
        val ad = listOf(3.0 to "https://ads.other.net/x/a1.ts", 3.0 to "https://ads.other.net/x/a2.ts")
        val out = M3u8AdFilter.filter(playlist(main(6, 0), ad, main(6, 6)))
        assertFalse(out.contains("ads.other.net"))
        assertTrue(out.contains("seg0.ts") && out.contains("seg11.ts"))
    }

    @Test fun `same directory short group is kept (only one signal)`() {
        val g = listOf(6.0 to "https://cdn.main.com/v/1000k/hls/x1.ts")
        val src = playlist(main(6, 0), g, main(6, 6))
        val out = M3u8AdFilter.filter(src)
        assertTrue(out.contains("x1.ts"))
    }

    @Test fun `directory signal catches mildly short ad from other host`() {
        // 时长偏离不够（6s vs 10s），旧版只命中 1 个信号会漏；目录不同补上第 2 个信号
        val g = listOf(6.0 to "https://ad.cdn9.cc/p/x1.ts")
        val out = M3u8AdFilter.filter(playlist(main(6, 0), g, main(6, 6)))
        assertFalse(out.contains("ad.cdn9.cc"))
    }

    @Test fun `playlist without discontinuity untouched`() {
        val src = playlist(main(8, 0))
        assertEquals(src, M3u8AdFilter.filter(src))
    }

    @Test fun `encrypted group untouched`() {
        val src = buildString {
            appendLine("#EXTM3U")
            main(5, 0).forEach { (d, u) -> appendLine("#EXTINF:$d,"); appendLine(u) }
            appendLine("#EXT-X-DISCONTINUITY")
            appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"k.key\"")
            appendLine("#EXTINF:2.0,"); appendLine("https://ads.other.net/a.ts")
            appendLine("#EXT-X-DISCONTINUITY")
            appendLine("#EXT-X-KEY:METHOD=NONE")
            main(5, 5).forEach { (d, u) -> appendLine("#EXTINF:$d,"); appendLine(u) }
        }
        assertTrue(M3u8AdFilter.filter(src).contains("ads.other.net"))
    }

    @Test fun `dirOf resolves relative and absolute`() {
        assertEquals("cdn.a.com/v/hls", M3u8AdFilter.dirOf("https://cdn.a.com/v/hls/s1.ts?t=1", ""))
        assertEquals("cdn.a.com/v/hls", M3u8AdFilter.dirOf("s1.ts", "https://cdn.a.com/v/hls/index.m3u8"))
        assertEquals("cdn.a.com/ad", M3u8AdFilter.dirOf("/ad/x.ts", "https://cdn.a.com/v/hls/index.m3u8"))
    }
}
