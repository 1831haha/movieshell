package com.movieshell.data.source.subtitle

// 与 OnlineSubtitle 同包（不同模块 subtitle-api），Kotlin 本不需要 import，
// 但 check_imports.py 只认 import 行，显式写上过门禁。
import com.movieshell.data.source.subtitle.OnlineSubtitle

/**
 * v1.2.14 B片（3）：首选字幕语言匹配——纯函数部分。
 *
 * SubHD 搜出来的 language 是自由文本标签（"简体中文""繁体中文""英文"
 * "中英双语""日语"…），不是标准语言码，只能按关键词匹配。规则保守：
 * 只认下面列出的关键词，认不出的标签不算匹配、不乱排。
 * 匹配只用来给搜索结果排序（匹配的排前面），不自动下载——自动下字幕
 * 会在起播时多一次联网，且用户没点过就是没选过。
 */
object SubtitleLanguage {

    /** 首选字幕语言选项：code 为存 PlayerSettings 的值，空=不指定。 */
    val SUBTITLE_LANGUAGE_OPTIONS: List<Pair<String, String>> = listOf(
        "" to "不指定",
        "zh-Hans" to "简体中文",
        "zh-Hant" to "繁体中文",
        "zh" to "中文（不限简繁）",
        "en" to "英文",
        "ja" to "日语",
        "ko" to "韩语",
    )

    /** 首选语言选项的中文名（设置页副标题用）。 */
    fun optionLabel(code: String): String =
        SUBTITLE_LANGUAGE_OPTIONS.firstOrNull { it.first == code }?.second ?: "不指定"

    /**
     * 一条字幕的语言标签是否符合用户首选。
     * 首选为空返回 false（不指定 = 不排序、不偏好）。
     */
    fun matchesLanguage(label: String, preferred: String): Boolean {
        if (preferred.isBlank()) return false
        val t = label.trim()
        if (t.isEmpty()) return false
        val hasSimplified = t.contains("简体")
        val hasTraditional = t.contains("繁体") || t.contains("繁體")
        val hasChinese = t.contains("中文") || t.contains("中英") || hasSimplified || hasTraditional
        val lower = t.lowercase()
        val hasEnglish = t.contains("英文") || t.contains("英语") || t.contains("中英") ||
            t.contains("双语") || lower.contains("english")
        val hasJapanese = t.contains("日语") || t.contains("日文") || t.contains("日本") ||
            lower.contains("japanese")
        val hasKorean = t.contains("韩语") || t.contains("韩文") || t.contains("韩国") ||
            lower.contains("korean")
        return when (preferred) {
            "zh-Hans" -> hasSimplified || (hasChinese && !hasTraditional)
            "zh-Hant" -> hasTraditional
            "zh" -> hasChinese
            "en" -> hasEnglish
            "ja" -> hasJapanese
            "ko" -> hasKorean
            else -> false
        }
    }

    /**
     * 搜索结果按首选语言排序：匹配的排前面，其余保持原顺序（稳定排序，
     * 不打乱 SubHD 自身的排序）；首选为空或没有匹配项时原样返回。
     */
    fun sortByPreferredLanguage(
        results: List<OnlineSubtitle>,
        preferred: String,
    ): List<OnlineSubtitle> {
        if (preferred.isBlank()) return results
        if (results.none { matchesLanguage(it.language, preferred) }) return results
        return results.sortedWith(compareByDescending { matchesLanguage(it.language, preferred) })
    }
}
