package com.movieshell.data.repo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.51.0：订阅云同步（WebDAV）。
 * 复用 BackupManager.exportJson/importJson 的本地备份格式，
 * 云端只存一份 movieshell_backup.json（覆盖写，不做多版本/冲突合并——
 * 备份恢复本来就是整包覆盖，简单可预期）。
 */
object CloudSyncManager {
    private const val PREFS = "cloud_sync_prefs"
    private const val KEY_URL = "webdav_url"
    private const val KEY_USER = "webdav_user"
    private const val KEY_PASS = "webdav_pass"
    private const val KEY_LAST = "last_sync_ms"
    private const val REMOTE_NAME = "movieshell_backup.json"

    data class Config(val url: String, val user: String, val pass: String)

    fun getConfig(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            url = p.getString(KEY_URL, "").orEmpty(),
            user = p.getString(KEY_USER, "").orEmpty(),
            pass = p.getString(KEY_PASS, "").orEmpty(),
        )
    }

    fun saveConfig(context: Context, url: String, user: String, pass: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_USER, user.trim())
            .putString(KEY_PASS, pass)
            .apply()
    }

    fun isConfigured(context: Context): Boolean = getConfig(context).url.isNotBlank()

    fun lastSyncText(context: Context): String {
        val ms = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L)
        if (ms == 0L) return "从未同步"
        return "上次：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }

    private fun markSynced(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST, System.currentTimeMillis()).apply()
    }

    /** 上传备份到云端：成功返回 null，失败返回原因 */
    suspend fun backup(context: Context): String? = withContext(Dispatchers.IO) {
        val c = getConfig(context)
        if (c.url.isBlank()) return@withContext "先配置 WebDAV 服务器地址"
        val json = when (val r = BackupManager.exportJson(context)) {
            is BackupManager.ExportResult.Ok -> r.json
            is BackupManager.ExportResult.Failed -> return@withContext r.reason
        }
        val err = WebDavClient.put(c.url, c.user, c.pass, REMOTE_NAME, json.toByteArray(Charsets.UTF_8))
        if (err == null) {
            markSynced(context)
            LogBus.i("CloudSync", "备份已上传到 WebDAV")
        } else {
            LogBus.w("CloudSync", "备份上传失败：$err")
        }
        err
    }

    /** 从云端下载并恢复：成功返回 null，失败返回原因 */
    suspend fun restore(context: Context): String? = withContext(Dispatchers.IO) {
        val c = getConfig(context)
        if (c.url.isBlank()) return@withContext "先配置 WebDAV 服务器地址"
        val reason = arrayOfNulls<String>(1)
        val bytes = WebDavClient.get(c.url, c.user, c.pass, REMOTE_NAME, reason)
            ?: return@withContext (reason[0] ?: "下载失败")
        when (val r = BackupManager.importJson(context, bytes.toString(Charsets.UTF_8))) {
            is BackupManager.RestoreResult.Ok -> {
                markSynced(context)
                LogBus.i("CloudSync", "已从 WebDAV 恢复备份")
                null
            }
            is BackupManager.RestoreResult.Failed -> {
                LogBus.w("CloudSync", "云端备份恢复失败：${r.reason}")
                "恢复失败：${r.reason}"
            }
        }
    }

    /** 测试连接：成功返回 null，失败返回原因 */
    suspend fun test(context: Context): String? = withContext(Dispatchers.IO) {
        val c = getConfig(context)
        if (c.url.isBlank()) return@withContext "先填写服务器地址"
        WebDavClient.test(c.url, c.user, c.pass)
    }
}
