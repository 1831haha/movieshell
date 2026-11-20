package com.movieshell.data.sync

import android.content.Context
import android.util.Log
import com.movieshell.core.util.AtomicFile
import com.movieshell.core.util.StoragePaths
import com.movieshell.data.novel.BookSourceManager
import com.movieshell.data.source.MusicApi
import com.movieshell.data.source_mgr.ApiSource
import com.movieshell.data.source_mgr.DrpySourceManager
import com.movieshell.data.source_mgr.PhpSourceManager
import com.movieshell.data.source_mgr.PySourceManager
import com.movieshell.data.source_mgr.WebSourceManager
import com.movieshell.data.store.ApiSourceStore
import com.movieshell.data.store.BookGroup
import com.movieshell.data.store.Bookmark
import com.movieshell.data.store.BookmarkStore
import com.movieshell.data.store.BookshelfGroupStore
import com.movieshell.data.store.FollowItem
import com.movieshell.data.store.FollowStore
import com.movieshell.data.store.LiveChannel
import com.movieshell.data.store.LiveSource
import com.movieshell.data.store.LiveStore
import com.movieshell.data.store.MusicPlaylist
import com.movieshell.data.store.MusicSource
import com.movieshell.data.store.MusicSourceStore
import com.movieshell.data.store.MusicStore
import com.movieshell.data.store.NovelStore
import com.movieshell.data.store.ReadingProgressStore
import com.movieshell.domain.model.BookSource
import com.movieshell.domain.model.Chapter
import com.movieshell.domain.model.Novel
import com.movieshell.domain.model.ReadingProgress
import com.movieshell.player.HistoryEntry
import com.movieshell.player.HistoryStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 订阅备份 / 恢复。
 *
 * 备份 JSON 格式（version 2）：
 * { "version": 2,
 *   "apiSources": [ {"name","url","enabled"} ],
 *   "activeRules": {"drpy": "...", "py": "...", "php": "...", "web": "..."},
 *   "follow": [ 追剧条目 ],
 *   "bookshelf": {"novels": [{"novel", "chapters"}], "groups": [...],
 *                "progress": [...], "bookmarks": [{"novelId", "items"}]},
 *   "music": {"local": [...], "fav": [...], "playlists": [...]},
 *   "live": {"sources": [...], "manual": [...], "favs": [...], "globalEpg": "..."},
 *   "history": [ 播放历史条目 ],
 *   "bookSources": [ 完整书源列表（book_sources.json 内容） ],
 *   "musicSources": {"registry": [ 音乐源注册表 ],
 *                    "scripts": [{"name": "<lx_sources 下文件名>", "content": "本地 JS 文件内容"}]},
 *   "ruleFiles": {"drpy": {"name","content"} | null, "py": {...} | null,
 *                 "php": {"name","content"} | null,
 *                 "web": [{"name","content"}] } }
 *
 * - apiSources 来自 ApiSourceStore（持久化文件，不存在则用默认订阅）；
 * - activeRules 四项都读各 Manager 现有 API（内存状态）；PHP 当前规则
 *   与网页源当前站点与规则文件是同一个备份能力：只恢复名字不够，换机
 *   时文件不在就选不中，所以 ruleFiles 段把当前规则文件（drpy/py/php
 *   当前规则各一 + 全部网页源文件）的内容一并备份，恢复时先写文件再切
 *   active；音乐本地 JS 同理只备 file:// 路径不够，scripts 段备文件内容。
 * - version 1 备份继续接受：缺新段跳过、按旧逻辑恢复；version 2 才解析
 *   新增段。新段格式不对时整单拒绝（当前数据一动不动），不让坏段覆盖
 *   旧数据；恢复沿用现有快照/校验/回滚保护链。
 * - v0.186.0：第 7 组扩展——追剧、书架、音乐、直播各占独立一段，
 *   不混进 apiSources；老备份（无这四段）照常导入，缺段即跳过。
 * - v1.2.13：播放历史进备份（history 段，WebDAV 同步用；老备份无此段时跳过）。
 * - 本备份不是全量：插件安装包、下载文件、系统设置不在其中，
 *   导出页与同步文案不得宣称全量。
 * - 恢复口径与主段一致：全部校验先行，缺字段的段整单拒绝；
 *   写盘后回读比对，任何一段失败则主段与各段一并回滚。
 * - 恢复的近似项（公开 API 无法原样还原的字段）：追剧的 updatedAt 与
 *   集数变化计时、书架的 addedAt、歌单的 id 与创建时间、书架分组的 id
 *   均在恢复时重算，条目内容与归属关系不变；书架分组名恢复时截断到
 *   12 字（与分组创建口径一致）。
 */
/** 书架备份里的单本：元信息 + 全量章节（含正文，恢复后可直接读）。 */
@Serializable
private data class BookshelfNovelEntry(
    val novel: Novel = Novel(),
    val chapters: List<Chapter> = emptyList(),
)

/** 书架备份里的单本书签组：同书多条书签按创建时间升序排列后恢复。 */
@Serializable
private data class BookmarkEntry(
    val novelId: String = "",
    val items: List<Bookmark> = emptyList(),
)

/** 校验通过的书架段（内部用，不进备份格式）。 */
private data class BookshelfParsed(
    val novels: List<BookshelfNovelEntry>,
    val groups: List<BookGroup>,
    val progress: List<ReadingProgress>,
    val bookmarks: List<BookmarkEntry>,
)

/** 校验通过的音乐段（内部用）。 */
private data class MusicParsed(
    val local: List<MusicApi.Track>,
    val fav: List<MusicApi.Track>,
    val playlists: List<MusicPlaylist>,
)

/** 校验通过的直播段（内部用）。 */
private data class LiveParsed(
    val sources: List<LiveSource>,
    val manual: List<LiveChannel>,
    val favs: Set<String>,
    val globalEpg: String,
)

/** 单个规则/脚本文件（内部用）：备份里只存文件名与内容，不存设备路径。 */
internal data class RuleFileEntry(
    val name: String = "",
    val content: String = "",
)

/** 校验通过的书源段（内部用）：rawJson 原样写回 book_sources.json。 */
internal data class BookSourcesParsed(
    val rawJson: String,
    val sources: List<BookSource>,
)

/** 校验通过的音乐源段（内部用）：registry 已按本机路径重映射 file:// 地址。 */
internal data class MusicSourcesParsed(
    val registry: List<MusicSource>,
    val scripts: List<RuleFileEntry>,
)

/** 校验通过的规则文件段（内部用）：drpy/py/php 各为当前规则文件，web 为全部网页源文件。 */
internal data class RuleFilesParsed(
    val drpy: RuleFileEntry?,
    val py: RuleFileEntry?,
    val php: RuleFileEntry?,
    val web: List<RuleFileEntry>,
)

object BackupManager {
    private const val TAG = "BackupManager"

    /** 备份格式版本（导出用）；恢复时接受 1 与 2，见 [isSupportedBackupVersion]。 */
    const val BACKUP_VERSION = 2

    /** 恢复接受的最老备份版本（version 1：无 v2 新段，按旧逻辑恢复）。 */
    const val BACKUP_VERSION_MIN = 1

    /** 导入是否接受该备份版本：只接受 1（旧备份）与 2（当前导出）。 */
    internal fun isSupportedBackupVersion(version: Int?): Boolean =
        version == BACKUP_VERSION_MIN || version == BACKUP_VERSION

    /** 单个规则/脚本文件内容上限（2MB；超限的导出时跳过并记日志，导入时整段拒绝）。 */
    internal const val MAX_RULE_FILE_BYTES = 2 * 1024 * 1024

    /** 书源段条数上限；音乐源注册表与脚本数上限；网页源文件数上限。 */
    internal const val MAX_BOOK_SOURCES = 5000
    internal const val MAX_MUSIC_SOURCES = 2000
    internal const val MAX_MUSIC_SCRIPTS = 500
    internal const val MAX_WEB_FILES = 500

    /**
     * v0.216.0（C15）：恢复前磁盘快照文件名。恢复动笔前先把当前状态写到这里；
     * 恢复成功后删掉；中途被杀/失败则留着，设置页“备份与恢复”里可一键导回。
     */
    private const val PRE_RESTORE_SNAPSHOT = "backup_pre_restore.json"

    /**
     * v1.3.25 合并 8：每日静默备份文件名（私有目录，单文件覆盖）。
     * 恢复入口在设置页“备份与恢复”里；恢复前先写中断快照，与手动
     * 导入同一条保护链（importJson 默认 writeSnapshot=true）。
     */
    private const val DAILY_BACKUP_FILE = "backup_daily.json"

