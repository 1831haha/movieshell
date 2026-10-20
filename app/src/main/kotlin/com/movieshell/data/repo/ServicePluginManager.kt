package com.movieshell.data.repo

import android.content.Context
import android.os.Build
import com.movieshell.data.source.CmsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/**
 * 服务型插件管理器（v0.120.0 C2：框架管道，不含 PHP 本体）。
 *
 * - 包规范见 xiaying-plugins/SERVER_PLUGIN_SPEC.md（server.json v1）。
 * - 安装：zip → zip-slip 防护解压到 filesDir/server_plugins/<id>/ → server.json 解析 →
 *   command 可执行文件 sha256（优先）/md5 校验 → 注册表登记。
 * - 运行：ProcessBuilder（workingDirectory=workDir，env + PORT/SERVICE_PORT），
 *   stdout/stderr 合并进每插件 200 行环形缓冲 + LogBus(tag=ServicePlugin)。
 * - 端口：port=0 从 29578 起自动分配；保留端口（DS 占用 + 常见端口）拒绝；冲突顺延。
 * - 注册表 filesDir/server_plugins_registry.json 只存期望态；Process 只在内存。
 *
 * 所有阻塞方法都必须在 Dispatchers.IO 调用；失败抛 IllegalStateException（中文原因）。
 */
object ServicePluginManager {

    private const val TAG = "ServicePlugin"

    /** 自动分配起始端口（侠影预留段）。 */
    const val BASE_PORT = 29578

    /**
     * 保留端口：DS 已占用 5757/5758/9321/17890/19978/9978 + 常见 8080/8000/8888。
     * 服务进程只绑 127.0.0.1，不绑 0.0.0.0。
     */
    val RESERVED_PORTS = setOf(5757, 5758, 9321, 17890, 19978, 9978, 8080, 8000, 8888)

    private val ID_RE = Regex("^[a-z0-9._-]+$")

    enum class Status { RUNNING, STOPPED, ERROR }

    data class PluginInfo(
        val id: String,
        val name: String,
        val version: String,
        val port: Int,
        val status: Status,
        val desiredRunning: Boolean,
    )

    data class CheckItem(val name: String, val ok: Boolean, val detail: String)
    data class SelfCheck(val items: List<CheckItem>) {
        val allOk: Boolean get() = items.all { it.ok }
    }

    private data class RegistryEntry(
        val id: String,
        val name: String,
        val version: String,
        val port: Int,
        val desiredRunning: Boolean,
    )

    private data class RunningHandle(
        val process: Process,
        val port: Int,
        val logs: ArrayDeque<String> = ArrayDeque(),
        @Volatile var exitCode: Int? = null,
        @Volatile var userStopped: Boolean = false,
    )

    private val lock = Any()
    private val running = mutableMapOf<String, RunningHandle>()

    // ---------------- 目录 ----------------

    private fun installRoot(context: Context): File =
        File(context.filesDir, "server_plugins").also { it.mkdirs() }

    private fun registryFile(context: Context): File =
        File(context.filesDir, "server_plugins_registry.json")

    // ---------------- 注册表 ----------------

