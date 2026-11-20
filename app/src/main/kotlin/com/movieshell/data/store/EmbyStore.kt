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
    /** v1.2.14：备注（多服时列表里一眼区分，如“家里群晖 / 宿舍小主机”）。 */
    val remark: String = "",
    /**
     * v1.2.14：忽略证书验证（默认关）。只对这台服务器的 Emby 请求生效
     * （EmbyApi 里这台服务器走放行证书的专属 client），自签证书可用；
     * 开关旁已写明仅在信任该服务器时开启。
     */
    val ignoreCert: Boolean = false,
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

    /**
     * v1.2.14：历史续播用的 ignoreCert 判定（纯函数）：历史记录的 vodId 是
     * "emby:{serverId}:{itemId}"（MediaServerScreen.playItem 写盘），按 serverId
     * 找到对应服务器、取它的 ignoreCert；格式不对、服务器已删、开关关着一律 false。
     */
    fun ignoreCertForVodId(vodId: String, servers: List<EmbyServer>): Boolean {
        val parts = vodId.split(":", limit = 3)
        if (parts.size != 3 || parts[0] != "emby") return false
        return servers.firstOrNull { it.id == parts[1] }?.ignoreCert ?: false
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
