package com.movieshell.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.25 合并 6：PlayFailureClass 单测——解码失败判定只认解码信号，
 * 网络类失败（超时/404/解析失败/分片展开）一律不认。
 */
class PlayFailureClassTest {

    @Test
    fun `marker means decode failure`() {
        assertTrue(PlayFailureClass.isDecodeFailure("decode-failed|libmpv 播放失败（未出画面）"))
    }

    @Test
    fun `media3 decode error codes`() {
        assertTrue(
            PlayFailureClass.isDecodeFailure(
                "ERROR_CODE_DECODER_INIT_FAILED: Decoder init failed: OMX.google.h264.decoder",
            ),
        )
        assertTrue(
            PlayFailureClass.isDecodeFailure(
                "ERROR_CODE_DECODER_QUERY_FAILED: Failed to query decoders",
            ),
        )
        assertTrue(
            PlayFailureClass.isDecodeFailure(
                "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED: Video frame processing failed",
            ),
        )
    }

    @Test
    fun `decode text hints`() {
        assertTrue(PlayFailureClass.isDecodeFailure("Decoder init failed: OMX.MTK.VIDEO.DECODER.AVC"))
        assertTrue(PlayFailureClass.isDecodeFailure("Track not supported: NO_EXCEEDS_CAPABILITIES"))
        assertTrue(PlayFailureClass.isDecodeFailure("Could not open decoder for h264"))
        assertTrue(PlayFailureClass.isDecodeFailure("Unsupported video codec hevc"))
    }

    @Test
    fun `network and other failures are not decode failures`() {
        assertFalse(
            PlayFailureClass.isDecodeFailure(
                "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: Unable to connect",
            ),
        )
        assertFalse(PlayFailureClass.isDecodeFailure("ERROR_CODE_IO_BAD_HTTP_STATUS: 403"))
        assertFalse(PlayFailureClass.isDecodeFailure("Read timed out"))
        assertFalse(PlayFailureClass.isDecodeFailure("播放启动失败，请检查地址或切换线路"))
        assertFalse(
            PlayFailureClass.isDecodeFailure(
                "playlist-expanded|本地 m3u8 被当成播放列表展开，分片拉不到",
            ),
        )
        assertFalse(PlayFailureClass.isDecodeFailure("libmpv 播放失败（未出画面，起播后1200ms，eof=false）"))
    }

    @Test
    fun `mpv log decode lines`() {
        assertTrue(
            PlayFailureClass.mpvLogHasDecodeFailure(
                "[vd] Failed to initialize a decoder for codec 'h264'.\n[vd] Could not open decoder.",
            ),
        )
        assertTrue(
            PlayFailureClass.mpvLogHasDecodeFailure(
                "[ffmpeg] Failed to open codec 'hevc'.",
            ),
        )
    }

    @Test
    fun `mpv log network lines are not decode failures`() {
        assertFalse(
            PlayFailureClass.mpvLogHasDecodeFailure(
                "[ffmpeg] Failed to open https://cdn.example.com/seg0.ts: Connection refused\n" +
                    "[hls] Failed to open segment.",
            ),
        )
        assertFalse(PlayFailureClass.mpvLogHasDecodeFailure(""))
    }
}
