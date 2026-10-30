package com.movieshell.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity

/**
 * v0.294.2：ModalBottomSheet 里可滚列表的嵌套滚动守卫（Material 标准口径）。
 *
 * M3 1.3.1 的 ModalBottomSheet 用 ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection
 * 接列表的剩余量：onPostScroll 把列表没吃完的下滑位移全交给弹窗拖动，onPostFling 把剩余
 * 速度全交给弹窗 settle。于是从列表中间开始下滑/甩动，列表一滚到顶，同一手势的剩余量
 * 立刻拖着弹窗往下走甚至直接收起——用户看到的是“源还没到顶窗口就在动/被收回”。
 *
 * 守卫挂在列表外层（是列表的嵌套滚动父级、弹窗的子级）：按下时记录列表是否已在顶部。
 * - 起手时列表不在顶：本次手势剩余的下滑位移与下滑速度全部吞掉，弹窗不动，只滚列表；
 * - 起手时已在顶：不拦，下滑照旧交给弹窗收起。
 * 拖动顶部小横线（不在列表内）与点弹窗外收起不经过这里，照旧生效。
 */
fun Modifier.sheetListScrollGuard(canScrollBackward: () -> Boolean): Modifier = composed {
    val canScrollBack by rememberUpdatedState(canScrollBackward)
    val guard = remember { SheetListGuardConnection() }
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                guard.startedAtTop = !canScrollBack()
            }
        }
        .nestedScroll(guard)
}

private class SheetListGuardConnection : NestedScrollConnection {
    var startedAtTop: Boolean = true

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (!startedAtTop && available.y > 0f) Offset(0f, available.y) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (!startedAtTop && available.y > 0f) Velocity(0f, available.y) else Velocity.Zero
}
