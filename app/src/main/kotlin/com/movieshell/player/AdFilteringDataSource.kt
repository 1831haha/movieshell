package com.movieshell.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.movieshell.data.repo.LogBus
import java.io.ByteArrayInputStream

/**
 * v0.18.0：m3u8 去广告 DataSource 包装器。
 *
 * 包装底层 HTTP DataSource：当请求的 URL 以 .m3u8 结尾（或 Content-Type
 * 为 mpegurl）时，把播放列表全文读出来，经 [M3u8AdFilter] 过滤后再交给
 * ExoPlayer；分片 (.ts) 请求直接透传，不做处理。
 */
class AdFilteringDataSource(
    private val upstream: DataSource,
) : DataSource {

    private var filteredStream: ByteArrayInputStream? = null
    private var filteredBytes: ByteArray? = null
    private var bytesRead = 0L
    private var isFiltered = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        val isPlaylist = url.substringBefore("?").lowercase().endsWith(".m3u8")

        val length = upstream.open(dataSpec)

        // 只处理播放列表；分片直接透传
        if (!isPlaylist || length == 0L) {
            return length
        }

        return try {
            // 把播放列表全文读出来
            val buf = if (length == C.LENGTH_UNSET.toLong()) {
                // 未知长度：读到 EOF
                val baos = java.io.ByteArrayOutputStream()
                val tmp = ByteArray(8192)
                while (true) {
                    val n = upstream.read(tmp, 0, tmp.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    baos.write(tmp, 0, n)
                }
                baos.toByteArray()
            } else {
                val data = ByteArray(length.toInt())
                var off = 0
                while (off < data.size) {
                    val n = upstream.read(data, off, data.size - off)
                    if (n == C.RESULT_END_OF_INPUT) break
                    off += n
                }
                data.copyOf(off)
            }

            val text = buf.toString(Charsets.UTF_8)
            val filtered = M3u8AdFilter.filter(text, url)
            if (filtered !== text && filtered != text) {
                LogBus.i("AdFilter", "m3u8 去广告：已过滤 ${text.lines().size - filtered.lines().size} 行")
            }
            filteredBytes = filtered.toByteArray(Charsets.UTF_8)
            filteredStream = ByteArrayInputStream(filteredBytes)
            bytesRead = 0
            isFiltered = true
            filteredBytes!!.size.toLong()
        } catch (e: Exception) {
            LogBus.e("AdFilter", "过滤失败，透传原文: ${e.message}")
            isFiltered = false
            length
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (isFiltered) {
            val stream = filteredStream ?: return C.RESULT_END_OF_INPUT
            val n = stream.read(buffer, offset, length)
            if (n > 0) bytesRead += n
            return if (n < 0) C.RESULT_END_OF_INPUT else n
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri() = upstream.uri
    override fun close() {
        filteredStream?.close()
        filteredStream = null
        filteredBytes = null
        isFiltered = false
        upstream.close()
    }

    /** Factory：包装任意 DataSource.Factory */
    class Factory(
        private val upstreamFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            AdFilteringDataSource(upstreamFactory.createDataSource())
    }
}
