package com.movieshell.ui.home

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import com.movieshell.core.log.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * v0.292.0 V5：海报取色——从首页 Hero 海报算 M3 种子色。
 *
 * 走 App 的 Coil ImageLoader（proxy:// 封面同样能取），关硬件位图、缩到 112px，
 * 用 material-color-utilities 的 QuantizerCelebi + Score（与 Android 壁纸取色同算法）取第一名做种子。
 * 结果按 URL 缓存；任何失败都返回 null，调用方不改主题（按已选主题色兜底）。
 * v1.1.22：drawable 不再只认 BitmapDrawable——动图海报（动画 WebP/GIF）解出来不是它，
 * 原先直接判 null、整页背景跟丢；改 toBitmap 通吃。另加一次重试（间隔 1 秒），
 * 首取赶上网络抖动时不至于整页无 tint。
 */
object PosterSeed {
    private val cache = HashMap<String, Int>()

    suspend fun extract(context: Context, url: String): Int? {
        synchronized(cache) { cache[url]?.let { return it } }
        val seed = extractOnce(context, url)
            ?: runCatching { delay(1000); extractOnce(context, url) }.getOrNull()
        return seed?.also {
            synchronized(cache) {
                if (cache.size > 64) cache.clear()
                cache[url] = it
            }
        }
    }

    private suspend fun extractOnce(context: Context, url: String): Int? {
        return runCatching {
            val req = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .size(112)
                .build()
            val result = context.imageLoader.execute(req) as? SuccessResult ?: return null
            val bmp: Bitmap = result.drawable.toBitmap()
            withContext(Dispatchers.Default) {
                val w = bmp.width
                val h = bmp.height
                if (w <= 0 || h <= 0) return@withContext null
                val pixels = IntArray(w * h)
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                val quantized = QuantizerCelebi.quantize(pixels, 128)
                Score.score(quantized).firstOrNull()
            }
        }.onFailure {
            LogBus.w("Theme", "海报取色失败：${it.message}")
        }.getOrNull()
    }
}
