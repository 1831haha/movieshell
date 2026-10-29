package com.movieshell.player

import android.content.Context
import android.net.Uri
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.MusicApi
import com.movieshell.data.repo.MusicStore
import com.movieshell.ui.components.AppToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v0.278.0：音乐下载——第一版只做单首直下，不走视频侧 WorkManager 队列
 * （不断点续传、不排队、不显示进度条，只给下载中/已下载状态 + 成败提示）。
 *
 * 直链复用 [MusicPlayer.resolvePlayUrl]（播放同一条解析链，不另写第二套）；
 * 文件落 App 私有目录 music_downloads，下完以 Track 形式记进本地音乐表
 * （id 沿用原曲 id，localUri 为 file://，本地 tab 直接播、删歌时连文件一起删）；
 * 离页/切页不取消——scope 与页面无关，进程在就下完。
 */
object MusicDownloader {
    private const val DIR_NAME = "music_downloads"

    // v0.112.0 B1 同款例外：大文件下载不得加 callTimeout，也不并入 60 秒共享单例。
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    /** 读本地表，把已落盘的下载曲 id 刷进状态（页面进页/切歌时调一次即可）。 */
    suspend fun refreshDownloaded(context: Context) {
        val ids = withContext(Dispatchers.IO) {
            runCatching { MusicStore.localList(context) }
                .getOrDefault(emptyList())
                .filter { isDownloadedFile(context, it) }
                .map { it.id }
                .toSet()
        }
        _downloaded.value = ids
    }

    fun isDownloadedFile(context: Context, track: MusicApi.Track): Boolean {
        val uri = track.localUri
        if (uri.isBlank()) return false
        val path = runCatching { Uri.parse(uri).path }.getOrNull() ?: return false
        return path.startsWith(File(context.filesDir, DIR_NAME).absolutePath + File.separator)
    }

    /** 删本地歌时顺带删下载文件——只删 music_downloads 里的 file://，用户导入的歌不动。 */
    fun deleteDownloadedFile(context: Context, track: MusicApi.Track) {
        if (!isDownloadedFile(context, track)) return
        val path = runCatching { Uri.parse(track.localUri).path }.getOrNull() ?: return
        runCatching { File(path).delete() }
        _downloaded.value = _downloaded.value - track.id
    }

    fun download(context: Context, track: MusicApi.Track) {
        if (track.isLocal) return
        val appContext = context.applicationContext
        if (track.id in _downloading.value) return
        if (track.id in _downloaded.value) {
            AppToast.show(context, "已经下载过了，去本地 tab 听", long = false)
            return
        }
        _downloading.value = _downloading.value + track.id
        AppToast.show(context, "正在下载《${track.name}》", long = false)
        scope.launch {
            val result = runCatching {
                val url = MusicPlayer.resolvePlayUrl(appContext, track)
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("取不到播放地址")
                val dir = File(appContext.filesDir, DIR_NAME).apply { mkdirs() }
                // 文件名带曲 id hash——同名同歌手在不同平台是两条记录，文件名不能撞
                val fileKey = track.id.hashCode().toString().replace("-", "n")
                val base = sanitizeFileName("${track.artist} - ${track.name}") + "_$fileKey"
                val tmp = File(dir, "$base.part")
                var finalFile: File? = null
                try {
                    http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("下载失败：HTTP ${resp.code}")
                        }
                        val body = resp.body ?: throw IllegalStateException("下载失败：空响应")
                        val ext = extFromContentType(resp.header("Content-Type").orEmpty())
                            ?: extFromUrl(url)
                            ?: "mp3"
                        finalFile = File(dir, "$base.$ext")
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var n: Int
                            val stream = body.byteStream()
                            while (stream.read(buf).also { n = it } > 0) {
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    val file = finalFile ?: throw IllegalStateException("下载失败：无文件")
                    if (!tmp.renameTo(file)) {
                        tmp.copyTo(file, overwrite = true)
                        tmp.delete()
                    }
                    val localTrack = track.copy(localUri = Uri.fromFile(file).toString())
                    val saved = MusicStore.addLocal(appContext, localTrack)
                    if (!saved) throw IllegalStateException("保存失败")
                    file
                } catch (e: Exception) {
                    tmp.delete()
                    finalFile?.takeIf { it.isFile }?.delete()
                    throw e
                }
            }
            _downloading.value = _downloading.value - track.id
            result.onSuccess {
                _downloaded.value = _downloaded.value + track.id
                LogBus.i("Music", "已下载：${track.name}")
                AppToast.show(appContext, "已下载《${track.name}》，去本地 tab 听", long = false)
            }.onFailure {
                LogBus.w("Music", "下载失败：${track.name} ${it.message}")
                AppToast.show(
                    appContext,
                    "下载失败：${it.message?.takeIf { m -> m.isNotBlank() } ?: it.javaClass.simpleName}",
                    long = false,
                )
            }
        }
    }

    private fun extFromContentType(ct: String): String? = when {
        ct.contains("audio/mpeg", ignoreCase = true) -> "mp3"
        ct.contains("audio/flac", ignoreCase = true) -> "flac"
        ct.contains("audio/x-flac", ignoreCase = true) -> "flac"
        ct.contains("audio/wav", ignoreCase = true) -> "wav"
        ct.contains("audio/x-wav", ignoreCase = true) -> "wav"
        ct.contains("audio/mp4", ignoreCase = true) -> "m4a"
        ct.contains("audio/ogg", ignoreCase = true) -> "ogg"
        ct.contains("audio/aac", ignoreCase = true) -> "aac"
        else -> null
    }

    private fun extFromUrl(url: String): String? {
        val path = url.substringBefore("?").substringAfterLast("/")
        val ext = path.substringAfterLast(".", "").lowercase()
        return ext.takeIf { it in setOf("mp3", "flac", "wav", "m4a", "ogg", "aac") }
    }

    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "未知歌曲" }
        return cleaned.take(80)
    }
}
