package com.movieshell.data.repo

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 可执行内容信任注册表（S2 修复的单一入口）。
 *
 * 威胁模型：公开目录 xiaying 下的文件可被拿到所有文件访问权限的
 * 第三方应用改写，App 绝不能把公开目录当执行基准。执行基准一律是
 * 私有 filesDir 下的可信副本，公开目录只做导入源。
 *
 * 规则：
 * - 首次见到某文件：拷入私有可信副本并记 SHA-256，之后以注册表为准。
 * - 再次见到公开文件内容与注册表一致：用私有副本执行，公开文件
 *   即使被外部改写也不会自动进入执行。
 * - 公开文件内容与注册表不一致，且本次不是用户显式重新信任
 *   （切源、重新安装、订阅刷新清缓存）：返回旧私有副本继续执行
 *   并记一条警告，绝不静默执行新内容；私有副本本身与注册表
 *   不一致时返回 null，拒绝执行。
 * - 显式重新信任（allowRetrust=true）只允许在用户主动操作时调：
 *   切源、保存服务定义、安装插件。后台加载、追剧检测、订阅详情
 *   一律用 allowRetrust=false。
 * - 本文件不删公开目录里的任何文件；旧私有副本在注册表被清掉
 *   前一直保留，用户更新规则要走显式重新信任那条路。
 * - 已知局限：本注册表在本次升级后首次见到旧文件时锚定当时的
 *   内容（升级前已被改写的文件无法回溯到安装时的原始摘要）；
 *   本次升级后新安装、切源、订阅刷新的内容锚定在安装/切源那一刻。
 */
object ExecutableTrust {
    private const val TAG = "ExecutableTrust"
    private const val REGISTRY_NAME = "exec_trust.json"
    private const val TRUSTED_DIR_NAME = "exec_trusted"

    private val lock = Any()

