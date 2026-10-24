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

    enum class Status { RUNNING, STOPPED, ERROR, STARTING }

    /** v0.138.0：启动中宽限窗——进程活着但未满该时长时显示黄灯，满后转绿灯。 */
    private const val STARTING_GRACE_MS = 5000L

    /**
     * v0.186.0：解压上限（H14，与 PluginMarket 同阈值）。条目数按实际解压的
     * 文件计，上限 2000；解压后总字节数按实际写出字节累计，上限 500MB，
     * 超限即中止并清暂存目录（install 的 catch 已清暂存）。
     */
    private const val MAX_ZIP_ENTRIES = 2000
    private const val MAX_UNPACKED_BYTES = 500L * 1024L * 1024L

    /** v0.186.0：解压预算累计器（条目数与解压后字节数）。 */
    private class UnpackBudget {
        var entries = 0
        var bytes = 0L
    }

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

    /** v0.136.0：原生二进制双目录探针中单个目录的四格结果（直执/linker64）。 */
    private data class NativeDirProbe(
        val directOk: Boolean,
        val directMsg: String,
        val linkerOk: Boolean,
        val linkerMsg: String,
    ) {
        val anyOk: Boolean get() = directOk || linkerOk
    }
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
        val startedAtMs: Long = System.currentTimeMillis(),
    )

    private val lock = Any()
    private val running = mutableMapOf<String, RunningHandle>()

    /**
     * v0.186.0：注册表损坏标记。loadRegistry 读坏时置 true、读成功或文件
     * 不存在时清 false；saveRegistry 成功写盘后清 false。全部读写都在
     * synchronized(lock) 内，标记只区分“上次加载是否损坏”，不做跨进程判断。
     */
    @Volatile
    private var registryCorrupt = false

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
        if (!f.exists()) {
            registryCorrupt = false
            return mutableMapOf()
        }
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
        }.onSuccess { registryCorrupt = false }
            .getOrElse {
                AtomicFile.preserveCorrupt(f)
                registryCorrupt = true
                LogBus.w(TAG, "注册表损坏，已保全为 .corrupt.bak，本次只读空表、拒绝写回：${it.message}")
                mutableMapOf()
            }
    }

    /**
     * v0.186.0：改走 AtomicFile 写盘（同目录唯一后缀 tmp + fsync + 改名替换，
     * 复用第 3 组 AtomicFile，不改其实现）。返回写盘成败，调用方忽略亦可编译。
     * 写盘前护栏：上次加载已损坏且本次写出为空时拒绝写回并记日志，原损坏
     * 文件与 .corrupt.bak 一并保留；损坏后写入非空表则放行（新表替换损坏文件，
     * 旧字节仍在 .corrupt.bak 里），避免损坏态下永远无法安装或保存新服务。
     * 主动清空不受影响：逐个卸载时每步加载均成功、标记为 false，最后一次
     * 写 0 条是合法清空，不会被拦。
     */
    private fun saveRegistry(context: Context, reg: Map<String, RegistryEntry>): Boolean {
        if (registryCorrupt && reg.isEmpty()) {
            LogBus.w(TAG, "拒绝写回空注册表：上次加载已损坏，原文件与 .corrupt.bak 均保留")
            return false
        }
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
        val ok = AtomicFile.writeText(
            registryFile(context),
            JSONObject().put("plugins", arr).toString(),
        )
        if (ok) {
            registryCorrupt = false
        } else {
            LogBus.w(TAG, "注册表写入失败")
        }
        return ok
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
            if (pid == "php") {
                // v0.137.0：php 运行时不走服务目录——市场 runtime 型装在私有
                // runtime/php；服务页 zip 安装的才在服务目录（共享存储时首次解析拷私有）。
                return@replace phpUsableDir(context).absolutePath
            }
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

    // ---------------- v0.137.0：PHP 运行时插件（市场 runtime 型，id=php） ----------------
    //
    // 落点：市场安装在私有 filesDir/runtime/php；服务页 zip 安装在服务目录
    // serviceDir/php（共享存储时首次解析拷私有，linker64 无法从共享存储映射
    // 是 v0.136.0 自检实测结论）。未安装时抛与原口径完全一致的报错，
    // T4-PHP 在 php 未安装时的启动行为不变。

    private const val PHP_COPY_DIR = "php-runtime-copy"

    /** php 运行时可用目录：私有安装直接用，共享安装先拷私有，源没了清旧拷。 */
    private fun phpUsableDir(context: Context): File {
        val runtimeDir = File(StoragePaths.runtimeDir(context), "php")
        if (File(runtimeDir, "bin/php").isFile) return runtimeDir
        val svcDir = File(installRoot(context), "php")
        if (File(svcDir, "bin/php").isFile) {
            if (isUnderFilesDir(context, svcDir)) return svcDir
            return ensurePhpPrivateCopy(context, svcDir)
        }
        runCatching { File(context.filesDir, PHP_COPY_DIR).deleteRecursively() }
        throw IllegalStateException(
            "占位符 __plugin:php__ 展开失败：服务/插件 php 未安装",
        )
    }

    private fun isUnderFilesDir(context: Context, dir: File): Boolean {
        val base = context.filesDir.absolutePath
        val path = dir.absolutePath
        return path == base || path.startsWith("$base/")
    }

    /** 共享安装拷私有（来源标记 gate：源 php 的路径+mtime+体积不变则跳过）。 */
    private fun ensurePhpPrivateCopy(context: Context, srcDir: File): File {
        val dst = File(context.filesDir, PHP_COPY_DIR)
        val srcPhp = File(srcDir, "bin/php")
        val stamp = "${srcDir.absolutePath}|${srcPhp.lastModified()}|${srcPhp.length()}"
        val marker = File(dst, ".source")
        if (File(dst, "bin/php").isFile &&
            runCatching { marker.readText() }.getOrDefault("") == stamp
        ) {
            return dst
        }
        dst.deleteRecursively()
        File(dst, "bin").mkdirs()
        File(dst, "lib").mkdirs()
        srcPhp.copyTo(File(dst, "bin/php"), overwrite = true)
        runCatching { File(dst, "bin/php").setExecutable(true) }
        File(srcDir, "lib").listFiles()
            ?.filter { it.isFile }
            ?.forEach { it.copyTo(File(dst, "lib/${it.name}"), overwrite = true) }
        runCatching { marker.writeText(stamp) }
        return dst
    }

    /** php 可执行文件对应的依赖库目录（bin/php + 同级 lib/ 结构时返回，否则 null）。 */
    private fun phpLibDirFor(exe: File): File? {
        if (exe.name != "php") return null
        val binDir = exe.parentFile ?: return null
        if (binDir.name != "bin") return null
        val libDir = File(binDir.parentFile ?: return null, "lib")
        return libDir.takeIf { it.isDirectory }
    }

    /** 一次性 CLI 运行结果：exitCode=-1 表示超时被杀，output 是 stdout 与 stderr 合并后的全文。 */
    data class CliResult(val exitCode: Int, val output: String)

    /**
     * v0.151.0：一次性 CLI 运行（零端口 Phase 3，PHP 引擎用）。
     * 与常驻服务共用同一条执行策略：直接 exec 失败回退 /system/bin/linker64，
     * php 依赖库走 LD_LIBRARY_PATH（v0.138.0 的教训，不用 --library-path 参数）。
     * 不占端口、不留常驻进程、不写服务日志缓冲，超时杀进程。
     * 阻塞调用，只能在 Dispatchers.IO 上调；失败抛 IllegalStateException。
     */
    fun runCliOnce(
        context: Context,
        command: List<String>,
        workDir: File,
        timeoutMs: Long = 30_000L,
    ): CliResult {
        val exe = File(command.firstOrNull().orEmpty())
        if (!exe.isFile) throw IllegalStateException("可执行文件不存在：${exe.absolutePath}")
        val env = mutableMapOf<String, String>()
        phpLibDirFor(exe)?.let { libDir ->
            val prev = System.getenv("LD_LIBRARY_PATH").orEmpty()
            env["LD_LIBRARY_PATH"] = if (prev.isBlank()) libDir.absolutePath
            else libDir.absolutePath + ":" + prev
        }
        val tmpDir = File(context.filesDir, "service_tmp").also { it.mkdirs() }
        env["TMPDIR"] = tmpDir.absolutePath
        val linker = File("/system/bin/linker64")
        val altCmd = if (linker.isFile) listOf(linker.absolutePath) + command else null
        val notes = mutableListOf<String>()
        val proc = startProcess(command, altCmd, workDir, env, notes)
        notes.forEach { LogBus.i(TAG, "[cli] $it") }
        val out = StringBuilder()
        val reader = thread(name = "cli-reader", isDaemon = true) {
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { line ->
                    synchronized(out) { out.append(line).append('\n') }
                }
            }
        }
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { proc.destroyForcibly() }
            reader.join(1000)
            return CliResult(-1, out.toString())
        }
        reader.join(2000)
        return CliResult(proc.exitValue(), out.toString())
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
            // v0.162.0：解压前先过全量清单闸门（见 verifyFullManifest）
            verifyFullManifest(zip)
            // v0.171.0：先单独读出 spec（只读 server.json，不落盘），后边的回滚
            // 逻辑需要提前知道 id 与旧注册表项
            val spec = ZipFile(zip).use { zf ->
                val manifest = zf.entries().asSequence().firstOrNull { it.name == "server.json" }
                    ?: throw IllegalStateException("插件包根部缺 server.json")
                parseSpec(zf.getInputStream(manifest).readBytes().toString(Charsets.UTF_8))
            }
            val reg = loadRegistry(context)
            val prev = reg[spec.id]
            // 同 id 更新：先停进程再覆盖
            running[spec.id]?.let { stopLocked(context, spec.id) }
            val root = File(installRoot(context), spec.id)
            // v0.171.0：先解到同父目录暂存目录，verifyExecutable 在暂存目录上验；
            // 验失败时旧目录原样保留、注册表不写，失败即回滚（此前先删旧目录
            // 再解压，验失败时磁盘已是新包残缺态、注册表还是旧版）
            val staging = File(installRoot(context), "${spec.id}.staging-${System.currentTimeMillis()}")
            staging.deleteRecursively()
            staging.mkdirs()
            try {
                ZipFile(zip).use { zf ->
                    val entries = zf.entries().asSequence().filter { !it.isDirectory }.toList()
                    if (entries.isEmpty()) throw IllegalStateException("插件包为空")
                    // v0.186.0：解压上限（H14），超限即抛，catch 清暂存、旧版不动。
                    if (entries.size > MAX_ZIP_ENTRIES) {
                        throw IllegalStateException("插件包条目数超限（上限 $MAX_ZIP_ENTRIES）")
                    }
                    val budget = UnpackBudget()
                    entries.forEach { e ->
                        val dst = safeDst(staging, e.name)
                        dst.parentFile?.mkdirs()
                        copyEntryBounded(zf, e, dst, budget)
                    }
                }
                verifyExecutable(staging, spec)
                swapInstallDir(root, staging)
                // v0.192.0：落盘后写 .nomedia（与市场 server 分支同款，见
                // StoragePaths.ensureNomedia）：服务脚本目录也在公开存储，
                // 包里带 .ts 之类媒体味后缀的文件不再被相册收成假视频。
                StoragePaths.ensureNomedia(root)
            } catch (e: Exception) {
                runCatching { staging.deleteRecursively() }
                // 旧进程是用户期望运行的，安装失败后尽力拉回旧版
                if (prev?.desiredRunning == true && root.exists()) {
                    runCatching { startLocked(context, spec.id) }
                }
                throw e
            }
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
            if (!saveRegistry(context, reg)) {
                LogBus.w(TAG, "卸载 $id：注册表未写回（见上一条日志），服务目录已删除")
            }
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

    /**
     * v0.171.0：暂存目录整体替换安装目录。旧目录先改名备份，暂存目录改名
     * 到位后再删备份；改名失败时把备份改回来，旧版不丢（同 PluginMarket.swapDir 口径）。
     */
    private fun swapInstallDir(root: File, staging: File) {
        val parent = root.parentFile ?: throw IllegalStateException("插件目录无父目录：${root.path}")
        val backup = File(parent, "${root.name}.bak-${System.currentTimeMillis()}")
        val hadOld = root.exists()
        if (hadOld && !root.renameTo(backup)) {
            throw IllegalStateException("旧版备份失败，安装已中止，旧版未动：${root.name}")
        }
        try {
            if (!staging.renameTo(root)) {
                throw IllegalStateException("新版落盘改名失败，安装已中止")
            }
            if (hadOld) runCatching { backup.deleteRecursively() }
        } catch (e: Exception) {
            if (hadOld && !root.exists()) {
                runCatching { backup.renameTo(root) }
                    .onFailure { LogBus.e(TAG, "旧版回滚失败，备份留在：${backup.path}") }
            }
            throw e
        }
    }

    /**
     * v0.186.0：带解压预算的单条目拷贝（H14，与 PluginMarket 同口径）。
     * 只解到暂存区，超限即抛异常，install 的 catch 清暂存、旧版不动。
     */
    private fun copyEntryBounded(
        zf: ZipFile,
        e: java.util.zip.ZipEntry,
        dst: File,
        budget: UnpackBudget,
    ) {
        if (++budget.entries > MAX_ZIP_ENTRIES) {
            throw IllegalStateException("插件包解压条目数超限（上限 $MAX_ZIP_ENTRIES）")
        }
        dst.parentFile?.mkdirs()
        zf.getInputStream(e).use { input ->
            dst.outputStream().use { out ->
                val buf = ByteArray(32 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    if (n == 0) continue
                    budget.bytes += n
                    if (budget.bytes > MAX_UNPACKED_BYTES) {
                        throw IllegalStateException("插件包解压后大小超限（上限 500MB）")
                    }
                    out.write(buf, 0, n)
                }
            }
        }
    }

    /**
     * v0.162.0：插件安装全量清单闸门（解压前调用，拒绝即不落盘）。
     * - server.json 带 files 清单 → 逐文件流式哈希比对，多余/缺失/不符均拒绝；
     *   清单键兼容两种写法：zip 内全路径（如 my-service/server.sh）或相对 workDir（如 server.sh）。
     * - v0.186.0（M22）：无 files 清单的旧格式包一律拒绝安装，不再退回只验主脚本
     *   的旧分支；市场包 MD5 本身不动、也不以换 SHA-256 冒充信任锚，签名公钥
     *   是产品决策，本波不做。暂存后 verifyExecutable 仍做主脚本签名复核。
     * 流式算哈希，不把大包整包读进内存（磁力类插件含 node_modules 时尤其重要）。
     */
    private fun verifyFullManifest(zip: File) {
        ZipFile(zip).use { zf ->
            val sj = zf.getEntry("server.json")
                ?: throw IllegalStateException("插件包根部缺 server.json")
            val conf = JSONObject(zf.getInputStream(sj).readBytes().toString(Charsets.UTF_8))
            val workDir = conf.optString("workDir").trim()
            val command = conf.optString("command").trim()
            if (workDir.isBlank() || command.isBlank()) {
                throw IllegalStateException("server.json 缺 workDir/command")
            }
            val files = conf.optJSONObject("files")
            if (files == null || files.length() == 0) {
                throw IllegalStateException("旧格式服务插件缺 files 清单，拒绝安装：请在 server.json 补全量文件 SHA-256 清单")
            }
            val fileEntries = zf.entries().asSequence()
                .filter { !it.isDirectory && it.name != "server.json" }.toList()
            if (fileEntries.size > MAX_ZIP_ENTRIES) {
                throw IllegalStateException("插件包条目数超限（上限 $MAX_ZIP_ENTRIES）")
            }
            val fileKeys = files.keys().asSequence().toList()
            val actual = fileEntries
                .associate { it.name to hashStream(zf.getInputStream(it), "SHA-256") }
            for (key in fileKeys) {
                val expect = files.optString(key)
                val got = actual[key] ?: actual["$workDir/$key"]
                    ?: throw IllegalStateException("清单声明文件缺失：$key")
                if (!got.equals(expect, ignoreCase = true)) {
                    throw IllegalStateException("文件哈希不匹配：$key")
                }
            }
            val extra = actual.keys.filter { k ->
                k !in fileKeys && fileKeys.none { fk -> k == fk || k == "$workDir/$fk" }
            }
            if (extra.isNotEmpty()) {
                throw IllegalStateException("存在清单外文件：${extra.take(3)}")
            }
        }
    }

    private fun hashStream(input: java.io.InputStream, algo: String): String {
        val md = MessageDigest.getInstance(algo)
        input.use { stream ->
            val buf = ByteArray(8192)
            var n: Int
            while (stream.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
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
        // v0.138.0：php 依赖库改走 LD_LIBRARY_PATH 环境变量——v0.137.0 的
        // --library-path 独立参数被本机 linker64 拒收（把它当待运行程序路径，
        // 报 expected absolute path，进程 code=1 退出）；且直执路径同样缺库
        // 路径，环境变量两路都通。直执在私有目录也会被 SELinux 拦，
        // 故回退是常态而非异常。
        val isScript = exe.name.endsWith(".sh", ignoreCase = true)
        val linker = File("/system/bin/linker64")
        val phpLibDir = phpLibDirFor(exe)
        if (phpLibDir != null) {
            val prev = env["LD_LIBRARY_PATH"]
                ?: System.getenv("LD_LIBRARY_PATH").orEmpty()
            env["LD_LIBRARY_PATH"] = if (prev.isBlank()) {
                phpLibDir.absolutePath
            } else {
                phpLibDir.absolutePath + ":" + prev
            }
        }
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
            h != null && h.process.isAlive &&
                System.currentTimeMillis() - h.startedAtMs < STARTING_GRACE_MS -> Status.STARTING
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

    /** v0.146.0：运行中服务的实际监听端口（弹幕本地源用）；未运行返回 null。 */
    fun portOf(id: String): Int? = synchronized(lock) {
        val h = running[id] ?: return@synchronized null
        if (!h.process.isAlive) return@synchronized null
        h.port
    }

    /** v0.132.0：运行中进程的 PID（编辑页/配置区只读展示）；未运行返回 null。 */
    fun pidOf(id: String): Long? = synchronized(lock) {
        val h = running[id] ?: return@synchronized null
        if (!h.process.isAlive) return@synchronized null
        // java.lang.Process#pid() 是 Java 9 API，android.jar 编译期不可见：反射取，取不到返回 null
        runCatching {
            val m = h.process.javaClass.methods
                .firstOrNull { it.name == "pid" && it.parameterCount == 0 }
                ?: return@runCatching null
            m.isAccessible = true
            when (val v = m.invoke(h.process)) {
                is Number -> v.toLong()
                else -> null
            }
        }.getOrNull()
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
                val wdRaw = def.workDir.ifBlank { "__plugin:${id}__" }
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

    // ---------------- v0.136.0：示例服务一键生成 ----------------
    // v0.137.0：示例服务（hello-service 演示包）退出生成列表，只留 T4-PHP；
    // 已装的示例服务不受影响，用户自己点卸载。市场 hello-service 包不动。

    /** 示例服务清单：id → 展示名，顺序即生成与「已全部存在」提示的列举顺序。 */
    val EXAMPLE_SERVICES: List<Pair<String, String>> = listOf(
        "t4-php" to "T4-PHP",
    )

    /**
     * 已存在的示例服务展示名：按 id 或同名判存（与 importServicesJson 的
     * 重名口径一致）——存在即由调用方跳过生成，绝不覆盖用户改过的同名服务。
     */
    fun existingExampleNames(context: Context): List<String> = synchronized(lock) {
        val reg = loadRegistry(context)
        EXAMPLE_SERVICES.filter { (id, name) ->
            reg.containsKey(id) || reg.values.any { it.name == name }
        }.map { it.second }
    }

    /**
     * 示例「T4-PHP」的定义：与 xiaying-plugins 的 examples/t4-php.service.json
     * 同一份内容，经 defToJson 拼成导入格式后走 importServicesJson 单一路径
     * 落注册表，不另开创建通道。PHP 运行时未上架前生成只是配置：点启动时
     * expandPlaceholders 会明确报 __plugin:php__ 未安装，不假装能跑。
     */
    fun importT4PhpExample(context: Context): String {
        val def = ServiceDef(
            id = "t4-php",
            name = "T4-PHP",
            desc = "PHP 站点源 Web 服务（T4 形态）：用 PHP 内置服务器跑 t4_demo 站点目录，" +
                "打开服务地址可看状态页，/config.php 输出的 T4 源列表可直接添加为侠影的订阅地址。" +
                "前置条件：先装 PHP 运行时插件（待上架；未上架前导入了也启动不了，App 会明确报 " +
                "__plugin:php__ 未安装）。与 DsPlayer 的 T4-PHP 是同一套服务、刻意用不同端口" +
                "（DS 19978 / 侠影 29578），两边同时装也不冲突；不建议改成 19978 与 DS 抢同一端口。" +
                "t4_demo 目录在公共存储 xiaying/php/t4_demo 下，PHP 脚本放进该目录即可被服务到。",
            version = "1.0.0",
            custom = true,
            runtime = RuntimeKind.PHP,
            port = BASE_PORT,
            command = "__plugin:php__/bin/php",
            args = listOf("-S", "127.0.0.1:\${PORT}", "-t", "__sdcard__/php/t4_demo"),
            workDir = "__sdcard__/php/t4_demo",
            env = emptyList(),
            healthMode = HealthMode.HTTP,
            healthPath = "/",
            autoStart = true,
            resident = false,
        )
        val text = JSONObject()
            .put("services", JSONArray().put(defToJson(def)))
            .toString()
        return importServicesJson(context, text)
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
     * 5. 原生二进制执行（v0.132.0 起探路，v0.136.0 扩双目录）：把系统
     *    toybox 分别复制到共享服务目录与私有目录，各试直接执行与
     *    linker64 执行，四格结果分列记进明细。只探共享目录不足以定论
     *    （v0.134.0 真机实测共享目录两路皆挂）——DS 的 PHP 运行时正是
     *    从私有目录 spawn 的；任一格通该项即不算全 FAIL，明细写清哪条通。
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

        // 5. 原生二进制执行探路（v0.136.0 双目录）：同一系统二进制分别复制到
        // 共享服务目录与私有目录，各试直执/linker64，四格分列；任一格通即有
        // 可用路线，该项不算全 FAIL（启动策略不在自检里改，路线下版再定）
        var nativeOk = false
        var nativeDetail = ""
        runCatching {
            val candidates = listOf("/system/bin/toybox", "/system/bin/true", "/system/bin/linker64")
            val srcPath = candidates.firstOrNull { File(it).isFile }
                ?: throw IllegalStateException("候选系统二进制都不存在")
            val probeArgs = when {
                srcPath.endsWith("toybox") -> listOf("true")
                srcPath.endsWith("linker64") -> listOf("/system/bin/true")
                else -> emptyList()
            }
            fun tryRun(dir: File, cmd: List<String>): Pair<Boolean, String> = runCatching {
                val p = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .directory(dir)
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

            fun probeDir(dir: File): NativeDirProbe {
                dir.mkdirs()
                // v0.137.0：toybox 靠 argv[0] 的 basename 认 applet，探针叫
                // svc_native_probe 时它报 Unknown command，是探针假阴性不是路不通：
                // 源是 toybox 或 true 时探针就叫 true、空参跑，加载成功即 exit=0；
                // 源是 linker64 时保持原逻辑（跑它去加载系统 true）。
                val toyboxLike = srcPath.endsWith("toybox") || srcPath.endsWith("true")
                val probe = File(dir, if (toyboxLike) "true" else "svc_native_probe")
                val runArgs = if (toyboxLike) emptyList() else probeArgs
                try {
                    File(srcPath).copyTo(probe, overwrite = true)
                    runCatching { probe.setExecutable(true) }
                    val (dOk, dMsg) = tryRun(dir, listOf(probe.absolutePath) + runArgs)
                    val linkerPath = "/system/bin/linker64"
                    val (lOk, lMsg) = if (File(linkerPath).isFile) {
                        tryRun(dir, listOf(linkerPath, probe.absolutePath) + runArgs)
                    } else {
                        false to "linker64 不存在"
                    }
                    return NativeDirProbe(dOk, dMsg, lOk, lMsg)
                } finally {
                    probe.delete()
                }
            }

            val shared = probeDir(installRoot(context))
            val privDir = File(context.filesDir, "svc_native_probe_dir")
            val priv = try {
                probeDir(privDir)
            } finally {
                runCatching { privDir.delete() }
            }
            nativeOk = shared.anyOk || priv.anyOk
            fun cell(ok: Boolean, msg: String) = "${if (ok) "通" else "挂"}（$msg）"
            val routes = mutableListOf<String>()
            if (shared.directOk) routes += "共享目录直执"
            if (shared.linkerOk) routes += "共享目录 linker64"
            if (priv.directOk) routes += "私有目录直执"
            if (priv.linkerOk) routes += "私有目录 linker64"
            val routeSuffix = if (routes.isEmpty()) {
                "；四路皆不通"
            } else {
                "；可用路线：${routes.joinToString("、")}"
            }
            nativeDetail = "源 $srcPath；" +
                "共享服务目录：直执${cell(shared.directOk, shared.directMsg)}，" +
                "linker64${cell(shared.linkerOk, shared.linkerMsg)}；" +
                "私有目录：直执${cell(priv.directOk, priv.directMsg)}，" +
                "linker64${cell(priv.linkerOk, priv.linkerMsg)}" +
                routeSuffix
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
