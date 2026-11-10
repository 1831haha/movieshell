package com.movieshell.player

import android.content.Context
import com.movieshell.core.log.LogBus
import com.movieshell.data.source.PanShareLinks
import com.movieshell.data.source.PyApi
import com.movieshell.data.source_mgr.PySourceManager
import com.movieshell.data.source_mgr.QuarkAuthStore
import com.movieshell.ui.components.AppToast

/**
 * v1.1.23：网盘分享分发——分享链接不再当直链给播放器。
 *
 * 流程：认出是夸克分享 → 调内置夸克 PY 规则 playerContent 拿直链 →
 * 直链包进回环代理地址交播放器（Cookie 只留在代理回源那一侧）。
 * 规则返回的直链加头由代理持有，播放地址里只有一个不透明 token。
 *
 * 失败时给明确提示、不静默失败：没登录态先报登录；有登录态仍失败
 * 报解析失败（最常见是 Cookie 过期，去设置里重登）。
 * UC、百度、阿里已能认出，解析未接，先拦下明说，不当直链放行。
 */
object PanDispatch {

    private const val TAG = "PanDispatch"
    private const val QUARK_RULE = "quark.py"

    /** 分享链接 → 回环代理地址；解析不出返回空串。 */
    suspend fun resolve(context: Context, shareUrl: String, flag: String): String {
        val platform = PanShareLinks.platformOf(shareUrl)
        if (platform == null) return ""
        if (platform != PanShareLinks.Platform.QUARK) {
            AppToast.show(context, "暂只支持夸克网盘，UC、百度、阿里以后再接", long = true)
            LogBus.w(TAG, "未接的网盘平台：$platform")
            return ""
        }
        val rulePath = PySourceManager.builtinRulePath(QUARK_RULE)
        if (rulePath.isNullOrBlank()) {
            AppToast.show(context, "夸克规则未就绪，请重启 App 再试", long = true)
            LogBus.w(TAG, "内置夸克规则路径为空")
            return ""
        }
        val raw = runCatching { PyApi().play(rulePath, flag, shareUrl) }
            .onFailure { LogBus.w(TAG, "夸克规则执行失败：${it.message}") }
            .getOrNull()
        val direct = raw?.url.orEmpty()
        if (direct.isBlank()) {
            if (!QuarkAuthStore.hasCookie()) {
                AppToast.show(context, "这个夸克分享要先登录：设置 → 网盘 → 夸克网盘", long = true)
            } else {
                AppToast.show(context, "夸克解析失败：分享可能失效，或登录已过期，请去设置里重登", long = true)
            }
            return ""
        }
        val proxyUrl = PanProxy.register(direct, raw?.header.orEmpty())
        if (proxyUrl.isNullOrBlank()) {
            AppToast.show(context, "本地代理没起来，请重启 App 再试", long = true)
            LogBus.w(TAG, "回环代理注册失败")
            return ""
        }
        return proxyUrl
    }
}
