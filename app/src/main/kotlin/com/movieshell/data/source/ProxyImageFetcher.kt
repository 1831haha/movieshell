package com.movieshell.data.source

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.movieshell.data.repo.PySourceManager
import okio.Buffer

/**
 * v0.291.0：proxy:// 封面取图器。部分 PY 规则把封面写成 proxy://do=py&url=..，
 * 只有规则自己的 localProxy 知道取图要带什么 Referer；直接拿真实地址去要图
 * 会被图站防盗链拦下，所以这里不剥壳直取，而是调当前激活 PY 规则的
 * localProxy 取字节，再以字节流交给 Coil 解码——全程进程内，不开端口。
 * 非 proxy:// 的地址一律返回 null，走 Coil 默认取图器，不改动现有链路。
 * 聚合搜索里非激活源的 proxy 图会走到激活源的 localProxy，Referer 不对时
 * 规则按约定返回 1x1 透明图，字节过小已在取图层判失败、回落占位，不会
 * 把透明小图当成封面（该边角 case 不做跨源规则映射，保持单入口简单）。
 */
class ProxyImageFetcher(
    private val data: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val innerUrl = parseProxyUrl(data) ?: return null
        val rulePath = PySourceManager.activeRulePathForProxy() ?: return null
        val image = ChaquopyEngine.fetchProxyImage(rulePath, innerUrl) ?: return null
        // Coil 2.6.0 实测签名：SourceResult 是 coil.fetch 下的顶层类（非 FetchResult 的嵌套类），
        // 其 source 必须是 ImageSource，不能直接传 okio Buffer；写法照抄同版本 ByteBufferFetcher。
        return SourceResult(
            source = ImageSource(Buffer().write(image.bytes), options.context),
            mimeType = image.contentType,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<String> {
        override fun create(
            data: String,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            if (data.startsWith("proxy://")) {
                return ProxyImageFetcher(data, options)
            }
            return null
        }
    }

    companion object {
        /**
         * proxy://do=py&url=<urlencoded> 取出真实地址；格式不对返回 null，
         * 让 Coil 走默认链路（失败后占位，不崩）。
         * 规则侧用 quote(url, safe='') 编码，'&' 等字符都已转义，
         * 按 '&' 切分参数是安全的；'+' 在原地址里会被编成 %2B，
         * URLDecoder 不会把它误当成空格。
         */
        fun parseProxyUrl(data: String): String? = runCatching {
            val query = data.substringAfter("?", "")
            if (query.isBlank()) return null
            val params = query.split("&").mapNotNull {
                val eq = it.indexOf("=")
                if (eq <= 0) null else it.substring(0, eq) to it.substring(eq + 1)
            }.toMap()
            val encoded = params["url"] ?: return null
            java.net.URLDecoder.decode(encoded, "UTF-8").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
