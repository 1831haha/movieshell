package com.movieshell.player

import android.content.Context
import com.movieshell.data.repo.LogBus
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
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
    private const val META_TIMEOUT_SEC = 60
    private const val DHT_WARMUP_SEC = 8
    private const val PIECE_WAIT_MS = 60_000L
    private const val DHT_BOOTSTRAP =
        "dht.libtorrent.org:25401,router.bittorrent.com:6881," +
            "router.utorrent.com:6881,dht.transmissionbt.com:6881," +
            "router.bt.ouinet.work:6881"

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
        val topEntry: String,
    )

    @Volatile private var appContext: Context? = null
    @Volatile private var releaseGen = 0
    @Volatile private var prepareGen = 0
    @Volatile private var lastServeMs = 0L
    private val openStreams = java.util.concurrent.atomic.AtomicInteger(0)
    private val metaHandles = ConcurrentHashMap<String, TorrentHandle>()

    /** 正在用的缓存顶层条目（清理时保留） */
    fun activeEntries(): Set<String> = torrents.values.map { it.topEntry }.toSet()

    /** 启动/初始化时调用：按上限与过期时间清理旧缓存（后台线程） */
    fun pruneAsync(context: Context) {
        val c = context.applicationContext
        Thread({ runCatching { MagnetCache.prune(c, activeEntries()) } }, "magnet-prune")
            .also { it.isDaemon = true; it.start() }
    }

    /**
     * 播放页关闭时调用：宽限 [graceMs] 后若没有新的起播/读流（换内核、进出全屏、
     * 同一磁力切集都会重新读流），就停掉全部种子并删掉其缓存文件。
     */
    fun releaseWhenIdle(graceMs: Long = 8000L) {
        if (torrents.isEmpty()) return
        val gen = ++releaseGen
        val since = System.currentTimeMillis()
        Thread({
            try { Thread.sleep(graceMs) } catch (_: InterruptedException) { return@Thread }
            if (gen != releaseGen) return@Thread
            if (openStreams.get() > 0 || lastServeMs > since) return@Thread
            stopAll(deleteFiles = true)
        }, "magnet-release").also { it.isDaemon = true; it.start() }
    }

    fun stopAll(deleteFiles: Boolean) {
        torrents.keys.toList().forEach { cancel(it, deleteFiles) }
        status("已停止磁力下载")
    }

    @Volatile private var nativeChecked = false
    @Volatile private var nativeOk = false

    fun isNativeAvailable(): Boolean {
        if (nativeChecked) return nativeOk
        nativeOk = try {
            // 仅在真正要播磁力时加载 JNI；LinkageError/UnsatisfiedLinkError → 不可用
            Class.forName("org.libtorrent4j.swig.libtorrent_jni")
            Class.forName("org.libtorrent4j.SessionManager")
            true
        } catch (t: Throwable) {
            LogBus.e(TAG, "libtorrent 不可用：${t::class.java.simpleName}: ${t.message}")
            false
        }
        nativeChecked = true
        return nativeOk
    }

    /** 当前回环端口（未启动为 -1） */
    fun port(): Int = http?.listeningPort ?: -1

    @Volatile
    var lastStatus: String = ""
        private set

    private fun status(msg: String) {
        lastStatus = msg
        LogBus.i(TAG, msg)
    }

    internal fun ensureSession(context: Context) {
        synchronized(lock) {
            if (session?.isRunning == true && http != null) return
            val root = File(context.filesDir, "magnet_cache").also { it.mkdirs() }
            saveRoot = root
            appContext = context.applicationContext
            MagnetControl.appContext = context.applicationContext
            runCatching { MagnetCache.prune(context, emptySet()) }
            // v0.294.28：会话配置尽量保守——部分机型 [::] 监听会原生崩溃
            val sm = SessionManager()
            val sp = SettingsPack().apply {
                connectionsLimit(100)
                setEnableDht(true)
                setEnableLsd(true)
                setDhtBootstrapNodes(DHT_BOOTSTRAP)
                listenInterfaces("0.0.0.0:0")
                anonymousMode(false)
            }
            try {
                sm.start(SessionParams(sp))
            } catch (t: Throwable) {
                LogBus.e(TAG, "Session.start 失败，回退默认设置：${t.message}")
                runCatching { sm.stop() }
                val sm2 = SessionManager()
                sm2.start() // 库默认设置
                session = sm2
                runCatching { sm2.startDht() }
                val srv = Loopback()
                srv.start()
                http = srv
                status("磁力引擎已启动（默认设置，端口 ${srv.listeningPort}）")
                return
            }
            runCatching { sm.startDht() }
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
        ensureActive()
        val sm = session ?: return@withContext Fail("磁力引擎未就绪")
        val srv = http ?: return@withContext Fail("本地转播端口未就绪")
        val root = saveRoot ?: return@withContext Fail("缓存目录不可用")

        val myGen = ++prepareGen
        releaseGen++ // 新起播：作废待执行的释放
        // 取消上一次未完成的元数据拉取（fetchMagnet 会占全局锁，改用可取消的 download）
        val enriched = MagnetStreamLogic.enrichMagnet(magnet)
        val hintHash = MagnetStreamLogic.infoHashHex(magnet)?.lowercase()
        // 同一时间只播一个磁力：其余种子停掉并删缓存
        if (hintHash != null) {
            torrents.keys.filter { it != hintHash }.forEach { cancel(it, deleteFiles = true) }
            torrents[hintHash]?.let {
                status("已在缓冲：${it.name}")
                return@withContext Ready("http://127.0.0.1:${srv.listeningPort}/stream/$hintHash")
            }
        } else {
            torrents.keys.toList().forEach { cancel(it, deleteFiles = true) }
        }

        // v0.294.29：元数据阶段回到库自带 fetchMagnet（v0.294.27 的手动 handle 轮询
        // 在元数据阶段反复调用 torrentFile()/remove()，疑似原生崩溃根源）。
        // 本引擎跑在独立 :magnet 进程，取消=杀进程，不再需要可取消的 handle。
        fun dht(): Long = runCatching { sm.dhtNodes() }.getOrDefault(-1L)
        fun metaPeers(): Int = runCatching {
            val h = hintHash?.let { sm.find(org.libtorrent4j.Sha1Hash.parseHex(it)) }
            if (h != null && h.isValid) h.status().numPeers() else -1
        }.getOrDefault(-1)
        status(MagnetStreamLogic.statusFetching(0, dht()))
        // v0.294.30：先用 8 秒并行尝试公共种子缓存（按 infohash 取 .torrent），
        // 同时让 DHT 暖机；缓存没命中再走 DHT/tracker 取元数据。
        val cached: ByteArray? = if (hintHash != null && hintHash.length == 40) {
            withTimeoutOrNull(8_000L) {
                val job = async(Dispatchers.IO) { TorrentCache.fetch(hintHash) }
                var e = 0
                while (!job.isCompleted) {
                    if (myGen != prepareGen) { job.cancel(); return@withTimeoutOrNull null }
                    delay(1000); e++
                    status(MagnetStreamLogic.statusFetching(e, dht()) + "（查种子缓存）")
                }
                job.await()
            }
        } else null
        if (cached != null) status("已从种子缓存取得元数据")
        val data = cached ?: withTimeoutOrNull((META_TIMEOUT_SEC + 5) * 1000L) {
            var elapsed = 8
            val holder = AtomicReference<ByteArray?>(null)
            val th = Thread({
                holder.set(runCatching { sm.fetchMagnet(enriched, META_TIMEOUT_SEC, root) }.getOrNull())
            }, "magnet-meta").also { it.isDaemon = true; it.start() }
            while (th.isAlive) {
                if (myGen != prepareGen) return@withTimeoutOrNull null
                delay(1000)
                elapsed++
                status(MagnetStreamLogic.statusFetchingPeers(elapsed, dht(), metaPeers()))
            }
            holder.get()
        }
        if (myGen != prepareGen) return@withContext Fail("已取消")
        if (data == null) return@withContext Fail(MagnetStreamLogic.statusTimeout(META_TIMEOUT_SEC))
        val ti = try { TorrentInfo(data) } catch (t: Throwable) {
            return@withContext Fail("磁力元数据解析失败：${t.message}")
        }
        val hash = ti.infoHash().toHex().lowercase()
        torrents.keys.filter { it != hash }.forEach { cancel(it, deleteFiles = true) }
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
        torrents[hash] = Active(handle, pick.index, path, pick.size, pick.name,
            rel.replace('\\', '/').substringBefore('/'))

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

    /** 外部/再点一次播放：作废当前元数据等待并摘掉尚未入 Active 的种子。 */
    fun cancelPrepare() {
        prepareGen++
        status("已取消磁力准备")
    }

    fun cancel(hashHex: String, deleteFiles: Boolean = true) {
        val a = torrents.remove(hashHex) ?: return
        runCatching { session?.remove(a.handle) }
        if (deleteFiles) {
            val root = saveRoot ?: return
            val top = a.topEntry
            if (top.isBlank() || top == "." || top == "..") return
            // 稍等 libtorrent 关闭文件句柄再删；若期间又被重新起播则保留
            Thread({
                try { Thread.sleep(1500) } catch (_: InterruptedException) {}
                if (top !in activeEntries()) runCatching { File(root, top).deleteRecursively() }
            }, "magnet-del").also { it.isDaemon = true; it.start() }
        }
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
            if (uri.startsWith("/ctl/")) return MagnetControl.handle(uri, session.parms ?: emptyMap())
            val m = Regex("""^/stream/([a-fA-F0-9]+)""").find(uri)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            val hash = m.groupValues[1].lowercase()
            lastServeMs = System.currentTimeMillis()
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
            openStreams.incrementAndGet()
            val body: InputStream = object : FilterInputStream(fis) {
                var left = len
                private var closed = false
                override fun close() {
                    if (!closed) { closed = true; openStreams.decrementAndGet(); lastServeMs = System.currentTimeMillis() }
                    super.close()
                }
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
