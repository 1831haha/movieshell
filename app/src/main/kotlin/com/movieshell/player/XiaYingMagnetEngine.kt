package com.movieshell.player

import android.content.Context
import com.movieshell.data.repo.LogBus
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * v0.294.24：侠影自建磁力转播（libtorrent4j + NanoHTTPD）。
 * 不依赖 DsPlayer / Node / WebTorrent 插件；点播 magnet 时进程内取元数据、
 * 顺序下最大视频文件，经 127.0.0.1 回环 HTTP（支持 Range）交给 Media3/mpv。
 */
object XiaYingMagnetEngine {

    private const val TAG = "XiaYingMagnet"
    private const val META_TIMEOUT_SEC = 90
    private const val PIECE_WAIT_MS = 60_000L

    data class Ready(val streamUrl: String)
    data class Fail(val message: String)

    @Volatile private var session: SessionManager? = null
    @Volatile private var http: Loopback? = null
    @Volatile private var saveRoot: File? = null
    private val torrents = ConcurrentHashMap<String, Active>() // infohash hex ->
    private val lock = Any()

    private data class Active(
        val handle: TorrentHandle,
        val fileIndex: Int,
        val filePath: File,
        val fileSize: Long,
        val name: String,
    )

    fun isNativeAvailable(): Boolean = try {
        Class.forName("org.libtorrent4j.SessionManager")
        true
    } catch (_: Throwable) {
        false
    }

    @Volatile
    var lastStatus: String = ""
        private set

    private fun status(msg: String) {
        lastStatus = msg
        LogBus.i(TAG, msg)
    }

    private fun ensureSession(context: Context) {
        synchronized(lock) {
            if (session?.isRunning == true && http != null) return
            val root = File(context.filesDir, "magnet_cache").also { it.mkdirs() }
            saveRoot = root
            val sm = SessionManager()
            val sp = SettingsPack().apply {
                // 移动网络友好：限制连接数
                connectionsLimit(100)
            }
            sm.start(SessionParams(sp))
            session = sm
            val srv = Loopback()
            srv.start()
            http = srv
            status("磁力引擎已启动（端口 ${srv.listeningPort}）")
        }
    }

