package com.movieshell.core.net

import android.content.Context
import com.movieshell.data.source.CmsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

/**
 * OpenList 客户端（v0.294.55：手机本机服务插件配套）。
 *
 * 只对 OpenList 的公开 HTTP 接口说话，不碰任何一个网盘的官方接口：
 * - POST /api/fs/list：列目录，body 为 path/password/page/per_page/refresh。
 * - POST /api/fs/get：取单个文件的 raw_url（播放直链），失败时用 sign 拼 /d 直链兜底。
 * - POST /api/auth/login：管理员登录取 token，失败不崩，只返回错误。
 *
 * 本机服务插件的地址固定为 http://127.0.0.1:5244（见插件包 server.json，
 * OpenList 的端口来自它自己的 config，不走服务插件的自动分配端口）。
 * token 的存法与现有 WebDAV 口径一致：App 私有 SharedPreferences 明文，
 * 与全仓无 EncryptedSharedPreferences 的现状相同，本版不另引加密库；
 * 用户在 OpenList 网页端改密码后，这里存的旧 token 失效会重新弹登录。
 */
object OpenListClient {

    const val LOCAL_BASE_URL = "http://127.0.0.1:5244"

    private const val PREFS = "openlist_prefs"
    private const val KEY_TOKEN = "token"
    private const val PER_PAGE = 100

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    data class OlEntry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val thumb: String,
    )

    data class OlPage(
        val content: List<OlEntry>,
        val total: Int,
    )

    val VIDEO_EXTS = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "m3u8", "ts",
        "flv", "wmv", "mpg", "mpeg", "rmvb", "3gp",
    )

    fun isVideoFile(name: String): Boolean {
        val ext = name.substringAfterLast(".", "").lowercase(Locale.US)
        return ext in VIDEO_EXTS
    }

    fun joinPath(parent: String, name: String): String {
        val p = parent.ifBlank { "/" }
        return if (p == "/") "/$name" else "$p/$name"
    }

    fun formatSize(size: Long): String {
        if (size < 0) return ""
        if (size < 1024) return "$size B"
        val kb = size / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun loadToken(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, "").orEmpty()
    }

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token).apply()
    }

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_TOKEN).apply()
    }

    private fun postJson(url: String, token: String, body: JSONObject): JSONObject {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_TYPE))
        if (token.isNotBlank()) {
            builder.header("Authorization", token)
        }
        val resp = CmsApi.defaultClient().newCall(builder.build()).execute()
        resp.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw IllegalStateException("HTTP ${it.code}")
            }
            return JSONObject(text)
        }
    }

    private fun checkCode(obj: JSONObject): String? {
        val code = obj.optInt("code", -1)
        if (code == 200) return null
        val msg = obj.optString("message").ifBlank { "OpenList 返回错误" }
        return "code=$code: $msg"
    }

    suspend fun login(baseUrl: String, username: String, password: String): Result<String> {
        return try {
            val obj = withContext(Dispatchers.IO) {
                val body = JSONObject()
                    .put("username", username)
                    .put("password", password)
                postJson("$baseUrl/api/auth/login", "", body)
            }
            val err = checkCode(obj)
            if (err != null) {
                Result.failure(IllegalStateException(err))
            } else {
                val token = obj.optJSONObject("data")?.optString("token").orEmpty()
                if (token.isBlank()) {
                    Result.failure(IllegalStateException("登录成功但未返回 token"))
                } else {
                    Result.success(token)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listDir(
        baseUrl: String,
        token: String,
        path: String,
        page: Int,
    ): Result<OlPage> {
        return try {
            val obj = withContext(Dispatchers.IO) {
                val body = JSONObject()
                    .put("path", path)
                    .put("password", "")
                    .put("page", page)
                    .put("per_page", PER_PAGE)
                    .put("refresh", false)
                postJson("$baseUrl/api/fs/list", token, body)
            }
            val err = checkCode(obj)
            if (err != null) {
                Result.failure(IllegalStateException(err))
            } else {
                val data = obj.optJSONObject("data")
                val total = data?.optInt("total", 0) ?: 0
                val arr = data?.optJSONArray("content")
                val list = mutableListOf<OlEntry>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val name = o.optString("name")
                        if (name.isBlank()) continue
                        list.add(
                            OlEntry(
                                name = name,
                                path = joinPath(path, name),
                                isDir = o.optBoolean("is_dir", false),
                                size = o.optLong("size", 0),
                                thumb = o.optString("thumb"),
                            ),
                        )
                    }
                }
                Result.success(OlPage(content = list, total = total))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRawUrl(baseUrl: String, token: String, path: String): Result<String> {
        return try {
            val obj = withContext(Dispatchers.IO) {
                val body = JSONObject()
                    .put("path", path)
                    .put("password", "")
                postJson("$baseUrl/api/fs/get", token, body)
            }
            val err = checkCode(obj)
            if (err != null) {
                Result.failure(IllegalStateException(err))
            } else {
                val data = obj.optJSONObject("data")
                val raw = data?.optString("raw_url").orEmpty()
                if (raw.isNotBlank()) {
                    Result.success(raw)
                } else {
                    val sign = data?.optString("sign").orEmpty()
                    if (sign.isNotBlank()) {
                        Result.success("$baseUrl/d$path?sign=$sign")
                    } else {
                        Result.failure(IllegalStateException("该文件拿不到播放地址"))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
