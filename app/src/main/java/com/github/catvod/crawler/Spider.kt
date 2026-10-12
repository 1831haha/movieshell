package com.github.catvod.crawler

import android.content.Context
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * v0.8.0：Java spider（csp_ 站）宿主基类。
 *
 * 包名必须与 tvkj.jar 里 spider 子类引用的完全一致
 * （dex 实测：Lcom/github/catvod/crawler/Spider;），否则 DexClassLoader
 * 加载子类时报 NoClassDefFoundError。
 * 方法签名按 dex 反查的子类 override 逐一对齐（返回值全是 String，
 * 无 JSONObject 形态）。默认实现均为安全空值，子类没 override 的方法
 * 调到基类也不会崩。
 *
 * v0.10.2：dex 全量扫描确认 jar 还会调宿主 Spider 的 3 个非 override 方法
 * （invoke-static safeDns()/client()、实例 initApi），缺失会报 NoSuchMethodError。
 */
open class Spider {
    open fun init(context: Context) {}

    open fun init(context: Context, extend: String) {
        init(context)
    }

    /** v0.10.2：部分 spider 在 init 内调 initApi(extend)，默认空实现。 */
    open fun initApi(extend: String) {}

    open fun homeContent(filter: Boolean): String = ""

    open fun homeVideoContent(): String = ""

    open fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, String>,
    ): String = ""

    open fun detailContent(ids: List<String>): String = ""

    open fun playerContent(flag: String, id: String, vipFlags: List<String>): String = ""

    open fun searchContent(key: String, quick: Boolean): String = ""

    open fun searchContent(key: String, quick: Boolean, pg: String): String =
        searchContent(key, quick)

    open fun manualVideoCheck(): Boolean = false

    open fun isVideoFormat(url: String): Boolean = false

    open fun destroy() {}

    companion object {
        @Volatile
        private var sharedClient: OkHttpClient? = null

        /**
         * v0.10.2：spider 通过 Spider.client() 拿共享 OkHttpClient
         * （dex 确认为 invoke-static）。App 自带 okhttp 4.12.0，
         * 父加载器优先会盖住 jar 内自带的版本，API 兼容。
         */
        @JvmStatic
        fun client(): OkHttpClient =
            sharedClient ?: synchronized(this) {
                sharedClient ?: OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .dns(safeDns())
                    .build()
                    .also { sharedClient = it }
            }

        /** v0.10.2：spider 拿 DNS（dex 确认为 invoke-static），默认系统 DNS。 */
        @JvmStatic
        fun safeDns(): Dns = Dns.SYSTEM
    }
}
