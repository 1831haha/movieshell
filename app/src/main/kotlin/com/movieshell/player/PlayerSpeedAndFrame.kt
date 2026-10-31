package com.movieshell.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** v0.294.21：倍速选择小菜单（点选即应用，当前项高亮打勾）。 */
@Composable
internal fun SpeedPickerMenu(
    expanded: Boolean,
    current: Float,
    onDismiss: () -> Unit,
    onPick: (Float) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
    ) {
        PlayerGestureMath.SPEED_CHOICES.asReversed().forEach { v ->
            val sel = v == current
            DropdownMenuItem(
                text = {
                    Text(
                        PlayerGestureMath.speedLabel(v),
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                },
                trailingIcon = if (sel) {
                    { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }
                } else null,
                onClick = { onPick(v) },
                modifier = if (sel) Modifier.background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                else Modifier,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            )
        }
    }
}

/** v0.294.21：切内核前抓当前画面（SurfaceView 走 PixelCopy，TextureView 直接取图），失败返回 null。 */
internal object FrameGrab {
    private fun findVideoSurface(v: View?): View? {
        if (v == null) return null
        if (v is SurfaceView || v is TextureView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findVideoSurface(v.getChildAt(i))?.let { return it }
        return null
    }

    suspend fun capture(root: View?): ImageBitmap? {
        val target = findVideoSurface(root) ?: return null
        val w = target.width
        val h = target.height
        if (w <= 0 || h <= 0) return null
        return runCatching {
            when (target) {
                is TextureView -> target.bitmap?.asImageBitmap()
                is SurfaceView -> withTimeoutOrNull(400) {
                    suspendCancellableCoroutine { cont ->
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        try {
                            PixelCopy.request(target, bmp, { r ->
                                if (cont.isActive) cont.resume(if (r == PixelCopy.SUCCESS) bmp.asImageBitmap() else null)
                            }, Handler(Looper.getMainLooper()))
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }
                else -> null
            }
        }.getOrNull()
    }
}
