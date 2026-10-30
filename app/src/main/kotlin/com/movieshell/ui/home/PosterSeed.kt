package com.movieshell.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v0.292.0 V5：海报取色——从首页 Hero 海报算 M3 种子色。
 *
 * 走 App 的 Coil ImageLoader（proxy:// 封面同样能取），关硬件位图、缩到 112px，
 * 用 material-color-utilities 的 QuantizerCelebi + Score（与 Android 壁纸取色同算法）取第一名做种子。
 * 结果按 URL 缓存；任何失败都返回 null，调用方不改主题（按已选主题色兜底）。
 */
object PosterSeed {
    private val cache = HashMap<String, Int>()

    suspend fun extract(context: Context, url: String): Int? {
        synchronized(cache) { cache[url]?.let { return it } }
        return runCatching {
            val req = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .size(112)
                .build()
            val result = context.imageLoader.execute(req) as? SuccessResult ?: return null
            val bmp: Bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
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
        }.getOrNull()?.also { seed ->
            synchronized(cache) {
                if (cache.size > 64) cache.clear()
                cache[url] = seed
            }
        }
    }
}
