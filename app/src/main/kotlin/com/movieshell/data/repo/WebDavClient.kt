package com.movieshell.data.repo

import com.movieshell.data.source.CmsApi
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * v0.51.0：极简 WebDAV 客户端（只做备份文件上传/下载，不做目录浏览）。
 * 用 OkHttp 直调 PUT/GET + Basic 认证，不引新依赖。
 * 坚果云/Alist/群晖等标准 WebDAV 服务都认这套。
 */
object WebDavClient {
    // v0.112.0 B1：只做小备份文件 PUT/GET + PROPFIND，无大文件上传，
    // 改用进程级共享单例（15s 超时 + callTimeout 60s 兜底 + 幂等重试）。
    private val client = CmsApi.defaultClient()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun authed(url: String, user: String, pass: String, builder: Request.Builder): Request =
        builder.url(url)
            .header("Authorization", Credentials.basic(user, pass))
            .build()

    /** 上传（覆盖）：成功返回 null，失败返回原因 */
    fun put(baseUrl: String, user: String, pass: String, name: String, bytes: ByteArray): String? {
        return try {
            val req = authed(
                join(baseUrl, name), user, pass,
                Request.Builder().put(bytes.toRequestBody(JSON)),
            )
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) null else "上传失败（HTTP ${resp.code}）"
            }
        } catch (e: Exception) {
            "上传失败：${e.message}"
        }
    }

    /** 删除：成功返回 null（不存在也算成功，调用方不因删不存在的文件报错） */
    fun delete(baseUrl: String, user: String, pass: String, name: String): String? {
        return try {
            val req = authed(
                join(baseUrl, name), user, pass,
                Request.Builder().delete(),
            )
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 404) null else "删除失败（HTTP ${resp.code}）"
            }
        } catch (e: Exception) {
            "删除失败：${e.message}"
        }
    }

    /** 下载：成功返回字节，失败返回 null（reason 写进 outReason[0]） */
    fun get(baseUrl: String, user: String, pass: String, name: String, outReason: Array<String?>): ByteArray? {
        return try {
            val req = authed(
                join(baseUrl, name), user, pass,
                Request.Builder().get(),
            )
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    outReason[0] = if (resp.code == 404) "云端没有备份文件" else "下载失败（HTTP ${resp.code}）"
                    return null
                }
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            outReason[0] = "下载失败：${e.message}"
            null
        }
    }

    /** 测试连接：对根目录发 PROPFIND，2xx 即通 */
    fun test(baseUrl: String, user: String, pass: String): String? {
        return try {
            val req = authed(
                baseUrl.trimEnd('/'), user, pass,
                Request.Builder().method("PROPFIND", null).header("Depth", "0"),
            )
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) null else "连接失败（HTTP ${resp.code}，检查地址/账号）"
            }
        } catch (e: Exception) {
            "连接失败：${e.message}"
        }
    }

    /** 远端文件信息：exists=false 表示远端无此文件；null 表示查不到
     * （服务端不支持 PROPFIND、日期解析失败等），调用方不得据此判定冲突。 */
    data class RemoteInfo(val exists: Boolean, val mtimeMs: Long)

    /** 对单个远端文件发 Depth 0 PROPFIND，取 getlastmodified 作远端 mtime。 */
    fun propfind(baseUrl: String, user: String, pass: String, name: String): RemoteInfo? {
        return try {
            val req = authed(
                join(baseUrl, name), user, pass,
                Request.Builder().method("PROPFIND", null).header("Depth", "0"),
            )
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) return RemoteInfo(false, 0L)
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val dateText = Regex(
                    "<(?:\\w+:)?getlastmodified>([^<]+)</(?:\\w+:)?getlastmodified>",
                ).find(body)?.groupValues?.get(1)?.trim() ?: return null
                val ms = runCatching {
                    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                        .parse(dateText)?.time
                }.getOrNull() ?: return null
                RemoteInfo(true, ms)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 摘要 sidecar 读取结果：缺文件（老备份无摘要）与读取失败要区分——
     * 失败时调用方必须拒绝恢复，不能当成“无摘要”放行。 */
    sealed interface SidecarResult {
        data class Found(val digestHex: String) : SidecarResult
        data object Missing : SidecarResult
        data class Failed(val reason: String) : SidecarResult
    }

    /** 读摘要 sidecar 文件内容（十六进制摘要文本）；404 视为老备份无摘要。 */
    fun getSidecar(baseUrl: String, user: String, pass: String, name: String): SidecarResult {
        val reason = arrayOfNulls<String>(1)
        val bytes = get(baseUrl, user, pass, name, reason)
        if (bytes != null) return SidecarResult.Found(bytes.toString(Charsets.UTF_8).trim().lowercase())
        return if (reason[0] == "云端没有备份文件") SidecarResult.Missing
        else SidecarResult.Failed(reason[0] ?: "读取失败")
    }

    private fun join(base: String, name: String) = base.trimEnd('/') + "/" + name.trimStart('/')
}
