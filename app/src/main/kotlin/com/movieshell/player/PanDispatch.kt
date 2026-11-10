package com.movieshell.player

import android.content.Context
import com.movieshell.core.log.LogBus
import com.movieshell.data.source.PanShareLinks
import com.movieshell.data.source.PyApi
import com.movieshell.data.source_mgr.BaiduAuthStore
import com.movieshell.data.source_mgr.PanShareCheck
import com.movieshell.data.source_mgr.PySourceManager
import com.movieshell.data.source_mgr.QuarkAuthStore
import com.movieshell.data.source_mgr.UcAuthStore
import com.movieshell.ui.components.AppToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.1.23：网盘分享分发——分享链接不再当直链给播放器。
 * v1.1.23 扩充：夸克、UC、百度三家都接；播前先验有效性，失效/过期
 * 直接给人话提示，不等到播放器黑屏才知道。
 *
 * 流程：认出是哪家盘 → 播前检测（分享失效/要登录/登录过期先拦下）→
 * 调对应内置 PY 规则 playerContent 拿直链 → 直链包进回环代理地址交
 * 播放器（Cookie 只留在代理回源那一侧）。
 * 规则返回的直链加头由代理持有，播放地址里只有一个不透明 token。
 *
 * 失败时给明确提示、不静默失败：哪一步、为什么、下一步做什么都说清。
 * 阿里云盘还没接，先拦下明说，不当直链放行。
 */
object PanDispatch {

    private const val TAG = "PanDispatch"

    /** 分享链接 → 回环代理地址；解析不出返回空串。 */
    suspend fun resolve(context: Context, shareUrl: String, flag: String): String {
        val platform = PanShareLinks.platformOf(shareUrl) ?: return ""
        val name = platformName(platform)
        if (platform == PanShareLinks.Platform.ALIYUN) {
            AppToast.show(context, "暂只支持夸克、UC、百度网盘，阿里云盘以后再接", long = true)
            return ""
        }
        val cookie = cookieOf(platform)
        // 播前检测：分享失效/要登录/登录过期先拦下，别等黑屏。
        val pre = withContext(Dispatchers.IO) { PanShareCheck.check(platform, shareUrl, cookie) }
        when (pre) {
            PanShareCheck.Result.DEAD -> {
                AppToast.show(context, "这个$name分享已失效，换个链接再试", long = true)
                return ""
            }
            PanShareCheck.Result.NEED_LOGIN -> {
                AppToast.show(context, "这个$name分享要先登录：设置 → 网盘 → $name网盘", long = true)
                return ""
            }
            PanShareCheck.Result.LOGIN_EXPIRED -> {
                markExpired(platform)
                AppToast.show(context, "登录已过期，去设置 → 网盘 → $name网盘重登", long = true)
                return ""
            }
            else -> { /* OK / UNKNOWN：继续走规则 */ }
        }
        AppToast.show(context, "正在解析$name分享…")
        val rule = when (platform) {
            PanShareLinks.Platform.QUARK -> "quark.py"
            PanShareLinks.Platform.UC -> "uc.py"
            PanShareLinks.Platform.BAIDU -> "baidu.py"
            PanShareLinks.Platform.ALIYUN -> return ""
        }
        val rulePath = PySourceManager.builtinRulePath(rule)
        if (rulePath.isNullOrBlank()) {
            AppToast.show(context, "$name规则未就绪，请重启 App 再试", long = true)
            LogBus.w(TAG, "内置$name规则路径为空")
            return ""
        }
        val raw = runCatching { PyApi().play(rulePath, flag, shareUrl) }
            .onFailure { LogBus.w(TAG, "$name规则执行失败：${it.message}") }
            .getOrNull()
        val direct = raw?.url.orEmpty()
        if (direct.isBlank()) {
            AppToast.show(context, failHint(platform, cookie.isNotBlank()), long = true)
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

    private fun platformName(platform: PanShareLinks.Platform): String = when (platform) {
        PanShareLinks.Platform.QUARK -> "夸克"
        PanShareLinks.Platform.UC -> "UC"
        PanShareLinks.Platform.BAIDU -> "百度"
        PanShareLinks.Platform.ALIYUN -> "阿里"
    }

    private fun cookieOf(platform: PanShareLinks.Platform): String = when (platform) {
        PanShareLinks.Platform.QUARK -> QuarkAuthStore.getCookie()
        PanShareLinks.Platform.UC -> UcAuthStore.getCookie()
        PanShareLinks.Platform.BAIDU -> BaiduAuthStore.getCookie()
        PanShareLinks.Platform.ALIYUN -> ""
    }

    private fun markExpired(platform: PanShareLinks.Platform) {
        when (platform) {
            PanShareLinks.Platform.QUARK -> QuarkAuthStore.markExpired()
            PanShareLinks.Platform.UC -> UcAuthStore.markExpired()
            PanShareLinks.Platform.BAIDU -> BaiduAuthStore.markExpired()
            PanShareLinks.Platform.ALIYUN -> { /* 不接 */ }
        }
    }

    /** 规则没拿到地址时的人话：分平台、分有无登录态。 */
    private fun failHint(platform: PanShareLinks.Platform, hasCookie: Boolean): String = when (platform) {
        PanShareLinks.Platform.QUARK -> if (hasCookie) {
            "夸克解析失败：转存和直取都没拿到地址，可能是登录过期或接口变了，去设置里重登再试"
        } else {
            "这个夸克分享要先登录：设置 → 网盘 → 夸克网盘（大文件必须登录）"
        }
        PanShareLinks.Platform.UC -> if (hasCookie) {
            "UC 解析失败：转存和直取都没拿到地址，可能是登录过期或接口变了，去设置里重登再试"
        } else {
            "这个 UC 分享要先登录：设置 → 网盘 → UC网盘（大文件必须登录）"
        }
        PanShareLinks.Platform.BAIDU -> if (hasCookie) {
            "百度解析失败：分享可能失效、登录已过期，或账号不是 SVIP"
        } else {
            "百度分享要登录才能播：设置 → 网盘 → 百度网盘（取直链需 SVIP）"
        }
        PanShareLinks.Platform.ALIYUN -> "阿里云盘暂不支持"
    }
}
