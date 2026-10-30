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
 *
 * v0.97.0：过滤受 SniffConfig.adFilterEnabled 总开关门控（默认开），
 * 由 Factory 传入 enabledProvider 实现"改完即时生效"。
 */
class AdFilteringDataSource(
    private val upstream: DataSource,
    private val enabledProvider: () -> Boolean = { true },
) : DataSource {

    private var filteredStream: ByteArrayInputStream? = null
    private var filteredBytes: ByteArray? = null
    private var isFiltered = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        val length = upstream.open(dataSpec)
        // v0.294.7：除 .m3u8 后缀外，再认 Content-Type（mpegurl 各变体）与起播前识别结果——
        // 无后缀 m3u8 也能去广告。分片（video/mp2t 等）不会命中。
        val isPlaylist = isPlaylistRequest(url, upstream.responseHeaders)

        // 只处理播放列表；分片直接透传；总开关关闭时整体透传
        if (!isPlaylist || length == 0L || !enabledProvider()) {
            return length
        }

        // 把播放列表全文读出来（读本身失败直接抛给 Media3，不假装透传）
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
        // v0.217.0（F18）：过滤失败也走内存流、内容为原文——原来 catch 里
        // 记“透传原文”却返回已读到头的上游流，Media3 读到的是 EOF，
        // 兜底根本不成立；现在失败与成功同一条返回路径。
        val filtered = try {
            M3u8AdFilter.filter(text, url)
        } catch (e: Exception) {
            LogBus.e("AdFilter", "过滤失败，返回原文: ${e.message}")
            text
        }
        if (filtered != text) {
            LogBus.i("AdFilter", "m3u8 去广告：播放列表已重写（${text.length} -> ${filtered.length} 字符）")
        }
        filteredBytes = filtered.toByteArray(Charsets.UTF_8)
        filteredStream = ByteArrayInputStream(filteredBytes)
        isFiltered = true
        return filteredBytes!!.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (isFiltered) {
            val stream = filteredStream ?: return C.RESULT_END_OF_INPUT
            val n = stream.read(buffer, offset, length)
            return if (n < 0) C.RESULT_END_OF_INPUT else n
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri() = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    companion object {
        /** 纯函数：是否当作播放列表处理（后缀 / Content-Type / 识别提示）。 */
        fun isPlaylistRequest(url: String, responseHeaders: Map<String, List<String>>?): Boolean {
            val path = url.substringBefore("?").substringBefore("#").lowercase()
            if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return true
            if (path.endsWith(".ts") || path.endsWith(".mp4") || path.endsWith(".m4s") || path.endsWith(".key")) return false
            val ct = responseHeaders?.entries
                ?.firstOrNull { it.key != null && it.key.equals("Content-Type", ignoreCase = true) }
                ?.value?.firstOrNull()
            if (MediaTypeSniffer.inferFromContentType(ct) == MediaTypeSniffer.Kind.HLS) return true
            // 起播前探测确认过是 HLS 的那一条主地址（精确匹配，不按路径猜，免得把无后缀分片整段读进内存）
            return MediaTypeSniffer.explicitHint(url) == MediaTypeSniffer.Kind.HLS &&
                MediaTypeSniffer.inferFromContentType(ct) != MediaTypeSniffer.Kind.PROGRESSIVE
        }
    }
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
        private val enabledProvider: () -> Boolean = { true },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            AdFilteringDataSource(upstreamFactory.createDataSource(), enabledProvider)
    }
}
