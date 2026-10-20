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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/**
 * 服务型插件管理器（v0.120.0 C2：框架管道，不含 PHP 本体）。
 *
 * - 包规范见 xiaying-plugins/SERVER_PLUGIN_SPEC.md（server.json v1）。
 * - 安装：zip → zip-slip 防护解压到服务根目录（StoragePaths.serviceDir()，
 *   v0.128.0 起为公开目录 xiaying/service，公共存储不可用时回退
 *   filesDir/server_plugins）→ server.json 解析 →
 *   command 可执行文件 sha256（优先）/md5 校验 → 注册表登记。
 * - 运行：ProcessBuilder（workingDirectory=workDir，env + PORT/SERVICE_PORT）；
 *   command 为 .sh 脚本时经 /system/bin/sh 解释执行（Android 16 对 App 私有
 *   目录 noexec，直接 exec 脚本 exit=126），原生二进制仍直接执行。
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
        /** v0.132.0：服务管理面字段（卡片标记/编辑入口用）。 */
        val autoStart: Boolean = false,
        val custom: Boolean = false,
    )

    /** v0.132.0：探活三式——仅进程存活 / TCP 端口可连 / HTTP 路径返回 2xx。 */
    enum class HealthMode { PROCESS, TCP, HTTP }

    /** v0.132.0：编辑页运行时占位选择；PHP/Node 本版无运行时本体，仅保存配置。 */
    enum class RuntimeKind { SCRIPT, PHP, NODE, CUSTOM }

    /**
     * v0.132.0：可编辑的服务定义（新建/编辑/导入导出的统一模型）。
     * command/workDir/args/env 值里可写占位符（__filesDir__、__sdcard__、
     * __pkg__、__plugin:<id>__），启动/回显时经 expandPlaceholders 展开。
     * zip 安装的服务编辑时 command/workDir 以 __plugin:<id>__ 形式回显，
     * 保证服务根目录迁移后路径不失效。
     */
    data class ServiceDef(
        val id: String,
        val name: String,
        val desc: String,
        val version: String,
        val custom: Boolean,
        val runtime: RuntimeKind,
        val port: Int,
        val command: String,
        val args: List<String>,
        val workDir: String,
        val env: List<Pair<String, String>>,
        val healthMode: HealthMode,
        val healthPath: String,
        val autoStart: Boolean,
        val resident: Boolean,
    )

    /**
     * v0.127.0：启动配置只读视图（服务卡「配置」区呈现用）。全部按安装后的真实
     * 绝对路径拼装：commandLine 与 startLocked 的 ProcessBuilder 入参同口径
     * （args 里的 ${PORT} 已替换为注册表实际分配端口），不写占位符。
     */
    data class LaunchInfo(
        val desc: String,
        val commandLine: String,
        val workDirPath: String,
        val command: String,
        val args: List<String>,
        /** 端口策略文案：固定 N / 自动分配（实际 M）。 */
        val portSpec: String,
        val healthPath: String,
        val envKeys: List<String>,
        /** v0.132.0：配置展开区补充项（探活方式/自启动/常驻/运行时/运行 PID）。 */
        val healthMode: String = "HTTP",
        val autoStart: Boolean = false,
        val resident: Boolean = false,
        val runtime: String = "CUSTOM",
        val pid: Long? = null,
    )

    data class CheckItem(val name: String, val ok: Boolean, val detail: String)
    data class SelfCheck(val items: List<CheckItem>) {
        val allOk: Boolean get() = items.all { it.ok }
    }

    /**
     * 注册表条目。v0.132.0 起扩出可编辑定义字段：zip 安装的服务这些字段默认空，
     * 启动走 server.json 的 Spec；用户在编辑页保存过（command 非空）或条目是
     * 新建/导入的自定义服务（custom=true）时，启动改走这里的定义（占位符展开后
     * 解析路径）。老注册表读不出新字段时全部走默认值，不崩、行为同旧版。
     */
    private data class RegistryEntry(
        val id: String,
        val name: String,
        val version: String,
        val port: Int,
        val desiredRunning: Boolean,
        val desc: String = "",
        val custom: Boolean = false,
        val runtime: String = "CUSTOM",
        val command: String = "",
        val args: List<String> = emptyList(),
        val workDir: String = "",
        val envList: List<Pair<String, String>> = emptyList(),
        val healthMode: String = "HTTP",
        val healthPath: String = "",
        val autoStart: Boolean = false,
        val resident: Boolean = false,
    ) {
        /** 定义字段是否权威（自定义服务恒是；zip 服务在编辑保存后是）。 */
        val defAuthoritative: Boolean get() = custom || command.isNotBlank()
    }

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

    /** v0.128.0 前的旧服务根（私有目录）；仅迁移源与回退时用，别处不许引用。 */
    private fun legacyRoot(context: Context): File =
        File(context.filesDir, "server_plugins")

    /** 已完成迁移的目标根绝对路径；根解析变化（权限授予后切到公开目录）时再跑一次。 */
    @Volatile
    private var migratedToRoot: String? = null

    private val migrationLock = Any()

    /**
     * 服务根目录唯一入口：解析口径在 StoragePaths.serviceDir()（公开目录
     * xiaying/service，不可用回退私有旧根），本函数在此之上挂旧目录迁移。
     * 安装/启动/停止/卸载/日志路径/describeLaunch/自检全部走这里，不各算各的。
     */
    private fun installRoot(context: Context): File {
        val root = StoragePaths.serviceDir(context)
        ensureMigrated(context, root)
        return root
    }

    /**
     * v0.128.0 旧安装迁移（幂等）：新根不是私有旧根、且旧根下还有已装服务时，
     * 逐个把服务目录搬到新根。顺序：先整目录复制 → 校验目标 server.json 存在
     * → 才删旧目录；复制失败保留旧目录、下次再试，不丢数据。注册表只存
     * id/name/version/port/desiredRunning、不存任何路径，工作目录全由
     * installRoot() 现算，故搬完目录即生效，无需改注册表。正在运行的服务
     * 跳过（其进程已加载脚本，下次走新根的入口自然带过去）。
     */
    private fun ensureMigrated(context: Context, newRoot: File) {
        if (migratedToRoot == newRoot.absolutePath) return
        synchronized(migrationLock) {
            if (migratedToRoot == newRoot.absolutePath) return
            val oldRoot = legacyRoot(context)
            if (oldRoot.canonicalPath == newRoot.canonicalPath) {
                migratedToRoot = newRoot.absolutePath
                return
            }
            val children = runCatching { oldRoot.listFiles() }.getOrNull()
                ?.filter { it.isDirectory }
                .orEmpty()
            if (children.isEmpty()) {
                migratedToRoot = newRoot.absolutePath
                return
            }
            var allMoved = true
            for (src in children) {
                if (synchronized(lock) { running.containsKey(src.name) }) {
                    allMoved = false
                    continue
                }
                val dst = File(newRoot, src.name)
                val moved = runCatching {
                    if (!dst.exists()) {
                        check(src.copyRecursively(dst)) { "复制返回 false" }
                    }
                    // 目标完整（源有 server.json 则目标也必须有）才删源
                    if (File(src, "server.json").exists()) {
                        check(File(dst, "server.json").isFile) { "目标缺 server.json" }
                    }
                    src.deleteRecursively()
                    true
                }.getOrElse {
                    LogBus.w(TAG, "服务 ${src.name} 迁移失败，保留旧目录：${it.message}")
                    false
                }
                if (!moved) allMoved = false
            }
            if (allMoved) {
                runCatching { oldRoot.delete() }
                migratedToRoot = newRoot.absolutePath
                LogBus.i(TAG, "服务工作目录已迁移到 ${newRoot.absolutePath}")
            }
        }
    }

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
                    desc = o.optString("desc", ""),
                    custom = o.optBoolean("custom", false),
                    runtime = o.optString("runtime", "CUSTOM"),
                    command = o.optString("command", ""),
                    args = o.optJSONArray("args")?.let { a ->
                        (0 until a.length()).map { a.optString(it) }
                    }.orEmpty(),
                    workDir = o.optString("workDir", ""),
                    envList = o.optJSONArray("env")?.let { a ->
                        (0 until a.length()).mapNotNull { j ->
                            val kv = a.optJSONObject(j) ?: return@mapNotNull null
                            val k = kv.optString("k")
                            if (k.isBlank()) null else k to kv.optString("v", "")
                        }
                    }.orEmpty(),
                    healthMode = o.optString("healthMode", "HTTP"),
                    healthPath = o.optString("healthPath", ""),
                    autoStart = o.optBoolean("autoStart", false),
                    resident = o.optBoolean("resident", false),
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
            val envArr = JSONArray()
            e.envList.forEach { (k, v) ->
                envArr.put(JSONObject().put("k", k).put("v", v))
            }
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("version", e.version)
                    .put("port", e.port)
                    .put("desiredRunning", e.desiredRunning)
                    .put("desc", e.desc)
                    .put("custom", e.custom)
                    .put("runtime", e.runtime)
                    .put("command", e.command)
                    .put("args", JSONArray(e.args))
                    .put("workDir", e.workDir)
                    .put("env", envArr)
                    .put("healthMode", e.healthMode)
                    .put("healthPath", e.healthPath)
                    .put("autoStart", e.autoStart)
                    .put("resident", e.resident),
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

    // ---------------- v0.132.0：占位符展开（单一入口） ----------------

    private val PLUGIN_TOKEN_RE = Regex("__plugin:([a-z0-9._-]+)__")

    /**
     * 占位符展开唯一入口：启动命令拼装、describeLaunch、编辑页回显的路径解析
     * 全部走这里，不许各写一份。
     * - __filesDir__ → App 私有目录
     * - __sdcard__ → 公开存储 xiaying 根（与 StoragePaths.publicRoot() 同口径）
     * - __pkg__ → 本应用包名
     * - __plugin:<id>__ → 该 id 服务/插件在服务根下的安装目录
     * __plugin 引用的 id 没有安装目录时抛 IllegalStateException（明确原因，
     * 调用方写进日志/Toast），绝不静默展开成错误路径。
     */
    fun expandPlaceholders(context: Context, raw: String): String {
        var out = PLUGIN_TOKEN_RE.replace(raw) { m ->
            val pid = m.groupValues[1]
            val dir = File(installRoot(context), pid)
            if (!dir.isDirectory) {
                throw IllegalStateException(
                    "占位符 __plugin:${pid}__ 展开失败：服务/插件 $pid 未安装",
                )
            }
            dir.absolutePath
        }
        out = out.replace("__filesDir__", context.filesDir.absolutePath)
        out = out.replace("__sdcard__", StoragePaths.publicRoot().absolutePath)
        out = out.replace("__pkg__", context.packageName)
        return out
    }

    // ---------------- v0.132.0：有效启动参数（注册表定义 / server.json 二选一） ----------------

    private data class EffectiveLaunch(
        val workDir: File,
        val exe: File,
        val args: List<String>,
        val env: Map<String, String>,
        val healthMode: HealthMode,
        val healthPath: String,
    )

    private fun parseHealthMode(raw: String): HealthMode =
        runCatching { HealthMode.valueOf(raw) }.getOrDefault(HealthMode.HTTP)

    private fun parseRuntime(raw: String): RuntimeKind =
        runCatching { RuntimeKind.valueOf(raw) }.getOrDefault(RuntimeKind.CUSTOM)

    /**
     * 把注册表条目解析成可直接执行的启动参数：
     * - 定义权威（自定义服务 / zip 服务被编辑保存过）：command/workDir/args/env
     *   全部经 expandPlaceholders 展开；相对路径以「服务根/<id>」为基准解析。
     * - 否则走 zip 的 server.json Spec（旧路径，口径不变）。
     */
    private fun effectiveLaunch(context: Context, entry: RegistryEntry): EffectiveLaunch {
        if (entry.defAuthoritative) {
            val base = File(installRoot(context), entry.id)
            val wdRaw = entry.workDir.ifBlank { "__plugin:${entry.id}__" }
            val wdExpanded = expandPlaceholders(context, wdRaw)
            val wdFile = File(wdExpanded)
            val workDir = if (wdFile.isAbsolute) wdFile else File(base, wdRaw)
            val cmdExpanded = expandPlaceholders(context, entry.command)
            val cmdFile = File(cmdExpanded)
            val exe = if (cmdFile.isAbsolute) cmdFile else File(workDir, cmdExpanded)
            return EffectiveLaunch(
                workDir = workDir,
                exe = exe,
                args = entry.args.map { expandPlaceholders(context, it) },
                env = entry.envList.associate { (k, v) ->
                    k to expandPlaceholders(context, v)
                },
                healthMode = parseHealthMode(entry.healthMode),
                healthPath = entry.healthPath.ifBlank { "/healthz" },
            )
        }
        val spec = readInstalledSpec(context, entry.id)
        val root = File(installRoot(context), entry.id)
        val workDir = safeDst(root, spec.workDir)
        return EffectiveLaunch(
            workDir = workDir,
            exe = File(workDir, spec.command),
            args = spec.args,
            env = spec.env,
            healthMode = HealthMode.HTTP,
            healthPath = spec.healthPath,
        )
    }

    /**
     * v0.132.0：原生二进制执行策略（单一入口，非脚本可执行文件专用）：
     * 先按 cmd 直接 exec；IOException（含 Permission denied）时自动回退
     * /system/bin/linker64 <exe> 方式；两次都失败把两次真实错误一并抛出。
     * 走了哪条路、成没成都写进 notes（调用方塞进服务日志 + LogBus，可观测）。
     * 脚本（.sh）由 launchCommand 预先包成 sh 调用，altCmd 传 null、不走回退。
     */
    private fun startProcess(
        cmd: List<String>,
        altCmd: List<String>?,
        workDir: File,
        env: Map<String, String>,
        notes: MutableList<String>,
    ): Process {
        fun build(c: List<String>): ProcessBuilder {
            val pb = ProcessBuilder(c)
            pb.directory(workDir)
            pb.redirectErrorStream(true)
            pb.environment().putAll(env)
            return pb
        }
        val first = runCatching { build(cmd).start() }
        if (first.isSuccess) {
            notes += "执行策略：直接执行成功（${cmd.firstOrNull().orEmpty()}）"
            return first.getOrThrow()
        }
        val e1 = first.exceptionOrNull()
        if (altCmd == null) {
            throw IllegalStateException("进程启动失败：${e1?.message}")
        }
        notes += "执行策略：直接执行失败（${e1?.message}），回退 linker64"
        val second = runCatching { build(altCmd).start() }
        if (second.isSuccess) {
            notes += "执行策略：linker64 执行成功"
            return second.getOrThrow()
        }
        throw IllegalStateException(
            "进程启动失败：直接执行=${e1?.message}；linker64=${second.exceptionOrNull()?.message}",
        )
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
                // v0.132.0：包更新不丢用户在编辑页保存过的定义/自启动等设置
                desc = prev?.desc.orEmpty(),
                custom = prev?.custom == true,
                runtime = prev?.runtime ?: "CUSTOM",
                command = prev?.command.orEmpty(),
                args = prev?.args.orEmpty(),
                workDir = prev?.workDir.orEmpty(),
                envList = prev?.envList.orEmpty(),
                healthMode = prev?.healthMode ?: "HTTP",
                healthPath = prev?.healthPath.orEmpty(),
                autoStart = prev?.autoStart == true,
                resident = prev?.resident == true,
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

    /**
     * 实际进程命令行（startLocked 与 describeLaunch 共用，保证展示与执行一致）：
     * exe 恒为解包后位于服务 workDir 内的 command 文件；.sh 脚本经
     * /system/bin/sh 解释执行（绕开私有目录 noexec），其余（原生二进制等）
     * 保持直接执行不变。
     */
    private fun launchCommand(exe: File, args: List<String>): List<String> =
        if (exe.name.endsWith(".sh", ignoreCase = true)) {
            listOf("/system/bin/sh", exe.absolutePath) + args
        } else {
            listOf(exe.absolutePath) + args
        }

    fun start(context: Context, id: String) {
        synchronized(lock) { startLocked(context, id) }
    }

    private fun startLocked(context: Context, id: String) {
        val reg = loadRegistry(context)
        val entry = reg[id] ?: throw IllegalStateException("未安装：$id")
        running[id]?.takeIf { it.process.isAlive }?.let { return } // 已在运行
        // v0.132.0：启动参数统一经 effectiveLaunch 解析（注册表定义或 server.json）
        val launch = effectiveLaunch(context, entry)
        val workDir = launch.workDir
        if (!workDir.isDirectory && entry.custom) {
            runCatching { workDir.mkdirs() }
        }
        if (!workDir.isDirectory) {
            throw IllegalStateException("workDir 不存在：${workDir.absolutePath}")
        }
        val exe = launch.exe
        if (!exe.isFile) {
            throw IllegalStateException("command 可执行文件不存在：${exe.absolutePath}")
        }
        runCatching { exe.setExecutable(true) }

        val port = entry.port
        val resolvedArgs = launch.args.map { it.replace("\${PORT}", port.toString()) }
        val env = mutableMapOf<String, String>()
        env.putAll(launch.env)
        env["PORT"] = port.toString()
        env["SERVICE_PORT"] = port.toString()
        // 共享存储建不了 FIFO/套接字文件，服务应把这类文件放 TMPDIR（私有目录）
        if (!launch.env.containsKey("TMPDIR")) {
            val tmpDir = File(context.filesDir, "service_tmp")
            runCatching { tmpDir.mkdirs() }
            env["TMPDIR"] = tmpDir.absolutePath
        }
        // v0.132.0：非脚本走「直接 exec → linker64 回退」策略（startProcess 单一入口）
        val isScript = exe.name.endsWith(".sh", ignoreCase = true)
        val linker = File("/system/bin/linker64")
        val altCmd = if (!isScript && linker.isFile) {
            listOf(linker.absolutePath, exe.absolutePath) + resolvedArgs
        } else {
            null
        }
        val notes = mutableListOf<String>()
        val proc = startProcess(
            launchCommand(exe, resolvedArgs), altCmd, workDir, env, notes,
        )
        notes.forEach { LogBus.i(TAG, "[$id] $it") }

        val handle = RunningHandle(process = proc, port = port)
        notes.forEach { handle.logs.addLast(it) }
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

    /**
     * 健康检查（v0.132.0 起按 healthMode 三式分发，3 秒超时）：
     * PROCESS 只看进程存活；TCP 试连 127.0.0.1:port；HTTP 走
     * CmsApi.defaultClient() GET healthPath 看 2xx（旧口径）。
     */
    suspend fun healthCheck(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        val (port, mode, healthPath) = synchronized(lock) {
            val reg = loadRegistry(context)
            val e = reg[id] ?: return@withContext false
            if (e.defAuthoritative) {
                Triple(
                    e.port,
                    parseHealthMode(e.healthMode),
                    e.healthPath.ifBlank { "/healthz" },
                )
            } else {
                val spec = runCatching { readInstalledSpec(context, id) }.getOrNull()
                Triple(e.port, HealthMode.HTTP, spec?.healthPath ?: "/healthz")
            }
        }
        when (mode) {
            HealthMode.PROCESS -> isRunning(id)
            HealthMode.TCP -> runCatching {
                withTimeout(3000) {
                    Socket().use { s ->
                        s.connect(InetSocketAddress("127.0.0.1", port), 3000)
                        s.isConnected
                    }
                }
            }.getOrDefault(false)
            HealthMode.HTTP -> runCatching {
                withTimeout(3000) {
                    val path = if (healthPath.startsWith("/")) healthPath else "/$healthPath"
                    val req = Request.Builder().url("http://127.0.0.1:$port$path").build()
                    CmsApi.defaultClient().newCall(req).execute().use { it.isSuccessful }
                }
            }.getOrDefault(false)
        }
    }

    // ---------------- 查询 ----------------

    fun installedPlugins(context: Context): List<PluginInfo> = synchronized(lock) {
        installRoot(context) // 触发 v0.128.0 旧工作目录迁移（幂等，见 ensureMigrated）
        val reg = loadRegistry(context)
        reg.values.map { e ->
            PluginInfo(
                id = e.id,
                name = e.name,
                version = e.version,
                port = e.port,
                status = statusOf(context, e.id),
                desiredRunning = e.desiredRunning,
                autoStart = e.autoStart,
                custom = e.custom,
            )
        }.sortedBy { it.id }
    }

    /** 环形缓冲日志（最近 200 行）。 */
    fun logs(id: String): List<String> = synchronized(lock) {
        running[id]?.logs?.toList().orEmpty()
    }

    /** v0.132.0：清空某服务的日志缓冲（日志弹窗「清空」用）；未运行则无操作。 */
    fun clearLogs(id: String) {
        synchronized(lock) { running[id]?.logs?.clear() }
    }

    /** v0.132.0：运行中进程的 PID（编辑页/配置区只读展示）；未运行返回 null。 */
    fun pidOf(id: String): Long? = synchronized(lock) {
        val h = running[id] ?: return@synchronized null
        if (!h.process.isAlive) return@synchronized null
        runCatching { h.process.pid() }.getOrNull()
    }

    /**
     * v0.127.0：读已装服务的启动配置（server.json + 注册表实际端口）拼成只读视图；
     * 未安装/配置损坏返回 null。desc 取 server.json 的 desc/description（可选字段，
     * 规范不强制），缺失时由 UI 用兜底文案。
     */
    fun describeLaunch(context: Context, id: String): LaunchInfo? = synchronized(lock) {
        val reg = loadRegistry(context)
        val entry = reg[id] ?: return@synchronized null
        val root = File(installRoot(context), id)
        // desc：注册表定义优先，其次 server.json 的 desc/description（规范不强制）
        val raw = runCatching {
            JSONObject(File(root, "server.json").readText())
        }.getOrNull()
        val desc = entry.desc.ifBlank {
            raw?.optString("desc")?.ifBlank { null }
                ?: raw?.optString("description").orEmpty()
        }
        val pid = pidOf(id)
        // v0.132.0：命令行/工作目录经 effectiveLaunch + expandPlaceholders 解析，
        // 与 startLocked 同口径；解析失败（如引用的插件未装）时退回原始字段展示
        val launch = runCatching { effectiveLaunch(context, entry) }.getOrNull()
        if (launch == null) {
            return@synchronized LaunchInfo(
                desc = desc,
                commandLine = (listOf(entry.command) + entry.args)
                    .filter { it.isNotBlank() }.joinToString(" "),
                workDirPath = entry.workDir,
                command = entry.command,
                args = entry.args,
                portSpec = "实际 ${entry.port}",
                healthPath = entry.healthPath.ifBlank { "/healthz" },
                envKeys = entry.envList.map { it.first }.sorted(),
                healthMode = parseHealthMode(entry.healthMode).name,
                autoStart = entry.autoStart,
                resident = entry.resident,
                runtime = entry.runtime,
                pid = pid,
            )
        }
        val resolvedArgs = launch.args.map { it.replace("\${PORT}", entry.port.toString()) }
        val specPort = if (entry.defAuthoritative) {
            entry.port
        } else {
            runCatching { readInstalledSpec(context, id).port }.getOrDefault(0)
        }
        LaunchInfo(
            desc = desc,
            commandLine = launchCommand(launch.exe, resolvedArgs).joinToString(" "),
            workDirPath = launch.workDir.absolutePath,
            command = launch.exe.name,
            args = resolvedArgs,
            portSpec = if (specPort > 0) {
                "固定 $specPort（实际 ${entry.port}）"
            } else {
                "自动分配（实际 ${entry.port}）"
            },
            healthPath = launch.healthPath,
            envKeys = launch.env.keys.sorted(),
            healthMode = launch.healthMode.name,
            autoStart = entry.autoStart,
            resident = entry.resident,
            runtime = entry.runtime,
            pid = pid,
        )
    }

    // ---------------- v0.132.0：服务定义读写 / 导入导出 / 自启动 ----------------

    /** 新建服务的默认定义（编辑页初始值）。 */
    fun blankDef(): ServiceDef = ServiceDef(
        id = "",
        name = "",
        desc = "",
        version = "1.0.0",
        custom = true,
        runtime = RuntimeKind.SCRIPT,
        port = 0,
        command = "",
        args = emptyList(),
        workDir = "",
        env = emptyList(),
        healthMode = HealthMode.HTTP,
        healthPath = "/healthz",
        autoStart = false,
        resident = false,
    )

    /**
     * 编辑回显：注册表里有该服务就拼出 ServiceDef。
     * 定义已权威的条目直接读注册表字段；未编辑过的 zip 服务从 server.json 拼，
     * command/workDir 写成 __plugin:<id>__ 占位符形式（展开逻辑单一入口，
     * 服务根迁移后仍有效）。
     */
    fun serviceDef(context: Context, id: String): ServiceDef? = synchronized(lock) {
        val reg = loadRegistry(context)
        val e = reg[id] ?: return@synchronized null
        if (e.defAuthoritative) {
            return@synchronized ServiceDef(
                id = e.id,
                name = e.name,
                desc = e.desc,
                version = e.version,
                custom = e.custom,
                runtime = parseRuntime(e.runtime),
                port = e.port,
                command = e.command,
                args = e.args,
                workDir = e.workDir,
                env = e.envList,
                healthMode = parseHealthMode(e.healthMode),
                healthPath = e.healthPath.ifBlank { "/healthz" },
                autoStart = e.autoStart,
                resident = e.resident,
            )
        }
        runCatching {
            val spec = readInstalledSpec(context, id)
            val raw = runCatching {
                JSONObject(File(installRoot(context), "$id/server.json").readText())
            }.getOrNull()
            ServiceDef(
                id = e.id,
                name = e.name,
                desc = raw?.optString("desc")?.ifBlank { null }
                    ?: raw?.optString("description").orEmpty(),
                version = e.version,
                custom = false,
                runtime = if (spec.command.endsWith(".sh", ignoreCase = true)) {
                    RuntimeKind.SCRIPT
                } else {
                    RuntimeKind.CUSTOM
                },
                port = e.port,
                command = "__plugin:${e.id}__/${spec.workDir}/${spec.command}",
                args = spec.args,
                workDir = "__plugin:${e.id}__/${spec.workDir}",
                env = spec.env.entries.map { it.key to it.value },
                healthMode = HealthMode.HTTP,
                healthPath = spec.healthPath,
                autoStart = e.autoStart,
                resident = e.resident,
            )
        }.getOrNull()
    }

    /** 由名字生成合法 id（小写字母/数字/点/横线）；重名加 -2、-3 后缀。 */
    private fun uniqueId(base: String, taken: Set<String>): String {
        var slug = base.lowercase()
            .map { c -> if (c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '_') c else '-' }
            .joinToString("")
            .trim('-', '.', '_')
        if (slug.isBlank()) slug = "service"
        if (!ID_RE.matches(slug)) slug = "service"
        var candidate = slug
        var n = 2
        while (candidate in taken) {
            candidate = "$slug-$n"
            n++
        }
        return candidate
    }

    /**
     * 保存服务定义（新建 + 编辑共用）：校验名称/命令必填、id 合法；
     * 端口经 allocatePort 分配（保留/冲突自动顺延）；自定义服务顺带建好
     * 工作目录。运行中的服务只保存、不自动重启（下次启动生效）。
     * @return 实际 id（新建且未给 id 时由名称生成）
     */
    fun saveService(context: Context, def: ServiceDef): String = synchronized(lock) {
        if (def.name.isBlank()) throw IllegalStateException("服务名称必填")
        if (def.command.isBlank()) throw IllegalStateException("可执行文件（命令）必填")
        val reg = loadRegistry(context)
        val existing = def.id.takeIf { it.isNotBlank() }?.let { reg[it] }
        val id = when {
            def.id.isBlank() -> uniqueId(def.name, reg.keys)
            !ID_RE.matches(def.id) ->
                throw IllegalStateException("服务 id 只能用小写字母、数字、点、横线：${def.id}")
            else -> def.id
        }
        val port = allocatePort(reg, def.port, excludeId = id)
        reg[id] = RegistryEntry(
            id = id,
            name = def.name.trim(),
            version = def.version.ifBlank { existing?.version ?: "1.0.0" },
            port = port,
            desiredRunning = existing?.desiredRunning == true,
            desc = def.desc.trim(),
            custom = existing?.custom ?: true,
            runtime = def.runtime.name,
            command = def.command.trim(),
            args = def.args,
            workDir = def.workDir.trim(),
            envList = def.env.filter { it.first.isNotBlank() },
            healthMode = def.healthMode.name,
            healthPath = def.healthPath.trim(),
            autoStart = def.autoStart,
            resident = def.resident,
        )
        saveRegistry(context, reg)
        // 自定义服务：工作目录先建好（展开失败只记日志，启动时还会再报）
        if (existing?.custom != false) {
            runCatching {
                val wdRaw = def.workDir.ifBlank { "__plugin:$id__" }
                val wd = File(expandPlaceholders(context, wdRaw))
                val target = if (wd.isAbsolute) wd else File(File(installRoot(context), id), wdRaw)
                target.mkdirs()
            }.onFailure { LogBus.w(TAG, "服务 $id 工作目录预建失败：${it.message}") }
        }
        LogBus.i(TAG, "服务定义已保存：$id（端口 $port）")
        id
    }

    private fun defToJson(def: ServiceDef): JSONObject {
        val envArr = JSONArray()
        def.env.forEach { (k, v) -> envArr.put(JSONObject().put("k", k).put("v", v)) }
        return JSONObject()
            .put("id", def.id)
            .put("name", def.name)
            .put("desc", def.desc)
            .put("version", def.version)
            .put("runtime", def.runtime.name)
            .put("port", def.port)
            .put("command", def.command)
            .put("args", JSONArray(def.args))
            .put("workDir", def.workDir)
            .put("env", envArr)
            .put("healthMode", def.healthMode.name)
            .put("healthPath", def.healthPath)
            .put("autoStart", def.autoStart)
            .put("resident", def.resident)
    }

    private fun defFromJson(o: JSONObject): ServiceDef = ServiceDef(
        id = o.optString("id", ""),
        name = o.optString("name", ""),
        desc = o.optString("desc", ""),
        version = o.optString("version", "1.0.0"),
        custom = true,
        runtime = parseRuntime(o.optString("runtime", "CUSTOM")),
        port = o.optInt("port", 0),
        command = o.optString("command", ""),
        args = o.optJSONArray("args")?.let { a ->
            (0 until a.length()).map { a.optString(it) }
        }.orEmpty(),
        workDir = o.optString("workDir", ""),
        env = o.optJSONArray("env")?.let { a ->
            (0 until a.length()).mapNotNull { j ->
                val kv = a.optJSONObject(j) ?: return@mapNotNull null
                val k = kv.optString("k")
                if (k.isBlank()) null else k to kv.optString("v", "")
            }
        }.orEmpty(),
        healthMode = parseHealthMode(o.optString("healthMode", "HTTP")),
        healthPath = o.optString("healthPath", "/healthz"),
        autoStart = o.optBoolean("autoStart", false),
        resident = o.optBoolean("resident", false),
    )

    /** 导出全部服务定义为 JSON 文本（UI 复制到剪贴板）。 */
    fun exportServicesJson(context: Context): String = synchronized(lock) {
        val reg = loadRegistry(context)
        val arr = JSONArray()
        reg.keys.sorted().forEach { id ->
            serviceDef(context, id)?.let { arr.put(defToJson(it)) }
        }
        JSONObject().put("services", arr).toString(2)
    }

    /**
     * 从 JSON 文本导入服务定义（粘贴导入）：接受 {"services":[...]} 或裸数组。
     * 重名（id 或名称与已有服务冲突）一律改名加后缀（-2、-3…），不覆盖已有服务。
     * @return 结果摘要（导入数 + 改名数）
     */
    fun importServicesJson(context: Context, text: String): String = synchronized(lock) {
        val trimmed = text.trim()
        val arr = when {
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
            else -> runCatching {
                JSONObject(trimmed).optJSONArray("services")
            }.getOrNull()
        } ?: throw IllegalStateException("导入内容不是合法的服务配置 JSON")
        if (arr.length() == 0) throw IllegalStateException("导入内容里没有服务定义")
        var imported = 0
        var renamed = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            var def = defFromJson(o)
            if (def.name.isBlank() || def.command.isBlank()) continue
            val regNow = loadRegistry(context)
            val idTaken = def.id.isNotBlank() && regNow.containsKey(def.id)
            val nameTaken = regNow.values.any { it.name == def.name }
            if (def.id.isBlank() || idTaken || nameTaken) {
                val baseId = def.id.ifBlank { def.name }
                val newId = uniqueId(baseId, regNow.keys)
                var newName = def.name
                if (nameTaken) {
                    var n = 2
                    newName = "${def.name}-$n"
                    while (regNow.values.any { it.name == newName }) {
                        n++
                        newName = "${def.name}-$n"
                    }
                }
                def = def.copy(id = newId, name = newName)
                renamed++
            }
            saveService(context, def)
            imported++
        }
        if (imported == 0) {
            throw IllegalStateException("没有可导入的服务（名称与命令必填）")
        }
        val summary = if (renamed > 0) {
            "已导入 $imported 个服务（其中 $renamed 个重名，已自动改名加后缀）"
        } else {
            "已导入 $imported 个服务"
        }
        LogBus.i(TAG, summary)
        summary
    }

    /**
     * App 启动完成后拉起 autoStart 的服务（XiaYingApp.onCreate 后台调用）：
     * 逐个尝试，失败只记 LogBus，绝不阻塞/拖垮启动。
     * resident（常驻前台）本版只存字段不托管，前台 Service 下一版配合运行时做。
     */
    fun startAutoStartServices(context: Context) {
        val targets = synchronized(lock) {
            loadRegistry(context).values.filter { it.autoStart }.map { it.id }
        }
        if (targets.isEmpty()) return
        LogBus.i(TAG, "自启动服务 ${targets.size} 个，开始拉起")
        targets.forEach { id ->
            runCatching { start(context, id) }
                .onFailure { LogBus.w(TAG, "自启动失败（$id）：${it.message}") }
        }
    }

    // ---------------- 环境自检 ----------------

    /**
     * C4 自检（短、安全、可重复跑）：
     * 1. 服务目录 sh 执行：服务根目录（StoragePaths.serviceDir()）写 echo
     *    小脚本 → /system/bin/sh 解释执行（与服务脚本的实际启动方式一致；
     *    v0.128.0 前查的是私有目录直接 exec，Android 16 noexec 恒失败，已改）；
     * 2. 当前 ABI：Build.SUPPORTED_ABIS；
     * 3. 首个可用端口：BASE_PORT 起探测；
     * 4. 私有目录可写：建临时文件写删；
     * 5. 原生二进制执行（v0.132.0，为 P1 PHP/Node 运行时探路）：把系统
     *    toybox 复制到服务目录，分别试直接执行与 linker64 执行，结果
     *    分别记进明细——哪条通决定运行时二进制的启动方式。
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

        // 1. 服务目录 sh 执行：在服务根目录写测试脚本，经 /system/bin/sh
        // 解释执行验证（与 .sh 服务的实际启动方式同一条路）
        var execOk = false
        var execDetail = ""
        runCatching {
            val svcRoot = installRoot(context)
            val scriptFile = File(svcRoot, "svc_selfcheck.sh")
            try {
                scriptFile.writeText("#!/system/bin/sh\necho SVC_EXEC_OK\n")
                val proc = ProcessBuilder("/system/bin/sh", scriptFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
                val done = proc.waitFor(10, TimeUnit.SECONDS)
                val code = if (done) proc.exitValue() else -1
                if (!done) proc.destroyForcibly()
                if (done && code == 0 && out.contains("SVC_EXEC_OK")) {
                    execOk = true
                    execDetail = "sh 解释执行成功并回显 SVC_EXEC_OK（${svcRoot.absolutePath}）"
                } else {
                    execDetail = "exit=$code，输出：${out.take(200)}"
                }
            } finally {
                scriptFile.delete()
            }
        }.onFailure {
            execDetail = it.message.orEmpty()
        }
        items.add(CheckItem("服务目录可经 sh 执行脚本", execOk, execDetail))

        // 2. ABI
        val abis = Build.SUPPORTED_ABIS.joinToString(", ")
        items.add(CheckItem("当前 ABI", abis.isNotBlank(), abis.ifBlank { "未知" }))

        // 3. 首个可用端口
        runCatching { firstFreePort(context) }
            .onSuccess { items.add(CheckItem("首个可用端口", true, "$it（自 $BASE_PORT 起）")) }
            .onFailure { items.add(CheckItem("首个可用端口", false, it.message.orEmpty())) }

        // 5. 原生二进制执行探路（v0.132.0）：系统二进制复制到服务目录后，
        // 直接执行与 linker64 两条路分别试，明细节分别记录供 P1 定路线
        var nativeOk = false
        var nativeDetail = ""
        runCatching {
            val svcRoot = installRoot(context)
            val candidates = listOf("/system/bin/toybox", "/system/bin/true", "/system/bin/linker64")
            val srcPath = candidates.firstOrNull { File(it).isFile }
                ?: throw IllegalStateException("候选系统二进制都不存在")
            val probe = File(svcRoot, "svc_native_probe")
            try {
                File(srcPath).copyTo(probe, overwrite = true)
                runCatching { probe.setExecutable(true) }
                val probeArgs = when {
                    srcPath.endsWith("toybox") -> listOf("true")
                    srcPath.endsWith("linker64") -> listOf("/system/bin/true")
                    else -> emptyList()
                }
                fun tryRun(cmd: List<String>): Pair<Boolean, String> = runCatching {
                    val p = ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .directory(svcRoot)
                        .start()
                    val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
                    val done = p.waitFor(5, TimeUnit.SECONDS)
                    if (!done) {
                        p.destroyForcibly()
                        false to "超时"
                    } else {
                        (p.exitValue() == 0) to
                            "exit=${p.exitValue()} ${out.take(80)}".trim()
                    }
                }.getOrElse { false to (it.message ?: "异常") }
                val (dOk, dMsg) = tryRun(listOf(probe.absolutePath) + probeArgs)
                val linkerPath = "/system/bin/linker64"
                val (lOk, lMsg) = if (File(linkerPath).isFile) {
                    tryRun(listOf(linkerPath, probe.absolutePath) + probeArgs)
                } else {
                    false to "linker64 不存在"
                }
                nativeOk = dOk || lOk
                nativeDetail = "源 $srcPath 已复制到服务目录；" +
                    "直接执行：${if (dOk) "通" else "挂"}（$dMsg）；" +
                    "linker64：${if (lOk) "通" else "挂"}（$lMsg）"
            } finally {
                probe.delete()
            }
        }.onFailure {
            nativeDetail = it.message.orEmpty()
        }
        items.add(CheckItem("原生二进制执行", nativeOk, nativeDetail))

        val result = SelfCheck(items)
        LogBus.i(
            TAG,
            "环境自检：${if (result.allOk) "全部通过" else "有失败项"}，" +
                items.joinToString("；") { "${it.name}=${if (it.ok) "OK" else "FAIL"}(${it.detail})" },
        )
        result
    }
}
