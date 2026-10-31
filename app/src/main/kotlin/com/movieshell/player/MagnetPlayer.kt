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
 * 磁力播放入口。
 *
 * v0.294.24 起优先走侠影自建引擎（[XiaYingMagnetEngine]：libtorrent4j + 回环 HTTP），
 * 不依赖 DsPlayer / Node。自建引擎失败时，再回退到本机已安装的 magnet-stream 服务。
 */
object MagnetPlayer {

    const val PLUGIN_ID = "magnet-stream"
    const val DEFAULT_PORT = 17890

    private const val HEALTH_TIMEOUT_SEC = 2L
    private const val AFTER_START_TRIES = 8
    private const val AFTER_START_GAP_MS = 1000L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(HEALTH_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(HEALTH_TIMEOUT_SEC, TimeUnit.SECONDS)
        .callTimeout(HEALTH_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    sealed class Ready {
        data class Ok(val playUrl: String) : Ready()
        data class Fail(val message: String) : Ready()
    }

    fun isMagnet(url: String): Boolean = url.startsWith("magnet:", ignoreCase = true)

    @Volatile
    var lastFailMessage: String? = null
        private set

    fun resolveLegacyBase(context: Context): String {
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
        httpClient.newCall(Request.Builder().url("$base/healthz").build()).execute().use { it.code == 200 }
    }.getOrDefault(false)

    /** 旧路径：已安装的 magnet-stream 服务，返回 base URL。 */
    private suspend fun ensureLegacyBase(context: Context): Ready = withContext(Dispatchers.IO) {
        var base = resolveLegacyBase(context)
        if (healthOk(base)) return@withContext Ready.Ok(base)
        delay(500)
        base = resolveLegacyBase(context)
        if (healthOk(base)) return@withContext Ready.Ok(base)

        val installed = runCatching {
            ServicePluginManager.installedPlugins(context).any { it.id == PLUGIN_ID }
        }.getOrDefault(false)
        if (!installed) return@withContext Ready.Fail("legacy-not-installed")

        runCatching { ServicePluginManager.start(context, PLUGIN_ID) }.onFailure {
            return@withContext Ready.Fail("自动启动旧版磁力服务失败：${it.message}")
        }
        repeat(AFTER_START_TRIES) {
            delay(AFTER_START_GAP_MS)
            base = resolveLegacyBase(context)
            if (healthOk(base)) return@withContext Ready.Ok(base)
        }
        Ready.Fail("旧版磁力服务启动后仍未就绪")
    }

    suspend fun ensureReady(context: Context, magnet: String): Ready = withContext(Dispatchers.IO) {
        if (XiaYingMagnetEngine.isNativeAvailable()) {
            when (val r = XiaYingMagnetEngine.prepare(context, magnet)) {
                is XiaYingMagnetEngine.Ready -> return@withContext Ready.Ok(r.streamUrl)
                is XiaYingMagnetEngine.Fail -> {
                    LogBus.w("MagnetPlayer", "自建引擎：${r.message}")
                    when (val legacy = ensureLegacyBase(context)) {
                        is Ready.Ok -> return@withContext Ready.Ok(streamUrl(magnet, legacy.playUrl))
                        is Ready.Fail -> return@withContext Ready.Fail(r.message)
                    }
                }
            }
        }
        when (val legacy = ensureLegacyBase(context)) {
            is Ready.Ok -> Ready.Ok(streamUrl(magnet, legacy.playUrl))
            is Ready.Fail -> Ready.Fail(
                if (legacy.message == "legacy-not-installed") {
                    "磁力链接需要本地转成 HTTP 才能播。请重试，或用其他应用打开。"
                } else {
                    legacy.message
                },
            )
        }
    }

    suspend fun playIfReady(
        context: Context,
        magnet: String,
        title: String,
        startPositionMs: Long = 0L,
    ): Boolean {
        lastFailMessage = null
        return when (val r = ensureReady(context, magnet)) {
            is Ready.Fail -> {
                lastFailMessage = r.message
                false
            }
            is Ready.Ok -> withContext(Dispatchers.Main) {
                EngineHost.play(context, r.playUrl, startPositionMs = startPositionMs)
            }
        }
    }

    fun openExternally(context: Context, magnet: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(magnet)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(
            Intent.createChooser(intent, "用其他应用打开磁力链接").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
