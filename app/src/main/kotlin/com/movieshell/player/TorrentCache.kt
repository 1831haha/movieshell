package com.movieshell.player

import com.movieshell.core.log.LogBus
import java.net.HttpURLConnection
import java.net.URL

/** v0.294.30：按 infohash 从公共种子缓存取 .torrent（任一命中即用，失败返回 null）。 */
object TorrentCache {
    fun urls(hashHex: String): List<String> {
        val up = hashHex.uppercase()
        return listOf(
            "https://itorrents.org/torrent/$up.torrent",
            "https://itorrents.net/torrent/$up.torrent",
            "https://btcache.me/torrent/$up",
        )
    }

    fun fetch(hashHex: String): ByteArray? {
        for (u in urls(hashHex)) {
            val b = runCatching {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 4000; c.readTimeout = 5000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
            }.getOrNull()
            if (MagnetStreamLogic.looksLikeTorrent(b)) {
                // 校验 infohash 一致，防缓存给错种子
                val ok = runCatching {
                    org.libtorrent4j.TorrentInfo(b).infoHash().toHex().equals(hashHex, true)
                }.getOrDefault(false)
                if (ok) { LogBus.i("XiaYingMagnet", "种子缓存命中：${URL(u).host}"); return b }
            }
        }
        return null
    }
}
