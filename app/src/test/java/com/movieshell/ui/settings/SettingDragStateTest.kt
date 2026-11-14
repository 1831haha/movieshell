package com.movieshell.ui.settings

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.movieshell.data.store.SettingsLayoutStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.2.7：设置项长按拖拽——落点判定状态机（begin/moveTo/end，不碰手势/界面）。
 * 坐标是编造的窗口坐标：主设置页播放组卡片 y 100..500，入口行 y 100..160，
 * 重力行 y 160..220；子页面列表 y 600..1000，四行各 60 高。
 * 两组坐标故意不重叠——真机上两个容器在不同页面，从不同时出现；
 * 坐标重叠会让落点先撞上主容器（#622 三例挂的就是这个测试毛病，不是产品 bug）。
 */
class SettingDragStateTest {

    private val main = SettingsLayoutStore.MAIN_PLAYBACK
    private val page = SettingsLayoutStore.PAGE_PLAYBACK

    private fun state(): SettingDragState {
        val s = SettingDragState()
        s.containers = mapOf(
            main to listOf("gravity"),
            page to listOf("engine", "autoplay", "adfilter", "max_parallel"),
        )
        s.containerRects[main] = Rect(0f, 100f, 400f, 500f)
        s.entryRects[page] = Rect(0f, 100f, 400f, 160f)
        s.itemRects["gravity"] = Rect(0f, 160f, 400f, 220f)
        s.containerRects[page] = Rect(0f, 600f, 400f, 1000f)
        s.itemRects["engine"] = Rect(0f, 600f, 400f, 660f)
        s.itemRects["autoplay"] = Rect(0f, 660f, 400f, 720f)
        s.itemRects["adfilter"] = Rect(0f, 720f, 400f, 780f)
        s.itemRects["max_parallel"] = Rect(0f, 780f, 400f, 840f)
        return s
    }

    @Test fun dragRowOntoEntryEntersPage() {
        val s = state()
        s.begin("gravity", Offset(200f, 190f), IntSize(400, 60))
        s.moveTo(Offset(200f, 130f))
        assertEquals("gravity" to SettingDropTarget.EnterPage(page), s.end())
    }

    @Test fun entryBeatsContainerWhenOverlapping() {
        // 入口在组卡片里面：落到入口上必须是进页面，不是组内插入。
        val s = state()
        s.begin("gravity", Offset(200f, 190f), IntSize(400, 60))
        s.moveTo(Offset(200f, 110f))
        assertEquals("gravity" to SettingDropTarget.EnterPage(page), s.end())
    }

    @Test fun dragRowOntoStripGoesBackToMain() {
        val s = state()
        s.stripRect = Rect(0f, 540f, 400f, 600f)
        s.begin("engine", Offset(200f, 630f), IntSize(400, 60))
        s.moveTo(Offset(200f, 570f))
        assertEquals("engine" to SettingDropTarget.BackToMain, s.end())
    }

    @Test fun stripBeatsContainerWhenOverlapping() {
        val s = state()
        s.stripRect = Rect(0f, 580f, 400f, 640f)
        s.begin("engine", Offset(200f, 630f), IntSize(400, 60))
        s.moveTo(Offset(200f, 620f))
        assertEquals("engine" to SettingDropTarget.BackToMain, s.end())
    }

    @Test fun dragWithinPageToMiddle() {
        // 手指 y=760：engine(630)、adfilter(750) 在上，max_parallel(810) 在下 → 插到 2。
        val s = state()
        s.begin("autoplay", Offset(200f, 690f), IntSize(400, 60))
        s.moveTo(Offset(200f, 760f))
        assertEquals("autoplay" to SettingDropTarget.Insert(page, 2), s.end())
    }

    @Test fun dragWithinPageToTop() {
        val s = state()
        s.begin("adfilter", Offset(200f, 750f), IntSize(400, 60))
        s.moveTo(Offset(200f, 610f))
        assertEquals("adfilter" to SettingDropTarget.Insert(page, 0), s.end())
    }

    @Test fun dragWithinPageToBottom() {
        val s = state()
        s.begin("engine", Offset(200f, 630f), IntSize(400, 60))
        s.moveTo(Offset(200f, 830f))
        assertEquals("engine" to SettingDropTarget.Insert(page, 3), s.end())
    }

    @Test fun dragOutsideAnyTargetCancels() {
        val s = state()
        s.begin("gravity", Offset(200f, 190f), IntSize(400, 60))
        s.moveTo(Offset(200f, 1100f))
        assertNull(s.end())
    }

    @Test fun cancelClearsState() {
        val s = state()
        s.begin("engine", Offset(200f, 630f), IntSize(400, 60))
        s.moveTo(Offset(200f, 570f))
        s.cancel()
        assertNull(s.draggedId)
        assertNull(s.end())
    }

    @Test fun secondBeginIsIgnoredWhileDragging() {
        val s = state()
        s.begin("engine", Offset(200f, 630f), IntSize(400, 60))
        s.begin("autoplay", Offset(200f, 690f), IntSize(400, 60))
        assertEquals("engine", s.draggedId)
    }
}