    fun dailyBackupFile(context: Context): File = File(context.filesDir, DAILY_BACKUP_FILE)

    /** 是否有每日自动备份可恢复。 */
    fun hasDailyBackup(context: Context): Boolean = dailyBackupFile(context).isFile

    /** 每日自动备份的备份时间（文件 mtime，无备份时 0）。 */
    fun dailyBackupTime(context: Context): Long =
        dailyBackupFile(context).let { if (it.isFile) it.lastModified() else 0L }

    /** 从每日自动备份恢复。 */
    // v1.3.25 审查修（GPT M-08）：读文件＋JSON 解析都在 IO 线程——原来
    // 设置页 scope.launch（主线程）直接调，备份一大就卡顿/ANR。
    suspend fun restoreDailyBackup(context: Context): RestoreResult = withContext(Dispatchers.IO) {
        val f = dailyBackupFile(context)
        if (!f.isFile) return@withContext RestoreResult.Failed("没有每日自动备份")
        val text = runCatching { f.readText() }.getOrNull()
            ?: return@withContext RestoreResult.Failed("自动备份读取失败")
        importJson(context, text)
    }

    /** 是否有中断/失败后留下的恢复前快照，供设置页入口判断。 */
    fun hasPreRestoreSnapshot(context: Context): Boolean =
        File(context.filesDir, PRE_RESTORE_SNAPSHOT).isFile

