package com.movieshell.data.source

/**
 * PY 规则引擎接口。
 *
 * 所有调用走 JSON 字符串，避免复杂对象跨 Chaquopy 桥。
 * invoke 返回 pyspider_bridge.call 的结果：
 *   {"ok": true, "data": {...}} 或 {"ok": false, "error": "..."}
 *
 * @param rulePath 规则 .py 文件的绝对路径（filesDir/py/ 下）
 * @param method   Spider 方法名：homeContent / categoryContent / detailContent /
 *                 playerContent / searchContent / homeVideoContent
 * @param argsJson 传给 Spider 方法的关键字参数 JSON，如 {"filter": true}
 */
interface PyEngine {
    suspend fun invoke(rulePath: String, method: String, argsJson: String): String

    /**
     * v0.64.0 书源入口：走 pyspider_bridge.book_call，调书源文件的
     * bookSearch / bookDetail / bookChapter 方法。
     */
    suspend fun bookInvoke(rulePath: String, method: String, argsJson: String): String

    /**
     * v0.64.0：清掉指定规则文件的桥缓存（书源文件更新/删除后调用，
     * 避免复用已删除文件的旧实例）。
     */
    suspend fun resetRuleCache(rulePath: String)
}
