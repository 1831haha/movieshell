package com.github.catvod.crawler

import android.content.Context

/**
 * v0.8.0：Java spider（csp_ 站）宿主基类。
 *
 * 包名必须与 tvkj.jar 里 spider 子类引用的完全一致
 * （dex 实测：Lcom/github/catvod/crawler/Spider;），否则 DexClassLoader
 * 加载子类时报 NoClassDefFoundError。
 * 方法签名按 dex 反查的子类 override 逐一对齐（返回值全是 String，
 * 无 JSONObject 形态）。默认实现均为安全空值，子类没 override 的方法
 * 调到基类也不会崩。
 */
open class Spider {
    open fun init(context: Context) {}

    open fun init(context: Context, extend: String) {
        init(context)
    }

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
}
