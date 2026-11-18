package com.movieshell.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleKitShiftTest {

    @Test fun `shiftSrtTs shifts and clamps at zero`() {
        assertEquals("01:02:04,456", SubtitleKit.shiftSrtTs("01:02:03,456", 1000))
        assertEquals("00:00:00,000", SubtitleKit.shiftSrtTs("00:00:00,500", -1000))
    }

    @Test fun `shiftSrtTs leaves unparseable timestamps untouched`() {
        val bad = "99999999999999999999:00:00,000"
        assertEquals(bad, SubtitleKit.shiftSrtTs(bad, 1000))
    }

    @Test fun `shiftVttTs handles short and long forms`() {
        assertEquals("00:01:03.000", SubtitleKit.shiftVttTs("01:02.500", 500))
        assertEquals("01:02:04.456", SubtitleKit.shiftVttTs("01:02:03.456", 1000))
        assertEquals("xx:02:03.456", SubtitleKit.shiftVttTs("xx:02:03.456", 1000))
    }

    @Test fun `shiftAssTs shifts centiseconds and leaves bad input untouched`() {
        assertEquals("1:02:04.45", SubtitleKit.shiftAssTs("1:02:03.45", 1000))
        assertEquals("xx:02:03.45", SubtitleKit.shiftAssTs("xx:02:03.45", 1000))
    }
}
