package com.movieshell.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.model.Chapter

/**
 * v0.244.0：整本连续阅读——滚动模式连续流。
 *
 * 一章就是 LazyColumn 里的一个 item（标题 Text + 正文 Text 在同一 Column 内），
 * 章与章在同一个列表里自然接续：滑过章界没有任何跳转、不重建滚动状态，
 * 标题就出现在上一章正文之后、下一章正文之前。章节列表本身永不因正文
 * 未加载而缩短（未加载章只显示占位，item 仍在），列表索引与章节索引
 * 才能一直对得上。
 *
 * 视口章节 = 视口顶部所在的 item；视口字符偏移 = 视口顶部在该章正文里的
 * 字符位置（标题区按该章 0 算），由调用方存进视口状态，供进度保存、
 * 底栏、书签共用。普通滚动期间本文件绝不主动改位置，定位只走
 * calibrateScrollToChar，且只由调用方的主动定位请求触发。
 */
@Composable
fun ContinuousReaderFlow(
    chapters: List<Chapter>,
    listState: LazyListState,
    fontSize: Int,
    lineHeightRatio: Float,
    readerFont: FontFamily,
    fg: Color,
    background: Color,
    onToggleImmersive: () -> Unit,
    getBodyLayout: (Int) -> TextLayoutResult?,
    getTitleHeight: (Int) -> Float,
    onChapterLayout: (chapterIndex: Int, bodyLayout: TextLayoutResult, titleHeightPx: Float) -> Unit,
    onViewportChanged: (chapterIndex: Int, charOffset: Int) -> Unit,
    // v0.294.5：正文左右边距（阅读设置「边距」）
    horizontalPadding: androidx.compose.ui.unit.Dp = 20.dp,
) {
    val density = LocalDensity.current
    val titleBottomPx = with(density) { 16.dp.toPx() }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .clickable { onToggleImmersive() },
        state = listState,
    ) {
        itemsIndexed(chapters, key = { _, c -> c.index }) { _, chapter ->
            // 标题高度按 item 记：标题先布局，正文回调读到的就是本帧的值。
            val titleHeightState = remember { mutableStateOf(0f) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = 12.dp),
            ) {
                Text(
                    text = chapter.title,
                    color = fg,
                    fontSize = (fontSize + 2).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp),
                    onTextLayout = {
                        titleHeightState.value = it.size.height + titleBottomPx
                    },
                )
                Text(
                    text = chapter.content,
                    color = fg,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * lineHeightRatio).sp,
                    fontFamily = readerFont,
                    onTextLayout = {
                        onChapterLayout(chapter.index, it, titleHeightState.value)
                    },
                )
            }
        }
    }

    // 视口跟踪：只读 layoutInfo，不改位置；变化才回调，避免每帧重组。
    LaunchedEffect(listState, chapters) {
        var lastChapter = -1
        var lastOffset = -1
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }.collect { visible ->
            val first = visible.minByOrNull { it.offset } ?: return@collect
            val chapterIndex = chapters.getOrNull(first.index)?.index ?: first.index
            val topInItem = -first.offset.toFloat()
            val titleH = getTitleHeight(chapterIndex)
            val layout = getBodyLayout(chapterIndex)
            val offset = when {
                layout == null -> 0
                topInItem < titleH -> 0
                else -> {
                    val line = layout.getLineForVerticalPosition(
                        (topInItem - titleH).coerceAtLeast(0f),
                    )
                    layout.getLineStart(line)
                }
            }
            if (chapterIndex != lastChapter || offset != lastOffset) {
                lastChapter = chapterIndex
                lastOffset = offset
                onViewportChanged(chapterIndex, offset)
            }
        }
    }
}

/**
 * 精确定位到某章某字符：先把目标 item 带到视口顶，再按行顶校准。
 *
 * item 顶 + 标题高 + 目标行顶 = 该字符在屏上的位置，减去想要的顶部留白
 * 就是要滚的量。调用方须在用户新开手势或新定位请求时取消本调用，
 * 普通滚动期间不许调用（落点只归主动定位管）。
 */
suspend fun calibrateScrollToChar(
    listState: LazyListState,
    itemIndex: Int,
    charOffset: Int,
    contentLength: Int,
    getBodyLayout: (Int) -> TextLayoutResult?,
    getTitleHeight: (Int) -> Float,
    desiredTopPx: Float = 0f,
): Boolean {
    listState.scrollToItem(itemIndex)
    var layout: TextLayoutResult? = null
    var frames = 0
    while (frames < 30) {
        layout = getBodyLayout(itemIndex)
        val hasItem = listState.layoutInfo.visibleItemsInfo.any { it.index == itemIndex }
        if (layout != null && hasItem) break
        withFrameNanos { }
        frames++
    }
    val result = layout ?: return false
    val line = result.getLineForOffset(charOffset.coerceIn(0, contentLength))
    val lineTop = result.getLineTop(line)
    val itemOffset = listState.layoutInfo.visibleItemsInfo
        .firstOrNull { it.index == itemIndex }?.offset?.toFloat() ?: 0f
    val delta = itemOffset + getTitleHeight(itemIndex) + lineTop - desiredTopPx
    if (delta != 0f) {
        listState.scroll { scrollBy(delta) }
    }
    return true
}
