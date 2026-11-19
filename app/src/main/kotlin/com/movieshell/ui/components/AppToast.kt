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
import java.util.concurrent.atomic.AtomicBoolean
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
data class AppToastMsg(val id: Long, val text: String, val long: Boolean)

object AppToast {
    private val seq = AtomicLong(0)
    private val _current = MutableStateFlow<AppToastMsg?>(null)
    val current: StateFlow<AppToastMsg?> = _current
    // v0.272.0（T35）：提示队列——原来 show 直接覆盖 _current，旧提示无声丢掉；
    // 现在当前在显示时新提示先进队，当前消失时按序弹出下一条。
    private val queue = ArrayDeque<AppToastMsg>()
    private val queueLock = Any()

    /** overlay 是否已挂载（MainActivity 挂载时置 true）。 */
    internal val mounted = AtomicBoolean(false)

    fun show(context: Context, msg: String, long: Boolean = false) {
        val text = msg.trim()
        if (text.isEmpty()) return
        if (mounted.get()) {
            val m = AppToastMsg(seq.incrementAndGet(), text, long)
            synchronized(queueLock) {
                if (_current.value == null) _current.value = m else queue.addLast(m)
            }
        } else {
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
    // 挂载标记：组合进树即 true，离树即 false（AppToast.show 靠它决定是否回退）。
    androidx.compose.runtime.DisposableEffect(Unit) {
        AppToast.mounted.set(true)
        onDispose { AppToast.mounted.set(false) }
    }
    LaunchedEffect(msg?.id) {
        val m = msg ?: return@LaunchedEffect
        // v1.3.32（GPT 折中）：短 2.5 秒 / 长 4 秒；无障碍“操作时间”开了的
        // 用户按系统推荐时长延长。getRecommendedTimeoutMillis 是 API 29+，
        // 入参是 Int（minSdk 26，低版本直接用基准时长）。
        val base = if (m.long) 4000 else 2500
        val effective = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            am?.getRecommendedTimeoutMillis(base, AccessibilityManager.FLAG_CONTENT_TEXT) ?: base
        } else base
        delay(effective.toLong())
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
            val m = msg ?: return@AnimatedVisibility
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
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
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
