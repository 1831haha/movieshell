package com.movieshell.player

import android.content.Context
import com.xunlei.downloadlib.XLLoader
import com.xunlei.downloadlib.XLTaskHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v0.294.32：迅雷引擎（参考海阔视界 thunder 模块流程）。只在 :magnet 进程里跑：
 * 磁力 → 迅雷下种子 → 选最大视频 → 建 BT 任务 → getLoclUrl 本地边下边播地址。
 */
object XunleiEngine {
    private const val TAG = "XunleiEngine"
    @Volatile private var inited = false
    @Volatile private var taskId = -1L

    private fun t(msg: String) = MagnetTrace.log(TAG, msg)

    private fun init(context: Context) {
        if (inited) return
        t("初始化：加载 ${EnginePlugins.xunleiDir(context).absolutePath} 下的 .so")
        XLLoader.libDir = EnginePlugins.xunleiDir(context).absolutePath
        // 海阔视界公开的 appKey/版本（迅雷下载库要求）
        XLTaskHelper.init(context, "xzNjAwMQ^^yb==0^852^083dbcff^cee25055f125a2fde", "21.01.07.800002")
        inited = true
        t("初始化完成")
    }

    private fun stopCurrent() {
        val t = taskId
        if (t >= 0) runCatching { XLTaskHelper.instance().stopTask(t) }
        taskId = -1
    }

