package com.movieshell.core.net

import com.movieshell.core.log.LogBus
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * v1.3.26：clash 插件代理路由（core 层，不反向依赖 data 层）。
 *
 * 全 App 的 OkHttp 流量要不要走本地 clash 代理，由这里一把决定。
 * data 层的 ClashProxy 在启动时注入两个回调；注入前默认全部直连，
 * 注入后按实时状态判断（服务是否在跑、该 host 是否属于开了代理的源）。
 * selector 自身绝不抛异常——判断出错一律回直连，不让代理逻辑炸掉正常请求。
 */
object ProxyRouting {

    private const val TAG = "ProxyRouting"

    /** 某 host 是否走代理（ClashProxy 注入：服务在跑 + host 在已开代理的源的 host 集里）。 */
    @Volatile
    var shouldProxyHost: (String) -> Boolean = { false }

    /** 代理地址（ClashProxy 注入：127.0.0.1:混合端口），服务没跑返回 null。 */
    @Volatile
    var proxyAddress: () -> InetSocketAddress? = { null }

    /** 挂到各 OkHttpClient.Builder 上的选择器；只读注入的回调，不持有 data 层对象。 */
    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            return runCatching {
                val host = uri.host ?: return listOf(Proxy.NO_PROXY)
                val addr = proxyAddress() ?: return listOf(Proxy.NO_PROXY)
                if (!shouldProxyHost(host)) return listOf(Proxy.NO_PROXY)
                // v1.4.4：只在命中代理时记一条（直连不记，避免刷屏）；配合设置页
                // 的生效域名列表，回答“这次请求走没走代理”。
                LogBus.d(TAG, "走代理 host=$host → $addr")
                listOf(Proxy(Proxy.Type.HTTP, addr))
            }.getOrDefault(listOf(Proxy.NO_PROXY))
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
            // 代理连不上由 OkHttp 按直连失败处理，这里不记日志（select 下次还会再算）。
        }
    }
}
