package com.movieshell.player

import android.content.Context
import com.movieshell.core.util.AtomicFile
import com.movieshell.core.log.LogBus
import com.movieshell.data.source.CmsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 外挂字幕单入口（v0.21.0）。
 *
 * - [prepare]：给定字幕地址 + 时间偏移，返回最终喂给播放器的地址。
 *   偏移为 0 时原样返回（零行为变化）；偏移非 0 时下载到私有缓存、
 *   按格式移位时间轴后返回 file:// 本地地址（双引擎统一）。
 * - 支持 .srt / .vtt / .ass（Media3 走 TEXT_SSA，libmpv 原生 libass）。
 * - 下载带播放请求头，防盗链字幕也能拉下来（私有缓存，无需存储权限）。
 */
object SubtitleKit {

    // v0.112.0 B1：字幕小文件下载，改用进程级共享单例。
    private val http = CmsApi.defaultClient()

    private val SRT_TS = Regex("""\d+:\d{2}:\d{2},\d{3}""")
    private val VTT_TS = Regex("""(?:\d+:)?\d{2}:\d{2}\.\d{3}""")
    private val ASS_TS = Regex("""\d+:\d{2}:\d{2}\.\d{2}""")

    suspend fun prepare(
        context: Context,
        url: String?,
        headers: Map<String, String>,
        offsetMs: Long,
    ): String? {
        val clean = url?.trim().orEmpty()
        if (clean.isEmpty()) return null
        if (offsetMs == 0L) return clean
        return runCatching {
            val body = downloadOnIo(clean, headers) ?: return clean
            val shifted = shift(body, extOf(clean), offsetMs) ?: return clean
            val file = cacheFile(context, clean, offsetMs)
            // v0.188.0：写盘走 AtomicFile；写成功后清同字幕其他偏移的旧文件（L25），
            // 字幕目录不再随每次改偏移无限攒旧文件（只删同 hash 前缀的旧偏移文件）。
            if (!AtomicFile.writeText(file, shifted)) throw IllegalStateException("字幕写盘失败")
            val dir = file.parentFile
            val prefix = "${clean.hashCode()}_"
            dir?.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f != file }
                ?.forEach { runCatching { it.delete() } }
            LogBus.i("Subtitle", "字幕时间轴已偏移 ${offsetMs}ms：${file.name}")
            "file://${file.absolutePath}"
        }.getOrElse {
            LogBus.w("Subtitle", "字幕偏移失败，用原地址：${it.message}")
            clean
        }
    }

    /** 挂起下载：内部切 IO 调度器，调用方在主线程协程里调也不阻塞 UI。 */
    private suspend fun downloadOnIo(url: String, headers: Map<String, String>): String? =
        withContext(Dispatchers.IO) { download(url, headers) }

    private fun extOf(url: String): String =
        url.substringBefore("?").substringAfterLast(".", "").lowercase()

    private fun cacheFile(context: Context, url: String, offsetMs: Long): File {
        val dir = File(context.cacheDir, "subtitles").also { it.mkdirs() }
        val ext = extOf(url).ifBlank { "srt" }
        return File(dir, "${url.hashCode()}_${offsetMs}.$ext")
    }

    /** 字幕下载上限 2MB：超大 ASS 动效字幕不整串进内存，超限直接用原地址。 */
    internal const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024

    private fun download(url: String, headers: Map<String, String>): String? {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> if (v.isNotBlank()) header(k, v) }
        }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            // 只读上限 +1 字节：读满即知超限，不把整个大文件读进内存。
            val buffer = okio.Buffer()
            var remaining = MAX_DOWNLOAD_BYTES + 1L
            while (remaining > 0) {
                val read = body.source().read(buffer, minOf(8192L, remaining))
                if (read == -1L) break
                remaining -= read
            }
            if (buffer.size > MAX_DOWNLOAD_BYTES) return null
            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            return buffer.readString(charset).takeIf { it.isNotBlank() }
        }
    }

    private fun shift(text: String, ext: String, offsetMs: Long): String? = when (ext) {
        "srt" -> SRT_TS.replace(text) { shiftSrtTs(it.value, offsetMs) }
        "vtt" -> VTT_TS.replace(text) { shiftVttTs(it.value, offsetMs) }
        "ass", "ssa" -> text.lineSequence().joinToString("\n") { line ->
            if (line.startsWith("Dialogue:")) {
                ASS_TS.replace(line) { shiftAssTs(it.value, offsetMs) }
            } else line
        }
        else -> null
    }

    internal fun shiftSrtTs(ts: String, offsetMs: Long): String {
        val p = ts.split(":", ",")
        val ms = (p.getOrNull(0)?.toLongOrNull() ?: return ts) * 3_600_000 +
            (p.getOrNull(1)?.toLongOrNull() ?: return ts) * 60_000 +
            (p.getOrNull(2)?.toLongOrNull() ?: return ts) * 1_000 +
            (p.getOrNull(3)?.toLongOrNull() ?: return ts)
        val n = (ms + offsetMs).coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(n / 3_600_000, (n / 60_000) % 60, (n / 1_000) % 60, n % 1_000)
    }

    internal fun shiftVttTs(ts: String, offsetMs: Long): String {
        // WebVTT 时间戳可能是 HH:MM:SS.mmm 或 MM:SS.mmm
        val p = ts.split(":", ".")
        val h = if (p.size == 4) (p[0].toLongOrNull() ?: return ts) else 0L
        val m = p.getOrNull(p.size - 3)?.toLongOrNull() ?: return ts
        val s = p.getOrNull(p.size - 2)?.toLongOrNull() ?: return ts
        val ms = p.getOrNull(p.size - 1)?.toLongOrNull() ?: return ts
        val n = (h * 3_600_000 + m * 60_000 + s * 1_000 + ms + offsetMs).coerceAtLeast(0)
        return "%02d:%02d:%02d.%03d".format(n / 3_600_000, (n / 60_000) % 60, (n / 1_000) % 60, n % 1_000)
    }

    internal fun shiftAssTs(ts: String, offsetMs: Long): String {
        val p = ts.split(":", ".")
        val ms = (p.getOrNull(0)?.toLongOrNull() ?: return ts) * 3_600_000 +
            (p.getOrNull(1)?.toLongOrNull() ?: return ts) * 60_000 +
            (p.getOrNull(2)?.toLongOrNull() ?: return ts) * 1_000 +
            (p.getOrNull(3)?.toLongOrNull() ?: return ts) * 10
        val n = (ms + offsetMs).coerceAtLeast(0)
        return "%d:%02d:%02d.%02d".format(n / 3_600_000, (n / 60_000) % 60, (n / 1_000) % 60, (n % 1_000) / 10)
    }
}