    fun sha256(file: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun registryFile(filesDir: File): File = File(filesDir, REGISTRY_NAME)

    // JVM 单测里 android.jar 的 org.json.JSONObject 是未 mock 的 stub（optString/put
    // 直接抛），改用 kotlinx.serialization，真机与单测同一套实现；落盘仍是扁平
    // {"key":"sha"} JSON，与旧 org.json 写出的格式兼容。
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun loadRegistry(filesDir: File): MutableMap<String, String> = synchronized(lock) {
        runCatching {
            val f = registryFile(filesDir)
            if (!f.isFile) return@runCatching mutableMapOf()
            val obj = json.parseToJsonElement(f.readText()).jsonObject
            obj.mapNotNull { (k, v) ->
                val s = v.jsonPrimitive.contentOrNull
                if (s.isNullOrBlank()) null else k to s
            }.toMap(mutableMapOf())
        }.getOrDefault(mutableMapOf())
    }

    private fun saveRegistry(filesDir: File, reg: Map<String, String>) {
        synchronized(lock) {
            runCatching {
                val f = registryFile(filesDir)
                f.parentFile?.mkdirs()
                val body = buildJsonObject {
                    reg.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                }.toString()
                f.writeText(body)
            }.onFailure { LogBus.w(TAG, "信任注册表写盘失败：${it.message}") }
        }
    }

    fun registeredSha(filesDir: File, key: String): String? {
        val reg = loadRegistry(filesDir)
        return reg[key]?.takeIf { it.isNotBlank() }
    }

    /** 安装、切源、保存服务定义时调用：把当前文件记为可信。 */
    fun record(filesDir: File, key: String, file: File): Boolean {
        val sha = sha256(file) ?: return false
        val reg = loadRegistry(filesDir)
        reg[key] = sha
        saveRegistry(filesDir, reg)
        return true
    }

    fun clear(filesDir: File, key: String) {
        val reg = loadRegistry(filesDir)
        if (reg.remove(key) != null) {
            saveRegistry(filesDir, reg)
        }
    }

    /**
     * 私有可信副本路径：key 里的斜杠与冒号转成下划线，防路径逃逸。
     * v0.294.1：中文等非 ASCII 名原来全被吃成 `_`（「py:红果短剧.py」「py:西瓜影视.py」都成 `py_.py`），
     * 多个规则共用一个副本、Python 桥按路径缓存 Spider → 换源后仍跑旧规则。
     * 现在走 SafeFileName：纯 ASCII 名结果不变，其余追加 key 摘要保证唯一，保留扩展名。
     */
    fun trustedPath(filesDir: File, key: String, sourceExt: String? = null): File =
        File(File(filesDir, TRUSTED_DIR_NAME), trustedName(key, sourceExt))

    /** 纯函数（可单测）：可信副本文件名。 */
    fun trustedName(key: String, sourceExt: String? = null): String {
        val base = SafeFileName.encode(
            key, Regex("[^A-Za-z0-9._-]+"), maxLen = 200, fallback = "_", keepExt = true,
        )
        // v0.294.22：订阅站信任 key 是 site:sub_xxx（没有 .py），按 key 算出来的副本也就没后缀；
        // Chaquopy 的 importlib.spec_from_file_location 对无后缀文件返回 None →
        // ImportError「无法加载规则文件」。公开源文件带 .py/.js 时强制保留。
        return forceScriptExt(base, sourceExt)
    }

    private fun legacyTrustedName(key: String): String =
        SafeFileName.legacyEncode(key, Regex("[^A-Za-z0-9._-]+"), maxLen = 200, fallback = "_", keepExt = true)

    /** 脚本后缀白名单：只有这些 loader 严格看后缀；其它扩展名不硬加，避免改名。 */
    private val SCRIPT_EXTS = setOf("py", "js")

    /** 纯函数（可单测）：名字尚无脚本后缀时补上。 */
    fun forceScriptExt(name: String, sourceExt: String?): String {
        val ext = sourceExt?.trim()?.trimStart('.')?.lowercase().orEmpty()
        if (ext.isEmpty() || ext !in SCRIPT_EXTS) return name
        if (name.endsWith(".$ext", ignoreCase = true)) return name
        return "$name.$ext"
    }

    /**
     * 取可信副本。返回 null 表示拒绝执行（公开文件已变且旧副本也
     * 不可信，或文件根本不存在）。调用方对 null 一律按执行失败处理，
     * 不得回退到公开文件。
     */
    fun trustedFile(
        filesDir: File,
        publicFile: File,
        key: String,
        allowRetrust: Boolean,
    ): File? {
        if (!publicFile.isFile) return null
        val publicSha = sha256(publicFile) ?: return null
        val registered = registeredSha(filesDir, key)
        val sourceExt = publicFile.extension
        val trusted = trustedPath(filesDir, key, sourceExt)
        // v0.294.5：文件名算法收紧后，老版本的可信副本在旧名下——新名还没有时搬过来，
        // 避免“公开文件已变 + 不允许重新信任”时因找不到旧副本而拒绝执行。
        // v0.294.22：再认「无脚本后缀」的旧副本（订阅 PY 曾被拷成无 .py 名）。
        if (!trusted.isFile) {
            val dir = File(filesDir, TRUSTED_DIR_NAME)
            val candidates = listOf(
                File(dir, legacyTrustedName(key)),
                File(dir, SafeFileName.encode(key, Regex("[^A-Za-z0-9._-]+"), 200, "_", keepExt = true)),
            )
            for (legacy in candidates) {
                if (legacy != trusted && legacy.isFile) {
                    runCatching {
                        trusted.parentFile?.mkdirs()
                        legacy.copyTo(trusted, overwrite = false)
                        trusted.setReadOnly()
                    }
                    if (trusted.isFile) break
                }
            }
        }
        if (registered == null) {
            // 首次见到：公开文件即导入源，拷入私有并锚定。
            if (!copyToTrusted(publicFile, trusted)) return null
            record(filesDir, key, trusted)
            return trusted
        }
        if (publicSha == registered) {
            val trustedSha = if (trusted.isFile) sha256(trusted) else null
            if (trustedSha == registered) return trusted
            // 私有副本丢了或被改，但公开文件仍与注册表一致：从公开文件恢复。
            if (!copyToTrusted(publicFile, trusted)) return null
            return trusted
        }
        // 公开文件已变：显式重新信任才更新，否则用旧私有副本，绝不执行新内容。
        if (allowRetrust) {
            if (!copyToTrusted(publicFile, trusted)) return null
            record(filesDir, key, trusted)
            return trusted
        }
        val trustedSha = if (trusted.isFile) sha256(trusted) else null
        if (trustedSha == registered) {
            LogBus.w(
                TAG,
                "公开文件已变动，未自动执行新内容，仍用旧可信副本：$key",
            )
            return trusted
        }
        LogBus.e(TAG, "可信副本与注册表不一致，拒绝执行：$key")
        return null
    }

    fun trustedText(
        filesDir: File,
        publicFile: File,
        key: String,
        allowRetrust: Boolean,
    ): String? {
        val trusted = trustedFile(filesDir, publicFile, key, allowRetrust) ?: return null
        return runCatching { trusted.readText() }.getOrNull()
    }

    /**
     * 只验不拷：PHP 脚本、服务工作目录这类带相对引用整目录执行的
     * 内容不能单文件拷私有，执行前用注册表验公开文件本身。
     * 返回 true=可执行（首次见到时锚定），false=已变动，拒绝执行。
     */
    fun verifyPublic(
        filesDir: File,
        publicFile: File,
        key: String,
        allowRetrust: Boolean,
    ): Boolean {
        if (!publicFile.isFile) return false
        val publicSha = sha256(publicFile) ?: return false
        val registered = registeredSha(filesDir, key)
        if (registered == null) {
            record(filesDir, key, publicFile)
            return true
        }
        if (publicSha == registered) return true
        if (allowRetrust) {
            record(filesDir, key, publicFile)
            return true
        }
        LogBus.e(TAG, "公开可执行文件已变动，拒绝执行：$key")
        return false
    }

    private fun copyToTrusted(src: File, dst: File): Boolean = runCatching {
        dst.parentFile?.mkdirs()
        dst.setWritable(true)
        src.inputStream().use { input ->
            dst.outputStream().use { out -> input.copyTo(out) }
        }
        dst.setReadOnly()
        dst.isFile && dst.length() == src.length()
    }.getOrDefault(false)
}
