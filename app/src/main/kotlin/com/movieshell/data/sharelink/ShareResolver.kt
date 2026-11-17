package com.movieshell.data.sharelink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * v1.3.5：六盘分享链接解析总入口（UI 只调这里）。
 *
 * list：粘贴链接 → 认盘 → 播前检测（夸克/UC/百度；阿里/光鸭/迅雷
 * 旧代码不做检测，直接 UNKNOWN 交给解析跑）→ 列一层目录。
 * resolve：点一集 → 按盘取播放地址（见各盘 Api 文件头）。
 * 两个方法都是挂起函数，网络走 Dispatchers.IO；底下各盘函数是
 * 同步阻塞的（client 可注入，单测用 MockWebServer）。
 */
object ShareResolver {

    /** 列一层目录。根目录 dirKey 传 ""，子目录传父目录的 dirKey。 */
    suspend fun listDir(
        shareUrl: String,
        dirKey: String = "",
        client: OkHttpClient = ShareHttp.defaultClient(),
    ): ShareResult<ShareListing> = withContext(Dispatchers.IO) {
        val platform = ShareLink.platformOf(shareUrl)
            ?: return@withContext ShareResult.Err("这不是网盘分享链接，只认夸克、UC、百度、阿里、光鸭、迅雷六家的分享链接")
        when (platform) {
            SharePlatform.QUARK, SharePlatform.UC -> {
                when (QuarkUcApi.check(platform, shareUrl, client)) {
                    QuarkUcApi.Check.DEAD ->
                        return@withContext ShareResult.Err("这个${platform.displayName}分享已失效，换个链接再试", dead = true)
                    QuarkUcApi.Check.NEED_LOGIN ->
                        return@withContext ShareResult.Err("这个${platform.displayName}分享要先登录，去登录页登录再试", needLogin = true)
                    QuarkUcApi.Check.LOGIN_EXPIRED -> {
                        ShareAuth.markExpired(platform)
                        return@withContext ShareResult.Err("${platform.displayName}登录已过期，去登录页重登再试", needLogin = true)
                    }
                    else -> { /* OK / UNKNOWN：继续 */ }
                }
                QuarkUcApi.listDir(platform, shareUrl, dirKey.ifBlank { "0" }, client)
            }
            SharePlatform.BAIDU -> {
                when (BaiduApi.check(shareUrl, client)) {
                    BaiduApi.Check.DEAD ->
                        return@withContext ShareResult.Err("这个百度分享已失效或提取码不对，换个链接再试", dead = true)
                    else -> { /* OK / UNKNOWN：继续 */ }
                }
                BaiduApi.listDir(shareUrl, dirKey, client)
            }
            SharePlatform.ALIYUN -> AliyunApi.listDir(shareUrl, dirKey, client)
            SharePlatform.GUANGYA -> GuangyaApi.listDir(shareUrl, dirKey, client)
            SharePlatform.XUNLEI -> XunleiApi.listDir(shareUrl, dirKey, client)
        }
    }

    /** 取一集的播放地址。listing 必须是列目录返回的那一份（会话配对）。 */
    suspend fun resolve(
        listing: ShareListing,
        file: ShareFile,
        client: OkHttpClient = ShareHttp.defaultClient(),
    ): ShareResult<PlayLink> = withContext(Dispatchers.IO) {
        when (listing.platform) {
            SharePlatform.QUARK, SharePlatform.UC ->
                QuarkUcApi.resolve(listing.platform, listing, file, client)
            SharePlatform.BAIDU -> BaiduApi.resolve(listing, file, client)
            SharePlatform.ALIYUN -> AliyunApi.resolve(listing, file, client)
            SharePlatform.GUANGYA -> GuangyaApi.resolve(listing, file, client)
            SharePlatform.XUNLEI -> XunleiApi.resolve(listing, file, client)
        }
    }
}
