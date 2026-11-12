package com.movieshell.data.store

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * v1.1.31：Emby / Jellyfin 服务器配置持久化（本机 SharedPreferences + JSON，只存本机）。
 *
 * 一台服务器一条记录：地址 + 类型 + 登录方式 + 登录后拿到的 token/userId。
 * token 只作本机直连用（请求头 X-Emby-Token / 播放地址 api_key 参数），不上传、不分享。
 * 密码不存：用户名密码只在添加时用一次换 token，换到即丢弃。
 */
@Serializable
data class EmbyServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: String = "emby", // emby | jellyfin
    val baseUrl: String = "",
    val username: String = "",
    val authMode: String = "password", // password | apikey
    val token: String = "",
    val userId: String = "",
)

object EmbyStore {
    private const val PREFS = "emby_store"
    private const val KEY_SERVERS = "servers_json"
    private const val KEY_ACTIVE = "active_server_id"
    private const val KEY_DEVICE_ID = "device_id"

    private val json = Json { ignoreUnknownKeys = true }

    private val _servers = MutableStateFlow<List<EmbyServer>>(emptyList())
    val servers: StateFlow<List<EmbyServer>> = _servers

    private var loaded = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 设备 ID（Emby 登录头 DeviceId 用）：一台手机一个，首次生成后不变。 */
    fun deviceId(context: Context): String {
        val p = prefs(context)
        var id = p.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            p.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val raw = prefs(context).getString(KEY_SERVERS, null) ?: return
        _servers.value = runCatching { json.decodeFromString<List<EmbyServer>>(raw) }.getOrDefault(emptyList())
    }

    fun activeId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE, null)

    fun activeServer(context: Context): EmbyServer? {
        ensureLoaded(context)
        val list = _servers.value
        val id = activeId(context)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    fun setActive(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    private fun persist(context: Context, list: List<EmbyServer>) {
        _servers.value = list
        prefs(context).edit()
            .putString(KEY_SERVERS, json.encodeToString(ListSerializer(EmbyServer.serializer()), list))
            .apply()
    }

    fun add(context: Context, server: EmbyServer) {
        ensureLoaded(context)
        val list = _servers.value + server
        persist(context, list)
        if (_servers.value.size == 1) setActive(context, server.id)
    }

    fun update(context: Context, server: EmbyServer) {
        ensureLoaded(context)
        persist(context, _servers.value.map { if (it.id == server.id) server else it })
    }

    fun remove(context: Context, id: String) {
        ensureLoaded(context)
        persist(context, _servers.value.filter { it.id != id })
        if (activeId(context) == id) {
            val next = _servers.value.firstOrNull()?.id.orEmpty()
            prefs(context).edit().putString(KEY_ACTIVE, next).apply()
        }
    }
}
