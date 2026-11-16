package com.movieshell.data.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.18：直链验活判定（纯函数）——死链在解析层就拦下，不再交给播放器 5 秒后报错。
 */
class LxMusicVerifyTest {

    @Test fun audio200IsAlive() {
        assertTrue(LxMusicSourceEngine.isAliveMusicResponse(200, "audio/mpeg", null, "12345"))
    }

    @Test fun range206IsAlive() {
        assertTrue(
            LxMusicSourceEngine.isAliveMusicResponse(206, "audio/flac", "bytes 0-1/12345678", "2"),
        )
    }

    @Test fun missingContentTypeIsAlive() {
        assertTrue(LxMusicSourceEngine.isAliveMusicResponse(200, null, null, "12345"))
    }

    @Test fun octetStreamIsAlive() {
        assertTrue(
            LxMusicSourceEngine.isAliveMusicResponse(
                200, "application/octet-stream", null, "12345",
            ),
        )
    }

    @Test fun notFoundIsDead() {
        assertFalse(LxMusicSourceEngine.isAliveMusicResponse(404, "text/html", null, "123"))
    }

    @Test fun forbiddenIsDead() {
        assertFalse(LxMusicSourceEngine.isAliveMusicResponse(403, null, null, null))
    }

    @Test fun htmlErrorPageIsDead() {
        assertFalse(
            LxMusicSourceEngine.isAliveMusicResponse(
                200, "text/html; charset=utf-8", null, "123",
            ),
        )
    }

    @Test fun jsonErrorIsDead() {
        assertFalse(
            LxMusicSourceEngine.isAliveMusicResponse(200, "application/json", null, "123"),
        )
    }

    @Test fun plainTextErrorIsDead() {
        assertFalse(LxMusicSourceEngine.isAliveMusicResponse(200, "text/plain", null, "123"))
    }

    @Test fun zeroTotalIsDead() {
        assertFalse(
            LxMusicSourceEngine.isAliveMusicResponse(206, "audio/mpeg", "bytes 0-1/0", "2"),
        )
    }

    @Test fun zeroLengthIsDead() {
        assertFalse(LxMusicSourceEngine.isAliveMusicResponse(200, "audio/mpeg", null, "0"))
    }

    @Test fun imageIsDead() {
        assertFalse(LxMusicSourceEngine.isAliveMusicResponse(200, "image/png", null, "123"))
    }
}
