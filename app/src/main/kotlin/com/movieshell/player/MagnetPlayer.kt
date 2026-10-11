package com.movieshell.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 磁力链接转播播放器。
 *
 * 不自己实现 BT 下载，只做"探测→转交"：
 * 探测本机 magnet-stream 转播服务（DsPlayer 服务插件，固定端口 17890），
 * 服务可用时把 magnet 链接交给其 /stream 接口转成 HTTP 视频流给 ExoPlayer 播。
 */
object MagnetPlayer {

    const val SERVICE_BASE = "http://127.0.0.1:17890"

    private const val HEALTH_TIMEOUT_SEC = 2L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(HEALTH_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(HEALTH_TIMEOUT_SEC, TimeUnit.SECONDS)
        .callTimeout(HEALTH_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /** 是否为磁力链接（忽略大小写） */
    fun isMagnet(url: String): Boolean = url.startsWith("magnet:", ignoreCase = true)

    /** 2 秒超时 GET /healthz，200 即为服务可用；任何异常返回 false */
    suspend fun isServiceUp(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$SERVICE_BASE/healthz")
                .build()
            httpClient.newCall(request).execute().use { response ->
                response.code == 200
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 拼出转播流地址（magnet 做 URLEncoder.encode UTF-8） */
    fun streamUrl(magnet: String): String =
        "$SERVICE_BASE/stream?magnet=${URLEncoder.encode(magnet, "UTF-8")}"

    /**
     * 服务可用 → 切主线程调 PlayerManager.play(streamUrl(magnet)) 起播并返回 true；
     * 服务不可用 → 返回 false（调用方负责弹提示）。
     *
     * startPositionMs 透传给 PlayerManager.play，用于断点续播。
     */
    suspend fun playIfReady(
        context: Context,
        magnet: String,
        title: String,
        startPositionMs: Long = 0L,
    ): Boolean {
        if (!isServiceUp()) return false
        PlayerManager.init(context)
        withContext(Dispatchers.Main) {
            PlayerManager.play(streamUrl(magnet), startPositionMs = startPositionMs)
        }
        return true
    }
}
