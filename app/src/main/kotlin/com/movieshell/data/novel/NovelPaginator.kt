package com.movieshell.data.novel

/**
 * v0.95.0 真分页引擎（对标阅读 3.0 分页）：纯函数，可单元测试。
 *
 * 按字号/行高/屏宽算每页字符数；分页时保持段落完整，段内超长才在标点处断开。
 * 进度换算用字符偏移（与 ReadingProgress.charOffset 对齐）。
 */
object NovelPaginator {

    /** 每页字符数：innerW/innerH 为内容区像素（已扣 padding）。 */
    fun charsPerPage(
        fontPx: Float,
        lineHeight: Float,
        innerW: Float,
        innerH: Float,
    ): Int {
        val cpl = if (innerW > 0 && fontPx > 0) {
            (innerW / (fontPx * 0.95f)).toInt().coerceAtLeast(10)
        } else 30
        val lpp = if (innerH > 0 && fontPx > 0) {
            (innerH / (fontPx * lineHeight)).toInt().coerceAtLeast(5)
        } else 20
        return (cpl * lpp).coerceAtLeast(200)
    }

    /** 正文切页：段落优先完整，段内超长在后 2/3 区间的标点处断开。 */
    fun paginate(text: String, charsPerPage: Int): List<String> {
        val cpp = charsPerPage.coerceAtLeast(200)
        if (text.isBlank()) return emptyList()
        val pages = mutableListOf<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotEmpty()) {
                pages.add(cur.toString().trimEnd())
                cur.clear()
            }
        }
        for (raw in text.split("\n\n")) {
            var para = raw.trim()
            if (para.isEmpty()) continue
            while (para.isNotEmpty()) {
                val sep = if (cur.isEmpty()) 0 else 2 // 段间 "\n\n"
                val room = cpp - cur.length - sep
                if (room <= 0) {
                    flush()
                    continue
                }
                if (para.length <= room) {
                    if (sep > 0) cur.append("\n\n")
                    cur.append(para)
                    para = ""
                } else {
                    var cut = room
                    val at = para.substring(0, room).indexOfLast { it in "。！？；…」』" }
                    if (at > room / 3) cut = at + 1
                    if (sep > 0) cur.append("\n\n")
                    cur.append(para.substring(0, cut))
                    para = para.substring(cut)
                    flush()
                }
            }
            // 段落结束：本页已用 85% 则换页，否则下段续写
            if (cur.length >= (cpp * 0.85f).toInt()) flush()
        }
        flush()
        return pages
    }

    /** 第 page 页在原文中的起始字符偏移（进度保存用）。 */
    fun pageStartOffset(pages: List<String>, page: Int): Int {
        var off = 0
        for (i in 0 until page.coerceIn(0, pages.size)) off += pages[i].length + 2
        return off
    }

    /** 字符偏移落在第几页（进度恢复用）。 */
    fun pageForOffset(pages: List<String>, offset: Int): Int {
        var acc = 0
        for (i in pages.indices) {
            acc += pages[i].length + 2
            if (offset < acc) return i
        }
        return (pages.size - 1).coerceAtLeast(0)
    }
}