    /**
     * 从恢复前快照导回（中断恢复的补救入口）。快照本身不再套一层新快照
     * （否则导回前先把好快照覆盖掉）；成功后删掉快照，失败则留着可重试。
     */
    // 同 M-08：快照导回也是读文件＋JSON 解析，走 IO 线程。
    suspend fun restorePreRestoreSnapshot(context: Context): RestoreResult = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, PRE_RESTORE_SNAPSHOT)
        if (!f.isFile) return@withContext RestoreResult.Failed("没有中断前的备份快照")
        val text = runCatching { f.readText() }.getOrNull()
            ?: return@withContext RestoreResult.Failed("快照读取失败")
        when (val r = importJson(context, text, writeSnapshot = false)) {
            is RestoreResult.Ok -> {
                runCatching { f.delete() }
                RestoreResult.Ok
            }
            is RestoreResult.Failed -> r
        }
    }

    sealed interface RestoreResult {
        data object Ok : RestoreResult
        data class Failed(val reason: String) : RestoreResult
    }

    sealed interface ExportResult {
        data class Ok(val json: String) : ExportResult
        data class Failed(val reason: String) : ExportResult
    }

    /**
     * 导出当前配置为 JSON 字符串。
     * v0.170.0：本地订阅文件已损坏时拒绝导出——空表绝不允许再覆盖云端好备份。
     * v0.186.0：suspend——追剧、书架、音乐三段走各 store 的 suspend 读接口；
     * 直播段读 live_prefs（订阅、手动频道、收藏、全局节目单地址）。
     */
    suspend fun exportJson(context: Context): ExportResult {
        DrpySourceManager.init(context)
        PySourceManager.init(context)
        PhpSourceManager.init(context)
        WebSourceManager.init(context)
        if (ApiSourceStore.isCorrupt(context)) {
            Log.w(TAG, "本地订阅文件已损坏，拒绝导出")
            return ExportResult.Failed("本地订阅文件已损坏，拒绝导出（避免覆盖云端好备份）")
        }
        val live = readLiveSection(context)
            ?: return ExportResult.Failed("直播配置读取失败，拒绝导出（避免覆盖云端好备份）")
        // builder lambda 不是 suspend，suspend 段先在外面算好。
        val followSection = encodeList(FollowStore.readSync(context), FollowItem.serializer())
        val bookshelfSection = buildBookshelfSection(context)
        val musicSection = buildMusicSection(context)
        val bookSourcesSection = buildBookSourcesSection(context)
            ?: return ExportResult.Failed("书源文件损坏，拒绝导出（避免覆盖云端好备份）")
        val musicSourcesSection = buildMusicSourcesSection(context)
        val ruleFilesSection = buildRuleFilesSection(context)
        val root = buildJsonObject {
            put("version", BACKUP_VERSION)
            putJsonArray("apiSources") {
                ApiSourceStore.load(context).forEach { s ->
                    addJsonObject {
                        put("name", s.name)
                        put("url", s.url)
                        put("enabled", s.enabled)
                        put("note", s.note)
                        put("group", s.group)
                        put("parentUrl", s.parentUrl)
                    }
                }
            }
            putJsonObject("activeRules") {
                put("drpy", DrpySourceManager.activeRuleName())
                put("py", PySourceManager.activeRuleName())
                put("php", PhpSourceManager.activeRuleName())
                put("web", WebSourceManager.activeSiteName())
            }
            put("follow", followSection)
            put("bookshelf", bookshelfSection)
            put("music", musicSection)
            put("live", live)
            put("history", encodeList(HistoryStore.getHistory(context), HistoryEntry.serializer()))
            put("bookSources", bookSourcesSection)
            put("musicSources", musicSourcesSection)
            put("ruleFiles", ruleFilesSection)
        }
        return ExportResult.Ok(root.toString())
    }

    /** 把一列可序列化条目编码为 JSON 数组（编码异常直接抛出，由导出调用方兜底）。 */
    private fun <T> encodeList(
        list: List<T>,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): kotlinx.serialization.json.JsonElement {
        return Json.parseToJsonElement(
            Json.encodeToString(ListSerializer(serializer), list),
        )
    }

    private suspend fun buildBookshelfSection(context: Context): kotlinx.serialization.json.JsonElement {
        val novels = NovelStore.list(context)
        val entries = novels.map { novel ->
            BookshelfNovelEntry(novel, NovelStore.chapters(context, novel.id))
        }
        val progress = novels.mapNotNull { ReadingProgressStore.load(context, it.id) }
        val bookmarks = novels.mapNotNull { novel ->
            val items = BookmarkStore.list(context, novel.id)
            if (items.isEmpty()) null
            else BookmarkEntry(novel.id, items.sortedBy { it.createdAt })
        }
        return buildJsonObject {
            put("novels", encodeList(entries, BookshelfNovelEntry.serializer()))
            put("groups", encodeList(BookshelfGroupStore.list(context), BookGroup.serializer()))
            put("progress", encodeList(progress, ReadingProgress.serializer()))
            put("bookmarks", encodeList(bookmarks, BookmarkEntry.serializer()))
        }
    }

    private suspend fun buildMusicSection(context: Context): kotlinx.serialization.json.JsonElement {
        return buildJsonObject {
            put("local", encodeList(MusicStore.localList(context), MusicApi.Track.serializer()))
            put("fav", encodeList(MusicStore.favList(context), MusicApi.Track.serializer()))
            put("playlists", encodeList(MusicStore.playlistList(context), MusicPlaylist.serializer()))
        }
    }

    /**
     * v2 书源段：book_sources.json 文件内容原样进备份（无文件=空列表）。
     * 文件存在但不是 JSON 数组时返回 null，导出直接拒绝——坏书源绝不允许
     * 再覆盖云端好备份（与 ApiSourceStore.isCorrupt 的导出口径一致）。
     */
    private fun buildBookSourcesSection(context: Context): kotlinx.serialization.json.JsonElement? {
        val f = File(context.filesDir, "book_sources.json")
        if (!f.isFile) return Json.parseToJsonElement("[]")
        val text = runCatching { f.readText() }.getOrNull()
            ?: run { Log.w(TAG, "书源文件读取失败，拒绝导出"); return null }
        return runCatching {
            Json.parseToJsonElement(text).jsonArray
            Json.parseToJsonElement(text)
        }.onFailure { Log.w(TAG, "书源文件读取失败，拒绝导出", it) }.getOrNull()
    }

    /**
     * v2 音乐源段：注册表 + lx_sources 下本地 JS 文件内容（只备 file:// 路径
     * 不够，换机后路径指向的文件不存在，注册表就悬空；内容进备份才能真恢复）。
     * 单个脚本超 2MB 时跳过该文件并记日志（LX 脚本正常只有几十 KB）。
     */
    private suspend fun buildMusicSourcesSection(context: Context): kotlinx.serialization.json.JsonElement {
        val registry = MusicSourceStore.list(context)
        val dir = File(context.filesDir, "lx_sources")
        val scripts = dir.listFiles { f -> f.isFile && f.name.endsWith(".js") }
            .orEmpty().sortedBy { it.name }.mapNotNull { f ->
                if (f.length() > MAX_RULE_FILE_BYTES) {
                    Log.w(TAG, "音乐本地脚本过大，导出跳过：${f.name}")
                    null
                } else {
                    runCatching { RuleFileEntry(f.name, f.readText()) }.getOrNull()
                }
            }
        return buildJsonObject {
            put("registry", encodeList(registry, MusicSource.serializer()))
            putJsonArray("scripts") {
                scripts.forEach { s ->
                    addJsonObject {
                        put("name", s.name)
                        put("content", s.content)
                    }
                }
            }
        }
    }

    /**
     * v2 规则文件段：当前规则文件内容（drpy/py/php 各一）+ 全部网页源文件。
     * 文件名只取 basename，内容超 2MB 或读不到时该项记 null（网页源单文件
     * 跳过）；恢复时先写文件再切 active，名字与文件是同一个备份能力。
     */
    private fun buildRuleFilesSection(context: Context): kotlinx.serialization.json.JsonElement {
        fun readRule(dir: File, name: String?, ext: String): kotlinx.serialization.json.JsonElement {
            val entry = name?.takeIf { validRuleFileName(it, ext) }?.let { n ->
                val f = File(dir, n)
                if (f.isFile && f.length() <= MAX_RULE_FILE_BYTES) {
                    runCatching { RuleFileEntry(n, f.readText()) }.getOrNull()
                } else {
                    if (f.isFile) Log.w(TAG, "规则文件过大，导出跳过：$n")
                    null
                }
            }
            return if (entry == null) JsonNull else buildJsonObject {
                put("name", entry.name)
                put("content", entry.content)
            }
        }
        val webDir = StoragePaths.webDir(context)
        val webFiles = WebSourceManager.listSites().mapNotNull { site ->
            val f = File(webDir, site.fileName)
            if (!f.isFile || f.length() > MAX_RULE_FILE_BYTES) {
                if (f.isFile) Log.w(TAG, "网页源文件过大，导出跳过：${site.fileName}")
                null
            } else {
                runCatching { RuleFileEntry(site.fileName, f.readText()) }.getOrNull()
            }
        }
        return buildJsonObject {
            put("drpy", readRule(StoragePaths.jsDir(context), DrpySourceManager.activeRuleName(), ".js"))
            put("py", readRule(StoragePaths.pyDir(context), PySourceManager.activeRuleName(), ".py"))
            put("php", readRule(PhpSourceManager.rulesDir(context), PhpSourceManager.activeRuleName(), ".php"))
            putJsonArray("web") {
                webFiles.forEach { s ->
                    addJsonObject {
                        put("name", s.name)
                        put("content", s.content)
                    }
                }
            }
        }
    }

    /** 规则/脚本文件名校验（纯函数，可单测）：只要 basename + 指定后缀，拒绝路径穿越。 */
    internal fun validRuleFileName(name: String, ext: String): Boolean {
        if (name.isBlank() || name == "." || name == "..") return false
        if (name.contains('/') || name.contains('\\')) return false
        return name.endsWith(ext, ignoreCase = true)
    }

    /** 网页源文件名校验（纯函数，可单测）：basename + .json/.html/.htm。 */
    internal fun validWebFileName(name: String): Boolean {
        if (name.isBlank() || name == "." || name == "..") return false
        if (name.contains('/') || name.contains('\\')) return false
        val lower = name.lowercase()
        return lower.endsWith(".json") || lower.endsWith(".html") || lower.endsWith(".htm")
    }

    /**
     * 从 JSON 恢复：覆盖写入接口站源文件 + 切换 drpy/py 的 active 规则
     * + 恢复备份里带有的追剧、书架、音乐、直播段。
     * 规则文件在磁盘上不存在时跳过（不报错，Manager 本来也会拒绝不存在的规则）。
     *
     * v0.170.0 恢复链（四段，缺一段都不算修好）：
     * 1. 全部校验先行——版本、apiSources 键存在且为数组、每条订阅都有非空 name/url、
     *    activeRules 格式合法；任何一项不过直接拒绝，当前数据一动不动。
     *    老版本合法备份（version=1、条目含 name/url，note/group/parentUrl 可缺）照常恢复。
     * 2. 快照——恢复前先存当前订阅文件字节 + drpy/py 当前规则。
     * 3. 写盘后回读校验——原子写盘返回失败、或回读条数对不上，都算失败。
     * 4. 回滚——写盘或切规则任何一步失败，文件与规则双双还原，再返回失败原因。
     * v0.186.0：上述口径原样扩到四个新段——新段先全部校验（缺关键字段
     * 整单拒绝，当前数据一动不动），写盘后回读比对，任一段失败则主段
     * 与各段一并回滚；老备份无新段时只恢复主段。
     * v1.3.33：完整备份升 version 2——导出写 version 2，导入继续接受
     * version 1（缺新段跳过、按旧逻辑恢复），version 2 才解析新增段
     * （bookSources / musicSources / ruleFiles）；新段格式不对整单拒绝，
     * 当前数据一动不动；规则文件先写盘再切 active（名字与文件同能力）。
     */
    suspend fun importJson(context: Context, json: String, writeSnapshot: Boolean = true): RestoreResult {
        val root = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "备份文件不是有效 JSON", e)
            return RestoreResult.Failed("文件不是有效的 JSON")
        }
        val version = root["version"]?.jsonPrimitive?.intOrNull
        if (!isSupportedBackupVersion(version)) {
            return RestoreResult.Failed("备份版本不匹配（需要 version=1 或 2，当前备份 version=$version）")
        }
        val isV2 = version == BACKUP_VERSION
        // ---- 1. 全部校验先行 ----
        val apiEl = root["apiSources"]
            ?: return RestoreResult.Failed("备份缺少 apiSources，拒绝恢复（当前数据未动）")
        val arr = try {
            apiEl.jsonArray
        } catch (e: Exception) {
            return RestoreResult.Failed("备份 apiSources 不是数组，拒绝恢复（当前数据未动）")
        }
        val apiSources = ArrayList<ApiSource>()
        var dropped = 0
        for (el in arr) {
            val o = try {
                el.jsonObject
            } catch (e: Exception) {
                dropped++
                continue
            }
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val url = o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (name == null || url == null) {
                dropped++
                continue
            }
            val enabled = o["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
            val note = o["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val group = o["group"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val parentUrl = o["parentUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
            apiSources.add(ApiSource(name, url, enabled, note, group, parentUrl))
        }
        if (dropped > 0) {
            return RestoreResult.Failed("备份有 $dropped 条订阅缺名称或地址，拒绝恢复（当前数据未动）")
        }
        val rules = try {
            root["activeRules"]?.jsonObject
        } catch (e: Exception) {
            return RestoreResult.Failed("备份 activeRules 格式不对，拒绝恢复（当前数据未动）")
        }
        val drpyName = try {
            rules?.strictString("drpy")
        } catch (e: Exception) {
            return RestoreResult.Failed("备份 activeRules.drpy 格式不对，拒绝恢复（当前数据未动）")
        }
        val pyName = try {
            rules?.strictString("py")
        } catch (e: Exception) {
            return RestoreResult.Failed("备份 activeRules.py 格式不对，拒绝恢复（当前数据未动）")
        }
        val phpName = try {
            rules?.strictString("php")
        } catch (e: Exception) {
            return RestoreResult.Failed("备份 activeRules.php 格式不对，拒绝恢复（当前数据未动）")
        }
        val webName = try {
            rules?.strictString("web")
        } catch (e: Exception) {
            return RestoreResult.Failed("备份 activeRules.web 格式不对，拒绝恢复（当前数据未动）")
        }
        // ---- 1b. 新段校验先行（缺段=老备份，跳过；有段但格式/字段不对=整单拒绝） ----
        // parse* 只在段存在时调用，返回 null 即格式或关键字段不对。
        val followEl = root["follow"]
        val followItems: List<FollowItem>? = if (followEl == null) null else {
            parseFollowSection(followEl)
                ?: return RestoreResult.Failed("备份追剧段格式不对，拒绝恢复（当前数据未动）")
        }
        val bookshelfEl = root["bookshelf"]
        val bookshelf: BookshelfParsed? = if (bookshelfEl == null) null else {
            parseBookshelfSection(bookshelfEl)
                ?: return RestoreResult.Failed("备份书架段格式不对，拒绝恢复（当前数据未动）")
        }
        val musicEl = root["music"]
        val music: MusicParsed? = if (musicEl == null) null else {
            parseMusicSection(musicEl)
                ?: return RestoreResult.Failed("备份音乐段格式不对，拒绝恢复（当前数据未动）")
        }
        val liveEl = root["live"]
        val live: LiveParsed? = if (liveEl == null) null else {
            parseLiveSection(liveEl)
                ?: return RestoreResult.Failed("备份直播段格式不对，拒绝恢复（当前数据未动）")
        }
        val historyEl = root["history"]
        val historyItems: List<HistoryEntry>? = if (historyEl == null) null else {
            parseHistorySection(historyEl)
                ?: return RestoreResult.Failed("备份播放历史段格式不对，拒绝恢复（当前数据未动）")
        }
        // ---- 1c. v2 新段校验先行（version 1 的备份无新段，直接跳过；
        // v2 有段但格式/字段不对=整单拒绝，当前数据一动不动） ----
        val bookSources: BookSourcesParsed? = if (!isV2) null else {
            root["bookSources"]?.let { el ->
                parseBookSourcesSection(el)
                    ?: return RestoreResult.Failed("备份书源段格式不对，拒绝恢复（当前数据未动）")
            }
        }
        val musicSources: MusicSourcesParsed? = if (!isV2) null else {
            root["musicSources"]?.let { el ->
                parseMusicSourcesSection(context.filesDir, el)
                    ?: return RestoreResult.Failed("备份音乐源段格式不对，拒绝恢复（当前数据未动）")
            }
        }
        val ruleFiles: RuleFilesParsed? = if (!isV2) null else {
            root["ruleFiles"]?.let { el ->
                parseRuleFilesSection(el)
                    ?: return RestoreResult.Failed("备份规则文件段格式不对，拒绝恢复（当前数据未动）")
            }
        }
        // ---- 2. 快照当前状态（回滚用；只快照备份里带有的段） ----
        DrpySourceManager.init(context)
        PySourceManager.init(context)
        PhpSourceManager.init(context)
        WebSourceManager.init(context)
        val prevBytes = ApiSourceStore.readRawBytes(context)
        val prevDrpy = DrpySourceManager.activeRuleName()
        val prevPy = PySourceManager.activeRuleName()
        val prevPhp = PhpSourceManager.activeRuleName()
        val prevWeb = WebSourceManager.activeSiteName()
        val prevFollow = if (followItems != null) FollowStore.readSync(context) else null
        val prevBookshelf = if (bookshelf != null) snapshotBookshelf(context) else null
        val prevMusic = if (music != null) snapshotMusic(context) else null
        val prevLiveRaw = if (live != null) readLivePrefsRaw(context) else null
        val prevHistory = if (historyItems != null) HistoryStore.getHistory(context) else null
        val prevBookRaw = if (bookSources != null) readRawOrNull(File(context.filesDir, "book_sources.json")) else null
        val prevMusicRaw = if (musicSources != null) readRawOrNull(File(context.filesDir, "music_sources.json")) else null
        // v2 规则文件与音乐脚本：只快照本次会写到的目标文件（原内容或“原本不存在”）。
        val prevRuleFileBytes: Map<File, ByteArray?> = if (ruleFiles != null) {
            snapshotFiles(ruleFileTargets(context, ruleFiles))
        } else {
            emptyMap()
        }
        val prevScriptBytes: Map<File, ByteArray?> = if (musicSources != null) {
            snapshotFiles(musicSources.scripts.map { File(File(context.filesDir, "lx_sources"), it.name) })
        } else {
            emptyMap()
        }
        // ---- 2b. 磁盘快照（v0.216.0 C15）：内存快照中途被杀就没了。
        // 动笔前先把当前完整备份落盘；成功后删，失败/被杀则留着可导回。
        // v0.246.0：快照写失败直接中止恢复——原来只记日志继续恢复，
        // 恢复中途再异常就没快照可回滚；且快照都写不出的盘，恢复写盘也大概率失败。
        if (writeSnapshot) {
            if (!writePreRestoreSnapshot(context, prevBytes, prevDrpy, prevPy, prevPhp, prevWeb)) {
                return RestoreResult.Failed("恢复前安全快照写入失败，当前数据未动，请检查存储空间后重试")
            }
        }
        // v0.271.0（T25）：回滚里的 Drpy 恢复走 suspend 版，rollback 本身改 suspend。
        suspend fun rollback(): Boolean {
            var ok = ApiSourceStore.restoreRaw(context, prevBytes)
            runCatching { DrpySourceManager.setActiveRuleAsync(prevDrpy) }.onFailure { ok = false }
            runCatching { PySourceManager.setActiveRule(prevPy) }.onFailure { ok = false }
            runCatching { PhpSourceManager.setActiveRule(prevPhp) }.onFailure { ok = false }
            runCatching { WebSourceManager.setActiveSite(prevWeb) }.onFailure { ok = false }
            // v2：规则文件、音乐脚本与书源/音源注册表原样滚回（文件不存在过=删掉）。
            runCatching { restoreFiles(prevRuleFileBytes) }.onFailure { ok = false }
            runCatching { restoreFiles(prevScriptBytes) }.onFailure { ok = false }
            runCatching {
                if (bookSources != null) {
                    if (prevBookRaw == null) File(context.filesDir, "book_sources.json").delete()
                    else AtomicFile.writeText(File(context.filesDir, "book_sources.json"), prevBookRaw.toString(Charsets.UTF_8))
                    BookSourceManager.load(context)
                }
            }.onFailure { ok = false }
            runCatching {
                if (musicSources != null) {
                    if (prevMusicRaw == null) File(context.filesDir, "music_sources.json").delete()
                    else AtomicFile.writeText(File(context.filesDir, "music_sources.json"), prevMusicRaw.toString(Charsets.UTF_8))
                }
            }.onFailure { ok = false }
            return ok
        }

        // ---- 3. 写盘 + 回读校验 ----
        if (!ApiSourceStore.save(context, apiSources)) {
            return RestoreResult.Failed("写入订阅文件失败，当前数据未动")
        }
        val writtenOk = ApiSourceStore.readRawBytes(context)?.let { bytes ->
            runCatching {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray.size == apiSources.size
            }.getOrDefault(false)
        } ?: false
        if (!writtenOk) {
            rollback()
            return RestoreResult.Failed("写入后校验失败，已还原")
        }
        // ---- 4. 先写规则文件，再切规则（失败则连文件带规则一起还原） ----
        // v2：名字与文件是同一个备份能力——规则文件不先落盘，换机恢复时
        // active 名字指着不存在的文件，切源会直接失败。
        if (ruleFiles != null && !restoreRuleFiles(context, ruleFiles)) {
            rollback()
            return RestoreResult.Failed("规则文件段写入失败，已还原")
        }
        try {
            drpyName?.let { name ->
                if (File(StoragePaths.jsDir(context), name).isFile) {
                    DrpySourceManager.setActiveRuleAsync(name)
                }
            }
            pyName?.let { name ->
                if (File(StoragePaths.pyDir(context), name).isFile) {
                    PySourceManager.setActiveRule(name)
                }
            }
            phpName?.let { name ->
                if (File(PhpSourceManager.rulesDir(context), name).isFile) {
                    if (!PhpSourceManager.setActiveRule(name)) {
                        Log.w(TAG, "切换 PHP 当前规则失败：$name")
                    }
                }
            }
            webName?.let { name ->
                if (WebSourceManager.findSite(name) != null) {
                    WebSourceManager.setActiveSite(name)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "切换当前规则失败，已还原", e)
            rollback()
            return RestoreResult.Failed("切换当前规则失败，已还原：${e.message}")
        }
        // ---- 5. 新段写盘 + 回读比对（任一段失败则主段与已写段一并还原；
        // 未动过的段不重写，避免公开 API 恢复重算时间戳误伤原数据） ----
        var extraFailed = ""
        var wroteFollow = false
        var wroteBookshelf = false
        var wroteMusic = false
        var wroteLive = false
        var wroteHistory = false
        if (followItems != null) {
            if (restoreFollow(context, followItems)) wroteFollow = true
            else extraFailed = "追剧段写入失败"
        }
        if (extraFailed.isEmpty() && bookshelf != null) {
            if (restoreBookshelf(context, bookshelf)) wroteBookshelf = true
            else extraFailed = "书架段写入失败"
        }
        if (extraFailed.isEmpty() && music != null) {
            if (restoreMusic(context, music)) wroteMusic = true
            else extraFailed = "音乐段写入失败"
        }
        if (extraFailed.isEmpty() && live != null) {
            if (restoreLive(context, live)) wroteLive = true
            else extraFailed = "直播段写入失败"
        }
        // v1.2.13：播放历史段——本地历史已损坏时恢复写盘会被 HistoryStore
        // 拒绝（防旧历史被顶掉），这里先判再写，失败原因写明白，不静默丢数据。
        if (extraFailed.isEmpty() && historyItems != null) {
            if (HistoryStore.isCorruptLoaded()) {
                extraFailed = "本地播放历史已损坏，恢复已中止（请先在历史页处理损坏数据）"
            } else if (restoreHistory(context, historyItems)) wroteHistory = true
            else extraFailed = "播放历史段写入失败"
        }
        // v2：书源段与音乐源段——写盘后才刷新内存态（BookSourceManager.load /
        // MusicSourceStore 下次 list 即读盘），失败同样进回滚链。
        if (extraFailed.isEmpty() && bookSources != null) {
            if (!restoreBookSources(context, bookSources)) extraFailed = "书源段写入失败"
        }
        if (extraFailed.isEmpty() && musicSources != null) {
            if (!restoreMusicSources(context, musicSources)) extraFailed = "音乐源段写入失败"
        }
        if (extraFailed.isEmpty() && !verifyExtras(context, followItems, bookshelf, music, live, historyItems, bookSources, musicSources, ruleFiles)) {
            extraFailed = "写入后校验失败（回读与备份对不上）"
        }
        if (extraFailed.isNotEmpty()) {
            Log.w(TAG, "新段恢复失败，已还原：$extraFailed")
            rollback()
            // v0.247.0 第 2 轮：回滚各段失败不再静默吞掉——原来 runCatching
            // 无 onFailure，哪段没滚回去查不到；现在记日志，调用方仍统一
            // 返回 Failed（用户不会看到假成功），日志用于定位残留段。
            runCatching { if (wroteFollow && prevFollow != null) restoreFollow(context, prevFollow) }
                .onFailure { Log.w(TAG, "回滚追剧段失败", it) }
            runCatching { if (wroteBookshelf && prevBookshelf != null) restoreBookshelf(context, prevBookshelf) }
                .onFailure { Log.w(TAG, "回滚书架段失败", it) }
            runCatching { if (wroteMusic && prevMusic != null) restoreMusic(context, prevMusic) }
                .onFailure { Log.w(TAG, "回滚音乐段失败", it) }
            runCatching {
                if (wroteLive && prevLiveRaw != null) {
                    writeLivePrefsRaw(context, prevLiveRaw)
                    val snap = decodeLivePrefs(prevLiveRaw)
                    if (snap != null) syncLiveFlow(context, snap.first, snap.second)
                }
            }.onFailure { Log.w(TAG, "回滚直播段失败", it) }
            runCatching { if (wroteHistory && prevHistory != null) restoreHistory(context, prevHistory) }
                .onFailure { Log.w(TAG, "回滚播放历史段失败", it) }
            // v2：书源/音乐源段与规则文件已在上面 rollback() 里按文件快照
            // 原样滚回（不存在过=删掉），这里不再重复写盘。
            return RestoreResult.Failed("$extraFailed，已还原")
        }
        // 恢复成功：删掉恢复前快照（已用不着；失败路径都留着快照可重试）
        runCatching { File(context.filesDir, PRE_RESTORE_SNAPSHOT).delete() }
        return RestoreResult.Ok
    }

    /**
     * v0.216.0（C15）：恢复前磁盘快照。完整导出能导就存完整备份；
     * 本地已损坏导不出时，至少把主段（订阅文件原样 + 当前规则）写成一张
     * 可导入的最小备份（其余段缺失按老备份口径只恢复主段）。
     */
    private suspend fun writePreRestoreSnapshot(
        context: Context,
        prevBytes: ByteArray?,
        prevDrpy: String?,
        prevPy: String?,
        prevPhp: String? = null,
        prevWeb: String? = null,
    ): Boolean {
        val json = when (val r = exportJson(context)) {
            is ExportResult.Ok -> r.json
            is ExportResult.Failed -> {
                Log.w(TAG, "恢复前快照：完整导出失败（${r.reason}），退回最小主段快照")
                val apiRaw = prevBytes?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: "[]"
                buildJsonObject {
                    put("version", BACKUP_VERSION)
                    put("apiSources", Json.parseToJsonElement(apiRaw))
                    putJsonObject("activeRules") {
                        put("drpy", prevDrpy)
                        put("py", prevPy)
                        put("php", prevPhp)
                        put("web", prevWeb)
                    }
                }.toString()
            }
        }
        // v0.246.0：返回写盘成败——原来失败只记日志、恢复照样继续，
        // 恢复中途再出异常或进程被杀就没快照可回滚。调用方失败时直接中止恢复。
        return runCatching { AtomicFile.writeText(File(context.filesDir, PRE_RESTORE_SNAPSHOT), json) }
            .onFailure { Log.w(TAG, "恢复前磁盘快照写入失败", it) }.getOrDefault(false)
    }

    // ---------- 新段：解析（只校验，不写盘；返回 null = 格式或关键字段不对） ----------

    private fun <T> decodeListOrNull(
        el: JsonElement,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): List<T>? {
        return runCatching {
            Json.decodeFromString(ListSerializer(serializer), el.toString())
        }.getOrNull()
    }

    private fun parseFollowSection(el: JsonElement): List<FollowItem>? {
        val list = decodeListOrNull(el, FollowItem.serializer()) ?: return null
        if (list.any { it.id.isBlank() || it.vodId.isBlank() || it.name.isBlank() }) return null
        if (list.map { it.id }.toSet().size != list.size) return null
        return list
    }

    private fun parseBookshelfSection(el: JsonElement): BookshelfParsed? {
        val obj = runCatching { el.jsonObject }.getOrNull() ?: return null
        val novelsEl = obj["novels"] ?: return null
        val groupsEl = obj["groups"] ?: return null
        val progressEl = obj["progress"] ?: return null
        val bookmarksEl = obj["bookmarks"] ?: return null
        val novels = decodeListOrNull(novelsEl, BookshelfNovelEntry.serializer()) ?: return null
        val groups = decodeListOrNull(groupsEl, BookGroup.serializer()) ?: return null
        val progress = decodeListOrNull(progressEl, ReadingProgress.serializer()) ?: return null
        val bookmarks = decodeListOrNull(bookmarksEl, BookmarkEntry.serializer()) ?: return null
        if (novels.any { it.novel.id.isBlank() || it.novel.title.isBlank() }) return null
        if (novels.map { it.novel.id }.toSet().size != novels.size) return null
        if (groups.any { it.id.isBlank() || it.name.isBlank() }) return null
        if (groups.map { it.id }.toSet().size != groups.size) return null
        if (progress.any { it.novelId.isBlank() }) return null
        if (bookmarks.any { it.novelId.isBlank() }) return null
        return BookshelfParsed(novels, groups, progress, bookmarks)
    }

    private fun parseMusicSection(el: JsonElement): MusicParsed? {
        val obj = runCatching { el.jsonObject }.getOrNull() ?: return null
        val localEl = obj["local"] ?: return null
        val favEl = obj["fav"] ?: return null
        val playlistsEl = obj["playlists"] ?: return null
        val local = decodeListOrNull(localEl, MusicApi.Track.serializer()) ?: return null
        val fav = decodeListOrNull(favEl, MusicApi.Track.serializer()) ?: return null
        val playlists = decodeListOrNull(playlistsEl, MusicPlaylist.serializer()) ?: return null
        if (local.any { it.id.isBlank() }) return null
        if (fav.any { it.id.isBlank() }) return null
        if (local.map { it.id }.toSet().size != local.size) return null
        if (fav.map { it.id }.toSet().size != fav.size) return null
        if (playlists.any { it.name.isBlank() }) return null
        if (playlists.map { it.name }.toSet().size != playlists.size) return null
        if (playlists.flatMap { it.tracks }.any { it.id.isBlank() }) return null
        return MusicParsed(local, fav, playlists)
    }

    private fun parseLiveSection(el: JsonElement): LiveParsed? {
        val obj = runCatching { el.jsonObject }.getOrNull() ?: return null
        val sourcesEl = obj["sources"] ?: return null
        val manualEl = obj["manual"] ?: return null
        val favsEl = obj["favs"] ?: return null
        val epgEl = obj["globalEpg"] ?: return null
        val sources = decodeListOrNull(sourcesEl, LiveSource.serializer()) ?: return null
        val manual = decodeListOrNull(manualEl, LiveChannel.serializer()) ?: return null
        val favs = runCatching {
            Json.decodeFromString(SetSerializer(String.serializer()), favsEl.toString())
        }.getOrNull() ?: return null
        val prim = epgEl as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (!prim.isString) return null
        if (sources.any { it.name.isBlank() || it.url.isBlank() }) return null
        if (sources.any { it.type != LiveSource.M3U_URL && it.type != LiveSource.LOCAL_FILE }) return null
        if (sources.map { it.url }.toSet().size != sources.size) return null
        if (manual.any { it.id.isBlank() || it.name.isBlank() || it.url.isBlank() }) return null
        if (favs.any { it.isBlank() }) return null
        return LiveParsed(sources, manual, favs, prim.content)
    }

    /**
     * v1.2.13：播放历史段校验——url 是去重键，必须非空且不重复；进度与总时长
     * 不能为负；条数不超过 HistoryStore 的上限（本版导出最多 50 条）。
     */
    private fun parseHistorySection(el: JsonElement): List<HistoryEntry>? {
        val list = decodeListOrNull(el, HistoryEntry.serializer()) ?: return null
        if (list.size > MAX_HISTORY_ENTRIES) return null
        if (list.any { it.url.isBlank() || it.positionMs < 0L || it.durationMs < 0L }) return null
        if (list.map { it.url }.toSet().size != list.size) return null
        return list
    }

    // ---------- v2 新段：解析（只校验，不写盘；返回 null = 格式或关键字段不对） ----------

    /** v2 书源段：必须是 JSON 数组，逐条能解析成 BookSource 且 key 非空不重复。 */
    internal fun parseBookSourcesSection(el: JsonElement): BookSourcesParsed? {
        val arr = runCatching { el.jsonArray }.getOrNull() ?: return null
        if (arr.size > MAX_BOOK_SOURCES) return null
        val sources = arr.map { item ->
            val text = item.toString()
            BookSource.fromJson(text)?.takeIf { it.key.isNotBlank() } ?: return null
        }
        if (sources.map { it.key }.toSet().size != sources.size) return null
        return BookSourcesParsed(el.toString(), sources)
    }

    /**
     * v2 音乐源段：{"registry": [...], "scripts": [{"name","content"}]}。
     * registry 逐条解码且 id 非空不重复；scripts 文件名只要 basename + .js，
     * 内容超 2MB 即整段拒绝；registry 里的 file:// 地址按本机路径重映射
     * （见 [remapMusicSourceUrls]），缺脚本文件的悬空项不拦路（与
     * MusicSourceStore 的 scriptMissing 口径一致，恢复后自检会标出来）。
     */
    internal fun parseMusicSourcesSection(filesDir: File, el: JsonElement): MusicSourcesParsed? {
        val obj = runCatching { el.jsonObject }.getOrNull() ?: return null
        val registryEl = obj["registry"] ?: return null
        val scriptsEl = obj["scripts"] ?: return null
        val registry = decodeListOrNull(registryEl, MusicSource.serializer()) ?: return null
        if (registry.size > MAX_MUSIC_SOURCES) return null
        if (registry.any { it.id.isBlank() }) return null
        if (registry.map { it.id }.toSet().size != registry.size) return null
        val scripts = parseRuleFileList(scriptsEl, ".js", MAX_MUSIC_SCRIPTS) ?: return null
        return MusicSourcesParsed(remapMusicSourceUrls(filesDir, registry, scripts), scripts)
    }

    /**
     * 音乐源注册表 file:// 地址重映射（纯函数，可单测）：备份里 file:// 指向
     * 的是原机 filesDir/lx_sources/<名>.js，恢复到本机时按本机 filesDir 重拼，
     * 只有 scripts 段里带了同名脚本的才改写，其余原样保留。
     */
    internal fun remapMusicSourceUrls(
        filesDir: File,
        registry: List<MusicSource>,
        scripts: List<RuleFileEntry>,
    ): List<MusicSource> {
        val names = scripts.map { it.name }.toSet()
        val dir = File(filesDir, "lx_sources")
        return registry.map { s ->
            if (!s.url.startsWith("file://")) return@map s
            val name = s.url.substringAfterLast('/')
            if (name !in names || !validRuleFileName(name, ".js")) s
            else s.copy(url = "file://" + File(dir, name).absolutePath)
        }
    }

    /** v2 规则文件段：drpy/py/php 各为单个文件或 null，web 为文件列表。 */
    internal fun parseRuleFilesSection(el: JsonElement): RuleFilesParsed? {
        val obj = runCatching { el.jsonObject }.getOrNull() ?: return null
        val (drpyOk, drpy) = parseRuleFileSlot(obj["drpy"], ".js")
        if (!drpyOk) return null
        val (pyOk, py) = parseRuleFileSlot(obj["py"], ".py")
        if (!pyOk) return null
        val (phpOk, php) = parseRuleFileSlot(obj["php"], ".php")
        if (!phpOk) return null
        val webEl = obj["web"] ?: return null
        val web = parseRuleFileList(webEl, null, MAX_WEB_FILES) ?: return null
        return RuleFilesParsed(drpy, py, php, web)
    }

    /**
     * 单个规则文件槽位解析：缺失/JsonNull=（true，无文件）；对象合法=
     * （true，文件）；对象不合法=（false，整段拒绝）。三态不能并成一个
     * 可空返回值，否则“无文件”与“坏文件”分不开。
     */
    private fun parseRuleFileSlot(el: JsonElement?, ext: String): Pair<Boolean, RuleFileEntry?> {
        if (el == null || el is JsonNull) return true to null
        val entry = parseRuleFileEntry(el, ext) ?: return false to null
        return true to entry
    }

    /** 单条 {"name","content"} 解析：名不合法、内容不是字符串或超 2MB 即 null。 */
    internal fun parseRuleFileEntry(el: JsonElement, ext: String?): RuleFileEntry? {
        val obj = runCatching { el.jsonObject }.getOrNull() ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return null
        if (ext != null) {
            if (!validRuleFileName(name, ext)) return null
        } else if (!validWebFileName(name)) return null
        if (content.toByteArray(Charsets.UTF_8).size > MAX_RULE_FILE_BYTES) return null
        return RuleFileEntry(name, content)
    }

    /** 文件对象数组解析：非数组、超条数、任一条不合法即 null。 */
    internal fun parseRuleFileList(el: JsonElement, ext: String?, max: Int): List<RuleFileEntry>? {
        val arr = runCatching { el.jsonArray }.getOrNull() ?: return null
        if (arr.size > max) return null
        val list = arr.map { parseRuleFileEntry(it, ext) ?: return null }
        if (list.map { it.name }.toSet().size != list.size) return null
        return list
    }

    // ---------- 新段：直播 prefs 直读直写 ----------
    // LiveStore 未公开手动频道与收藏的读接口，备份只能按它落盘的键直读；
    // 键名须与 LiveStore 保持一致，LiveStore 改键时这里必须同步改。

    private const val LIVE_PREFS = "live_prefs"
    private const val LIVE_KEY_SOURCES = "live_sources"
    private const val LIVE_KEY_MANUAL = "live_manual"
    private const val LIVE_KEY_FAVS = "live_favs"
    private const val LIVE_KEY_EPG = "live_global_epg"

    private fun readLivePrefsRaw(context: Context): Map<String, String?> {
        val prefs = context.getSharedPreferences(LIVE_PREFS, Context.MODE_PRIVATE)
        return mapOf(
            LIVE_KEY_SOURCES to prefs.getString(LIVE_KEY_SOURCES, null),
            LIVE_KEY_MANUAL to prefs.getString(LIVE_KEY_MANUAL, null),
            LIVE_KEY_FAVS to prefs.getString(LIVE_KEY_FAVS, null),
            LIVE_KEY_EPG to prefs.getString(LIVE_KEY_EPG, null),
        )
    }

    private fun writeLivePrefsRaw(context: Context, raw: Map<String, String?>): Boolean {
        val editor = context.getSharedPreferences(LIVE_PREFS, Context.MODE_PRIVATE).edit()
        raw.forEach { (key, value) ->
            if (value == null) editor.remove(key) else editor.putString(key, value)
        }
        return editor.commit()
    }

    /** 从 prefs 原串解出（订阅，全局节目单地址）；解不出返回 null。 */
    private fun decodeLivePrefs(raw: Map<String, String?>): Pair<List<LiveSource>, String>? {
        val sources = runCatching {
            Json.decodeFromString(
                ListSerializer(LiveSource.serializer()),
                raw[LIVE_KEY_SOURCES] ?: "[]",
            )
        }.getOrNull() ?: return null
        return sources to (raw[LIVE_KEY_EPG].orEmpty())
    }

    private fun readLiveSection(context: Context): JsonElement? {
        LiveStore.init(context)
        val raw = readLivePrefsRaw(context)
        val sources = runCatching {
            Json.decodeFromString(
                ListSerializer(LiveSource.serializer()),
                raw[LIVE_KEY_SOURCES] ?: "[]",
            )
        }.getOrNull() ?: return null
        val manual = runCatching {
            Json.decodeFromString(
                ListSerializer(LiveChannel.serializer()),
                raw[LIVE_KEY_MANUAL] ?: "[]",
            )
        }.getOrNull() ?: return null
        val favs = runCatching {
            Json.decodeFromString(
                SetSerializer(String.serializer()),
                raw[LIVE_KEY_FAVS] ?: "[]",
            )
        }.getOrNull() ?: return null
        return buildJsonObject {
            put("sources", encodeList(sources, LiveSource.serializer()))
            put("manual", encodeList(manual, LiveChannel.serializer()))
            put(
                "favs",
                Json.parseToJsonElement(
                    Json.encodeToString(SetSerializer(String.serializer()), favs),
                ),
            )
            put("globalEpg", raw[LIVE_KEY_EPG].orEmpty())
        }
    }

    /** 直播内存态同步：全局节目单地址与订阅列表走公开 API，手动频道与收藏
     * 下次直播刷新时从 prefs 读入（LiveStore 刷新即读手动频道与收藏）。 */
    private fun syncLiveFlow(context: Context, sources: List<LiveSource>, globalEpg: String) {
        LiveStore.init(context)
        LiveStore.setGlobalEpgUrl(context, globalEpg)
        LiveStore.sources.value.forEach { LiveStore.removeSource(context, it.url) }
        sources.forEach { LiveStore.addSource(context, it.name, it.type, it.url) }
    }

    // ---------- 新段：快照 ----------

    private suspend fun snapshotBookshelf(context: Context): BookshelfParsed {
        val novels = NovelStore.list(context)
        val entries = novels.map { BookshelfNovelEntry(it, NovelStore.chapters(context, it.id)) }
        val progress = novels.mapNotNull { ReadingProgressStore.load(context, it.id) }
        val bookmarks = novels.mapNotNull { novel ->
            val items = BookmarkStore.list(context, novel.id)
            if (items.isEmpty()) null
            else BookmarkEntry(novel.id, items.sortedBy { it.createdAt })
        }
        return BookshelfParsed(entries, BookshelfGroupStore.list(context), progress, bookmarks)
    }

    private suspend fun snapshotMusic(context: Context): MusicParsed {
        return MusicParsed(
            MusicStore.localList(context),
            MusicStore.favList(context),
            MusicStore.playlistList(context),
        )
    }

    // ---------- 新段：恢复（返回是否全部写成功；调用方负责回滚） ----------

    private suspend fun restoreFollow(context: Context, items: List<FollowItem>): Boolean {
        return try {
            FollowStore.readSync(context).forEach { FollowStore.unfollow(context, it.id) }
            var ok = true
            items.asReversed().forEach { if (!FollowStore.follow(context, it)) ok = false }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "追剧段恢复失败", e)
            false
        }
    }

    private suspend fun restoreBookshelf(context: Context, parsed: BookshelfParsed): Boolean {
        return try {
            // v0.247.0（DS29）：清旧书架的结果也要计入 ok——原来 removeNovel
            // 等 Boolean 返回值被当语句丢掉，删旧失败照样走恢复、最终报成功；
            // 现在任一清理失败都记失败，restore() 按“书架段写入失败”回滚。
            // 清理失败不停恢复（与后段新增失败同款“继续并记失败”，由回滚兜底）。
            // v0.247.0 第 2 轮修正：分组 delete 同样计入——第 1 轮注释误写它
            // 是 Unit，实测它经 writeFile 透传 Boolean（无显式返回类型、推断
            // 即 Boolean），调用方原先忽略，旧分组残留也会误报成功。
            var ok = true
            NovelStore.list(context).forEach { old ->
                if (!NovelStore.removeNovel(context, old.id)) ok = false
                if (!ReadingProgressStore.clear(context, old.id)) ok = false
                BookmarkStore.list(context, old.id).forEach { mark ->
                    if (!BookmarkStore.remove(context, old.id, mark.createdAt)) ok = false
                }
            }
            BookshelfGroupStore.list(context).forEach {
                if (!BookshelfGroupStore.delete(context, it.id)) ok = false
            }
            val restoredIds = parsed.novels.map { it.novel.id }.toSet()
            parsed.novels.asReversed().forEach { entry ->
                if (!NovelStore.addNovel(context, entry.novel, entry.chapters)) ok = false
                NovelStore.updateProgress(context, entry.novel.id, entry.novel.lastReadChapter)
            }
            parsed.progress.forEach { ReadingProgressStore.save(context, it) }
            parsed.bookmarks.forEach { group ->
                group.items.sortedBy { it.createdAt }.forEach { mark ->
                    BookmarkStore.add(context, group.novelId, mark)
                }
            }
            parsed.groups.forEach { group ->
                val created = BookshelfGroupStore.create(context, group.name)
                if (created.id.isBlank()) {
                    ok = false
                } else {
                    BookshelfGroupStore.addNovels(
                        context,
                        created.id,
                        group.novelIds.filter { it in restoredIds },
                    )
                }
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "书架段恢复失败", e)
            false
        }
    }

    private suspend fun restoreMusic(context: Context, parsed: MusicParsed): Boolean {
        return try {
            MusicStore.localList(context).forEach { MusicStore.removeLocal(context, it.id) }
            parsed.local.asReversed().forEach { MusicStore.addLocal(context, it) }
            val curFav = MusicStore.favList(context)
            val wantFavIds = parsed.fav.map { it.id }.toSet()
            curFav.filterNot { it.id in wantFavIds }.forEach { MusicStore.toggleFav(context, it) }
            parsed.fav.filterNot { track ->
                curFav.any { it.id == track.id }
            }.forEach { MusicStore.toggleFav(context, it) }
            MusicStore.playlistList(context).forEach { MusicStore.deletePlaylist(context, it.id) }
            var ok = true
            parsed.playlists.asReversed().forEach { playlist ->
                val created = MusicStore.createPlaylist(context, playlist.name)
                if (created == null) {
                    ok = false
                } else {
                    playlist.tracks.forEach { track ->
                        if (!MusicStore.addToPlaylist(context, created.id, track)) ok = false
                    }
                }
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "音乐段恢复失败", e)
            false
        }
    }

    private fun restoreLive(context: Context, parsed: LiveParsed): Boolean {
        return try {
            val raw = mapOf(
                LIVE_KEY_SOURCES to Json.encodeToString(
                    ListSerializer(LiveSource.serializer()),
                    parsed.sources,
                ),
                LIVE_KEY_MANUAL to Json.encodeToString(
                    ListSerializer(LiveChannel.serializer()),
                    parsed.manual,
                ),
                LIVE_KEY_FAVS to Json.encodeToString(
                    SetSerializer(String.serializer()),
                    parsed.favs,
                ),
                LIVE_KEY_EPG to parsed.globalEpg,
            )
            if (!writeLivePrefsRaw(context, raw)) return false
            syncLiveFlow(context, parsed.sources, parsed.globalEpg)
            true
        } catch (e: Exception) {
            Log.w(TAG, "直播段恢复失败", e)
            false
        }
    }

    /** v1.2.13：播放历史段恢复——整表写回（读坏时 restoreAll 拒绝并返回 false）。 */
    private fun restoreHistory(context: Context, items: List<HistoryEntry>): Boolean {
        return try {
            HistoryStore.restoreAll(context, items)
        } catch (e: Exception) {
            Log.w(TAG, "播放历史段恢复失败", e)
            false
        }
    }

    // ---------- v2 新段：恢复（返回是否全部写成功；调用方负责回滚） ----------

    /** 读文件原字节（不存在=null，供回滚快照用）。 */
    private fun readRawOrNull(f: File): ByteArray? =
        if (f.isFile) runCatching { f.readBytes() }.getOrNull() else null

    /** 快照一组目标文件：原内容或 null（原本不存在）；读失败时记 null 当不存在处理。 */
    private fun snapshotFiles(files: List<File>): Map<File, ByteArray?> =
        files.associateWith { readRawOrNull(it) }

    /** 按快照还原一组文件：原内容写回，原本不存在的删掉；任一失败返回 false。 */
    private fun restoreFiles(snapshot: Map<File, ByteArray?>): Boolean {
        var ok = true
        snapshot.forEach { (file, bytes) ->
            val done = runCatching {
                if (bytes == null) {
                    if (file.isFile) file.delete() else true
                } else {
                    file.parentFile?.mkdirs()
                    AtomicFile.writeText(file, bytes.toString(Charsets.UTF_8))
                }
            }.getOrDefault(false)
            if (!done) {
                Log.w(TAG, "回滚文件失败：${file.name}")
                ok = false
            }
        }
        return ok
    }

    /** ruleFiles 段对应的全部目标文件（写前快照、写后校验都按这份清单）。 */
    private fun ruleFileTargets(context: Context, parsed: RuleFilesParsed): List<File> {
        val out = mutableListOf<File>()
        parsed.drpy?.let { out += File(StoragePaths.jsDir(context), it.name) }
        parsed.py?.let { out += File(StoragePaths.pyDir(context), it.name) }
        parsed.php?.let { out += File(PhpSourceManager.rulesDir(context), it.name) }
        parsed.web.forEach { out += File(StoragePaths.webDir(context), it.name) }
        return out
    }

    /** v2 规则文件段恢复：内容写进各规则目录（PHP 写 t4_demo，网页源写 web 目录）。 */
    private fun restoreRuleFiles(context: Context, parsed: RuleFilesParsed): Boolean {
        return try {
            var ok = true
            fun write(dir: File, entry: RuleFileEntry) {
                dir.mkdirs()
                if (!AtomicFile.writeText(File(dir, entry.name), entry.content)) ok = false
            }
            parsed.drpy?.let { write(StoragePaths.jsDir(context), it) }
            parsed.py?.let { write(StoragePaths.pyDir(context), it) }
            parsed.php?.let { write(PhpSourceManager.rulesDir(context), it) }
            parsed.web.forEach { write(StoragePaths.webDir(context), it) }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "规则文件段恢复失败", e)
            false
        }
    }

    /** v2 书源段恢复：原样写回 book_sources.json，再重载内存表；失败返回 false。 */
    private suspend fun restoreBookSources(context: Context, parsed: BookSourcesParsed): Boolean {
        return try {
            val f = File(context.filesDir, "book_sources.json")
            if (!AtomicFile.writeText(f, parsed.rawJson)) return false
            BookSourceManager.load(context)
        } catch (e: Exception) {
            Log.w(TAG, "书源段恢复失败", e)
            false
        }
    }

    /** v2 音乐源段恢复：注册表写回 music_sources.json，脚本写进 lx_sources。 */
    private fun restoreMusicSources(context: Context, parsed: MusicSourcesParsed): Boolean {
        return try {
            var ok = true
            val registryJson = Json.encodeToString(
                ListSerializer(MusicSource.serializer()),
                parsed.registry,
            )
            if (!AtomicFile.writeText(File(context.filesDir, "music_sources.json"), registryJson)) {
                return false
            }
            val dir = File(context.filesDir, "lx_sources").also { it.mkdirs() }
            parsed.scripts.forEach { s ->
                if (!AtomicFile.writeText(File(dir, s.name), s.content)) ok = false
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "音乐源段恢复失败", e)
            false
        }
    }

    // ---------- 新段：回读比对 ----------

    /** v1.2.13：播放历史段上限，与 HistoryStore.MAX_SIZE 同值（本版导出最多 50 条）。 */
    private const val MAX_HISTORY_ENTRIES = 50

    private suspend fun verifyExtras(
        context: Context,
        followItems: List<FollowItem>?,
        bookshelf: BookshelfParsed?,
        music: MusicParsed?,
        live: LiveParsed?,
        history: List<HistoryEntry>?,
        bookSources: BookSourcesParsed? = null,
        musicSources: MusicSourcesParsed? = null,
        ruleFiles: RuleFilesParsed? = null,
    ): Boolean {
        if (followItems != null) {
            val gotFollow = FollowStore.readSync(context).associateBy { it.id }
            if (gotFollow.keys != followItems.map { it.id }.toSet()) {
                return false
            }
            // v0.217.0（F66）：原来只比 id 集合，集数／看到第几集写错
            // 位也照样报恢复成功；现在逐本比总集数与已看集数。
            followItems.forEach { want ->
                val got = gotFollow[want.id] ?: return false
                if (got.episodeCount != want.episodeCount ||
                    got.watchedEpisode != want.watchedEpisode
                ) {
                    return false
                }
            }
        }
        if (bookshelf != null) {
            if (NovelStore.list(context).map { it.id }.toSet() !=
                bookshelf.novels.map { it.novel.id }.toSet()
            ) {
                return false
            }
            // v0.217.0（F66）：原来分组只比数量，分组里装了谁根本没比；
            // 现在按分组 id 比名字与组内书 id 集合。
            val gotGroups = BookshelfGroupStore.list(context).associateBy { it.id }
            if (gotGroups.keys != bookshelf.groups.map { it.id }.toSet()) return false
            bookshelf.groups.forEach { want ->
                val got = gotGroups[want.id] ?: return false
                if (got.name != want.name || got.novelIds.toSet() != want.novelIds.toSet()) {
                    return false
                }
            }
            bookshelf.progress.forEach { want ->
                val got = ReadingProgressStore.load(context, want.novelId) ?: return false
                if (got.chapterIndex != want.chapterIndex || got.charOffset != want.charOffset) {
                    return false
                }
            }
        }
        if (music != null) {
            if (MusicStore.localList(context).map { it.id }.toSet() !=
                music.local.map { it.id }.toSet()
            ) {
                return false
            }
            if (MusicStore.favList(context).map { it.id }.toSet() !=
                music.fav.map { it.id }.toSet()
            ) {
                return false
            }
            // v0.217.0（F66）：原来歌单只比名字集合，歌单里的歌没比；
            // 现在按歌单名比组内曲目 id 集合。
            val gotPlaylists = MusicStore.playlistList(context).associateBy { it.name }
            if (gotPlaylists.keys != music.playlists.map { it.name }.toSet()) {
                return false
            }
            music.playlists.forEach { want ->
                val got = gotPlaylists[want.name] ?: return false
                if (got.tracks.map { it.id }.toSet() != want.tracks.map { it.id }.toSet()) {
                    return false
                }
            }
        }
        if (live != null) {
            val raw = readLivePrefsRaw(context)
            val sources = runCatching {
                Json.decodeFromString(
                    ListSerializer(LiveSource.serializer()),
                    raw[LIVE_KEY_SOURCES] ?: "[]",
                )
            }.getOrNull() ?: return false
            val manual = runCatching {
                Json.decodeFromString(
                    ListSerializer(LiveChannel.serializer()),
                    raw[LIVE_KEY_MANUAL] ?: "[]",
                )
            }.getOrNull() ?: return false
            val favs = runCatching {
                Json.decodeFromString(
                    SetSerializer(String.serializer()),
                    raw[LIVE_KEY_FAVS] ?: "[]",
                )
            }.getOrNull() ?: return false
            if (sources != live.sources) return false
            if (manual != live.manual) return false
            if (favs != live.favs) return false
            if (raw[LIVE_KEY_EPG].orEmpty() != live.globalEpg) return false
        }
        if (history != null) {
            val got = HistoryStore.getHistory(context)
            if (got.map { it.url } != history.map { it.url }) return false
            history.forEach { want ->
                val entry = got.firstOrNull { it.url == want.url } ?: return false
                if (entry.positionMs != want.positionMs || entry.durationMs != want.durationMs ||
                    entry.timestamp != want.timestamp
                ) {
                    return false
                }
            }
        }
        if (bookSources != null) {
            // 书源写盘后 BookSourceManager.load 已重载内存表，按 key 集合比对。
            if (BookSourceManager.sources.value.map { it.key }.toSet() !=
                bookSources.sources.map { it.key }.toSet()
            ) {
                return false
            }
        }
        if (musicSources != null) {
            val gotRegistry = runCatching {
                Json.decodeFromString(
                    ListSerializer(MusicSource.serializer()),
                    File(context.filesDir, "music_sources.json").readText(),
                )
            }.getOrNull() ?: return false
            if (gotRegistry != musicSources.registry) return false
            val dir = File(context.filesDir, "lx_sources")
            musicSources.scripts.forEach { want ->
                val got = runCatching { File(dir, want.name).readText() }.getOrNull()
                if (got != want.content) return false
            }
        }
        if (ruleFiles != null) {
            ruleFileTargets(context, ruleFiles).forEach { target ->
                val want = ruleFiles.drpy?.takeIf { target.name == it.name }
                    ?: ruleFiles.py?.takeIf { target.name == it.name }
                    ?: ruleFiles.php?.takeIf { target.name == it.name }
                    ?: ruleFiles.web.firstOrNull { it.name == target.name }
                    ?: return false
                val got = runCatching { target.readText() }.getOrNull()
                if (got != want.content) return false
            }
        }
        return true
    }

    /**
     * 取 JsonObject 里的字符串字段：缺失 / JsonNull → null；字符串 → 值；
     * 其它类型 → 抛错（恢复校验用，格式不对直接拒绝，不许静默跳过）。
     */
    private fun kotlinx.serialization.json.JsonObject.strictString(key: String): String? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        val prim = el as? kotlinx.serialization.json.JsonPrimitive
            ?: throw IllegalArgumentException("$key 不是字符串")
        if (!prim.isString) throw IllegalArgumentException("$key 不是字符串")
        return prim.content
    }
}
