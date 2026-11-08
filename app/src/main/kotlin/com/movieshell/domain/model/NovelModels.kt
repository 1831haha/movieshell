package com.movieshell.domain.model

import kotlinx.serialization.Serializable

/**
 * v0.35.0 小说：一本书的元信息。章节正文存在 novels/{id}.json，不进这个表。
 */
@Serializable
data class Novel(
    val id: String = "",
    val sourceKey: String = "local", // local=TXT 导入，后续在线源用源 key
    val title: String = "",
    val author: String? = null,
    val cover: String? = null,
    val desc: String? = null,
    val totalChapters: Int = 0,
    val lastReadChapter: Int = 0, // 已读到的章节下标（从 0 起），0=没读过
    // v0.217.0（F46）：是否动过这本书——进度记录存在即已开始。原来只看
    // lastReadChapter==0，正在读第 1 章的书也被算成“没读过”、进度 0%。
    // 派生自进度记录有无（list()/get() 里算），不由调用方手写。
    val hasStartedReading: Boolean = false,
    // v0.214.0：书架行章节标题——在读章标题由阅读器落盘时写进度，
    // 最新章标题由入架/换源/追更写目录时写索引；老数据无此字段时回落只显章号。
    val lastReadChapterTitle: String? = null,
    val latestChapterTitle: String? = null,
    // v0.214.0：单本追更开关（默认开；关掉的书手动/后台检查都跳过）。
    val updateEnabled: Boolean = true,
    val addedAt: Long = 0L,
    // v0.249.0：目录状态——READY=已有目录、LOADING=刚入架目录还在后台取、
    // FAILED=目录取失败（书仍在架，可重试）。老数据缺字段回落 READY。
    val dirState: String = "READY",
    // v0.249.0 收尾：源内书籍标识（JSON 源即详情地址，PY 源即规则内 id）——
    // 书架重试时凭它精确拉目录，不走标题重搜；老数据缺字段回落空串。
    val remoteId: String = "",
    // v0.247.0 复核：上次成功提交的章节事务 id（WAL 提交判定用，不展示）。
    // 老数据无此字段时回落空串，永不与新事务 id 撞上。
    val lastTxId: String = "",
)

/** 单章。正文 content 可能很长，单独存文件。 */
@Serializable
data class Chapter(
    val novelId: String = "",
    val index: Int = 0,
    val title: String = "",
    val content: String = "",
    // v0.61.0：在线书源的章节地址；content 为空时按此地址按需拉取
    val url: String = "",
    // v1.1.3：段评最小版——本章评论页地址；老数据缺字段回落空串（无评论入口）。
    val commentUrl: String = "",
)

/** 阅读进度：读到第几章、章内第几个字符。 */
@Serializable
data class ReadingProgress(
    val novelId: String = "",
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,
    val updatedAt: Long = 0L,
    // v0.214.0：在读章节标题（阅读器落盘时一并写，书架行直接显示，不用再翻目录文件）。
    val chapterTitle: String = "",
)

/** 书架分组。 */
enum class ShelfTab { READING, WANT, FINISHED }

/**
 * v1.1.6 分层第 2 批：正文失败分类——UI 按它给短提示，细节进日志，不再一个通用 toast。
 * 原 NovelSourceEngine.ContentFail，一字未改，只换位置（小说引擎接口的返回契约）。
 */
enum class ContentFail {
    /** 403 或疑似验证页，验证通道可解（预加载静默时也用它，只是不提示）。 */
    VERIFY,

    /** 连接/读取超时（底层拦截器已退避重试过，这里不再叠加）。 */
    TIMEOUT,

    /** 域名解析失败。 */
    DNS,

    /** 其他 IO 异常。 */
    NETWORK,

    /** 验证已过、重抓仍 403：站方拒绝，非验证可解。 */
    FORBIDDEN,

    /** 200 且页面正常，但规则与兜底都提不出正文。 */
    PARSE_EMPTY,

    /** 正文抓到了，但净化后成空串（规则过宽）：不写盘、不覆盖旧正文。 */
    PURIFY_EMPTY,

    /** 无明确信号的失败。 */
    UNKNOWN,
}

/** v0.250.0：正文拉取结果——空串分不清 403/超时/解析失败，调用方必须拿到原因。 */
sealed interface ContentResult {
    data class Ok(val text: String) : ContentResult
    data class Failed(val reason: ContentFail) : ContentResult
}

/** v0.229.0：搜书详情页用——详情页规则一次求全（书名/作者/简介/封面/类型）。 */
data class BookDetailInfo(
    val name: String = "",
    val author: String = "",
    val intro: String = "",
    val cover: String = "",
    val kind: String = "",
)
