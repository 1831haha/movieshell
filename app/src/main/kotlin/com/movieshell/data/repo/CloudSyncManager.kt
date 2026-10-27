package com.movieshell.data.repo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.51.0：订阅云同步（WebDAV）。
 * 复用 BackupManager.exportJson/importJson 的本地备份格式，
 * 云端存 movieshell_backup.json + 摘要 sidecar movieshell_backup.json.sha256。
 *
 * v0.186.0：第 7 组——
 * - 上传前本地算 SHA-256 摘要并随文件存为 sidecar，下载后比对；
 *   摘要对不上直接拒绝恢复（截断但合法的 JSON 进不了恢复链）；
 *   无 sidecar 的老备份仍可恢复，但记一条警告（见 restore）。
 * - 上传前比本地 last_sync_ms 与远端 mtime：远端更新时不静默覆盖，
 *   返回 Conflict，由设置页对话框请用户确认是否仍要覆盖。
 * - WebDAV 地址校验：只放行 https；http 仅在用户勾选风险确认且地址
 *   为局域网地址时放行，勾选状态持久化；非法地址不保存。
 * - 密码仍存普通 SharedPreferences（见下）：全仓无 EncryptedSharedPreferences
 *   依赖与用法，凭空加未验证依赖不在本组做，记为延期。
 */
object CloudSyncManager {
    private const val PREFS = "cloud_sync_prefs"
    private const val KEY_URL = "webdav_url"
    private const val KEY_USER = "webdav_user"
    private const val KEY_PASS = "webdav_pass"
    private const val KEY_ALLOW_LAN_HTTP = "webdav_allow_lan_http"
    private const val KEY_LAST = "last_sync_ms"
    private const val REMOTE_NAME = "movieshell_backup.json"
    private const val DIGEST_SUFFIX = ".sha256"

    // v0.186.0 延期：WebDAV 密码仍存普通 prefs。迁移到 EncryptedSharedPreferences
    // 需要先引入并验证 androidx.security 依赖，本组禁改 build.gradle.kts 且全仓
    // 无现成用法，故不凭空加依赖；密码明文风险由地址校验收敛（https，或
    // 局域网 http + 用户显式勾选），彻底解决等依赖落地后再迁移。
    data class Config(
        val url: String,
        val user: String,
        val pass: String,
        val allowLanHttp: Boolean = false,
    )

    sealed interface SyncResult {
        data object Ok : SyncResult
        data class Failed(val reason: String) : SyncResult
        data class Conflict(val reason: String) : SyncResult
    }

