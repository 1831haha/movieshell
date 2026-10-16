package com.movieshell.data.download

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.movieshell.data.repo.DlStatus
import com.movieshell.data.repo.DownloadStore
import com.movieshell.data.repo.DownloadTask
import com.movieshell.player.M3u8AdFilter
import com.movieshell.player.SniffPlayHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 下载队列 Worker（v0.26.0）。
 *
 * 每个实例循环 claimNext() 原子认领排队任务，逐个下载；无任务即退出。
 * 并行数由 DownloadManager.pump 按设置起 N 个实例。
 * 暂停/取消靠 DownloadStore 状态位协作（每分片检查一次）。
 */
class DownloadQueueWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        setForeground(fg("准备下载", 0))
        while (!isStopped) {
            val task = DownloadStore.claimNext(applicationContext) ?: break
            // v0.41.0：失败时带上真实错误原因，不再是笼统的"下载失败"
            val failReason: String? = try {
                downloadTask(task)
                null
            } catch (e: Exception) {
                e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            }
            if (isStopped) {
                DownloadStore.setStatus(applicationContext, task.id, DlStatus.PAUSED)
                break
            }
            if (failReason != null && DownloadStore.get(task.id)?.status == DlStatus.DOWNLOADING) {
                Log.w(TAG, "下载失败：${task.title}，原因=$failReason")
                DownloadStore.setStatus(
                    applicationContext, task.id, DlStatus.FAILED, failReason.take(120)
                )
            }
        }
        return Result.success()
    }

    // ---------------- 调度 ----------------

    private suspend fun downloadTask(task: DownloadTask): Boolean {
        return withContext(Dispatchers.IO) {
            var attempt = 0
            var lastErr: Exception? = null
            while (attempt < 3) {
                if (!alive(task.id)) return@withContext true // 被暂停/删除：不算失败
                try {
                    if (task.kind == "m3u8") downloadM3u8(task) else downloadFile(task)
                    return@withContext true
                } catch (e: Exception) {
                    lastErr = e
                    attempt++
                    Log.w(TAG, "下载尝试 $attempt 失败：${task.title}", e)
                    if (attempt < 3) kotlinx.coroutines.delay(2000L * attempt)
                }
            }
            throw lastErr ?: IllegalStateException("下载失败")
        }
    }

    /** 任务是否还该继续（外部暂停/删除则停）。 */
    private fun alive(id: String): Boolean =
        !isStopped && DownloadStore.get(id)?.status == DlStatus.DOWNLOADING

    // ---------------- m3u8 ----------------

    private suspend fun downloadM3u8(task: DownloadTask) {
        val ctx = applicationContext
        val dir = DownloadStore.taskDir(ctx, task)
        val headers = SniffPlayHeaders.peek(task.url)

        var base = task.url
        var text = getText(task.url, headers)
        // 主 playlist：选最大码率的分流
        pickVariant(text, base)?.let { vUrl ->
            base = vUrl
            text = getText(vUrl, headers)
        }
        val filtered = M3u8AdFilter.filter(text, base)
        val pl = parsePlaylist(filtered, base)
        if (pl.segments.isEmpty()) throw IllegalStateException("m3u8 无分片")

        DownloadStore.update(ctx, task.id, force = true) {
            it.copy(totalSeg = pl.segments.size, progress = 0f)
        }

        // 先下 key/map（小文件）
        pl.assets.forEach { (remote, local) ->
            if (!alive(task.id)) return
            val f = File(dir, local)
            if (!f.isFile) downloadTo(remote, f, headers, 0L)
        }

        var done = countDone(dir, pl)
        renderLocalPlaylist(dir, pl, done)
        updateFg(task.title, 0)
        val meter = SpeedMeter()
        pl.segments.forEachIndexed { i, seg ->
            if (!alive(task.id)) return
            val local = segLocal(i, seg.url)
            val f = File(dir, local)
            if (!f.isFile || f.length() == 0L) {
                downloadTo(seg.url, f, headers, 0L)
                meter.add(f.length())
            }
            done++
            val p = done.toFloat() / pl.segments.size
            DownloadStore.update(ctx, task.id) {
                it.copy(doneSeg = done, progress = p, speedBps = meter.current())
            }
            renderLocalPlaylist(dir, pl, done)
            if (done % 5 == 0 || done == pl.segments.size) {
                updateFg(task.title, (p * 100).toInt())
            }
        }
        renderLocalPlaylist(dir, pl, pl.segments.size)
        val index = File(dir, "index.m3u8")
        DownloadStore.update(ctx, task.id, force = true) {
            it.copy(status = DlStatus.DONE, progress = 1f, localPath = index.absolutePath, error = "")
        }
        Notify.downloadDone(ctx, task.title)
        Log.i(TAG, "m3u8 下载完成：${task.title}（${pl.segments.size} 分片）")
    }

    private data class Seg(val url: String)
    private data class Playlist(
        val lines: List<String>,
        val segments: List<Seg>,
        /** 远程地址 -> 本地文件名（key/map） */
        val assets: Map<String, String>,
        /** 每行对应的分片下标（-1=非分片行） */
        val segIndexOfLine: List<Int>,
    )

    private fun parsePlaylist(content: String, base: String): Playlist {
        val lines = content.lines()
        val segments = mutableListOf<Seg>()
        val assets = linkedMapOf<String, String>()
        val segIdx = mutableListOf<Int>()
        val keyRe = Regex("""URI="([^"]+)"""")
        lines.forEach { raw ->
            val t = raw.trim()
            var idx = -1
            when {
                t.startsWith("#EXT-X-KEY") || t.startsWith("#EXT-X-MAP") -> {
                    keyRe.find(t)?.groupValues?.get(1)?.let { u ->
                        val abs = absUrl(base, u)
                        val local = "asset_${assets.size}_${fileNameOf(u)}"
                        assets.putIfAbsent(abs, local)
                    }
                }
                t.isNotEmpty() && !t.startsWith("#") -> {
                    idx = segments.size
                    segments.add(Seg(absUrl(base, t)))
                }
            }
            segIdx.add(idx)
        }
        return Playlist(lines, segments, assets, segIdx)
    }

    /** 只渲染已下载的分片（边下边播可直接播这个文件）。 */
    private fun renderLocalPlaylist(dir: File, pl: Playlist, done: Int) {
        val keyRe = Regex("""URI="([^"]+)"""")
        val sb = StringBuilder()
        pl.lines.forEachIndexed { li, raw ->
            val t = raw.trim()
            val si = pl.segIndexOfLine[li]
            if (si >= 0) {
                if (si < done) {
                    // 前面的 EXTINF 行在 TextLine 里已输出，这里只写本地文件名
                    sb.appendLine(segLocal(si, pl.segments[si].url))
                }
                // 未下载的分片整段跳过（含其 EXTINF：见下）
            } else if (t.startsWith("#EXTINF") || t.startsWith("#EXT-X-DISCONTINUITY")) {
                // EXTINF/DISCONTINUITY 只在紧随的分片已下载时输出
                val nextSeg = ((li + 1) until pl.lines.size)
                    .firstOrNull { pl.segIndexOfLine[it] >= 0 }
                    ?.let { pl.segIndexOfLine[it] } ?: Int.MAX_VALUE
                if (nextSeg < done) sb.appendLine(raw)
            } else if (t.startsWith("#EXT-X-KEY") || t.startsWith("#EXT-X-MAP")) {
                var out = raw
                keyRe.find(t)?.groupValues?.get(1)?.let { u ->
                    // base 在解析时已确定，这里用相对 key 匹配 assets
                    pl.assets.entries.firstOrNull { it.key.endsWith(u) || u.endsWith(it.key) }
                        ?.let { out = keyRe.replace(raw, "URI=\"${it.value}\"") }
                }
                sb.appendLine(out)
            } else {
                sb.appendLine(raw)
            }
        }
        File(dir, "index.m3u8").writeText(sb.toString())
    }

    private fun segLocal(i: Int, url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "")
            .takeIf { it.length in 1..5 } ?: "ts"
        return "seg_%05d.%s".format(i, ext)
    }

    private fun countDone(dir: File, pl: Playlist): Int {
        var n = 0
        pl.segments.forEachIndexed { i, seg ->
            val f = File(dir, segLocal(i, seg.url))
            if (f.isFile && f.length() > 0) n++ else return n
        }
        return n
    }

    private fun pickVariant(content: String, base: String): String? {
        val re = Regex("""#EXT-X-STREAM-INF:([^\r\n]*)\r?\n([^\r\n#][^\r\n]*)""")
        var best: String? = null
        var bestBw = -1L
        re.findAll(content).forEach { m ->
            val bw = Regex("""BANDWIDTH=(\d+)""").find(m.groupValues[1])
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            if (bw >= bestBw) {
                bestBw = bw
                best = absUrl(base, m.groupValues[2].trim())
            }
        }
        return best
    }

    // ---------------- 直链文件 ----------------

    private suspend fun downloadFile(task: DownloadTask) {
        val ctx = applicationContext
        val dir = DownloadStore.taskDir(ctx, task)
        val headers = SniffPlayHeaders.peek(task.url)
        val ext = guessExt(task.url, null)
        val target = File(dir, "video.$ext")
        val part = File(dir, "video.$ext.part")
        if (target.isFile && target.length() > 0) {
            DownloadStore.update(ctx, task.id, force = true) {
                it.copy(status = DlStatus.DONE, progress = 1f, localPath = target.absolutePath)
            }
            return
        }
        var done = if (part.isFile) part.length() else 0L
        val reqB = Request.Builder().url(task.url)
            .header("User-Agent", UA)
        headers.forEach { (k, v) -> reqB.header(k, v) }
        if (done > 0) reqB.header("Range", "bytes=$done-")
        http.newCall(reqB.build()).execute().use { resp ->
            // v0.41.0：有些 m3u8 地址不带扩展名，按 Content-Type 识别后转分片下载，
            // 否则会把 playlist 文本当视频存下来
            val ct = resp.header("Content-Type").orEmpty()
            if (ct.contains("mpegurl", ignoreCase = true)) {
                Log.i(TAG, "Content-Type 识别为 m3u8，转分片下载：${task.title}")
                DownloadStore.update(ctx, task.id, force = true) { it.copy(kind = "m3u8") }
                downloadM3u8(task.copy(kind = "m3u8"))
                return
            }
            if (!resp.isSuccessful && resp.code != 206) throw IllegalStateException("HTTP ${resp.code}")
            val total = resp.header("Content-Length")?.toLongOrNull()?.let { it + done } ?: -1L
            val body = resp.body ?: throw IllegalStateException("空响应")
            // 断点续传：服务器忽略 Range 时从头写
            val append = resp.code == 206 && done > 0
            if (!append && done > 0) {
                part.delete()
                done = 0
            }
            java.io.FileOutputStream(part, append).use { out ->
                val buf = ByteArray(64 * 1024)
                val meter = SpeedMeter()
                var n: Int
                var lastTick = 0L
                while (body.byteStream().read(buf).also { n = it } > 0) {
                    if (!alive(task.id)) return
                    out.write(buf, 0, n)
                    done += n
                    meter.add(n.toLong())
                    val now = System.currentTimeMillis()
                    if (now - lastTick > 800) {
                        lastTick = now
                        val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                        DownloadStore.update(ctx, task.id) {
                            it.copy(
                                doneBytes = done, totalBytes = total, progress = p,
                                speedBps = meter.current(),
                            )
                        }
                        if (total > 0) updateFg(task.title, (p * 100).toInt())
                    }
                }
            }
        }
        if (!part.renameTo(target)) throw IllegalStateException("文件落盘失败")
        DownloadStore.update(ctx, task.id, force = true) {
            it.copy(status = DlStatus.DONE, progress = 1f, localPath = target.absolutePath, error = "")
        }
        Notify.downloadDone(ctx, task.title)
    }

    private fun guessExt(url: String, contentType: String?): String {
        url.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
            ?.let { return it }
        return when {
            contentType?.contains("mp4") == true -> "mp4"
            else -> "mp4"
        }
    }

    // ---------------- 基础 ----------------

    private fun getText(url: String, headers: Map<String, String>): String {
        val reqB = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> reqB.header(k, v) }
        http.newCall(reqB.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("空响应")
        }
    }

    /** 小文件下载（分片/key/map），带断点续传。 */
    private fun downloadTo(url: String, dst: File, headers: Map<String, String>, resumeFrom: Long) {
        val reqB = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> reqB.header(k, v) }
        var start = if (dst.isFile) dst.length() else 0L
        if (start > 0) reqB.header("Range", "bytes=$start-")
        http.newCall(reqB.build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("空响应")
            if (resp.code != 206 && start > 0) {
                dst.delete()
                start = 0
            }
            dst.parentFile?.mkdirs()
            java.io.FileOutputStream(dst, start > 0).use { out ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (body.byteStream().read(buf).also { n = it } > 0) {
                    if (isStopped) throw InterruptedException("取消")
                    out.write(buf, 0, n)
                }
            }
        }
        if (!dst.isFile || dst.length() == 0L) throw IllegalStateException("分片为空：$url")
    }

    private fun absUrl(base: String, rel: String): String {
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        return runCatching { URL(URL(base), rel).toString() }.getOrDefault(rel)
    }

    /**
     * 滑动窗口测速（v0.41.0）：按 1 秒窗口统计写入字节，任务列表实时显示用。
     * 只活在 worker 内存里，不追求精确落盘。
     */
    private class SpeedMeter {
        private var windowStart = System.currentTimeMillis()
        private var windowBytes = 0L
        private var lastSpeed = 0L

        @Synchronized
        fun add(n: Long) {
            windowBytes += n
            val now = System.currentTimeMillis()
            val dt = now - windowStart
            if (dt >= 1000) {
                lastSpeed = if (dt > 0) windowBytes * 1000 / dt else 0L
                windowBytes = 0L
                windowStart = now
            }
        }

        @Synchronized
        fun current(): Long = lastSpeed
    }

    private fun fileNameOf(url: String): String =
        url.substringBefore('?').substringAfterLast('/').take(32).ifBlank { "a.bin" }

    private fun fg(title: String, progress: Int): ForegroundInfo {
        return ForegroundInfo(
            FG_ID,
            Notify.downloadProgress(applicationContext, title, progress),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun updateFg(title: String, progress: Int) {
        runCatching {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(FG_ID, Notify.downloadProgress(applicationContext, title, progress))
        }
    }

    companion object {
        private const val TAG = "Download"
        private const val FG_ID = 1001
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}
