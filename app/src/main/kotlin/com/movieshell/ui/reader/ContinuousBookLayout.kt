package com.movieshell.ui.reader

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import com.movieshell.data.model.Chapter

/**
 * v0.244.0：整本连续阅读——全局分页模型。
 *
 * 旧分页（NovelPaginator.paginate）按章切 List<String>，每章页码重算，
 * 章界处必然断开；本模型改用实测行高打包：标题与正文进入同一排版序列，
 * 一页可同时装上一章结尾、下一章标题、下一章开头，页边界只由实际布局
 * 容量决定。标题只是普通文本块（isTitle 仅用于显示样式），不触发任何
 * 特殊分页规则：不强制换页、不独占一页、不置顶、不做标题避让。
 *
 * 字符偏移口径：全部用 Chapter.content 的原始字符下标，不再复用旧
 * pageStartOffset 每页 +2 的分隔符口径；ReadingProgress（章节 + 偏移）
 * 不迁移，靠本文件的映射函数换算全局页。
 */

/** 全局页里的一个片段：一段标题，或一段连续正文字符区间。 */
data class GlobalTextFragment(
    val chapterIndex: Int,
    val isTitle: Boolean,
    /** 正文片段在 Chapter.content 里的 [startOffset, endOffset)；标题片段恒为 0, 0。 */
    val startOffset: Int,
    val endOffset: Int,
)

/** 一张全局页：片段按阅读顺序排列，跨章、跨标题均可共处一页。 */
data class GlobalPage(
    val fragments: List<GlobalTextFragment>,
) {
    /** 本页第一个可落点位置：标题片段返回该章 0，否则首个正文片段起点。 */
    fun firstPosition(): Pair<Int, Int> {
        val f = fragments.firstOrNull() ?: return 0 to 0
        return if (f.isTitle) f.chapterIndex to 0 else f.chapterIndex to f.startOffset
    }
}

/**
 * 增量全局分页状态。
 *
 * pages 只装已封口的页；正在填的页存在 openFragments/openUsedPx 里，
 * 其展示序号就是 pages.size（封口时原地落袋，序号不变）。
 * frontierChapter 之前章节的页全部已确认；frontier 处若正文未到，
 * 分页在此停住，等正文到了再续（不等全书到齐）。
 */
data class BookPagination(
    val pages: List<GlobalPage>,
    val openFragments: List<GlobalTextFragment>,
    val openUsedPx: Float,
    val frontierChapter: Int,
    val fullyDone: Boolean,
    val titlePageByChapter: Map<Int, Int>,
    val lastPageByChapter: Map<Int, Int>,
    val layoutVersion: Int,
) {
    /** 展示用页表 = 封口页 + 正在填的页（非空时）。 */
    val displayPages: List<GlobalPage>
        get() = if (openFragments.isEmpty()) pages else pages + GlobalPage(openFragments)

    val displayPageCount: Int get() = displayPages.size

    companion object {
        fun empty(layoutVersion: Int) = BookPagination(
            pages = emptyList(),
            openFragments = emptyList(),
            openUsedPx = 0f,
            frontierChapter = 0,
            fullyDone = false,
            titlePageByChapter = emptyMap(),
            lastPageByChapter = emptyMap(),
            layoutVersion = layoutVersion,
        )
    }
}

/**
 * 从 base.frontierChapter 起向后排 maxChapters 章（或到书末），返回新状态。
 *
 * 遇到正文未到（content 为空且 url 非空）的章节即停住，frontier 停在该章，
 * 调用方拉到正文后再续排；本地空章（content 为空且 url 为空）只排标题。
 * 标题块高度 = 标题实测高度 + titleBottomPx；正文按实测行高逐行打包，
 * 行高用 layout.getLineTop/getLineBottom，不再按字符数估算；段落不断行
 * 保护，行就是行，标题与正文同等待遇。
 */
