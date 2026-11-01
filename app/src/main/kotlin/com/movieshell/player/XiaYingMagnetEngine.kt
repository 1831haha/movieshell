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
    /** v0.294.39：主进程播放器心跳（在播 / 暂停都发），有心跳就不停 */
    @Volatile var lastBeatMs = 0L
    fun keepAlive() { lastBeatMs = System.currentTimeMillis() }

    fun releaseWhenIdle(graceMs: Long = 8000L) {
        val gen = ++releaseGen
        val since = System.currentTimeMillis()
        Thread({
            try { Thread.sleep(graceMs) } catch (_: InterruptedException) { return@Thread }
            if (gen != releaseGen) return@Thread
            // v0.294.36：离开播放页后只看宽限期内有没有新的读流请求（挂着的旧连接不算），
            // 到点停种子并结束磁力进程（前台服务 / 通知随之消失，迅雷任务一并结束）
            if (lastServeMs > since || lastBeatMs > since) return@Thread
            stopAll(deleteFiles = true)
            trace("离开播放 ${graceMs / 1000}s 无新请求：已停止磁力下载并退出磁力进程")
            try { Thread.sleep(2000) } catch (_: InterruptedException) {}
            if (gen == releaseGen && lastBeatMs <= since) android.os.Process.killProcess(android.os.Process.myPid())
        }, "magnet-release").also { it.isDaemon = true; it.start() }
    }

    fun stopAll(deleteFiles: Boolean) {
        torrents.keys.toList().forEach { cancel(it, deleteFiles) }
        status("已停止磁力下载")
    }

    @Volatile private var nativeOk = false

    /**
     * v0.294.32：libtorrent4j.so 不再打进 APK，由「磁力引擎」插件下载到私有目录；
     * 加载前把 libtorrent4j.jni.path 指过去（libtorrent_jni 静态块会读这个属性）。
     */
    fun isNativeAvailable(context: Context): Boolean {
        if (nativeOk) return true
        val so = EnginePlugins.libtorrentSo(context)
        if (!so.isFile) return false
        nativeOk = try {
            System.setProperty("libtorrent4j.jni.path", so.absolutePath)
            Class.forName("org.libtorrent4j.swig.libtorrent_jni")
            Class.forName("org.libtorrent4j.SessionManager")
            true
        } catch (t: Throwable) {
            LogBus.e(TAG, "libtorrent 加载失败：${t::class.java.simpleName}: ${t.message}")
            false
        }
        return nativeOk
    }

    /** 只起回环 HTTP（控制接口 + 迅雷不需要它播放），不碰 libtorrent */
    internal fun ensureHttp(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            MagnetControl.appContext = context.applicationContext
            if (http != null) return
            MagnetTrace.init(context)
            val srv = Loopback()
            srv.start()
            http = srv
        }
    }

    /** 当前回环端口（未启动为 -1） */
    fun port(): Int = http?.listeningPort ?: -1

    @Volatile
    var lastStatus: String = ""
        private set

    internal fun status(msg: String) {
        lastStatus = msg
        MagnetTrace.log(TAG, msg)
    }

    private const val USE_TORRENT_CACHE = false

    internal fun trace(msg: String) = MagnetTrace.log(TAG, msg)

    /** 选中文件自己的分片范围（多文件种子里文件不一定从第 0 片开始） */
    private fun filePieces(ti: TorrentInfo, idx: Int): IntRange {
        val pl = ti.pieceLength().toLong().coerceAtLeast(1)
        val off = ti.files().fileOffset(idx)
        val size = ti.files().fileSize(idx).coerceAtLeast(1)
        val a = (off / pl).toInt()
        val b = ((off + size - 1) / pl).toInt().coerceAtMost(ti.numPieces() - 1)
        return a..b
    }

    /**
     * v0.294.36：原来催的是整个种子的第 0～7 片——这个资源选中文件不在开头，
     * 催的根本不是视频的片，节点连上了也一直 0%。改为催选中文件开头 8MB + 末尾 2 片（mp4 索引常在尾部）。
     */
    private fun boostFileEnds(h: TorrentHandle, ti: TorrentInfo, idx: Int) {
        val r = filePieces(ti, idx)
        val pl = ti.pieceLength().toLong().coerceAtLeast(1)
        val head = ((8L * 1024 * 1024) / pl).toInt().coerceAtLeast(4)
        val heads = (r.first..minOf(r.last, r.first + head - 1)).toList()
        val tails = listOf(r.last - 1, r.last).filter { it in r && it !in heads }
        (heads + tails).forEachIndexed { i, p ->
            h.piecePriority(p, Priority.TOP_PRIORITY)
            h.setPieceDeadline(p, i * 50)
        }
        trace("步骤：选中文件分片 ${r.first}～${r.last}，优先下载开头 ${heads.size} 片 + 末尾 ${tails.size} 片")
    }

    /** v0.294.34：播放中给播放器显示的实时缓冲信息 */
    fun liveInfo(): String {
        val a = torrents.values.firstOrNull() ?: return ""
        return runCatching {
            if (!a.handle.isValid) return ""
            val st = a.handle.status()
            val pct = if (a.fileSize > 0) (a.handle.fileProgress()[a.fileIndex] * 100 / a.fileSize) else 0
            "BT：${st.numPeers()} 个节点 · 已缓冲 $pct% · ${st.downloadRate() / 1024}KB/s"
        }.getOrDefault("")
    }

    private val netLogCounts = ConcurrentHashMap<String, Int>()

    /** v0.294.36：把监听 / DHT 引导 / tracker 回应等网络告警写进日志（每类前几条 + 每 20 条一次） */
    private fun installNetLog(sm: SessionManager) {
        sm.addListener(object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.LISTEN_SUCCEEDED.swig(), AlertType.LISTEN_FAILED.swig(),
                AlertType.DHT_BOOTSTRAP.swig(), AlertType.TRACKER_REPLY.swig(),
                AlertType.TRACKER_ERROR.swig(), AlertType.TORRENT_ERROR.swig(),
                AlertType.FILE_ERROR.swig(), AlertType.PEER_CONNECT.swig(),
            )
            override fun alert(alert: Alert<*>) {
                runCatching {
                    val k = alert.type().name
                    val n = netLogCounts.merge(k, 1, Int::plus) ?: 1
                    // v0.294.38：连到自己（同端口回环）不算节点
                    val selfPorts: List<Int> = runCatching { sm.listenEndpoints().mapNotNull { e -> e.substringAfterLast(':').trimEnd(']').toIntOrNull() } }.getOrDefault(emptyList())
                    val msg0 = alert.message()
                    if (alert.type() == AlertType.PEER_CONNECT && selfPorts.any { p -> msg0.contains(":$p ") }) {
                        netLogCounts.merge(k, -1, Int::plus); return@runCatching
                    }
                    if (n <= 6 || n % 20 == 0) {
                        val label = when (alert.type()) {
                            AlertType.LISTEN_SUCCEEDED -> "监听成功"; AlertType.LISTEN_FAILED -> "监听失败"
                            AlertType.DHT_BOOTSTRAP -> "DHT 引导完成"; AlertType.TRACKER_REPLY -> "tracker 回应"
                            AlertType.TRACKER_ERROR -> "tracker 出错"; AlertType.TORRENT_ERROR -> "任务出错"
                            AlertType.FILE_ERROR -> "文件出错"; AlertType.PEER_CONNECT -> "连上节点"
                            else -> k
                        }
                        trace("网络：$label（第 $n 次）${alert.message().take(160)}")
                    }
                }
            }
        })
    }

    internal fun ensureSession(context: Context) {
        synchronized(lock) {
            ensureHttp(context)
            if (session?.isRunning == true) return
            val root = File(context.filesDir, "magnet_cache").also { it.mkdirs() }
            saveRoot = root
            appContext = context.applicationContext
            MagnetControl.appContext = context.applicationContext
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
                // v0.294.31：用 posix 读写代替 mmap——小米 f2fs 上 mmap 文件被截断/删除会 SIGBUS
                sm.start(SessionParams(sp).apply { setPosixDiskIO() })
            } catch (t: Throwable) {
                LogBus.e(TAG, "Session.start 失败，回退默认设置：${t.message}")
                runCatching { sm.stop() }
                val sm2 = SessionManager()
                sm2.start(SessionParams(SettingsPack()).apply { setPosixDiskIO() })
                session = sm2
                runCatching { sm2.startDht() }
                status("磁力引擎已启动（默认设置）")
                return
            }
            runCatching { sm.startDht() }
            session = sm
            installNetLog(sm)
            status("磁力引擎已启动（端口 ${http?.listeningPort}）")
        }
    }

    /**
     * 准备可播的 HTTP 地址。失败返回中文说明。
     */
    suspend fun prepare(context: Context, magnet: String, torrentFile: java.io.File? = null): Any = withContext(Dispatchers.IO) {
        if (!isNativeAvailable(context)) return@withContext Fail(EnginePlugins.NEED_BT)
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
        releaseGen++ // 新的起播作废待执行的「离开后停止」
        netLogCounts.clear()
        releaseGen++ // 新起播：作废待执行的释放
        // 取消上一次未完成的元数据拉取（fetchMagnet 会占全局锁，改用可取消的 download）
        val enriched = MagnetStreamLogic.enrichMagnet(magnet)
        val hintHash = MagnetStreamLogic.infoHashHex(magnet)?.lowercase()?.takeIf { it.length == 40 }
            ?: MagnetStreamLogic.base32ToHex(magnet)
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
        // v0.294.33：两次原生崩溃都发生在「种子缓存命中 → 用缓存的种子加入」之后，
        // 先停用缓存路径，统一走「加入磁力 → 同一任务上取元数据」单一路径。
        // v0.294.34：迅雷已下到 .torrent（海阔 TSEngine 的做法）→ 直接用文件喂 libtorrent，跳过取元数据
        val fromFile: TorrentInfo? = torrentFile?.takeIf { it.isFile && it.length() > 0 }?.let { f ->
            runCatching { TorrentInfo(f) }.onFailure { trace("迅雷种子文件解析失败：${it.message}") }.getOrNull()
        }
        if (fromFile != null) trace("步骤：使用迅雷下好的种子文件 ${torrentFile?.name}，跳过取元数据")
        val cached: ByteArray? = if (fromFile == null && hintHash != null && hintHash.length == 40 && USE_TORRENT_CACHE) {
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
        var magnetHandle: TorrentHandle? = null
        val ti: TorrentInfo = if (fromFile != null) fromFile else if (cached != null) {
            try { TorrentInfo(cached) } catch (t: Throwable) {
                return@withContext Fail("种子缓存解析失败：${t.message}")
            }
        } else {
            // v0.294.31：不再用 fetchMagnet（它结束时会删掉自己加的种子，和随后重新加入同一
            // infohash 竞争，句柄失效后原生崩溃）。改为只加入一次磁力，拿到元数据后在同一句柄上设优先级。
            val hex = hintHash ?: MagnetStreamLogic.base32ToHex(enriched)
                ?: return@withContext Fail("无法识别磁力链接里的 infohash")
            trace("步骤：加入磁力任务 $hex")
            runCatching { sm.download(enriched, root, TorrentFlags.SEQUENTIAL_DOWNLOAD) }
                .onFailure { return@withContext Fail("加入磁力失败：${it.message}") }
            val sha = org.libtorrent4j.Sha1Hash.parseHex(hex)
            var got: TorrentInfo? = null
            var elapsed = 8
            val deadline = System.currentTimeMillis() + META_TIMEOUT_SEC * 1000L
            while (System.currentTimeMillis() < deadline) {
                if (myGen != prepareGen) return@withContext Fail("已取消")
                delay(1000); elapsed++
                val h = runCatching { sm.find(sha) }.getOrNull()
                var peers = -1
                if (h != null && h.isValid) {
                    magnetHandle = h
                    val st = runCatching { h.status() }.getOrNull()
                    peers = st?.numPeers() ?: -1
                    if (st != null && st.hasMetadata()) {
                        got = runCatching { h.torrentFile() }.getOrNull()
                        if (got != null) break
                    }
                }
                status(MagnetStreamLogic.statusFetchingPeers(elapsed, dht(), peers))
            }
            got ?: run {
                magnetHandle?.let { h -> runCatching { if (h.isValid) sm.remove(h) } }
                return@withContext Fail(MagnetStreamLogic.statusTimeout(META_TIMEOUT_SEC))
            }
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
                            boostFileEnds(h, ti, pick.index)
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
        trace("步骤：已取得元数据，文件 ${ti.numFiles()} 个，分片 ${ti.numPieces()}×${ti.pieceLength()}")
        val existing = magnetHandle?.takeIf { it.isValid }
        if (existing != null) {
            trace("步骤：在同一任务上设置文件优先级（第 ${pick.index} 个）")
            handleRef.set(existing)
            runCatching {
                existing.prioritizeFiles(prios)
                existing.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                existing.resume()
                boostFileEnds(existing, ti, pick.index)
            }
        } else {
            sm.addListener(listener)
            try {
                sm.download(ti, root, null, prios, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                if (!added.await(30, TimeUnit.SECONDS)) {
                    return@withContext Fail("加入下载队列超时")
                }
            } finally {
                sm.removeListener(listener)
            }
        }
        // v0.294.35：告警里的 handle 是「不拥有内存」的指针，告警回收后再用就是野指针
        // （SIGSEGV 在 torrent_handle.is_valid）。告警回调之外一律用 session.find 取得自有副本。
        val handle = (if (existing != null) runCatching { sm.find(ti.infoHash()) }.getOrNull() ?: existing
            else runCatching { sm.find(ti.infoHash()) }.getOrNull())
            ?: return@withContext Fail("无法取得下载句柄")
        handleRef.set(null)
        val rel = ti.files().filePath(pick.index)
        val path = File(root, rel)
        torrents[hash] = Active(handle, pick.index, path, pick.size, pick.name,
            rel.replace('\\', '/').substringBefore('/'))

        // v0.294.36：用种子文件加入时，种子里可能没有 tracker——补上磁力自带 + 公共 tracker，
        // 并立刻向 tracker / DHT 宣告（原来走种子文件这条路 DHT 也没暖机，一直 0 节点）
        runCatching {
            val have = handle.trackers().map { it.url() }.toSet()
            val own = Regex("""[?&]tr=([^&]+)""").findAll(magnet).map { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }.toList()
            var added = 0
            (own + MagnetStreamLogic.PUBLIC_TRACKERS).distinct().filter { it !in have }.forEach {
                handle.addTracker(org.libtorrent4j.AnnounceEntry(it)); added++
            }
            trace("步骤：tracker 原有 ${have.size} 个，补 $added 个；DHT 节点 ${dht()}")
            handle.forceReannounce()
            handle.forceDHTAnnounce()
        }.onFailure { trace("补 tracker 失败：${it.message}") }
        trace("步骤：开始缓冲，保存到 ${path.name}")
        // v0.294.36：等文件头有数据再交播放器，最多 3 分钟；一直 0 节点就不开播放器
        var maxPeers = 0
        var maxKnown = 0
        var gotBytes = 0L
        val fileRange = filePieces(ti, pick.index)
        val headReady = withTimeoutOrNull(180_000L) {
            var n = 0
            while (true) {
                if (myGen != prepareGen) return@withTimeoutOrNull false
                if (!handle.isValid) return@withTimeoutOrNull false
                val st = handle.status()
                val peers = st.numPeers()
                maxPeers = maxOf(maxPeers, peers)
                maxKnown = maxOf(maxKnown, st.listPeers())
                gotBytes = maxOf(gotBytes, st.totalDone())
                val pct = (st.progress() * 100).toInt().coerceIn(0, 100)
                status(MagnetStreamLogic.statusPeers(peers, pct) + "（DHT ${dht()}，${st.downloadRate() / 1024}KB/s）")
                if (handle.havePiece(fileRange.first)) return@withTimeoutOrNull true
                if (n % 20 == 0) runCatching {
                    trace("诊断：连接 ${st.numPeers()}（做种 ${st.numSeeds()}），已知节点 ${st.listPeers()}/做种 ${st.listSeeds()}，候选 ${st.connectCandidates()}，已有分片 ${st.numPieces()}，速度 ${st.downloadRate() / 1024}KB/s，开头片 ${fileRange.first} 未到")
                }
                if (n % 40 == 20) runCatching { handle.forceReannounce(); handle.forceDHTAnnounce() }
                delay(500)
                n++
            }
            @Suppress("UNREACHABLE_CODE") false
        } == true
        if (myGen != prepareGen) return@withContext Fail("已取消")
        if (!headReady) {
            if (maxPeers == 0 || gotBytes == 0L) {
                cancel(hash, deleteFiles = true)
                return@withContext Fail(
                    if (maxKnown > 0) "3 分钟内找到 $maxKnown 个节点但没有下到任何数据，这个资源可能已无人做种"
                    else "3 分钟内没有找到任何节点，这个资源可能已无人做种",
                )
            }
            status("有节点但还没下到开头数据，先尝试起播…")
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
                    if (!closed) { closed = true; openStreams.decrementAndGet() }
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
