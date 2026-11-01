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
        val save = File(root, "dl_" + (info?.mInfoHash ?: "x")).also { it.mkdirs() }
        val tid = runCatching { h.addTorrentTask(torrent.absolutePath, save.absolutePath, pick.index) }
            .onFailure { t("创建下载任务异常：${it.message}") }.getOrDefault(-1L)
        t("下载任务 id=$tid")
        if (tid < 0) return@withContext XiaYingMagnetEngine.Fail("迅雷：创建下载任务失败")
        taskId = tid
        // 等子任务不再是失败态，并且本地文件出现
        val target = listOfNotNull(
            sub.mSubPath?.takeIf { it.isNotBlank() }?.let { File(File(save, it), pick.name) },
            File(save, pick.name),
        )
        for (i in 0 until 60) {
            delay(500)
            val ti = runCatching { h.getBtSubTaskInfo(tid, pick.index).mTaskInfo }.getOrNull()
            if (ti != null && ti.mTaskStatus == 3) {
                stopCurrent()
                return@withContext XiaYingMagnetEngine.Fail("迅雷：下载失败（错误码 ${ti.mErrorCode}）")
            }
            val f = target.firstOrNull { it.exists() } ?: save.walkTopDown().firstOrNull { it.isFile && it.name == pick.name }
            if (f != null) {
                val url = runCatching { h.getLoclUrl(f.absolutePath) }.getOrNull()
                t("本地播放地址：${url ?: "（空）"}")
                if (!url.isNullOrBlank()) {
                    onStatus("迅雷：已开始边下边播")
                    return@withContext XiaYingMagnetEngine.Ready(url)
                }
            }
            if (i % 2 == 0) {
                val spd = (ti?.mDownloadSpeed ?: 0) / 1024
                onStatus("迅雷：缓冲中（${spd}KB/s）…")
            }
        }
        stopCurrent()
        XiaYingMagnetEngine.Fail("迅雷：30 秒内没有拿到播放地址")
    }

    fun stop() = stopCurrent()
}
