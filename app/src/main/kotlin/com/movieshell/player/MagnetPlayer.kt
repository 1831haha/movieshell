package com.movieshell.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.movieshell.data.repo.LogBus
import com.movieshell.data.repo.ServicePluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 磁力链接转播播放。
 *
 * Media3 / libmpv 都不能直接吃 `magnet:`：必须有进程把 BT 分片转成 HTTP（或整文件）。
 * 本对象不自己做 BT，只负责：
 *  1. 找到本机磁力转播服务（优先侠影已注册的 `magnet-stream` 实际端口，其次 17890 兼容口）
 *  2. 未在跑时尝试自动拉起（用户不必再去服务页点启动）
 *  3. 探活通过后把 magnet 交给 `/stream`，再交给 EngineHost 播放
 *
 * 「不依赖端口服务」从实现上做不到；从体验上做到「不用手点启动」就是自动拉起。
 */
object MagnetPlayer {

    const val PLUGIN_ID = "magnet-stream"
    const val DEFAULT_PORT = 17890

    private const val HEALTH_TIMEOUT_SEC = 2L
    private const val AFTER_START_TRIES = 12
    private const val AFTER_START_GAP_MS = 1000L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(HEALTH_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(HEALTH_TIMEOUT_SEC, TimeUnit.SECONDS)
        .callTimeout(HEALTH_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /** 探活/起播失败原因（直接给 UI 中文文案）。 */
    sealed class Ready {
        data class Ok(val base: String) : Ready()
        data class Fail(val message: String) : Ready()
    }

    fun isMagnet(url: String): Boolean = url.startsWith("magnet:", ignoreCase = true)

    /** 当前应探的基址：运行中服务端口 > 注册表端口 > 默认 17890。 */
    fun resolveBase(context: Context): String {
        val live = runCatching { ServicePluginManager.portOf(PLUGIN_ID) }.getOrNull()
        if (live != null && live > 0) return "http://127.0.0.1:$live"
        val registered = runCatching {
            ServicePluginManager.installedPlugins(context).firstOrNull { it.id == PLUGIN_ID }?.port
        }.getOrNull()
        if (registered != null && registered > 0) return "http://127.0.0.1:$registered"
        return "http://127.0.0.1:$DEFAULT_PORT"
    }

    fun streamUrl(magnet: String, base: String): String =
        "$base/stream?magnet=${URLEncoder.encode(magnet, "UTF-8")}"

    private fun healthOk(base: String): Boolean = runCatching {
        val request = Request.Builder().url("$base/healthz").build()
        httpClient.newCall(request).execute().use { it.code == 200 }
    }.getOrDefault(false)

    /**
     * 探活；不通则若已安装则自动 start，再多等几秒。
     * 全程 IO 线程。
     */
    suspend fun ensureReady(context: Context): Ready = withContext(Dispatchers.IO) {
        var base = resolveBase(context)
        if (healthOk(base)) return@withContext Ready.Ok(base)

        // 短重试：服务可能刚起来
        repeat(2) {
            delay(800)
            base = resolveBase(context)
            if (healthOk(base)) return@withContext Ready.Ok(base)
        }

        val installed = runCatching {
            ServicePluginManager.installedPlugins(context).any { it.id == PLUGIN_ID }
        }.getOrDefault(false)

        if (!installed) {
            LogBus.w("MagnetPlayer", "磁力服务未安装且 $base 不通")
            return@withContext Ready.Fail(
                "本机没有可用的磁力转播服务。\n\n" +
                    "磁力链接不能直接给播放器播，需要本地转成 HTTP 流。" +
                    "请到「插件中心 → 服务/市场」安装并启动「磁力转播服务」后再播。" +
                    "也可点下方用其他应用打开。",
            )
        }

        LogBus.i("MagnetPlayer", "磁力服务已安装但未响应，尝试自动拉起")
        val startErr = runCatching {
            ServicePluginManager.start(context, PLUGIN_ID)
            null
        }.exceptionOrNull()
        if (startErr != null) {
            LogBus.w("MagnetPlayer", "自动拉起失败：${startErr.message}")
            return@withContext Ready.Fail(
                "已安装「磁力转播服务」，但自动启动失败：${startErr.message}\n\n" +
                    "请到「插件中心 → 服务」手动启动；若提示缺 Node 运行时，需先装对应运行时。" +
                    "也可点下方用其他应用打开。",
            )
        }

        repeat(AFTER_START_TRIES) { i ->
            delay(AFTER_START_GAP_MS)
            base = resolveBase(context)
            if (healthOk(base)) {
                LogBus.i("MagnetPlayer", "自动拉起成功（${i + 1}s）：$base")
                return@withContext Ready.Ok(base)
            }
        }
        LogBus.w("MagnetPlayer", "自动拉起后仍不通：$base")
        Ready.Fail(
            "已尝试自动启动「磁力转播服务」，但仍未就绪（$base）。\n\n" +
                "请到「插件中心 → 服务」查看日志，或用其他应用打开磁力链接。",
        )
    }

    /**
     * 服务可用 → EngineHost 播转播流；不可用 → false（调用方弹 [lastFailMessage]）。
     */
    @Volatile
    var lastFailMessage: String? = null
        private set

    suspend fun playIfReady(
        context: Context,
        magnet: String,
        title: String,
        startPositionMs: Long = 0L,
    ): Boolean {
        lastFailMessage = null
        return when (val r = ensureReady(context)) {
            is Ready.Fail -> {
                lastFailMessage = r.message
                false
            }
            is Ready.Ok -> withContext(Dispatchers.Main) {
                EngineHost.play(
                    context,
                    streamUrl(magnet, r.base),
                    startPositionMs = startPositionMs,
                )
            }
        }
    }

    /** 把 magnet 交给系统里已装的 BT/下载应用。 */
    fun openExternally(context: Context, magnet: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(magnet)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, "用其他应用打开磁力链接").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
