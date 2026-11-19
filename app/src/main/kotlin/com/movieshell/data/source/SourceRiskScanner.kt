package com.movieshell.data.source

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * v1.3.25 合并 2（DsPlayer 对照第 2 名）：源安全风险扫描——自建规则表（纯函数部分）。
 *
 * 只按用户定的标准判：对手机有无危害——联网往哪发、发什么、有无读手机
 * 敏感数据（通讯录/短信/位置/设备标识/照片文件）、有无执行代码/装东西/
 * 改系统。绕墙与否不在此判。规则库自研，不抄任何现成实现。
 *
 * 判定是"信号组合"不是"关键词命中"：单个网络请求、单个 eval 都不算高危，
 * 高危只认组合——读敏感数据+联网、执行代码+联网、下载+写代码文件、装包。
 * 误报代价只是一次确认（弹窗里可点"仍要导入"），漏报代价是手机被偷数据，
 * 所以组合判定的阈值宁严勿松；单个信号只记中危，不拦路。
 *
 * 校验过的误报边界（2026-11-19，对 ~/workspace/py_fix 实测）：
 * - "android_id" 字面量不算读设备标识（橘汁 4K 自生成随机 id、聚合短剧
 *   发写死的设备号都只是字符串）；只认 Secure.ANDROID_ID / getDeviceId /
 *   getImei / Build.SERIAL 这类读手机真实标识的 API。
 * - subprocess.run 这类本地执行只记中危（喜马拉雅书源调本地命令是正常用法）。
 * - JSON 书源是纯数据、不含可执行规则，本版不扫（@js: 规则另议）。
 */
object SourceRiskScanner {

    enum class Severity { HIGH, MEDIUM }

    data class Finding(
        val severity: Severity,
        val ruleId: String,
        val title: String,
        val detail: String,
        /** 命中位置（1 起；JAR 扫描为 0）。 */
        val line: Int = 0,
    )

    data class Report(val findings: List<Finding>) {
        val hasHighRisk: Boolean get() = findings.any { it.severity == Severity.HIGH }
        val maxSeverity: Severity?
            get() = when {
                hasHighRisk -> Severity.HIGH
                findings.isNotEmpty() -> Severity.MEDIUM
                else -> null
            }
    }

    /** 扫描上限：源文件超大时拒绝导入（规则文件正常只有几十 KB）。 */
    const val MAX_SCAN_BYTES = 4 * 1024 * 1024

    /** 带上限读流：超限返回 null（调用方按"文件过大"拒绝）。 */
    fun readCappedBytes(input: InputStream, maxBytes: Int = MAX_SCAN_BYTES): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private data class Signals(
        val network: Int,
        val readContacts: Int,
        val readSms: Int,
        val readLocation: Int,
        val readDeviceId: Int,
        val readFiles: Int,
        val execCode: Int,
        val dynamicCode: Int,
        val download: Int,
        val writeCodeFile: Int,
        val installApk: Int,
    )

    /** 首个匹配的行号（1 起）；没命中返回 0。 */
    private fun findFirst(text: String, pattern: Regex): Int {
        val m = pattern.find(text) ?: return 0
        return text.substring(0, m.range.first).count { it == '\n' } + 1
    }