    /**
     * 准备可播的 HTTP 地址。失败返回中文说明。
     */
    suspend fun prepare(context: Context, magnet: String): Any = withContext(Dispatchers.IO) {
        if (!isNativeAvailable()) return@withContext Fail("本机构建未包含磁力引擎")
        try {
            ensureSession(context)
        } catch (t: Throwable) {
            LogBus.e(TAG, "启动失败：${t.message}")
            return@withContext Fail("磁力引擎启动失败：${t.message}")
        }
        val sm = session ?: return@withContext Fail("磁力引擎未就绪")
        val srv = http ?: return@withContext Fail("本地转播端口未就绪")
        val root = saveRoot ?: return@withContext Fail("缓存目录不可用")

        status(MagnetStreamLogic.statusFetching(0))
        // 先拉元数据（最多 90s）
        val data = withTimeoutOrNull((META_TIMEOUT_SEC + 5) * 1000L) {
            var elapsed = 0
            val holder = AtomicReference<ByteArray?>(null)
            val th = Thread({
                holder.set(
                    runCatching { sm.fetchMagnet(magnet, META_TIMEOUT_SEC, root) }.getOrNull(),
                )
            }, "magnet-meta").also { it.isDaemon = true; it.start() }
            while (th.isAlive) {
                delay(1000)
                elapsed++
                status(MagnetStreamLogic.statusFetching(elapsed))
            }
            holder.get()
        }
        if (data == null) {
            return@withContext Fail(
                "获取磁力元数据超时（${META_TIMEOUT_SEC}s）。资源可能无人做种，或当前网络访问 DHT 受限。可换网络重试，或用其他应用打开。",
            )
        }
        val ti = try {
            TorrentInfo(data)
        } catch (t: Throwable) {
            return@withContext Fail("磁力元数据解析失败：${t.message}")
        }
        val hash = ti.infoHash().toHex().lowercase()
        torrents[hash]?.let {
            status("已在缓冲：${it.name}")
            return@withContext Ready("http://127.0.0.1:${srv.listeningPort}/stream/$hash")
        }

        val files = (0 until ti.numFiles()).map { i ->
            MagnetStreamLogic.FileCand(i, ti.files().fileName(i), ti.files().fileSize(i))
        }
        val pick = MagnetStreamLogic.pickPlayFile(files)
            ?: return@withContext Fail("种子里没有可播放的文件")
        status("选定「${pick.name}」（${pick.size / 1024 / 1024}MB），开始缓冲…")

        val prios = Priority.array(Priority.IGNORE, ti.numFiles())
        prios[pick.index] = Priority.TOP_PRIORITY
        val added = CountDownLatch(1)
        val handleRef = AtomicReference<TorrentHandle?>(null)
        val listener = object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.ADD_TORRENT.swig(),
                AlertType.METADATA_RECEIVED.swig(),
            )
            override fun alert(alert: Alert<*>) {
                when (alert.type()) {
                    AlertType.ADD_TORRENT -> {
                        val h = (alert as AddTorrentAlert).handle()
                        handleRef.set(h)
                        runCatching {
                            h.prioritizeFiles(prios)
                            h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                            h.resume()
                            // 先催前几片，方便起播
                            val pieceLen = ti.pieceLength().toLong().coerceAtLeast(1)
                            val firstPieces = ((8L * 1024 * 1024) / pieceLen).toInt().coerceAtLeast(4)
                            for (p in 0 until firstPieces.coerceAtMost(ti.numPieces())) {
                                h.piecePriority(p, Priority.TOP_PRIORITY)
                                h.setPieceDeadline(p, p * 50)
                            }
                        }
                        added.countDown()
                    }
                    AlertType.METADATA_RECEIVED -> {
                        val h = (alert as MetadataReceivedAlert).handle()
                        handleRef.compareAndSet(null, h)
                    }
                    else -> {}
                }
            }
        }
        sm.addListener(listener)
        try {
            sm.download(ti, root, null, prios, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            if (!added.await(30, TimeUnit.SECONDS)) {
                return@withContext Fail("加入下载队列超时")
            }
        } finally {
            sm.removeListener(listener)
        }
        val handle = handleRef.get() ?: sm.find(ti.infoHash())
            ?: return@withContext Fail("无法取得下载句柄")
        val rel = ti.files().filePath(pick.index)
        val path = File(root, rel)
        torrents[hash] = Active(handle, pick.index, path, pick.size, pick.name)

        // 等到文件头有数据再交播放器（最多再等 60s）
        val headReady = withTimeoutOrNull(60_000L) {
            var n = 0
            while (true) {
                val st = handle.status()
                val peers = st.numPeers()
                val pct = (st.progress() * 100).toInt().coerceIn(0, 100)
                status(MagnetStreamLogic.statusPeers(peers, pct))
                if (path.exists() && path.length() > 256 * 1024) return@withTimeoutOrNull true
                if (handle.havePiece(0)) return@withTimeoutOrNull true
                delay(500)
                n++
                if (n > 120) return@withTimeoutOrNull false
            }
            @Suppress("UNREACHABLE_CODE") false
        } == true
        if (!headReady) {
            status("仍在找源，先尝试起播…")
        }
        Ready("http://127.0.0.1:${srv.listeningPort}/stream/$hash")
    }

    fun cancel(hashHex: String) {
        val a = torrents.remove(hashHex) ?: return
        runCatching { session?.remove(a.handle) }
    }

    /** 供 HTTP 服务读文件：等到 [offset, offset+len) 覆盖的 piece 就绪。 */
    private fun waitPieces(hash: String, offset: Long, length: Long): Active? {
        val a = torrents[hash] ?: return null
        val h = a.handle
        if (!h.isValid) return null
        val ti = h.torrentFile() ?: return a
        val pieceLen = ti.pieceLength().toLong().coerceAtLeast(1)
        val fileOff = ti.files().fileOffset(a.fileIndex)
        val startPiece = ((fileOff + offset) / pieceLen).toInt()
        val endPiece = ((fileOff + offset + length - 1) / pieceLen).toInt()
            .coerceAtMost(ti.numPieces() - 1)
        val deadline = System.currentTimeMillis() + PIECE_WAIT_MS
        for (p in startPiece..endPiece) {
            h.piecePriority(p, Priority.TOP_PRIORITY)
            h.setPieceDeadline(p, ((p - startPiece) * 40).coerceAtLeast(0))
        }
        for (p in startPiece..endPiece) {
            while (!h.havePiece(p)) {
                if (System.currentTimeMillis() > deadline) return a // 尽力而为，让播放器重试
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    return a
                }
            }
        }
        return a
    }

    private class Loopback : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri ?: "/"
            if (uri == "/healthz") {
                return newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true,"engine":"xiaying"}""")
            }
            val m = Regex("""^/stream/([a-fA-F0-9]+)""").find(uri)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            val hash = m.groupValues[1].lowercase()
            val active = torrents[hash]
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "torrent gone")
            val total = active.fileSize
            val rangeHdr = session.headers["range"]
            val range = MagnetStreamLogic.parseRange(rangeHdr, total)
            val start = range?.start ?: 0L
            val end = range?.end ?: (total - 1)
            val len = (end - start + 1).coerceAtLeast(0)
            waitPieces(hash, start, len)
            // 文件可能尚未扩到足够长度：等到 length > start 或超时
            val fileWaitDeadline = System.currentTimeMillis() + PIECE_WAIT_MS
            while ((!active.filePath.exists() || active.filePath.length() <= start) &&
                System.currentTimeMillis() < fileWaitDeadline
            ) {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (!active.filePath.exists()) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "buffering")
            }
            val fis = FileInputStream(active.filePath)
            fis.skip(start)
            val body: InputStream = object : FilterInputStream(fis) {
                var left = len
                override fun read(b: ByteArray, off: Int, l: Int): Int {
                    if (left <= 0) return -1
                    val n = super.read(b, off, minOf(l.toLong(), left).toInt())
                    if (n > 0) left -= n
                    return n
                }
                override fun read(): Int {
                    if (left <= 0) return -1
                    val c = super.read()
                    if (c >= 0) left--
                    return c
                }
            }
            val mime = guessMime(active.name)
            return if (range != null) {
                newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, body, len).also {
                    it.addHeader("Accept-Ranges", "bytes")
                    it.addHeader("Content-Range", "bytes $start-$end/$total")
                    it.addHeader("Content-Length", len.toString())
                }
            } else {
                newFixedLengthResponse(Response.Status.OK, mime, body, total).also {
                    it.addHeader("Accept-Ranges", "bytes")
                    it.addHeader("Content-Length", total.toString())
                }
            }
        }

        private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            else -> "video/mp4"
        }
    }
}