    suspend fun prepare(context: Context, magnet: String, onStatus: (String) -> Unit): Any = withContext(Dispatchers.IO) {
        if (!EnginePlugins.xlInstalled(context)) return@withContext XiaYingMagnetEngine.Fail("迅雷引擎插件未安装")
        try { init(context) } catch (t: Throwable) {
            t("初始化失败：${t::class.java.simpleName}: ${t.message}")
            return@withContext XiaYingMagnetEngine.Fail("迅雷引擎加载失败：${t.message}")
        }
        val h = XLTaskHelper.instance()
        stopCurrent()
        val root = File(context.filesDir, "magnet_cache/xl").also { it.mkdirs() }
        val name = runCatching { h.getFileName(magnet) }.getOrNull()?.ifBlank { null } ?: "meta.torrent"
        val torrent = File(root, name)
        torrent.delete()
        lastTorrent = null
        onStatus("迅雷：正在解析磁力…")
        t("添加磁力任务：$name")
        val metaId = runCatching { h.addMagentTask(magnet, root.absolutePath, name) }
            .onFailure { t("添加磁力任务异常：${it.message}") }.getOrDefault(-1L)
        t("磁力任务 id=$metaId")
        if (metaId < 0) return@withContext XiaYingMagnetEngine.Fail("迅雷：创建磁力任务失败")
        taskId = metaId
        var ok = false
        for (i in 0 until 120) { // 最多 60s
            delay(500)
            val info0 = runCatching { h.getTaskInfo(metaId) }.getOrNull()
            val st = info0?.mTaskStatus ?: 3
            if (i % 4 == 0) t("解析状态=$st（0连接 1下载 2完成 3失败）错误码=${info0?.mErrorCode}")
            if (st == 2) { ok = true; break }
            if (st == 3) { t("解析失败，错误码 ${info0?.mErrorCode}"); break }
            if (i % 2 == 0) onStatus("迅雷：正在解析磁力（${i / 2}s）…")
        }
        stopCurrent()
        if (!ok || !torrent.isFile) return@withContext XiaYingMagnetEngine.Fail("迅雷：磁力解析失败或超时")
        val info = runCatching { h.getTorrentInfo(torrent.absolutePath) }.getOrNull()
        val subs = info?.mSubFileInfo?.toList().orEmpty()
        val pick = MagnetStreamLogic.pickPlayFile(subs.map { MagnetStreamLogic.FileCand(it.mFileIndex, it.mFileName ?: "", it.mFileSize) })
            ?: return@withContext XiaYingMagnetEngine.Fail("迅雷：种子里没有可播放的文件")
        val sub = subs.first { it.mFileIndex == pick.index }
        onStatus("迅雷：选定「${pick.name}」（${pick.size / 1024 / 1024}MB），开始缓冲…")
        lastTorrent = torrent
        // v0.294.34：按海阔视界 ThunderManager.download：保存目录 = 根目录/文件名去扩展名，
        // addTorrentTask（内部 createBtTask + 取消其余文件 + startTask），
        // 子任务进入 1/4（下载中）或 2（完成）后立即 getLoclUrl(保存目录/文件名)，不等文件落盘。
        val base = pick.name.substringAfterLast('/').let { if (it.contains('.')) it.substringBeforeLast('.') else it }
        val save = File(root, "dl_" + base.take(80)).also { it.mkdirs() }
        val tid = runCatching { h.addTorrentTask(torrent.absolutePath, save.absolutePath, pick.index) }
            .onFailure { t("创建下载任务异常：${it::class.java.simpleName}: ${it.message}") }.getOrDefault(-1L)
        t("下载任务 id=$tid，保存到 ${save.name}")
        if (tid < 0) return@withContext XiaYingMagnetEngine.Fail("迅雷：创建下载任务失败（addTorrentTask 返回 $tid）")
        taskId = tid
        val subPath = sub.mSubPath?.takeIf { it.isNotBlank() }
        val target = if (subPath != null) File(File(save, subPath), pick.name) else File(save, pick.name)
        var lastSt = -1
        for (i in 0 until 100) { // 300ms × 100 = 30s
            delay(300)
            val ti = runCatching { h.getBtSubTaskInfo(tid, pick.index).mTaskInfo }
                .onFailure { if (i == 0) t("读取子任务状态异常：${it.message}") }.getOrNull()
            val st = ti?.mTaskStatus ?: -1
            if (st != lastSt) { t("子任务状态=$st（0等待 1/4下载中 2完成 3失败）错误码=${ti?.mErrorCode}"); lastSt = st }
            if (st == 3 && i > 3) {
                stopCurrent()
                return@withContext XiaYingMagnetEngine.Fail("迅雷：下载失败（错误码 ${ti?.mErrorCode}）")
            }
            if (st == 1 || st == 4 || st == 2) {
                val f = target.takeIf { it.exists() }
                    ?: save.walkTopDown().firstOrNull { it.isFile && it.name == pick.name } ?: target
                val url = runCatching { h.getLoclUrl(f.absolutePath) }
                    .onFailure { t("getLoclUrl 异常：${it.message}") }.getOrNull()
                t("本地播放地址：${url ?: "（空）"}（${f.absolutePath.substringAfter("magnet_cache/")}）")
                if (!url.isNullOrBlank()) {
                    onStatus("迅雷：已开始边下边播")
                    return@withContext XiaYingMagnetEngine.Ready(url)
                }
            }
            if (i % 3 == 0) {
                val spd = (ti?.mDownloadSpeed ?: 0) / 1024
                onStatus("迅雷：连接中（状态 $st，${spd}KB/s）…")
            }
        }
        stopCurrent()
        XiaYingMagnetEngine.Fail("迅雷：30 秒内没有拿到播放地址")
    }

    fun liveInfo(): String {
        val t = taskId
        if (t < 0) return ""
        return runCatching {
            val i = XLTaskHelper.instance().getTaskInfo(t)
            val pct = if (i.mFileSize > 0) i.mDownloadSize * 100 / i.mFileSize else 0
            "迅雷：已缓冲 $pct% · ${i.mDownloadSpeed / 1024}KB/s"
        }.getOrDefault("")
    }

    /** 最近一次迅雷下好的 .torrent，供 BT 引擎回退时直接使用 */
    @Volatile var lastTorrent: File? = null

    fun stop() = stopCurrent()
}
