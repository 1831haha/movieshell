package com.movieshell.player

/**
 * v1.3.25 合并 6（播放失败自动换内核＋硬解软解回退链）：播放失败分类（纯函数）。
 *
 * 确定性解码失败——同编解码器再试也解不出来（10-bit 硬解不支持、编解码器
 * 打不开、帧处理失败），同内核重拉与清 Referer 都是白烧时间（与 1.3.21
 * 的展开检测同款道理）；handlePlayFailure 认到就跳过这两步直换内核：
 * Media3 硬解失败 → libmpv 软解；libmpv 解码失败 → Media3 试一次。
 *
 * 判定信号分两路（错误串本身不带码，靠这两处补）：
 * - Media3：EngineHost 在错误串前拼 PlaybackException.errorCodeName
 *   （如 ERROR_CODE_DECODER_INIT_FAILED），码认最准；
 * - libmpv：END_FILE 只给“播失败了”不给原因，MpvPlayerManager 按
 *   mpv 日志尾里的解码失败行打 decode-failed 标记（格式与
 *   PLAYLIST_EXPANDED_MARKER 同款前缀）。
 * 文案兜底只认带 decoder/codec 的串，网络类（超时/404/403/解析失败）
 * 进不来；网络失败仍走完整重试链。
 */
object PlayFailureClass {

    /** libmpv 解码失败标记（MpvPlayerManager 打在错误串前）。 */
    const val DECODE_FAILED_MARKER = "decode-failed"

    /** Media3 的确定性解码错误码（EngineHost 拼码后可认）。 */
    private val MEDIA3_DECODE_CODES = setOf(
        "ERROR_CODE_DECODER_INIT_FAILED",
        "ERROR_CODE_DECODER_QUERY_FAILED",
        "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED",
    )

    /** 错误串里的解码失败文案（大小写不敏感）。 */
    private val DECODE_TEXT_HINTS = listOf(
        "decoder init failed",
        "decoder query failed",
        "video frame processing failed",
        "could not open decoder",
        "failed to initialize a decoder",
        "could not initialize decoder",
        "no video decoder",
        "unsupported video codec",
        "no_exceeds_capabilities",
        "no exceeds capabilities",
    )

    /** mpv 日志尾里的解码失败行（大小写不敏感）。 */
    private val MPV_LOG_DECODE_HINTS = listOf(
        "failed to initialize a decoder",
        "could not open decoder",
        "failed to open codec",
        "decoder init failed",
        "could not initialize decoder",
        "failed to init hardware decoder",
        "no video decoder",
        "unsupported video codec",
    )

    fun isDecodeFailure(msg: String): Boolean {
        if (msg.startsWith("$DECODE_FAILED_MARKER|")) return true
        if (MEDIA3_DECODE_CODES.any { it in msg }) return true
        val lower = msg.lowercase()
        return DECODE_TEXT_HINTS.any { it in lower }
    }

    fun mpvLogHasDecodeFailure(logTail: String): Boolean {
        val lower = logTail.lowercase()
        return MPV_LOG_DECODE_HINTS.any { it in lower }
    }
}
