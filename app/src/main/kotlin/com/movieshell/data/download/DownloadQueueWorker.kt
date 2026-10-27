package com.movieshell.data.download

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.movieshell.data.repo.AtomicFile
import com.movieshell.data.repo.DlStatus
import com.movieshell.data.repo.DownloadStore
import com.movieshell.data.repo.DownloadTask
import com.movieshell.data.source.DEFAULT_UA
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

    // v0.112.0 B1：有意例外——大文件下载不得加 callTimeout（整链路超时会杀死
    // 大文件下载），也不并入共享单例（共享单例有 60s callTimeout）。
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // v0.246.0：播单节流基准（Worker 每任务一实例，实例字段即任务内状态）
    private var lastRenderDone = 0
    private var lastRenderMs = 0L

    override suspend fun doWork(): Result {
        setForeground(fg("准备下载", 0))
        while (!isStopped) {
            val task = DownloadStore.claimNext(applicationContext) ?: break
            // v0.171.0：认领即标记在途，删除任务靠它等 Worker 真停
            DownloadStore.markActive(task.id)
            try {
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
            } finally {
                DownloadStore.unmarkActive(task.id)
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
            if (!f.isFile) downloadTo(remote, f, headers) { alive(task.id) }
        }

        var done = countDone(dir, pl)
        // v0.171.0：恢复下载时把边界分片（最后一个已计入的分片）重下一遍。
        // 旧版分片直接写最终文件，进程被杀时留下的半截分片与完整分片无法区分，
        // 而顺序下载决定了半截只能是最后一个已计入分片；多下一个分片的代价，
        // 换取 171 之前留下的残片在续下时必被修复（新版分片走临时文件改名，
        // 正式文件只会是完整的，边界重下只是多花一个分片）
        if (done > 0 && done <= pl.segments.size) {
            val boundary = File(dir, segLocal(done - 1, pl.segments[done - 1].url))
            if (boundary.isFile) {
                boundary.delete()
                done--
            }
        }
        renderLocalPlaylist(dir, pl, done)
        updateFg(task.title, 0)
        // v0.246.0：播单节流基准——初始渲染后记一次，后续按节流写
        lastRenderDone = done
        lastRenderMs = System.currentTimeMillis()
        val meter = SpeedMeter()
        pl.segments.forEachIndexed { i, seg ->
            if (!alive(task.id)) return
            val local = segLocal(i, seg.url)
            val f = File(dir, local)
            if (!f.isFile || f.length() == 0L) {
                downloadTo(seg.url, f, headers) { alive(task.id) }
                meter.add(f.length())
            }
            // v0.217.0（F17）：已下过的分片不能再数一遍——原来无条件 done++，
            // 续下时 10 个旧分片又数一遍，进度能超过 100%；改按下标推导：
            // 处理完第 i 个分片，前 i+1 个分片必已齐全（旧的跳过、缺的刚下完）。
            done = i + 1
            val p = done.toFloat() / pl.segments.size
            DownloadStore.update(ctx, task.id) {
                it.copy(doneSeg = done, progress = p, speedBps = meter.current())
            }
            renderLocalPlaylistThrottled(dir, pl, done)
            if (done % 5 == 0 || done == pl.segments.size) {
                updateFg(task.title, (p * 100).toInt())
            }
        }
        renderLocalPlaylist(dir, pl, pl.segments.size)
        val index = File(dir, "index.m3u8")
        DownloadStore.update(ctx, task.id, force = true) {
            it.copy(
                status = DlStatus.DONE, progress = 1f, localPath = index.absolutePath,
                error = "", completedAt = System.currentTimeMillis(),
            )
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

    // v0.246.0：播单节流——原来每分片都全量渲染 + 原子写盘，几百个分片就是
    // 几百次写盘。改每 10 片或 2 秒写一次；循环外收尾仍无条件全量写，不丢最终态。
    private fun renderLocalPlaylistThrottled(dir: File, pl: Playlist, done: Int) {
        val now = System.currentTimeMillis()
        if (done - lastRenderDone >= 10 || now - lastRenderMs >= 2000) {
            renderLocalPlaylist(dir, pl, done)
            lastRenderDone = done
            lastRenderMs = now
        }
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
        // v0.188.0：播单走 AtomicFile（L25）——边下边播期间读到的要么是旧的
        // 完整播单、要么是新的完整播单，不再是写一半的撕裂播单。
        AtomicFile.writeText(File(dir, "index.m3u8"), sb.toString())
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

    private suspend fun downloadFile(
        task: DownloadTask,
        allowRangeRestart: Boolean = true,
    ) {
        val ctx = applicationContext
        val dir = DownloadStore.taskDir(ctx, task)
        val headers = SniffPlayHeaders.peek(task.url)
        var ext = guessExt(task.url, null)
        var target = File(dir, "video.$ext")
        var part = File(dir, "video.$ext.part")
        // v0.247.0（DS11 复核）：跨重试隔离——上次 206 错位后删断点失败时，
        // 任务会被标记 resumeBlocked；标记在则跳过一切认领、从头下载
        // （截断写覆盖可疑字节），直到某次完整下载成功才清除标记。
        // 标记存在任务记录（私有存储）里，不依赖任务目录的文件操作成败，
        // 因此即使目录删/改名全失败，隔离依然成立。
        val resumeBlocked = DownloadStore.get(task.id)?.resumeBlocked == true
        // v0.247.0（DS11 复核）：任务目录只属于本任务（taskDir 按任务 id 分），
        // 目录里的 video.*.part 全是同一地址的断点文件；但认领绝不合并多个
        // 谱系——不断言较长者一定是较短者的前缀（服务器换资源、错误响应都
        // 可能让两份字节不同源），也不凭长度删掉任何一份。规则只分三种，
        // 全都不需要前缀假设：猜测的 part 存在→用它（URL 谱系，本就是续传
        // 一直信任的单谱系）；猜测的 part 不存在、恰好只剩一份闲置 part→
        // 认领它（同样是单谱系，与单 part 续传同一信任级别）；猜测的 part
        // 不存在、闲置 part 有两份及以上→一份都不认领，从头下载（多谱系歧义
        // 时不猜）。认领不删任何文件，因此不存在“删失败破坏前提”的分支。
        // 认领后 DONE 检查与 done 计数都按认领后的文件名算；若认领的是
        // 已完成文件，DONE 检查同样命中。文件名必须是 video.<ext>.part
        // （恰好两个点、扩展名 2–5 位字母数字），歧义名不认领。
        val orphans = if (resumeBlocked) {
            emptyList()
        } else {
            dir.listFiles { f ->
                f.name.startsWith("video.") && f.name.endsWith(".part")
            }?.filter { f ->
                val e = f.name.removePrefix("video.").removeSuffix(".part")
                f.name != part.name && f.name.count { c -> c == '.' } == 2 &&
                    e.length in 2..5 && e.all { c -> c.isLetterOrDigit() }
            }.orEmpty()
        }
        if (!resumeBlocked && !part.isFile && orphans.size == 1) {
            val only = orphans[0]
            ext = only.name.removePrefix("video.").removeSuffix(".part")
            target = File(dir, "video.$ext")
            part = only
        }
        if (target.isFile && target.length() > 0) {
            // v0.171.0：记录过总大小时必对长度——半截成品不再直接当 DONE；
            // 长度对不上说明是旧残留或服务端换了文件，删掉重下（续传走 .part）
            if (task.totalBytes <= 0 || target.length() == task.totalBytes) {
                DownloadStore.update(ctx, task.id, force = true) {
                    it.copy(
                        status = DlStatus.DONE, progress = 1f, localPath = target.absolutePath,
                        completedAt = System.currentTimeMillis(),
                    )
                }
                return
            }
            Log.w(TAG, "直链成品长度与记录不符，重新下载：${task.title}")
            target.delete()
        }
        var done = if (resumeBlocked) {
            // 隔离标记在：不认领任何断点，从头下载（截断写覆盖可疑字节）。
            0L
        } else if (part.isFile) {
            part.length()
        } else {
            0L
        }
        // v0.247.0（DS11 复核）：分段轮次走循环不走递归——轮数再多栈深恒定，
        // 递归只留给错位重下与探针 200 重下（各最多一次，栈安全）。
        var segmentRounds = 0
        while (true) {
            val reqB = Request.Builder().url(task.url)
                .header("User-Agent", DEFAULT_UA)
            headers.forEach { (k, v) -> reqB.header(k, v) }
            if (done > 0) reqB.header("Range", "bytes=$done-")
            // v0.247.0（DS11 复核）：206 必须核对 Content-Range 起始字节——服务端若
            // 回了错误的起始位置就按该位置续写，文件会静默损坏；不写这段 body，
            // 改删 part、重下一整份（见 use 块后的处理）。
            var rangeMismatch = false
            // v0.247.0（DS11 复核）：本轮开始时的断点长度——限段循环必须有进展
            // （done 增加）才继续；服务端限段标志见 append 块内的解析。
            val roundStartDone = done
            var cappedRange = false
            // v0.247.0（DS11 复核）：未知总量 206——Content-Range 缺失、无法解析
            // 或总量为 * 时置位；本轮长度核对不能证明文件已到尾，落位前必须探针。
            var unknownTotal206 = false
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
                // v0.247.0（DS11）：直链地址没后缀时请求前只能默认 mp4，响应头到了再按
                // Content-Type 纠正。纠正时不删任何一份 part：两份并存时闲置那份
                // 改名移开（字节保留），旧 part 再改名落位；续传语境下旧缺失、
                // 新存在是真冲突，抛错重试；从头下载时截断写会覆盖新 part，无需抛错。
                extFromContentType(ct)?.takeIf { it != ext }?.let { ctExt ->
                    val oldPart = part
                    val newPart = File(dir, "video.$ctExt.part")
                    // 续传语境（done>0）下旧缺失、新存在是真冲突：响应要从 done 起续，
                    // 工作文件却只有另一谱系的字节，抛错重试、绝不混写。
                    // 从头下载（done==0）时不冲突：后段截断写会覆盖新 part，无需抛错。
                    if (done > 0 && !oldPart.isFile && newPart.isFile) {
                        throw IllegalStateException("续传文件冲突，放弃本次下载")
                    }
                    if (oldPart.isFile && newPart.isFile) {
                        // 两份并存：不断言谁是谁的前缀，不删闲置那份——把它改名移开
                        // （字节保留在盘上），旧 part 再改名落位；移开失败则抛错，
                        // 什么都不动。移开的残留只在文件完整落位后才清理（见 DONE 后）。
                        val aside = freshAside(dir, newPart.name, "orphan")
                        if (!newPart.renameTo(aside)) {
                            throw IllegalStateException("闲置断点移开失败")
                        }
                    }
                    // 从头下载（done==0）时新 part 已存在：也不直接截断——先移开保留
                    // 字节（失败则抛错），新下载失败时旧候选仍在盘上；完整落位后由
                    // DONE 后清理。截断只发生在确认可丢弃时（完整文件已落位）。
                    if (done == 0L && newPart.isFile) {
                        val aside = freshAside(dir, newPart.name, "orphan")
                        if (!newPart.renameTo(aside)) {
                            throw IllegalStateException("旧候选移开失败")
                        }
                    }
                    if (oldPart.isFile && !oldPart.renameTo(newPart)) {
                        throw IllegalStateException("续传文件改名失败")
                    }
                    ext = ctExt
                    target = File(dir, "video.$ctExt")
                    part = newPart
                    // 改名不改变字节数，此处重读只是把不变量写进代码：后续
                    // Range 续传与进度计数用的 done 就是迁移后文件的长度。
                    done = part.length()
                }
                if (!resp.isSuccessful && resp.code != 206) throw IllegalStateException("HTTP ${resp.code}")
                val total = resp.header("Content-Length")?.toLongOrNull()?.let { it + done } ?: -1L
                val body = resp.body ?: throw IllegalStateException("空响应")
                // 断点续传：服务器忽略 Range 时从头写。注意这里删 part 只是清理，
                // 真正保证从头写的是下面 append=false 的截断打开——删失败也不影响：
                // 截断打开成功则从头写正确，打开失败则抛错、任务失败可见，绝不会
                // 在旧字节后追加 200 响应体。
                val append = resp.code == 206 && done > 0
                if (append) {
                    val cr = resp.header("Content-Range")
                    val crStart = cr
                        ?.let { Regex("""bytes (\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                    // 带 Content-Range 且起始字节 ≠ 本次 Range 起始：这段 body 绝不能续写，
                    // 否则文件静默损坏；跳过写盘循环，use 块后删 part、重下一整份。
                    // 无 Content-Range 的 206（不规范服务端）沿用旧逻辑信任它。
                    if (crStart != null && crStart != done) {
                        Log.w(TAG, "续传起始字节不符（要 $done，给 $crStart），放弃本次续传：${task.title}")
                        rangeMismatch = true
                        return@use
                    }
                }
                // v0.247.0（DS11 复核）：长度核对（done 最终值 == 本轮声明总量）
                // 只证明“服务端发来的字节全写进去了”，不证明文件已到尾——限段
                // 206（末尾未到文件尾）同样通过长度核对，因此这里另看
                // Content-Range 末尾：E+1 < 总量说明本轮只给了文件的一段，
                // 写完本轮后循环再要下一段；总量未知（缺失、无法解析或 *）时
                // 无法判断，记未知总量、落位前探针（见 use 块后处理）。
                // 206 无论是否续传都适用（无 Range 请求却回 206 的异常服务端同理）。
                if (resp.code == 206) {
                    val crEndTotal = resp.header("Content-Range")
                        ?.let { Regex("""bytes \d+-(\d+)/(\d+)""").find(it)?.groupValues }
                    if (crEndTotal != null) {
                        val end = crEndTotal[1].toLongOrNull()
                        val total = crEndTotal[2].toLongOrNull()
                        if (end != null && total != null && end + 1 < total) cappedRange = true
                    } else {
                        unknownTotal206 = true
                    }
                }
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
                // v0.247.0（DS11 复核）：长度核对——成品字节数必须等于响应声明的总量；
                // 无 Content-Encoding 时 body 长度即 wire 长度，可比；有编码时跳过
                // （压缩后长度与声明长度不可比，沿用旧行为）。不符说明下载被截断
                // 或服务端谎报长度：不落盘、不清残留、不标记 DONE，直接抛错走失败重试。
                if (total > 0 && resp.header("Content-Encoding") == null && done != total) {
                    throw IllegalStateException("下载长度不符（应 $total，实 $done）：${task.title}")
                }
            }
            // v0.247.0（DS11 复核）：续传起始字节不符时删 part、重下一整份（递归一次，
            // 第二次不再重下、仍不符直接抛错，避免无限循环）；递归的 DONE 更新与
            // 完成通知由递归调用自己做，本次直接返回，不再落盘与通知。
            // 删 part 失败时绝不把不可信的旧字节再当正常断点认领——跨重试隔离分两层：
            // ①把可疑文件改名隔离（改名后不再以 .part 结尾，认领过滤直接排除它，
            // 完整落位后的清理仍会清掉它；不依赖记录写盘）；②把任务标记
            // resumeBlocked，用同步确认式写盘（commit 返回成功才算落盘），
            // 下次重试跳过一切认领、从头下载，直到某次完整下载成功才清除标记。
            // 仅抛错不隔离是不够的——下次重试会按普通认领规则重新用上这份可疑断点。
            // 两层互不掩盖：标记更新抛错也不能跳过改名隔离，最终一定抛错
            // （不标记 DONE）。持久性范围限定为应用进程重启场景（进程退出/
            // 被杀后重进仍可读到），不是断电或底层存储故障下的绝对保证。
            // 残留窗口只剩两者同时失败（目录改名失败 +
            // 记录同步写盘失败，即任务目录与记录存储同时不可写），那时整个下载
            // 系统已不可用、处处都会失败，不属于本路径能单独兜底的情形。
            if (rangeMismatch) {
                if (!part.delete()) {
                    val quarantine = freshAside(dir, part.name, "suspect")
                    part.renameTo(quarantine)
                    val flagPersisted = try {
                        DownloadStore.updateSync(ctx, task.id) { it.copy(resumeBlocked = true) }
                    } catch (e: Exception) {
                        Log.w(TAG, "隔离标记同步写盘异常：${task.title}", e)
                        false
                    }
                    if (!flagPersisted) {
                        // 内存态仍已置位（更新函数先改内存再写盘），本进程内重试
                        // 隔离成立；跨进程恢复只靠改名隔离层。
                        Log.w(TAG, "隔离标记未确认落盘，仅靠改名隔离：${task.title}")
                    }
                    throw IllegalStateException("不可信断点清理失败，已隔离并标记：${task.title}")
                }
                if (allowRangeRestart) {
                    Log.w(TAG, "续传起始字节不符，重新完整下载：${task.title}")
                    downloadFile(task, allowRangeRestart = false)
                } else {
                    throw IllegalStateException("续传起始字节不符：${task.title}")
                }
                return
            }
            // v0.247.0（DS11 复核）：服务端限段返回的 206（Content-Range 末尾未到
            // 文件尾）不是错误——本轮字节已正确追加，循环再要下一段即可；每轮
            // 必须有进展（done 增加）且轮数有界，否则抛错，避免空轮次或
            // 无限轮次。allowRangeRestart 不消耗（这不是错位，是正常多轮）。
            if (cappedRange) {
                if (done > roundStartDone && segmentRounds < MAX_CAPPED_ROUNDS) {
                    segmentRounds++
                    continue
                } else {
                    throw IllegalStateException("限段续传无进展或超限：${task.title}")
                }
            }
            // v0.247.0（DS11 复核）：未知总量 206 不能仅凭本轮长度核对就标记
            // DONE——本轮写全只证明发来的字节写完了，不证明文件已到尾。
            // 落位前探针再要一段（只读响应码与 Content-Range、不消费 body）：
            // 206=还有字节，循环再下（计入分段轮数，同样要求有进展且有界，
            // 下一轮的起始字节核对与响应关闭照常）；200=服务端前后不一致，
            // 按错位处理（删 part 重下一整份，耗尽则抛错；删失败也不阻塞重下，
            // 截断写覆盖即可）；探针本身失败或返回其他码则抛错，不标记 DONE。
            // 416 不能无条件视为已到尾：必须自证断点就是资源末尾——
            // Content-Range 总量 == 当前断点才算证明；总量更小说明资源已缩水
            // （现有字节超长，按错位重下）；总量更大是自相矛盾，抛错；
            // 总量缺失或无法解析时不采信 416，改发第二探针取首字节读总量：
            // 首字节探针总量 == 断点才算证明；总量更大说明 416 是误报，
            // 继续下（这是重定向不是下载轮，不要求本轮进展，但计入轮数）；
            // 总量更小按错位重下；首字节探针回 200 按前后不一致处理；
            // 其他情况抛错。探针复用同一请求头（含嗅探到的播放头与 UA）。
            if (unknownTotal206) {
                val probeReq = Request.Builder().url(task.url)
                    .header("User-Agent", DEFAULT_UA)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .header("Range", "bytes=$done-").build()
                val (probeCode, probeCr) = http.newCall(probeReq).execute().use {
                    it.code to it.header("Content-Range")
                }
                when (probeCode) {
                    416 -> {
                        val total416 = probeCr
                            ?.let { Regex("""bytes \*/(\d+)""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                        when {
                            total416 == null -> {
                                val probe0Req = Request.Builder().url(task.url)
                                    .header("User-Agent", DEFAULT_UA)
                                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                                    .header("Range", "bytes=0-0").build()
                                val (code0, cr0) = http.newCall(probe0Req).execute().use {
                                    it.code to it.header("Content-Range")
                                }
                                // 第二探针保守处理：只有能证明断点位于资源末尾时
                                // 才落位；416 与其他未明确状态一律抛错，绝不默认完成。
                                // 首字节探针的总量 claim 只有在服务端确实按 0-0 回
                                // （起始为 0 且末尾为 0，与单字节区间完全一致）时
                                // 才采信，否则总量不可信，抛错。
                                val cr0Parts = cr0
                                    ?.let { Regex("""bytes (\d+)-(\d+)/(\d+)""").find(it)?.groupValues }
                                val start0 = cr0Parts?.get(1)?.toLongOrNull()
                                val end0 = cr0Parts?.get(2)?.toLongOrNull()
                                val total0 = cr0Parts?.get(3)?.toLongOrNull()
                                when {
                                    code0 == 206 && start0 == 0L && end0 == 0L && total0 != null && total0 == done -> { /* 已证到尾，按正常落位 */ }
                                    code0 == 206 && start0 == 0L && end0 == 0L && total0 != null && total0 > done -> {
                                        if (segmentRounds < MAX_CAPPED_ROUNDS) {
                                            segmentRounds++
                                            continue
                                        } else {
                                            throw IllegalStateException("分段续传超限：${task.title}")
                                        }
                                    }
                                    code0 == 206 && start0 == 0L && end0 == 0L && total0 != null -> {
                                        if (allowRangeRestart) {
                                            part.delete()
                                            downloadFile(task, allowRangeRestart = false)
                                        } else {
                                            throw IllegalStateException("资源已缩水且重下耗尽：${task.title}")
                                        }
                                        return
                                    }
                                    code0 == 200 -> {
                                        if (allowRangeRestart) {
                                            part.delete()
                                            downloadFile(task, allowRangeRestart = false)
                                        } else {
                                            throw IllegalStateException("服务端 Range 行为不一致：${task.title}")
                                        }
                                        return
                                    }
                                    code0 == 416 -> throw IllegalStateException("首字节探针回 416，资源为空或异常：${task.title}")
                                    else -> throw IllegalStateException("续传总量探针异常（$code0）：${task.title}")
                                }
                            }
                            total416 == done -> { /* 已证到尾，按正常落位 */ }
                            total416 < done -> {
                                if (allowRangeRestart) {
                                    part.delete()
                                    downloadFile(task, allowRangeRestart = false)
                                } else {
                                    throw IllegalStateException("资源已缩水且重下耗尽：${task.title}")
                                }
                                return
                            }
                            else -> throw IllegalStateException("探针总量自相矛盾：${task.title}")
                        }
                    }
                    206 -> {
                        if (done > roundStartDone && segmentRounds < MAX_CAPPED_ROUNDS) {
                            segmentRounds++
                            continue
                        } else {
                            throw IllegalStateException("分段续传无进展或超限：${task.title}")
                        }
                    }
                    200 -> {
                        if (allowRangeRestart) {
                            part.delete()
                            downloadFile(task, allowRangeRestart = false)
                        } else {
                            throw IllegalStateException("服务端 Range 行为不一致：${task.title}")
                        }
                        return
                    }
                    else -> throw IllegalStateException("续传探针异常（$probeCode）：${task.title}")
                }
            }
            // 探针 416 已证到尾、或本轮就是完整响应：跳出循环，落位并标记 DONE。
            break
        }
        finishPart(part, target)
        DownloadStore.update(ctx, task.id, force = true) {
            it.copy(
                status = DlStatus.DONE, progress = 1f, localPath = target.absolutePath, error = "",
                completedAt = System.currentTimeMillis(),
                // 完整下载成功：隔离标记清除（可疑字节已被本次完整写盘覆盖）。
                resumeBlocked = false,
            )
        }
        // v0.247.0（DS11 复核）：残留清理必须发生在完成条件之后——长度核对
        // （use 块内）已通过、文件已落位、DONE 状态已写盘，三者齐了才认定
        // 残留候选无用；仅凭落位成功就清，可能在成品实际不完整时丢掉可恢复数据。
        // 清失败只记日志，不影响已完成的下载。
        dir.listFiles { f -> f.name.startsWith("video.") && f.name.contains(".part") }
            ?.forEach { if (!it.delete()) Log.w(TAG, "残留断点清理失败：${it.name}") }
        Notify.downloadDone(ctx, task.title)
    }

    private fun guessExt(url: String, contentType: String?): String {
        url.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
            ?.let { return it }
        return extFromContentType(contentType) ?: "mp4"
    }

    // v0.247.0（DS11）：Content-Type 到扩展名的映射；未知类型返回 null，不猜。
    private fun extFromContentType(contentType: String?): String? {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            ct.contains("mp4") -> "mp4"
            ct.contains("matroska") -> "mkv"
            ct.contains("webm") -> "webm"
            ct.contains("quicktime") -> "mov"
            ct.contains("msvideo") -> "avi"
            ct.contains("mp2t") -> "ts"
            ct.contains("mpeg") -> "mpg"
            ct.contains("x-flv") -> "flv"
            ct.contains("3gpp") -> "3gp"
            else -> null
        }
    }

    // v0.247.0（DS11 复核）：生成不存在的移开文件名——时间戳 + 序号循环，
    // 绝不覆盖已有候选（同目录改名会覆盖目标，必须先判存在）。
    private fun freshAside(dir: File, baseName: String, tag: String): File {
        var n = 0
        var f = File(dir, "$baseName.$tag-" + System.currentTimeMillis())
        while (f.isFile) {
            f = File(dir, "$baseName.$tag-" + System.currentTimeMillis() + "-" + (++n))
        }
        return f
    }

    // v0.247.0（DS12 复核）：.part 落盘收尾——不再先删目标再改名（改名失败
    // 时旧目标已丢）；改走 AtomicFile 备份替换：旧目标先改名备份，落位成功
    // 后清备份，落位失败恢复旧目标；任一步失败都抛错且留着 .part 续传。
    private fun finishPart(part: File, dst: File) {
        if (!AtomicFile.replaceFile(part, dst)) throw IllegalStateException("文件落盘失败：${dst.name}")
    }

    // ---------------- 基础 ----------------

    private fun getText(url: String, headers: Map<String, String>): String {
        val reqB = Request.Builder().url(url).header("User-Agent", DEFAULT_UA)
        headers.forEach { (k, v) -> reqB.header(k, v) }
        http.newCall(reqB.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("空响应")
        }
    }

    /**
     * 小文件下载（分片/key/map）。
     * v0.171.0：先写同名 .part 文件（断点续传从 .part 长度起），整段写完
     * 才改名到最终文件——中途被杀/断网只留 .part，正式文件绝不会是半截；
     * 下次调用时 .part 在则续传，不在则重下。aliveCheck 在 64KB 块边界查，
     * 暂停/删除后最多一个块即停，不再把整段写完才响应。
     */
    private fun downloadTo(
        url: String,
        dst: File,
        headers: Map<String, String>,
        aliveCheck: (() -> Boolean)? = null,
    ) {
        val part = File(dst.parentFile, dst.name + ".part")
        var start = if (part.isFile) part.length() else 0L
        val reqB = Request.Builder().url(url).header("User-Agent", DEFAULT_UA)
        headers.forEach { (k, v) -> reqB.header(k, v) }
        if (start > 0) reqB.header("Range", "bytes=$start-")
        http.newCall(reqB.build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("空响应")
            if (resp.code != 206 && start > 0) {
                part.delete()
                start = 0
            }
            dst.parentFile?.mkdirs()
            java.io.FileOutputStream(part, start > 0).use { out ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (body.byteStream().read(buf).also { n = it } > 0) {
                    if (isStopped) throw InterruptedException("取消")
                    if (aliveCheck != null && !aliveCheck()) throw InterruptedException("已暂停")
                    out.write(buf, 0, n)
                }
            }
        }
        if (!part.isFile || part.length() == 0L) throw IllegalStateException("分片为空：$url")
        finishPart(part, dst)
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
        // v0.247.0（DS11 复核）：分段续传单次尝试的轮数上限——正常限段
        // 服务端用 MB 级分段，1 万轮可覆盖 10GB 以上文件；超出则说明服务端
        // 行为异常，直接失败而不是无限请求下去。轮数走循环，栈深恒定，
        // 上限封的是总请求数；每轮必须有进展（至少 1 字节真实文件内容），
        // 因此不存在空转，总耗时由文件大小与带宽加上轮次往返决定。
        private const val MAX_CAPPED_ROUNDS = 10_000
    }
}