    private fun loadRegistry(context: Context): MutableMap<String, RegistryEntry> {
        val f = registryFile(context)
        if (!f.exists()) return mutableMapOf()
        return runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("plugins") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to RegistryEntry(
                    id = id,
                    name = o.optString("name", id),
                    version = o.optString("version", ""),
                    port = o.optInt("port", 0),
                    desiredRunning = o.optBoolean("desiredRunning", false),
                )
            }.toMap().toMutableMap()
        }.getOrElse {
            LogBus.w(TAG, "注册表损坏，已重置：${it.message}")
            mutableMapOf()
        }
    }

    private fun saveRegistry(context: Context, reg: Map<String, RegistryEntry>) {
        val arr = JSONArray()
        reg.values.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("version", e.version)
                    .put("port", e.port)
                    .put("desiredRunning", e.desiredRunning),
            )
        }
        runCatching {
            registryFile(context).writeText(JSONObject().put("plugins", arr).toString())
        }.onFailure { LogBus.w(TAG, "注册表写入失败：${it.message}") }
    }

    // ---------------- server.json 解析 ----------------

    private data class Spec(
        val id: String,
        val name: String,
        val version: String,
        val workDir: String,
        val command: String,
        val args: List<String>,
        val port: Int,
        val healthPath: String,
        val env: Map<String, String>,
        val sha256: String,
        val md5: String,
    )

    private fun parseSpec(text: String): Spec {
        val o = runCatching { JSONObject(text) }
            .getOrElse { throw IllegalStateException("server.json 不是合法 JSON") }
        val id = o.optString("id").trim()
        if (!ID_RE.matches(id)) throw IllegalStateException("server.json 的 id 非法：$id")
        return Spec(
            id = id,
            name = o.optString("name", id),
            version = o.optString("version", ""),
            workDir = o.optString("workDir").trim()
                .also { if (it.isBlank()) throw IllegalStateException("server.json 缺 workDir") },
            command = o.optString("command").trim()
                .also { if (it.isBlank()) throw IllegalStateException("server.json 缺 command") },
            args = o.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            port = o.optInt("port", 0),
            healthPath = o.optString("healthPath", "/healthz").takeIf { it.isNotBlank() } ?: "/healthz",
            env = o.optJSONObject("env")?.let { e ->
                e.keys().asSequence().associateWith { e.optString(it) }
            }.orEmpty(),
            sha256 = o.optString("sha256").trim().lowercase(),
            md5 = o.optString("md5").trim().lowercase(),
        )
    }

    private fun readInstalledSpec(context: Context, id: String): Spec {
        val f = File(installRoot(context), "$id/server.json")
        if (!f.exists()) throw IllegalStateException("插件 $id 的 server.json 不存在，可能安装不完整")
        return parseSpec(f.readText())
    }

    // ---------------- 安装 / 卸载 ----------------

    /**
     * 从本地 zip 安装（含更新：同 id 覆盖前先停进程）。
     * server.json 内的 sha256（优先）/md5 是对 command 可执行文件的摘要，二选一必填。
     */
    fun install(context: Context, zip: File) {
        synchronized(lock) {
            if (!zip.exists()) throw IllegalStateException("安装包不存在")
            val spec: Spec
            ZipFile(zip).use { zf ->
                val entries = zf.entries().asSequence().filter { !it.isDirectory }.toList()
                if (entries.isEmpty()) throw IllegalStateException("插件包为空")
                val manifest = entries.firstOrNull { it.name == "server.json" }
                    ?: throw IllegalStateException("插件包根部缺 server.json")
                spec = parseSpec(zf.getInputStream(manifest).readBytes().toString(Charsets.UTF_8))
                // 同 id 更新：先停进程再覆盖
                running[spec.id]?.let { stopLocked(context, spec.id) }
                val root = File(installRoot(context), spec.id)
                if (root.exists()) root.deleteRecursively()
                root.mkdirs()
                entries.forEach { e ->
                    val dst = safeDst(root, e.name)
                    dst.parentFile?.mkdirs()
                    zf.getInputStream(e).use { input -> dst.outputStream().use { input.copyTo(it) } }
                }
                verifyExecutable(root, spec)
            }
            val reg = loadRegistry(context)
            val prev = reg[spec.id]
            val port = allocatePort(reg, spec.port, excludeId = spec.id)
            reg[spec.id] = RegistryEntry(
                id = spec.id,
                name = spec.name,
                version = spec.version,
                port = port,
                desiredRunning = prev?.desiredRunning == true,
            )
            saveRegistry(context, reg)
            LogBus.i(TAG, "安装成功：${spec.id} v${spec.version}（端口 $port）")
            // 更新且之前期望运行 → 拉起新进程
            if (prev?.desiredRunning == true) {
                runCatching { startLocked(context, spec.id) }
                    .onFailure { LogBus.w(TAG, "更新后自动拉起失败：${it.message}") }
            }
        }
    }

    fun uninstall(context: Context, id: String) {
        synchronized(lock) {
            val reg = loadRegistry(context)
            if (!reg.containsKey(id) && !File(installRoot(context), id).exists()) {
                throw IllegalStateException("未安装：$id")
            }
            stopLocked(context, id)
            File(installRoot(context), id).deleteRecursively()
            reg.remove(id)
            saveRegistry(context, reg)
            running.remove(id)
            LogBus.i(TAG, "卸载成功：$id")
        }
    }

    /** zip-slip 防护：解压目标必须落在 dir 内（复用 PluginMarket 口径）。 */
    private fun safeDst(dir: File, name: String): File {
        val dst = File(dir, name)
        if (!dst.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            throw IllegalStateException("插件包路径非法：$name")
        }
        return dst
    }

    /** 可执行文件签名校验：sha256 优先、md5 兼容，二者都缺则拒绝安装。 */
    private fun verifyExecutable(root: File, spec: Spec) {
        val exe = File(File(root, spec.workDir), spec.command)
        if (!exe.isFile) throw IllegalStateException("command 可执行文件不存在：${spec.command}")
        runCatching { exe.setExecutable(true) }
        val (algo, expect) = when {
            spec.sha256.isNotBlank() -> "SHA-256" to spec.sha256
            spec.md5.isNotBlank() -> "MD5" to spec.md5
            else -> throw IllegalStateException("server.json 缺 sha256/md5 签名，拒绝安装")
        }
        val actual = MessageDigest.getInstance(algo).let { md ->
            exe.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } > 0) md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        if (!actual.equals(expect, ignoreCase = true)) {
            throw IllegalStateException("$algo 校验失败（${spec.id} 的 ${spec.command}），文件可能被篡改，已拒绝安装")
        }
    }

    // ---------------- 端口 ----------------

    /** 分配端口：显式 port 若保留/冲突则顺延；port=0 从 BASE_PORT 起找。 */
    private fun allocatePort(
        reg: Map<String, RegistryEntry>,
        want: Int,
        excludeId: String? = null,
    ): Int {
        val taken = reg.filterKeys { it != excludeId }.values.map { it.port }.toSet()
        var p = if (want > 0) want else BASE_PORT
        if (want > 0 && want in RESERVED_PORTS) {
            LogBus.w(TAG, "端口 $want 为保留端口，自动顺延")
        }
        val start = if (want > 0 && want !in RESERVED_PORTS) want else BASE_PORT
        p = start
        while (p < BASE_PORT + 2000) {
            if (p !in RESERVED_PORTS && p !in taken && canBind(p)) return p
            p++
        }
        throw IllegalStateException("无可用端口（$start 起 2000 范围内）")
    }

    /** 只绑 127.0.0.1 探测，不碰 0.0.0.0。 */
    private fun canBind(port: Int): Boolean = runCatching {
        ServerSocket(port, 50, InetAddress.getByName("127.0.0.1")).use { true }
    }.getOrDefault(false)

    /** 29578 起第一个可用端口（供自检/UI 展示）。 */
    fun firstFreePort(context: Context): Int = synchronized(lock) {
        val reg = loadRegistry(context)
        allocatePort(reg, 0)
    }

    // ---------------- 启动 / 停止 ----------------

    fun start(context: Context, id: String) {
        synchronized(lock) { startLocked(context, id) }
    }

    private fun startLocked(context: Context, id: String) {
        val reg = loadRegistry(context)
        val entry = reg[id] ?: throw IllegalStateException("未安装：$id")
        running[id]?.takeIf { it.process.isAlive }?.let { return } // 已在运行
        val spec = readInstalledSpec(context, id)
        val workDir = safeDst(File(installRoot(context), id), spec.workDir)
        if (!workDir.isDirectory) throw IllegalStateException("workDir 不存在：${spec.workDir}")
        val exe = File(workDir, spec.command)
        if (!exe.isFile) throw IllegalStateException("command 可执行文件不存在：${spec.command}")
        runCatching { exe.setExecutable(true) }

        val port = entry.port
        val pb = ProcessBuilder(
            listOf(exe.absolutePath) + spec.args.map { it.replace("\${PORT}", port.toString()) },
        )
        pb.directory(workDir)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env.putAll(spec.env)
        env["PORT"] = port.toString()
        env["SERVICE_PORT"] = port.toString()
        val proc = runCatching { pb.start() }
            .getOrElse { throw IllegalStateException("进程启动失败：${it.message}") }

        val handle = RunningHandle(process = proc, port = port)
        running[id] = handle
        // 日志读取线程：随进程退出自动回收
        thread(name = "svc-log-$id", isDaemon = true) {
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { line ->
                    synchronized(lock) {
                        val h = running[id]
                        if (h != null) {
                            h.logs.addLast(line)
                            while (h.logs.size > 200) h.logs.removeFirst()
                        }
                    }
                    LogBus.i(TAG, "[$id] $line")
                }
            }
        }
        // 退出观察线程：记 exitCode（用户主动 stop 不记异常）
        thread(name = "svc-watch-$id", isDaemon = true) {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            synchronized(lock) {
                val h = running[id] ?: return@synchronized
                h.exitCode = code
                if (!h.userStopped) {
                    LogBus.w(TAG, "[$id] 进程退出，code=$code")
                }
            }
        }
        reg[id] = entry.copy(desiredRunning = true)
        saveRegistry(context, reg)
        LogBus.i(TAG, "已启动：$id（端口 $port）")
    }

    fun stop(context: Context, id: String) {
        synchronized(lock) { stopLocked(context, id) }
    }

    private fun stopLocked(context: Context, id: String) {
        val handle = running[id]
        if (handle != null) {
            handle.userStopped = true
            runCatching {
                handle.process.destroy()
                if (!handle.process.waitFor(3, TimeUnit.SECONDS)) {
                    handle.process.destroyForcibly()
                }
            }
            running.remove(id)
        }
        val reg = loadRegistry(context)
        reg[id]?.let {
            reg[id] = it.copy(desiredRunning = false)
            saveRegistry(context, reg)
        }
        LogBus.i(TAG, "已停止：$id")
    }

    fun isRunning(id: String): Boolean = synchronized(lock) {
        running[id]?.process?.isAlive == true
    }

    fun statusOf(context: Context, id: String): Status = synchronized(lock) {
        val h = running[id]
        return when {
            h != null && h.process.isAlive -> Status.RUNNING
            h != null && !h.userStopped && h.exitCode != null && h.exitCode != 0 -> Status.ERROR
            else -> Status.STOPPED
        }
    }

    /** 健康检查：GET http://127.0.0.1:port/healthPath，3 秒超时，走 CmsApi.defaultClient()。 */
    suspend fun healthCheck(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        val (port, healthPath) = synchronized(lock) {
            val reg = loadRegistry(context)
            val e = reg[id] ?: return@withContext false
            val spec = runCatching { readInstalledSpec(context, id) }.getOrNull()
            e.port to (spec?.healthPath ?: "/healthz")
        }
        runCatching {
            withTimeout(3000) {
                val req = Request.Builder().url("http://127.0.0.1:$port$healthPath").build()
                CmsApi.defaultClient().newCall(req).execute().use { it.isSuccessful }
            }
        }.getOrDefault(false)
    }

    // ---------------- 查询 ----------------

    fun installedPlugins(context: Context): List<PluginInfo> = synchronized(lock) {
        val reg = loadRegistry(context)
        reg.values.map { e ->
            PluginInfo(
                id = e.id,
                name = e.name,
                version = e.version,
                port = e.port,
                status = statusOf(context, e.id),
                desiredRunning = e.desiredRunning,
            )
        }.sortedBy { it.id }
    }

    /** 环形缓冲日志（最近 200 行）。 */
    fun logs(id: String): List<String> = synchronized(lock) {
        running[id]?.logs?.toList().orEmpty()
    }

    // ---------------- 环境自检 ----------------

    /**
     * C4 自检（短、安全、可重复跑）：
     * 1. 私有目录 exec：filesDir 写 echo 小脚本 → chmod +x → 真实执行；
     * 2. 当前 ABI：Build.SUPPORTED_ABIS；
     * 3. 首个可用端口：BASE_PORT 起探测；
     * 4. 私有目录可写：建临时文件写删。
     */
    suspend fun runSelfCheck(context: Context): SelfCheck = withContext(Dispatchers.IO) {
        val items = mutableListOf<CheckItem>()

        // 4. 私有目录可写
        val (writeOk, writeDetail) = runCatching {
            val f = File(context.filesDir, "svc_write_check.tmp")
            f.writeText("w")
            check(f.exists()) { "写入后文件不存在" }
            f.delete()
            true to context.filesDir.absolutePath
        }.getOrElse { false to it.message.orEmpty() }
        items.add(CheckItem("私有目录可写", writeOk, writeDetail))

        // 1. 私有目录 exec（唯一走 shell 的一项）
        var execOk = false
        var execDetail = ""
        if (writeOk) {
            val dir = context.filesDir.absolutePath.replace("'", "")
            val script = "D='$dir'; " +
                "printf '#!/system/bin/sh\\necho SVC_EXEC_OK\\n' > \"\$D/svc_selfcheck.sh\" && " +
                "chmod 700 \"\$D/svc_selfcheck.sh\" && " +
                "\"\$D/svc_selfcheck.sh\""
            runCatching {
                val proc = ProcessBuilder("/system/bin/sh", "-c", script)
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
                val done = proc.waitFor(10, TimeUnit.SECONDS)
                val code = if (done) proc.exitValue() else -1
                if (!done) proc.destroyForcibly()
                File(context.filesDir, "svc_selfcheck.sh").delete()
                if (done && code == 0 && out.contains("SVC_EXEC_OK")) {
                    execOk = true; execDetail = "脚本执行成功并回显 SVC_EXEC_OK"
                } else {
                    execDetail = "exit=$code，输出：${out.take(200)}"
                }
            }.onFailure {
                execDetail = it.message.orEmpty()
            }
        } else {
            execDetail = "私有目录不可写，跳过"
        }
        items.add(CheckItem("私有目录可执行脚本", execOk, execDetail))

        // 2. ABI
        val abis = Build.SUPPORTED_ABIS.joinToString(", ")
        items.add(CheckItem("当前 ABI", abis.isNotBlank(), abis.ifBlank { "未知" }))

        // 3. 首个可用端口
        runCatching { firstFreePort(context) }
            .onSuccess { items.add(CheckItem("首个可用端口", true, "$it（自 $BASE_PORT 起）")) }
            .onFailure { items.add(CheckItem("首个可用端口", false, it.message.orEmpty())) }

        val result = SelfCheck(items)
        LogBus.i(
            TAG,
            "环境自检：${if (result.allOk) "全部通过" else "有失败项"}，" +
                items.joinToString("；") { "${it.name}=${if (it.ok) "OK" else "FAIL"}(${it.detail})" },
        )
        result
    }
}
