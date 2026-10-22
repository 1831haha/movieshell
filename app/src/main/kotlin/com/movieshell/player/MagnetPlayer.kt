package com.movieshell.player

import android.content.Context
import com.movieshell.data.repo.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /**
     * v0.161.0：/healthz 探活重试 3 次（每次 2 秒超时，间隔 1 秒），
     * 服务刚启动、还没完全起来时不再一次就判死。
     * 注意：17890 是 DsPlayer 占的口，壳子自己的服务管理器不会分这个口，
     * 所以壳子拉不起 DsPlayer 的 magnet-stream——重试都失败时只能提示用户
     * 去 DsPlayer 服务页启动，启动后再点一次播放，不要假装能自动拉起。
     */
    suspend fun isServiceUp(): Boolean = withContext(Dispatchers.IO) {
        repeat(3) { attempt ->
            val ok = runCatching {
                val request = Request.Builder()
                    .url("$SERVICE_BASE/healthz")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    response.code == 200
                }
            }.getOrDefault(false)
            if (ok) return@withContext true
            if (attempt < 2) delay(1000)
        }
        LogBus.w("MagnetPlayer", "磁力服务 /healthz 重试 3 次均未通")
        false
    }

    /** 拼出转播流地址（magnet 做 URLEncoder.encode UTF-8） */
    fun streamUrl(magnet: String): String =
        "$SERVICE_BASE/stream?magnet=${URLEncoder.encode(magnet, "UTF-8")}"

    /**
     * 服务可用 → 经 EngineHost 用当前内核播放转播流；
     * 服务不可用 → 返回 false（调用方负责弹提示）。
     * v0.25.0：透传 EngineHost.play 的返回值（起播失败也返回 false）。
     *
     * startPositionMs 透传，用于断点续播。
     */
    suspend fun playIfReady(
        context: Context,
        magnet: String,
        title: String,
        startPositionMs: Long = 0L,
    ): Boolean {
        if (!isServiceUp()) return false
        // v0.20.0：走 EngineHost，磁力转播流也尊重当前内核选择；
        // 切主线程：Media3（ExoPlayer）要求在其创建线程（主线程）上调用
        return withContext(Dispatchers.Main) {
            EngineHost.play(context, streamUrl(magnet), startPositionMs = startPositionMs)
        }
    }
}
