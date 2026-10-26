package com.movieshell.ui.components

import android.content.Context
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

    /** overlay 是否已挂载（MainActivity 挂载时置 true）。 */
    internal val mounted = AtomicBoolean(false)

    fun show(context: Context, msg: String, long: Boolean = false) {
        val text = msg.trim()
        if (text.isEmpty()) return
        if (mounted.get()) {
            _current.value = AppToastMsg(seq.incrementAndGet(), text, long)
        } else {
            // 后台/非主 Activity 调用：回退系统提示，不丢消息。
            Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    fun dismiss(id: Long) {
        if (_current.value?.id == id) _current.value = null
    }
}

/**
 * 挂在 MainActivity 根 Box 里，全 App 共用一层提示。
 * 新消息到 → 显示；2.2 秒（长提示 3.6 秒）后自动消失；点一下提前消失。
 */
@Composable
fun AppToastOverlay() {
    val msg by AppToast.current.collectAsState()
    // 挂载标记：组合进树即 true，离树即 false（AppToast.show 靠它决定是否回退）。
    androidx.compose.runtime.DisposableEffect(Unit) {
        AppToast.mounted.set(true)
        onDispose { AppToast.mounted.set(false) }
    }
    LaunchedEffect(msg?.id) {
        val m = msg ?: return@LaunchedEffect
        delay(if (m.long) 3600L else 2200L)
        AppToast.dismiss(m.id)
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = msg != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            val m = msg ?: return@AnimatedVisibility
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    // 110dp：悬在底部 dock（约 84dp 高）上方，不盖列表最后一行。
                    .padding(bottom = 110.dp)
                    .shadow(16.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF171B28))
                    .border(1.dp, Color(0xFF4DD0E1).copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                    .clickable { AppToast.dismiss(m.id) }
                    .padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4DD0E1)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = m.text,
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 3,
                )
            }
        }
    }
}
