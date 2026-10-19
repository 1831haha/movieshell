package com.movieshell.data.source

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * CmsApi / Type4Api 共用的网络小件（v0.112.0 A3 + A5 收敛）。
 *
 * 原来两处各有一份拷贝，safe 差别只在 TAG 字面量，getText 差别只在
 * 各自实例的 client 与 UA；抽到这里参数化。UA 字面量两处原本完全一致，
 * 收成全仓唯一的 DEFAULT_UA。
 */

/** 全仓统一的移动端 UA（原 CmsApi / Type4Api 各一份同字面量）。 */
internal const val DEFAULT_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

/** Chrome 120 老构建 UA（原 SniffEngine / BookRuleEngine 各一份同字面量）。 */
internal const val DEFAULT_UA_CHROME120 =
    "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

/** Android 10 老设备 UA（原 BuiltinSubHdSource，字符串拼接值与此逐字一致）。 */
internal const val DEFAULT_UA_ANDROID10 =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

/** 极简平台 UA（原 EpgFetcher）。 */
internal const val DEFAULT_UA_SHORT = "Mozilla/5.0 (Linux; Android 14)"

/** 平台 + WebKit UA（原 MusicApi）。 */
internal const val DEFAULT_UA_WEBKIT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"

/** 裸 UA 字面量（原 JarSpiderStore 下载 jar 用）。 */
internal const val DEFAULT_UA_BARE = "Mozilla/5.0"

/**
 * IO 上异常兜底：任何异常吞掉返回 fallback，不向上传播崩溃；
 * 唯独 CancellationException 必须透传，否则取消搜索/切页后请求照样跑到超时。
 */
internal suspend fun <T> ioSafe(tag: String, fallback: T, block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(tag, "request failed: ${e.message}")
            fallback
        }
    }

/** 同步 GET 取文本；HTTP 非 2xx 抛 IOException。默认带全仓 UA。 */
@Throws(IOException::class)
internal fun OkHttpClient.getText(url: HttpUrl, ua: String = DEFAULT_UA): String {
    val req = Request.Builder().url(url).header("User-Agent", ua).build()
    newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${resp.message}")
        return resp.body?.string().orEmpty()
    }
}
