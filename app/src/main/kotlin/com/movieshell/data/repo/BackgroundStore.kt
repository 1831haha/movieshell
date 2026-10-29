package com.movieshell.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * v0.82.0：自定义背景底图。
 *
 * 用户从相册选图 → 拷贝到应用私有目录 bg_custom.jpg → 首页用它做底图（压深色罩保证文字可读）。
 * 不用系统壁纸 API，只做 App 内背景。
 *
 * v0.273.0（T33）：原来原图直存（手机照片动辄几 MB、几千万像素，底图用不上），
 * 且 copyTo 覆盖非原子，写一半崩了旧背景就坏了。现在：先解码、长边压到 1920、
 * JPEG 85 压缩、写同目录临时文件再经 AtomicFile.replaceFile 原子落位；
 * 解码失败（不是图片）直接返回失败，不动旧背景。
 */
object BackgroundStore {
    private const val TAG = "BackgroundStore"
    private const val FILE_NAME = "bg_custom.jpg"
    private const val MAX_EDGE = 1920
    private const val JPEG_QUALITY = 85

    private val _customBg = MutableStateFlow<File?>(null)
    val customBg: StateFlow<File?> = _customBg

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        val f = file(context)
        _customBg.value = if (f.isFile && f.length() > 0) f else null
    }

    /** 从相册选图设为底图，返回是否成功。 */
    suspend fun setCustomBg(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: return@runCatching false
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false
            // 先按 2 的幂次采样到长边 3840 以内，防大图整张解码 OOM，再精确缩到 1920。
            var sample = 1
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (longEdge / sample > MAX_EDGE * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            } ?: return@runCatching false
            val scaled = if (maxOf(decoded.width, decoded.height) > MAX_EDGE) {
                val ratio = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
                val out = Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
                if (out !== decoded) decoded.recycle()
                out
            } else {
                decoded
            }
            val dest = file(context)
            val tmp = File(context.filesDir, "$FILE_NAME.${java.util.UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                        return@runCatching false
                    }
                }
                if (!AtomicFile.replaceFile(tmp, dest)) return@runCatching false
            } finally {
                scaled.recycle()
                if (tmp.exists()) tmp.delete()
            }
            _customBg.value = dest
            true
        }.onFailure { Log.w(TAG, "设置背景底图失败", it) }.getOrDefault(false)
    }

    /**
     * v0.273.0（T33）：返回是否真的清掉了——原来删没删掉都返回 Unit，
     * 还无条件把内存状态置空，文件还在、下次 load 又复活，调用方却已提示“已恢复”。
     * 文件本来就不存在算成功；删失败则内存状态不动，调用方按失败提示。
     */
    suspend fun clearCustomBg(context: Context): Boolean = withContext(Dispatchers.IO) {
        val f = file(context)
        if (!f.exists()) {
            _customBg.value = null
            return@withContext true
        }
        val ok = runCatching { f.delete() }.getOrDefault(false) && !f.exists()
        if (ok) _customBg.value = null
        ok
    }
}