    fun getConfig(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            url = p.getString(KEY_URL, "").orEmpty(),
            user = p.getString(KEY_USER, "").orEmpty(),
            pass = p.getString(KEY_PASS, "").orEmpty(),
            allowLanHttp = p.getBoolean(KEY_ALLOW_LAN_HTTP, false),
        )
    }

    /**
     * 保存配置并持久化局域网 http 勾选状态；地址非法时不保存并返回 false。
     * 调用方须在 false 时提示用户，不得继续测试或同步。
     */
    fun saveConfig(
        context: Context,
        url: String,
        user: String,
        pass: String,
        allowLanHttp: Boolean,
    ): Boolean {
        if (validateUrl(url.trim(), allowLanHttp) != null) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_USER, user.trim())
            .putString(KEY_PASS, pass)
            .putBoolean(KEY_ALLOW_LAN_HTTP, allowLanHttp)
            .apply()
        return true
    }

    /** 地址校验：非法返回原因，合法返回 null。 */
    fun validateUrl(rawUrl: String, allowLanHttp: Boolean): String? {
        val url = rawUrl.trim()
        if (url.isBlank()) return "先填写服务器地址"
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return "地址须以 https:// 或 http:// 开头"
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "https" && scheme != "http") return "地址须以 https:// 或 http:// 开头"
        if (scheme == "https") return null
        if (!allowLanHttp) return "http 地址有明文传输风险：仅局域网地址可在勾选风险确认后使用"
        val host = url.substring(schemeEnd + 3)
            .substringBefore('/').substringBefore(':').lowercase()
        if (!isLanHost(host)) return "http 只允许局域网地址，公网地址请用 https"
        return null
    }

    private fun isLanHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local")) return true
        // v0.246.0：严格 IPv4 解析——原来 startsWith("10.") 会把 10.evil.com、
        // 192.168.evil.com 这类域名也当局域网放行，http 明文发到公网。
        // 要求恰好 4 段、每段 0–255 的纯数字，再按段判私有段。
        val parts = host.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it < 0 || it > 255 }) return false
        return when (nums[0]) {
            127 -> true
            10 -> true
            172 -> nums[1] in 16..31
            192 -> nums[1] == 168
            else -> false
        }
    }

    fun isConfigured(context: Context): Boolean = getConfig(context).url.isNotBlank()

    fun lastSyncText(context: Context): String {
        val ms = lastSyncMs(context)
        if (ms == 0L) return "从未同步"
        return "上次：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }

    private fun lastSyncMs(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L)
    }

    private fun markSynced(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST, System.currentTimeMillis()).apply()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 上传备份到云端；冲突时返回 Conflict 而不是静默覆盖云端。 */
    suspend fun backup(context: Context, force: Boolean = false): SyncResult =
        withContext(Dispatchers.IO) {
            val c = getConfig(context)
            if (c.url.isBlank()) return@withContext SyncResult.Failed("先配置 WebDAV 服务器地址")
            validateUrl(c.url, c.allowLanHttp)?.let { return@withContext SyncResult.Failed(it) }
            val json = try {
                when (val r = BackupManager.exportJson(context)) {
                    is BackupManager.ExportResult.Ok -> r.json
                    is BackupManager.ExportResult.Failed -> return@withContext SyncResult.Failed(r.reason)
                }
            } catch (e: Exception) {
                LogBus.w("CloudSync", "备份导出异常：${e.message}")
                return@withContext SyncResult.Failed("导出失败：${e.message}")
            }
            val bytes = json.toByteArray(Charsets.UTF_8)
            if (!force) {
                val remote = WebDavClient.propfind(c.url, c.user, c.pass, REMOTE_NAME)
                val last = lastSyncMs(context)
                if (remote != null && remote.exists && last > 0L && remote.mtimeMs > last) {
                    val remoteText = SimpleDateFormat(
                        "yyyy-MM-dd HH:mm",
                        Locale.getDefault(),
                    ).format(Date(remote.mtimeMs))
                    return@withContext SyncResult.Conflict(
                        "云端备份比本地上次同步更新（远端 $remoteText），" +
                            "直接上传会覆盖云端改动；请先从云端恢复，或确认后仍要覆盖",
                    )
                }
            }
            val err = WebDavClient.put(c.url, c.user, c.pass, REMOTE_NAME, bytes)
            if (err != null) {
                LogBus.w("CloudSync", "备份上传失败：$err")
                return@withContext SyncResult.Failed(err)
            }
            // v0.216.0（C14）：摘要失败不再留“新文件＋旧摘要”的锁死态——
            // 重试一次，仍失败就删掉刚上传的新备份（旧备份已被覆盖，删了不是
            // 回到旧版，而是让下次恢复明确报“云端没有备份”而不是摘要不符卡住，
            // 用户重传一次即恢复正常；不删的话新备份明明在却永远恢复不了）。
            val digestBytes = sha256Hex(bytes).toByteArray(Charsets.UTF_8)
            val digestErr = WebDavClient.put(
                c.url, c.user, c.pass, REMOTE_NAME + DIGEST_SUFFIX, digestBytes,
            ) ?: WebDavClient.put(
                c.url, c.user, c.pass, REMOTE_NAME + DIGEST_SUFFIX, digestBytes,
            )
            if (digestErr != null) {
                LogBus.w("CloudSync", "备份摘要上传失败（已重试）：$digestErr")
                val delErr = WebDavClient.delete(c.url, c.user, c.pass, REMOTE_NAME)
                if (delErr != null) LogBus.w("CloudSync", "清理新备份失败：$delErr")
                return@withContext SyncResult.Failed(
                    "备份摘要两次写入失败，已删除云端新备份避免新旧混搭，未标记同步；请检查网络后重新备份",
                )
            }
            markSynced(context)
            LogBus.i("CloudSync", "备份已上传到 WebDAV")
            SyncResult.Ok
        }

    /** 从云端下载并恢复：摘要对不上直接拒绝，不进恢复链。 */
    suspend fun restore(context: Context): SyncResult = withContext(Dispatchers.IO) {
        val c = getConfig(context)
        if (c.url.isBlank()) return@withContext SyncResult.Failed("先配置 WebDAV 服务器地址")
        validateUrl(c.url, c.allowLanHttp)?.let { return@withContext SyncResult.Failed(it) }
        val reason = arrayOfNulls<String>(1)
        val bytes = WebDavClient.get(c.url, c.user, c.pass, REMOTE_NAME, reason)
            ?: return@withContext SyncResult.Failed(reason[0] ?: "下载失败")
        when (val sidecar = WebDavClient.getSidecar(c.url, c.user, c.pass, REMOTE_NAME + DIGEST_SUFFIX)) {
            is WebDavClient.SidecarResult.Found -> {
                if (sidecar.digestHex != sha256Hex(bytes)) {
                    LogBus.w("CloudSync", "云端备份摘要不符，拒绝恢复")
                    // v0.216.0（C14）：原来只剩一句“校验失败”，用户不知道下一步干什么。
                    // 摘要不符仍是硬拒绝（不拿可能截断的备份恢复），但文案给两条可走的路。
                    return@withContext SyncResult.Failed(
                        "云端备份校验失败（摘要不符，可能已截断或损坏），未恢复；" +
                            "请删除云端备份后重新备份，或检查网络后重试恢复",
                    )
                }
            }
            is WebDavClient.SidecarResult.Failed -> {
                return@withContext SyncResult.Failed("无法校验云端备份摘要：${sidecar.reason}，未恢复")
            }
            is WebDavClient.SidecarResult.Missing -> {
                LogBus.w("CloudSync", "云端备份无摘要文件（老备份），跳过摘要比对")
            }
        }
        try {
            when (val r = BackupManager.importJson(context, bytes.toString(Charsets.UTF_8))) {
                is BackupManager.RestoreResult.Ok -> {
                    markSynced(context)
                    LogBus.i("CloudSync", "已从 WebDAV 恢复备份")
                    SyncResult.Ok
                }
                is BackupManager.RestoreResult.Failed -> {
                    LogBus.w("CloudSync", "云端备份恢复失败：${r.reason}")
                    SyncResult.Failed("恢复失败：${r.reason}")
                }
            }
        } catch (e: Exception) {
            LogBus.w("CloudSync", "云端备份恢复异常：${e.message}")
            SyncResult.Failed("恢复异常：${e.message}")
        }
    }

    /** 测试连接：成功返回 null，失败返回原因 */
    suspend fun test(context: Context): String? = withContext(Dispatchers.IO) {
        val c = getConfig(context)
        if (c.url.isBlank()) return@withContext "先填写服务器地址"
        validateUrl(c.url, c.allowLanHttp)?.let { return@withContext it }
        WebDavClient.test(c.url, c.user, c.pass)
    }
}
