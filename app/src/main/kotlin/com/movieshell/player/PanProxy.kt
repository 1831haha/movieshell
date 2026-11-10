package com.movieshell.player

import android.util.Base64
import com.movieshell.core.log.LogBus
import com.movieshell.core.net.HttpClient
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import okhttp3.Request
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.1.23：网盘回环代理（只听 127.0.0.1，绝不绑 0.0.0.0、不露局域网）。
 *
 * 写法照抄 XiaYingMagnetEngine.Loopback（NanoHTTPD 127.0.0.1:0，端口动态取），
 * 只多两种入口：
 * - /pan/<token>：PanDispatch 注册的直链（规则给的头由代理带上回源，
 *   播放器只看到不透明 token，Cookie 不进播放地址）；
 * - /?do=...&url=...&header=...：CatVod jar spider 的 Proxy 协议，
 *   SpiderApi.getAddress/getPort 指的就是这里，jar spider 才能用。
 *
 * 取流走 HttpClient.streamClient（无整 call 上限、无读取超时，大文件
 * 长流不会被 defaultClient 的 60 秒上限掐断）。
 */
object PanProxy {

    private const val TAG = "PanProxy"
    private const val ENTRY_TTL_MS = 2L * 60 * 60 * 1000
    private const val MAX_ENTRIES = 32

    private data class Entry(
        val url: String,
        val headers: Map<String, String>,
        val expiresAt: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val startLock = Any()
    @Volatile private var server: Server? = null
    @Volatile private var serverPort = -1

    /** 确保服务已起；起失败返回 false（调用方按无代理处理）。 */
    fun ensureStarted(): Boolean {
        if (server != null) return true
        synchronized(startLock) {
            if (server != null) return true
            return runCatching {
                val s = Server()
                s.start()
                server = s
                serverPort = s.listeningPort
                LogBus.i(TAG, "回环代理已起：127.0.0.1:$serverPort")
                true
            }.onFailure { LogBus.w(TAG, "回环代理启动失败：${it.message}") }.getOrDefault(false)
        }
    }

    /** 监听端口；未启动返回 -1。 */
    fun port(): Int {
        if (!ensureStarted()) return -1
        return serverPort
    }

    /** SpiderApi.getPort 同款：端口号字符串，起失败返回空串（旧行为）。 */
    fun portString(): String {
        val p = port()
        return if (p > 0) p.toString() else ""
    }

    /** 注册一条直链，返回播放器用的回环地址；服务起不来返回 null。 */
    fun register(url: String, headers: Map<String, String>): String? {
        if (url.isBlank()) return null
        if (!ensureStarted()) return null
        prune()
        val token = UUID.randomUUID().toString().replace("-", "")
        entries[token] = Entry(url, headers, System.currentTimeMillis() + ENTRY_TTL_MS)
        return "http://127.0.0.1:$serverPort/pan/$token"
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        entries.entries.removeIf { it.value.expiresAt <= now }
        if (entries.size >= MAX_ENTRIES) {
            entries.entries.sortedBy { it.value.expiresAt }
                .take(entries.size - MAX_ENTRIES + 1)
                .forEach { entries.remove(it.key) }
        }
    }

    private class Server : NanoHTTPD("127.0.0.1", 0) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri ?: "/"
            if (uri == "/healthz") {
                return newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true}""")
            }
            if (uri.startsWith("/pan/")) {
                val token = uri.removePrefix("/pan/").substringBefore('/').substringBefore('?')
                val entry = entries[token]
                if (entry == null || entry.expiresAt <= System.currentTimeMillis()) {
                    entries.remove(token)
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "expired")
                }
                return fetch(entry.url, entry.headers, session.headers["range"])
            }
            // CatVod jar spider 的 Proxy 协议：?do=quark&type=video&url=<base64>&header=<base64 JSON>
            val parms = session.parms ?: emptyMap()
            if (parms.containsKey("do") && parms.containsKey("url")) {
                val url = decodeBase64(parms["url"].orEmpty())
                if (url.isBlank()) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad url")
                }
                val headers = decodeHeaderJson(parms["header"].orEmpty())
                return fetch(url, headers, session.headers["range"])
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }

        private fun decodeBase64(raw: String): String {
            if (raw.isBlank()) return ""
            return runCatching {
                String(Base64.decode(raw.trim(), Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault("")
        }

        private fun decodeHeaderJson(raw: String): Map<String, String> {
            val text = decodeBase64(raw)
            if (text.isBlank()) return emptyMap()
            return runCatching {
                val o = JSONObject(text)
                val out = mutableMapOf<String, String>()
                o.keys().forEach { k -> out[k] = o.optString(k) }
                out
            }.getOrDefault(emptyMap())
        }

        /**
         * 回源取流：Range 原样透传，206/200 原样回，流式转发不整段读进内存。
         * 直链域名实测支持 Range（Accept-Ranges: bytes，206 带 Content-Range）。
         */
        private fun fetch(url: String, headers: Map<String, String>, range: String?): Response {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) ->
                if (k.equals("Host", ignoreCase = true) ||
                    k.equals("Content-Length", ignoreCase = true) ||
                    k.equals("Range", ignoreCase = true)
                ) return@forEach
                builder.header(k, v)
            }
            if (!range.isNullOrBlank()) builder.header("Range", range)
            val resp = runCatching { HttpClient.streamClient().newCall(builder.build()).execute() }
                .onFailure { LogBus.w(TAG, "回源失败：${it.message}") }.getOrNull()
                ?: return newFixedLengthResponse(Response.Status.BAD_GATEWAY, MIME_PLAINTEXT, "upstream fail")
            if (!resp.isSuccessful) {
                val code = resp.code
                runCatching { resp.close() }
                LogBus.w(TAG, "回源 HTTP $code")
                return newFixedLengthResponse(
                    when (code) {
                        400 -> Response.Status.BAD_REQUEST
                        404 -> Response.Status.NOT_FOUND
                        else -> Response.Status.BAD_GATEWAY
                    },
                    MIME_PLAINTEXT, "upstream $code",
                )
            }
            val body = resp.body ?: run {
                runCatching { resp.close() }
                return newFixedLengthResponse(Response.Status.BAD_GATEWAY, MIME_PLAINTEXT, "empty body")
            }
            val total = resp.header("Content-Length")?.toLongOrNull()
                ?: body.contentLength().takeIf { it >= 0 }
            val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotBlank() } ?: guessMime(url)
            val stream: InputStream = object : FilterInputStream(body.byteStream()) {
                private var closed = false
                override fun close() {
                    if (!closed) {
                        closed = true
                        runCatching { resp.close() }
                    }
                    super.close()
                }
            }
            val status = if (resp.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            if (total == null || total < 0) {
                // 长度未知时不走 chunked（该 API 未在仓库里验证过）——直链实测都带
                // Content-Length，这支只防极端情况。
                runCatching { stream.close() }
                LogBus.w(TAG, "回源无 Content-Length，拒绝服务")
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "no length")
            }
            return newFixedLengthResponse(status, mime, stream, total).also { r ->
                r.addHeader("Accept-Ranges", "bytes")
                resp.header("Content-Range")?.let { r.addHeader("Content-Range", it) }
                r.addHeader("Content-Length", total.toString())
            }
        }

        private fun guessMime(url: String): String = when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts", "m2ts" -> "video/mp2t"
            "m3u8" -> "application/vnd.apple.mpegurl"
            else -> "video/mp4"
        }
    }
}
