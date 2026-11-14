package com.movieshell.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * v1.2.7：设置项长按拖拽。
 *
 * 全部用窗口坐标算落点（行 / 容器 / 入口 / 回页条都记 boundsInWindow，
 * 手指位置也是窗口坐标），悬浮副本画在页面最外层 Box 里，跟手走。
 * 拖动时原列表不动（被拖的行变淡占位），松手才提交一次 move，列表
 * 重组到新位置——拖动中途不改布局，坐标不会乱。
 * 落点优先级：回页条 > 子页面入口 > 容器内插入位置。
 */
sealed interface SettingDropTarget {
    data class Insert(val container: String, val index: Int) : SettingDropTarget
    data class EnterPage(val container: String) : SettingDropTarget
    data object BackToMain : SettingDropTarget
}

class SettingDragState {
    var draggedId by mutableStateOf<String?>(null)
        private set
    var dragPos by mutableStateOf(Offset.Zero)
        private set
    var dragSize by mutableStateOf(IntSize.Zero)
        private set
    var hoverTarget by mutableStateOf<SettingDropTarget?>(null)
        private set
    var rootTopLeft by mutableStateOf(Offset.Zero)
        private set

    /** 各容器当前住的行（布局流），供落点算插入位置；宿主屏每次布局变化时更新。 */
    var containers by mutableStateOf<Map<String, List<String>>>(emptyMap())

    val itemRects = mutableMapOf<String, Rect>()
    val containerRects = mutableMapOf<String, Rect>()
    val entryRects = mutableMapOf<String, Rect>()
    var stripRect by mutableStateOf<Rect?>(null)
        private set

    val isDragging get() = draggedId != null

    fun setStripRect(rect: Rect?) {
        stripRect = rect
    }

    fun begin(id: String, fingerWindowPos: Offset, size: IntSize) {
        if (draggedId != null) return
        draggedId = id
        dragPos = fingerWindowPos
        dragSize = size
        hoverTarget = null
    }

    fun moveTo(pos: Offset) {
        if (draggedId == null) return
        dragPos = pos
        hoverTarget = hitTest(pos)
    }

    /** 松手：返回要提交的（行 id，落点），并清掉拖动状态；没落到有效目标返回 null。 */
    fun end(): Pair<String, SettingDropTarget>? {
        val id = draggedId
        val target = hoverTarget
        draggedId = null
        hoverTarget = null
        stripRect = null
        return if (id != null && target != null) id to target else null
    }

    fun cancel() {
        draggedId = null
        hoverTarget = null
        stripRect = null
    }

    private fun hitTest(pos: Offset): SettingDropTarget? {
        stripRect?.let { if (it.contains(pos)) return SettingDropTarget.BackToMain }
        for ((container, rect) in entryRects) {
            if (rect.contains(pos)) return SettingDropTarget.EnterPage(container)
        }
        for ((container, rect) in containerRects) {
            if (!rect.contains(pos)) continue
            val rows = (containers[container] ?: emptyList())
                .filter { it != draggedId }
                .mapNotNull { itemRects[it] }
            var index = 0
            for (row in rows) {
                if (row.center.y < pos.y) index++ else break
            }
            return SettingDropTarget.Insert(container, index)
        }
        return null
    }
}

@Composable
fun rememberSettingDragState(): SettingDragState = androidx.compose.runtime.remember { SettingDragState() }

/** 可移行挂这个：记自己的窗口位置，长按后开始拖拽。 */
fun Modifier.settingDraggable(
    dragState: SettingDragState,
    itemId: String,
    onDrop: (String, SettingDropTarget) -> Unit,
): Modifier = this
    .onGloballyPositioned { coords ->
        dragState.itemRects[itemId] = coords.boundsInWindow()
    }
    .pointerInput(itemId) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val rect = dragState.itemRects[itemId]
                val size = rect?.let { IntSize(it.width.roundToInt(), it.height.roundToInt()) } ?: IntSize.Zero
                val origin = rect?.topLeft ?: Offset.Zero
                dragState.begin(itemId, origin + offset, size)
            },
            onDrag = { change, dragAmount ->
                dragState.moveTo(dragState.dragPos + dragAmount)
                change.consume()
            },
            onDragEnd = {
                dragState.end()?.let { (id, target) -> onDrop(id, target) }
            },
            onDragCancel = { dragState.cancel() },
        )
    }

/** 一组可移行住的容器（设置页的分组卡片 / 子页面的列表区），记窗口位置供落点用。 */
fun Modifier.settingDropContainer(
    dragState: SettingDragState,
    containerId: String,
): Modifier = onGloballyPositioned { coords ->
    dragState.containerRects[containerId] = coords.boundsInWindow()
}

/** 子页面入口（设置页里的“播放设置”行）：拖到它上面松手，行就搬进那个子页面。 */
fun Modifier.settingDropEntry(
    dragState: SettingDragState,
    targetContainer: String,
): Modifier = onGloballyPositioned { coords ->
    dragState.entryRects[targetContainer] = coords.boundsInWindow()
}

/** 子页面顶部的“拖回设置页”条：只在拖动时出现，记窗口位置供落点用。 */
fun Modifier.settingBackStrip(
    dragState: SettingDragState,
): Modifier = onGloballyPositioned { coords ->
    dragState.setStripRect(coords.boundsInWindow())
}

/** 拖动时的悬浮跟手副本 + 插入位置指示线，宿主屏放在最外层 Box 的最后。 */
@Composable
fun SettingDragOverlay(
    dragState: SettingDragState,
    originStyle: SettingRowStyle,
    onOpenMarketClick: () -> Unit,
) {
    val id = dragState.draggedId ?: return
    val size = dragState.dragSize
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { dragState.rootTopLeft = it.boundsInWindow().topLeft },
    ) {
        Surface(
            modifier = Modifier
                .offset {
                    val p = dragState.dragPos - dragState.rootTopLeft -
                        Offset(size.width / 2f, size.height / 2f)
                    IntOffset(p.x.roundToInt(), p.y.roundToInt())
                }
                .width(with(LocalDensity.current) { size.width.toDp() }),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            MovableSettingRow(
                id = id,
                style = originStyle,
                cardPosition = SegPos.Single,
                divider = false,
                onOpenMarketClick = onOpenMarketClick,
            )
        }
        val target = dragState.hoverTarget
        if (target is SettingDropTarget.Insert) {
            val y = insertionLineY(dragState, target)
            if (y != null) {
                Box(
                    Modifier
                        .offset { IntOffset(0, (y - dragState.rootTopLeft.y).roundToInt()) }
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

private fun insertionLineY(dragState: SettingDragState, target: SettingDropTarget.Insert): Float? {
    val ids = dragState.containers[target.container] ?: return null
    val rows = ids.filter { it != dragState.draggedId }.mapNotNull { dragState.itemRects[it] }
    if (rows.isEmpty()) return dragState.containerRects[target.container]?.center?.y
    return when {
        target.index <= 0 -> rows.first().top
        target.index >= rows.size -> rows.last().bottom
        else -> (rows[target.index - 1].bottom + rows[target.index].top) / 2f
    }
}
