package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import com.movieshell.data.model.Chapter
import com.movieshell.data.model.Novel
import com.movieshell.data.model.ReadingProgress
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/** 接口站源（订阅）条目快照；v0.112.0 加 note/group（订阅级备注/分组），缺字段的老数据读作空串 */
data class ApiSource(
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val note: String = "",
    val group: String = "",
    /** v0.122.0：多仓自动展开出的子仓记录其父仓 URL；空串=用户自加的顶层订阅 */
    val parentUrl: String = "",
)

/**
 * 接口站源最小持久化：filesDir/api_sources.json。
 *
 * v0.27.2：无预置默认源；文件不存在/解析失败一律返回空列表，由站源页空态引导添加。
 */
object ApiSourceStore {
    private const val TAG = "ApiSourceStore"
    private const val FILE_NAME = "api_sources.json"
    private const val PREFS = "api_source_store"
    private const val KEY_DEFAULTS_PURGED = "builtin_defaults_purged_v0273"

    /**
     * v0.27.2 及之前预置过的内置默认源（精确 name+url 配对才算，避免误删用户自己加的同名订阅）。
     * v0.27.3：老用户升级时一次性清掉，只做一次。
     */
    private val builtinDefaults = setOf(
        "量子资源" to "https://cj.lziapi.com/api.php/provide/vod/",
        "天空资源" to "https://api.tiankongapi.com/api.php/provide/vod/",
        "暴风资源" to "https://bfzyapi.com/api.php/provide/vod/",
    )

    /**
     * v0.27.2：不再预置默认源。新安装源列表初始为空（空态引导用户添加）；
     * 老用户已有 api_sources.json 的不受影响。
     * v0.170.0：文件存在但解析失败时保全原文件为 .corrupt.bak 再返回空列表——
     * 空列表只代表"本次读不到"，绝不能由下次写盘把旧数据悄悄清掉。
     */
    fun load(context: Context): List<ApiSource> {
        val list = parseFile(File(context.filesDir, FILE_NAME)) ?: return emptyList()
        return purgeBuiltinDefaultsOnce(context, list)
    }

    /** 文件存在但解析失败时为 true（导出/云同步用：拒绝用空表覆盖好备份）。 */
    fun isCorrupt(context: Context): Boolean {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return false
        return parseFile(file) == null
    }

    /** 当前文件的原始字节；文件不存在返回 null（回滚时删文件即还原）。 */
    fun readRawBytes(context: Context): ByteArray? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    /** 回滚用：bytes 为 null 删文件，否则原子写回。返回是否成功。 */
    fun restoreRaw(context: Context, bytes: ByteArray?): Boolean {
        val file = File(context.filesDir, FILE_NAME)
        if (bytes == null) return runCatching { if (file.isFile) file.delete() else true }.getOrDefault(false)
        return AtomicFile.writeText(file, bytes.toString(Charsets.UTF_8))
    }

