package com.movieshell.ui.components

import androidx.compose.material3.MaterialTheme

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v0.211.0：全仓统一精致提示（替代系统原生 Toast）。
 *
 * 之前的问题（截图实证）：系统 Toast 是灰黑方块、贴着屏幕底部，
 * 正好盖住底部导航和列表最后一行，文案长了还被截成多行硬块。
 * 新口径：App 内统一走这一个入口，样式跟 TechDialog/毛玻璃卡片同源——
 * 深底 #171B28 + 香槟金微光描边 + 青色圆点，底部抬高 110dp 悬在
 * 底部 dock 上方，不再盖最后一行；点一下可提前关闭。
 *
 * 实现说明：Android 11 起自定义 Toast 视图会被系统打回标准样式，
 * 所以这里不用 Toast.setView，而是走应用内 Compose 悬浮层
 * （MainActivity 的 Box 根节点里挂 AppToastOverlay）。
 * 调用方在 Activity 外（如后台播放器）时 overlay 未挂载，
 * 则回退系统 Toast，保证提示不丢。
 */
/**
 * v1.3.32（GPT 第二轮）：消息带创建时间——重新挂载时丢弃超过有效期
 * （名义显示时长）的消息，避免重建后弹一条过期的旧提示。
 */
data class AppToastMsg(
    val id: Long,
    val text: String,
    val long: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** 名义显示时长（毫秒）；无障碍延长只影响计时器，不延长有效期。 */
    val displayMs: Int get() = if (long) 4000 else 2500
    fun isExpired(now: Long): Boolean = now - createdAt > displayMs
}

object AppToast {
    private val seq = AtomicLong(0)
    private val _current = MutableStateFlow<AppToastMsg?>(null)
    val current: StateFlow<AppToastMsg?> = _current
    // v0.272.0（T35）：提示队列——原来 show 直接覆盖 _current，旧提示无声丢掉；
    // 现在当前在显示时新提示先进队，当前消失时按序弹出下一条。
    private val queue = ArrayDeque<AppToastMsg>()
    private val queueLock = Any()
    // v1.3.32（GPT 第二轮）：挂载计数代替单个 Boolean——Activity 重建时旧
    // Overlay 的 onDispose 不会清掉新 Overlay 的挂载（每例 +1/-1 配对，
    // 交错顺序也不影响最终计数）；判断与投递在同一锁内是原子操作。
    // guarded by queueLock
    private var mountCount = 0

    /** Overlay 挂载时调（DisposableEffect）；每次挂载顺手清掉过期消息。 */
    internal fun registerOverlay() {
        synchronized(queueLock) {
            mountCount++
            dropExpiredLocked()
        }
    }

    /** Overlay 离树时调；下限 0，配对的 -1 不会把计数打成负。 */
    internal fun unregisterOverlay() {
        synchronized(queueLock) {
            if (mountCount > 0) mountCount--
        }
    }

    /** 是否有 Overlay 挂着（ChaquopyEngine 后台提示前先问一句）。 */
    internal fun isMounted(): Boolean = synchronized(queueLock) { mountCount > 0 }

    /** 丢弃超过有效期的消息（调用方已持 queueLock；当前过期则顺延下一条）。 */
    private fun dropExpiredLocked() {
        val now = System.currentTimeMillis()
        if (_current.value?.isExpired(now) == true) _current.value = null
        queue.removeAll { it.isExpired(now) }
        if (_current.value == null) _current.value = queue.removeFirstOrNull()
    }

