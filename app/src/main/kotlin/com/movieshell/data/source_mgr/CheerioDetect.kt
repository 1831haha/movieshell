package com.movieshell.data.source_mgr

/**
 * v1.3.33：识别 cheerio 类 drpy 规则（纯函数，可单测）——一级为 jsp:/json:/
 * html: 解析串，或用到了 pdfa/pdfh/cheerio（垫片没有 HTML 解析能力，这类
 * 规则的分类/列表本身跑不起来）。只认 drpy 规则文本，别处不用。
 * （原在 ui/sources/SourcesScreen.kt；探活 SourceProbe 也要用，故移到 data 层，
 * 导入与探活两道口共用同一份判定。）
 */
fun needsCheerioEngine(ruleText: String): Boolean {
    if (CHEERIO_FIRST_LEVEL.containsMatchIn(ruleText)) return true
    if (ruleText.contains("pdfa(") || ruleText.contains("pdfh(")) return true
    if (ruleText.contains("cheerio", ignoreCase = true)) return true
    return false
}

private val CHEERIO_FIRST_LEVEL =
    Regex("""["']?一级["']?\s*[:=]\s*["']\s*(jsp:|json:|html:)""")
