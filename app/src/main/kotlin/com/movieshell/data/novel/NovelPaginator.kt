package com.movieshell.data.novel

/**
 * v0.95.0 真分页引擎（对标阅读 3.0 分页）：纯函数，可单元测试。
 *
 * 按字号/行高/屏宽算每页字符数；分页时保持段落完整，段内超长才在标点处断开。
 * 进度换算用字符偏移（与 ReadingProgress.charOffset 对齐）。
 */
object NovelPaginator {

    /**
     * v0.102.0 P2-1（降级方案）：真实测量（rememberTextMeasurer 逐行量）需要重写整个分页算法，
     * 把逐行测量搬进 composition，主线程代价大且会打乱现有字符偏移进度语义，故降级为
     * 按中英文混排比例微调字符宽度系数。
     */
    /** 每页字符数：innerW/innerH 为内容区像素（已扣 padding）。 */
    fun charsPerPage(
        fontPx: Float,
        lineHeight: Float,
        innerW: Float,
        innerH: Float,
        sample: String = "",
    ): Int {
        // v0.102.0：按正文样本的中英文混排比例估算平均字宽，替代固定 0.95 系数
        val widthRatio = avgCharWidthRatio(sample)
        val cpl = if (innerW > 0 && fontPx > 0) {
            (innerW / (fontPx * widthRatio)).toInt().coerceAtLeast(10)
        } else 30
        val lpp = if (innerH > 0 && fontPx > 0) {
            (innerH / (fontPx * lineHeight)).toInt().coerceAtLeast(5)
        } else 20
        return (cpl * lpp).coerceAtLeast(200)
    }

    /**
     * v0.102.0 P2-1：正文样本的平均字符宽度系数（相对字号）。
     * CJK/全角按 1.0，ASCII 半角按 0.5，空格按 0.3；只看前 2000 字符。
     * 空样本返回 0.95（与旧固定系数一致，老行为不变）。
     */
    fun avgCharWidthRatio(sample: String): Float {
        var sum = 0f
        var n = 0
        val limit = sample.length.coerceAtMost(2000)
        for (i in 0 until limit) {
            val c = sample[i]
            if (c == '\n' || c == '\r') continue
            sum += when {
                c == ' ' || c == '\t' -> 0.3f
                c.code <= 0x7F -> 0.5f
                else -> 1.0f
            }
            n++
        }
        if (n == 0) return 0.95f
        return (sum / n).coerceIn(0.5f, 1.0f)
    }

    /** 可断行标点（断在这些字符之后，避免标点落到行首）。 */
    private const val BREAK_CHARS = "。！？；…」』，、：）》"

    /** 行尾避让：左引号/左括号不能落在一行末尾。 */
    private const val OPEN_CHARS = "「『（〈《\"'‘’"

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
                    // v0.102.0：断句标点集扩展到逗号/冒号/右括号，宁可在标点后断、不让标点落行首
                    val at = para.substring(0, room).indexOfLast { it in BREAK_CHARS }
                    if (at > room / 3) cut = at + 1
                    // 避头微调：行尾不留左引号/左括号，否则退一格
                    if (cut > 1 && para[cut - 1] in OPEN_CHARS && cut - 1 > room / 3) cut--
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