    private val NETWORK_RE =
        Regex("""https?://[^\s"'<>]+|\b(fetch|axios|XMLHttpRequest|requests\.(get|post)|urllib|http\.get|http\.post|okhttp|java\.net\.URL)\b""")
    private val READ_CONTACTS_RE = Regex("""content://com\.android\.contacts|ContactsContract|READ_CONTACTS""")
    private val READ_SMS_RE = Regex("""content://sms|Telephony\.Sms|SmsManager|READ_SMS""")
    private val READ_LOCATION_RE =
        Regex("""getLastKnownLocation|requestLocationUpdates|LocationManager|ACCESS_FINE_LOCATION|getLatitude|getLongitude""")
    private val READ_DEVICE_ID_RE =
        Regex("""Secure\.ANDROID_ID|getDeviceId|getImei|getMeid|Build\.SERIAL|TelephonyManager""")
    private val READ_FILES_RE =
        Regex("""Environment\.getExternalStorageDirectory|/sdcard/|MediaStore\.Images|READ_EXTERNAL_STORAGE|READ_MEDIA_IMAGES""")
    private val EXEC_CODE_RE =
        Regex("""\beval\s*\(|\bexec\s*\(|Function\s*\(|os\.system|subprocess\.|Runtime\.getRuntime\(\)\.exec|shell_exec|passthru\s*\(|\bsystem\s*\(|popen\s*\(|loadClass\s*\(""")
    private val DYNAMIC_CODE_RE = Regex("""DexClassLoader|System\.loadLibrary|System\.load\s*\(""")
    private val DOWNLOAD_RE =
        Regex("""\bdownload\b|requests\.get|urllib\.request\.urlretrieve|urlretrieve|wget|curl\b""")
    private val WRITE_CALL_RE =
        Regex("""open\s*\([^)]*['"][wab][^'"]*['"]|FileOutputStream|Files\.write|writeText|fwrite\s*\(""")
    private val CODE_SUFFIX_RE = Regex("""\.py['"]|\.js['"]|\.php['"]|\.jar['"]|\.dex['"]|\.so['"]""")
    private val INSTALL_APK_RE =
        Regex("""ACTION_INSTALL_PACKAGE|pm install|application/vnd\.android\.package-archive|PackageInstaller""")

    private fun collectSignals(text: String): Signals {
        val hasWrite = WRITE_CALL_RE.containsMatchIn(text)
        val hasCodeSuffix = CODE_SUFFIX_RE.containsMatchIn(text)
        return Signals(
            network = findFirst(text, NETWORK_RE),
            readContacts = findFirst(text, READ_CONTACTS_RE),
            readSms = findFirst(text, READ_SMS_RE),
            readLocation = findFirst(text, READ_LOCATION_RE),
            readDeviceId = findFirst(text, READ_DEVICE_ID_RE),
            readFiles = findFirst(text, READ_FILES_RE),
            execCode = findFirst(text, EXEC_CODE_RE),
            dynamicCode = findFirst(text, DYNAMIC_CODE_RE),
            download = findFirst(text, DOWNLOAD_RE),
            writeCodeFile = if (hasWrite && hasCodeSuffix) {
                findFirst(text, WRITE_CALL_RE)
            } else {
                0
            },
            installApk = findFirst(text, INSTALL_APK_RE),
        )
    }

