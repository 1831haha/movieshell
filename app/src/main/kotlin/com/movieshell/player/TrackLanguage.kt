package com.movieshell.player

/**
 * v1.2.14 B片（3）：音轨信息与首选音频语言匹配——纯函数部分。
 *
 * 音轨语言在各容器里的写法并不统一：HLS 写 "en"/"zh"，MP4 的 mdhd 语言码
 * 是三字母 "eng"/"chi"/"zho"，有的复用器写 "cmn"（普通话）或 "und"
 * （未标注）。匹配前先归一化到二字母码，再比较；归一化表只收已见过的
 * 码，不编造没出现过的映射。
 */
object TrackLanguage {

    /** 一条音频轨道（PlayerManager 从 Media3 Tracks 映射而来，与 UI 共用）。 */
    data class AudioTrackInfo(
        /** 轨道 id："组下标:轨下标"，PlayerManager.selectAudioTrack 认这个。 */
        val id: String,
        /** 原始语言码（容器里怎么写就怎么记，不改写）。 */
        val language: String,
        /** 容器给的轨道名（可能为空）。 */
        val label: String,
        val mimeType: String,
        val selected: Boolean,
    )

    /** 首选音频语言选项：code 为存 PlayerSettings 的值，空=不指定。 */
    val AUDIO_LANGUAGE_OPTIONS: List<Pair<String, String>> = listOf(
        "" to "不指定",
        "zh" to "中文",
        "yue" to "粤语",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어",
    )

    /** 三字母/别名码 → 二字母码；未列出的码原样返回，不猜。 */
    private val LANG_ALIASES = mapOf(
        "eng" to "en",
        "chi" to "zh",
        "zho" to "zh",
        "cmn" to "zh",
        "jpn" to "ja",
        "kor" to "ko",
        "und" to "",
    )

    /**
     * 语言码归一化：去空白转小写、去掉地区后缀（zh-Hans/zh_CN → zh）、
     * 三字母/别名码换二字母码。"und"（未标注）归一化为空串。
     */
    fun normalizeLanguage(code: String): String {
        val base = code.trim().lowercase().split('-', '_').firstOrNull().orEmpty()
        if (base.isEmpty()) return ""
        return LANG_ALIASES[base] ?: base
    }

    /** 两码是否同一语言（都先归一化；任一为空不算匹配）。 */
    fun languageMatches(preferred: String, trackLanguage: String): Boolean {
        val p = normalizeLanguage(preferred)
        val t = normalizeLanguage(trackLanguage)
        if (p.isEmpty() || t.isEmpty()) return false
        return p == t
    }

    /**
     * 在轨道列表里挑首选语言的那条：首选为空或无匹配返回 null（调用方保持
     * 当前选中，不乱切）。多条同语言时取第一条（通常是默认音轨）。
     */
    fun pickPreferredAudioTrack(
        tracks: List<AudioTrackInfo>,
        preferredLanguage: String,
    ): AudioTrackInfo? {
        if (preferredLanguage.isBlank()) return null
        return tracks.firstOrNull { languageMatches(preferredLanguage, it.language) }
    }

    /** 轨道在 UI 里的显示名：容器名优先，其次语言名，最后回落到原始码。 */
    fun displayName(track: AudioTrackInfo): String {
        if (track.label.isNotBlank()) return track.label
        val lang = normalizeLanguage(track.language)
        return when (lang) {
            "zh" -> "中文"
            "yue" -> "粤语"
            "en" -> "English"
            "ja" -> "日本語"
            "ko" -> "한국어"
            else -> "音轨"
        }
    }

    /** 首选语言选项的中文名（设置页副标题用）。 */
    fun optionLabel(code: String): String =
        AUDIO_LANGUAGE_OPTIONS.firstOrNull { it.first == code }?.second ?: "不指定"
}
