package com.movieshell.player

/**
 * v0.57：播放失败的友好错误模型。
 * 把引擎/网络抛出的原始错误翻译成人话：告诉用户发生了什么、为什么、能做什么。
 */
data class PlayError(
    val title: String,       // 一句话标题
    val reason: String,       // 原因说明
    val suggestions: List<String>, // 可操作的建议
) {
    companion object {
        /** 按原始错误文本分类 */
        fun classify(raw: String): PlayError {
            val r = raw.lowercase()
            return when {
                // 网络类
                "timeout" in r || "timed out" in r ->
                    PlayError(
                        "网络连接超时",
                        "视频地址响应太慢，可能是源服务器忙或你的网络不稳定。",
                        listOf("点重试再试一次", "换一条线路", "检查网络后重试"),
                    )
                "unknownhost" in r || "unable to resolve" in r ->
                    PlayError(
                        "找不到视频服务器",
                        "域名解析失败，源站可能已关停或被墙。",
                        listOf("换一条线路", "换个源试试"),
                    )
                "connection refused" in r || "connect" in r && "failed" in r ->
                    PlayError(
                        "服务器拒绝连接",
                        "视频服务器不接受连接，可能已关停或限制访问。",
                        listOf("换一条线路", "稍后再试"),
                    )
                // 格式/解码类
                "unsupported" in r || "not supported" in r ->
                    PlayError(
                        "格式不支持",
                        "这个视频的编码格式当前引擎播不了。",
                        listOf("设置里切换播放引擎再试", "换一条线路"),
                    )
                "404" in r || "not found" in r ->
                    PlayError(
                        "视频资源不存在",
                        "源站返回 404，这个地址的视频可能已被删除。",
                        listOf("换一条线路", "换个源搜这部剧"),
                    )
                "403" in r || "forbidden" in r ->
                    PlayError(
                        "访问被拒绝",
                        "源站拒绝了这次请求（403），可能是防盗链或需要登录。",
                        listOf("换一条线路", "换个源试试"),
                    )
                // 通用
                raw.isBlank() ->
                    PlayError(
                        "播放失败",
                        "未知原因，可能是源不稳定。",
                        listOf("点重试", "换一条线路"),
                    )
                else ->
                    PlayError(
                        "播放失败",
                        "原因：$raw".take(120),
                        listOf("点重试", "换一条线路", "换个源试试"),
                    )
            }
        }
    }
}