    /** 解析文件；返回 null = 文件存在但解析失败（已保全原文件）。缺 name/url 的单条静默跳过（读侧容错）。 */
    private fun parseFile(file: File): List<ApiSource>? {
        if (!file.isFile) return emptyList()
        return try {
            file.readText().let(Json::parseToJsonElement).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val enabled = o["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: true
                val note = o["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val group = o["group"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val parentUrl = o["parentUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ApiSource(name, url, enabled, note, group, parentUrl)
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取 $FILE_NAME 失败，已保全原文件", e)
            AtomicFile.preserveCorrupt(file)
            null
        }
    }

    /**
     * v0.27.3：老用户升级一次性迁移——清掉 v0.27.2 及之前预置的内置默认源。
     * 只认精确的 name+url 配对，用户自己后加的订阅（哪怕同名不同 URL）不动。
     */
    private fun purgeBuiltinDefaultsOnce(context: Context, list: List<ApiSource>): List<ApiSource> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DEFAULTS_PURGED, false)) return list
        val kept = list.filterNot { (it.name to it.url) in builtinDefaults }
        if (kept.size != list.size) {
            save(context, kept)
            Log.i(TAG, "已清理内置默认源 ${list.size - kept.size} 个")
        }
        prefs.edit().putBoolean(KEY_DEFAULTS_PURGED, true).apply()
        return kept
    }

    /** v0.170.0：原子写盘，返回是否成功——写盘失败不许再对外报成功。 */
    fun save(context: Context, list: List<ApiSource>): Boolean {
        val arr = buildJsonArray {
            list.forEach { s ->
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
        val ok = AtomicFile.writeText(File(context.filesDir, FILE_NAME), arr.toString())
        if (!ok) Log.w(TAG, "写入 $FILE_NAME 失败")
        return ok
    }
}

/**
 * 订阅备份 / 恢复。
 *
 * 备份 JSON 格式：
 * { "version": 1,
 *   "apiSources": [ {"name","url","enabled"} ],
 *   "activeRules": {"drpy": "...", "py": "...", "php": null, "web": null},
 *   "follow": [ 追剧条目 ],
 *   "bookshelf": {"novels": [{"novel", "chapters"}], "groups": [...],
 *                "progress": [...], "bookmarks": [{"novelId", "items"}]},
 *   "music": {"local": [...], "fav": [...], "playlists": [...]},
 *   "live": {"sources": [...], "manual": [...], "favs": [...], "globalEpg": "..."} }
 *
 * - apiSources 来自 ApiSourceStore（持久化文件，不存在则用默认订阅）；
 * - activeRules.drpy/py 读各 Manager 现有 API（内存状态）；
 * - php/web 目前没有 active 概念，固定导出 null，恢复时忽略。
 * - v0.186.0：第 7 组扩展——追剧、书架、音乐、直播各占独立一段，
 *   不混进 apiSources；老备份（无这四段）照常导入，缺段即跳过。
 * - 本备份不是全量：插件、下载任务、播放历史、各项设置不在其中，
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

object BackupManager {
    private const val TAG = "BackupManager"

    /** 备份格式版本；恢复时校验，不一致直接拒绝并提示 */
    const val BACKUP_VERSION = 1

    /**
     * v0.216.0（C15）：恢复前磁盘快照文件名。恢复动笔前先把当前状态写到这里；
     * 恢复成功后删掉；中途被杀/失败则留着，设置页“备份与恢复”里可一键导回。
     */
    private const val PRE_RESTORE_SNAPSHOT = "backup_pre_restore.json"

    /** 是否有中断/失败后留下的恢复前快照，供设置页入口判断。 */
    fun hasPreRestoreSnapshot(context: Context): Boolean =
        File(context.filesDir, PRE_RESTORE_SNAPSHOT).isFile

    /**
     * 从恢复前快照导回（中断恢复的补救入口）。快照本身不再套一层新快照
     * （否则导回前先把好快照覆盖掉）；成功后删掉快照，失败则留着可重试。
     */
    suspend fun restorePreRestoreSnapshot(context: Context): RestoreResult {
        val f = File(context.filesDir, PRE_RESTORE_SNAPSHOT)
        if (!f.isFile) return RestoreResult.Failed("没有中断前的备份快照")
        val text = runCatching { f.readText() }.getOrNull()
            ?: return RestoreResult.Failed("快照读取失败")
        return when (val r = importJson(context, text, writeSnapshot = false)) {
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
                put("php", null as String?)
                put("web", null as String?)
            }
            put("follow", followSection)
            put("bookshelf", bookshelfSection)
            put("music", musicSection)
            put("live", live)
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
     */
    suspend fun importJson(context: Context, json: String, writeSnapshot: Boolean = true): RestoreResult {
        val root = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "备份文件不是有效 JSON", e)
            return RestoreResult.Failed("文件不是有效的 JSON")
        }
        val version = root["version"]?.jsonPrimitive?.intOrNull
        if (version != BACKUP_VERSION) {
            return RestoreResult.Failed("备份版本不匹配（需要 version=$BACKUP_VERSION）")
        }
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
        // ---- 2. 快照当前状态（回滚用；只快照备份里带有的段） ----
        DrpySourceManager.init(context)
        PySourceManager.init(context)
        val prevBytes = ApiSourceStore.readRawBytes(context)
        val prevDrpy = DrpySourceManager.activeRuleName()
        val prevPy = PySourceManager.activeRuleName()
        val prevFollow = if (followItems != null) FollowStore.readSync(context) else null
        val prevBookshelf = if (bookshelf != null) snapshotBookshelf(context) else null
        val prevMusic = if (music != null) snapshotMusic(context) else null
        val prevLiveRaw = if (live != null) readLivePrefsRaw(context) else null
        // ---- 2b. 磁盘快照（v0.216.0 C15）：内存快照中途被杀就没了。
        // 动笔前先把当前完整备份落盘；成功后删，失败/被杀则留着可导回。
        if (writeSnapshot) {
            writePreRestoreSnapshot(context, prevBytes, prevDrpy, prevPy)
        }
        fun rollback(): Boolean {
            var ok = ApiSourceStore.restoreRaw(context, prevBytes)
            runCatching { DrpySourceManager.setActiveRule(prevDrpy) }.onFailure { ok = false }
            runCatching { PySourceManager.setActiveRule(prevPy) }.onFailure { ok = false }
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
        // ---- 4. 切规则（失败则连文件带规则一起还原） ----
        try {
            drpyName?.let { name ->
                if (File(StoragePaths.jsDir(context), name).isFile) {
                    DrpySourceManager.setActiveRule(name)
                }
            }
            pyName?.let { name ->
                if (File(StoragePaths.pyDir(context), name).isFile) {
                    PySourceManager.setActiveRule(name)
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
        if (extraFailed.isEmpty() && !verifyExtras(context, followItems, bookshelf, music, live)) {
            extraFailed = "写入后校验失败（回读与备份对不上）"
        }
        if (extraFailed.isNotEmpty()) {
            Log.w(TAG, "新段恢复失败，已还原：$extraFailed")
            rollback()
            runCatching { if (wroteFollow && prevFollow != null) restoreFollow(context, prevFollow) }
            runCatching { if (wroteBookshelf && prevBookshelf != null) restoreBookshelf(context, prevBookshelf) }
            runCatching { if (wroteMusic && prevMusic != null) restoreMusic(context, prevMusic) }
            runCatching {
                if (wroteLive && prevLiveRaw != null) {
                    writeLivePrefsRaw(context, prevLiveRaw)
                    val snap = decodeLivePrefs(prevLiveRaw)
                    if (snap != null) syncLiveFlow(context, snap.first, snap.second)
                }
            }
            return RestoreResult.Failed("$extraFailed，已还原")
        }
        // php / web：当前版本无 active 概念，恢复时忽略
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
    ) {
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
                        put("php", null as String?)
                        put("web", null as String?)
                    }
                }.toString()
            }
        }
        runCatching { AtomicFile.writeText(File(context.filesDir, PRE_RESTORE_SNAPSHOT), json) }
            .onFailure { Log.w(TAG, "恢复前磁盘快照写入失败", it) }
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
            NovelStore.list(context).forEach { old ->
                NovelStore.removeNovel(context, old.id)
                ReadingProgressStore.clear(context, old.id)
                BookmarkStore.list(context, old.id).forEach { mark ->
                    BookmarkStore.remove(context, old.id, mark.createdAt)
                }
            }
            BookshelfGroupStore.list(context).forEach { BookshelfGroupStore.delete(context, it.id) }
            val restoredIds = parsed.novels.map { it.novel.id }.toSet()
            var ok = true
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

    // ---------- 新段：回读比对 ----------

    private suspend fun verifyExtras(
        context: Context,
        followItems: List<FollowItem>?,
        bookshelf: BookshelfParsed?,
        music: MusicParsed?,
        live: LiveParsed?,
    ): Boolean {
        if (followItems != null) {
            if (FollowStore.readSync(context).map { it.id }.toSet() != followItems.map { it.id }.toSet()) {
                return false
            }
        }
        if (bookshelf != null) {
            if (NovelStore.list(context).map { it.id }.toSet() !=
                bookshelf.novels.map { it.novel.id }.toSet()
            ) {
                return false
            }
            if (BookshelfGroupStore.list(context).size != bookshelf.groups.size) return false
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
            if (MusicStore.playlistList(context).map { it.name }.toSet() !=
                music.playlists.map { it.name }.toSet()
            ) {
                return false
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