fun paginateChapterRange(
    measurer: TextMeasurer,
    chapters: List<Chapter>,
    base: BookPagination,
    maxChapters: Int,
    bodyStyle: TextStyle,
    titleStyle: TextStyle,
    titleBottomPx: Float,
    innerWidthPx: Int,
    innerHeightPx: Float,
): BookPagination {
    if (innerWidthPx <= 0 || innerHeightPx <= 0f || maxChapters <= 0) return base
    val closed = base.pages.toMutableList()
    val titleMap = base.titlePageByChapter.toMutableMap()
    val lastMap = base.lastPageByChapter.toMutableMap()
    val frags = base.openFragments.toMutableList()
    var used = base.openUsedPx
    var frontier = base.frontierChapter

    fun closePage() {
        if (frags.isNotEmpty()) {
            closed.add(GlobalPage(frags.toList()))
            frags.clear()
            used = 0f
        }
    }

    /** 把一块内容放进正在填的页；放不下且页非空时先封口。超高单块直接独占一页。 */
    fun placeBlock(height: Float, fragment: GlobalTextFragment) {
        if (frags.isNotEmpty() && used + height > innerHeightPx) closePage()
        frags.add(fragment)
        used += height
        // 正在填的页展示序号恒为 closed.size（封口时原地落袋，序号不变）。
        if (fragment.isTitle) titleMap[fragment.chapterIndex] = closed.size
        lastMap[fragment.chapterIndex] = closed.size
    }

    val end = (frontier + maxChapters).coerceAtMost(chapters.size)
    while (frontier < end) {
        val chapter = chapters.getOrNull(frontier) ?: break
        val content = chapter.content
        if (content.isBlank()) {
            if (chapter.url.isNotBlank()) break // 正文未到：停住，等拉到再续排
            // 本地空章：只排标题。
            val titleLayout = measurer.measure(
                chapter.title, titleStyle, Constraints(maxWidth = innerWidthPx),
            )
            placeBlock(
                titleLayout.size.height + titleBottomPx,
                GlobalTextFragment(chapter.index, true, 0, 0),
            )
            frontier++
            continue
        }
        val titleLayout = measurer.measure(
            chapter.title, titleStyle, Constraints(maxWidth = innerWidthPx),
        )
        placeBlock(
            titleLayout.size.height + titleBottomPx,
            GlobalTextFragment(chapter.index, true, 0, 0),
        )
        val bodyLayout = measurer.measure(
            content, bodyStyle, Constraints(maxWidth = innerWidthPx),
        )
        val lineCount = bodyLayout.lineCount
        for (i in 0 until lineCount) {
            val lineTop = bodyLayout.getLineTop(i)
            val lineBottom = bodyLayout.getLineBottom(i)
            val start = bodyLayout.getLineStart(i)
            val lineEnd = if (i + 1 < lineCount) bodyLayout.getLineStart(i + 1) else content.length
            val last = frags.lastOrNull()
            if (last != null && !last.isTitle && last.chapterIndex == chapter.index &&
                last.endOffset == start
            ) {
                // 与上一行同章连续：直接延长片段，不新增片段。
                if (frags.isNotEmpty() && used + (lineBottom - lineTop) > innerHeightPx) {
                    closePage()
                    frags.add(GlobalTextFragment(chapter.index, false, start, lineEnd))
                    used += lineBottom - lineTop
                    lastMap[chapter.index] = closed.size
                } else {
                    frags[frags.lastIndex] = last.copy(endOffset = lineEnd)
                    used += lineBottom - lineTop
                }
            } else {
                placeBlock(
                    lineBottom - lineTop,
                    GlobalTextFragment(chapter.index, false, start, lineEnd),
                )
            }
        }
        frontier++
    }

    val fullyDone = frontier >= chapters.size
    val finalPages = if (fullyDone) {
        if (frags.isNotEmpty()) closed + GlobalPage(frags.toList()) else closed
    } else {
        closed
    }
    return BookPagination(
        pages = finalPages,
        openFragments = if (fullyDone) emptyList() else frags.toList(),
        openUsedPx = if (fullyDone) 0f else used,
        frontierChapter = frontier,
        fullyDone = fullyDone,
        titlePageByChapter = titleMap,
        lastPageByChapter = lastMap,
        layoutVersion = base.layoutVersion,
    )
}

/** 章节标题锚点：标题片段所在的全局页（未排到时返回 null，调用方继续排版）。 */
fun BookPagination.titlePage(chapterIndex: Int): Int? = titlePageByChapter[chapterIndex]

/** 上一章末页：该章最后一个正文片段所在的全局页（未排到时返回 null）。 */
fun BookPagination.lastTextPage(chapterIndex: Int): Int? = lastPageByChapter[chapterIndex]

/**
 * 章节 + 字符偏移 → 全局页。
 *
 * offset == 0 优先返回标题锚点（标题在正文之前）；其它偏移找覆盖它的片段；
 * 找不着时：该章已排完则退回该章末页（章末偏移），未排到则返回 null
 * 表示目标尚未确认，调用方应继续排版而不是退回旧按章分页。
 */
fun BookPagination.pageIndexForPosition(chapterIndex: Int, charOffset: Int): Int? {
    if (charOffset == 0) titlePageByChapter[chapterIndex]?.let { return it }
    val pages = displayPages
    for ((pi, page) in pages.withIndex()) {
        for (f in page.fragments) {
            if (f.chapterIndex == chapterIndex && !f.isTitle &&
                charOffset in f.startOffset until f.endOffset
            ) {
                return pi
            }
        }
    }
    if (chapterIndex < frontierChapter) return lastPageByChapter[chapterIndex]
    return null
}