    fun show(context: Context, msg: String, long: Boolean = false) {
        val text = msg.trim()
        if (text.isEmpty()) return
        val m = AppToastMsg(seq.incrementAndGet(), text, long)
        // 判断与投递同一锁内原子完成：挂载时入队/直显，否则回退系统 Toast。
        val deliverInApp = synchronized(queueLock) {
            if (mountCount > 0) {
                if (_current.value == null) _current.value = m else queue.addLast(m)
                true
            } else {
                false
            }
        }
        if (!deliverInApp) {
            // 后台/非主 Activity 调用：回退系统提示，不丢消息。
            Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    fun dismiss(id: Long) {
        synchronized(queueLock) {
            if (_current.value?.id != id) return
            _current.value = queue.removeFirstOrNull()
        }
    }
}

/**
 * 挂在 MainActivity 根 Box 里，全 App 共用一层提示。
 * 新消息到 → 显示；短 2.5 秒（长提示 4 秒）后自动消失，
 * 无障碍“操作时间”开了的用户按系统推荐时长延长；点一下提前消失。
 */
@Composable
fun AppToastOverlay(bottomPadding: androidx.compose.ui.unit.Dp = 16.dp) {
    val msg by AppToast.current.collectAsState()
    val context = LocalContext.current
    // v1.3.32（GPT 第二轮）：退出动画要留内容——displayedMsg 保存最后一条显示过
    // 的消息；msg 变 null 时暂不清空，让 AnimatedVisibility 的退出动画有内容
    // 可淡出；新消息到时同一帧更新（等值写入，不会死循环）。
    var displayedMsg by remember { mutableStateOf<AppToastMsg?>(null) }
    if (msg != null) {
        displayedMsg = msg
    }
    // 挂载标记：组合进树即注册，离树即注销（AppToast.show 靠它决定是否回退）。
    androidx.compose.runtime.DisposableEffect(Unit) {
        AppToast.registerOverlay()
        onDispose { AppToast.unregisterOverlay() }
    }
    LaunchedEffect(msg?.id) {
        val m = msg ?: return@LaunchedEffect
        // v1.3.32（GPT 折中）：短 2.5 秒 / 长 4 秒；无障碍“操作时间”开了的
        // 用户按系统推荐时长延长。getRecommendedTimeoutMillis 是 API 29+，
        // 入参是 Int（minSdk 26，低版本直接用基准时长）。
        // v1.3.32（GPT 第二轮）：flags 补全——卡片含圆点图标且可点击关闭。
        val base = if (m.long) 4000 else 2500
        val effective = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            am?.getRecommendedTimeoutMillis(
                base,
                AccessibilityManager.FLAG_CONTENT_TEXT or
                    AccessibilityManager.FLAG_CONTENT_ICONS or
                    AccessibilityManager.FLAG_CONTENT_CONTROLS,
            ) ?: base
        } else base
        // v1.3.32（GPT 第二轮）：重建后重计时只补剩余时长，不是从头完整显示一次。
        val remaining = (effective - (System.currentTimeMillis() - m.createdAt)).coerceAtLeast(0L)
        delay(remaining)
        AppToast.dismiss(m.id)
    }
    // v1.3.32：小小小——满宽横条改按内容自适应（最宽屏宽 80% 且不超过 420dp），
    // 字 13sp/行高 17sp、内边距收紧、圆角 6dp；347 处调用点一处生效。
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val maxToastWidth = minOf(maxWidth * 0.8f, 420.dp)
        AnimatedVisibility(
            visible = msg != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            val m = displayedMsg ?: return@AnimatedVisibility
            Row(
                modifier = Modifier
                    // F6：不再固定离底 110dp——调用方按底栏/迷你播放条高度传 bottomPadding，
                    // 有底栏时悬在底栏上方 16dp，没底栏时离底 16dp，不压迷你播放条也不悬空。
                    .padding(bottom = bottomPadding)
                    // v0.279.0 旧版：平面提示条——底栏同款 #161921 实底 + 1px 分隔线描边 + 8dp 圆角。
                    .widthIn(max = maxToastWidth)
                    .shadow(8.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    .clickable { AppToast.dismiss(m.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(8.dp))
                // 超长文案不截断：最高 200dp，超出可滚动；liveRegion 让 TalkBack 感知。
                // v1.3.32（GPT 第二轮）：滚动状态按消息 ID 隔离——key(m.id) 保证每条
                // 消息独立滚动，长提示滚到底后下一条从顶部开始。
                key(m.id) {
                    val scrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = 200.dp)
                            .verticalScroll(scrollState),
                    ) {
                        Text(
                            text = m.text,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            // v0.272.0（T34）：原来 maxLines=3，稍长一点的提示就被截掉后半句；
                            // 提示本来就能点一下关闭，不再截断，全文显示。
                        )
                    }
                }
            }
        }
    }
}
