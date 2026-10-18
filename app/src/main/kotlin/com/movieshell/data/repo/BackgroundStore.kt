package com.movieshell.data.repo

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v0.82.0：自定义背景底图。
 *
 * 用户从相册选图 → 拷贝到应用私有目录 bg_custom.jpg → 首页用它做底图（压深色罩保证文字可读）。
 * 不用系统壁纸 API，只做 App 内背景。
 */
object BackgroundStore {
    private const val TAG = "BackgroundStore"
    private const val FILE_NAME = "bg_custom.jpg"

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
            val tmp = File(context.cacheDir, "bg_tmp.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return@runCatching false
            if (tmp.length() == 0L) return@runCatching false
            val dest = file(context)
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
            _customBg.value = dest
            true
        }.onFailure { Log.w(TAG, "设置背景底图失败", it) }.getOrDefault(false)
    }

    suspend fun clearCustomBg(context: Context) = withContext(Dispatchers.IO) {
        runCatching { file(context).delete() }
        _customBg.value = null
    }
}