    private fun findingsFromSignals(s: Signals, jarMode: Boolean): List<Finding> {
        val out = ArrayList<Finding>()
        val readKinds = listOf(
            "通讯录" to s.readContacts,
            "短信" to s.readSms,
            "位置" to s.readLocation,
            "设备标识" to s.readDeviceId,
            "手机文件/照片" to s.readFiles,
        ).filter { it.second > 0 }
        if (readKinds.isNotEmpty() && s.network > 0) {
            out.add(
                Finding(
                    severity = Severity.HIGH,
                    ruleId = "sensitive_exfil",
                    title = "敏感数据可能外发",
                    detail = "读了" + readKinds.joinToString("、") { it.first } + "，同时会联网",
                    line = readKinds.first().second,
                ),
            )
        } else if (readKinds.isNotEmpty()) {
            out.add(
                Finding(
                    severity = Severity.MEDIUM,
                    ruleId = "read_sensitive",
                    title = "读取手机敏感数据",
                    detail = "读了" + readKinds.joinToString("、") { it.first } + "，但没发现联网",
                    line = readKinds.first().second,
                ),
            )
        }
        if (s.execCode > 0 && s.network > 0) {
            out.add(
                Finding(
                    severity = Severity.HIGH,
                    ruleId = "remote_code_exec",
                    title = "联网后执行代码",
                    detail = "联网的同时用了 eval/exec/system 这类执行代码的写法，服务端能下发代码跑",
                    line = s.execCode,
                ),
            )
        } else if (s.execCode > 0) {
            out.add(
                Finding(
                    severity = Severity.MEDIUM,
                    ruleId = "exec_local",
                    title = "执行本地代码/命令",
                    detail = "用了 eval/exec/system 这类写法（只在本地跑，没发现联网）",
                    line = s.execCode,
                ),
            )
        }
        if (s.dynamicCode > 0) {
            out.add(
                Finding(
                    severity = Severity.MEDIUM,
                    ruleId = "dynamic_code",
                    title = "动态加载代码/本地库",
                    detail = "用了 DexClassLoader / loadLibrary，运行时能再加载别的代码",
                    line = s.dynamicCode,
                ),
            )
        }
        if (s.download > 0 && s.writeCodeFile > 0) {
            out.add(
                Finding(
                    severity = Severity.HIGH,
                    ruleId = "self_update",
                    title = "下载后写代码文件（云端自更新）",
                    detail = "从网上下载东西并写成 .py/.js/.php/.jar/.dex 代码文件，源作者能悄悄换掉规则",
                    line = s.writeCodeFile,
                ),
            )
        }
        if (s.installApk > 0) {
            out.add(
                Finding(
                    severity = Severity.HIGH,
                    ruleId = "install_apk",
                    title = "尝试安装应用包",
                    detail = "调了安装应用的接口，源能往手机上装东西",
                    line = s.installApk,
                ),
            )
        }
        if (jarMode) {
            // JAR 扫描不走行号，统一为 0（上面 findFirst 在 dex 文本上
            // 的行号没有意义）。
            return out.map { it.copy(line = 0) }
        }
        return out
    }

    /** 扫文本源（.js/.py/.php）。 */
    fun scanText(content: String): Report {
        return Report(findingsFromSignals(collectSignals(content), jarMode = false))
    }

    /**
     * 扫 JAR 字节：解 zip，读 classes*.dex 的字符串池（按 ISO-8859-1 解，
     * dex 字符串在字节流里是原文），用同一套信号判定；.so 条目另记中危。
     * 不是 zip / 解不开时回一个中危"无法扫描"，让调用方知道这次没扫成。
     */
    fun scanJar(bytes: ByteArray): Report {
        val findings = ArrayList<Finding>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                var entry = zin.nextEntry
                var sawDex = false
                while (entry != null) {
                    if (!entry.isDirectory) {
                        when {
                            entry.name.endsWith(".so") -> findings.add(
                                Finding(
                                    severity = Severity.MEDIUM,
                                    ruleId = "native_lib",
                                    title = "含本地库（.so）",
                                    detail = "带了 " + entry.name + "，本地代码不受规则层约束",
                                ),
                            )
                            entry.name.matches(Regex("""classes\d*\.dex""")) -> {
                                sawDex = true
                                val dexText = zin.readBytes().toString(Charsets.ISO_8859_1)
                                findings.addAll(findingsFromSignals(collectSignals(dexText), jarMode = true))
                            }
                        }
                    }
                    entry = zin.nextEntry
                }
                if (!sawDex && findings.none { it.ruleId == "native_lib" }) {
                    findings.add(
                        Finding(
                            severity = Severity.MEDIUM,
                            ruleId = "jar_unscannable",
                            title = "JAR 里没有可扫的 dex",
                            detail = "没找到 classes.dex，没扫出东西不等于安全",
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            return Report(
                listOf(
                    Finding(
                        severity = Severity.MEDIUM,
                        ruleId = "jar_unscannable",
                        title = "JAR 解不开，没扫成",
                        detail = "不是有效的 zip/JAR：" + (e.message ?: ""),
                    ),
                ),
            )
        }
        return Report(findings)
    }
}
